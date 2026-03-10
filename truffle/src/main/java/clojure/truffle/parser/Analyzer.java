package clojure.truffle.parser;

import clojure.lang.*;
import clojure.truffle.ClojureContext;
import clojure.truffle.ClojureTruffleLanguage;
import clojure.truffle.nodes.*;
import clojure.truffle.nodes.interop.*;
import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.ClojureNil;
import clojure.truffle.runtime.MultiArityFunction;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.FrameSlotKind;

import java.io.PushbackReader;
import java.io.StringReader;
import java.util.*;

public class Analyzer {

    private final ClojureTruffleLanguage language;
    private ClojureContext context;
    private Scope currentScope;

    private static final Object EOF = new Object();

    // --- Scope ---

    static class CaptureEntry {
        final int outerSlot;
        final int innerSlot;
        CaptureEntry(int outerSlot, int innerSlot) {
            this.outerSlot = outerSlot;
            this.innerSlot = innerSlot;
        }
    }

    static class Scope {
        final Scope parent;
        final Map<String, Integer> locals = new LinkedHashMap<>();
        final FrameDescriptor.Builder frameBuilder;
        final List<CaptureEntry> captures = new ArrayList<>();

        Scope(Scope parent) {
            this.parent = parent;
            this.frameBuilder = FrameDescriptor.newBuilder();
        }

        int addLocal(String name) {
            int slot = frameBuilder.addSlot(FrameSlotKind.Object, name, null);
            locals.put(name, slot);
            return slot;
        }

        Integer findLocal(String name) {
            Integer slot = locals.get(name);
            if (slot != null) return slot;
            if (parent != null) {
                Integer parentSlot = parent.findLocal(name);
                if (parentSlot != null) {
                    int captureSlot = addLocal(name);
                    captures.add(new CaptureEntry(parentSlot, captureSlot));
                    return captureSlot;
                }
            }
            return null;
        }

        FrameDescriptor buildDescriptor() { return frameBuilder.build(); }
        int[] getOuterCaptureSlots() { return captures.stream().mapToInt(c -> c.outerSlot).toArray(); }
        int[] getInnerCaptureSlots() { return captures.stream().mapToInt(c -> c.innerSlot).toArray(); }
    }

    public Analyzer(ClojureTruffleLanguage language) {
        this.language = language;
    }

    public void setContext(ClojureContext context) { this.context = context; }
    public FrameDescriptor getFrameDescriptor() { return currentScope.buildDescriptor(); }

    // --- Public API ---

    public ExpressionNode[] analyzeProgram(String source) {
        currentScope = new Scope(null);
        List<Object> forms = readAll(source);
        ExpressionNode[] nodes = new ExpressionNode[forms.size()];
        for (int i = 0; i < forms.size(); i++) {
            nodes[i] = analyze(forms.get(i));
        }
        return nodes;
    }

    public ExpressionNode analyzeForm(Object form) {
        if (currentScope == null) currentScope = new Scope(null);
        return analyze(form);
    }

    // --- Reader ---

    private List<Object> readAll(String source) {
        List<Object> forms = new ArrayList<>();
        PushbackReader reader = new PushbackReader(new StringReader(source), 2);
        try {
            while (true) {
                Object form = LispReader.read(reader, false, EOF, false, null);
                if (form == EOF) break;
                forms.add(form);
            }
        } catch (Exception e) {
            throw new RuntimeException("Read error: " + e.getMessage(), e);
        }
        return forms;
    }

    // --- Core Analyzer ---

    private ExpressionNode analyze(Object form) {
        if (form == null) return new NilNode();
        if (form instanceof Long l) return new LongLiteralNode(l);
        if (form instanceof Integer i) return new LongLiteralNode(i.longValue());
        if (form instanceof Double d) return new DoubleLiteralNode(d);
        if (form instanceof Float f) return new DoubleLiteralNode(f.doubleValue());
        if (form instanceof String s) return new StringLiteralNode(s);
        if (form instanceof Boolean b) return new BooleanLiteralNode(b);
        if (form instanceof Keyword kw) return new KeywordLiteralNode(kw);
        if (form instanceof Symbol sym) return analyzeSymbol(sym);
        if (form instanceof ISeq seq) return analyzeList(seq);
        if (form instanceof IPersistentVector vec) return analyzeVector(vec);
        if (form instanceof IPersistentMap map) return analyzeMap(map);
        return new QuoteNode(form);
    }

