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
import clojure.truffle.runtime.ClojureRT;
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
                if ("require".equals(name) || "ns".equals(name) ||
                       "use".equals(name) || "import".equals(name) ||
                       "refer".equals(name) || "refer-clojure".equals(name) || "in-ns".equals(name) ||
                       "alias".equals(name) || "defmacro".equals(name) ||
                       "require-native".equals(name) ||
                       "defrecord".equals(name) || "deftype".equals(name) ||
                       "defprotocol".equals(name) || "definterface".equals(name)) {
                    return true;
                }
                // Check if let/do/when wraps a defmacro (e.g., (let [x ...] (defmacro ...)))
                if ("let".equals(name) || "do".equals(name) || "when".equals(name)) {
                    return containsDefmacro(seq.next());
                }
            }
        }
        return false;
    }

    private boolean containsDefmacro(clojure.lang.ISeq forms) {
        for (; forms != null; forms = forms.next()) {
            Object f = forms.first();
            if (f instanceof clojure.lang.ISeq inner) {
                Object h = inner.first();
                if (h instanceof clojure.lang.Symbol s && "defmacro".equals(s.getName())) return true;
                if (h instanceof clojure.lang.Symbol s2) {
                    String n = s2.getName();
                    if ("let".equals(n) || "do".equals(n) || "when".equals(n)) {
                        if (containsDefmacro(inner.next())) return true;
                    }
                }
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
        final Set<String> letfnNames = new HashSet<>(); // names bound by letfn (use cell indirection)
        final FrameDescriptor.Builder frameBuilder;
        final List<CaptureEntry> captures = new ArrayList<>();

        Scope(Scope parent) {
            this.parent = parent;
            this.frameBuilder = FrameDescriptor.newBuilder();
        }

        void markLetfn(String name) {
            letfnNames.add(name);
        }

        boolean isLetfn(String name) {
            if (letfnNames.contains(name)) return true;
            // When captured across scopes, check parent
            if (parent != null) return parent.isLetfn(name);
            return false;
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

        boolean hasLocal(String name) {
            if (locals.containsKey(name)) return true;
            if (parent != null) return parent.hasLocal(name);
            return false;
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
        clojure.lang.Var nsVar = clojure.truffle.runtime.ClojureRT.var("clojure.core", "*ns*");
        clojure.lang.Namespace clojureNs = clojure.lang.Namespace.findOrCreate(
                clojure.lang.Symbol.intern(currentNs));
        clojure.lang.Var.pushThreadBindings(clojure.truffle.runtime.ClojureRT.map(nsVar, clojureNs));
        try {
            // Read and analyze forms incrementally so that side-effect forms
            // (require, ns, use, import) are executed before subsequent forms are analyzed
            java.util.List<ExpressionNode> nodes = new java.util.ArrayList<>();
            java.io.PushbackReader reader = new java.io.PushbackReader(new java.io.StringReader(source), 2);
            while (true) {
                Object form = clojure.lang.TruffleReader.read(reader, false, EOF, false, getReadOpts());
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
                Object form = TruffleReader.read(reader, false, EOF, false, getReadOpts());
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
                if (context != null && "clojure.tools.analyzer.jvm".equals(context.getCurrentNamespace())) {
                    System.err.println("[TAJ-FORM #" + formCount + "] " + formStr);
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

    private static volatile Object READ_OPTS;
    private static Object getReadOpts() {
        if (READ_OPTS == null) {
            READ_OPTS = clojure.truffle.runtime.ClojureRT.map(TruffleReader.OPT_READ_COND, TruffleReader.COND_ALLOW);
        }
        return READ_OPTS;
    }

    private List<Object> readAll(String source) {
        List<Object> forms = new ArrayList<>();
        PushbackReader reader = new PushbackReader(new StringReader(source), 2);
        try {
            while (true) {
                Object form = TruffleReader.read(reader, false, EOF, false, getReadOpts());
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
        if (form instanceof clojure.truffle.runtime.ClojureDeftypeInstance) return new QuoteNode(form);
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
        if (slot != null) {
            if (currentScope.isLetfn(name)) {
                return new LetfnCellReadNode(slot);
            }
            return new ReadLocalNode(slot);
        }
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
                        // Try both underscore and dash forms for namespace lookup
                        clojure.truffle.runtime.ClojureNamespace possibleNsObj = context.getNamespace(possibleNs);
                        if (possibleNsObj == null) {
                            possibleNsObj = context.getNamespace(possibleNs.replace('_', '-'));
                        }
                        if (possibleNsObj != null && possibleNsObj.resolve(possibleVar) != null) {
                            return new SymbolNode(context, possibleNsObj.getName() + "/" + possibleVar);
                        }
                    }
                }
                // Check if symbol resolves to a namespace var first (e.g. protocol, deftype)
                // before trying Java class resolution, so user-defined names take priority
                if (context != null && context.getVar(name) != null) {
                    return new SymbolNode(context, name);
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
        "if", "do", "def", "let*", "let", "fn*", "fn", "quote", "recur",
        "loop*", "loop", "try", "throw", "new", "set!", "var",
        "monitor-enter", "monitor-exit"
    );

    // Forms implemented as builtins in Truffle Clojure (macros/special forms in clojure.core)
    // These are recognized when namespace-qualified to clojure.core or an alias thereof.
    private static final java.util.Set<String> CORE_FORMS = java.util.Set.of(
        "and", "or", "when", "cond", "defn", "defn-",
        "defmacro", "macroexpand", "lazy-seq",
        "defmulti", "defmethod", "defprotocol", "deftype", "defrecord", "definterface",
        "ns", "in-ns", "require", "->", "->>", "as->", "some->", "some->>",
        "cond->", "cond->>", "doto", "..", "if-let", "when-let", "if-some",
        "when-some", "case", "for", "doseq", "dotimes", "letfn",
        "binding", "when-not", "if-not", "condp", "while", "comment",
        "declare", "defonce", "with-open", "with-out-str", "reify", "proxy",
        "extend-type", "extend-protocol", "delay", "future", "locking",
        "dosync", "macroexpand-1", "when-first", "assert",
        "time", "with-in-str", "with-redefs", "memfn", "import", "use",
        "refer", "refer-clojure", "lazy-cat", "require-native"
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
                // Check if name shadows a special form with a local variable
                boolean isLocalShadow = (name.equals("fn") || name.equals("fn*") || name.equals("let") || name.equals("let*"))
                        && currentScope != null && currentScope.hasLocal(name);

                // For CORE_FORMS (not TRUE_SPECIAL_FORMS), check if the current namespace
                // has a local macro override (e.g., taoensso.encore redefines cond)
                if (!isLocalShadow && ns == null && !TRUE_SPECIAL_FORMS.contains(name) && CORE_FORMS.contains(name) && context != null) {
                    String curNs = context.getCurrentNamespace();
                    if (curNs != null && !"clojure.core".equals(curNs) && !"user".equals(curNs)) {
                        // Check if this namespace has its own macro definition for this name
                        ClojureNamespace nsObj = context.getNamespace(curNs);
                        if (nsObj != null) {
                            Object localMacro = nsObj.resolve("__macro__" + name);
                            if (localMacro != null) {
                                // This namespace has its own macro for this name, use macro expansion
                                isLocalShadow = true;
                            }
                        }
                    }
                }

                if (!isLocalShadow) switch (name) {
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
                    case "definterface": return analyzeDefinterface(seq);
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
                    case "definline":   return analyzeDefn(seq, false); // treat as defn
                    case "defonce":     return analyzeDefonce(seq);
                    case "with-open":   return analyzeWithOpen(seq);
                    case "with-bindings": return analyzeWithBindings(seq);
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
                    case "lazy-cat":    return analyzeLazyCat(seq);
                    case "with-redefs": return analyzeWithRedefs(seq);
                    case "memfn":       return analyzeMemfn(seq);
                    case "import":      return analyzeImport(seq);
                    case "require-native": return analyzeRequireNative(seq);
                    case "use":         return analyzeUse(seq);
                    case "refer":       return analyzeRefer(seq);
                    case "refer-clojure": return analyzeReferClojure(seq);
                    case "load":        return analyzeLoad(seq);
                }
            }
            // Static method call: (Class/method args...)
            if (ns != null && isJavaClassName(ns)) {
                return analyzeStaticCall(ns, name, seq.next());
            }
            // Defrecord/deftype static methods: (ns.TypeName/method args...)
            if (ns != null && ns.contains(".") && context != null) {
                ExpressionNode recordStatic = analyzeRecordStaticCall(ns, name, seq.next());
                if (recordStatic != null) return recordStatic;
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
                            return expandAndAnalyzeMacro(macro, seq, seq.next(), name);
                        }
                    }
                } else {
                    // Namespace-qualified: check if it's a macro in the target namespace (e.g., s/def)
                    Object macro = context.getMacroFromNs(ns, name);
                    if (macro != null) {
                        return expandAndAnalyzeMacro(macro, seq, seq.next(), ns + "/" + name);
                    }
                }
            }
        }
        return analyzeInvoke(seq);
    }

    // --- Macro expansion ---

    private int macroDepth = 0;
    private ExpressionNode expandAndAnalyzeMacro(Object macro, ISeq fullForm, ISeq argForms, String macroName) {
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
            // For user-defined macros (ClojureFunction/MultiArityFunction), pass &form and &env
            // as the first two args (standard Clojure defmacro behavior)
            boolean isUserMacro = (macro instanceof ClojureFunction || macro instanceof MultiArityFunction);
            if (isUserMacro) {
                rawArgs.add(fullForm);  // &form - the original form
                rawArgs.add(clojure.truffle.runtime.ClojureNil.INSTANCE);  // &env - nil for now
            }
            while (argForms != null) {
                rawArgs.add(argForms.first());
                argForms = argForms.next();
            }
            Object expanded;
            try {
                expanded = context.callFunction(macro, rawArgs.toArray());
            } catch (RuntimeException e) {
                String macroDesc = macro instanceof ClojureFunction cf ? cf.getName() :
                    macro instanceof MultiArityFunction maf ? "MultiArity" : macro.getClass().getSimpleName();
                System.err.println("[MACRO-ERROR] name=" + macroName + " error=" + e.getMessage() + " rawArgs=" + rawArgs.size());
                // Fallback for core.async go/go-loop: run body in a thread (async)
                if (macroName != null && (macroName.equals("clojure.core.async/go") ||
                        macroName.equals("clojure.core.async/go-loop") ||
                        macroName.equals("go") || macroName.equals("go-loop") ||
                        macroName.endsWith("/go") || macroName.endsWith("/go-loop"))) {
                    System.err.println("[MACRO-FALLBACK] " + macroName + " → (future body...) with <!/> substitution");
                    // Build (future body...) from the original form args (skip the macro symbol)
                    // Replace <! with <!! and >! with >!! for blocking channel operations
                    // Run in a thread so go blocks are async (prevents deadlock)
                    ISeq bodyArgs = fullForm.next();
                    if (macroName.contains("go-loop")) {
                        // go-loop has bindings before body: (go-loop [bindings] body...)
                        // Convert to: (future (loop [bindings] body...))
                        List<Object> loopForm = new ArrayList<>();
                        loopForm.add(Symbol.intern("loop"));
                        while (bodyArgs != null) { loopForm.add(goSubstitute(bodyArgs.first())); bodyArgs = bodyArgs.next(); }
                        List<Object> futureForm = new ArrayList<>();
                        futureForm.add(Symbol.intern("future"));
                        futureForm.add(PersistentList.create(loopForm));
                        return analyze(PersistentList.create(futureForm));
                    }
                    List<Object> doForm = new ArrayList<>();
                    doForm.add(Symbol.intern("do"));
                    while (bodyArgs != null) { doForm.add(goSubstitute(bodyArgs.first())); bodyArgs = bodyArgs.next(); }
                    List<Object> futureForm = new ArrayList<>();
                    futureForm.add(Symbol.intern("future"));
                    futureForm.add(PersistentList.create(doForm));
                    return analyze(PersistentList.create(futureForm));
                }
                throw e;
            }
            if (ClojureContext.DEBUG) {
                String macroDesc = macro instanceof ClojureFunction cf ? cf.getName() :
                    macro instanceof MultiArityFunction maf ? "MultiArityFunction" : macro.getClass().getSimpleName();
                System.err.println("[MACRO-EXPAND] " + macroDesc + " => " +
                    (expanded != null ? expanded.toString().substring(0, Math.min(300, expanded.toString().length())) : "nil"));
            }
            try {
                return analyze(expanded);
            } catch (RuntimeException ae) {
                if (ae.getMessage() != null && ae.getMessage().contains("Unable to resolve symbol")) {
                    String expStr = expanded != null ? expanded.toString() : "null";
                    if (expStr.length() > 800) expStr = expStr.substring(0, 800) + "...";
                    System.err.println("[MACRO-EXPAND-ERR] name=" + macroName + " error=" + ae.getMessage() + " expanded=" + expStr);
                }
                throw ae;
            }
        } finally {
            macroDepth--;
        }
    }

    /**
     * Prepend &form and &env symbols to a param vector, matching standard Clojure defmacro behavior.
     */
    private IPersistentVector prependFormEnvParams(IPersistentVector params) {
        java.util.ArrayList<Object> newParams = new java.util.ArrayList<>();
        newParams.add(Symbol.intern("&form"));
        newParams.add(Symbol.intern("&env"));
        for (int i = 0; i < params.count(); i++) {
            newParams.add(params.nth(i));
        }
        return ClojureRT.vector(newParams.toArray());
    }

    /**
     * Transform defmacro args to add implicit &form and &env params to each arity,
     * matching standard Clojure defmacro behavior.
     */
    private ISeq addImplicitMacroParams(ISeq args) {
        // args = (name optional-docstring? optional-attr-map? [params] body...)
        // or   = (name optional-docstring? optional-attr-map? ([params1] body1)...)
        List<Object> prefix = new ArrayList<>();
        prefix.add(args.first()); // name
        ISeq rest = args.next();

        // Optional docstring
        if (rest != null && rest.first() instanceof String) {
            prefix.add(rest.first());
            rest = rest.next();
        }
        // Optional attr-map
        if (rest != null && rest.first() instanceof IPersistentMap) {
            prefix.add(rest.first());
            rest = rest.next();
        }

        if (rest == null) return args; // degenerate case

        List<Object> transformed = new ArrayList<>(prefix);

        if (rest.first() instanceof IPersistentVector) {
            // Single arity: [params] body...
            IPersistentVector params = (IPersistentVector) rest.first();
            transformed.add(prependFormEnvParams(params));
            for (ISeq s = rest.next(); s != null; s = s.next()) {
                transformed.add(s.first());
            }
        } else {
            // Multi arity: each element is ([params] body...)
            for (ISeq s = rest; s != null; s = s.next()) {
                Object arity = s.first();
                ISeq aritySeq = ClojureRT.seq(arity);
                if (aritySeq != null && aritySeq.first() instanceof IPersistentVector) {
                    IPersistentVector params = (IPersistentVector) aritySeq.first();
                    IPersistentVector newParams = prependFormEnvParams(params);
                    transformed.add(ClojureRT.cons(newParams, aritySeq.next()));
                } else {
                    transformed.add(arity); // pass through (e.g., metadata)
                }
            }
        }

        return (ISeq) PersistentList.create(transformed);
    }

    private ExpressionNode analyzeDefmacro(ISeq seq) {
        // (defmacro name [params] body...)
        ISeq args = seq.next();
        if (args == null) throw err("defmacro: missing name");
        if (!(args.first() instanceof Symbol)) throw err("defmacro: name must be a symbol");
        String macroName = ((Symbol) args.first()).getName();

        // Add implicit &form and &env params (standard Clojure defmacro behavior)
        ISeq transformedArgs = addImplicitMacroParams(args);

        // Build (fn* name [&form &env params...] body...) and compile it
        ISeq fnForm = ClojureRT.cons(Symbol.intern("fn*"), transformedArgs);
        ExpressionNode fnNode = analyzeFn(fnForm);

        // Check if the fn captures from an outer scope
        boolean hasCaptures = false;
        if (fnNode instanceof FnNode fn) {
            hasCaptures = fn.hasOuterCaptures();
        } else if (fnNode instanceof MultiArityFnNode) {
            hasCaptures = true; // be safe, use runtime for multi-arity
        }

        if (hasCaptures) {
            // Fn captures outer locals (e.g., defmacro inside let) -
            // defer macro registration to runtime so captured values are available
            return new DefmacroNode(context, macroName, fnNode);
        }

        // No captures - execute immediately to register the macro at analysis time
        FrameDescriptor fd = FrameDescriptor.newBuilder().build();
        EvalRootNode evalRoot = new EvalRootNode(language, fd, new ExpressionNode[]{fnNode});
        Object result = evalRoot.getCallTarget().call();

        context.setMacro(macroName, result);
        return new NilNode();
    }

    private ExpressionNode analyzeMacroexpand(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("macroexpand: missing form");
        // Evaluate the form argument and call the macroexpand builtin at runtime
        ExpressionNode formNode = analyze(args.first());
        return new InvokeNode(new SymbolNode(context, "macroexpand"),
                new ExpressionNode[]{formNode});
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
        String varName;
        if (s.getNamespace() != null) {
            // Resolve namespace alias
            String nsAlias = s.getNamespace();
            if (context != null) {
                ClojureNamespace curNs = context.getNamespace(context.getCurrentNamespace());
                if (curNs != null) {
                    ClojureNamespace resolved = curNs.resolveAlias(nsAlias);
                    if (resolved != null) nsAlias = resolved.getName();
                }
            }
            varName = nsAlias + "/" + s.getName();
        } else {
            // Qualify with the namespace where the var is actually defined
            String compileNs = context != null ? context.getCurrentNamespace() : "user";
            if (context != null) {
                ClojureNamespace curNs = context.getNamespace(compileNs);
                if (curNs != null) {
                    // Check if this name is referred from another namespace
                    String referSource = curNs.getReferSource(s.getName());
                    if (referSource != null) {
                        compileNs = referSource;
                    }
                }
            }
            varName = compileNs + "/" + s.getName();
        }
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
                Object classObj = ClojureRT.second(targetSeq);
                Object fieldObj = ClojureRT.third(targetSeq);
                if (classObj instanceof Symbol classSym && fieldObj instanceof Symbol fieldSym) {
                    String className = classSym.getName();
                    String fieldName = fieldSym.getName();
                    ExpressionNode valNode = valueNode;
                    return new ExpressionNode() {
                        @Child private ExpressionNode valNodeChild = valNode;
                        @Override
                        public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                            Object value = valNodeChild.executeGeneric(frame);
                            return setStaticField(className, fieldName, value);
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
        Object bindForm = bindings.nth(0); // can be Symbol or destructuring form (vector/map)
        Object coll = bindings.nth(1);
        ISeq body = args.next();

        // Build: (let [s__temp (seq coll)] (when s__temp (let [bindForm (first s__temp)] body...)))
        Symbol tempSym = Symbol.intern("__when-first-temp__" + System.nanoTime());
        // (seq coll)
        Object seqCall = ClojureRT.list(Symbol.intern("seq"), coll);
        // (first s__temp)
        Object firstCall = ClojureRT.list(Symbol.intern("first"), tempSym);
        // (let [bindForm (first s__temp)] body...)
        List<Object> innerLetForms = new ArrayList<>();
        innerLetForms.add(Symbol.intern("let"));
        innerLetForms.add(PersistentVector.create(bindForm, firstCall));
        while (body != null) { innerLetForms.add(body.first()); body = body.next(); }
        Object innerLet = PersistentList.create(innerLetForms);
        // (when s__temp innerLet)
        Object whenForm = ClojureRT.list(Symbol.intern("when"), tempSym, innerLet);
        // (let [s__temp (seq coll)] whenForm)
        Object outerLet = ClojureRT.list(Symbol.intern("let"),
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
        ISeq fnForm = ClojureRT.cons(Symbol.intern("fn*"),
                ClojureRT.cons(PersistentVector.EMPTY, body));
        return analyzeFn(fnForm);
    }

    // --- with-in-str ---

    private ExpressionNode analyzeLazyCat(ISeq seq) {
        // (lazy-cat xs ys ...) -> (lazy-seq (concat xs ys ...))
        ISeq args = seq.next();
        if (args == null) {
            return new QuoteNode(PersistentList.EMPTY);
        }
        // Build: (lazy-seq (concat xs ys ...))
        ISeq concatForm = ClojureRT.cons(Symbol.intern("concat"), args);
        ISeq lazySeqForm = ClojureRT.list(Symbol.intern("lazy-seq"), concatForm);
        return analyzeLazySeq(lazySeqForm);
    }

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
        Object fnForm = ClojureRT.list(Symbol.intern("fn"),
                PersistentVector.create(params.toArray()), callForm);
        return analyze(fnForm);
    }

    // --- require-native ---

    private ExpressionNode analyzeRequireNative(ISeq seq) {
        // (require-native 'c "libc.so.6" strlen "(STRING):UINT64" ...)
        // All args passed as quoted values to the builtin
        ISeq args = seq.next();
        List<ExpressionNode> argNodes = new ArrayList<>();
        while (args != null) {
            Object form = args.first();
            // Unwrap (quote x) → x
            if (form instanceof ISeq qs && qs.first() instanceof Symbol s && s.getName().equals("quote")) {
                form = qs.next().first();
            }
            if (form instanceof Symbol sym) {
                // Pass symbol name as string
                argNodes.add(new QuoteNode(sym.getName()));
            } else {
                // Strings and other literals: analyze normally
                argNodes.add(analyze(form));
            }
            args = args.next();
        }
        return new InvokeNode(new SymbolNode(context, "require-native"),
                argNodes.toArray(new ExpressionNode[0]));
    }

    // --- import ---

    private ExpressionNode analyzeImport(ISeq seq) {
        // (import java.util.ArrayList) or (import (java.util ArrayList HashMap))
        ISeq args = seq.next();
        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) {
            Object form = args.first();
            // Unwrap quote: (import 'java.util.ArrayList) or (import '[java.util ArrayList])
            if (form instanceof ISeq qs && qs.first() instanceof Symbol s && s.getName().equals("quote")) {
                form = qs.next().first();
            }
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
            } else if (form instanceof IPersistentVector importVec) {
                // Vector form: (import '[java.util ArrayList HashMap])
                if (importVec.count() > 0) {
                    String pkg = ((Symbol) importVec.nth(0)).getName();
                    for (int j = 1; j < importVec.count(); j++) {
                        String className = ((Symbol) importVec.nth(j)).getName();
                        String fqn = pkg + "." + className;
                        Class<?> clazz = resolveClassSafe(fqn);
                        context.setVar(className, clazz);
                        nodes.add(new DefNode(context, className, new QuoteNode(clazz)));
                    }
                }
            }
            args = args.next();
        }
        if (nodes.isEmpty()) return new NilNode();
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    private Class<?> resolveClassSafe(String fqn) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return cl != null ? Class.forName(fqn, true, cl) : Class.forName(fqn);
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
        final Analyzer self = this;
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return doUse(self, capturedSpecs);
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
                            onlySyms = extractSymbolNames(v.nth(i + 1));
                            break;
                        case "exclude":
                            excludeSyms = new java.util.HashSet<>(extractSymbolNames(v.nth(i + 1)));
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

    private ExpressionNode analyzeLoad(ISeq seq) {
        // (load "reflect/java") loads clojure/reflect/java.clj relative to classpath
        ISeq args = seq.next();
        List<String> paths = new ArrayList<>();
        while (args != null) {
            Object pathObj = args.first();
            if (pathObj instanceof String s) {
                paths.add(s);
            }
            args = args.next();
        }
        List<String> capturedPaths = List.copyOf(paths);
        final Analyzer self = this;
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return doLoad(context, capturedPaths, self);
            }
        };
    }

    private ExpressionNode analyzeRefer(ISeq seq) {
        // (refer 'some.ns) or (refer 'some.ns :only '[foo bar])
        ISeq args = seq.next();
        List<Object> capturedArgs = new ArrayList<>();
        while (args != null) { capturedArgs.add(args.first()); args = args.next(); }
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return doRefer(context, capturedArgs);
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
                return referClojureBoundary(context, capturedExcludes);
            }
        };
    }

    private List<String> extractSymbolNames(Object coll) {
        List<String> names = new ArrayList<>();
        for (ISeq s = clojure.lang.RT.seq(coll); s != null; s = s.next()) {
            names.add(((Symbol) s.first()).getName());
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
        ISeq fnForm = ClojureRT.cons(Symbol.intern("fn*"),
                ClojureRT.cons(PersistentVector.EMPTY, body));
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
                if (name.startsWith("clojure.lang.") || name.startsWith("clojure.asm.")) {
                    // These are Java packages, not Clojure namespaces
                    return true;
                }
                if (name.startsWith("clojure.") || name.startsWith("user.")) return false;
                // Try to resolve as Java class
                try {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    if (cl != null) Class.forName(name, false, cl); else Class.forName(name);
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
                // Check if it's a defrecord/deftype static method (create, getBasis)
                ExpressionNode recordStatic = analyzeRecordStaticCall(className, memberName, args);
                if (recordStatic != null) return recordStatic;
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

    /**
     * Handle static method calls on defrecord/deftype types (e.g., RecordName/create, RecordName/getBasis).
     * Returns null if className is not a known defrecord/deftype.
     */
    private ExpressionNode analyzeRecordStaticCall(String className, String memberName, ISeq args) {
        // Resolve simple or qualified class name to find the map-> factory
        String simpleName = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
        String nsPrefix = className.contains(".") ? className.substring(0, className.lastIndexOf('.')).replace('_', '-') : null;

        // Check if map->TypeName or ->TypeName exist (indicating it's a defrecord/deftype)
        String mapFactoryName = "map->" + simpleName;
        String ctorName = "->" + simpleName;
        Object mapFactory = null;
        Object ctor = null;

        if (nsPrefix != null) {
            // Namespace-qualified: check specific namespace
            var ns = context.getNamespace(nsPrefix);
            if (ns != null) {
                mapFactory = ns.resolve(mapFactoryName);
                ctor = ns.resolve(ctorName);
            }
        } else {
            // Unqualified: check current namespace
            mapFactory = context.getVar(mapFactoryName);
            ctor = context.getVar(ctorName);
        }

        if (mapFactory == null && ctor == null) return null;

        switch (memberName) {
            case "create": {
                // RecordType/create takes a map and creates a record instance
                List<ExpressionNode> argList = new ArrayList<>();
                while (args != null) { argList.add(analyze(args.first())); args = args.next(); }
                String qualifiedFactory = nsPrefix != null ? nsPrefix + "/" + mapFactoryName : mapFactoryName;
                return new InvokeNode(new SymbolNode(context, qualifiedFactory),
                        argList.toArray(new ExpressionNode[0]));
            }
            case "getBasis": {
                // RecordType/getBasis returns a vector of field name symbols
                // Look up the defrecord's field names from the constructor function
                String qualifiedCtor = nsPrefix != null ? nsPrefix + "/" + ctorName : ctorName;
                // Return a node that calls the __getBasis helper
                String basisVarName = "__basis__" + simpleName;
                Object basisVal = nsPrefix != null ?
                    (context.getNamespace(nsPrefix) != null ? context.getNamespace(nsPrefix).resolve(basisVarName) : null) :
                    context.getVar(basisVarName);
                if (basisVal != null) {
                    Object finalBasisVal = basisVal;
                    return new ExpressionNode() {
                        @Override
                        public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                            return finalBasisVal;
                        }
                    };
                }
                // Fallback: return empty vector
                return new ExpressionNode() {
                    @Override
                    public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                        return clojure.lang.PersistentVector.EMPTY;
                    }
                };
            }
            default:
                return null;
        }
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
        // For namespace-qualified class names (e.g. clojure.core.match.VectorPattern),
        // resolve the constructor in the appropriate namespace
        if (className.contains(".")) {
            int lastDot = className.lastIndexOf('.');
            String nsName = className.substring(0, lastDot);
            String typeName = className.substring(lastDot + 1);
            String qualifiedCtor = nsName + "/->" + typeName;
            return new InvokeNode(new SymbolNode(context, qualifiedCtor),
                    argList.toArray(new ExpressionNode[0]));
        }
        // Try current namespace first, then search all namespaces
        ExpressionNode ctorNode;
        Object directVal = context.getVar(ctorName);
        if (directVal != null) {
            ctorNode = new SymbolNode(context, ctorName);
        } else {
            // Search all namespaces for ->TypeName
            String foundNs = null;
            for (var ns : context.getAllNamespaces()) {
                if (ns.resolve("->" + className) != null) {
                    foundNs = ns.getName();
                    break;
                }
            }
            if (foundNs != null) {
                ctorNode = new SymbolNode(context, foundNs + "/->" + className);
            } else {
                ctorNode = new SymbolNode(context, ctorName);
            }
        }
        return new InvokeNode(ctorNode,
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
                return accessInstanceField(obj, fieldName);
            }
        };
    }

    // --- Multimethods ---

    private ExpressionNode analyzeDefmulti(ISeq seq) {
        // (defmulti name "doc"? attr-map? dispatch-fn & options)
        ISeq args = seq.next();
        if (args == null) throw err("defmulti: missing name");
        if (!(args.first() instanceof Symbol sym)) throw err("defmulti: name must be a symbol");
        String name = sym.getName();
        args = args.next();
        if (args == null) throw err("defmulti: missing dispatch function");

        // Collect metadata from symbol, docstring, and attr-map
        clojure.lang.IPersistentMap varMeta = sym.meta() != null ? sym.meta() : clojure.lang.PersistentArrayMap.EMPTY;

        // Skip optional docstring
        if (args.first() instanceof String doc) {
            varMeta = varMeta.assoc(clojure.lang.Keyword.intern("doc"), doc);
            args = args.next();
            if (args == null) throw err("defmulti: missing dispatch function after docstring");
        }
        // Skip optional attr-map
        if (args.first() instanceof clojure.lang.IPersistentMap attrMap) {
            varMeta = mergeMetaMaps(varMeta, attrMap);
            args = args.next();
            if (args == null) throw err("defmulti: missing dispatch function after attr-map");
        }

        // Store var metadata
        if (context != null && varMeta.count() > 0) {
            String ns = context.getCurrentNamespace();
            String qname = ns + "/" + name;
            context.setVarMeta(qname, (clojure.lang.IPersistentMap) resolveVarRefsInMeta(varMeta));
        }

        ExpressionNode dispatchFnNode = analyze(args.first());
        args = args.next();

        // Parse options: :default val, :hierarchy #'h
        ExpressionNode hierarchyNode = null;
        while (args != null) {
            Object key = args.first();
            args = args.next();
            if (args == null) break;
            if (key instanceof clojure.lang.Keyword kw && kw.getName().equals("hierarchy")) {
                hierarchyNode = analyze(args.first());
            }
            args = args.next();
        }

        final ExpressionNode finalHierarchyNode = hierarchyNode;
        return new ExpressionNode() {
            @Child ExpressionNode dispatchNode = dispatchFnNode;
            @Child ExpressionNode hierNode = finalHierarchyNode;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object dispatchFn = dispatchNode.executeGeneric(frame);
                Object hierarchy = hierNode != null ? hierNode.executeGeneric(frame) : null;
                return doDefmulti(context, name, dispatchFn, hierarchy);
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
        ISeq fnForm = ClojureRT.cons(Symbol.intern("fn*"), args);
        ExpressionNode fnNode = analyzeFn(fnForm);

        return new ExpressionNode() {
            @Child ExpressionNode dvNode = dispatchValNode;
            @Child ExpressionNode methodFnNode = fnNode;
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                Object dispatchVal = dvNode.executeGeneric(frame);
                Object methodFn = methodFnNode.executeGeneric(frame);
                return registerDefmethod(context, mmName, dispatchVal, methodFn);
            }
        };
    }

    // --- Protocols ---

    private ExpressionNode analyzeDefprotocol(ISeq seq) {
        // (defprotocol Name "doc?" (method-name "doc?" [this arg1] [this arg1 arg2]) ...)
        ISeq args = seq.next();
        if (args == null) throw err("defprotocol: missing name");
        if (!(args.first() instanceof Symbol sym)) throw err("defprotocol: name must be a symbol");
        String protoName = sym.getName();
        args = args.next();

        // Skip protocol-level docstring if present
        if (args != null && args.first() instanceof String) {
            args = args.next();
        }

        List<String> methodNames = new ArrayList<>();
        // methodMeta: methodName -> {arglists, doc}
        java.util.Map<String, Object[]> methodMeta = new java.util.LinkedHashMap<>();
        while (args != null) {
            Object methodSpec = args.first();
            if (methodSpec instanceof ISeq methodSeq) {
                if (!(methodSeq.first() instanceof Symbol methodSym))
                    throw err("defprotocol: method name must be a symbol");
                String mName = methodSym.getName();
                methodNames.add(mName);

                // Get :tag from method symbol metadata (e.g., ^String baz)
                Object methodTag = null;
                if (methodSym.meta() != null) {
                    methodTag = methodSym.meta().valAt(clojure.lang.Keyword.intern("tag"));
                }

                // Parse arglists and optional trailing docstring from method spec
                // Format: (method-name [params] [params2] "docstring")
                ISeq mArgs = methodSeq.next();
                String methodDoc = null;
                List<Object> arglists = new ArrayList<>();
                while (mArgs != null) {
                    Object form = mArgs.first();
                    if (form instanceof IPersistentVector) {
                        arglists.add(form);
                    } else if (form instanceof String s) {
                        methodDoc = s;
                    }
                    mArgs = mArgs.next();
                }
                // arglists as a PersistentList
                clojure.lang.ISeq arglistSeq = null;
                for (int i = arglists.size() - 1; i >= 0; i--) {
                    arglistSeq = clojure.lang.RT.cons(arglists.get(i), arglistSeq);
                }
                methodMeta.put(mName, new Object[]{arglistSeq, methodDoc, methodTag});
            }
            args = args.next();
        }

        // Validate: each method must take at least one arg (this)
        for (var entry : methodMeta.entrySet()) {
            String mName = entry.getKey();
            Object[] meta = entry.getValue();
            clojure.lang.ISeq arglistSeq = (clojure.lang.ISeq) meta[0];
            if (arglistSeq != null) {
                for (clojure.lang.ISeq s = arglistSeq; s != null; s = s.next()) {
                    IPersistentVector params = (IPersistentVector) s.first();
                    if (params.count() == 0) {
                        throw new IllegalArgumentException(
                            "Definition of function " + mName + " in protocol " + protoName +
                            " must take at least one arg.");
                    }
                }
            }
        }
        // Validate: method names must be unique within a protocol
        java.util.Set<String> seenMethods = new java.util.HashSet<>();
        for (String mName : methodNames) {
            if (!seenMethods.add(mName)) {
                throw new IllegalArgumentException(
                    "Function " + mName + " in protocol " + protoName +
                    " was redefined. Specify all arities in single definition.");
            }
        }

        List<String> capturedMethodNames = List.copyOf(methodNames);
        java.util.Map<String, Object[]> capturedMethodMeta = new java.util.LinkedHashMap<>(methodMeta);
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return registerDefprotocol(context, protoName, capturedMethodNames, capturedMethodMeta);
            }
        };
    }

    // --- definterface ---

    private ExpressionNode analyzeDefinterface(ISeq seq) {
        // (definterface Name (method-name [arg1 arg2]) ...)
        // For marker interfaces (no methods), just register as a protocol with no methods
        ISeq args = seq.next();
        if (args == null) throw err("definterface: missing name");
        if (!(args.first() instanceof Symbol sym)) throw err("definterface: name must be a symbol");
        String ifaceName = sym.getName();
        // Collect method signatures (may be empty for marker interfaces)
        args = args.next();
        List<String> methodNames = new ArrayList<>();
        while (args != null) {
            Object methodSpec = args.first();
            if (methodSpec instanceof ISeq methodSeq) {
                if (methodSeq.first() instanceof Symbol methodSym) {
                    methodNames.add(methodSym.getName());
                }
            }
            args = args.next();
        }
        List<String> capturedMethodNames = List.copyOf(methodNames);
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return doDefinterface(context, ifaceName, capturedMethodNames);
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

        // Parse field names and preserve original symbols (with metadata like ^String)
        List<String> fieldNames = new ArrayList<>();
        List<Symbol> fieldSymbols = new ArrayList<>();
        for (int i = 0; i < fieldVec.count(); i++) {
            if (!(fieldVec.nth(i) instanceof Symbol fs)) throw err("deftype: field must be a symbol");
            fieldNames.add(fs.getName());
            fieldSymbols.add(fs);
        }
        args = args.next();

        // Parse protocol implementations
        // format: ProtoName (method [this arg] body...) (method2 [this] body...) AnotherProto ...
        // First pass: collect raw method forms grouped by proto and method name
        java.util.Map<String, java.util.Map<String, List<Object[]>>> protoMethodForms = new java.util.LinkedHashMap<>();
        String currentProto = null;

        while (args != null) {
            Object form = args.first();
            if (form instanceof Symbol protoSym) {
                // Handle namespace-qualified protocol names (e.g., cache/CacheProtocol)
                if (protoSym.getNamespace() != null) {
                    currentProto = protoSym.getNamespace() + "/" + protoSym.getName();
                } else {
                    currentProto = protoSym.getName();
                }
                protoMethodForms.putIfAbsent(currentProto, new java.util.LinkedHashMap<>());
            } else if (form instanceof ISeq methodSeq && currentProto != null) {
                if (!(methodSeq.first() instanceof Symbol methodSym))
                    throw err("deftype: method name must be a symbol");
                String methodName = methodSym.getName();
                ISeq methodArgs = methodSeq.next();
                if (methodArgs == null || !(methodArgs.first() instanceof IPersistentVector))
                    throw err("deftype: method must have param vector");
                IPersistentVector methodParams = (IPersistentVector) methodArgs.first();
                ISeq methodBody = methodArgs.next();
                protoMethodForms.get(currentProto)
                    .computeIfAbsent(methodName, k -> new ArrayList<>())
                    .add(new Object[]{methodParams, methodBody});
            }
            args = args.next();
        }

        // Second pass: compile methods, merging multi-arity methods into single multi-arity fns
        java.util.Map<String, java.util.Map<String, ExpressionNode>> protoMethods = new java.util.LinkedHashMap<>();
        for (var protoEntry : protoMethodForms.entrySet()) {
            String protoName = protoEntry.getKey();
            java.util.Map<String, ExpressionNode> methodNodes = new java.util.LinkedHashMap<>();
            for (var methodEntry : protoEntry.getValue().entrySet()) {
                String methodName = methodEntry.getKey();
                List<Object[]> arityList = methodEntry.getValue();
                if (arityList.size() == 1) {
                    // Single arity - compile as before
                    Object[] pair = arityList.get(0);
                    methodNodes.put(methodName, compileDeftypeMethod(typeName, fieldNames, (IPersistentVector) pair[0], (ISeq) pair[1]));
                } else {
                    // Multiple arities - compile as multi-arity fn
                    methodNodes.put(methodName, compileDeftypeMultiArityMethod(typeName, fieldNames, arityList));
                }
            }
            protoMethods.put(protoName, methodNodes);
        }

        List<String> capturedFieldNames = List.copyOf(fieldNames);
        List<Symbol> capturedFieldSymbols = List.copyOf(fieldSymbols);
        // Capture proto methods
        java.util.Map<String, java.util.Map<String, ExpressionNode>> capturedProtoMethods =
                new java.util.LinkedHashMap<>(protoMethods);

        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return evalDeftype(capturedProtoMethods, frame, context, typeName, capturedFieldNames, false, capturedFieldSymbols);
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
        List<Symbol> fieldSymbols = new ArrayList<>();
        for (int i = 0; i < fieldVec.count(); i++) {
            if (!(fieldVec.nth(i) instanceof Symbol fs)) throw err("defrecord: field must be a symbol");
            fieldNames.add(fs.getName());
            fieldSymbols.add(fs);
        }
        args = args.next();

        // Parse protocol implementations (same as deftype)
        java.util.Map<String, java.util.Map<String, ExpressionNode>> protoMethods = new java.util.LinkedHashMap<>();
        String currentProto = null;
        while (args != null) {
            Object form = args.first();
            if (form instanceof Symbol protoSym) {
                if (protoSym.getNamespace() != null) {
                    currentProto = protoSym.getNamespace() + "/" + protoSym.getName();
                } else {
                    currentProto = protoSym.getName();
                }
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
        List<Symbol> capturedFieldSymbols = List.copyOf(fieldSymbols);
        java.util.Map<String, java.util.Map<String, ExpressionNode>> capturedProtoMethods =
                new java.util.LinkedHashMap<>(protoMethods);

        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return evalDeftype(capturedProtoMethods, frame, context, typeName, capturedFieldNames, true, capturedFieldSymbols);
            }
        };
    }

    private ExpressionNode compileDeftypeMethod(String typeName, List<String> fieldNames,
                                                 IPersistentVector params, ISeq body) {
        // Build: (fn* [this-param arg1 ...] (let [field1 (.-field1 this-param) ...] body...))
        // Use the actual first parameter (the "this" param) for field access
        Symbol thisSym = (params.count() > 0 && params.nth(0) instanceof Symbol s) ? s : Symbol.intern("this");
        List<Object> letBindings = new ArrayList<>();

        // Only bind fields that aren't shadowed by params
        java.util.Set<String> paramNames = new java.util.HashSet<>();
        for (int i = 0; i < params.count(); i++) {
            if (params.nth(i) instanceof Symbol s) paramNames.add(s.getName());
        }

        for (String fieldName : fieldNames) {
            if (paramNames.contains(fieldName)) continue;
            letBindings.add(Symbol.intern(fieldName));
            // (.-fieldName this-param)
            letBindings.add(ClojureRT.list(Symbol.intern(".-" + fieldName), thisSym));
        }

        Object wrappedBody;
        if (letBindings.isEmpty()) {
            wrappedBody = body;
        } else {
            // (let [bindings...] body...)
            IPersistentVector bindVec = PersistentVector.create(letBindings);
            wrappedBody = ClojureRT.cons(Symbol.intern("let"), ClojureRT.cons(bindVec, body));
            wrappedBody = ClojureRT.list(wrappedBody);
        }

        // (fn* [params] wrapped-body)
        ISeq fnSeq;
        if (wrappedBody instanceof ISeq ws) {
            fnSeq = ClojureRT.cons(Symbol.intern("fn*"), ClojureRT.cons(params, ws));
        } else {
            fnSeq = ClojureRT.list(Symbol.intern("fn*"), params, wrappedBody);
        }
        return analyzeFn(fnSeq);
    }

    /**
     * Compile a deftype method with multiple arities into a single multi-arity fn.
     * Each entry in arityList is [IPersistentVector params, ISeq body].
     */
    private ExpressionNode compileDeftypeMultiArityMethod(String typeName, List<String> fieldNames,
                                                          List<Object[]> arityList) {
        // Build: (fn* ([params1] body1...) ([params2] body2...))
        List<Object> arityForms = new ArrayList<>();

        for (Object[] pair : arityList) {
            IPersistentVector params = (IPersistentVector) pair[0];
            ISeq body = (ISeq) pair[1];

            // Use actual first parameter name for field access (could be _, this, etc.)
            Symbol thisSym = (params.count() > 0 && params.nth(0) instanceof Symbol s) ? s : Symbol.intern("this");

            // Build field bindings for this arity
            java.util.Set<String> paramNames = new java.util.HashSet<>();
            for (int i = 0; i < params.count(); i++) {
                if (params.nth(i) instanceof Symbol s2) paramNames.add(s2.getName());
            }
            List<Object> letBindings = new ArrayList<>();
            for (String fieldName : fieldNames) {
                if (paramNames.contains(fieldName)) continue;
                letBindings.add(Symbol.intern(fieldName));
                letBindings.add(ClojureRT.list(Symbol.intern(".-" + fieldName), thisSym));
            }

            ISeq wrappedBody;
            if (letBindings.isEmpty()) {
                wrappedBody = body;
            } else {
                IPersistentVector bindVec = PersistentVector.create(letBindings);
                Object letForm = ClojureRT.cons(Symbol.intern("let"), ClojureRT.cons(bindVec, body));
                wrappedBody = ClojureRT.list(letForm);
            }

            // ([params] wrapped-body...)
            arityForms.add(ClojureRT.cons(params, wrappedBody));
        }

        // (fn* ([params1] body1) ([params2] body2) ...)
        ISeq fnSeq = ClojureRT.cons(Symbol.intern("fn*"), PersistentList.create(arityForms));
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
                form = ClojureRT.cons(stepSeq.first(), ClojureRT.cons(form, stepSeq.next()));
            } else {
                // Bare symbol: (f) -> (f form)
                form = ClojureRT.list(step, form);
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
                form = ClojureRT.list(step, form);
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
        Object letForm = ClojureRT.list(Symbol.intern("let"),
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
                    threadedForm = ClojureRT.cons(stepSeq.first(), ClojureRT.cons(tmpSym, stepSeq.next()));
                } else {
                    java.util.List<Object> newList = new ArrayList<>();
                    for (ISeq s = stepSeq; s != null; s = s.next()) newList.add(s.first());
                    newList.add(tmpSym);
                    threadedForm = PersistentList.create(newList);
                }
            } else {
                threadedForm = ClojureRT.list(step, tmpSym);
            }
            // (if (nil? t) nil threadedForm)
            bindings.add(tmpSym);
            bindings.add(ClojureRT.list(Symbol.intern("if"),
                    ClojureRT.list(Symbol.intern("nil?"), tmpSym),
                    null, threadedForm));
            args = args.next();
        }
        return analyze(ClojureRT.list(Symbol.intern("let"),
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
                    threadedForm = ClojureRT.cons(stepSeq.first(), ClojureRT.cons(tmpSym, stepSeq.next()));
                } else {
                    java.util.List<Object> newList = new ArrayList<>();
                    for (ISeq s = stepSeq; s != null; s = s.next()) newList.add(s.first());
                    newList.add(tmpSym);
                    threadedForm = PersistentList.create(newList);
                }
            } else {
                threadedForm = ClojureRT.list(step, tmpSym);
            }
            bindings.add(tmpSym);
            bindings.add(ClojureRT.list(Symbol.intern("if"), test, threadedForm, tmpSym));
            args = args.next();
        }
        return analyze(ClojureRT.list(Symbol.intern("let"),
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
                body.add(ClojureRT.cons(stepSeq.first(), ClojureRT.cons(tmpSym, stepSeq.next())));
            } else {
                body.add(ClojureRT.list(step, tmpSym));
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
                form = ClojureRT.list(Symbol.intern("." + sym.getName()), form);
            } else if (step instanceof ISeq stepSeq) {
                form = ClojureRT.cons(Symbol.intern("." + ((Symbol) stepSeq.first()).getName()),
                        ClojureRT.cons(form, stepSeq.next()));
            }
            args = args.next();
        }
        return analyze(form);
    }

    // --- Control flow ---

    private ExpressionNode analyzeIfLet(ISeq seq) {
        // (if-let [pattern expr] then else)
        // => (let [temp expr] (if temp (let [pattern temp] then) else))
        // Also supports multiple binding pairs (encore compatibility):
        // (if-let [a 1 b 2] then else) => (if-let [a 1] (if-let [b 2] then else) else)
        ISeq args = seq.next();
        if (args == null) throw err("if-let: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        // Handle empty bindings (encore compatibility): (if-let [] then else) => then
        if (bindings.count() == 0) {
            ISeq rest = args.next();
            Object thenF = rest != null ? rest.first() : null;
            return thenF != null ? analyze(thenF) : new NilNode();
        }
        if (bindings.count() % 2 != 0) {
            throw err("if-let: binding must have even number of forms, got " + bindings.count() + ": " + bindings);
        }
        // Multiple binding pairs: chain into nested if-let
        if (bindings.count() > 2) {
            // (if-let [a 1 b 2 ...] then else)
            // => (if-let [a 1] (if-let [b 2 ...] then else) else)
            args = args.next();
            Object thenForm = args != null ? args.first() : null;
            Object elseForm = (args != null && args.next() != null) ? args.next().first() : null;
            IPersistentVector firstPair = ClojureRT.vector(bindings.nth(0), bindings.nth(1));
            java.util.List<Object> restBindings = new ArrayList<>();
            for (int i = 2; i < bindings.count(); i++) restBindings.add(bindings.nth(i));
            IPersistentVector restVec = PersistentVector.create(restBindings);
            Object innerIfLet = ClojureRT.list(Symbol.intern("if-let"), restVec, thenForm, elseForm);
            return analyze(ClojureRT.list(Symbol.intern("if-let"), firstPair, innerIfLet, elseForm));
        }
        args = args.next();
        Object thenForm = args != null ? args.first() : null;
        Object elseForm = (args != null && args.next() != null) ? args.next().first() : null;
        Object bindingForm = bindings.nth(0);
        Object initExpr = bindings.nth(1);
        if (bindingForm instanceof Symbol) {
            // Simple case: (let [x expr] (if x then else))
            return analyze(ClojureRT.list(Symbol.intern("let"), bindings,
                    ClojureRT.list(Symbol.intern("if"), bindingForm, thenForm, elseForm)));
        } else {
            // Destructuring case: use temp var for the test
            Symbol temp = Symbol.intern("__if_let_tmp__");
            return analyze(ClojureRT.list(Symbol.intern("let"),
                    ClojureRT.vector(temp, initExpr),
                    ClojureRT.list(Symbol.intern("if"), temp,
                            ClojureRT.list(Symbol.intern("let"),
                                    ClojureRT.vector(bindingForm, temp),
                                    thenForm),
                            elseForm)));
        }
    }

    private ExpressionNode analyzeWhenLet(ISeq seq) {
        // (when-let [pattern expr] body...) => (let [temp expr] (when temp (let [pattern temp] body...)))
        // Also supports multiple binding pairs (encore compatibility):
        // (when-let [a 1 b 2] body...) => (when-let [a 1] (when-let [b 2] body...))
        ISeq args = seq.next();
        if (args == null) throw err("when-let: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();
        // Handle empty bindings
        if (bindings.count() == 0) {
            return body != null ? analyzeDo(ClojureRT.cons(Symbol.intern("do"), body)) : new NilNode();
        }
        if (bindings.count() % 2 != 0) {
            throw err("when-let: binding must have even number of forms, got " + bindings.count());
        }
        // Multiple binding pairs: chain into nested when-let
        if (bindings.count() > 2) {
            IPersistentVector firstPair = ClojureRT.vector(bindings.nth(0), bindings.nth(1));
            java.util.List<Object> restBindings = new ArrayList<>();
            for (int i = 2; i < bindings.count(); i++) restBindings.add(bindings.nth(i));
            IPersistentVector restVec = PersistentVector.create(restBindings);
            // Build inner (when-let [rest...] body...)
            java.util.List<Object> innerForm = new ArrayList<>();
            innerForm.add(Symbol.intern("when-let"));
            innerForm.add(restVec);
            while (body != null) { innerForm.add(body.first()); body = body.next(); }
            return analyze(ClojureRT.list(Symbol.intern("when-let"), firstPair,
                    PersistentList.create(innerForm)));
        }
        Object bindingForm = bindings.nth(0);
        Object initExpr = bindings.nth(1);
        if (bindingForm instanceof Symbol) {
            // Simple case
            java.util.List<Object> whenForm = new ArrayList<>();
            whenForm.add(Symbol.intern("when"));
            whenForm.add(bindingForm);
            while (body != null) { whenForm.add(body.first()); body = body.next(); }
            return analyze(ClojureRT.list(Symbol.intern("let"), bindings,
                    PersistentList.create(whenForm)));
        } else {
            // Destructuring case: use temp var for the test
            Symbol temp = Symbol.intern("__when_let_tmp__");
            java.util.List<Object> innerBody = new ArrayList<>();
            innerBody.add(Symbol.intern("let"));
            innerBody.add(ClojureRT.vector(bindingForm, temp));
            while (body != null) { innerBody.add(body.first()); body = body.next(); }
            return analyze(ClojureRT.list(Symbol.intern("let"),
                    ClojureRT.vector(temp, initExpr),
                    ClojureRT.list(Symbol.intern("when"), temp,
                            PersistentList.create(innerBody))));
        }
    }

    private ExpressionNode analyzeIfSome(ISeq seq) {
        // (if-some [x expr] then else) - like if-let but only nil check (not false)
        ISeq args = seq.next();
        if (args == null) throw err("if-some: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        args = args.next();
        Object thenForm = args != null ? args.first() : null;
        Object elseForm = (args != null && args.next() != null) ? args.next().first() : null;
        Object bindForm = bindings.nth(0);
        Object exprForm = bindings.nth(1);
        Symbol tmpSym = Symbol.intern("__if_some_tmp__");
        // (let [tmp expr] (if (not (nil? tmp)) (let [bindForm tmp] then) else))
        return analyze(ClojureRT.list(Symbol.intern("let"),
                PersistentVector.create(java.util.Arrays.asList(tmpSym, exprForm)),
                ClojureRT.list(Symbol.intern("if"),
                        ClojureRT.list(Symbol.intern("not"), ClojureRT.list(Symbol.intern("nil?"), tmpSym)),
                        ClojureRT.list(Symbol.intern("let"),
                                PersistentVector.create(java.util.Arrays.asList(bindForm, tmpSym)),
                                thenForm),
                        elseForm)));
    }

    private ExpressionNode analyzeWhenSome(ISeq seq) {
        ISeq args = seq.next();
        if (args == null) throw err("when-some: missing bindings");
        IPersistentVector bindings = (IPersistentVector) args.first();
        ISeq body = args.next();
        Object bindForm = bindings.nth(0);
        Object exprForm = bindings.nth(1);
        Symbol tmpSym = Symbol.intern("__when_some_tmp__");
        // Build body as do form
        java.util.List<Object> doBody = new ArrayList<>();
        doBody.add(Symbol.intern("do"));
        while (body != null) { doBody.add(body.first()); body = body.next(); }
        // (let [tmp expr] (when (not (nil? tmp)) (let [bindForm tmp] body...)))
        return analyze(ClojureRT.list(Symbol.intern("let"),
                PersistentVector.create(java.util.Arrays.asList(tmpSym, exprForm)),
                ClojureRT.list(Symbol.intern("when"),
                        ClojureRT.list(Symbol.intern("not"), ClojureRT.list(Symbol.intern("nil?"), tmpSym)),
                        ClojureRT.list(Symbol.intern("let"),
                                PersistentVector.create(java.util.Arrays.asList(bindForm, tmpSym)),
                                PersistentList.create(doBody)))));
    }

    private ExpressionNode analyzeCase(ISeq seq) {
        // (case expr val1 result1 val2 result2 default)
        ISeq args = seq.next();
        if (args == null) throw err("case: missing expression");
        ExpressionNode exprNode = analyze(args.first());
        args = args.next();

        int tmpSlot = currentScope.addLocal("__case_tmp__");
        List<Object[]> clauses = new ArrayList<>(); // [matchVal, resultForm]
        java.util.List<Object> formList = new ArrayList<>();
        while (args != null) {
            formList.add(args.first());
            args = args.next();
        }
        java.util.Set<Object> seenConstants = new java.util.HashSet<>();
        for (int i = 0; i < formList.size() - 1; i += 2) {
            Object testVal = formList.get(i);
            // Check for duplicate test constants
            if (testVal instanceof IPersistentList || (testVal instanceof ISeq && !(testVal instanceof IPersistentVector))) {
                for (ISeq s = ClojureRT.seq(testVal); s != null; s = s.next()) {
                    if (!seenConstants.add(s.first()))
                        throw compilerError("Duplicate case test constant: " + s.first());
                }
            } else {
                if (!seenConstants.add(testVal))
                    throw compilerError("Duplicate case test constant: " + testVal);
            }
            clauses.add(new Object[]{testVal, formList.get(i + 1)});
        }
        // Emit performance warnings to *err*
        emitCaseWarnings(seenConstants);
        boolean hasDefault = formList.size() % 2 == 1;

        // Build: set tmp, then chain of if (= tmp val) result ...
        int caseSlot = tmpSlot;
        ExpressionNode chain;
        if (hasDefault) {
            Object defaultForm = formList.get(formList.size() - 1);
            chain = defaultForm == null
                    ? new QuoteNode(clojure.truffle.runtime.ClojureNil.INSTANCE)
                    : analyze(defaultForm);
        } else {
            chain = new ExpressionNode() {
                    @Override
                    public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame f) {
                        Object val = f.getObject(caseSlot);
                        throw caseNoMatchError(val);
                    }
                };
        }
        for (int i = clauses.size() - 1; i >= 0; i--) {
            Object matchVal = clauses.get(i)[0];
            // Normalize nil match value to ClojureNil
            if (matchVal == null) matchVal = clojure.truffle.runtime.ClojureNil.INSTANCE;
            ExpressionNode resultNode = analyze(clauses.get(i)[1]);

            // Handle grouped test values: (case x (:a :b) "match" ...)
            // Only lists are group test values; vectors/maps/sets are literal match values
            if (matchVal instanceof IPersistentList || (matchVal instanceof ISeq && !(matchVal instanceof IPersistentVector))) {
                // Multiple test values — generate (or (= tmp v1) (= tmp v2) ...)
                List<ExpressionNode> tests = new ArrayList<>();
                for (ISeq s = ClojureRT.seq(matchVal); s != null; s = s.next()) {
                    Object v = s.first();
                    ExpressionNode vNode = new QuoteNode(v);
                    tests.add(new InvokeNode(
                            new SymbolNode(context, "="),
                            new ExpressionNode[]{new ReadLocalNode(tmpSlot), vNode}));
                }
                ExpressionNode condNode = tests.get(0);
                for (int j = 1; j < tests.size(); j++) {
                    // (or test1 test2) via if-chain
                    condNode = new IfNode(condNode, new QuoteNode(true), tests.get(j));
                }
                chain = new IfNode(condNode, resultNode, chain);
            } else {
                ExpressionNode matchNode = new QuoteNode(matchVal);
                ExpressionNode condNode = new InvokeNode(
                        new SymbolNode(context, "="),
                        new ExpressionNode[]{new ReadLocalNode(tmpSlot), matchNode});
                chain = new IfNode(condNode, resultNode, chain);
            }
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
            return analyzeDo(ClojureRT.cons(Symbol.intern("do"), body));
        }

        Object key = bindings.nth(pos);

        // :when modifier
        if (key instanceof Keyword kw && kw.getName().equals("when")) {
            Object pred = bindings.nth(pos + 1);
            ExpressionNode innerNode = analyzeForBindings(bindings, pos + 2, body);
            // Check if there are more normal collection bindings after this :when
            // If only :let/:when modifiers remain, innerNode is a bare value (not a seq)
            boolean hasMoreCollBindings = false;
            for (int i = pos + 2; i < bindings.count(); i += 2) {
                Object b = bindings.nth(i);
                if (!(b instanceof Keyword)) { hasMoreCollBindings = true; break; }
            }
            if (!hasMoreCollBindings) {
                // No more collection bindings — innerNode is a bare value.
                // Wrap in (list value) so mapcat gets a seq to concatenate.
                ExpressionNode listWrapped = new InvokeNode(
                        new SymbolNode(context, "list"),
                        new ExpressionNode[]{innerNode});
                return new IfNode(analyze(pred), listWrapped, new NilNode());
            } else {
                // More collection bindings follow — innerNode returns a seq from map/mapcat.
                return new IfNode(analyze(pred), innerNode, new NilNode());
            }
        }

        // :while modifier — wrap the preceding collection with take-while
        if (key instanceof Keyword kw && kw.getName().equals("while")) {
            // Skip :while — it will be handled in the collection binding phase
            // For now, treat like :when (filter, not stop — approximate)
            Object pred = bindings.nth(pos + 1);
            ExpressionNode innerNode = analyzeForBindings(bindings, pos + 2, body);
            boolean hasMoreCollBindings = false;
            for (int i = pos + 2; i < bindings.count(); i += 2) {
                Object b = bindings.nth(i);
                if (!(b instanceof Keyword)) { hasMoreCollBindings = true; break; }
            }
            if (!hasMoreCollBindings) {
                ExpressionNode listWrapped = new InvokeNode(
                        new SymbolNode(context, "list"),
                        new ExpressionNode[]{innerNode});
                return new IfNode(analyze(pred), listWrapped, new NilNode());
            } else {
                return new IfNode(analyze(pred), innerNode, new NilNode());
            }
        }

        // :let modifier
        if (key instanceof Keyword kw && kw.getName().equals("let")) {
            IPersistentVector letBindings = (IPersistentVector) bindings.nth(pos + 1);
            Object innerForm = buildForInner(bindings, pos + 2, body);
            return analyze(ClojureRT.list(Symbol.intern("let"), letBindings, innerForm));
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
                bodyExpr = ClojureRT.list(Symbol.intern("let"),
                        PersistentVector.create(java.util.List.of(bodyWrapper, paramSym)), bodyExpr);
            }
            Object fnForm = ClojureRT.list(Symbol.intern("fn"),
                    PersistentVector.create(java.util.List.of(paramSym)),
                    bodyExpr);
            return analyze(ClojureRT.list(Symbol.intern("map"), fnForm, collForm));
        } else {
            // Not last: (mapcat (fn [sym] (for [rest...] body)) coll)
            IPersistentVector restBindings = PersistentVector.EMPTY;
            for (int i = pos + 2; i < bindings.count(); i++)
                restBindings = restBindings.cons(bindings.nth(i));
            Object innerFor = ClojureRT.list(Symbol.intern("for"), restBindings, body.first());
            if (bodyWrapper != null) {
                innerFor = ClojureRT.list(Symbol.intern("let"),
                        PersistentVector.create(java.util.List.of(bodyWrapper, paramSym)), innerFor);
            }
            Object fnForm = ClojureRT.list(Symbol.intern("fn"),
                    PersistentVector.create(java.util.List.of(paramSym)), innerFor);
            return analyze(ClojureRT.list(Symbol.intern("mapcat"), fnForm, collForm));
        }
    }

    private Object buildForInner(IPersistentVector bindings, int pos, ISeq body) {
        if (pos >= bindings.count()) return body.first();
        // Rebuild remaining as (for [rest...] body)
        IPersistentVector restBindings = PersistentVector.EMPTY;
        for (int i = pos; i < bindings.count(); i++)
            restBindings = restBindings.cons(bindings.nth(i));
        return ClojureRT.list(Symbol.intern("for"), restBindings, body.first());
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
        Object forForm = ClojureRT.list(Symbol.intern("for"), bindings, PersistentList.create(doBody));
        return analyze(ClojureRT.list(Symbol.intern("dorun"), forForm));
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
        loopBody.add(ClojureRT.list(Symbol.intern("<"), iSym, nSym));
        while (body != null) { loopBody.add(body.first()); body = body.next(); }
        loopBody.add(ClojureRT.list(Symbol.intern("recur"), ClojureRT.list(Symbol.intern("inc"), iSym)));

        Object loopForm = ClojureRT.list(Symbol.intern("loop"),
                PersistentVector.create(java.util.List.of(iSym, 0L)),
                PersistentList.create(loopBody));
        return analyze(ClojureRT.list(Symbol.intern("let"),
                PersistentVector.create(java.util.List.of(nSym, nForm)), loopForm));
    }

    private ExpressionNode analyzeLetfn(ISeq seq) {
        // (letfn [(f [x] body) (g [y] body)] expr)
        // Uses mutable cell (Object[1]) indirection for mutual recursion:
        //   - Each letfn name gets a cell stored in its local slot
        //   - References to letfn names read through the cell (LetfnCellReadNode)
        //   - After all fns are compiled & created, cells are updated with actual values
        ISeq args = seq.next();
        if (args == null) throw err("letfn: missing bindings");
        IPersistentVector fnBindings = (IPersistentVector) args.first();
        ISeq body = args.next();

        // First pass: declare all names and mark as letfn
        List<String> names = new ArrayList<>();
        for (int i = 0; i < fnBindings.count(); i++) {
            ISeq fnSpec = (ISeq) fnBindings.nth(i);
            names.add(((Symbol) fnSpec.first()).getName());
        }

        // Allocate slots - initialize with Object[1] cells (not nil)
        List<Integer> slots = new ArrayList<>();
        List<ExpressionNode> values = new ArrayList<>();
        for (String name : names) {
            int slot = currentScope.addLocal(name);
            currentScope.markLetfn(name);
            slots.add(slot);
            // Initialize with a mutable cell containing nil
            values.add(new ExpressionNode() {
                @Override
                public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                    return new Object[]{clojure.truffle.runtime.ClojureNil.INSTANCE};
                }
            });
        }

        // Second pass: compile fns (references to other letfn names go through cells)
        List<ExpressionNode> allSteps = new ArrayList<>();
        for (int i = 0; i < fnBindings.count(); i++) {
            ISeq fnSpec = (ISeq) fnBindings.nth(i);
            Symbol fnName = (Symbol) fnSpec.first();
            ISeq fnArgs = fnSpec.next();
            ISeq fnForm = ClojureRT.cons(Symbol.intern("fn*"), ClojureRT.cons(fnName, fnArgs));
            ExpressionNode fnNode = analyze(fnForm);
            final int slot = slots.get(i);
            // Create the fn and update its cell
            allSteps.add(new ExpressionNode() {
                @Child ExpressionNode valueNode = fnNode;
                final int s = slot;
                @Override
                public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                    Object val = valueNode.executeGeneric(frame);
                    Object[] cell = (Object[]) frame.getObject(s);
                    cell[0] = val;
                    return val;
                }
            });
        }

        ExpressionNode bodyNode = analyzeBody(body);
        allSteps.add(bodyNode);

        ExpressionNode assignAndBody = new DoNode(allSteps.toArray(new ExpressionNode[0]));

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

        final Analyzer self = this;
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return doNs(context, nsName, capturedRequires, capturedUses, capturedImports,
                           capturedExcludes, capturedHasReferClojure, self);
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
                return doInNs(context, name);
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
        final Analyzer self = this;
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return doRequire(self, capturedSpecs);
            }
        };
    }

    private void processRequireSpec(Object spec) {
        // Unwrap quote: (quote foo) -> foo
        if (spec instanceof ISeq qs) {
            Object first = qs.first();
            if (first instanceof Symbol s && s.getName().equals("quote")) {
                spec = qs.next().first();
            } else if (first instanceof Symbol pfx) {
                // Prefix list in list form: (clojure.data.xml [protocols :refer [AsQName]])
                // Convert to vector form and delegate
                String prefix = pfx.getName();
                for (ISeq rest = qs.next(); rest != null; rest = rest.next()) {
                    Object sub = rest.first();
                    if (sub instanceof IPersistentVector sv) {
                        String subNs = prefix + "." + ((Symbol) sv.nth(0)).getName();
                        java.util.List<Object> newSpec = new ArrayList<>();
                        newSpec.add(Symbol.intern(subNs));
                        for (int j = 1; j < sv.count(); j++) newSpec.add(sv.nth(j));
                        processRequireSpec(clojure.lang.PersistentVector.create(newSpec));
                    } else if (sub instanceof Symbol subSym) {
                        processRequireSpec(Symbol.intern(prefix + "." + subSym.getName()));
                    } else if (sub instanceof ISeq subSeq) {
                        // Nested list form: (sub :as s)
                        String subNs = prefix + "." + ((Symbol) subSeq.first()).getName();
                        java.util.List<Object> newSpec = new ArrayList<>();
                        newSpec.add(Symbol.intern(subNs));
                        for (ISeq sr = subSeq.next(); sr != null; sr = sr.next()) newSpec.add(sr.first());
                        processRequireSpec(clojure.lang.PersistentVector.create(newSpec));
                    }
                }
                return;
            }
        }
        if (spec instanceof Symbol sym) {
            // Simple require: (require 'some.ns)
            context.loadNamespace(sym.getName());
        } else if (spec instanceof IPersistentVector v) {
            // [some.ns :as s] or [some.ns :refer [foo bar]]
            // or prefix list: [prefix.ns [sub1 :as s1] [sub2 :as s2]]
            if (v.count() == 0) return;

            // Check for prefix list: [prefix [sub1 :as s1] [sub2 :as s2]]
            // A prefix list has vectors/lists as direct children at position 1
            boolean isPrefixList = v.count() > 1 &&
                    (v.nth(1) instanceof IPersistentVector || v.nth(1) instanceof ISeq);
            if (isPrefixList) {
                String prefix = ((Symbol) v.nth(0)).getName();
                for (int i = 1; i < v.count(); i++) {
                    Object sub = v.nth(i);
                    if (sub instanceof IPersistentVector sv) {
                        // Prepend prefix to first element
                        String subNs = prefix + "." + ((Symbol) sv.nth(0)).getName();
                        java.util.List<Object> newSpec = new ArrayList<>();
                        newSpec.add(Symbol.intern(subNs));
                        for (int j = 1; j < sv.count(); j++) newSpec.add(sv.nth(j));
                        processRequireSpec(clojure.lang.PersistentVector.create(newSpec));
                    } else if (sub instanceof Symbol subSym) {
                        processRequireSpec(Symbol.intern(prefix + "." + subSym.getName()));
                    }
                }
                return;
            }

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
                            currentNs.refer(targetName, val, nsName);
                            Object macroVal = reqNs.resolve("__macro__" + symName);
                            if (macroVal != null) {
                                currentNs.refer("__macro__" + targetName, macroVal, nsName);
                            }
                        }
                    }
                } else if (referSpec instanceof ISeq rs) {
                    // :refer (sym1 sym2 ...) — list form
                    for (ISeq s = rs; s != null; s = s.next()) {
                        String symName = ((Symbol) s.first()).getName();
                        Object val = reqNs.resolve(symName);
                        if (val != null) {
                            String targetName = (renames != null && renames.containsKey(symName))
                                    ? renames.get(symName) : symName;
                            currentNs.refer(targetName, val, nsName);
                            Object macroVal = reqNs.resolve("__macro__" + symName);
                            if (macroVal != null) {
                                currentNs.refer("__macro__" + targetName, macroVal, nsName);
                            }
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
            throw specError("let", "Unsupported destructuring form: " + bindingForm);
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
                if (sym.getNamespace() != null) {
                    throw specError("let", "Can't let qualified name: " + sym);
                }
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
        // Convert seq source to map: '(:a 1 :b 2) -> {:a 1 :b 2}, '() -> {}
        // This mirrors Clojure's destructure behavior for map patterns applied to seqs
        int mapSlotConverted = currentScope.addLocal("__map_destruct__" + sourceSlot);
        slots.add(mapSlotConverted);
        values.add(new InvokeNode(new SymbolNode(context, "clojure.core/__destructure-map__"),
                new ExpressionNode[]{new ReadLocalNode(sourceSlot)}));
        sourceSlot = mapSlotConverted;

        // {:keys [a b] :strs [c] :or {a 1} :as all}
        // or {localName :mapKey, ...}
        // Also supports ::keys (namespace-qualified) e.g. {::keys [a]} -> bind a to (::a source)
        Object keysVec = pattern.valAt(Keyword.intern("keys"));
        Object strsVec = pattern.valAt(Keyword.intern("strs"));
        Object symsVec = pattern.valAt(Keyword.intern("syms"));
        Object orMap = pattern.valAt(Keyword.intern("or"));
        Object asName = pattern.valAt(Keyword.intern("as"));
        IPersistentMap defaults = (orMap instanceof IPersistentMap m) ? m : null;

        // Check for ::keys and :ns/keys and :ns/syms patterns
        String nsKeysNs = null;
        Object nsKeysVec = null;
        String nsSymsNs = null;
        Object nsSymsVec = null;
        for (ISeq scanSeq = pattern.seq(); scanSeq != null; scanSeq = scanSeq.next()) {
            IMapEntry entry = (IMapEntry) scanSeq.first();
            Object k = entry.key();
            if (k instanceof Keyword kw && kw.getNamespace() != null) {
                if (kw.getName().equals("keys")) {
                    nsKeysNs = kw.getNamespace();
                    nsKeysVec = entry.val();
                } else if (kw.getName().equals("syms")) {
                    nsSymsNs = kw.getNamespace();
                    nsSymsVec = entry.val();
                }
            }
        }

        // :keys [a b] or :keys [:a :b] -> bind a to (:a source), b to (:b source)
        // :keys [a/b] -> bind b to (:a/b source)
        if (keysVec instanceof IPersistentVector kv) {
            for (int i = 0; i < kv.count(); i++) {
                Object elem = kv.nth(i);
                String localName;
                Keyword key;
                if (elem instanceof Symbol sym) {
                    localName = sym.getName();
                    key = Keyword.intern(sym.getNamespace(), sym.getName());
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

        // ::keys [a b] -> bind a to (:ns/a source), b to (:ns/b source)
        if (nsKeysVec instanceof IPersistentVector nkv) {
            for (int i = 0; i < nkv.count(); i++) {
                Object elem = nkv.nth(i);
                String localName;
                Keyword key;
                if (elem instanceof Symbol sym) {
                    localName = sym.getName();
                    key = Keyword.intern(nsKeysNs, localName);
                } else if (elem instanceof Keyword kw) {
                    localName = kw.getName();
                    key = Keyword.intern(nsKeysNs, localName);
                } else {
                    throw err("Unsupported ::keys element: " + elem);
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

        // :ns/syms [a b] -> bind a to ('ns/a source), b to ('ns/b source)
        if (nsSymsVec instanceof IPersistentVector nsv) {
            for (int i = 0; i < nsv.count(); i++) {
                Object elem = nsv.nth(i);
                String localName;
                Symbol key;
                if (elem instanceof Symbol sym) {
                    localName = sym.getName();
                    key = Symbol.intern(nsSymsNs, localName);
                } else {
                    throw err("Unsupported :ns/syms element: " + elem);
                }
                ExpressionNode getExpr = makeGetNodeQuoted(sourceSlot, key, defaults, Symbol.intern(localName));
                slots.add(currentScope.addLocal(localName));
                values.add(getExpr);
            }
        }

        // :syms [a b] -> bind a to ('a source), b to ('b source)
        // :syms [a/b] -> bind b to ('a/b source)
        if (symsVec instanceof IPersistentVector yv) {
            for (int i = 0; i < yv.count(); i++) {
                Symbol sym = (Symbol) yv.nth(i);
                Symbol key = sym; // preserve namespace if present
                // For :or defaults, use local name (without namespace) as lookup key
                Symbol localSym = Symbol.intern(sym.getName());
                // Use QuoteNode directly so the symbol is treated as a literal value, not a var reference
                ExpressionNode getExpr = makeGetNodeQuoted(sourceSlot, key, defaults, localSym);
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
            // Nested destructuring: {[a b] :key} or {{:keys [x]} :key}
            if ((k instanceof IPersistentVector || k instanceof IPersistentMap) && !(k instanceof Keyword)) {
                ExpressionNode getExpr;
                if (v instanceof Keyword kw) {
                    getExpr = makeGetNode(sourceSlot, kw, defaults, null);
                } else {
                    getExpr = makeGetNodeObj(sourceSlot, v, defaults, null);
                }
                // Store the intermediate value in a temp slot
                int tempSlot = currentScope.addLocal("__nested_destr_" + sourceSlot + "_" + slots.size());
                slots.add(tempSlot);
                values.add(getExpr);
                // Recursively expand
                expandDestructuring(k, tempSlot, slots, values);
            }
        }

        // :as binds the whole map
        if (asName instanceof Symbol asSym) {
            slots.add(currentScope.addLocal(asSym.getName()));
            values.add(new ReadLocalNode(sourceSlot));
        }
    }

    // Helper: (nth source index nil) — returns nil for out-of-bounds (safe for destructuring)
    private ExpressionNode makeNthNode(int sourceSlot, int index) {
        return new InvokeNode(
                new SymbolNode(context, "nth"),
                new ExpressionNode[]{new ReadLocalNode(sourceSlot), new LongLiteralNode(index), new NilNode()});
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

        // Process var metadata: split into static (compile-time) and dynamic (runtime) entries
        if (context != null && meta != null && meta.count() > 0) {
            clojure.lang.IPersistentMap staticMeta = clojure.lang.PersistentArrayMap.EMPTY;
            java.util.List<Object> dynKeys = new java.util.ArrayList<>();
            java.util.List<ExpressionNode> dynNodes = new java.util.ArrayList<>();

            for (ISeq s = meta.seq(); s != null; s = s.next()) {
                clojure.lang.IMapEntry entry = (clojure.lang.IMapEntry) s.first();
                Object k = entry.key();
                Object v = entry.val();
                if (v instanceof ISeq formSeq) {
                    // Form values like (fn [] ...) need runtime evaluation
                    dynKeys.add(k);
                    dynNodes.add(analyze(formSeq));
                } else {
                    staticMeta = staticMeta.assoc(k, resolveVarRefsInMeta(v));
                }
            }

            if (dynNodes.isEmpty()) {
                // All static - set at compile time
                String ns = context.getCurrentNamespace();
                String qname = ns + "/" + name;
                context.setVarMeta(qname, staticMeta);
                return new DefNode(context, name, valueNode);
            } else {
                // Has dynamic entries - evaluate at runtime
                return new DefNode(context, name, valueNode,
                    staticMeta,
                    dynKeys.toArray(),
                    dynNodes.toArray(new ExpressionNode[0]));
            }
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
            if (bindingForm == null || bindingForm instanceof ClojureNil) {
                // nil binding form (e.g., from macro expansion) - evaluate init but discard
                slotList.add(currentScope.addLocal("__nil_bind_" + i));
                valueList.add(initExpr);
            } else if (bindingForm instanceof Symbol sym) {
                if (sym.getNamespace() != null) {
                    throw specError("let", "Can't let qualified name: " + sym);
                }
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
        if (args == null) throw fnSpecError("fn*: missing parameters");

        String fnName = null;
        if (args.first() instanceof Symbol sym) {
            fnName = sym.getNamespace() != null ? sym.getNamespace() + "/" + sym.getName() : sym.getName();
            args = args.next();
            if (args == null) throw fnSpecError("fn*: missing parameters after name");
        }

        // Skip docstring if present (only valid after a name symbol)
        if (fnName != null && args.first() instanceof String) {
            args = args.next();
            if (args == null) throw fnSpecError("fn*: missing parameters after docstring");
        } else if (fnName == null && args.first() instanceof String) {
            throw fnSpecError("fn*: string in arg position without name");
        }

        // Skip metadata map if present (e.g., {:added "1.1"})
        if (args.first() instanceof clojure.lang.IPersistentMap && !(args.first() instanceof clojure.lang.IPersistentVector)) {
            args = args.next();
            if (args == null) throw fnSpecError("fn*: missing parameters after metadata map");
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
            throw fnSpecError("fn*: parameters must be a vector");
        IPersistentVector params = (IPersistentVector) args.first();

        Scope outerScope = currentScope;
        currentScope = new Scope(outerScope);

        // Add self-reference slot for named fns (use local name for scope lookup)
        int selfSlot = -1;
        if (fnName != null) {
            String localName = fnName.contains("/") ? fnName.substring(fnName.lastIndexOf('/') + 1) : fnName;
            selfSlot = currentScope.addLocal(localName);
        }

        List<Integer> destructSlots = new ArrayList<>();
        List<ExpressionNode> destructValues = new ArrayList<>();

        int[] result = parseParams(params, destructSlots, destructValues);
        int[] paramSlots = Arrays.copyOf(result, result.length - 1);
        int variadicSlot = result[result.length - 1];

        // Check for :pre/:post conditions map as first body form
        ISeq bodyForms = stripPrePost(args.next());

        ExpressionNode bodyNode = analyzeBody(bodyForms);

        // Wrap body with destructuring bindings if any
        if (!destructSlots.isEmpty()) {
            int[] dSlots = destructSlots.stream().mapToInt(Integer::intValue).toArray();
            ExpressionNode[] dValues = destructValues.toArray(new ExpressionNode[0]);
            bodyNode = new LetNode(dSlots, dValues, bodyNode);
        }

        int[] outerCaptureSlots = currentScope.getOuterCaptureSlots();
        int[] innerCaptureSlots = currentScope.getInnerCaptureSlots();
        int fnResultSlot = currentScope.addLocal("__fn_result__");
        FrameDescriptor fd = currentScope.buildDescriptor();

        FnBodyNode fnBody = new FnBodyNode(language, fd, fnName,
                paramSlots, variadicSlot, innerCaptureSlots, bodyNode, selfSlot, fnResultSlot);

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
                throw fnSpecError("fn*: arity clause must be a list");

            if (!(clauseSeq.first() instanceof IPersistentVector))
                throw fnSpecError("fn*: arity parameters must be a vector");
            IPersistentVector params = (IPersistentVector) clauseSeq.first();
            ISeq body = clauseSeq.next();

            currentScope = new Scope(outerScope);

            // Add self-reference slot for named fns (use local name for scope lookup)
            int selfSlot = -1;
            if (fnName != null) {
                String localName = fnName.contains("/") ? fnName.substring(fnName.lastIndexOf('/') + 1) : fnName;
                selfSlot = currentScope.addLocal(localName);
            }

            List<Integer> dSlots = new ArrayList<>();
            List<ExpressionNode> dVals = new ArrayList<>();
            int[] result = parseParams(params, dSlots, dVals);
            int[] paramSlots = Arrays.copyOf(result, result.length - 1);
            int varSlot = result[result.length - 1];

            ExpressionNode bodyNode = analyzeBody(stripPrePost(body));
            if (!dSlots.isEmpty()) {
                bodyNode = new LetNode(
                        dSlots.stream().mapToInt(Integer::intValue).toArray(),
                        dVals.toArray(new ExpressionNode[0]), bodyNode);
            }

            int[] outerCaptures = currentScope.getOuterCaptureSlots();
            int[] innerCaptures = currentScope.getInnerCaptureSlots();
            int fnResultSlot = currentScope.addLocal("__fn_result__");
            FrameDescriptor fd = currentScope.buildDescriptor();

            FnBodyNode fnBody = new FnBodyNode(language, fd, fnName,
                    paramSlots, varSlot, innerCaptures, bodyNode, selfSlot, fnResultSlot);
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
                    // Rest args arrive as either:
                    //   1. A seq of key-value pairs: (:x 1 :y 2) → (apply hash-map seq)
                    //   2. A single map (Clojure 1.11+): ({:x 1 :y 2}) → use directly
                    String tmpName = "__var_rest_" + i;
                    varSlot = currentScope.addLocal(tmpName);
                    if (destructSlots != null) {
                        String mapTmpName = "__var_map_" + i;
                        int mapSlot = currentScope.addLocal(mapTmpName);
                        final int restSlot = varSlot;
                        // Runtime check: if rest is a single-element seq containing a map, use it directly
                        ExpressionNode kwArgConvert = new ExpressionNode() {
                            @Child private ExpressionNode restNode = new ReadLocalNode(restSlot);
                            @Override
                            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                                Object rest = restNode.executeGeneric(frame);
                                return convertKwArgs(rest, context);
                            }
                        };
                        destructSlots.add(mapSlot);
                        destructValues.add(kwArgConvert);
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
        if (args.next() != null) {
            clojure.lang.IPersistentMap data = (clojure.lang.IPersistentMap)
                    clojure.lang.PersistentArrayMap.EMPTY
                            .assoc(clojure.lang.Keyword.intern("form"), seq);
            throw new clojure.lang.Compiler.CompilerException(
                    (String) null, 0, 0,
                    new clojure.lang.ExceptionInfo("Wrong number of args passed to quote", data));
        }
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
            ISeq newSeq = ClojureRT.list(Symbol.intern("loop"), newBindings,
                    PersistentList.create(letForm));
            return analyzeLoop(newSeq);
        }

        int[] slots = new int[count];
        ExpressionNode[] values = new ExpressionNode[count];
        // Analyze all init values BEFORE adding locals to avoid shadowing
        // e.g., (loop [x x] ...) should use outer x's value as init
        for (int i = 0; i < count; i++) {
            values[i] = analyze(bindings.nth(i * 2 + 1));
        }
        for (int i = 0; i < count; i++) {
            slots[i] = currentScope.addLocal(((Symbol) bindings.nth(i * 2)).getName());
        }
        int resultSlot = currentScope.addLocal("__loop_result__" + System.nanoTime());
        return new LoopNode(slots, values, analyzeBody(args.next()), resultSlot);
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

        // Handle taoensso/better-cond extensions
        if (test instanceof Keyword kw) {
            switch (kw.getName()) {
                case "else": {
                    if (rest == null) throw err("cond: :else without value");
                    return analyze(rest.first());
                }
                case "let": {
                    // :let [bindings] — establish let bindings for remaining forms
                    if (rest == null) throw err("cond: :let without bindings");
                    Object bindings = rest.first();
                    ISeq remaining = rest.next();
                    // Build the remaining cond as the let body
                    // We construct (let [bindings] (cond remaining...)) as forms
                    Object condBody;
                    if (remaining != null) {
                        condBody = ClojureRT.cons(Symbol.intern("cond"), remaining);
                    } else {
                        condBody = null; // nil
                    }
                    return analyze(ClojureRT.list(Symbol.intern("let"), bindings,
                            condBody != null ? condBody : null));
                }
                case "do": {
                    // :do expr — evaluate for side effects, then continue
                    if (rest == null) throw err("cond: :do without expr");
                    ExpressionNode sideEffect = analyze(rest.first());
                    ExpressionNode continuation = buildCondChain(rest.next());
                    return new DoNode(new ExpressionNode[]{sideEffect, continuation});
                }
                case "when": {
                    // :when test — if falsy, return nil; else continue
                    if (rest == null) throw err("cond: :when without test");
                    ExpressionNode whenTest = analyze(rest.first());
                    ExpressionNode continuation = buildCondChain(rest.next());
                    return new IfNode(whenTest, continuation, new NilNode());
                }
            }
        }

        if (rest == null) {
            // Odd number of forms: treat as implicit else (taoensso extension)
            return analyze(test);
        }
        ExpressionNode thenNode = analyze(rest.first());
        ExpressionNode elseNode = buildCondChain(rest.next());
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
        clojure.lang.IPersistentMap symMeta = nameSym.meta();
        if (symMeta != null) {
            Object dynVal = symMeta.valAt(clojure.lang.Keyword.intern("dynamic"));
            if (Boolean.TRUE.equals(dynVal)) isDynamic = true;
            Object privVal = symMeta.valAt(clojure.lang.Keyword.intern("private"));
            if (Boolean.TRUE.equals(privVal)) isPrivate = true;
        }

        if (isDynamic) {
            context.declareDynamic(name);
        }

        // Collect var metadata from docstring, attr-map, and symbol meta
        // defn form: (defn name "doc"? attr-map? [params] body)
        // or multi-arity: (defn name "doc"? attr-map? ([params] body) ...)
        clojure.lang.IPersistentMap varMeta = symMeta != null ? symMeta : clojure.lang.PersistentArrayMap.EMPTY;
        ISeq restArgs = args.next();
        if (restArgs != null && restArgs.first() instanceof String doc) {
            // Docstring present - merge it
            varMeta = varMeta.assoc(clojure.lang.Keyword.intern("doc"), doc);
            // Check if attr-map follows
            if (restArgs.next() != null && restArgs.next().first() instanceof clojure.lang.IPersistentMap attrMap) {
                varMeta = mergeMetaMaps(varMeta, attrMap);
            }
        } else if (restArgs != null && restArgs.first() instanceof clojure.lang.IPersistentMap attrMap) {
            // attr-map without docstring
            varMeta = mergeMetaMaps(varMeta, attrMap);
        }
        if (isPrivate) {
            varMeta = varMeta.assoc(clojure.lang.Keyword.intern("private"), Boolean.TRUE);
        }

        // Store var metadata
        if (context != null && varMeta.count() > 0) {
            String ns = context.getCurrentNamespace();
            String qname = ns + "/" + name;
            context.setVarMeta(qname, (clojure.lang.IPersistentMap) resolveVarRefsInMeta(varMeta));
        }

        // Use namespace-qualified name for fn so ArityException messages include namespace
        String qualifiedName = (context != null ? context.getCurrentNamespace() + "/" : "") + name;
        // Replace the name symbol in args with the qualified version
        ISeq qualifiedArgs = ClojureRT.cons(Symbol.intern(qualifiedName), args.next());
        ISeq fnForm = ClojureRT.cons(Symbol.intern("fn*"), qualifiedArgs);
        ExpressionNode fnNode = analyzeFn(fnForm);
        return new DefNode(context, name, fnNode);
    }

    private clojure.lang.IPersistentMap mergeMetaMaps(clojure.lang.IPersistentMap base, clojure.lang.IPersistentMap overlay) {
        for (ISeq s = overlay.seq(); s != null; s = s.next()) {
            java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) s.first();
            base = base.assoc(entry.getKey(), entry.getValue());
        }
        return base;
    }

    /**
     * Recursively resolve (var symbol) forms in metadata to ClojureVar objects.
     * This is needed because #'symbol is read as (var symbol) by LispReader,
     * and attr-maps in defn/defmulti need these to be actual Var objects.
     */
    private Object resolveVarRefsInMeta(Object form) {
        if (form instanceof ISeq seq) {
            Object first = seq.first();
            if (first instanceof Symbol s && "var".equals(s.getName()) && s.getNamespace() == null) {
                // (var symbol) -> ClojureVar
                ISeq rest = seq.next();
                if (rest != null && rest.first() instanceof Symbol varSym) {
                    String ns, sym;
                    if (varSym.getNamespace() != null) {
                        ns = varSym.getNamespace();
                        // Resolve ns alias
                        if (context != null) {
                            ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
                            if (currentNs != null) {
                                ClojureNamespace resolved = currentNs.resolveAlias(ns);
                                if (resolved != null) ns = resolved.getName();
                            }
                        }
                        sym = varSym.getName();
                    } else {
                        sym = varSym.getName();
                        ns = context != null ? context.getCurrentNamespace() : "user";
                        // Resolve non-qualified symbols through refer sources
                        if (context != null) {
                            ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
                            if (currentNs != null) {
                                String sourceNs = currentNs.getReferSource(sym);
                                if (sourceNs != null) {
                                    ns = sourceNs;
                                } else if (currentNs.getInterns().containsKey(sym)) {
                                    ns = context.getCurrentNamespace();
                                }
                            }
                        }
                    }
                    return context.getOrCreateVar(ns + "/" + sym);
                }
            }
            // Not a var form, don't recurse into arbitrary lists
            return form;
        }
        if (form instanceof clojure.lang.IPersistentMap m) {
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (ISeq s = m.seq(); s != null; s = s.next()) {
                java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) s.first();
                result = result.assoc(
                    resolveVarRefsInMeta(entry.getKey()),
                    resolveVarRefsInMeta(entry.getValue()));
            }
            return result;
        }
        if (form instanceof clojure.lang.IPersistentSet set) {
            java.util.List<Object> items = new java.util.ArrayList<>();
            for (ISeq s = set.seq(); s != null; s = s.next()) {
                items.add(resolveVarRefsInMeta(s.first()));
            }
            return clojure.lang.PersistentHashSet.create(items);
        }
        if (form instanceof clojure.lang.IPersistentVector v) {
            java.util.List<Object> items = new java.util.ArrayList<>();
            for (int i = 0; i < v.count(); i++) {
                items.add(resolveVarRefsInMeta(v.nth(i)));
            }
            return clojure.lang.PersistentVector.create(items);
        }
        return form;
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
            case "IllegalAccessError" -> IllegalAccessError.class;
            case "IllegalStateException" -> IllegalStateException.class;
            case "UnsupportedOperationException" -> UnsupportedOperationException.class;
            case "ClassCastException" -> ClassCastException.class;
            case "StackOverflowError" -> StackOverflowError.class;
            case "AssertionError" -> AssertionError.class;
            case "ArityException" -> clojure.lang.ArityException.class;
            case "NumberFormatException" -> NumberFormatException.class;
            default -> {
                try {
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    yield (Class<? extends Throwable>) (cl != null ? Class.forName(className, true, cl) : Class.forName(className));
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
        Object head = seq.first();

        // Emit specialized arithmetic nodes for known builtins
        if (head instanceof Symbol sym && sym.getNamespace() == null) {
            String name = sym.getName();
            // Only specialize if not locally shadowed
            if (currentScope.findLocal(name) == null) {
                ExpressionNode specialized = trySpecializeArithmetic(name, seq.next());
                if (specialized != null) return specialized;
            }
        }

        ExpressionNode fn = analyze(head);
        return new InvokeNode(fn, analyzeArgList(seq.next()));
    }

    private ExpressionNode trySpecializeArithmetic(String name, ISeq argSeq) {
        int argc = countSeq(argSeq);

        // Binary arithmetic: (+ a b), (- a b), (* a b), (/ a b)
        if (argc == 2) {
            switch (name) {
                case "+": return AddNode.create(analyze(argSeq.first()), analyze(argSeq.next().first()));
                case "-": return SubNode.create(analyze(argSeq.first()), analyze(argSeq.next().first()));
                case "*": return MulNode.create(analyze(argSeq.first()), analyze(argSeq.next().first()));
                case "/": return DivNode.create(analyze(argSeq.first()), analyze(argSeq.next().first()));
                case "<": return CompareNode.create(CompareNode.Op.LT, analyze(argSeq.first()), analyze(argSeq.next().first()));
                case ">": return CompareNode.create(CompareNode.Op.GT, analyze(argSeq.first()), analyze(argSeq.next().first()));
                case "<=": return CompareNode.create(CompareNode.Op.LE, analyze(argSeq.first()), analyze(argSeq.next().first()));
                case ">=": return CompareNode.create(CompareNode.Op.GE, analyze(argSeq.first()), analyze(argSeq.next().first()));
                case "==": return CompareNode.create(CompareNode.Op.EQ, analyze(argSeq.first()), analyze(argSeq.next().first()));
            }
        }

        // Unary: (inc x), (dec x)
        if (argc == 1) {
            switch (name) {
                case "inc": return IncNode.create(analyze(argSeq.first()));
                case "dec": return DecNode.create(analyze(argSeq.first()));
            }
        }

        return null;
    }

    private static int countSeq(ISeq seq) {
        int count = 0;
        for (ISeq s = seq; s != null; s = s.next()) count++;
        return count;
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

    /**
     * Strip :pre/:post conditions map from the beginning of a fn body.
     * In Clojure, (fn [x] {:pre [...] :post [...]} body) uses % in :post
     * to refer to the return value. For now, we skip these conditions
     * rather than evaluating them, to avoid needing to resolve %.
     */
    private ISeq stripPrePost(ISeq body) {
        if (body != null && body.first() instanceof IPersistentMap m
                && !(body.first() instanceof IPersistentVector)) {
            // Check if it has :pre or :post keys
            Keyword preSym = Keyword.intern("pre");
            Keyword postSym = Keyword.intern("post");
            if (m.valAt(preSym) != null || m.valAt(postSym) != null) {
                // Skip the pre/post map; remaining forms are the actual body
                ISeq rest = body.next();
                return rest != null ? rest : body; // if no body after map, keep the map as body
            }
        }
        return body;
    }

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
        return analyze(ClojureRT.list(Symbol.intern("if"),
                ClojureRT.list(Symbol.intern("not"), test),
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
        return analyze(ClojureRT.list(Symbol.intern("if"),
                ClojureRT.list(Symbol.intern("not"), test),
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
        Symbol tmpPredResult = Symbol.intern("__condp_pr__");
        Keyword arrowKw = Keyword.intern(null, ">>");
        // Collect clauses as triples: (testVal, isArrow, result/fn)
        // plus optional default
        List<Object[]> clauses = new ArrayList<>();
        Object defaultVal = null;
        boolean hasDefault = false;
        while (args != null) {
            Object testVal = args.first();
            args = args.next();
            if (args == null) {
                // Default clause (no pair)
                defaultVal = testVal;
                hasDefault = true;
                break;
            }
            Object second = args.first();
            args = args.next();
            if (arrowKw.equals(second)) {
                // :>> clause: test-val :>> fn
                if (args == null) throw err("condp: :>> expects a function");
                Object fn = args.first();
                args = args.next();
                clauses.add(new Object[]{testVal, Boolean.TRUE, fn});
            } else {
                clauses.add(new Object[]{testVal, Boolean.FALSE, second});
            }
        }
        // Build from inside out
        Object elseExpr;
        if (hasDefault) {
            elseExpr = defaultVal;
        } else {
            // throw IllegalArgumentException if no match
            elseExpr = ClojureRT.list(Symbol.intern("throw"),
                    ClojureRT.list(Symbol.intern("new"), Symbol.intern("IllegalArgumentException"),
                            ClojureRT.list(Symbol.intern("str"), "No matching clause: ", tmpSym)));
        }
        // Build nested ifs from last clause to first
        Object result = elseExpr;
        for (int i = clauses.size() - 1; i >= 0; i--) {
            Object[] clause = clauses.get(i);
            Object testVal = clause[0];
            boolean isArrow = (Boolean) clause[1];
            Object resultOrFn = clause[2];
            if (isArrow) {
                // (let [pr (pred testVal tmpSym)] (if pr (fn pr) <else>))
                result = ClojureRT.list(Symbol.intern("let"),
                        PersistentVector.create(java.util.List.of(tmpPredResult, ClojureRT.list(pred, testVal, tmpSym))),
                        ClojureRT.list(Symbol.intern("if"), tmpPredResult,
                                ClojureRT.list(resultOrFn, tmpPredResult),
                                result));
            } else {
                // (if (pred testVal tmpSym) result <else>)
                result = ClojureRT.list(Symbol.intern("if"),
                        ClojureRT.list(pred, testVal, tmpSym),
                        resultOrFn,
                        result);
            }
        }
        // Wrap in let to evaluate expr once
        return analyze(ClojureRT.list(Symbol.intern("let"),
                PersistentVector.create(java.util.Arrays.asList(tmpSym, expr)),
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
        whenBody.add(ClojureRT.list(Symbol.intern("recur")));
        return analyze(ClojureRT.list(Symbol.intern("loop"),
                PersistentVector.EMPTY,
                PersistentList.create(whenBody)));
    }

    private ExpressionNode analyzeDeclare(ISeq seq) {
        // (declare name1 name2 ...) - forward declarations
        // Only declares vars that don't already have a value (don't overwrite existing defs)
        ISeq args = seq.next();
        List<String> names = new ArrayList<>();
        while (args != null) {
            Symbol sym = (Symbol) args.first();
            names.add(sym.getName());
            args = args.next();
        }
        if (names.isEmpty()) return new NilNode();
        final List<String> capturedNames = names;
        return new ExpressionNode() {
            @Override
            public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                return declareVars(context, capturedNames);
            }
        };
    }

    private ExpressionNode analyzeDelay(ISeq seq) {
        // (delay body) => (delay (fn [] body))
        ISeq args = seq.next();
        List<Object> body = new ArrayList<>();
        while (args != null) { body.add(args.first()); args = args.next(); }
        List<Object> doForm = new ArrayList<>();
        doForm.add(Symbol.intern("do"));
        doForm.addAll(body);
        Object fnForm = ClojureRT.list(Symbol.intern("fn"), PersistentVector.EMPTY, PersistentList.create(doForm));
        // Call the builtin delay function directly using core-qualified name
        ExpressionNode fnNode = analyze(fnForm);
        return new InvokeNode(new SymbolNode(context, "clojure.core/delay"), new ExpressionNode[]{fnNode});
    }

    private ExpressionNode analyzeExtendType(ISeq seq) {
        // (extend-type Type Protocol (method [this args] body) ...)
        ISeq args = seq.next();
        if (args == null) throw err("extend-type: missing type");
        Object typeForm = args.first();
        args = args.next();

        // For deftype types (symbols that aren't Java classes), use the type name as string key
        ExpressionNode typeNode = resolveTypeNode(typeForm);

        List<ExpressionNode> nodes = new ArrayList<>();
        while (args != null) {
            // Protocol name
            Object protoForm = args.first();
            args = args.next();
            // Collect methods until next symbol (protocol) or end
            java.util.Map<String, Object> methodForms = new java.util.LinkedHashMap<>();
            while (args != null && isMethodDef(args.first())) {
                ISeq methodDef = (ISeq) args.first();
                String methodName = ((Symbol) methodDef.first()).getName();
                methodForms.put(methodName, buildMethodFn(methodDef));
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
            while (args != null && isMethodDef(args.first())) {
                ISeq methodDef = (ISeq) args.first();
                String methodName = ((Symbol) methodDef.first()).getName();
                methodForms.put(methodName, buildMethodFn(methodDef));
                args = args.next();
            }
            // Resolve type: Java class, nil, or deftype name string
            ExpressionNode typeNode = resolveTypeNode(typeForm);
            nodes.add(new ExtendTypeNode(context, typeNode, analyze(protoForm),
                    methodForms, this));
        }
        if (nodes.isEmpty()) return new NilNode();
        if (nodes.size() == 1) return nodes.get(0);
        return new DoNode(nodes.toArray(new ExpressionNode[0]));
    }

    /**
     * Check if form is a method definition:
     *   Single arity: (methodName [params...] body...)  — second is vector
     *   Multi arity:  (methodName ([p1] body1) ([p2 p3] body2)) — second is list starting with vector
     * Distinguishes from type expressions like (Class/forName "[B") where second is a string.
     */
    private boolean isMethodDef(Object form) {
        if (!(form instanceof ISeq seq)) return false;
        if (!(seq.first() instanceof Symbol)) return false;
        Object second = seq.next() != null ? seq.next().first() : null;
        if (second instanceof IPersistentVector) return true;
        // Multi-arity: second element is a list whose first element is a vector
        if (second instanceof ISeq innerSeq) {
            return innerSeq.first() instanceof IPersistentVector;
        }
        return false;
    }

    /**
     * Build a fn form from a method definition.
     * Single arity: (methodName [params] body) -> (fn [params] body)
     * Multi arity: (methodName ([p1] b1) ([p2 p3] b2)) -> (fn ([p1] b1) ([p2 p3] b2))
     */
    private Object buildMethodFn(ISeq methodDef) {
        List<Object> fnParts = new ArrayList<>();
        fnParts.add(Symbol.intern("fn*"));
        ISeq rest = methodDef.next();
        while (rest != null) { fnParts.add(rest.first()); rest = rest.next(); }
        return PersistentList.create(fnParts);
    }

    private ExpressionNode resolveTypeNode(Object typeForm) {
        if (typeForm == null) {
            // nil literal maps to Void.class for protocol dispatch
            return new QuoteNode(Void.class);
        }
        if (typeForm instanceof Symbol typeSym) {
            String name = typeSym.getName();
            if ("nil".equals(name)) {
                return new QuoteNode(Void.class);
            }
            // Check namespace imports and vars for Class objects (e.g. Type -> clojure.asm.Type)
            if (!name.contains(".") && context != null) {
                // Check imports map
                ClojureNamespace ns = context.getNamespace(context.getCurrentNamespace());
                if (ns != null) {
                    Class<?> imported = ns.getImports().get(name);
                    if (imported != null) {
                        return new QuoteNode(imported);
                    }
                }
                // Check if var holds a Class (import stores classes as vars)
                Object varVal = context.getVar(name);
                if (varVal instanceof Class<?> clazz) {
                    return new QuoteNode(clazz);
                }
            }
            try {
                Class<?> clazz = JavaInteropUtil.resolveClass(name);
                return new QuoteNode(clazz);
            } catch (RuntimeException e) {
                // Not a Java class - use type name string (for deftype types)
                return new QuoteNode(name);
            }
        } else {
            return analyze(typeForm);
        }
    }

    private Class<?> loadClass(String fqn) throws ClassNotFoundException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? Class.forName(fqn, true, cl) : Class.forName(fqn);
    }

    private boolean tryImportDeftype(String fqn) {
        // For FQN like "clojure.tools.reader.reader_types.SourceLoggingPushbackReader",
        // check if this is a deftype from a Clojure namespace.
        // The namespace uses hyphens but the class FQN uses underscores.
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot > 0) {
            String nsPart = fqn.substring(0, lastDot).replace('_', '-');
            String typeName = fqn.substring(lastDot + 1);
            ClojureNamespace ns = context.getNamespace(nsPart);
            if (ns != null) {
                // Look up the deftype constructor or type name var in that namespace
                Object typeVar = context.getVar(nsPart + "/" + typeName);
                if (typeVar == null) typeVar = context.getVar(typeName);
                if (typeVar != null) {
                    context.setVar(typeName, typeVar);
                    return true;
                }
            }
        }
        return false;
    }

    private void processImportSpec(Object spec) {
        if (spec instanceof Symbol sym) {
            // (import java.util.ArrayList) - import single class
            String fqn = sym.getName();
            try {
                Class<?> clazz = loadClass(fqn);
                String simpleName = clazz.getSimpleName();
                context.setVar(simpleName, clazz);
            } catch (ClassNotFoundException e) {
                if (!tryImportDeftype(fqn)) {
                    throw new RuntimeException("import: class not found: " + fqn);
                }
            }
        } else if (spec instanceof IPersistentVector v) {
            // [java.util ArrayList HashMap] - package prefix form
            if (v.count() < 2) return;
            String pkg = ((Symbol) v.nth(0)).getName();
            for (int i = 1; i < v.count(); i++) {
                String className = ((Symbol) v.nth(i)).getName();
                String fqn = pkg + "." + className;
                try {
                    Class<?> clazz = loadClass(fqn);
                    context.setVar(className, clazz);
                } catch (ClassNotFoundException e) {
                    if (!tryImportDeftype(fqn)) {
                        throw new RuntimeException("import: class not found: " + fqn);
                    }
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
                        Class<?> clazz = loadClass(fqn);
                        context.setVar(className, clazz);
                    } catch (ClassNotFoundException e) {
                        if (!tryImportDeftype(fqn)) {
                            throw new RuntimeException("import: class not found: " + fqn);
                        }
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
            finallyBody.add(ClojureRT.list(Symbol.intern(".close"), sym));
        }
        // Build: (let [bindings] (try body... (finally close-forms...)))
        List<Object> tryForm = new ArrayList<>();
        tryForm.add(Symbol.intern("try"));
        tryForm.addAll(body);
        List<Object> finallyForm = new ArrayList<>();
        finallyForm.add(Symbol.intern("finally"));
        finallyForm.addAll(finallyBody);
        tryForm.add(PersistentList.create(finallyForm));

        return analyze(ClojureRT.list(Symbol.intern("let"), bindings, PersistentList.create(tryForm)));
    }

    /**
     * Recursively substitute go-block channel ops with blocking versions:
     * <! → <!!, >! → >!!, alt! → alt!!, alts! → alts!!
     */
    private Object goSubstitute(Object form) {
        if (form instanceof Symbol sym) {
            String n = sym.getName();
            if ("<!" .equals(n)) return Symbol.intern(sym.getNamespace(), "<!!");
            if (">!" .equals(n)) return Symbol.intern(sym.getNamespace(), ">!!");
            if ("alt!".equals(n)) return Symbol.intern(sym.getNamespace(), "alt!!");
            if ("alts!".equals(n)) return Symbol.intern(sym.getNamespace(), "alts!!");
            return sym;
        }
        if (form instanceof ISeq seq2) {
            List<Object> result = new ArrayList<>();
            for (ISeq s = seq2; s != null; s = s.next()) {
                result.add(goSubstitute(s.first()));
            }
            return PersistentList.create(result);
        }
        if (form instanceof IPersistentVector v) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < v.count(); i++) {
                result.add(goSubstitute(v.nth(i)));
            }
            return clojure.lang.PersistentVector.create(result);
        }
        return form;
    }

    private ExpressionNode analyzeWithBindings(ISeq seq) {
        // (with-bindings binding-map & body)
        // For now: evaluate binding-map (ignore), execute body in do block
        ISeq args = seq.next();
        if (args == null) throw err("with-bindings: missing binding map");
        // Skip the binding map (first arg) - evaluate but discard
        ExpressionNode bindingMapExpr = analyze(args.first());
        args = args.next();
        List<Object> doForm = new ArrayList<>();
        doForm.add(Symbol.intern("do"));
        while (args != null) { doForm.add(args.first()); args = args.next(); }
        return analyze(PersistentList.create(doForm));
    }

    private ExpressionNode analyzeWithOutStr(ISeq seq) {
        // (with-out-str body...) => wrap body in fn thunk, invoke with-out-str builtin
        ISeq body = seq.next();
        Object fnForm = ClojureRT.cons(Symbol.intern("fn"),
                ClojureRT.cons(PersistentVector.EMPTY, body));
        ExpressionNode thunkNode = analyze(fnForm);
        ExpressionNode wosNode = new SymbolNode(context, "with-out-str");
        return new InvokeNode(wosNode, new ExpressionNode[]{thunkNode});
    }

    private ExpressionNode analyzeFuture(ISeq seq) {
        // (future body...) => (future-call (fn [] body...))
        ISeq body = seq.next();
        Object fnForm = ClojureRT.cons(Symbol.intern("fn"),
                ClojureRT.cons(PersistentVector.EMPTY, body));
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
                return doLocking(lock, bodyExpr, frame);
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
        // Track which interface/protocol section each method was first defined in
        java.util.Map<String, Object> methodSection = new java.util.HashMap<>();
        Object currentSection = null;

        while (args != null) {
            Object item = args.first();
            if (item instanceof Symbol sym) {
                // Try to resolve as Java interface
                try {
                    Class<?> clazz = JavaInteropUtil.resolveClass(sym.getName());
                    if (clazz.isInterface()) {
                        javaInterfaces.add(clazz);
                        currentSection = clazz;
                    } else {
                        protocolNames.add(sym);
                        currentSection = sym;
                    }
                } catch (RuntimeException e) {
                    Object varVal = context.getVar(sym.getName());
                    if (varVal instanceof Class<?> c && c.isInterface()) {
                        javaInterfaces.add(c);
                        currentSection = c;
                    } else {
                        protocolNames.add(sym);
                        currentSection = sym;
                    }
                }
            } else if (item instanceof ISeq methodDef) {
                String methodName = ((Symbol) methodDef.first()).getName();
                ISeq rest = methodDef.next();
                // Check for duplicate method across different interface sections
                if (currentSection != null && methodSection.containsKey(methodName)
                        && methodSection.get(methodName) != currentSection) {
                    throw err("Can't define method " + methodName + " in both " +
                            methodSection.get(methodName) + " and " + currentSection);
                }
                if (currentSection != null) methodSection.put(methodName, currentSection);
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
            // Check for same-arity type-overloaded methods (e.g., hinted [^int i] vs hinted [^String s])
            boolean hasSameArityOverloads = false;
            if (arities.size() > 1) {
                int firstCount = ((IPersistentVector) arities.get(0).get(0)).count();
                hasSameArityOverloads = arities.stream()
                    .allMatch(a -> ((IPersistentVector) a.get(0)).count() == firstCount);
            }
            if (hasSameArityOverloads) {
                // Create separate type-suffixed entries for each overload
                for (int ai = 0; ai < arities.size(); ai++) {
                    List<Object> arity = arities.get(ai);
                    IPersistentVector params = (IPersistentVector) arity.get(0);
                    StringBuilder typeSuffix = new StringBuilder();
                    for (int j = 1; j < params.count(); j++) {
                        if (params.nth(j) instanceof Symbol ps && ps.meta() != null) {
                            Object tag = ps.meta().valAt(Keyword.intern("tag"));
                            if (tag != null) typeSuffix.append("__").append(tag);
                        }
                    }
                    List<Object> fnForm = new ArrayList<>();
                    fnForm.add(Symbol.intern("fn*"));
                    fnForm.addAll(arity);
                    if (typeSuffix.length() > 0) {
                        methods.put(mName + typeSuffix, PersistentList.create(fnForm));
                    }
                    if (ai == 0) {
                        // Also store plain name pointing to first overload as fallback
                        methods.put(mName, PersistentList.create(fnForm));
                    }
                }
            } else {
                List<Object> fnForm = new ArrayList<>();
                fnForm.add(Symbol.intern("fn*"));
                if (arities.size() == 1) {
                    fnForm.addAll(arities.get(0));
                } else {
                    for (List<Object> arity : arities) {
                        fnForm.add(PersistentList.create(arity));
                    }
                }
                methods.put(mName, PersistentList.create(fnForm));
            }
        }

        List<ExpressionNode> methodNodeList = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        for (var entry : methods.entrySet()) {
            methodNames.add(entry.getKey());
            methodNodeList.add(analyze(entry.getValue()));
        }

        // Validate methods against Java interfaces
        if (!javaInterfaces.isEmpty()) {
            // Collect all valid method names from interfaces
            java.util.Set<String> validMethodNames = new java.util.HashSet<>();
            for (Class<?> iface : javaInterfaces) {
                for (java.lang.reflect.Method m : iface.getMethods()) {
                    validMethodNames.add(m.getName());
                }
            }
            // Check for methods not declared on any interface
            for (String mName : methodNames) {
                // Skip Object methods (toString, hashCode, equals)
                if (mName.equals("toString") || mName.equals("hashCode") || mName.equals("equals")) continue;
                // Convert hyphens to underscores for matching
                String javaName = mName.replace('-', '_');
                if (!validMethodNames.contains(javaName) && !validMethodNames.contains(mName)) {
                    throw err("Can't define method not in interfaces: " + mName);
                }
            }
            // Check for duplicate methods across interfaces
            java.util.Map<String, Class<?>> methodOwner = new java.util.HashMap<>();
            for (Class<?> iface : javaInterfaces) {
                for (java.lang.reflect.Method m : iface.getDeclaredMethods()) {
                    String mName = m.getName();
                    if (methodOwner.containsKey(mName) && methodOwner.get(mName) != iface) {
                        // Method declared in multiple interfaces — check if both are provided
                        // Track which interface section each method was defined under
                    }
                }
            }
            return new ProxyNode(context,
                    javaInterfaces.toArray(new Class<?>[0]),
                    methodNames.toArray(new String[0]),
                    methodNodeList.toArray(new ExpressionNode[0]));
        }

        // Validate methods against definterface protocols (overloaded methods need type hints)
        for (Symbol protoSym : protocolNames) {
            String pName = protoSym.getName();
            // Resolve potentially namespace-qualified protocol
            Object protoObj = null;
            if (pName.contains("/")) {
                String[] parts = pName.split("/", 2);
                var ns = context.getNamespace(parts[0]);
                if (ns != null) protoObj = ns.resolve(parts[1]);
            } else if (pName.contains(".")) {
                // Dot-notation: e.g., clojure.test_clojure.protocols.examples.ExampleInterface
                int lastDot = pName.lastIndexOf('.');
                String nsName = pName.substring(0, lastDot).replace('_', '-');
                String varName = pName.substring(lastDot + 1);
                var ns = context.getNamespace(nsName);
                if (ns != null) protoObj = ns.resolve(varName);
            } else {
                protoObj = context.getVar(pName);
            }
            if (protoObj instanceof ClojureProtocol proto && proto.isFromInterface()) {
                var overloads = proto.getMethodArityCount();
                if (overloads != null) {
                    for (var entry : overloads.entrySet()) {
                        String mName = entry.getKey();
                        int expectedCount = entry.getValue();
                        // Count how many type-hinted implementations were provided for this method
                        long typedCount = methodNames.stream()
                            .filter(n -> n.startsWith(mName + "__"))
                            .count();
                        // If no typed variants but method is overloaded, it's an error
                        if (typedCount == 0 && methodArities.containsKey(mName)) {
                            throw err("Must hint overloaded method: " + mName);
                        }
                    }
                }
            }
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
        List<String> unresolvedInterfaces = new ArrayList<>();
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
                    unresolvedInterfaces.add(name);
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

        // Try to resolve unresolved interfaces as ClojureProtocols (from defprotocol/definterface)
        List<Symbol> protocolsForProxy = new ArrayList<>();
        List<String> stillUnresolved = new ArrayList<>();
        for (String uName : unresolvedInterfaces) {
            // Try dot-notation: ns.name.Var
            Object protoObj = null;
            int lastDot = uName.lastIndexOf('.');
            if (lastDot > 0) {
                String nsName = uName.substring(0, lastDot).replace('_', '-');
                String varName = uName.substring(lastDot + 1);
                var ns = context.getNamespace(nsName);
                if (ns != null) protoObj = ns.resolve(varName);
            }
            if (protoObj == null) protoObj = context.getVar(uName);
            if (protoObj instanceof ClojureProtocol) {
                // Protocol can be handled via ReifyNode approach
                protocolsForProxy.add(Symbol.intern(uName));
            } else {
                stillUnresolved.add(uName);
            }
        }
        if (!stillUnresolved.isEmpty()) {
            String msg = "proxy: cannot resolve interface: " + String.join(", ", stillUnresolved);
            return new ExpressionNode() {
                @Override
                public Object executeGeneric(com.oracle.truffle.api.frame.VirtualFrame frame) {
                    throw new RuntimeException(msg);
                }
            };
        }
        // If all "interfaces" are protocols and no real Java interfaces, use ReifyNode
        // Proxy methods don't include 'this' param, but ReifyNode dispatch prepends 'this'
        // Re-build fn forms with 'this' injected as the first parameter
        if (interfaces.isEmpty() && !protocolsForProxy.isEmpty()) {
            // Re-parse methods with 'this' added to params
            List<ExpressionNode> reifyMethodNodes = new ArrayList<>();
            ISeq reArgs = seq.next();
            if (reArgs != null) reArgs = reArgs.next(); // skip interface vec
            if (reArgs != null && reArgs.first() instanceof IPersistentVector) reArgs = reArgs.next(); // skip ctor args
            List<String> reifyMethodNames = new ArrayList<>();
            while (reArgs != null) {
                Object item = reArgs.first();
                if (item instanceof ISeq methodDef) {
                    String mName = ((Symbol) methodDef.first()).getName();
                    ISeq rest = methodDef.next();
                    List<Object> fnForm = new ArrayList<>();
                    fnForm.add(Symbol.intern("fn*"));
                    // Inject 'this' as first param in each arity
                    while (rest != null) {
                        Object formItem = rest.first();
                        if (formItem instanceof IPersistentVector params) {
                            // Add 'this' as first param: [args...] -> [this_ args...]
                            List<Object> newParams = new ArrayList<>();
                            newParams.add(Symbol.intern("this__proxy"));
                            for (int pi = 0; pi < params.count(); pi++) newParams.add(params.nth(pi));
                            fnForm.add(PersistentVector.create(newParams));
                        } else {
                            fnForm.add(formItem);
                        }
                        rest = rest.next();
                    }
                    reifyMethodNames.add(mName);
                    reifyMethodNodes.add(analyze(PersistentList.create(fnForm)));
                }
                reArgs = reArgs.next();
            }
            return new clojure.truffle.nodes.ReifyNode(context,
                    reifyMethodNames.toArray(new String[0]),
                    reifyMethodNodes.toArray(new ExpressionNode[0]));
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

    private void emitCaseWarnings(java.util.Set<Object> testConstants) {
        if (context == null) return;
        // Check if all test constants are integers
        boolean allInts = !testConstants.isEmpty();
        for (Object c : testConstants) {
            if (!(c instanceof Integer || c instanceof Long)) {
                allInts = false;
                break;
            }
        }
        if (allInts) {
            printCaseWarning("case has int tests, but tested expression is not primitive.");
        }
        // Check for hash collisions
        java.util.Set<Integer> hashes = new java.util.HashSet<>();
        boolean hasCollision = false;
        for (Object c : testConstants) {
            if (c != null && !hashes.add(c.hashCode())) {
                hasCollision = true;
                break;
            }
        }
        if (hasCollision) {
            printCaseWarning("hash collision of some case test constants; if selected, those entries will be tested sequentially.");
        }
    }

    private void printCaseWarning(String msg) {
        try {
            Object errWriter = context.getVarWithBindings("*err*");
            if (errWriter instanceof java.io.Writer w) {
                w.write("Performance warning, NO_SOURCE_PATH:0 - " + msg + "\n");
                w.flush();
            } else if (errWriter instanceof java.io.PrintWriter pw) {
                pw.println("Performance warning, NO_SOURCE_PATH:0 - " + msg);
                pw.flush();
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private static RuntimeException compilerError(String msg) {
        return new clojure.lang.Compiler.CompilerException((String)null, 0, 0, new RuntimeException(msg));
    }

    private static RuntimeException specError(String macro, String detail) {
        return new clojure.lang.ExceptionInfo(
                "Call to clojure.core/" + macro + " did not conform to spec",
                clojure.lang.PersistentArrayMap.EMPTY);
    }

    private static RuntimeException fnSpecError(String detail) {
        return new clojure.lang.ExceptionInfo(
                "Call to clojure.core/fn did not conform to spec",
                clojure.lang.PersistentArrayMap.EMPTY);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object accessInstanceField(Object obj, String fieldName) {
        if (obj instanceof ClojureDeftypeInstance inst) {
            return inst.getField(fieldName);
        }
        try {
            java.lang.reflect.Field f = obj.getClass().getField(fieldName);
            return clojure.truffle.nodes.interop.JavaInteropUtil.wrapResult(f.get(obj));
        } catch (Exception e) {
            throw new RuntimeException("No field '" + fieldName + "' on " + obj.getClass().getName());
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object setStaticField(String className, String fieldName, Object value) {
        try {
            Class<?> clazz = clojure.truffle.nodes.interop.JavaInteropUtil.resolveClass(className);
            java.lang.reflect.Field field = clazz.getField(fieldName);
            field.setAccessible(true);
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

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object registerDefmethod(ClojureContext context, String mmName, Object dispatchVal, Object methodFn) {
        Object mm = context.getVar(mmName);
        if (!(mm instanceof ClojureMultiMethod multi))
            throw new RuntimeException("defmethod: " + mmName + " is not a multimethod");
        multi.addMethod(dispatchVal, methodFn);
        return multi;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object registerDefprotocol(ClojureContext context, String protoName, List<String> methodNames) {
        return registerDefprotocol(context, protoName, methodNames, null);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object registerDefprotocol(ClojureContext context, String protoName, List<String> methodNames,
                                      java.util.Map<String, Object[]> methodMeta) {
        ClojureProtocol proto = new ClojureProtocol(protoName, methodNames);
        // Set per-method arity counts for getMethods() duplicate entries
        if (methodMeta != null) {
            java.util.Map<String, Integer> arityCount = new java.util.HashMap<>();
            for (var entry : methodMeta.entrySet()) {
                Object arglists = entry.getValue()[0];
                int count = 0;
                if (arglists instanceof clojure.lang.ISeq s) {
                    for (; s != null; s = s.next()) count++;
                }
                if (count > 1) arityCount.put(entry.getKey(), count);
            }
            if (!arityCount.isEmpty()) proto.setMethodArityCount(arityCount);
        }
        context.setVar(protoName, proto);
        String currentNsName = context.getCurrentNamespace();
        String nsQualifiedProto = currentNsName != null ? currentNsName + "/" + protoName : protoName;
        for (String methodName : methodNames) {
            context.setVar(methodName, new ClojureContext.NamedBuiltin(
                    protoName + "/" + methodName, fnArgs -> {
                if (fnArgs.length < 1)
                    throw new RuntimeException(protoName + "/" + methodName + ": missing target (this)");
                Object target = fnArgs[0];
                // Resolve the current protocol via var (supports protocol redefinition)
                Object currentProtoObj = context.getVar(protoName);
                ClojureProtocol currentProto = (currentProtoObj instanceof ClojureProtocol cp) ? cp : proto;
                // Check if the method still exists in the protocol (may have been redefined without it)
                if (!currentProto.getMethodNames().contains(methodName)) {
                    String qualName = nsQualifiedProto.replace('/', '.').replace('-', '_');
                    throw new IllegalArgumentException(
                            "No method of interface: " + qualName +
                            " found for function: " + methodName +
                            " of protocol: " + protoName +
                            " (The protocol method may have been defined before and removed.)");
                }
                Object fn = currentProto.findMethod(methodName, target);
                if (fn == null) {
                    String targetType = target instanceof clojure.truffle.runtime.ClojureReified r
                            ? "ClojureReified{" + String.join(",", r.getMethods().keySet()) + "}"
                            : target.getClass().getName();
                    throw new IllegalArgumentException(
                            "No implementation of method: :" + methodName +
                            " of protocol: #'" + nsQualifiedProto +
                            " found for class: " + targetType);
                }
                return context.callFunction(fn, fnArgs);
            }));

            // Set metadata on protocol method var
            if (currentNsName != null) {
                String methodQname = currentNsName + "/" + methodName;
                Object[] meta = (methodMeta != null) ? methodMeta.get(methodName) : null;
                Object arglists = (meta != null) ? meta[0] : null;
                String doc = (meta != null) ? (String) meta[1] : null;
                Object tag = (meta != null && meta.length > 2) ? meta[2] : null;
                // Resolve :tag symbol (e.g., String -> java.lang.String)
                if (tag instanceof clojure.lang.Symbol tagSym && tagSym.getNamespace() == null) {
                    try {
                        Class<?> cls = Class.forName("java.lang." + tagSym.getName());
                        tag = clojure.lang.Symbol.intern(cls.getName());
                    } catch (ClassNotFoundException ignored) {}
                }
                // :protocol should be the var, :ns should be namespace object
                Object protoVar = context.getOrCreateVar(nsQualifiedProto);
                Object nsObj = context.getOrCreateNamespace(currentNsName);
                // Build metadata map, normalizing null → ClojureNil for Truffle consistency
                clojure.lang.IPersistentMap metaMap = clojure.lang.PersistentArrayMap.EMPTY;
                metaMap = metaMap.assoc(clojure.lang.Keyword.intern("protocol"), protoVar);
                metaMap = metaMap.assoc(clojure.lang.Keyword.intern("ns"), nsObj);
                metaMap = metaMap.assoc(clojure.lang.Keyword.intern("name"),
                    clojure.lang.Symbol.intern(methodName));
                metaMap = metaMap.assoc(clojure.lang.Keyword.intern("arglists"),
                    arglists != null ? arglists : ClojureNil.INSTANCE);
                metaMap = metaMap.assoc(clojure.lang.Keyword.intern("doc"),
                    doc != null ? doc : ClojureNil.INSTANCE);
                metaMap = metaMap.assoc(clojure.lang.Keyword.intern("tag"),
                    tag != null ? tag : ClojureNil.INSTANCE);
                context.setVarMeta(methodQname, metaMap);
            }
        }
        return proto;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object registerDeftype(ClojureContext context, String typeName, List<String> fieldNames,
                                  java.util.Map<String, java.util.Map<String, Object>> evaluatedMethods,
                                  boolean isRecord) {
        return registerDeftype(context, typeName, fieldNames, evaluatedMethods, isRecord, null);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object registerDeftype(ClojureContext context, String typeName, List<String> fieldNames,
                                  java.util.Map<String, java.util.Map<String, Object>> evaluatedMethods,
                                  boolean isRecord, List<Symbol> fieldSymbols) {
        java.util.Map<String, Integer> fieldIdx = new java.util.LinkedHashMap<>();
        for (int i = 0; i < fieldNames.size(); i++)
            fieldIdx.put(fieldNames.get(i), i);

        // Extract field type hints for validation (e.g., ^long, ^double)
        Class<?>[] fieldTypeHints = null;
        if (fieldSymbols != null) {
            boolean hasHints = false;
            fieldTypeHints = new Class<?>[fieldNames.size()];
            for (int i = 0; i < fieldSymbols.size() && i < fieldNames.size(); i++) {
                Symbol fs = fieldSymbols.get(i);
                if (fs.meta() != null) {
                    Object tag = fs.meta().valAt(clojure.lang.Keyword.intern("tag"));
                    if (tag instanceof Symbol tagSym) {
                        Class<?> hintClass = resolvePrimitiveHint(tagSym.getName());
                        if (hintClass != null) {
                            fieldTypeHints[i] = hintClass;
                            hasHints = true;
                        }
                    } else if (tag instanceof String tagStr) {
                        Class<?> hintClass = resolvePrimitiveHint(tagStr);
                        if (hintClass != null) {
                            fieldTypeHints[i] = hintClass;
                            hasHints = true;
                        }
                    }
                }
            }
            if (!hasHints) fieldTypeHints = null;
        }
        final Class<?>[] finalFieldTypeHints = fieldTypeHints;

        java.util.Map<String, Object> typeMethods = new java.util.HashMap<>();

        for (var entry : evaluatedMethods.entrySet()) {
            String protoName = entry.getKey();
            Object protoObj = context.getVar(protoName);
            if (protoObj instanceof ClojureProtocol proto) {
                proto.extend(typeName, new java.util.HashMap<>(entry.getValue()));
                proto.addInlineImplementor(typeName);
            } else {
                for (var methodEntry : entry.getValue().entrySet()) {
                    Object fn = methodEntry.getValue();
                    String mName = methodEntry.getKey();
                    Object existing = typeMethods.get(mName);
                    if (existing != null && fn instanceof ClojureFunction newCf) {
                        if (existing instanceof MultiArityFunction maf) {
                            int newArity = newCf.getArity();
                            int[] oldArities = maf.getArities();
                            ClojureFunction[] oldFns = maf.getFunctions();
                            int[] newArities = new int[oldArities.length + 1];
                            ClojureFunction[] newFns = new ClojureFunction[oldFns.length + 1];
                            System.arraycopy(oldArities, 0, newArities, 0, oldArities.length);
                            System.arraycopy(oldFns, 0, newFns, 0, oldFns.length);
                            newArities[oldArities.length] = newArity;
                            newFns[oldFns.length] = newCf;
                            MultiArityFunction merged = new MultiArityFunction(newArities, newFns,
                                    newCf.isVariadic() ? oldArities.length : maf.getVariadicIndex());
                            context.registerTypeMethod(typeName, mName, merged);
                            typeMethods.put(mName, merged);
                        } else if (existing instanceof ClojureFunction existCf) {
                            int[] mergedArities = {existCf.getArity(), newCf.getArity()};
                            ClojureFunction[] mergedFns = {existCf, newCf};
                            int varIdx = existCf.isVariadic() ? 0 : (newCf.isVariadic() ? 1 : -1);
                            MultiArityFunction merged = new MultiArityFunction(mergedArities, mergedFns, varIdx);
                            context.registerTypeMethod(typeName, mName, merged);
                            typeMethods.put(mName, merged);
                        } else {
                            context.registerTypeMethod(typeName, mName, fn);
                            typeMethods.put(mName, fn);
                        }
                    } else {
                        context.registerTypeMethod(typeName, mName, fn);
                        typeMethods.put(mName, fn);
                    }
                }
            }
        }

        java.util.Map<String, Object> capturedTypeMethods =
                typeMethods.isEmpty() ? null : new java.util.HashMap<>(typeMethods);

        // Compute namespace-qualified type name for printing (e.g., clojure.test_clojure.protocols.Foo)
        String currentNsName = context.getCurrentNamespace();
        String qualifiedName = currentNsName != null
            ? currentNsName.replace('-', '_') + "." + typeName
            : typeName;

        context.setVar("->" + typeName, (ClojureContext.BuiltinFunction) ctorArgs -> {
            if (isRecord && ctorArgs.length == fieldNames.size() + 2) {
                // Extended constructor: (RecordType. f1 f2 ... meta extras)
                Object[] fieldValues = new Object[fieldNames.size()];
                System.arraycopy(ctorArgs, 0, fieldValues, 0, fieldNames.size());
                Object metaArg = ctorArgs[fieldNames.size()];
                Object extrasArg = ctorArgs[fieldNames.size() + 1];
                clojure.lang.IPersistentMap extrasMap = (extrasArg instanceof clojure.lang.IPersistentMap em) ? em
                    : clojure.lang.PersistentArrayMap.EMPTY;
                ClojureDeftypeInstance inst = new ClojureDeftypeInstance(typeName, fieldValues, fieldIdx, extrasMap);
                inst.setQualifiedTypeName(qualifiedName);
                inst.setRecord(true);
                if (metaArg instanceof clojure.lang.IPersistentMap mm) {
                    inst = (ClojureDeftypeInstance) inst.withMeta(mm);
                }
                if (capturedTypeMethods != null) inst.setMethods(capturedTypeMethods);
                return inst;
            }
            if (ctorArgs.length != fieldNames.size())
                throw new clojure.lang.ArityException(ctorArgs.length, "->" + typeName);
            if (finalFieldTypeHints != null)
                validateFieldTypes(ctorArgs, finalFieldTypeHints, fieldNames);
            ClojureDeftypeInstance inst = new ClojureDeftypeInstance(typeName, ctorArgs.clone(), fieldIdx);
            inst.setQualifiedTypeName(qualifiedName);
            if (isRecord) inst.setRecord(true);
            if (capturedTypeMethods != null) inst.setMethods(capturedTypeMethods);
            return inst;
        });

        // Set :doc metadata on ->TypeName factory var
        String ctorQname = currentNsName + "/->" + typeName;
        context.setVarMeta(ctorQname, clojure.lang.RT.map(
            clojure.lang.Keyword.intern("doc"),
            "Positional factory function for class " + qualifiedName + "."));

        context.setVar(typeName, typeName);

        // Register basis (field name symbols with metadata) for TypeName/getBasis support
        clojure.lang.IPersistentVector basis = clojure.lang.PersistentVector.EMPTY;
        for (int fi = 0; fi < fieldNames.size(); fi++) {
            Symbol sym;
            if (fieldSymbols != null && fi < fieldSymbols.size()) {
                sym = fieldSymbols.get(fi);
            } else {
                sym = clojure.lang.Symbol.intern(fieldNames.get(fi));
            }
            basis = basis.cons(sym);
        }
        context.setVar("__basis__" + typeName, basis);

        if (isRecord) {
            context.setVar("map->" + typeName, (ClojureContext.BuiltinFunction) ctorArgs -> {
                if (ctorArgs.length != 1)
                    throw new clojure.lang.ArityException(ctorArgs.length, "map->" + typeName);
                Object mapArg = ctorArgs[0];
                // Convert java.util.Map to IPersistentMap if needed
                clojure.lang.IPersistentMap m;
                if (mapArg instanceof clojure.lang.IPersistentMap pm) {
                    m = pm;
                } else if (mapArg instanceof java.util.Map jm) {
                    m = clojure.lang.PersistentHashMap.create(jm);
                } else {
                    throw new RuntimeException("map->" + typeName + ": arg must be a map");
                }
                Object[] fieldValues = new Object[fieldNames.size()];
                // Collect extras (keys not in fieldNames)
                java.util.Set<clojure.lang.Keyword> fieldKeywords = new java.util.HashSet<>();
                for (int i = 0; i < fieldNames.size(); i++) {
                    clojure.lang.Keyword kw = clojure.lang.Keyword.intern(fieldNames.get(i));
                    fieldKeywords.add(kw);
                    fieldValues[i] = m.valAt(kw);
                }
                if (finalFieldTypeHints != null)
                    validateFieldTypes(fieldValues, finalFieldTypeHints, fieldNames);
                // Build extras map from remaining keys
                clojure.lang.IPersistentMap extras = clojure.lang.PersistentArrayMap.EMPTY;
                for (clojure.lang.ISeq s = m.seq(); s != null; s = s.next()) {
                    clojure.lang.IMapEntry me = (clojure.lang.IMapEntry) s.first();
                    if (!fieldKeywords.contains(me.key())) {
                        extras = extras.assoc(me.key(), me.val());
                    }
                }
                ClojureDeftypeInstance inst = new ClojureDeftypeInstance(typeName, fieldValues, fieldIdx, extras);
                inst.setQualifiedTypeName(qualifiedName);
                inst.setRecord(true);
                if (capturedTypeMethods != null) inst.setMethods(capturedTypeMethods);
                return inst;
            });

            // Set :doc metadata on map->TypeName factory var
            String mapCtorQname = currentNsName + "/map->" + typeName;
            context.setVarMeta(mapCtorQname, clojure.lang.RT.map(
                clojure.lang.Keyword.intern("doc"),
                "Factory function for class " + qualifiedName + ", taking a map of keywords to field values."));
        }

        return ClojureNil.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object referClojureBoundary(ClojureContext context, java.util.Set<String> excludes) {
        ClojureNamespace core = context.getNamespace("clojure.core");
        ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
        if (core != null && currentNs != null) {
            currentNs.referWithExclude(core, excludes);
        }
        return ClojureNil.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object evalDeftype(java.util.Map<String, java.util.Map<String, ExpressionNode>> protoMethods,
                              Object frameObj, ClojureContext context, String typeName,
                              List<String> fieldNames, boolean isRecord) {
        return evalDeftype(protoMethods, frameObj, context, typeName, fieldNames, isRecord, null);
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object evalDeftype(java.util.Map<String, java.util.Map<String, ExpressionNode>> protoMethods,
                              Object frameObj, ClojureContext context, String typeName,
                              List<String> fieldNames, boolean isRecord, List<Symbol> fieldSymbols) {
        com.oracle.truffle.api.frame.VirtualFrame frame = (com.oracle.truffle.api.frame.VirtualFrame) frameObj;
        java.util.Map<String, java.util.Map<String, Object>> evaluatedMethods = new java.util.LinkedHashMap<>();
        for (var entry : protoMethods.entrySet()) {
            java.util.Map<String, Object> evaluated = new java.util.LinkedHashMap<>();
            for (var methodEntry : entry.getValue().entrySet()) {
                evaluated.put(methodEntry.getKey(), methodEntry.getValue().executeGeneric(frame));
            }
            evaluatedMethods.put(entry.getKey(), evaluated);
        }
        return registerDeftype(context, typeName, fieldNames, evaluatedMethods, isRecord, fieldSymbols);
    }

    /** Resolve primitive type hint name to Class. Returns null if not a primitive hint. */
    private static Class<?> resolvePrimitiveHint(String name) {
        return switch (name) {
            case "long" -> Long.class;
            case "double" -> Double.class;
            case "int" -> Integer.class;
            case "float" -> Float.class;
            case "short" -> Short.class;
            case "byte" -> Byte.class;
            case "boolean" -> Boolean.class;
            case "char" -> Character.class;
            default -> null;
        };
    }

    /** Validate field values against type hints. Throws ClassCastException for mismatches. */
    private static void validateFieldTypes(Object[] fieldValues, Class<?>[] typeHints, List<String> fieldNames) {
        for (int i = 0; i < fieldValues.length && i < typeHints.length; i++) {
            if (typeHints[i] != null && fieldValues[i] != null) {
                Object val = fieldValues[i];
                if (val instanceof ClojureNil) continue; // nil is acceptable
                Class<?> expected = typeHints[i];
                if (!expected.isInstance(val) && !(val instanceof Number && Number.class.isAssignableFrom(expected))) {
                    throw new ClassCastException(
                        "Cannot cast " + val.getClass().getName() + " to " + expected.getName());
                }
                // For numeric types, also validate specific type match
                if (expected == Long.class && !(val instanceof Long)) {
                    if (val instanceof Number) continue; // numeric coercion OK
                    throw new ClassCastException(
                        "Cannot cast " + val.getClass().getName() + " to " + expected.getName());
                }
            }
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object declareVars(ClojureContext context, List<String> names) {
        for (String name : names) {
            Object existing = context.getVar(name);
            if (existing == null) {
                context.setVar(name, ClojureNil.INSTANCE);
            }
        }
        return ClojureNil.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object convertKwArgs(Object rest, ClojureContext context) {
        if (rest == null || rest instanceof ClojureNil) {
            return clojure.lang.PersistentHashMap.EMPTY;
        }
        // Single map argument (Clojure 1.11+ style)
        clojure.lang.ISeq s = null;
        if (rest instanceof clojure.lang.Seqable sq) s = sq.seq();
        else if (rest instanceof clojure.lang.ISeq is) s = is;
        if (s != null && s.next() == null && s.first() instanceof clojure.lang.IPersistentMap) {
            return s.first();
        }
        // Flat key-value pairs: (apply hash-map rest)
        return context.callFunction(
                context.getVar("apply"),
                new Object[]{context.getVar("hash-map"), rest});
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object doUse(Analyzer analyzer, List<Object> specs) {
        for (Object spec : specs) analyzer.processUseSpec(spec);
        return ClojureNil.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object doLoad(ClojureContext context, List<String> paths, Analyzer self) {
        String currentNs = context.getCurrentNamespace();
        for (String path : paths) {
            String fullPath;
            if (path.startsWith("/")) {
                fullPath = path;
            } else {
                String rootRes = "/" + currentNs.replace('.', '/').replace('-', '_');
                int lastSlash = rootRes.lastIndexOf('/');
                String rootDir = lastSlash > 0 ? rootRes.substring(0, lastSlash) : "";
                fullPath = rootDir + "/" + path;
            }
            String cleanPath = fullPath.startsWith("/") ? fullPath.substring(1) : fullPath;
            String resourcePath = cleanPath + ".clj";
            java.io.InputStream is = context.findResource(resourcePath);
            if (is == null) {
                resourcePath = cleanPath + ".cljc";
                is = context.findResource(resourcePath);
            }
            if (is == null) {
                throw new RuntimeException("Cannot find resource for load: " + path);
            }
            try {
                byte[] bytes = is.readAllBytes();
                is.close();
                String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                Analyzer loader = new Analyzer(context.getLanguage());
                loader.setContext(context);
                loader.loadSource(source, context.getLanguage());
            } catch (java.io.IOException e) {
                throw new RuntimeException("Error loading: " + path, e);
            }
        }
        return ClojureNil.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object doRefer(ClojureContext context, List<Object> capturedArgs) {
        if (capturedArgs.isEmpty()) return ClojureNil.INSTANCE;
        Object nsArg = capturedArgs.get(0);
        if (nsArg instanceof ISeq qs && qs.first() instanceof Symbol s && s.getName().equals("quote")) {
            nsArg = qs.next().first();
        }
        String nsName;
        if (nsArg instanceof Symbol sym) nsName = sym.getName();
        else nsName = nsArg.toString();
        ClojureNamespace reqNs = context.getNamespace(nsName);
        ClojureNamespace currentNs = context.getNamespace(context.getCurrentNamespace());
        if (reqNs == null || currentNs == null) return ClojureNil.INSTANCE;
        // Parse keyword options: :only, :exclude, :rename
        java.util.Map<String, String> renames = null;
        java.util.List<String> onlyNames = null;
        java.util.Set<String> excludeNames = null;
        for (int i = 1; i + 1 < capturedArgs.size(); i += 2) {
            Object filterKey = capturedArgs.get(i);
            if (!(filterKey instanceof Keyword kw)) continue;
            Object valArg = capturedArgs.get(i + 1);
            // Unquote if needed
            if (valArg instanceof ISeq qs2 && qs2.first() instanceof Symbol s2 && s2.getName().equals("quote")) {
                valArg = qs2.next().first();
            }
            switch (kw.getName()) {
                case "only":
                    if (valArg instanceof IPersistentVector pv) {
                        onlyNames = new ArrayList<>();
                        for (int j = 0; j < pv.count(); j++) {
                            onlyNames.add(((Symbol) pv.nth(j)).getName());
                        }
                    }
                    break;
                case "exclude":
                    if (valArg instanceof IPersistentVector pv2) {
                        excludeNames = new java.util.HashSet<>();
                        for (int j = 0; j < pv2.count(); j++) {
                            excludeNames.add(((Symbol) pv2.nth(j)).getName());
                        }
                    }
                    break;
                case "rename":
                    if (valArg instanceof IPersistentMap m) {
                        renames = new java.util.HashMap<>();
                        for (ISeq ms = m.seq(); ms != null; ms = ms.next()) {
                            IMapEntry me = (IMapEntry) ms.first();
                            renames.put(((Symbol) me.key()).getName(), ((Symbol) me.val()).getName());
                        }
                    }
                    break;
            }
        }
        if (onlyNames != null) {
            currentNs.referOnly(reqNs, onlyNames);
        } else if (excludeNames != null) {
            currentNs.referWithExclude(reqNs, excludeNames);
        } else if (renames != null) {
            currentNs.referWithRename(reqNs, renames);
        } else {
            currentNs.referAll(reqNs);
        }
        return ClojureNil.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object doDefmulti(ClojureContext context, String name, Object dispatchFn) {
        return doDefmulti(context, name, dispatchFn, null);
    }

    static Object doDefmulti(ClojureContext context, String name, Object dispatchFn, Object hierarchy) {
        ClojureMultiMethod mm = new ClojureMultiMethod(name, dispatchFn, context);
        if (hierarchy != null) {
            mm.setHierarchy(hierarchy);
        }
        context.setVar(name, mm);
        return mm;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object doDefinterface(ClojureContext context, String ifaceName, List<String> methodNames) {
        // Deduplicate method names for the protocol, but track overload counts
        java.util.Map<String, Integer> overloadCounts = new java.util.LinkedHashMap<>();
        for (String name : methodNames) {
            overloadCounts.merge(name, 1, Integer::sum);
        }
        List<String> uniqueNames = new ArrayList<>(overloadCounts.keySet());
        ClojureProtocol proto = new ClojureProtocol(ifaceName, uniqueNames);
        // Set overload counts for methods with multiple signatures
        java.util.Map<String, Integer> multiOverloads = new java.util.HashMap<>();
        for (var entry : overloadCounts.entrySet()) {
            if (entry.getValue() > 1) multiOverloads.put(entry.getKey(), entry.getValue());
        }
        if (!multiOverloads.isEmpty()) proto.setMethodArityCount(multiOverloads);
        proto.setFromInterface(true);
        context.setVar(ifaceName, proto);
        return proto;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object doNs(ClojureContext context, String nsName, List<Object> requires,
                       List<Object> uses, List<Object> imports,
                       java.util.Set<String> excludes, boolean hasReferClojure, Analyzer self) {
        context.getOrCreateNamespace(nsName);
        context.setCurrentNamespace(nsName);
        ClojureNamespace ns = context.getNamespace(nsName);
        ClojureNamespace core = context.getNamespace("clojure.core");
        if (core != null) {
            if (hasReferClojure && !excludes.isEmpty()) {
                ns.referWithExclude(core, excludes);
            } else {
                ns.referAll(core);
            }
        }
        for (Object spec : requires) self.processRequireSpec(spec);
        for (Object spec : uses) self.processUseSpec(spec);
        for (Object spec : imports) self.processImportSpec(spec);
        return ClojureNil.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object doInNs(ClojureContext context, Object name) {
        String nsName;
        if (name instanceof clojure.lang.Symbol sym) nsName = sym.getName();
        else nsName = name.toString();
        context.getOrCreateNamespace(nsName);
        context.setCurrentNamespace(nsName);
        return ClojureNil.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object doRequire(Analyzer self, List<Object> specs) {
        for (Object spec : specs) self.processRequireSpec(spec);
        return ClojureNil.INSTANCE;
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static Object doLocking(Object lock, ExpressionNode bodyExpr, Object frameObj) {
        com.oracle.truffle.api.frame.VirtualFrame frame = (com.oracle.truffle.api.frame.VirtualFrame) frameObj;
        synchronized (lock) {
            return bodyExpr.executeGeneric(frame);
        }
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    static RuntimeException caseNoMatchError(Object val) {
        return new IllegalArgumentException("No matching clause: " + val);
    }
}
