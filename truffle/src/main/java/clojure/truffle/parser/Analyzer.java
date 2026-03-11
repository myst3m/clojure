package clojure.truffle.parser;

import clojure.lang.*;
import clojure.truffle.ClojureContext;
import clojure.truffle.ClojureTruffleLanguage;
import clojure.truffle.nodes.*;
import clojure.truffle.nodes.interop.*;
import clojure.truffle.runtime.ClojureDeftypeInstance;
import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.ClojureMultiMethod;
import clojure.truffle.runtime.ClojureNamespace;
import clojure.truffle.runtime.ClojureNil;
import clojure.truffle.runtime.ClojureProtocol;
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

    private boolean isSideEffectForm(Object form) {
        if (form instanceof clojure.lang.ISeq seq) {
            Object head = seq.first();
            if (head instanceof clojure.lang.Symbol sym) {
                String name = sym.getName();
                return "require".equals(name) || "ns".equals(name) ||
                       "use".equals(name) || "import".equals(name) ||
                       "refer-clojure".equals(name) || "in-ns".equals(name) ||
                       "alias".equals(name);
            }
        }
        return false;
    }

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
        // Set *ns* for LispReader so ::keyword auto-qualification works
        String currentNs = context != null ? context.getCurrentNamespace() : "user";
        clojure.lang.Var nsVar = clojure.lang.RT.var("clojure.core", "*ns*");
        clojure.lang.Namespace clojureNs = clojure.lang.Namespace.findOrCreate(
                clojure.lang.Symbol.intern(currentNs));
        clojure.lang.Var.pushThreadBindings(clojure.lang.RT.map(nsVar, clojureNs));
        try {
            // Read and analyze forms incrementally so that side-effect forms
            // (require, ns, use, import) are executed before subsequent forms are analyzed
            java.util.List<ExpressionNode> nodes = new java.util.ArrayList<>();
            java.io.PushbackReader reader = new java.io.PushbackReader(new java.io.StringReader(source), 2);
            while (true) {
                Object form = clojure.lang.LispReader.read(reader, false, EOF, false, READ_OPTS);
                if (form == EOF) break;
                ExpressionNode node = analyze(form);
                nodes.add(node);
                // Execute side-effect forms immediately so aliases/vars are available
                if (isSideEffectForm(form)) {
                    EvalRootNode root = new EvalRootNode(language, currentScope.buildDescriptor(),
                            new ExpressionNode[]{node});
                    root.getCallTarget().call();
                    // Replace the executed node with a no-op so it's not executed again
                    nodes.set(nodes.size() - 1, new NilNode());
                }
            }
            return nodes.toArray(new ExpressionNode[0]);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException(e);
        } finally {
            clojure.lang.Var.popThreadBindings();
        }
    }

    public ExpressionNode analyzeForm(Object form) {
        if (currentScope == null) currentScope = new Scope(null);
        return analyze(form);
    }

    /**
     * Read and evaluate forms one at a time (incremental mode).
     * This is needed for loading namespaces where ::alias/keyword
     * depends on ns/require having been executed first.
     */
    private int formCount = 0;
    public void loadSource(String source, ClojureTruffleLanguage lang) {
        if (currentScope == null) currentScope = new Scope(null);
        formCount = 0;
        PushbackReader reader = new PushbackReader(new StringReader(source), 2);
        try {
            while (true) {
                Object form = LispReader.read(reader, false, EOF, false, READ_OPTS);
                if (form == EOF) {
                    if (context != null && "clojure.spec.alpha".equals(context.getCurrentNamespace())) {
                        if (ClojureContext.DEBUG) System.err.println("[LOAD-EOF] after " + formCount + " forms, source length=" + source.length());
                    }
                    break;
                }
                String formStr = form.toString();
                if (formStr.length() > 80) formStr = formStr.substring(0, 80) + "...";
                if (context != null && "clojure.spec.alpha".equals(context.getCurrentNamespace())) {
                    if (ClojureContext.DEBUG) System.err.println("[LOAD-FORM #" + formCount + "] " + formStr);
                }
                formCount++;
                ExpressionNode node;
                try {
                    node = analyze(form);
                } catch (StackOverflowError soe2) {
                    if (ClojureContext.DEBUG) System.err.println("[ANALYZE-SOE] form=" + formStr);
                    // Print stack trace pattern to identify recursion
                    StackTraceElement[] st = soe2.getStackTrace();
                    if (ClojureContext.DEBUG) System.err.println("[ANALYZE-SOE] Stack depth: " + st.length);
                    // Print first 30 frames
                    for (int si = 0; si < Math.min(30, st.length); si++) {
                        if (ClojureContext.DEBUG) System.err.println("[ST] " + si + ": " + st[si]);
                    }
                    // Find repeating pattern
                    java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
                    for (StackTraceElement e : st) {
                        String key = e.getClassName() + "." + e.getMethodName() + ":" + e.getLineNumber();
                        counts.merge(key, 1, Integer::sum);
                    }
                    if (ClojureContext.DEBUG) System.err.println("[ANALYZE-SOE] Top repeated frames:");
                    counts.entrySet().stream()
                        .sorted((a, b) -> b.getValue() - a.getValue())
                        .limit(15)
                        .forEach(e -> System.err.println("  " + e.getValue() + "x " + e.getKey()));
                    throw soe2;
                } catch (Exception ae) {
                    if (context != null && "clojure.spec.alpha".equals(context.getCurrentNamespace())) {
                        if (ClojureContext.DEBUG) System.err.println("[ANALYZE-ERROR] " + ae.getMessage() + " form=" + formStr);
                    }
                    throw ae;
                }
                // Execute immediately so side effects (ns, require) take effect
                // before reading the next form
                Scope savedScope = currentScope;
                EvalRootNode root = new EvalRootNode(lang, currentScope.buildDescriptor(),
                        new ExpressionNode[]{node});
                try {
                    root.getCallTarget().call();
                } catch (StackOverflowError soe) {
                    if (ClojureContext.DEBUG) System.err.println("[SOE] in ns=" + (context != null ? context.getCurrentNamespace() : "?") + " form=" + form.toString().substring(0, Math.min(200, form.toString().length())));
                    throw soe;
                } catch (Exception ex) {
                    if (ex.getMessage() != null && ex.getMessage().contains("Stack overflow")) {
                        if (ClojureContext.DEBUG) System.err.println("[SOE-EX] in ns=" + (context != null ? context.getCurrentNamespace() : "?") + " form=" + form.toString().substring(0, Math.min(200, form.toString().length())));
                    }
                    throw ex;
                }
                currentScope = savedScope;
            }
        } catch (Exception e) {
            String ns = context != null ? context.getCurrentNamespace() : "?";
            throw new RuntimeException("Load error in " + ns + ": " + e.getMessage(), e);
        }
    }

    // --- Reader ---

    private static final Object READ_OPTS = clojure.lang.RT.map(
            LispReader.OPT_READ_COND, LispReader.COND_ALLOW);

    private List<Object> readAll(String source) {
        List<Object> forms = new ArrayList<>();
        PushbackReader reader = new PushbackReader(new StringReader(source), 2);
        try {
            while (true) {
                Object form = LispReader.read(reader, false, EOF, false, READ_OPTS);
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
        if (form instanceof java.util.regex.Pattern pat) return new QuoteNode(pat);
        if (form instanceof Symbol sym) return analyzeSymbol(sym);
        if (form instanceof ISeq seq) return analyzeList(seq);
        if (form instanceof IPersistentVector vec) return analyzeVector(vec);
        if (form instanceof IPersistentMap map) return analyzeMap(map);
        if (form instanceof clojure.lang.IPersistentSet set) return analyzeSet(set);
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
                // Check imported classes
                Object varVal = context.getVar(ns);
                if (varVal instanceof Class<?> c) {
                    return new JavaStaticFieldNode(c, name);
                }
            }
        }
        // Try resolving as a Java class (e.g. String, Long, java.util.ArrayList)
        if (ns == null && !name.isEmpty()) {
            // Simple class name starting with uppercase (String, Long) or
            // fully qualified (java.lang.Long, java.util.ArrayList)
            if (Character.isUpperCase(name.charAt(0)) || name.contains(".")) {
                // Try as namespace.VarName first (e.g. clojure.spec.alpha.Spec → clojure.spec.alpha/Spec)
                if (name.contains(".") && context != null) {
                    int lastDot = name.lastIndexOf('.');
                    if (lastDot > 0) {
                        String possibleNs = name.substring(0, lastDot);
                        String possibleVar = name.substring(lastDot + 1);
                        if (context.getNamespace(possibleNs) != null) {
                            return new SymbolNode(context, possibleNs + "/" + possibleVar);
                        }
                    }
                }
                try {
                    Class<?> clazz = JavaInteropUtil.resolveClass(name);
                    return new QuoteNode(clazz);
                } catch (RuntimeException ignored) {}
            }
        } else if (ns != null) {
            // Check if this is a known namespace var before trying Java class resolution
            if (context != null && context.getNamespace(ns) != null) {
                // ns is a known Clojure namespace — resolve as namespace var, not Java class
                // Fall through to namespace alias resolution below
            } else {
                // Namespace-qualified: java.lang/Long → java.lang.Long
                String fqn = ns + "." + name;
                try {
                    Class<?> clazz = JavaInteropUtil.resolveClass(fqn);
                    return new QuoteNode(clazz);
                } catch (RuntimeException ignored) {}
            }
        }
        // Resolve namespace alias at compile time to fully qualified name
        String resolvedNs = ns;
        if (ns != null && context != null) {
            ClojureNamespace currentNsObj = context.getNamespace(context.getCurrentNamespace());
            if (currentNsObj != null) {
                ClojureNamespace aliased = currentNsObj.resolveAlias(ns);
                if (aliased != null) resolvedNs = aliased.getName();
            }
        }
        String varName = resolvedNs != null ? resolvedNs + "/" + name : name;
        return new SymbolNode(context, varName);
    }

    // True JVM-level special forms that should always be recognized regardless of namespace.
    // These match Clojure's Compiler.specials - forms that are never macros.
    private static final java.util.Set<String> TRUE_SPECIAL_FORMS = java.util.Set.of(
        "if", "do", "let*", "let", "fn*", "fn", "quote", "recur",
        "loop*", "loop", "try", "throw", "new", "set!", "var",
        "monitor-enter", "monitor-exit"
    );

    // Forms implemented as builtins in Truffle Clojure (macros/special forms in clojure.core)
    // These are recognized when namespace-qualified to clojure.core or an alias thereof.
    private static final java.util.Set<String> CORE_FORMS = java.util.Set.of(
        "and", "or", "when", "cond", "defn", "defn-", "def",
        "defmacro", "macroexpand", "lazy-seq",
        "defmulti", "defmethod", "defprotocol", "deftype", "defrecord",
        "ns", "in-ns", "require", "->", "->>", "as->", "some->", "some->>",
        "cond->", "cond->>", "doto", "..", "if-let", "when-let", "if-some",
        "when-some", "case", "for", "doseq", "dotimes", "letfn",
        "binding", "when-not", "if-not", "condp", "while", "comment",
        "declare", "defonce", "with-open", "with-out-str", "reify", "proxy",
        "extend-type", "extend-protocol", "delay", "future", "locking",
        "dosync", "macroexpand-1", "when-first", "assert",
        "time", "with-in-str", "with-redefs", "memfn", "import", "use",
        "refer", "refer-clojure"
    );

    private ExpressionNode analyzeList(ISeq seq) {
        if (seq == null) return new QuoteNode(PersistentList.EMPTY);
        Object first = seq.first();
        if (first == null && seq.next() == null) return new QuoteNode(PersistentList.EMPTY);

        if (first instanceof Symbol sym) {
            String name = sym.getName();
            String ns = sym.getNamespace();

            // For namespace-qualified symbols, check if they resolve to a known form:
            // 1. True special forms (if, do, let*, fn*, etc.) are always recognized by name
            // 2. Core forms (defn, delay, etc.) only when qualified to clojure.core
            boolean treatAsBuiltin = false;
            if (ns != null && (TRUE_SPECIAL_FORMS.contains(name) || CORE_FORMS.contains(name))) {
                // Only treat as builtin if the namespace resolves to clojure.core
                String resolvedNs = ns;
                if (context != null) {
                    ClojureNamespace currentNsObj = context.getNamespace(context.getCurrentNamespace());
                    if (currentNsObj != null) {
                        ClojureNamespace aliasedNs = currentNsObj.resolveAlias(ns);
                        if (aliasedNs != null) resolvedNs = aliasedNs.getName();
                    }
                }
                if ("clojure.core".equals(resolvedNs)) {
                    treatAsBuiltin = true;
                }
            }
            if (ns == null || treatAsBuiltin) {
                // Field access: (.-field obj)
                if (name.startsWith(".-") && name.length() > 2) {
                    return analyzeFieldAccess(name.substring(2), seq.next());
                }
                // Instance method: (.method obj args...)
                if (name.startsWith(".") && name.length() > 1 && !name.equals("..")) {
                    return analyzeInstanceMethod(name.substring(1), seq.next());
                }
                // Constructor: (ClassName. args...)
                if (name.endsWith(".") && name.length() > 1 && !name.equals("..")) {
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
                    case "defn":        return analyzeDefn(seq, false);
                    case "defn-":       return analyzeDefn(seq, true);
                    case "try":         return analyzeTry(seq);
                    case "throw":       return analyzeThrow(seq);
                    case "defmacro":    return analyzeDefmacro(seq);
                    case "macroexpand": return analyzeMacroexpand(seq);
                    case "new":         return analyzeNew(seq);
                    case "lazy-seq":    return analyzeLazySeq(seq);
                    case "defmulti":    return analyzeDefmulti(seq);
                    case "defmethod":   return analyzeDefmethod(seq);
                    case "defprotocol": return analyzeDefprotocol(seq);
                    case "deftype":     return analyzeDeftype(seq);
                    case "defrecord":   return analyzeDefrecord(seq);
                    case "ns":          return analyzeNs(seq);
                    case "in-ns":       return analyzeInNs(seq);
                    case "require":     return analyzeRequire(seq);
                    case "->":          return analyzeThreadFirst(seq);
                    case "->>":         return analyzeThreadLast(seq);
                    case "as->":        return analyzeAsThread(seq);
                    case "some->":      return analyzeSomeThread(seq, true);
                    case "some->>":     return analyzeSomeThread(seq, false);
                    case "cond->":      return analyzeCondThread(seq, true);
                    case "cond->>":     return analyzeCondThread(seq, false);
                    case "doto":        return analyzeDoto(seq);
                    case "..":          return analyzeDotDot(seq);
                    case "if-let":      return analyzeIfLet(seq);
                    case "when-let":    return analyzeWhenLet(seq);
                    case "if-some":     return analyzeIfSome(seq);
                    case "when-some":   return analyzeWhenSome(seq);
                    case "case":        return analyzeCase(seq);
                    case "for":         return analyzeFor(seq);
                    case "doseq":       return analyzeDoseq(seq);
                    case "dotimes":     return analyzeDotimes(seq);
                    case "letfn":       return analyzeLetfn(seq);
                    case "do-template": return analyzeDo(seq); // fallback
                    case "binding":     return analyzeBinding(seq);
                    case "when-not":    return analyzeWhenNot(seq);
                    case "if-not":      return analyzeIfNot(seq);
                    case "condp":       return analyzeCondp(seq);
                    case "while":       return analyzeWhile(seq);
                    case "comment":     return new NilNode();
                    case "declare":     return analyzeDeclare(seq);
                    case "defonce":     return analyzeDefonce(seq);
                    case "with-open":   return analyzeWithOpen(seq);
                    case "with-out-str": return analyzeWithOutStr(seq);
                    case "reify":       return analyzeReify(seq);
                    case "proxy":       return analyzeProxy(seq);
                    case "extend-type": return analyzeExtendType(seq);
                    case "extend-protocol": return analyzeExtendProtocol(seq);
                    case "delay":       return analyzeDelay(seq);
                    case "future":      return analyzeFuture(seq);
                    case "locking":     return analyzeLocking(seq);
                    case "dosync":      return analyzeDo(seq); // simplified: just execute body
                    case "var":         return analyzeVar(seq);
                    case "set!":        return analyzeSetBang(seq);
                    case "macroexpand-1": return analyzeMacroexpand1(seq);
                    case "when-first":  return analyzeWhenFirst(seq);
                    case "assert":      return analyzeAssert(seq);
                    case "time":        return analyzeTime(seq);
                    case "with-in-str": return analyzeWithInStr(seq);
                    case "with-redefs": return analyzeWithRedefs(seq);
                    case "memfn":       return analyzeMemfn(seq);
                    case "import":      return analyzeImport(seq);
                    case "use":         return analyzeUse(seq);
                    case "refer":       return analyzeRefer(seq);
                    case "refer-clojure": return analyzeReferClojure(seq);
                }
            }
            // Static method call: (Class/method args...)
            if (ns != null && isJavaClassName(ns)) {
                return analyzeStaticCall(ns, name, seq.next());
            }

            // Check for macros AFTER builtin forms - macros should not shadow builtins
            // Local variables and builtin forms take priority over macros
            if (context != null) {
                if (ns == null) {
                    // Unqualified: check local vars first, then macros
                    Integer localSlot = currentScope.findLocal(name);
                    if (localSlot == null) {
                        Object macro = context.getMacro(name);
                        if (macro != null) {
                            return expandAndAnalyzeMacro(macro, seq.next());
                        }
                    }
                } else {
                    // Namespace-qualified: check if it's a macro in the target namespace (e.g., s/def)
                    Object macro = context.getMacroFromNs(ns, name);
                    if (macro != null) {
                        return expandAndAnalyzeMacro(macro, seq.next());
                    }
                }
            }
        }
        return analyzeInvoke(seq);
    }

    // --- Macro expansion ---

    private int macroDepth = 0;
    private ExpressionNode expandAndAnalyzeMacro(Object macro, ISeq argForms) {
        if (macroDepth > 5) {
            String macroDesc = macro instanceof ClojureFunction cf ? cf.getName() :
                macro instanceof MultiArityFunction ? "MultiArityFunction" : macro.getClass().getSimpleName();
            if (ClojureContext.DEBUG) System.err.println("[MACRO depth=" + macroDepth + "] macro=" + macroDesc + " args=" + argForms);
        }
        if (macroDepth > 50) {
            String macroDesc = macro instanceof ClojureFunction cf ? cf.getName() :
                macro instanceof MultiArityFunction ? "MultiArityFunction" : macro.getClass().getSimpleName();
            throw new RuntimeException("Macro expansion depth exceeded (>50), macro=" + macroDesc + ", args=" + argForms);
        }
        macroDepth++;
        try {
            List<Object> rawArgs = new ArrayList<>();
            while (argForms != null) {
                rawArgs.add(argForms.first());
                argForms = argForms.next();
            }
            Object expanded = context.callFunction(macro, rawArgs.toArray());
            if (ClojureContext.DEBUG) {
                String macroDesc = macro instanceof ClojureFunction cf ? cf.getName() :
                    macro instanceof MultiArityFunction maf ? "MultiArityFunction" : macro.getClass().getSimpleName();
                System.err.println("[MACRO-EXPAND] " + macroDesc + " => " +
                    (expanded != null ? expanded.toString().substring(0, Math.min(300, expanded.toString().length())) : "nil"));
            }
            return analyze(expanded);
        } finally {
            macroDepth--;
        }
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

    private ExpressionNode analyzeMacroexpand1(ISeq seq) {
        // (macroexpand-1 '(macro-form ...)) -> single expansion step
        ISeq args = seq.next();
        if (args == null) throw err("macroexpand-1: missing form");
        Object form = args.first();
        // Evaluate the quoted form to get the actual form
        ExpressionNode formNode = analyze(form);
        // macroexpand-1 is registered as a function in ClojureContext
        return new InvokeNode(new SymbolNode(context, "macroexpand-1"),
                new ExpressionNode[]{formNode});
    }

    // --- Var / Set! ---

    private ExpressionNode analyzeVar(ISeq seq) {
        // (var symbol) -> returns the Var object for the symbol
        ISeq args = seq.next();
        if (args == null) throw err("var: missing symbol");
        Object sym = args.first();
        if (!(sym instanceof Symbol s)) throw err("var: argument must be a symbol");
        String varName = s.getNamespace() != null ? s.getNamespace() + "/" + s.getName() : s.getName();
        return new VarNode(context, varName);
    }

    private ExpressionNode analyzeSetBang(ISeq seq) {
        // (set! *var* value) -> sets dynamic var's thread-local binding
        ISeq args = seq.next();
        if (args == null) throw err("set!: missing var");
        Object target = args.first();
        args = args.next();
        if (args == null) throw err("set!: missing value");
        ExpressionNode valueNode = analyze(args.first());

        if (target instanceof Symbol s) {
            String varName = s.getName();
            return new SetBangNode(context, varName, valueNode);
        }
        // (set! (. Class field) value) → Java static field assignment
        if (target instanceof ISeq targetSeq) {
            Object head = targetSeq.first();
            if (head instanceof Symbol dotSym && ".".equals(dotSym.getName())) {
                Object classObj = RT.second(targetSeq);
                Object fieldObj = RT.third(targetSeq);
                if (classObj instanceof Symbol classSym && fieldObj instanceof Symbol fieldSym) {
                    String className = classSym.getName();
                    String fieldName = fieldSym.getName();
                    ExpressionNode valNode = valueNode;
                    return new ExpressionNode() {
                        @Override
                        public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                            try {
                                Class<?> clazz = clojure.truffle.nodes.interop.JavaInteropUtil.resolveClass(className);
                                java.lang.reflect.Field field = clazz.getField(fieldName);
                                field.setAccessible(true);
                                Object value = valNode.executeGeneric(frame);
                                // Convert truthy/falsy to boolean if field is boolean
                                if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                                    if (value instanceof Boolean b) field.set(null, b);
                                    else field.set(null, value != null && !(value instanceof clojure.truffle.runtime.ClojureNil));
                                } else {
                                    field.set(null, value);
                                }
                                return value;
                            } catch (Exception e) {
                                throw new RuntimeException("set!: cannot set field " + className + "/" + fieldName + ": " + e.getMessage(), e);
                            }
                        }
                    };
                }
            }
        }
        throw err("set!: target must be a symbol");
    }

    // --- when-first ---

    private ExpressionNode analyzeWhenFirst(ISeq seq) {
        // (when-first [x coll] body...) => (let [s (seq coll)] (when s (let [x (first s)] body...)))
        ISeq args = seq.next();
        if (args == null) throw err("when-first: missing binding");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() != 2) throw err("when-first: binding must have 2 forms");
        Symbol sym = (Symbol) bindings.nth(0);
        Object coll = bindings.nth(1);
        ISeq body = args.next();

        // Build: (let [s__temp (seq coll)] (when s__temp (let [sym (first s__temp)] body...)))
        Symbol tempSym = Symbol.intern("__when-first-temp__" + System.nanoTime());
        // (seq coll)
        Object seqCall = RT.list(Symbol.intern("seq"), coll);
        // (first s__temp)
        Object firstCall = RT.list(Symbol.intern("first"), tempSym);
        // (let [sym (first s__temp)] body...)
        List<Object> innerLetForms = new ArrayList<>();
        innerLetForms.add(Symbol.intern("let"));
        innerLetForms.add(PersistentVector.create(sym, firstCall));
        while (body != null) { innerLetForms.add(body.first()); body = body.next(); }
        Object innerLet = PersistentList.create(innerLetForms);
        // (when s__temp innerLet)
        Object whenForm = RT.list(Symbol.intern("when"), tempSym, innerLet);
        // (let [s__temp (seq coll)] whenForm)
        Object outerLet = RT.list(Symbol.intern("let"),
                PersistentVector.create(tempSym, seqCall), whenForm);
        return analyze(outerLet);
    }

    // --- assert ---

    private ExpressionNode analyzeAssert(ISeq seq) {
        // (assert expr) or (assert expr message)
        ISeq args = seq.next();
        if (args == null) throw err("assert: missing expression");
        ExpressionNode testNode = analyze(args.first());
        args = args.next();
        String message = args != null ? args.first().toString() : "Assert failed: " + seq.next().first();

        // Build directly: if (not test) throw new AssertionError(message)
        ExpressionNode throwNode = new ThrowNode(
                new InvokeNode(new SymbolNode(context, "new-assertion-error"),
                        new ExpressionNode[]{new StringLiteralNode(message)}));
        return new IfNode(testNode, new NilNode(), throwNode);
    }

    // --- time ---

    private ExpressionNode analyzeTime(ISeq seq) {
        // (time expr) -> prints elapsed time, returns value
        ISeq args = seq.next();
        if (args == null) throw err("time: missing expression");
        // Build as function call to time builtin
        return new InvokeNode(new SymbolNode(context, "time*"),
                new ExpressionNode[]{analyzeLambdaThunk(args)});
    }

    private ExpressionNode analyzeLambdaThunk(ISeq body) {
        // Wrap body in (fn* [] body...)
        ISeq fnForm = RT.cons(Symbol.intern("fn*"),
                RT.cons(PersistentVector.EMPTY, body));
        return analyzeFn(fnForm);
    }

    // --- with-in-str ---

    private ExpressionNode analyzeWithInStr(ISeq seq) {
        // (with-in-str s body...) -> bind *in* to StringReader of s, execute body
        ISeq args = seq.next();
        if (args == null) throw err("with-in-str: missing string");
        ExpressionNode strNode = analyze(args.first());
        ExpressionNode thunkNode = analyzeLambdaThunk(args.next());
        return new InvokeNode(new SymbolNode(context, "with-in-str*"),
                new ExpressionNode[]{strNode, thunkNode});
    }

    // --- with-redefs ---

    private ExpressionNode analyzeWithRedefs(ISeq seq) {
        // (with-redefs [var val ...] body...) -> temporarily redefine vars
        ISeq args = seq.next();
        if (args == null) throw err("with-redefs: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() % 2 != 0) throw err("with-redefs: bindings must have even number of forms");

        // Build as: save old vals, set new vals, try body finally restore old vals
        // Use binding mechanism (treat as dynamic for the duration)
        int numBindings = bindings.count() / 2;
        String[] varNames = new String[numBindings];
        ExpressionNode[] valueNodes = new ExpressionNode[numBindings];
        for (int i = 0; i < numBindings; i++) {
            Symbol sym = (Symbol) bindings.nth(i * 2);
            varNames[i] = sym.getName();
            valueNodes[i] = analyze(bindings.nth(i * 2 + 1));
        }
        ISeq bodyArgs = args.next();
        List<ExpressionNode> body = new ArrayList<>();
        while (bodyArgs != null) { body.add(analyze(bodyArgs.first())); bodyArgs = bodyArgs.next(); }
        return new clojure.truffle.nodes.WithRedefsNode(context, varNames, valueNodes,
                body.toArray(new ExpressionNode[0]));
    }

    // --- memfn ---

    private ExpressionNode analyzeMemfn(ISeq seq) {
        // (memfn method-name arg1 arg2 ...) -> (fn [target arg1 arg2 ...] (.method-name target arg1 arg2 ...))
        ISeq args = seq.next();
        if (args == null) throw err("memfn: missing method name");
        String methodName = ((Symbol) args.first()).getName();
        args = args.next();
        List<Symbol> params = new ArrayList<>();
        params.add(Symbol.intern("target__"));
        while (args != null) {
            params.add((Symbol) args.first());
            args = args.next();
        }
        // Build (.methodName target__ arg1 arg2 ...)
        List<Object> callForms = new ArrayList<>();
        callForms.add(Symbol.intern("." + methodName));
        for (Symbol p : params) callForms.add(p);
        Object callForm = PersistentList.create(callForms);
        // Build (fn [target__ arg1 ...] (.methodName target__ arg1 ...))
        Object fnForm = RT.list(Symbol.intern("fn"),
                PersistentVector.create(params.toArray()), callForm);
        return analyze(fnForm);
    }

    // --- import ---

    private ExpressionNode analyzeImport(ISeq seq) {
        // (import java.util.ArrayList) or (import (java.util ArrayList HashMap))
        ISeq args = seq.next();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) {
            Object form = args.first();
            if (form instanceof Symbol sym) {
                // Simple: (import java.util.ArrayList)
                String fqn = sym.getName();
                String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
                Class<?> clazz = resolveClassSafe(fqn);
                // Register immediately so subsequent analysis can resolve the class
                context.setVar(simpleName, clazz);
                nodes.add(new DefNode(context, simpleName, new QuoteNode(clazz)));
            } else if (form instanceof ISeq importList) {
                // Package-prefixed: (import (java.util ArrayList HashMap))
                String pkg = ((Symbol) importList.first()).getName();
                ISeq classes = importList.next();
                while (classes != null) {
                    String className = ((Symbol) classes.first()).getName();
                    String fqn = pkg + "." + className;
                    Class<?> clazz = resolveClassSafe(fqn);
                    context.setVar(className, clazz);
                    nodes.add(new DefNode(context, className, new QuoteNode(clazz)));
                    classes = classes.next();
                }
            }
            args = args.next();
        }
        if (nodes.isEmpty()) return new NilNode();
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private Class<?> resolveClassSafe(String fqn) {
        try {
            return Class.forName(fqn);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("import: class not found: " + fqn);
        }
    }

    // --- use / refer ---

    private ExpressionNode analyzeUse(ISeq seq) {
        // (use 'some.ns) or (use '[some.ns :only [foo bar]])
        ISeq args = seq.next();
        List<Object> specs = new ArrayList<>();
        while (args != null) {
            specs.add(args.first());
            args = args.next();
        }
        List<Object> capturedSpecs = List.copyOf(specs);
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                for (Object spec : capturedSpecs) processUseSpec(spec);
                return ClojureNil.INSTANCE;
            }
        };
    }

    private void processUseSpec(Object spec) {
        // Unwrap quote
        if (spec instanceof ISeq qs) {
            Object first = qs.first();
            if (first instanceof Symbol s && s.getName().equals("quote")) {
                spec = qs.next().first();
            }
        }
        if (spec instanceof Symbol sym) {
            // Simple: (use 'some.ns) -> load + refer all
            String nsName = sym.getName();
            context.loadNamespace(nsName);
            ClojureNamespace reqNs = context.getNamespace(nsName);
            ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
            if (reqNs != null && currentNs != null) currentNs.referAll(reqNs);
        } else if (spec instanceof IPersistentVector v) {
            // [some.ns :only [foo bar]] or [some.ns :rename {old new}]
            if (v.count() == 0) return;
            String nsName = ((Symbol) v.nth(0)).getName();
            context.loadNamespace(nsName);
            ClojureNamespace reqNs = context.getNamespace(nsName);
            ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
            if (reqNs == null || currentNs == null) return;

            List<String> onlySyms = null;
            java.util.Set<String> excludeSyms = null;
            java.util.Map<String, String> renames = null;

            for (int i = 1; i < v.count(); i += 2) {
                if (v.nth(i) instanceof Keyword kw && i + 1 < v.count()) {
                    switch (kw.getName()) {
                        case "only":
                            onlySyms = extractSymbolNames((IPersistentVector) v.nth(i + 1));
                            break;
                        case "exclude":
                            excludeSyms = new java.util.HashSet<>(extractSymbolNames((IPersistentVector) v.nth(i + 1)));
                            break;
                        case "rename":
                            renames = extractRenameMap((IPersistentMap) v.nth(i + 1));
                            break;
                        case "as":
                            String alias = ((Symbol) v.nth(i + 1)).getName();
                            currentNs.alias(alias, reqNs);
                            break;
                    }
                }
            }

            if (onlySyms != null) {
                currentNs.referOnly(reqNs, onlySyms);
            } else if (excludeSyms != null) {
                currentNs.referWithExclude(reqNs, excludeSyms);
            } else if (renames != null) {
                currentNs.referWithRename(reqNs, renames);
            } else {
                currentNs.referAll(reqNs);
            }
        }
    }

    private ExpressionNode analyzeRefer(ISeq seq) {
        // (refer 'some.ns) or (refer 'some.ns :only '[foo bar])
        ISeq args = seq.next();
        List<Object> capturedArgs = new ArrayList<>();
        while (args != null) { capturedArgs.add(args.first()); args = args.next(); }
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                if (capturedArgs.isEmpty()) return ClojureNil.INSTANCE;
                Object nsArg = capturedArgs.get(0);
                // Unwrap quote
                if (nsArg instanceof ISeq qs && qs.first() instanceof Symbol s && s.getName().equals("quote")) {
                    nsArg = qs.next().first();
                }
                String nsName;
                if (nsArg instanceof Symbol sym) nsName = sym.getName();
                else nsName = nsArg.toString();
                ClojureNamespace reqNs = context.getNamespace(nsName);
                ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
                if (reqNs == null || currentNs == null) return ClojureNil.INSTANCE;

                // Check for :only filter
                if (capturedArgs.size() >= 3) {
                    Object filterKey = capturedArgs.get(1);
                    if (filterKey instanceof Keyword kw && kw.getName().equals("only")) {
                        Object symsArg = capturedArgs.get(2);
                        // Unwrap quote
                        if (symsArg instanceof ISeq qs2 && qs2.first() instanceof Symbol s2 && s2.getName().equals("quote")) {
                            symsArg = qs2.next().first();
                        }
                        if (symsArg instanceof IPersistentVector pv) {
                            currentNs.referOnly(reqNs, extractSymbolNames(pv));
                            return ClojureNil.INSTANCE;
                        }
                    }
                }
                currentNs.referAll(reqNs);
                return ClojureNil.INSTANCE;
            }
        };
    }

    private ExpressionNode analyzeReferClojure(ISeq seq) {
        // (refer-clojure :exclude [symbol1 symbol2])
        ISeq args = seq.next();
        java.util.Set<String> excludes = new java.util.HashSet<>();
        while (args != null) {
            if (args.first() instanceof Keyword kw && kw.getName().equals("exclude")) {
                args = args.next();
                if (args != null && args.first() instanceof IPersistentVector v) {
                    for (int i = 0; i < v.count(); i++) {
                        excludes.add(((Symbol) v.nth(i)).getName());
                    }
                }
            }
            if (args != null) args = args.next();
        }
        java.util.Set<String> capturedExcludes = java.util.Set.copyOf(excludes);
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                ClojureNamespace core = context.getNamespace("clojure.core");
                ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
                if (core != null && currentNs != null) {
                    currentNs.referWithExclude(core, capturedExcludes);
                }
                return ClojureNil.INSTANCE;
            }
        };
    }

    private List<String> extractSymbolNames(IPersistentVector v) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < v.count(); i++) {
            names.add(((Symbol) v.nth(i)).getName());
        }
        return names;
    }

    private java.util.Map<String, String> extractRenameMap(IPersistentMap m) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        for (ISeq s = m.seq(); s != null; s = s.next()) {
            IMapEntry entry = (IMapEntry) s.first();
            map.put(((Symbol) entry.key()).getName(), ((Symbol) entry.val()).getName());
        }
        return map;
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
        if (name.isEmpty()) return false;
        // If it looks like a Clojure namespace (registered or contains lowercase first segment), not Java
        if (name.contains(".")) {
            // Check: if first segment starts with lowercase and matches known Clojure patterns, skip
            String firstSeg = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
            if (Character.isLowerCase(firstSeg.charAt(0))) {
                // Check if there's a registered var with this prefix (e.g., clojure.string/split)
                // Also check known Clojure namespace patterns
                if (name.startsWith("clojure.") || name.startsWith("user.")) return false;
                // Try to resolve as Java class
                try {
                    Class.forName(name);
                    return true;
                } catch (ClassNotFoundException e) {
                    return false;
                }
            }
            return true; // e.g., java.util.List
        }
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
        Class<?> clazz;
        try {
            clazz = JavaInteropUtil.resolveClass(className);
        } catch (RuntimeException e) {
            // Check if it's an imported class stored as a var
            Object varVal = context.getVar(className);
            if (varVal instanceof Class<?> c) {
                clazz = c;
            } else {
                throw e;
            }
        }
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
        // Try Java class first
        Class<?> clazz = null;
        try {
            clazz = JavaInteropUtil.resolveClass(className);
        } catch (RuntimeException e) {
            // Check if it's an imported class stored as a var
            Object varVal = context.getVar(className);
            if (varVal instanceof Class<?> c) {
                clazz = c;
            }
        }
        if (clazz != null) {
            List<ExpressionNode> argList = new ArrayList<>();
            while (args != null) { argList.add(analyze(args.first())); args = args.next(); }
            return new JavaConstructorNode(clazz, argList.toArray(new ExpressionNode[0]));
        }
        // Not a Java class, try deftype constructor (->TypeName)
        List<ExpressionNode> argList = new ArrayList<>();
        while (args != null) { argList.add(analyze(args.first())); args = args.next(); }
        String ctorName = "->" + className;
        return new InvokeNode(new SymbolNode(context, ctorName),
                argList.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeNew(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("new: missing class name");
        Object classForm = args.first();
        if (!(classForm instanceof Symbol classSym))
            throw err("new: class name must be a symbol");
        return analyzeConstructor(classSym.getName(), args.next());
    }

    // --- Field access ---

    private ExpressionNode analyzeFieldAccess(String fieldName, ISeq args) {
        if (args == null) throw err(".-field: missing target");
        ExpressionNode target = analyze(args.first());
        // Return a node that accesses the field
        return new ExpressionNode() {
            @Child ExpressionNode targetNode = target;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object obj = targetNode.executeGeneric(frame);
                if (obj instanceof ClojureDeftypeInstance inst) {
                    return inst.getField(fieldName);
                }
                // Fall back to Java reflection for field access
                try {
                    java.lang.reflect.Field f = obj.getClass().getField(fieldName);
                    return clojure.truffle.nodes.interop.JavaInteropUtil.wrapResult(f.get(obj));
                } catch (Exception e) {
                    throw new RuntimeException("No field '" + fieldName + "' on " + obj.getClass().getName());
                }
            }
        };
    }

    // --- Multimethods ---

    private ExpressionNode analyzeDefmulti(ISeq seq) {
        // (defmulti name dispatch-fn)
        ISeq args = seq.next();
        if (args == null) throw err("defmulti: missing name");
        if (!(args.first() instanceof Symbol sym)) throw err("defmulti: name must be a symbol");
        String name = sym.getName();
        args = args.next();
        if (args == null) throw err("defmulti: missing dispatch function");
        ExpressionNode dispatchFnNode = analyze(args.first());

        return new ExpressionNode() {
            @Child ExpressionNode dispatchNode = dispatchFnNode;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object dispatchFn = dispatchNode.executeGeneric(frame);
                ClojureMultiMethod mm = new ClojureMultiMethod(name, dispatchFn, context);
                context.setVar(name, mm);
                return mm;
            }
        };
    }

    private ExpressionNode analyzeDefmethod(ISeq seq) {
        // (defmethod name dispatch-val [params] body...)
        ISeq args = seq.next();
        if (args == null) throw err("defmethod: missing name");
        if (!(args.first() instanceof Symbol sym)) throw err("defmethod: name must be a symbol");
        String mmName = sym.getName();
        args = args.next();
        if (args == null) throw err("defmethod: missing dispatch value");
        ExpressionNode dispatchValNode = analyze(args.first());
        args = args.next();
        if (args == null) throw err("defmethod: missing fn body");

        // Build fn from remaining args: [params] body...
        ISeq fnForm = RT.cons(Symbol.intern("fn*"), args);
        ExpressionNode fnNode = analyzeFn(fnForm);

        return new ExpressionNode() {
            @Child ExpressionNode dvNode = dispatchValNode;
            @Child ExpressionNode methodFnNode = fnNode;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object dispatchVal = dvNode.executeGeneric(frame);
                Object methodFn = methodFnNode.executeGeneric(frame);
                Object mm = context.getVar(mmName);
                if (!(mm instanceof ClojureMultiMethod multi))
                    throw new RuntimeException("defmethod: " + mmName + " is not a multimethod");
                multi.addMethod(dispatchVal, methodFn);
                return multi;
            }
        };
    }

    // --- Protocols ---

    private ExpressionNode analyzeDefprotocol(ISeq seq) {
        // (defprotocol Name (method-name [this arg1] [this arg1 arg2]) ...)
        ISeq args = seq.next();
        if (args == null) throw err("defprotocol: missing name");
        if (!(args.first() instanceof Symbol sym)) throw err("defprotocol: name must be a symbol");
        String protoName = sym.getName();
        args = args.next();

        List<String> methodNames = new ArrayList<>();
        while (args != null) {
            Object methodSpec = args.first();
            if (methodSpec instanceof ISeq methodSeq) {
                if (!(methodSeq.first() instanceof Symbol methodSym))
                    throw err("defprotocol: method name must be a symbol");
                methodNames.add(methodSym.getName());
            }
            args = args.next();
        }

        List<String> capturedMethodNames = List.copyOf(methodNames);
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                ClojureProtocol proto = new ClojureProtocol(protoName, capturedMethodNames);
                context.setVar(protoName, proto);
                // Create dispatch functions for each method
                for (String methodName : capturedMethodNames) {
                    context.setVar(methodName, new ClojureContext.NamedBuiltin(
                            protoName + "/" + methodName, fnArgs -> {
                        if (fnArgs.length < 1)
                            throw new RuntimeException(protoName + "/" + methodName + ": missing target (this)");
                        Object target = fnArgs[0];
                        Object fn = proto.findMethod(methodName, target);
                        if (fn == null) {
                            String targetType = target instanceof clojure.truffle.runtime.ClojureReified r
                                    ? "ClojureReified{" + String.join(",", r.getMethods().keySet()) + "}"
                                    : target.getClass().getName();
                            throw new RuntimeException("No implementation of protocol " + protoName +
                                    " method " + methodName + " for type: " + targetType);
                        }
                        return context.callFunction(fn, fnArgs);
                    }));
                }
                return proto;
            }
        };
    }

    // --- deftype ---

    private ExpressionNode analyzeDeftype(ISeq seq) {
        // (deftype TypeName [field1 field2] ProtoName (method1 [this] body...) ...)
        ISeq args = seq.next();
        if (args == null) throw err("deftype: missing name");
        if (!(args.first() instanceof Symbol typeSym)) throw err("deftype: name must be a symbol");
        String typeName = typeSym.getName();
        args = args.next();
        if (args == null) throw err("deftype: missing fields");
        if (!(args.first() instanceof IPersistentVector fieldVec)) throw err("deftype: fields must be a vector");

        // Parse field names
        List<String> fieldNames = new ArrayList<>();
        for (int i = 0; i < fieldVec.count(); i++) {
            if (!(fieldVec.nth(i) instanceof Symbol fs)) throw err("deftype: field must be a symbol");
            fieldNames.add(fs.getName());
        }
        args = args.next();

        // Parse protocol implementations
        // format: ProtoName (method [this arg] body...) (method2 [this] body...) AnotherProto ...
        java.util.Map<String, java.util.Map<String, ExpressionNode>> protoMethods = new java.util.LinkedHashMap<>();
        String currentProto = null;

        while (args != null) {
            Object form = args.first();
            if (form instanceof Symbol protoSym) {
                currentProto = protoSym.getName();
                protoMethods.putIfAbsent(currentProto, new java.util.LinkedHashMap<>());
            } else if (form instanceof ISeq methodSeq && currentProto != null) {
                if (!(methodSeq.first() instanceof Symbol methodSym))
                    throw err("deftype: method name must be a symbol");
                String methodName = methodSym.getName();
                // Build fn: (fn* [this arg1 ...] (let [field1 (.-field1 this) ...] body...))
                ISeq methodArgs = methodSeq.next();
                if (methodArgs == null || !(methodArgs.first() instanceof IPersistentVector))
                    throw err("deftype: method must have param vector");
                IPersistentVector methodParams = (IPersistentVector) methodArgs.first();
                ISeq methodBody = methodArgs.next();

                // Compile fn with field bindings inside body
                ExpressionNode methodFn = compileDeftypeMethod(typeName, fieldNames, methodParams, methodBody);
                protoMethods.get(currentProto).put(methodName, methodFn);
            }
            args = args.next();
        }

        List<String> capturedFieldNames = List.copyOf(fieldNames);
        // Capture proto methods
        java.util.Map<String, java.util.Map<String, ExpressionNode>> capturedProtoMethods =
                new java.util.LinkedHashMap<>(protoMethods);

        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                // Register constructor ->TypeName
                java.util.Map<String, Integer> fieldIdx = new java.util.LinkedHashMap<>();
                for (int i = 0; i < capturedFieldNames.size(); i++)
                    fieldIdx.put(capturedFieldNames.get(i), i);

                context.setVar("->" + typeName, (ClojureContext.BuiltinFunction) ctorArgs -> {
                    if (ctorArgs.length != capturedFieldNames.size())
                        throw new RuntimeException("->" + typeName + ": expected " +
                                capturedFieldNames.size() + " args");
                    return new ClojureDeftypeInstance(typeName, ctorArgs.clone(), fieldIdx);
                });

                // Register protocol implementations
                for (var entry : capturedProtoMethods.entrySet()) {
                    String protoName = entry.getKey();
                    Object protoObj = context.getVar(protoName);
                    if (protoObj instanceof ClojureProtocol proto) {
                        java.util.Map<String, Object> methodMap = new java.util.HashMap<>();
                        for (var methodEntry : entry.getValue().entrySet()) {
                            Object fn = methodEntry.getValue().executeGeneric(frame);
                            methodMap.put(methodEntry.getKey(), fn);
                        }
                        proto.extend(typeName, methodMap);
                    }
                }
                return ClojureNil.INSTANCE;
            }
        };
    }

    private ExpressionNode analyzeDefrecord(ISeq seq) {
        // defrecord is like deftype but also registers map->TypeName constructor
        ISeq args = seq.next();
        if (args == null) throw err("defrecord: missing name");
        if (!(args.first() instanceof Symbol typeSym)) throw err("defrecord: name must be a symbol");
        String typeName = typeSym.getName();
        args = args.next();
        if (args == null) throw err("defrecord: missing fields");
        if (!(args.first() instanceof IPersistentVector fieldVec)) throw err("defrecord: fields must be a vector");

        List<String> fieldNames = new ArrayList<>();
        for (int i = 0; i < fieldVec.count(); i++) {
            if (!(fieldVec.nth(i) instanceof Symbol fs)) throw err("defrecord: field must be a symbol");
            fieldNames.add(fs.getName());
        }
        args = args.next();

        // Parse protocol implementations (same as deftype)
        java.util.Map<String, java.util.Map<String, ExpressionNode>> protoMethods = new java.util.LinkedHashMap<>();
        String currentProto = null;
        while (args != null) {
            Object form = args.first();
            if (form instanceof Symbol protoSym) {
                currentProto = protoSym.getName();
                protoMethods.putIfAbsent(currentProto, new java.util.LinkedHashMap<>());
            } else if (form instanceof ISeq methodSeq && currentProto != null) {
                if (!(methodSeq.first() instanceof Symbol methodSym))
                    throw err("defrecord: method name must be a symbol");
                String methodName = methodSym.getName();
                ISeq methodArgs = methodSeq.next();
                if (methodArgs == null || !(methodArgs.first() instanceof IPersistentVector))
                    throw err("defrecord: method must have param vector");
                IPersistentVector methodParams = (IPersistentVector) methodArgs.first();
                ISeq methodBody = methodArgs.next();
                ExpressionNode methodFn = compileDeftypeMethod(typeName, fieldNames, methodParams, methodBody);
                protoMethods.get(currentProto).put(methodName, methodFn);
            }
            args = args.next();
        }

        List<String> capturedFieldNames = List.copyOf(fieldNames);
        java.util.Map<String, java.util.Map<String, ExpressionNode>> capturedProtoMethods =
                new java.util.LinkedHashMap<>(protoMethods);

        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                java.util.Map<String, Integer> fieldIdx = new java.util.LinkedHashMap<>();
                for (int i = 0; i < capturedFieldNames.size(); i++)
                    fieldIdx.put(capturedFieldNames.get(i), i);

                // Register ->TypeName positional constructor
                context.setVar("->" + typeName, (ClojureContext.BuiltinFunction) ctorArgs -> {
                    if (ctorArgs.length != capturedFieldNames.size())
                        throw new RuntimeException("->" + typeName + ": expected " +
                                capturedFieldNames.size() + " args");
                    return new ClojureDeftypeInstance(typeName, ctorArgs.clone(), fieldIdx);
                });

                // Register map->TypeName map-based constructor
                context.setVar("map->" + typeName, (ClojureContext.BuiltinFunction) ctorArgs -> {
                    if (ctorArgs.length != 1)
                        throw new RuntimeException("map->" + typeName + ": expected 1 arg (a map)");
                    Object mapArg = ctorArgs[0];
                    if (!(mapArg instanceof clojure.lang.IPersistentMap m))
                        throw new RuntimeException("map->" + typeName + ": arg must be a map");
                    Object[] fieldValues = new Object[capturedFieldNames.size()];
                    for (int i = 0; i < capturedFieldNames.size(); i++) {
                        clojure.lang.Keyword kw = clojure.lang.Keyword.intern(capturedFieldNames.get(i));
                        fieldValues[i] = m.valAt(kw);
                    }
                    return new ClojureDeftypeInstance(typeName, fieldValues, fieldIdx);
                });

                // Register protocol implementations
                for (var entry : capturedProtoMethods.entrySet()) {
                    String protoName = entry.getKey();
                    Object protoObj = context.getVar(protoName);
                    if (protoObj instanceof ClojureProtocol proto) {
                        java.util.Map<String, Object> methodMap = new java.util.HashMap<>();
                        for (var methodEntry : entry.getValue().entrySet()) {
                            Object fn = methodEntry.getValue().executeGeneric(frame);
                            methodMap.put(methodEntry.getKey(), fn);
                        }
                        proto.extend(typeName, methodMap);
                    }
                }
                return ClojureNil.INSTANCE;
            }
        };
    }

    private ExpressionNode compileDeftypeMethod(String typeName, List<String> fieldNames,
                                                 IPersistentVector params, ISeq body) {
        // Build: (fn* [this arg1 ...] (let [field1 (.-field1 this) ...] body...))
        // Create the let bindings for fields
        List<Object> letBindings = new ArrayList<>();
        Symbol thisSym = Symbol.intern("this");

        // Only bind fields that aren't shadowed by params
        java.util.Set<String> paramNames = new java.util.HashSet<>();
        for (int i = 0; i < params.count(); i++) {
            if (params.nth(i) instanceof Symbol s) paramNames.add(s.getName());
        }

        for (String fieldName : fieldNames) {
            if (paramNames.contains(fieldName)) continue;
            letBindings.add(Symbol.intern(fieldName));
            // (.-fieldName this)
            letBindings.add(RT.list(Symbol.intern(".-" + fieldName), thisSym));
        }

        Object wrappedBody;
        if (letBindings.isEmpty()) {
            wrappedBody = body;
        } else {
            // (let [bindings...] body...)
            IPersistentVector bindVec = PersistentVector.create(letBindings);
            wrappedBody = RT.cons(Symbol.intern("let"), RT.cons(bindVec, body));
            wrappedBody = RT.list(wrappedBody);
        }

        // (fn* [params] wrapped-body)
        ISeq fnSeq;
        if (wrappedBody instanceof ISeq ws) {
            fnSeq = RT.cons(Symbol.intern("fn*"), RT.cons(params, ws));
        } else {
            fnSeq = RT.list(Symbol.intern("fn*"), params, wrappedBody);
        }
        return analyzeFn(fnSeq);
    }

    // --- Threading macros ---

    private ExpressionNode analyzeThreadFirst(ISeq seq) {
        // (-> x (f a) (g b)) => (g (f x a) b)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object form = args.first();
        args = args.next();
        while (args != null) {
            Object step = args.first();
            if (step instanceof ISeq stepSeq) {
                // Insert form as first arg: (f a b) -> (f form a b)
                form = RT.cons(stepSeq.first(), RT.cons(form, stepSeq.next()));
            } else {
                // Bare symbol: (f) -> (f form)
                form = RT.list(step, form);
            }
            args = args.next();
        }
        return analyze(form);
    }

    private ExpressionNode analyzeThreadLast(ISeq seq) {
        // (->> x (f a) (g b)) => (g b (f a x))
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object form = args.first();
        args = args.next();
        while (args != null) {
            Object step = args.first();
            if (step instanceof ISeq stepSeq) {
                // Append form as last arg
                java.util.List<Object> newList = new ArrayList<>();
                for (ISeq s = stepSeq; s != null; s = s.next()) newList.add(s.first());
                newList.add(form);
                form = PersistentList.create(newList);
            } else {
                form = RT.list(step, form);
            }
            args = args.next();
        }
        return analyze(form);
    }

    private ExpressionNode analyzeAsThread(ISeq seq) {
        // (as-> expr name (f name) (g name)) => (let [name expr, name (f name), name (g name)] name)
        ISeq args = seq.next();
        if (args == null) throw err("as->: missing expression");
        Object expr = args.first();
        args = args.next();
        if (args == null) throw err("as->: missing name");
        if (!(args.first() instanceof Symbol nameSym)) throw err("as->: name must be a symbol");
        args = args.next();

        // Build: (let [name expr name (f name) name (g name) ...] name)
        java.util.List<Object> bindings = new ArrayList<>();
        bindings.add(nameSym);
        bindings.add(expr);
        while (args != null) {
            bindings.add(nameSym);
            bindings.add(args.first());
            args = args.next();
        }
        Object letForm = RT.list(Symbol.intern("let"),
                PersistentVector.create(bindings), nameSym);
        return analyze(letForm);
    }

    private ExpressionNode analyzeSomeThread(ISeq seq, boolean first) {
        // (some-> x f g) => (let [t x, t (if (nil? t) nil (f t)), ...] t)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object expr = args.first();
        args = args.next();
        Symbol tmpSym = Symbol.intern("__some_thread__");
        java.util.List<Object> bindings = new ArrayList<>();
        bindings.add(tmpSym);
        bindings.add(expr);
        while (args != null) {
            Object step = args.first();
            Object threadedForm;
            if (step instanceof ISeq stepSeq) {
                if (first) {
                    threadedForm = RT.cons(stepSeq.first(), RT.cons(tmpSym, stepSeq.next()));
                } else {
                    java.util.List<Object> newList = new ArrayList<>();
                    for (ISeq s = stepSeq; s != null; s = s.next()) newList.add(s.first());
                    newList.add(tmpSym);
                    threadedForm = PersistentList.create(newList);
                }
            } else {
                threadedForm = RT.list(step, tmpSym);
            }
            // (if (nil? t) nil threadedForm)
            bindings.add(tmpSym);
            bindings.add(RT.list(Symbol.intern("if"),
                    RT.list(Symbol.intern("nil?"), tmpSym),
                    null, threadedForm));
            args = args.next();
        }
        return analyze(RT.list(Symbol.intern("let"),
                PersistentVector.create(bindings), tmpSym));
    }

    private ExpressionNode analyzeCondThread(ISeq seq, boolean first) {
        // (cond-> x test1 (f) test2 (g)) => (let [t x, t (if test1 (f t) t), ...] t)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object expr = args.first();
        args = args.next();
        Symbol tmpSym = Symbol.intern("__cond_thread__");
        java.util.List<Object> bindings = new ArrayList<>();
        bindings.add(tmpSym);
        bindings.add(expr);
        while (args != null) {
            Object test = args.first();
            args = args.next();
            if (args == null) throw err("cond->: odd number of forms");
            Object step = args.first();
            Object threadedForm;
            if (step instanceof ISeq stepSeq) {
                if (first) {
                    threadedForm = RT.cons(stepSeq.first(), RT.cons(tmpSym, stepSeq.next()));
                } else {
                    java.util.List<Object> newList = new ArrayList<>();
                    for (ISeq s = stepSeq; s != null; s = s.next()) newList.add(s.first());
                    newList.add(tmpSym);
                    threadedForm = PersistentList.create(newList);
                }
            } else {
                threadedForm = RT.list(step, tmpSym);
            }
            bindings.add(tmpSym);
            bindings.add(RT.list(Symbol.intern("if"), test, threadedForm, tmpSym));
            args = args.next();
        }
        return analyze(RT.list(Symbol.intern("let"),
                PersistentVector.create(bindings), tmpSym));
    }

    private ExpressionNode analyzeDoto(ISeq seq) {
        // (doto x (.method1 a) (.method2 b)) => (let [t x] (.method1 t a) (.method2 t b) t)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object expr = args.first();
        args = args.next();
        Symbol tmpSym = Symbol.intern("__doto_tmp__");
        java.util.List<Object> body = new ArrayList<>();
        while (args != null) {
            Object step = args.first();
            if (step instanceof ISeq stepSeq) {
                body.add(RT.cons(stepSeq.first(), RT.cons(tmpSym, stepSeq.next())));
            } else {
                body.add(RT.list(step, tmpSym));
            }
            args = args.next();
        }
        body.add(tmpSym);
        java.util.List<Object> letForm = new ArrayList<>();
        letForm.add(Symbol.intern("let"));
        letForm.add(PersistentVector.create(java.util.List.of(tmpSym, expr)));
        letForm.addAll(body);
        return analyze(PersistentList.create(letForm));
    }

    private ExpressionNode analyzeDotDot(ISeq seq) {
        // (.. x method1 (method2 a)) => (. (. x method1) method2 a)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object form = args.first();
        args = args.next();
        while (args != null) {
            Object step = args.first();
            if (step instanceof Symbol sym) {
                form = RT.list(Symbol.intern("." + sym.getName()), form);
            } else if (step instanceof ISeq stepSeq) {
                form = RT.cons(Symbol.intern("." + ((Symbol) stepSeq.first()).getName()),
                        RT.cons(form, stepSeq.next()));
            }
            args = args.next();
        }
        return analyze(form);
    }

    // --- Control flow ---

    private ExpressionNode analyzeIfLet(ISeq seq) {
        // (if-let [x expr] then else) => (let [x expr] (if x then else))
        ISeq args = seq.next();
        if (args == null) throw err("if-let: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() != 2) throw err("if-let: binding must have exactly 2 forms");
        args = args.next();
        Object thenForm = args != null ? args.first() : null;
        Object elseForm = (args != null && args.next() != null) ? args.next().first() : null;
        return analyze(RT.list(Symbol.intern("let"), bindings,
                RT.list(Symbol.intern("if"), bindings.nth(0), thenForm, elseForm)));
    }

    private ExpressionNode analyzeWhenLet(ISeq seq) {
        // (when-let [x expr] body...) => (let [x expr] (when x body...))
        ISeq args = seq.next();
        if (args == null) throw err("when-let: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();
        java.util.List<Object> whenForm = new ArrayList<>();
        whenForm.add(Symbol.intern("when"));
        whenForm.add(bindings.nth(0));
        while (body != null) { whenForm.add(body.first()); body = body.next(); }
        return analyze(RT.list(Symbol.intern("let"), bindings,
                PersistentList.create(whenForm)));
    }

    private ExpressionNode analyzeIfSome(ISeq seq) {
        // (if-some [x expr] then else) - like if-let but only nil check (not false)
        ISeq args = seq.next();
        if (args == null) throw err("if-some: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        args = args.next();
        Object thenForm = args != null ? args.first() : null;
        Object elseForm = (args != null && args.next() != null) ? args.next().first() : null;
        return analyze(RT.list(Symbol.intern("let"), bindings,
                RT.list(Symbol.intern("if"),
                        RT.list(Symbol.intern("not"), RT.list(Symbol.intern("nil?"), bindings.nth(0))),
                        thenForm, elseForm)));
    }

    private ExpressionNode analyzeWhenSome(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("when-some: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();
        java.util.List<Object> whenForm = new ArrayList<>();
        whenForm.add(Symbol.intern("when"));
        whenForm.add(RT.list(Symbol.intern("not"), RT.list(Symbol.intern("nil?"), bindings.nth(0))));
        while (body != null) { whenForm.add(body.first()); body = body.next(); }
        return analyze(RT.list(Symbol.intern("let"), bindings,
                PersistentList.create(whenForm)));
    }

    private ExpressionNode analyzeCase(ISeq seq) {
        // (case expr val1 result1 val2 result2 default)
        ISeq args = seq.next();
        if (args == null) throw err("case: missing expression");
        ExpressionNode exprNode = analyze(args.first());
        args = args.next();

        int tmpSlot = currentScope.addLocal("__case_tmp__");
        List<Object[]> clauses = new ArrayList<>(); // [matchVal, resultForm]
        Object defaultForm = null;

        java.util.List<Object> formList = new ArrayList<>();
        while (args != null) {
            formList.add(args.first());
            args = args.next();
        }
        for (int i = 0; i < formList.size() - 1; i += 2) {
            clauses.add(new Object[]{formList.get(i), formList.get(i + 1)});
        }
        if (formList.size() % 2 == 1) {
            defaultForm = formList.get(formList.size() - 1);
        }

        // Build: set tmp, then chain of if (= tmp val) result ...
        ExpressionNode chain = defaultForm != null ? analyze(defaultForm) :
                new ExpressionNode() {
                    @Override
                    public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame f) {
                        throw new RuntimeException("No matching clause in case");
                    }
                };
        for (int i = clauses.size() - 1; i >= 0; i--) {
            Object matchVal = clauses.get(i)[0];
            ExpressionNode resultNode = analyze(clauses.get(i)[1]);
            ExpressionNode matchNode = analyze(matchVal);
            ExpressionNode condNode = new InvokeNode(
                    new SymbolNode(context, "="),
                    new ExpressionNode[]{new ReadLocalNode(tmpSlot), matchNode});
            chain = new IfNode(condNode, resultNode, chain);
        }

        ExpressionNode finalChain = chain;
        return new LetNode(new int[]{tmpSlot}, new ExpressionNode[]{exprNode}, finalChain);
    }

    // --- Comprehensions ---

    private ExpressionNode analyzeFor(ISeq seq) {
        // (for [x coll :when pred :let [y expr]] body)
        // Simple implementation: convert to nested map/filter/mapcat
        ISeq args = seq.next();
        if (args == null) throw err("for: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();
        if (body == null) throw err("for: missing body");

        // Build from inside out
        // Single binding: (for [x coll] body) => (map (fn [x] body) coll)
        return analyzeForBindings(bindings, 0, body);
    }

    private ExpressionNode analyzeForBindings(IPersistentVector bindings, int pos, ISeq body) {
        if (pos >= bindings.count()) {
            // All bindings consumed, analyze body
            if (body.next() == null) return analyze(body.first());
            return analyzeDo(RT.cons(Symbol.intern("do"), body));
        }

        Object key = bindings.nth(pos);

        // :when modifier
        if (key instanceof Keyword kw && kw.getName().equals("when")) {
            Object pred = bindings.nth(pos + 1);
            ExpressionNode innerNode = analyzeForBindings(bindings, pos + 2, body);
            return new IfNode(analyze(pred), innerNode, new NilNode());
        }

        // :let modifier
        if (key instanceof Keyword kw && kw.getName().equals("let")) {
            IPersistentVector letBindings = (IPersistentVector) bindings.nth(pos + 1);
            Object innerForm = buildForInner(bindings, pos + 2, body);
            return analyze(RT.list(Symbol.intern("let"), letBindings, innerForm));
        }

        // Normal binding: sym or destructuring pattern
        Object collForm = bindings.nth(pos + 1);
        Object bindForm = key; // can be Symbol or destructuring vector/map

        // For fn parameter, use a temp symbol if destructuring, then let-destructure inside
        Symbol paramSym;
        Object bodyWrapper;
        if (bindForm instanceof Symbol) {
            paramSym = (Symbol) bindForm;
            bodyWrapper = null; // no wrapping needed
        } else {
            paramSym = Symbol.intern("__for_temp__" + System.nanoTime());
            bodyWrapper = bindForm; // will wrap body with (let [pattern paramSym] ...)
        }

        if (pos + 2 >= bindings.count()) {
            // Last binding: (map (fn [sym] body) coll)
            Object bodyExpr = body.first();
            if (bodyWrapper != null) {
                bodyExpr = RT.list(Symbol.intern("let"),
                        PersistentVector.create(java.util.List.of(bodyWrapper, paramSym)), bodyExpr);
            }
            Object fnForm = RT.list(Symbol.intern("fn"),
                    PersistentVector.create(java.util.List.of(paramSym)),
                    bodyExpr);
            return analyze(RT.list(Symbol.intern("map"), fnForm, collForm));
        } else {
            // Not last: (mapcat (fn [sym] (for [rest...] body)) coll)
            IPersistentVector restBindings = PersistentVector.EMPTY;
            for (int i = pos + 2; i < bindings.count(); i++)
                restBindings = restBindings.cons(bindings.nth(i));
            Object innerFor = RT.list(Symbol.intern("for"), restBindings, body.first());
            if (bodyWrapper != null) {
                innerFor = RT.list(Symbol.intern("let"),
                        PersistentVector.create(java.util.List.of(bodyWrapper, paramSym)), innerFor);
            }
            Object fnForm = RT.list(Symbol.intern("fn"),
                    PersistentVector.create(java.util.List.of(paramSym)), innerFor);
            return analyze(RT.list(Symbol.intern("mapcat"), fnForm, collForm));
        }
    }

    private Object buildForInner(IPersistentVector bindings, int pos, ISeq body) {
        if (pos >= bindings.count()) return body.first();
        // Rebuild remaining as (for [rest...] body)
        IPersistentVector restBindings = PersistentVector.EMPTY;
        for (int i = pos; i < bindings.count(); i++)
            restBindings = restBindings.cons(bindings.nth(i));
        return RT.list(Symbol.intern("for"), restBindings, body.first());
    }

    private ExpressionNode analyzeDoseq(ISeq seq) {
        // (doseq [x coll] body...) => (dorun (for [x coll] (do body...)))
        ISeq args = seq.next();
        if (args == null) throw err("doseq: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();

        // Simple: iterate and execute for side effects
        // (doseq [x coll] body) => (let [s (seq coll)] (loop [] (when s (let [x (first s)] body (recur (next s)))))
        // Actually simpler: just use eager for with dorun
        java.util.List<Object> doBody = new ArrayList<>();
        doBody.add(Symbol.intern("do"));
        while (body != null) { doBody.add(body.first()); body = body.next(); }
        Object forForm = RT.list(Symbol.intern("for"), bindings, PersistentList.create(doBody));
        return analyze(RT.list(Symbol.intern("dorun"), forForm));
    }

    private ExpressionNode analyzeDotimes(ISeq seq) {
        // (dotimes [i n] body...) => (let [n# n] (loop [i 0] (when (< i n#) body... (recur (inc i)))))
        ISeq args = seq.next();
        if (args == null) throw err("dotimes: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() != 2) throw err("dotimes: binding must have exactly 2 forms");
        Symbol iSym = (Symbol) bindings.nth(0);
        Object nForm = bindings.nth(1);
        ISeq body = args.next();

        Symbol nSym = Symbol.intern("__dotimes_n__");
        java.util.List<Object> loopBody = new ArrayList<>();
        loopBody.add(Symbol.intern("when"));
        loopBody.add(RT.list(Symbol.intern("<"), iSym, nSym));
        while (body != null) { loopBody.add(body.first()); body = body.next(); }
        loopBody.add(RT.list(Symbol.intern("recur"), RT.list(Symbol.intern("inc"), iSym)));

        Object loopForm = RT.list(Symbol.intern("loop"),
                PersistentVector.create(java.util.List.of(iSym, 0L)),
                PersistentList.create(loopBody));
        return analyze(RT.list(Symbol.intern("let"),
                PersistentVector.create(java.util.List.of(nSym, nForm)), loopForm));
    }

    private ExpressionNode analyzeLetfn(ISeq seq) {
        // (letfn [(f [x] body) (g [y] body)] expr)
        // => (let [f nil g nil] (set! f (fn f [x] body)) (set! g (fn g [y] body)) expr)
        // Actually simpler: use forward declarations via def
        ISeq args = seq.next();
        if (args == null) throw err("letfn: missing bindings");
        IPersistentVector fnBindings = (IPersistentVector) args.first();
        ISeq body = args.next();

        // First pass: declare all names
        List<String> names = new ArrayList<>();
        for (int i = 0; i < fnBindings.count(); i++) {
            ISeq fnSpec = (ISeq) fnBindings.nth(i);
            names.add(((Symbol) fnSpec.first()).getName());
        }

        // Allocate slots and set to nil
        List<Integer> slots = new ArrayList<>();
        List<ExpressionNode> values = new ArrayList<>();
        for (String name : names) {
            slots.add(currentScope.addLocal(name));
            values.add(new NilNode());
        }

        // Second pass: compile fns (they can reference each other via locals)
        List<ExpressionNode> assignments = new ArrayList<>();
        for (int i = 0; i < fnBindings.count(); i++) {
            ISeq fnSpec = (ISeq) fnBindings.nth(i);
            Symbol fnName = (Symbol) fnSpec.first();
            ISeq fnArgs = fnSpec.next();
            ISeq fnForm = RT.cons(Symbol.intern("fn"), RT.cons(fnName, fnArgs));
            ExpressionNode fnNode = analyze(fnForm);
            // Assignment: set the slot
            int slot = slots.get(i);
            assignments.add(new ExpressionNode() {
                @Child ExpressionNode valueNode = fnNode;
                final int s = slot;
                @Override
                public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                    Object val = valueNode.executeGeneric(frame);
                    frame.setObject(s, val);
                    return val;
                }
            });
        }

        ExpressionNode bodyNode = analyzeBody(body);

        // Combine: let [names nil...], then assignments, then body
        ExpressionNode[] allAssignments = assignments.toArray(new ExpressionNode[0]);
        ExpressionNode assignAndBody = new DoNode(
                java.util.stream.Stream.concat(
                        java.util.Arrays.stream(allAssignments),
                        java.util.stream.Stream.of(bodyNode)
                ).toArray(ExpressionNode[]::new));

        return new LetNode(
                slots.stream().mapToInt(Integer::intValue).toArray(),
                values.toArray(new ExpressionNode[0]),
                assignAndBody);
    }

    // --- Namespace ---

    private ExpressionNode analyzeNs(ISeq seq) {
        // (ns my.ns
        //   (:require [some.ns :as s] [other.ns :refer [foo]])
        //   (:use [lib.ns :only [bar]])
        //   (:import [java.util ArrayList HashMap])
        //   (:refer-clojure :exclude [get]))
        ISeq args = seq.next();
        if (args == null) throw err("ns: missing name");
        if (!(args.first() instanceof Symbol nsSym)) throw err("ns: name must be a symbol");
        String nsName = nsSym.getName();
        args = args.next();

        // Collect directives
        List<Object> requireSpecs = new ArrayList<>();
        List<Object> useSpecs = new ArrayList<>();
        List<Object> importSpecs = new ArrayList<>();
        java.util.Set<String> referClojureExcludes = new java.util.HashSet<>();
        boolean hasReferClojure = false;

        while (args != null) {
            Object directive = args.first();
            if (directive instanceof ISeq ds) {
                Object head = ds.first();
                if (head instanceof Keyword kw) {
                    switch (kw.getName()) {
                        case "require":
                            for (ISeq specs = ds.next(); specs != null; specs = specs.next())
                                requireSpecs.add(specs.first());
                            break;
                        case "use":
                            for (ISeq specs = ds.next(); specs != null; specs = specs.next())
                                useSpecs.add(specs.first());
                            break;
                        case "import":
                            for (ISeq specs = ds.next(); specs != null; specs = specs.next())
                                importSpecs.add(specs.first());
                            break;
                        case "refer-clojure":
                            hasReferClojure = true;
                            for (ISeq rc = ds.next(); rc != null; rc = rc.next()) {
                                if (rc.first() instanceof Keyword ek && ek.getName().equals("exclude")) {
                                    rc = rc.next();
                                    if (rc != null && rc.first() instanceof IPersistentVector ev) {
                                        for (int i = 0; i < ev.count(); i++)
                                            referClojureExcludes.add(((Symbol) ev.nth(i)).getName());
                                    }
                                }
                            }
                            break;
                    }
                }
            }
            // Skip docstrings and metadata maps
            args = args.next();
        }

        List<Object> capturedRequires = List.copyOf(requireSpecs);
        List<Object> capturedUses = List.copyOf(useSpecs);
        List<Object> capturedImports = List.copyOf(importSpecs);
        java.util.Set<String> capturedExcludes = java.util.Set.copyOf(referClojureExcludes);
        boolean capturedHasReferClojure = hasReferClojure;

        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                context.getOrCreateNamespace(nsName);
                context.setCurrentNamespace(nsName);
                ClojureNamespace ns = context.getNamespace(nsName);
                ClojureNamespace core = context.getNamespace("clojure.core");
                // Refer clojure.core (with optional exclusions)
                if (core != null) {
                    if (capturedHasReferClojure && !capturedExcludes.isEmpty()) {
                        ns.referWithExclude(core, capturedExcludes);
                    } else {
                        ns.referAll(core);
                    }
                }
                // Process requires
                for (Object spec : capturedRequires) processRequireSpec(spec);
                // Process uses
                for (Object spec : capturedUses) processUseSpec(spec);
                // Process imports
                for (Object spec : capturedImports) processImportSpec(spec);
                return ClojureNil.INSTANCE;
            }
        };
    }

    private ExpressionNode analyzeInNs(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("in-ns: missing name");
        ExpressionNode nameNode = analyze(args.first());
        return new ExpressionNode() {
            @Child ExpressionNode nsNode = nameNode;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object name = nsNode.executeGeneric(frame);
                String nsName;
                if (name instanceof clojure.lang.Symbol sym) nsName = sym.getName();
                else nsName = name.toString();
                context.getOrCreateNamespace(nsName);
                context.setCurrentNamespace(nsName);
                return ClojureNil.INSTANCE;
            }
        };
    }

    private ExpressionNode analyzeRequire(ISeq seq) {
        ISeq args = seq.next();
        List<Object> specs = new ArrayList<>();
        while (args != null) {
            specs.add(args.first());
            args = args.next();
        }
        List<Object> capturedSpecs = List.copyOf(specs);
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                for (Object spec : capturedSpecs) processRequireSpec(spec);
                return ClojureNil.INSTANCE;
            }
        };
    }

    private void processRequireSpec(Object spec) {
        // Unwrap quote: (quote foo) -> foo
        if (spec instanceof ISeq qs) {
            Object first = qs.first();
            if (first instanceof Symbol s && s.getName().equals("quote")) {
                spec = qs.next().first();
            }
        }
        if (spec instanceof Symbol sym) {
            // Simple require: (require 'some.ns)
            context.loadNamespace(sym.getName());
        } else if (spec instanceof IPersistentVector v) {
            // [some.ns :as s] or [some.ns :refer [foo bar]]
            if (v.count() == 0) return;
            String nsName = ((Symbol) v.nth(0)).getName();
            context.loadNamespace(nsName);
            ClojureNamespace reqNs = context.getNamespace(nsName);
            ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
            if (reqNs == null || currentNs == null) return;

            // First pass: collect all options
            String alias = null;
            Object referSpec = null;
            java.util.Map<String, String> renames = null;
            for (int i = 1; i < v.count(); i += 2) {
                Object key = v.nth(i);
                if (key instanceof Keyword kw && i + 1 < v.count()) {
                    switch (kw.getName()) {
                        case "as": alias = ((Symbol) v.nth(i + 1)).getName(); break;
                        case "refer": referSpec = v.nth(i + 1); break;
                        case "rename": renames = extractRenameMap((IPersistentMap) v.nth(i + 1)); break;
                    }
                }
            }
            // Second pass: apply
            if (alias != null) {
                currentNs.alias(alias, reqNs);
                // Also register in Clojure's namespace for ::alias/keyword reader support
                try {
                    clojure.lang.Namespace clojureCurrentNs = clojure.lang.Namespace.findOrCreate(
                            clojure.lang.Symbol.intern(context.getCurrentNamespace()));
                    clojure.lang.Namespace clojureReqNs = clojure.lang.Namespace.findOrCreate(
                            clojure.lang.Symbol.intern(nsName));
                    clojureCurrentNs.addAlias(clojure.lang.Symbol.intern(alias), clojureReqNs);
                } catch (Exception ignored) {}
            }
            if (referSpec != null) {
                if (referSpec instanceof Keyword rk && rk.getName().equals("all")) {
                    if (renames != null) {
                        currentNs.referWithRename(reqNs, renames);
                    } else {
                        currentNs.referAll(reqNs);
                    }
                } else if (referSpec instanceof IPersistentVector rv) {
                    for (int j = 0; j < rv.count(); j++) {
                        String symName = ((Symbol) rv.nth(j)).getName();
                        Object val = reqNs.resolve(symName);
                        if (val != null) {
                            String targetName = (renames != null && renames.containsKey(symName))
                                    ? renames.get(symName) : symName;
                            currentNs.refer(targetName, val);
                        }
                    }
                }
            }
        }
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
        Object symsVec = pattern.valAt(Keyword.intern("syms"));
        Object orMap = pattern.valAt(Keyword.intern("or"));
        Object asName = pattern.valAt(Keyword.intern("as"));
        IPersistentMap defaults = (orMap instanceof IPersistentMap m) ? m : null;

        // :keys [a b] or :keys [:a :b] -> bind a to (:a source), b to (:b source)
        if (keysVec instanceof IPersistentVector kv) {
            for (int i = 0; i < kv.count(); i++) {
                Object elem = kv.nth(i);
                String localName;
                Keyword key;
                if (elem instanceof Symbol sym) {
                    localName = sym.getName();
                    key = Keyword.intern(localName);
                } else if (elem instanceof Keyword kw) {
                    localName = kw.getName();
                    key = kw;
                } else {
                    throw err("Unsupported :keys element: " + elem);
                }
                ExpressionNode getExpr = makeGetNode(sourceSlot, key, defaults, Symbol.intern(localName));
                slots.add(currentScope.addLocal(localName));
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

        // :syms [a b] -> bind a to ('a source), b to ('b source)
        if (symsVec instanceof IPersistentVector yv) {
            for (int i = 0; i < yv.count(); i++) {
                Symbol sym = (Symbol) yv.nth(i);
                Symbol key = Symbol.intern(sym.getName());
                // Use QuoteNode directly so the symbol is treated as a literal value, not a var reference
                ExpressionNode getExpr = makeGetNodeQuoted(sourceSlot, key, defaults, sym);
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

    // Helper: (nthnext source index) - returns nil when no elements remain
    private ExpressionNode makeNthnextNode(int sourceSlot, int index) {
        return new InvokeNode(
                new SymbolNode(context, "nthnext"),
                new ExpressionNode[]{new ReadLocalNode(sourceSlot), new LongLiteralNode(index)});
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

    private ExpressionNode makeGetNodeQuoted(int sourceSlot, Object key, IPersistentMap defaults, Symbol sym) {
        ExpressionNode[] getArgs;
        Object defaultVal = defaults != null ? defaults.valAt(sym) : null;
        if (defaultVal != null) {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new QuoteNode(key), analyze(defaultVal)};
        } else {
            getArgs = new ExpressionNode[]{
                    new ReadLocalNode(sourceSlot), new QuoteNode(key)};
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
        Symbol sym = (Symbol) args.first();
        String name = sym.getName();

        // Extract metadata from the symbol (^:dynamic, ^:private, ^{...})
        boolean isDynamic = false;
        boolean isPrivate = false;
        String docString = null;
        clojure.lang.IPersistentMap meta = sym.meta();
        if (meta != null) {
            Object dynVal = meta.valAt(clojure.lang.Keyword.intern("dynamic"));
            if (Boolean.TRUE.equals(dynVal)) isDynamic = true;
            Object privVal = meta.valAt(clojure.lang.Keyword.intern("private"));
            if (Boolean.TRUE.equals(privVal)) isPrivate = true;
            Object docVal = meta.valAt(clojure.lang.Keyword.intern("doc"));
            if (docVal instanceof String s) docString = s;
        }
        // Also detect dynamic vars by earmuff convention
        if (name.startsWith("*") && name.endsWith("*") && name.length() > 2) {
            isDynamic = true;
        }

        args = args.next();

        // Check for docstring before value: (def name "doc" value)
        if (args != null && args.next() != null && args.first() instanceof String s) {
            if (docString == null) docString = s;
            args = args.next();
        }

        ExpressionNode valueNode = (args != null) ? analyze(args.first()) : new NilNode();

        if (isDynamic) {
            context.declareDynamic(name);
        }

        return new DefNode(context, name, valueNode);
    }

    private ExpressionNode analyzeLet(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("let*: missing bindings");
        if (!(args.first() instanceof IPersistentVector))
            throw err("let*: bindings must be a vector");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() % 2 != 0) throw err("let*: odd number of binding forms");

        // Save current locals to restore after let scope
        Map<String, Integer> savedLocals = new LinkedHashMap<>(currentScope.locals);

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
        ExpressionNode body = analyzeBody(args.next());

        // Restore locals (let bindings go out of scope)
        currentScope.locals.clear();
        currentScope.locals.putAll(savedLocals);

        return new LetNode(slots, values, body);
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

        // Skip docstring if present
        if (args.first() instanceof String) {
            args = args.next();
            if (args == null) throw err("fn*: missing parameters after docstring");
        }

        // Skip metadata map if present (e.g., {:added "1.1"})
        if (args.first() instanceof clojure.lang.IPersistentMap && !(args.first() instanceof clojure.lang.IPersistentVector)) {
            args = args.next();
            if (args == null) throw err("fn*: missing parameters after metadata map");
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

        // Add self-reference slot for named fns
        int selfSlot = -1;
        if (fnName != null) {
            selfSlot = currentScope.addLocal(fnName);
        }

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
                paramSlots, variadicSlot, innerCaptureSlots, bodyNode, selfSlot);

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

            // Add self-reference slot for named fns
            int selfSlot = -1;
            if (fnName != null) {
                selfSlot = currentScope.addLocal(fnName);
            }

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
                    paramSlots, varSlot, innerCaptures, bodyNode, selfSlot);
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
                } else if (varParam instanceof IPersistentMap) {
                    // & {:keys [...]} — keyword argument destructuring
                    // Rest args arrive as a seq like (:req-un [:a] :opt [:b])
                    // Must convert to map via (apply hash-map seq) before map destructuring
                    String tmpName = "__var_rest_" + i;
                    varSlot = currentScope.addLocal(tmpName);
                    if (destructSlots != null) {
                        String mapTmpName = "__var_map_" + i;
                        int mapSlot = currentScope.addLocal(mapTmpName);
                        // (apply hash-map __var_rest_N)
                        ExpressionNode applyHashMap = new InvokeNode(
                                new SymbolNode(context, "apply"),
                                new ExpressionNode[]{
                                        new SymbolNode(context, "hash-map"),
                                        new ReadLocalNode(varSlot)
                                });
                        destructSlots.add(mapSlot);
                        destructValues.add(applyHashMap);
                        expandMapDestructuring((IPersistentMap) varParam, mapSlot, destructSlots, destructValues);
                    }
                } else {
                    // Destructured variadic param (vector pattern)
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

        // Check for destructuring bindings - convert to simple bindings with let
        boolean hasDestructuring = false;
        int count = bindings.count() / 2;
        for (int i = 0; i < count; i++) {
            if (!(bindings.nth(i * 2) instanceof Symbol)) {
                hasDestructuring = true;
                break;
            }
        }

        if (hasDestructuring) {
            // Convert destructuring loop to:
            // (loop [temp1 init1 temp2 init2 ...]
            //   (let [pattern1 temp1 pattern2 temp2 ...] body...))
            IPersistentVector newBindings = PersistentVector.EMPTY;
            IPersistentVector letBindings = PersistentVector.EMPTY;
            for (int i = 0; i < count; i++) {
                Object pattern = bindings.nth(i * 2);
                Object init = bindings.nth(i * 2 + 1);
                if (pattern instanceof Symbol) {
                    newBindings = newBindings.cons(pattern);
                    newBindings = newBindings.cons(init);
                } else {
                    Symbol temp = Symbol.intern("__loop_temp__" + i + "__" + System.nanoTime());
                    newBindings = newBindings.cons(temp);
                    newBindings = newBindings.cons(init);
                    letBindings = letBindings.cons(pattern);
                    letBindings = letBindings.cons(temp);
                }
            }
            // Build (loop [temps...] (let [destructured...] body...))
            List<Object> letForm = new ArrayList<>();
            letForm.add(Symbol.intern("let"));
            letForm.add(letBindings);
            ISeq body = args.next();
            while (body != null) {
                letForm.add(body.first());
                body = body.next();
            }
            ISeq newSeq = RT.list(Symbol.intern("loop"), newBindings,
                    PersistentList.create(letForm));
            return analyzeLoop(newSeq);
        }

        int[] slots = new int[count];
        ExpressionNode[] values = new ExpressionNode[count];
        for (int i = 0; i < count; i++) {
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

    private ExpressionNode analyzeDefn(ISeq seq, boolean isPrivate) {
        ISeq args = seq.next();
        if (args == null) throw err("defn: missing name");
        if (!(args.first() instanceof Symbol)) throw err("defn: name must be a symbol");
        Symbol nameSym = (Symbol) args.first();
        String name = nameSym.getName();

        // Check metadata on the symbol
        boolean isDynamic = false;
        clojure.lang.IPersistentMap meta = nameSym.meta();
        if (meta != null) {
            Object dynVal = meta.valAt(clojure.lang.Keyword.intern("dynamic"));
            if (Boolean.TRUE.equals(dynVal)) isDynamic = true;
            Object privVal = meta.valAt(clojure.lang.Keyword.intern("private"));
            if (Boolean.TRUE.equals(privVal)) isPrivate = true;
        }

        if (isDynamic) {
            context.declareDynamic(name);
        }

        ISeq fnForm = RT.cons(Symbol.intern("fn*"), args);
        ExpressionNode fnNode = analyzeFn(fnForm);
        return new DefNode(context, name, fnNode);
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

    private ExpressionNode analyzeSet(clojure.lang.IPersistentSet set) {
        List<ExpressionNode> elements = new ArrayList<>();
        for (ISeq s = set.seq(); s != null; s = s.next()) {
            elements.add(analyze(s.first()));
        }
        return new SetLiteralNode(elements.toArray(new ExpressionNode[0]));
    }

    // --- Helpers ---

    private ExpressionNode analyzeBody(ISeq body) {
        if (body == null) return new NilNode();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (body != null) { nodes.add(analyze(body.first())); body = body.next(); }
        if (nodes.size() == 1) return nodes.get(0);
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeBinding(ISeq seq) {
        // (binding [*var* val *var2* val2 ...] body...)
        ISeq args = seq.next();
        if (args == null) throw err("binding: missing bindings vector");
        IPersistentVector bindings = (IPersistentVector) args.first();
        if (bindings.count() % 2 != 0) throw err("binding: bindings must have even number of forms");
        int numBindings = bindings.count() / 2;
        String[] varNames = new String[numBindings];
        ExpressionNode[] valueNodes = new ExpressionNode[numBindings];
        for (int i = 0; i < numBindings; i++) {
            Symbol sym = (Symbol) bindings.nth(i * 2);
            varNames[i] = sym.getName();
            context.declareDynamic(varNames[i]);
            valueNodes[i] = analyze(bindings.nth(i * 2 + 1));
        }
        args = args.next();
        List<ExpressionNode> body = new ArrayList<>();
        while (args != null) { body.add(analyze(args.first())); args = args.next(); }
        return new clojure.truffle.nodes.BindingNode(context, varNames, valueNodes,
                body.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeWhenNot(ISeq seq) {
        // (when-not test body...) => (if (not test) (do body...) nil)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object test = args.first();
        args = args.next();
        List<Object> body = new ArrayList<>();
        while (args != null) { body.add(args.first()); args = args.next(); }
        List<Object> doBody = new ArrayList<>();
        doBody.add(Symbol.intern("do"));
        doBody.addAll(body);
        return analyze(RT.list(Symbol.intern("if"),
                RT.list(Symbol.intern("not"), test),
                PersistentList.create(doBody),
                null));
    }

    private ExpressionNode analyzeIfNot(ISeq seq) {
        // (if-not test then else) => (if (not test) then else)
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object test = args.first();
        args = args.next();
        Object thenForm = args != null ? args.first() : null;
        Object elseForm = (args != null && args.next() != null) ? args.next().first() : null;
        return analyze(RT.list(Symbol.intern("if"),
                RT.list(Symbol.intern("not"), test),
                thenForm, elseForm));
    }

    private ExpressionNode analyzeCondp(ISeq seq) {
        // (condp pred expr clause...) where clause is: test-val result or test-val :>> fn
        ISeq args = seq.next();
        if (args == null) throw err("condp: missing pred");
        Object pred = args.first(); args = args.next();
        if (args == null) throw err("condp: missing expr");
        Object expr = args.first(); args = args.next();

        Symbol tmpSym = Symbol.intern("__condp_val__");
        List<Object> body = new ArrayList<>();
        while (args != null) {
            Object testVal = args.first();
            args = args.next();
            if (args == null) {
                // Default clause (no pair) — testVal is the default result
                body.add(testVal);
                break;
            }
            Object result = args.first();
            args = args.next();
            // (if (pred testVal tmpSym) result ...)
            body.add(0, RT.list(Symbol.intern("if"),
                    RT.list(pred, testVal, tmpSym),
                    result,
                    null)); // placeholder
        }
        // Chain the conditions: replace null placeholders
        if (body.isEmpty()) return new NilNode();
        // Build nested if from inside out
        Object result = body.get(body.size() - 1);
        // Check if last element is a standalone default or an if
        for (int i = body.size() - 1; i >= 0; i--) {
            Object item = body.get(i);
            if (item instanceof ISeq s && Symbol.intern("if").equals(s.first())) {
                // Replace the nil (else) with the current accumulated result
                // (if test then nil) -> (if test then result)
                ISeq ifArgs = s.next();
                Object cond = ifArgs.first();
                Object then = ifArgs.next().first();
                item = RT.list(Symbol.intern("if"), cond, then, result);
                result = item;
            } else if (i < body.size() - 1) {
                // shouldn't happen
                result = item;
            }
        }
        // Wrap in let to evaluate expr once
        return analyze(RT.list(Symbol.intern("let"),
                PersistentVector.create(java.util.List.of(tmpSym, expr)),
                result));
    }

    private ExpressionNode analyzeWhile(ISeq seq) {
        // (while test body...) - expand to loop/recur with if
        ISeq args = seq.next();
        if (args == null) return new NilNode();
        Object test = args.first();
        args = args.next();
        List<Object> body = new ArrayList<>();
        while (args != null) { body.add(args.first()); args = args.next(); }
        // (loop [] (when test body... (recur)))
        List<Object> whenBody = new ArrayList<>();
        whenBody.add(Symbol.intern("when"));
        whenBody.add(test);
        whenBody.addAll(body);
        whenBody.add(RT.list(Symbol.intern("recur")));
        return analyze(RT.list(Symbol.intern("loop"),
                PersistentVector.EMPTY,
                PersistentList.create(whenBody)));
    }

    private ExpressionNode analyzeDeclare(ISeq seq) {
        // (declare name1 name2 ...) - forward declarations
        // Always use core def, not any user-defined def macro
        ISeq args = seq.next();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) {
            Symbol sym = (Symbol) args.first();
            nodes.add(analyzeDef(RT.list(Symbol.intern("def"), sym)));
            args = args.next();
        }
        if (nodes.isEmpty()) return new NilNode();
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeDelay(ISeq seq) {
        // (delay body) => (delay (fn [] body))
        ISeq args = seq.next();
        List<Object> body = new ArrayList<>();
        while (args != null) { body.add(args.first()); args = args.next(); }
        List<Object> doForm = new ArrayList<>();
        doForm.add(Symbol.intern("do"));
        doForm.addAll(body);
        Object fnForm = RT.list(Symbol.intern("fn"), PersistentVector.EMPTY, PersistentList.create(doForm));
        // Call the builtin delay function directly, not recurse through analyze
        ExpressionNode fnNode = analyze(fnForm);
        return new InvokeNode(new SymbolNode(context, "delay"), new ExpressionNode[]{fnNode});
    }

    private ExpressionNode analyzeExtendType(ISeq seq) {
        // (extend-type Type Protocol (method [this args] body) ...)
        ISeq args = seq.next();
        if (args == null) throw err("extend-type: missing type");
        Object typeForm = args.first();
        args = args.next();

        // For deftype types (symbols that aren't Java classes), use the type name as string key
        ExpressionNode typeNode;
        if (typeForm instanceof Symbol typeSym) {
            // Try Java class first
            try {
                Class<?> clazz = JavaInteropUtil.resolveClass(typeSym.getName());
                typeNode = new QuoteNode(clazz);
            } catch (RuntimeException e) {
                // Not a Java class - use type name string (for deftype types)
                typeNode = new QuoteNode(typeSym.getName());
            }
        } else {
            typeNode = analyze(typeForm);
        }

        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) {
            // Protocol name
            Object protoForm = args.first();
            args = args.next();
            // Collect methods until next symbol (protocol) or end
            java.util.Map<String, Object> methodForms = new java.util.LinkedHashMap<>();
            while (args != null && args.first() instanceof ISeq) {
                ISeq methodDef = (ISeq) args.first();
                String methodName = ((Symbol) methodDef.first()).getName();
                // Build fn form
                List<Object> fnParts = new ArrayList<>();
                fnParts.add(Symbol.intern("fn"));
                ISeq rest = methodDef.next();
                while (rest != null) { fnParts.add(rest.first()); rest = rest.next(); }
                methodForms.put(methodName, PersistentList.create(fnParts));
                args = args.next();
            }
            nodes.add(new ExtendTypeNode(context, typeNode, analyze(protoForm),
                    methodForms, this));
        }
        if (nodes.size() == 1) return nodes.get(0);
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeExtendProtocol(ISeq seq) {
        // (extend-protocol Protocol Type1 (method ...) Type2 (method ...) ...)
        ISeq args = seq.next();
        if (args == null) throw err("extend-protocol: missing protocol");
        Object protoForm = args.first();
        args = args.next();

        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) {
            // Type name
            Object typeForm = args.first();
            args = args.next();
            java.util.Map<String, Object> methodForms = new java.util.LinkedHashMap<>();
            while (args != null && args.first() instanceof ISeq) {
                ISeq methodDef = (ISeq) args.first();
                String methodName = ((Symbol) methodDef.first()).getName();
                List<Object> fnParts = new ArrayList<>();
                fnParts.add(Symbol.intern("fn"));
                ISeq rest = methodDef.next();
                while (rest != null) { fnParts.add(rest.first()); rest = rest.next(); }
                methodForms.put(methodName, PersistentList.create(fnParts));
                args = args.next();
            }
            // Resolve type: Java class or deftype name string
            ExpressionNode typeNode;
            if (typeForm instanceof Symbol typeSym) {
                try {
                    Class<?> clazz = JavaInteropUtil.resolveClass(typeSym.getName());
                    typeNode = new QuoteNode(clazz);
                } catch (RuntimeException e) {
                    typeNode = new QuoteNode(typeSym.getName());
                }
            } else {
                typeNode = analyze(typeForm);
            }
            nodes.add(new ExtendTypeNode(context, typeNode, analyze(protoForm),
                    methodForms, this));
        }
        if (nodes.isEmpty()) return new NilNode();
        if (nodes.size() == 1) return nodes.get(0);
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private void processImportSpec(Object spec) {
        if (spec instanceof Symbol sym) {
            // (import java.util.ArrayList) - import single class
            String fqn = sym.getName();
            try {
                Class<?> clazz = Class.forName(fqn);
                String simpleName = clazz.getSimpleName();
                context.setVar(simpleName, clazz);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("import: class not found: " + fqn);
            }
        } else if (spec instanceof IPersistentVector v) {
            // [java.util ArrayList HashMap] - package prefix form
            if (v.count() < 2) return;
            String pkg = ((Symbol) v.nth(0)).getName();
            for (int i = 1; i < v.count(); i++) {
                String className = ((Symbol) v.nth(i)).getName();
                String fqn = pkg + "." + className;
                try {
                    Class<?> clazz = Class.forName(fqn);
                    context.setVar(className, clazz);
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("import: class not found: " + fqn);
                }
            }
        } else if (spec instanceof ISeq sl) {
            // (java.util ArrayList HashMap) - list form
            Object first = sl.first();
            if (first instanceof Symbol pkgSym) {
                String pkg = pkgSym.getName();
                for (ISeq rest = sl.next(); rest != null; rest = rest.next()) {
                    String className = ((Symbol) rest.first()).getName();
                    String fqn = pkg + "." + className;
                    try {
                        Class<?> clazz = Class.forName(fqn);
                        context.setVar(className, clazz);
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException("import: class not found: " + fqn);
                    }
                }
            }
        }
    }

    // Expose analyze for ExtendTypeNode
    public ExpressionNode analyzePublic(Object form) {
        return analyze(form);
    }

    private ExpressionNode analyzeWithOpen(ISeq seq) {
        // (with-open [r (resource)] body...) => (let [r (resource)] (try body... (finally (.close r))))
        ISeq args = seq.next();
        if (args == null) throw err("with-open: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        args = args.next();
        // Collect body forms
        List<Object> body = new ArrayList<>();
        while (args != null) { body.add(args.first()); args = args.next(); }
        // Build finally clause that closes all resources
        List<Object> finallyBody = new ArrayList<>();
        for (int i = 0; i < bindings.count(); i += 2) {
            Symbol sym = (Symbol) bindings.nth(i);
            finallyBody.add(RT.list(Symbol.intern(".close"), sym));
        }
        // Build: (let [bindings] (try body... (finally close-forms...)))
        List<Object> tryForm = new ArrayList<>();
        tryForm.add(Symbol.intern("try"));
        tryForm.addAll(body);
        List<Object> finallyForm = new ArrayList<>();
        finallyForm.add(Symbol.intern("finally"));
        finallyForm.addAll(finallyBody);
        tryForm.add(PersistentList.create(finallyForm));

        return analyze(RT.list(Symbol.intern("let"), bindings, PersistentList.create(tryForm)));
    }

    private ExpressionNode analyzeWithOutStr(ISeq seq) {
        // (with-out-str body...) => wrap body in fn thunk, invoke with-out-str builtin
        ISeq body = seq.next();
        Object fnForm = RT.cons(Symbol.intern("fn"),
                RT.cons(PersistentVector.EMPTY, body));
        ExpressionNode thunkNode = analyze(fnForm);
        ExpressionNode wosNode = new SymbolNode(context, "with-out-str");
        return new InvokeNode(wosNode, new ExpressionNode[]{thunkNode});
    }

    private ExpressionNode analyzeFuture(ISeq seq) {
        // (future body...) => (future-call (fn [] body...))
        ISeq body = seq.next();
        Object fnForm = RT.cons(Symbol.intern("fn"),
                RT.cons(PersistentVector.EMPTY, body));
        ExpressionNode thunkNode = analyze(fnForm);
        ExpressionNode futureCallNode = new SymbolNode(context, "future-call");
        return new InvokeNode(futureCallNode, new ExpressionNode[]{thunkNode});
    }

    private ExpressionNode analyzeLocking(ISeq seq) {
        // (locking expr body...) => synchronized on expr, execute body
        ISeq args = seq.next();
        if (args == null) throw err("locking: missing lock expression");
        ExpressionNode lockNode = analyze(args.first());
        ISeq body = args.next();
        ExpressionNode bodyNode = (body != null) ? analyzeBody(body) : new NilNode();
        return new ExpressionNode() {
            @Child private ExpressionNode lockExpr = lockNode;
            @Child private ExpressionNode bodyExpr = bodyNode;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object lock = lockExpr.executeGeneric(frame);
                synchronized (lock) {
                    return bodyExpr.executeGeneric(frame);
                }
            }
        };
    }

    private ExpressionNode analyzeReify(ISeq seq) {
        // (reify Protocol/Interface (method [args] body)...)
        ISeq args = seq.next();
        if (args == null) return new NilNode();

        // Collect interface/protocol names and methods
        List<Class<?>> javaInterfaces = new ArrayList<>();
        List<Symbol> protocolNames = new ArrayList<>();
        java.util.Map<String, Object> methods = new java.util.LinkedHashMap<>();
        java.util.Map<String, List<List<Object>>> methodArities = new java.util.LinkedHashMap<>();

        while (args != null) {
            Object item = args.first();
            if (item instanceof Symbol sym) {
                // Try to resolve as Java interface
                try {
                    Class<?> clazz = JavaInteropUtil.resolveClass(sym.getName());
                    if (clazz.isInterface()) {
                        javaInterfaces.add(clazz);
                    } else {
                        protocolNames.add(sym);
                    }
                } catch (RuntimeException e) {
                    Object varVal = context.getVar(sym.getName());
                    if (varVal instanceof Class<?> c && c.isInterface()) {
                        javaInterfaces.add(c);
                    } else {
                        protocolNames.add(sym);
                    }
                }
            } else if (item instanceof ISeq methodDef) {
                String methodName = ((Symbol) methodDef.first()).getName();
                ISeq rest = methodDef.next();
                // Collect params and body as a single arity: ([params] body...)
                List<Object> arityForm = new ArrayList<>();
                while (rest != null) { arityForm.add(rest.first()); rest = rest.next(); }
                if (methods.containsKey(methodName)) {
                    // Multi-arity: existing must already be in multi-arity format
                    @SuppressWarnings("unchecked")
                    List<List<Object>> arities = (List<List<Object>>) methodArities.get(methodName);
                    arities.add(arityForm);
                } else {
                    List<List<Object>> arities = new ArrayList<>();
                    arities.add(arityForm);
                    methodArities.put(methodName, arities);
                    methods.put(methodName, null); // placeholder
                }
            }
            args = args.next();
        }

        // Build fn forms from collected arities
        for (var entry : methodArities.entrySet()) {
            String mName = entry.getKey();
            List<List<Object>> arities = entry.getValue();
            List<Object> fnForm = new ArrayList<>();
            fnForm.add(Symbol.intern("fn"));
            if (arities.size() == 1) {
                // Single arity: (fn [params] body...)
                fnForm.addAll(arities.get(0));
            } else {
                // Multi-arity: (fn ([params1] body1...) ([params2] body2...))
                for (List<Object> arity : arities) {
                    fnForm.add(PersistentList.create(arity));
                }
            }
            methods.put(mName, PersistentList.create(fnForm));
        }

        List<ExpressionNode> methodNodeList = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        for (var entry : methods.entrySet()) {
            methodNames.add(entry.getKey());
            methodNodeList.add(analyze(entry.getValue()));
        }

        // If there are Java interfaces, use ProxyNode for proper implementation
        if (!javaInterfaces.isEmpty()) {
            return new ProxyNode(context,
                    javaInterfaces.toArray(new Class<?>[0]),
                    methodNames.toArray(new String[0]),
                    methodNodeList.toArray(new ExpressionNode[0]));
        }

        // Otherwise use simple ReifyNode for protocol-only
        return new clojure.truffle.nodes.ReifyNode(context,
                methodNames.toArray(new String[0]),
                methodNodeList.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeProxy(ISeq seq) {
        // (proxy [Interface1 Interface2] [ctor-args]
        //   (method1 [this arg] body)
        //   (method2 [this arg1 arg2] body))
        ISeq args = seq.next();
        if (args == null) throw err("proxy: missing interface vector");

        // Parse interfaces
        IPersistentVector interfaceVec = (IPersistentVector) args.first();
        List<Class<?>> interfaces = new ArrayList<>();
        for (int i = 0; i < interfaceVec.count(); i++) {
            Symbol ifaceSym = (Symbol) interfaceVec.nth(i);
            String name = ifaceSym.getName();
            try {
                interfaces.add(JavaInteropUtil.resolveClass(name));
            } catch (RuntimeException e) {
                Object varVal = context.getVar(name);
                if (varVal instanceof Class<?> c) {
                    interfaces.add(c);
                } else {
                    throw new RuntimeException("proxy: cannot resolve interface: " + name);
                }
            }
        }
        args = args.next();

        // Skip constructor args vector (not used for interface proxies)
        if (args != null && args.first() instanceof IPersistentVector) {
            args = args.next();
        }

        // Parse methods
        List<String> methodNames = new ArrayList<>();
        List<ExpressionNode> methodNodes = new ArrayList<>();
        while (args != null) {
            Object item = args.first();
            if (item instanceof ISeq methodDef) {
                String methodName = ((Symbol) methodDef.first()).getName();
                ISeq rest = methodDef.next();
                List<Object> fnForm = new ArrayList<>();
                fnForm.add(Symbol.intern("fn"));
                while (rest != null) { fnForm.add(rest.first()); rest = rest.next(); }
                methodNames.add(methodName);
                methodNodes.add(analyze(PersistentList.create(fnForm)));
            }
            args = args.next();
        }

        return new ProxyNode(context,
                interfaces.toArray(new Class<?>[0]),
                methodNames.toArray(new String[0]),
                methodNodes.toArray(new ExpressionNode[0]));
    }

    private ExpressionNode analyzeDefonce(ISeq seq) {
        // (defonce name expr) - only define if not already bound (runtime check)
        ISeq args = seq.next();
        if (args == null) throw err("defonce: missing name");
        Symbol sym = (Symbol) args.first();
        args = args.next();
        ExpressionNode valueNode = args != null ? analyze(args.first()) : new NilNode();
        return new clojure.truffle.nodes.DefonceNode(context, sym.getName(), valueNode);
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
