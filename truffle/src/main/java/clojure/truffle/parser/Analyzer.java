package clojure.truffle.parser;

import clojure.lang.*;
import clojure.truffle.ClojureContext;
import clojure.truffle.ClojureTruffleLanguage;
import clojure.truffle.nodes.*;
import clojure.truffle.runtime.ClojureNil;
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

    // --- Scope for variable resolution and closure tracking ---

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

        FrameDescriptor buildDescriptor() {
            return frameBuilder.build();
        }

        int[] getOuterCaptureSlots() {
            return captures.stream().mapToInt(c -> c.outerSlot).toArray();
        }

        int[] getInnerCaptureSlots() {
            return captures.stream().mapToInt(c -> c.innerSlot).toArray();
        }
    }

    public Analyzer(ClojureTruffleLanguage language) {
        this.language = language;
    }

    public void setContext(ClojureContext context) {
        this.context = context;
    }

    public FrameDescriptor getFrameDescriptor() {
        return currentScope.buildDescriptor();
    }

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

    /**
     * Analyze a single form (used by eval).
     */
    public ExpressionNode analyzeForm(Object form) {
        if (currentScope == null) {
            currentScope = new Scope(null);
        }
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

    // --- Analyzer ---

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

        String varName = ns != null ? ns + "/" + name : name;
        return new SymbolNode(context, varName);
    }

    private ExpressionNode analyzeList(ISeq seq) {
        if (seq == null) return new QuoteNode(PersistentList.EMPTY);

        Object first = seq.first();

        if (first instanceof Symbol sym) {
            String name = sym.getName();
            String ns = sym.getNamespace();
            if (ns == null) {
                switch (name) {
                    case "if":      return analyzeIf(seq);
                    case "do":      return analyzeDo(seq);
                    case "def":     return analyzeDef(seq);
                    case "let*":    return analyzeLet(seq);
                    case "fn*":     return analyzeFn(seq);
                    case "quote":   return analyzeQuote(seq);
                    case "recur":   return analyzeRecur(seq);
                    case "loop*":   return analyzeLoop(seq);
                    case "and":     return analyzeAnd(seq);
                    case "or":      return analyzeOr(seq);
                    // Compiler macros
                    case "when":    return analyzeWhen(seq);
                    case "cond":    return analyzeCond(seq);
                    case "defn":    return analyzeDefn(seq);
                    case "let":     return analyzeLet(seq);   // alias
                    case "loop":    return analyzeLoop(seq);  // alias
                }
            }
        }

        return analyzeInvoke(seq);
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
        while (args != null) {
            nodes.add(analyze(args.first()));
            args = args.next();
        }
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
        if (bindings.count() % 2 != 0)
            throw err("let*: bindings must have even number of forms");

        int bindingCount = bindings.count() / 2;
        int[] slots = new int[bindingCount];
        ExpressionNode[] values = new ExpressionNode[bindingCount];

        for (int i = 0; i < bindingCount; i++) {
            if (!(bindings.nth(i * 2) instanceof Symbol))
                throw err("let*: binding name must be a symbol");
            String bname = ((Symbol) bindings.nth(i * 2)).getName();
            slots[i] = currentScope.addLocal(bname);
            values[i] = analyze(bindings.nth(i * 2 + 1));
        }

        ExpressionNode bodyNode = analyzeBody(args.next());
        return new LetNode(slots, values, bodyNode);
    }

    private ExpressionNode analyzeFn(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("fn*: missing parameters");

        // Optional name
        String fnName = null;
        if (args.first() instanceof Symbol) {
            fnName = ((Symbol) args.first()).getName();
            args = args.next();
            if (args == null) throw err("fn*: missing parameters after name");
        }

        if (!(args.first() instanceof IPersistentVector))
            throw err("fn*: parameters must be a vector");

        IPersistentVector params = (IPersistentVector) args.first();

        // Create new scope for function body
        Scope outerScope = currentScope;
        currentScope = new Scope(outerScope);

        // Parse parameters (handle & for variadic)
        List<Integer> positionalSlots = new ArrayList<>();
        int variadicSlot = -1;

        for (int i = 0; i < params.count(); i++) {
            if (!(params.nth(i) instanceof Symbol))
                throw err("fn*: parameter must be a symbol");
            String pName = ((Symbol) params.nth(i)).getName();

            if ("&".equals(pName)) {
                // Next param is the variadic (rest) parameter
                i++;
                if (i >= params.count()) throw err("fn*: missing parameter after &");
                String restName = ((Symbol) params.nth(i)).getName();
                variadicSlot = currentScope.addLocal(restName);
            } else {
                positionalSlots.add(currentScope.addLocal(pName));
            }
        }

        int[] paramSlotArray = positionalSlots.stream().mapToInt(Integer::intValue).toArray();

        // Analyze body
        ExpressionNode bodyNode = analyzeBody(args.next());

        // Get capture info
        int[] outerCaptureSlots = currentScope.getOuterCaptureSlots();
        int[] innerCaptureSlots = currentScope.getInnerCaptureSlots();

        FrameDescriptor fnDescriptor = currentScope.buildDescriptor();
        FnBodyNode fnBody = new FnBodyNode(language, fnDescriptor, fnName,
                paramSlotArray, variadicSlot, innerCaptureSlots, bodyNode);

        // Restore outer scope
        currentScope = outerScope;

        return new FnNode(fnName, fnBody.getCallTarget(), outerCaptureSlots);
    }

    private ExpressionNode analyzeQuote(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("quote: missing form");
        return new QuoteNode(convertToRuntime(args.first()));
    }

    private ExpressionNode analyzeRecur(ISeq seq) {
        ISeq args = seq.next();
        List<ExpressionNode> argNodes = new ArrayList<>();
        while (args != null) {
            argNodes.add(analyze(args.first()));
            args = args.next();
        }
        return new RecurNode(argNodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeLoop(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("loop*: missing bindings");
        if (!(args.first() instanceof IPersistentVector))
            throw err("loop*: bindings must be a vector");

        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() % 2 != 0)
            throw err("loop*: bindings must have even number of forms");

        int bindingCount = bindings.count() / 2;
        int[] slots = new int[bindingCount];
        ExpressionNode[] values = new ExpressionNode[bindingCount];

        for (int i = 0; i < bindingCount; i++) {
            if (!(bindings.nth(i * 2) instanceof Symbol))
                throw err("loop*: binding name must be a symbol");
            String bname = ((Symbol) bindings.nth(i * 2)).getName();
            slots[i] = currentScope.addLocal(bname);
            values[i] = analyze(bindings.nth(i * 2 + 1));
        }

        ExpressionNode bodyNode = analyzeBody(args.next());
        return new LoopNode(slots, values, bodyNode);
    }

    private ExpressionNode analyzeAnd(ISeq seq) {
        ISeq args = seq.next();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) {
            nodes.add(analyze(args.first()));
            args = args.next();
        }
        return new AndNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeOr(ISeq seq) {
        ISeq args = seq.next();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) {
            nodes.add(analyze(args.first()));
            args = args.next();
        }
        return new OrNode(nodes.toArray(new ExpressionNode[0]));
    }

    // --- Compiler Macros ---

    private ExpressionNode analyzeWhen(ISeq seq) {
        // (when test body...) → (if test (do body...) nil)
        ISeq args = seq.next();
        if (args == null) throw err("when: missing condition");
        ExpressionNode cond = analyze(args.first());
        ExpressionNode body = analyzeBody(args.next());
        return new IfNode(cond, body, new NilNode());
    }

    private ExpressionNode analyzeCond(ISeq seq) {
        // (cond test1 expr1 test2 expr2 ...) → nested if
        ISeq args = seq.next();
        return buildCondChain(args);
    }

    private ExpressionNode buildCondChain(ISeq pairs) {
        if (pairs == null) return new NilNode();
        Object test = pairs.first();
        ISeq rest = pairs.next();
        if (rest == null) throw err("cond: odd number of forms");
        ExpressionNode testNode = analyze(test);
        ExpressionNode thenNode = analyze(rest.first());
        ExpressionNode elseNode = buildCondChain(rest.next());

        // :else is always truthy
        if (test instanceof Keyword kw && "else".equals(kw.getName())) {
            return thenNode;
        }
        return new IfNode(testNode, thenNode, elseNode);
    }

    private ExpressionNode analyzeDefn(ISeq seq) {
        // (defn name [params] body...) → (def name (fn* name [params] body...))
        ISeq args = seq.next();
        if (args == null) throw err("defn: missing name");
        if (!(args.first() instanceof Symbol)) throw err("defn: name must be a symbol");
        Symbol nameSym = (Symbol) args.first();

        // Build (fn* name [params] body...)
        ISeq fnArgs = args; // includes name, params, body
        // Create: (fn* name [params...] body...)
        ISeq fnForm = RT.cons(Symbol.intern("fn*"), fnArgs);
        ExpressionNode fnNode = analyzeFn(fnForm);

        return new DefNode(context, nameSym.getName(), fnNode);
    }

    // --- Invoke ---

    private ExpressionNode analyzeInvoke(ISeq seq) {
        ExpressionNode fn = analyze(seq.first());
        ISeq args = seq.next();
        List<ExpressionNode> argNodes = new ArrayList<>();
        while (args != null) {
            argNodes.add(analyze(args.first()));
            args = args.next();
        }
        return new InvokeNode(fn, argNodes.toArray(new ExpressionNode[0]));
    }

    // --- Literal collections ---

    private ExpressionNode analyzeVector(IPersistentVector vec) {
        ExpressionNode[] elements = new ExpressionNode[vec.count()];
        for (int i = 0; i < vec.count(); i++) {
            elements[i] = analyze(vec.nth(i));
        }
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
        while (body != null) {
            nodes.add(analyze(body.first()));
            body = body.next();
        }
        if (nodes.size() == 1) return nodes.get(0);
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private Object convertToRuntime(Object form) {
        if (form == null) return ClojureNil.INSTANCE;
        return form;
    }

    private static RuntimeException err(String msg) {
        return new RuntimeException(msg);
    }
}