    private ExpressionNode analyzeSymbol(Symbol sym) {
        String name = sym.getName();
        String ns = sym.getNamespace();
        if (ns == null) {
            switch (name) {
                case "nil": return new NilNode();
                case "true": return new BooleanLiteralNode(true);
                case "false": return new BooleanLiteralNode(false);
            }
        }
        Integer slot = (ns == null) ? currentScope.findLocal(name) : null;
        if (slot != null) return new ReadLocalNode(slot);
        // Static field access: Class/FIELD (outside call position)
        if (ns != null && isJavaClassName(ns)) {
            try {
                Class<?> clazz = JavaInteropUtil.resolveClass(ns);
                return new JavaStaticFieldNode(clazz, name);
            } catch (RuntimeException ignored) {
                // Not a Java class, fall through to var lookup
            }
        }
        String varName = ns != null ? ns + "/" + name : name;
        return new SymbolNode(context, varName);
    }

    private ExpressionNode analyzeList(ISeq seq) {
        if (seq == null) return new QuoteNode(PersistentList.EMPTY);
        Object first = seq.first();

        if (first instanceof Symbol sym) {
            String name = sym.getName();
            String ns = sym.getNamespace();

            // Check for macros first
            if (ns == null && context != null) {
                Object macro = context.getMacro(name);
                if (macro != null) {
                    return expandAndAnalyzeMacro(macro, seq.next());
                }
            }

            if (ns == null) {
                // Instance method: (.method obj args...)
                if (name.startsWith(".") && name.length() > 1 && !name.equals("..")) {
                    return analyzeInstanceMethod(name.substring(1), seq.next());
                }
                // Constructor: (ClassName. args...)
                if (name.endsWith(".") && name.length() > 1) {
                    String className = name.substring(0, name.length() - 1);
                    return analyzeConstructor(className, seq.next());
                }
                switch (name) {
                    case "if":          return analyzeIf(seq);
                    case "do":          return analyzeDo(seq);
                    case "def":         return analyzeDef(seq);
                    case "let*": case "let": return analyzeLet(seq);
                    case "fn*": case "fn": return analyzeFn(seq);
                    case "quote":       return analyzeQuote(seq);
                    case "recur":       return analyzeRecur(seq);
                    case "loop*": case "loop": return analyzeLoop(seq);
                    case "and":         return analyzeAnd(seq);
                    case "or":          return analyzeOr(seq);
                    case "when":        return analyzeWhen(seq);
                    case "cond":        return analyzeCond(seq);
                    case "defn":        return analyzeDefn(seq);
                    case "try":         return analyzeTry(seq);
                    case "throw":       return analyzeThrow(seq);
                    case "defmacro":    return analyzeDefmacro(seq);
                    case "macroexpand": return analyzeMacroexpand(seq);
                    case "new":         return analyzeNew(seq);
                    case "lazy-seq":    return analyzeLazySeq(seq);
                    case "do-template": return analyzeDo(seq); // fallback
                }
            }
            // Static method call: (Class/method args...)
            if (ns != null && isJavaClassName(ns)) {
                return analyzeStaticCall(ns, name, seq.next());
            }
        }
        return analyzeInvoke(seq);
    }

    // --- Macro expansion ---

    private ExpressionNode expandAndAnalyzeMacro(Object macro, ISeq argForms) {
        List<Object> rawArgs = new ArrayList<>();
        while (argForms != null) {
            rawArgs.add(argForms.first());
            argForms = argForms.next();
        }
        Object expanded = context.callFunction(macro, rawArgs.toArray());
        return analyze(expanded);
    }

    private ExpressionNode analyzeDefmacro(ISeq seq) {
        // (defmacro name [params] body...)
        ISeq args = seq.next();
        if (args == null) throw err("defmacro: missing name");
        if (!(args.first() instanceof Symbol)) throw err("defmacro: name must be a symbol");
        String macroName = ((Symbol) args.first()).getName();

        // Build (fn* name [params] body...) and compile it
        ISeq fnForm = RT.cons(Symbol.intern("fn*"), args);
        ExpressionNode fnNode = analyzeFn(fnForm);

        // Execute immediately to get the function
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        EvalRootNode evalRoot = new EvalRootNode(language, fd, new ExpressionNode[]{fnNode});
        Object result = evalRoot.getCallTarget().call();

        context.setMacro(macroName, result);
        return new NilNode();
    }

    private ExpressionNode analyzeMacroexpand(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("macroexpand: missing form");
        // Return the quoted expanded form (for debugging)
        return analyze(args.first());
    }

    // --- Lazy Seq ---

    private ExpressionNode analyzeLazySeq(ISeq seq) {
        // (lazy-seq body...) -> wrap body in (fn* [] body...) then LazySeqNode
        ISeq body = seq.next();
        // Build: (fn* [] body...)
        ISeq fnForm = RT.cons(Symbol.intern("fn*"),
                RT.cons(PersistentVector.EMPTY, body));
        ExpressionNode thunkNode = analyzeFn(fnForm);
        return new LazySeqNode(thunkNode);
    }

    // --- Java Interop ---

    private boolean isJavaClassName(String name) {
        // Heuristic: starts with uppercase, or contains '.' (like java.util.List)
        if (name.isEmpty()) return false;
        if (name.contains(".")) return true;
        return Character.isUpperCase(name.charAt(0));
    }

    private ExpressionNode analyzeInstanceMethod(String methodName, ISeq args) {
        if (args == null) throw err(".method: missing target object");
        ExpressionNode target = analyze(args.first());
        args = args.next();
        List<ExpressionNode> argList = new ArrayList<>();
        while (args != null) { argList.add(analyze(args.first())); args = args.next(); }
        return new JavaInstanceMethodNode(methodName, target, argList.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeStaticCall(String className, String memberName, ISeq args) {
        Class<?> clazz = JavaInteropUtil.resolveClass(className);
        List<ExpressionNode> argList = new ArrayList<>();
        while (args != null) { argList.add(analyze(args.first())); args = args.next(); }
        if (argList.isEmpty()) {
            // Could be static field or zero-arg method - try field first
            try {
                clazz.getField(memberName);
                return new JavaStaticFieldNode(clazz, memberName);
            } catch (NoSuchFieldException ignored) {
                // Fall through to static method
            }
        }
        return new JavaStaticMethodNode(clazz, memberName, argList.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeConstructor(String className, ISeq args) {
        Class<?> clazz = JavaInteropUtil.resolveClass(className);
        List<ExpressionNode> argList = new ArrayList<>();
        while (args != null) { argList.add(analyze(args.first())); args = args.next(); }
        return new JavaConstructorNode(clazz, argList.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeNew(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("new: missing class name");
        Object classForm = args.first();
        if (!(classForm instanceof Symbol classSym))
            throw err("new: class name must be a symbol");
        return analyzeConstructor(classSym.getName(), args.next());
    }

    // --- Destructuring ---

    private void expandDestructuring(Object bindingForm, int sourceSlot,
                                     List<Integer> slots, List<ExpressionNode> values) {
        if (bindingForm instanceof IPersistentVector vec) {
            expandVectorDestructuring(vec, sourceSlot, slots, values);
        } else if (bindingForm instanceof IPersistentMap map) {
            expandMapDestructuring(map, sourceSlot, slots, values);
        } else {
            throw err("Unsupported destructuring form: " + bindingForm);
        }
    }

    private void expandVectorDestructuring(IPersistentVector pattern, int sourceSlot,
                                           List<Integer> slots, List<ExpressionNode> values) {
        // [a b & rest :as all]
        int positionalIndex = 0;
        for (int i = 0; i < pattern.count(); i++) {
            Object elem = pattern.nth(i);
            if (elem instanceof Symbol sym && sym.getName().equals("&")) {
                // Variadic: next element gets the rest
                i++;
                if (i >= pattern.count()) throw err("destructuring: missing name after &");
                Object restForm = pattern.nth(i);
                ExpressionNode restExpr = makeNthnextNode(sourceSlot, positionalIndex);
                if (restForm instanceof Symbol restSym) {
                    slots.add(currentScope.addLocal(restSym.getName()));
                    values.add(restExpr);
                } else {
                    int tmpSlot = currentScope.addLocal("__rest_tmp_" + positionalIndex);
                    slots.add(tmpSlot);
                    values.add(restExpr);
                    expandDestructuring(restForm, tmpSlot, slots, values);
                }
            } else if ((elem instanceof Keyword kw && kw.getName().equals("as")) ||
                       (elem instanceof Symbol sym2 && sym2.getName().equals(":as"))) {
                // :as binds the whole collection
                i++;
                if (i >= pattern.count()) throw err("destructuring: missing name after :as");
                Symbol asSym = (Symbol) pattern.nth(i);
                slots.add(currentScope.addLocal(asSym.getName()));
                values.add(new ReadLocalNode(sourceSlot));
            } else if (elem instanceof Symbol sym) {
                // Simple positional binding
                slots.add(currentScope.addLocal(sym.getName()));
                values.add(makeNthNode(sourceSlot, positionalIndex));
                positionalIndex++;
            } else {
                // Nested destructuring
                int tmpSlot = currentScope.addLocal("__vec_tmp_" + positionalIndex);
                slots.add(tmpSlot);
                values.add(makeNthNode(sourceSlot, positionalIndex));
                expandDestructuring(elem, tmpSlot, slots, values);
                positionalIndex++;
            }
        }
    }

    private void expandMapDestructuring(IPersistentMap pattern, int sourceSlot,
                                        List<Integer> slots, List<ExpressionNode> values) {
        // {:keys [a b] :strs [c] :or {a 1} :as all}
        // or {localName :mapKey, ...}
        Object keysVec = pattern.valAt(Keyword.intern("keys"));
        Object strsVec = pattern.valAt(Keyword.intern("strs"));
        Object orMap = pattern.valAt(Keyword.intern("or"));
        Object asName = pattern.valAt(Keyword.intern("as"));
        IPersistentMap defaults = (orMap instanceof IPersistentMap m) ? m : null;

        // :keys [a b] -> bind a to (:a source), b to (:b source)
        if (keysVec instanceof IPersistentVector kv) {
            for (int i = 0; i < kv.count(); i++) {
                Symbol sym = (Symbol) kv.nth(i);
                Keyword key = Keyword.intern(sym.getName());
                ExpressionNode getExpr = makeGetNode(sourceSlot, key, defaults, sym);
                slots.add(currentScope.addLocal(sym.getName()));
                values.add(getExpr);
            }
        }

        // :strs [a b] -> bind a to ("a" source), b to ("b" source)
        if (strsVec instanceof IPersistentVector sv) {
            for (int i = 0; i < sv.count(); i++) {
                Symbol sym = (Symbol) sv.nth(i);
                String key = sym.getName();
                ExpressionNode getExpr = makeGetNodeStr(sourceSlot, key, defaults, sym);
                slots.add(currentScope.addLocal(sym.getName()));
                values.add(getExpr);
            }
        }

        // Explicit bindings: {localName :mapKey}
        for (ISeq s = pattern.seq(); s != null; s = s.next()) {
            IMapEntry entry = (IMapEntry) s.first();
            Object k = entry.key();
            Object v = entry.val();
            // Skip :keys, :strs, :or, :as
            if (k instanceof Keyword kw) {
                String kwName = kw.getName();
                if (kwName.equals("keys") || kwName.equals("strs") || kwName.equals("syms") ||
                        kwName.equals("or") || kwName.equals("as"))
                    continue;
            }
            // {localSym :key} or {localSym "key"}
            if (k instanceof Symbol localSym) {
                ExpressionNode getExpr;
                if (v instanceof Keyword kw) {
                    getExpr = makeGetNode(sourceSlot, kw, defaults, localSym);
                } else {
                    getExpr = makeGetNodeObj(sourceSlot, v, defaults, localSym);
                }
                if (localSym.getName().equals("_")) continue; // skip _ bindings
                slots.add(currentScope.addLocal(localSym.getName()));
                values.add(getExpr);
            }
        }

        // :as binds the whole map
        if (asName instanceof Symbol asSym) {
            slots.add(currentScope.addLocal(asSym.getName()));
            values.add(new ReadLocalNode(sourceSlot));
        }
    }

    // Helper: (nth source index)
    private ExpressionNode makeNthNode(int sourceSlot, int index) {
        return new InvokeNode(
                new SymbolNode(context, "nth"),
                new ExpressionNode[]{new ReadLocalNode(sourceSlot), new LongLiteralNode(index)});
    }

    // Helper: (nthnext source index) or (drop index source)
    private ExpressionNode makeNthnextNode(int sourceSlot, int index) {
        return new InvokeNode(
                new SymbolNode(context, "drop"),
                new ExpressionNode[]{new LongLiteralNode(index), new ReadLocalNode(sourceSlot)});
    }

    // Helper: (get source key) or (get source key default)
    private ExpressionNode makeGetNode(int sourceSlot, Keyword key, IPersistentMap defaults, Symbol sym) {
        ExpressionNode[] getArgs;
        Object defaultVal = defaults != null ? defaults.valAt(sym) : null;
        if (defaultVal != null) {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new KeywordLiteralNode(key), analyze(defaultVal)};
        } else {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new KeywordLiteralNode(key)};
        }
        return new InvokeNode(new SymbolNode(context, "get"), getArgs);
    }

    private ExpressionNode makeGetNodeStr(int sourceSlot, String key, IPersistentMap defaults, Symbol sym) {
        ExpressionNode[] getArgs;
        Object defaultVal = defaults != null ? defaults.valAt(sym) : null;
        if (defaultVal != null) {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new StringLiteralNode(key), analyze(defaultVal)};
        } else {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new StringLiteralNode(key)};
        }
        return new InvokeNode(new SymbolNode(context, "get"), getArgs);
    }

    private ExpressionNode makeGetNodeObj(int sourceSlot, Object key, IPersistentMap defaults, Symbol sym) {
        ExpressionNode[] getArgs;
        Object defaultVal = defaults != null ? defaults.valAt(sym) : null;
        if (defaultVal != null) {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), analyze(key), analyze(defaultVal)};
        } else {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), analyze(key)};
        }
        return new InvokeNode(new SymbolNode(context, "get"), getArgs);
    }

    // --- Special Forms ---

    private ExpressionNode analyzeIf(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("if: missing condition");
        ExpressionNode cond = analyze(args.first());
        args = args.next();
        if (args == null) throw err("if: missing then branch");
        ExpressionNode then = analyze(args.first());
        args = args.next();
        ExpressionNode elseNode = (args != null) ? analyze(args.first()) : new NilNode();
        return new IfNode(cond, then, elseNode);
    }

    private ExpressionNode analyzeDo(ISeq seq) {
        ISeq args = seq.next();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) { nodes.add(analyze(args.first())); args = args.next(); }
        if (nodes.isEmpty()) return new NilNode();
        if (nodes.size() == 1) return nodes.get(0);
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeDef(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("def: missing name");
        if (!(args.first() instanceof Symbol)) throw err("def: name must be a symbol");
        String name = ((Symbol) args.first()).getName();
        args = args.next();
        ExpressionNode valueNode = (args != null) ? analyze(args.first()) : new NilNode();
        return new DefNode(context, name, valueNode);
    }

    private ExpressionNode analyzeLet(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("let*: missing bindings");
        if (!(args.first() instanceof IPersistentVector))
            throw err("let*: bindings must be a vector");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() % 2 != 0) throw err("let*: odd number of binding forms");

        List<Integer> slotList = new ArrayList<>();
        List<ExpressionNode> valueList = new ArrayList<>();
        for (int i = 0; i < bindings.count(); i += 2) {
            Object bindingForm = bindings.nth(i);
            ExpressionNode initExpr = analyze(bindings.nth(i + 1));
            if (bindingForm instanceof Symbol sym) {
                slotList.add(currentScope.addLocal(sym.getName()));
                valueList.add(initExpr);
            } else {
                // Destructuring: store init in temp, then expand
                int tempSlot = currentScope.addLocal("__destructure_tmp_" + i);
                slotList.add(tempSlot);
                valueList.add(initExpr);
                expandDestructuring(bindingForm, tempSlot, slotList, valueList);
            }
        }
        int[] slots = slotList.stream().mapToInt(Integer::intValue).toArray();
        ExpressionNode[] values = valueList.toArray(new ExpressionNode[0]);
        return new LetNode(slots, values, analyzeBody(args.next()));
    }

    private ExpressionNode analyzeFn(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("fn*: missing parameters");

        String fnName = null;
        if (args.first() instanceof Symbol) {
            fnName = ((Symbol) args.first()).getName();
            args = args.next();
            if (args == null) throw err("fn*: missing parameters after name");
        }

        // Multi-arity: first arg is a list, not a vector
        if (args.first() instanceof ISeq) {
            return analyzeMultiArityFn(fnName, args);
        }

        // Single arity
        return analyzeSingleArityFn(fnName, args);
    }

    private ExpressionNode analyzeSingleArityFn(String fnName, ISeq args) {
        if (!(args.first() instanceof IPersistentVector))
            throw err("fn*: parameters must be a vector");
        IPersistentVector params = (IPersistentVector) args.first();

        Scope outerScope = currentScope;
        currentScope = new Scope(outerScope);

        List<Integer> destructSlots = new ArrayList<>();
        List<ExpressionNode> destructValues = new ArrayList<>();

        int[] result = parseParams(params, destructSlots, destructValues);
        int[] paramSlots = Arrays.copyOf(result, result.length - 1);
        int variadicSlot = result[result.length - 1];

        ExpressionNode bodyNode = analyzeBody(args.next());

        // Wrap body with destructuring bindings if any
        if (!destructSlots.isEmpty()) {
            int[] dSlots = destructSlots.stream().mapToInt(Integer::intValue).toArray();
            ExpressionNode[] dValues = destructValues.toArray(new ExpressionNode[0]);
            bodyNode = new LetNode(dSlots, dValues, bodyNode);
        }

        int[] outerCaptureSlots = currentScope.getOuterCaptureSlots();
        int[] innerCaptureSlots = currentScope.getInnerCaptureSlots();
        FrameDescriptor fd = currentScope.buildDescriptor();

        FnBodyNode fnBody = new FnBodyNode(language, fd, fnName,
                paramSlots, variadicSlot, innerCaptureSlots, bodyNode);

        currentScope = outerScope;
        return new FnNode(fnName, fnBody.getCallTarget(), outerCaptureSlots);
    }

    private ExpressionNode analyzeMultiArityFn(String fnName, ISeq clauses) {
        Scope outerScope = currentScope;
        List<FnNode> arityFnNodes = new ArrayList<>();
        List<Integer> arities = new ArrayList<>();
        int variadicIndex = -1;

        while (clauses != null) {
            Object clause = clauses.first();
            if (!(clause instanceof ISeq clauseSeq))
                throw err("fn*: arity clause must be a list");

            if (!(clauseSeq.first() instanceof IPersistentVector))
                throw err("fn*: arity parameters must be a vector");
            IPersistentVector params = (IPersistentVector) clauseSeq.first();
            ISeq body = clauseSeq.next();

            currentScope = new Scope(outerScope);

            List<Integer> dSlots = new ArrayList<>();
            List<ExpressionNode> dVals = new ArrayList<>();
            int[] result = parseParams(params, dSlots, dVals);
            int[] paramSlots = Arrays.copyOf(result, result.length - 1);
            int varSlot = result[result.length - 1];

            ExpressionNode bodyNode = analyzeBody(body);
            if (!dSlots.isEmpty()) {
                bodyNode = new LetNode(
                        dSlots.stream().mapToInt(Integer::intValue).toArray(),
                        dVals.toArray(new ExpressionNode[0]), bodyNode);
            }

            int[] outerCaptures = currentScope.getOuterCaptureSlots();
            int[] innerCaptures = currentScope.getInnerCaptureSlots();
            FrameDescriptor fd = currentScope.buildDescriptor();

            FnBodyNode fnBody = new FnBodyNode(language, fd, fnName,
                    paramSlots, varSlot, innerCaptures, bodyNode);
            FnNode fnNode = new FnNode(fnName, fnBody.getCallTarget(), outerCaptures);
            arityFnNodes.add(fnNode);

            if (varSlot >= 0) {
                variadicIndex = arities.size();
            }
            arities.add(paramSlots.length);

            clauses = clauses.next();
        }

        currentScope = outerScope;
        return new MultiArityFnNode(fnName,
                arityFnNodes.toArray(new FnNode[0]),
                arities.stream().mapToInt(Integer::intValue).toArray(),
                variadicIndex);
    }

    /**
     * Parse parameters, returning array where last element is variadic slot (-1 if none).
     * destructBindings is populated with any destructuring that needs to happen.
     */
    private int[] parseParams(IPersistentVector params) {
        return parseParams(params, null, null);
    }

    private int[] parseParams(IPersistentVector params,
                              List<Integer> destructSlots,
                              List<ExpressionNode> destructValues) {
        List<Integer> positional = new ArrayList<>();
        int varSlot = -1;
        for (int i = 0; i < params.count(); i++) {
            Object param = params.nth(i);
            if (param instanceof Symbol sym && "&".equals(sym.getName())) {
                i++;
                if (i >= params.count()) throw err("fn*: missing parameter after &");
                Object varParam = params.nth(i);
                if (varParam instanceof Symbol varSym) {
                    varSlot = currentScope.addLocal(varSym.getName());
                } else {
                    // Destructured variadic param
                    String tmpName = "__var_destructure_" + i;
                    varSlot = currentScope.addLocal(tmpName);
                    if (destructSlots != null) {
                        expandDestructuring(varParam, varSlot, destructSlots, destructValues);
                    }
                }
            } else if (param instanceof Symbol sym) {
                positional.add(currentScope.addLocal(sym.getName()));
            } else {
                // Destructured positional param
                String tmpName = "__param_destructure_" + i;
                int tmpSlot = currentScope.addLocal(tmpName);
                positional.add(tmpSlot);
                if (destructSlots != null) {
                    expandDestructuring(param, tmpSlot, destructSlots, destructValues);
                }
            }
        }
        int[] result = new int[positional.size() + 1];
        for (int i = 0; i < positional.size(); i++) result[i] = positional.get(i);
        result[positional.size()] = varSlot;
        return result;
    }

    private ExpressionNode analyzeQuote(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("quote: missing form");
        return new QuoteNode(convertToRuntime(args.first()));
    }

    private ExpressionNode analyzeRecur(ISeq seq) {
        ISeq args = seq.next();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) { nodes.add(analyze(args.first())); args = args.next(); }
        return new RecurNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeLoop(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("loop*: missing bindings");
        if (!(args.first() instanceof IPersistentVector))
            throw err("loop*: bindings must be a vector");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() % 2 != 0) throw err("loop*: odd number of binding forms");

        int count = bindings.count() / 2;
        int[] slots = new int[count];
        ExpressionNode[] values = new ExpressionNode[count];
        for (int i = 0; i < count; i++) {
            if (!(bindings.nth(i * 2) instanceof Symbol))
                throw err("loop*: binding name must be a symbol");
            slots[i] = currentScope.addLocal(((Symbol) bindings.nth(i * 2)).getName());
            values[i] = analyze(bindings.nth(i * 2 + 1));
        }
        return new LoopNode(slots, values, analyzeBody(args.next()));
    }

    private ExpressionNode analyzeAnd(ISeq seq) {
        return new AndNode(analyzeArgList(seq.next()));
    }

    private ExpressionNode analyzeOr(ISeq seq) {
        return new OrNode(analyzeArgList(seq.next()));
    }

    private ExpressionNode analyzeWhen(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("when: missing condition");
        return new IfNode(analyze(args.first()), analyzeBody(args.next()), new NilNode());
    }

    private ExpressionNode analyzeCond(ISeq seq) {
        return buildCondChain(seq.next());
    }

    private ExpressionNode buildCondChain(ISeq pairs) {
        if (pairs == null) return new NilNode();
        Object test = pairs.first();
        ISeq rest = pairs.next();
        if (rest == null) throw err("cond: odd number of forms");
        ExpressionNode thenNode = analyze(rest.first());
        ExpressionNode elseNode = buildCondChain(rest.next());
        if (test instanceof Keyword kw && "else".equals(kw.getName())) return thenNode;
        return new IfNode(analyze(test), thenNode, elseNode);
    }

    private ExpressionNode analyzeDefn(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("defn: missing name");
        if (!(args.first() instanceof Symbol)) throw err("defn: name must be a symbol");
        Symbol nameSym = (Symbol) args.first();
        ISeq fnForm = RT.cons(Symbol.intern("fn*"), args);
        ExpressionNode fnNode = analyzeFn(fnForm);
        return new DefNode(context, nameSym.getName(), fnNode);
    }

    // --- try/catch/throw ---

    private ExpressionNode analyzeTry(ISeq seq) {
        ISeq args = seq.next();
        List<ExpressionNode> bodyForms = new ArrayList<>();
        List<CatchHandlerNode> catchHandlers = new ArrayList<>();
        ExpressionNode finallyNode = null;

        while (args != null) {
            Object form = args.first();
            if (form instanceof ISeq formSeq) {
                Object head = formSeq.first();
                if (head instanceof Symbol s) {
                    if ("catch".equals(s.getName())) {
                        catchHandlers.add(analyzeCatch(formSeq));
                        args = args.next();
                        continue;
                    }
                    if ("finally".equals(s.getName())) {
                        finallyNode = analyzeBody(formSeq.next());
                        args = args.next();
                        continue;
                    }
                }
            }
            bodyForms.add(analyze(form));
            args = args.next();
        }

        ExpressionNode body = bodyForms.size() == 1 ? bodyForms.get(0) :
                new DoNode(bodyForms.toArray(new ExpressionNode[0]));

        return new TryNode(body,
                catchHandlers.toArray(new CatchHandlerNode[0]),
                finallyNode);
    }

    private CatchHandlerNode analyzeCatch(ISeq seq) {
        // (catch ExceptionType e body...)
        ISeq args = seq.next();
        if (args == null) throw err("catch: missing exception type");

        Class<? extends Throwable> exClass = resolveExceptionClass(args.first());
        args = args.next();
        if (args == null) throw err("catch: missing binding name");
        if (!(args.first() instanceof Symbol))
            throw err("catch: binding must be a symbol");

        int slot = currentScope.addLocal(((Symbol) args.first()).getName());
        ExpressionNode handler = analyzeBody(args.next());
        return new CatchHandlerNode(exClass, slot, handler);
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Throwable> resolveExceptionClass(Object name) {
        String className;
        if (name instanceof Symbol s) {
            className = s.getName();
        } else {
            className = name.toString();
        }
        return switch (className) {
            case "Exception" -> Exception.class;
            case "RuntimeException" -> RuntimeException.class;
            case "Throwable" -> Throwable.class;
            case "Error" -> Error.class;
            case "ArithmeticException" -> ArithmeticException.class;
            case "NullPointerException" -> NullPointerException.class;
            case "IndexOutOfBoundsException" -> IndexOutOfBoundsException.class;
            case "IllegalArgumentException" -> IllegalArgumentException.class;
            default -> {
                try {
                    yield (Class<? extends Throwable>) Class.forName(className);
                } catch (ClassNotFoundException e) {
                    yield RuntimeException.class;
                }
            }
        };
    }

    private ExpressionNode analyzeThrow(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("throw: missing expression");
        return new ThrowNode(analyze(args.first()));
    }

    // --- Invoke ---

    private ExpressionNode analyzeInvoke(ISeq seq) {
        ExpressionNode fn = analyze(seq.first());
        return new InvokeNode(fn, analyzeArgList(seq.next()));
    }

    // --- Collections ---

    private ExpressionNode analyzeVector(IPersistentVector vec) {
        ExpressionNode[] elements = new ExpressionNode[vec.count()];
        for (int i = 0; i < vec.count(); i++) elements[i] = analyze(vec.nth(i));
        return new VectorNode(elements);
    }

    private ExpressionNode analyzeMap(IPersistentMap map) {
        List<ExpressionNode> kvNodes = new ArrayList<>();
        for (ISeq s = map.seq(); s != null; s = s.next()) {
            IMapEntry entry = (IMapEntry) s.first();
            kvNodes.add(analyze(entry.key()));
            kvNodes.add(analyze(entry.val()));
        }
        return new MapNode(kvNodes.toArray(new ExpressionNode[0]));
    }

    // --- Helpers ---

    private ExpressionNode analyzeBody(ISeq body) {
        if (body == null) return new NilNode();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (body != null) { nodes.add(analyze(body.first())); body = body.next(); }
        if (nodes.size() == 1) return nodes.get(0);
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode[] analyzeArgList(ISeq args) {
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) { nodes.add(analyze(args.first())); args = args.next(); }
        return nodes.toArray(new ExpressionNode[0]);
    }

    private Object convertToRuntime(Object form) {
        return form == null ? ClojureNil.INSTANCE : form;
    }

    private static RuntimeException err(String msg) {
        return new RuntimeException(msg);
    }
}
