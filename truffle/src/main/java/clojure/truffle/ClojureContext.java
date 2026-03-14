package clojure.truffle;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import clojure.truffle.nodes.EvalRootNode;
import clojure.truffle.nodes.ExpressionNode;
import clojure.truffle.parser.Analyzer;
import clojure.truffle.runtime.ClojureAtom;
import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.ClojureNil;
import clojure.truffle.runtime.ClojureDeftypeInstance;
import clojure.truffle.runtime.ClojureMultiMethod;
import clojure.truffle.runtime.ClojureNamespace;
import clojure.truffle.runtime.ClojureProtocol;
import clojure.truffle.runtime.ClojurePromise;
import clojure.truffle.runtime.ClojureVolatile;
import clojure.truffle.runtime.LazySeq;
import clojure.truffle.runtime.MultiArityFunction;

import com.oracle.truffle.api.utilities.CyclicAssumption;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClojureContext {

    private final ClojureTruffleLanguage language;
    private final TruffleLanguage.Env env;
    public static final boolean DEBUG = Boolean.getBoolean("clojure.truffle.debug");
    private final ConcurrentHashMap<String, Object> globalVars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> macros = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, clojure.lang.IPersistentMap> varMeta = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClojureNamespace> namespaces = new ConcurrentHashMap<>();
    private volatile String currentNamespace = "user";
    private final java.util.Set<String> loadingNamespaces =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final AtomicLong gensymCounter = new AtomicLong(0);
    private final java.util.Set<String> dynamicVars =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final ThreadLocal<java.util.HashMap<String, Object>> threadBindings =
            ThreadLocal.withInitial(java.util.HashMap::new);
    // Thread-local output writer override for with-out-str
    private final ThreadLocal<java.io.Writer> outOverride = new ThreadLocal<>();
    // Classpath entries for -cp option (JAR files and directories)
    private final java.util.List<String> classpathEntries = new java.util.ArrayList<>();
    // ClassLoader for -cp entries (for Java class resolution)
    private ClassLoader cpClassLoader;
    // Per-type method registry for deftype methods (IFn, IDeref, etc.)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> typeMethodRegistry = new ConcurrentHashMap<>();
    // Assumptions for var stability — invalidated when a var's value changes.
    // SymbolNode uses these to cache resolved values during JIT compilation.
    private final ConcurrentHashMap<String, CyclicAssumption> varAssumptions = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface BuiltinFunction {
        Object execute(Object[] args);
        default String name() { return "<builtin>"; }
    }

    /** BuiltinFunction with a name for stack traces and debugging. Extends AFn for IFn compatibility. */
    public static class NamedBuiltin extends clojure.lang.AFn implements BuiltinFunction {
        private final String name;
        private final BuiltinFunction delegate;
        private volatile clojure.lang.IPersistentMap meta;
        public NamedBuiltin(String name, BuiltinFunction delegate) {
            this.name = name;
            this.delegate = delegate;
        }
        @Override public Object execute(Object[] args) { return delegate.execute(args); }
        @Override public String name() { return name; }
        @Override public String toString() { return "<builtin:" + name + ">"; }
        public clojure.lang.IPersistentMap getMeta() { return meta; }
        public void setMeta(clojure.lang.IPersistentMap meta) { this.meta = meta; }

        // IFn invoke methods delegate to execute()
        @Override public Object invoke() { return execute(new Object[]{}); }
        @Override public Object invoke(Object a1) { return execute(new Object[]{a1}); }
        @Override public Object invoke(Object a1, Object a2) { return execute(new Object[]{a1, a2}); }
        @Override public Object invoke(Object a1, Object a2, Object a3) { return execute(new Object[]{a1, a2, a3}); }
        @Override public Object invoke(Object a1, Object a2, Object a3, Object a4) { return execute(new Object[]{a1, a2, a3, a4}); }
        @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5) { return execute(new Object[]{a1, a2, a3, a4, a5}); }
        @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) { return execute(new Object[]{a1, a2, a3, a4, a5, a6}); }
        @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) { return execute(new Object[]{a1, a2, a3, a4, a5, a6, a7}); }
        @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8) { return execute(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8}); }
        @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9) { return execute(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9}); }
        @Override public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10) { return execute(new Object[]{a1, a2, a3, a4, a5, a6, a7, a8, a9, a10}); }
        @Override public Object applyTo(clojure.lang.ISeq arglist) { return execute(clojure.truffle.runtime.ClojureRT.seqToArray(arglist)); }
    }

    /** Register a builtin function with a name for debugging. */
    private void defBuiltin(String name, BuiltinFunction fn) {
        globalVars.put(name, new NamedBuiltin(name, fn));
    }

    public ClojureContext(ClojureTruffleLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        // Parse classpath from system property
        String cp = System.getProperty("clojure.truffle.classpath");
        if (cp != null && !cp.isEmpty()) {
            for (String entry : cp.split(java.io.File.pathSeparator)) {
                String resolved = entry;
                // Expand glob: "dir/*" -> all JARs in dir
                if (entry.endsWith("/*")) {
                    String dir = entry.substring(0, entry.length() - 2);
                    java.io.File dirFile = new java.io.File(dir);
                    if (dirFile.isDirectory()) {
                        java.io.File[] jars = dirFile.listFiles((d, name) ->
                                name.endsWith(".jar") || name.endsWith(".JAR"));
                        if (jars != null) {
                            for (java.io.File jar : jars) {
                                classpathEntries.add(jar.getAbsolutePath());
                            }
                        }
                    }
                    continue;
                }
                classpathEntries.add(resolved);
            }
        }
        // Build URLClassLoader for -cp entries so Java class resolution works
        if (!classpathEntries.isEmpty()) {
            try {
                java.net.URL[] urls = new java.net.URL[classpathEntries.size()];
                for (int i = 0; i < classpathEntries.size(); i++) {
                    urls[i] = new java.io.File(classpathEntries.get(i)).toURI().toURL();
                }
                cpClassLoader = new java.net.URLClassLoader(urls, getClass().getClassLoader());
                Thread.currentThread().setContextClassLoader(cpClassLoader);
            } catch (java.net.MalformedURLException e) {
                throw new RuntimeException("Invalid classpath entry", e);
            }
        }
        // Create clojure.core and user namespaces
        namespaces.put("clojure.core", new ClojureNamespace("clojure.core"));
        namespaces.put("user", new ClojureNamespace("user"));
        registerBuiltins();
        // Intern all builtins into clojure.core Truffle namespace
        // so that qualified references like clojure.core/seq work
        ClojureNamespace coreNs = namespaces.get("clojure.core");
        for (var entry : globalVars.entrySet()) {
            coreNs.intern(entry.getKey(), entry.getValue());
        }
        // Register all builtins as Vars in Clojure's clojure.core namespace
        // so that LispReader's syntax-quote can resolve them properly
        registerClojureCoreVars();
        // User namespace refers all of clojure.core
        namespaces.get("user").referAll(namespaces.get("clojure.core"));
        // Set up TruffleReader #= read-eval hook so builtins like * are available
        clojure.lang.TruffleReader.readEvalHook = (form) -> {
            Object fnSym = clojure.lang.RT.first(form);
            Object fn = getVar(fnSym.toString());
            if (fn == null) throw new RuntimeException("Can't resolve " + fnSym + " in #= form");
            Object[] args = clojure.lang.TruffleReader.toArray(clojure.lang.RT.next(form));
            return callFunction(fn, args);
        };
    }

    public ClojureTruffleLanguage getLanguage() {
        return language;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    // Type method registry for deftype
    public void registerTypeMethod(String typeName, String methodName, Object fn) {
        typeMethodRegistry.computeIfAbsent(typeName, k -> new ConcurrentHashMap<>()).put(methodName, fn);
    }

    public Object lookupTypeMethod(String typeName, String methodName) {
        var methods = typeMethodRegistry.get(typeName);
        return methods != null ? methods.get(methodName) : null;
    }

    @TruffleBoundary
    public void setVar(String name, Object value) {
        // Only put into globalVars if we're in clojure.core (or no namespace yet)
        // Otherwise, namespace-local defs should NOT pollute the global scope
        if (currentNamespace == null || "clojure.core".equals(currentNamespace) || "user".equals(currentNamespace)) {
            globalVars.put(name, value);
        }
        // Always intern in current namespace
        ClojureNamespace ns = namespaces.get(currentNamespace);
        if (ns != null) ns.intern(name, value);
        // Invalidate assumption so JIT-compiled code re-resolves this var
        invalidateVarAssumption(name);
    }

    /** Get or create a CyclicAssumption for the given var name. */
    public CyclicAssumption getVarAssumption(String name) {
        return varAssumptions.computeIfAbsent(name, k -> new CyclicAssumption("var:" + k));
    }

    /** Invalidate the assumption for a var, causing JIT-compiled code to deoptimize. */
    private void invalidateVarAssumption(String name) {
        CyclicAssumption a = varAssumptions.get(name);
        if (a != null) a.invalidate("var redefined: " + name);
    }

    @TruffleBoundary
    public Object getVar(String name) {
        // Check for qualified name: ns/sym
        int slash = name.indexOf('/');
        if (slash > 0) {
            String nsName = name.substring(0, slash);
            String sym = name.substring(slash + 1);
            // Check aliases first
            ClojureNamespace currentNs = namespaces.get(currentNamespace);
            if (currentNs != null) {
                ClojureNamespace aliased = currentNs.resolveAlias(nsName);
                if (aliased != null) {
                    Object val = aliased.resolve(sym);
                    if (val != null) return val;
                }
            }
            // Check full namespace name
            ClojureNamespace targetNs = namespaces.get(nsName);
            if (targetNs != null) {
                Object val = targetNs.resolve(sym);
                if (val != null) return val;
            }
        }
        // Check current namespace first (includes refers from clojure.core)
        ClojureNamespace ns = namespaces.get(currentNamespace);
        if (ns != null) {
            Object val = ns.resolve(name);
            if (val != null) return val;
        }
        // Fall back to global builtins
        return globalVars.get(name);
    }

    @TruffleBoundary
    public clojure.truffle.runtime.ClojureVar getOrCreateVar(String name) {
        String ns = currentNamespace;
        String sym = name;
        int slash = name.indexOf('/');
        if (slash > 0) {
            ns = name.substring(0, slash);
            sym = name.substring(slash + 1);
        }
        return new clojure.truffle.runtime.ClojureVar(this, ns, sym);
    }

    public clojure.lang.IPersistentMap getVarMeta(String qualifiedName) {
        return varMeta.get(qualifiedName);
    }

    public void setVarMeta(String qualifiedName, clojure.lang.IPersistentMap meta) {
        if (meta != null) {
            varMeta.put(qualifiedName, meta);
        }
    }

    @TruffleBoundary
    public void setMacro(String name, Object fn) {
        // Only put into global macros map for core/user namespaces
        if (currentNamespace == null || "clojure.core".equals(currentNamespace) || "user".equals(currentNamespace)) {
            macros.put(name, fn);
        }
        // Always register in the current namespace so ns-qualified macro calls work
        ClojureNamespace ns = namespaces.get(currentNamespace);
        if (ns != null) {
            ns.intern("__macro__" + name, fn);
            // Also intern under the plain name so it can be referred/resolved normally
            ns.intern(name, fn);
        }
    }

    @TruffleBoundary
    public Object getMacro(String name) {
        // Check current namespace's macros first, then fall back to global
        ClojureNamespace ns = namespaces.get(currentNamespace);
        if (ns != null) {
            // Check refers (from use/refer) for macros
            Object referred = ns.resolve("__macro__" + name);
            if (referred != null) return referred;
        }
        return macros.get(name);
    }

    /**
     * Get a macro from a specific namespace (for ns-qualified macro calls like s/def).
     */
    public Object getMacroFromNs(String nsName, String macroName) {
        // Resolve namespace alias
        ClojureNamespace currentNs = namespaces.get(currentNamespace);
        String resolvedNs = nsName;
        if (currentNs != null) {
            ClojureNamespace aliased = currentNs.resolveAlias(nsName);
            if (aliased != null) resolvedNs = aliased.getName();
        }
        ClojureNamespace targetNs = namespaces.get(resolvedNs);
        if (targetNs != null) {
            Object macro = targetNs.resolve("__macro__" + macroName);
            if (macro != null) {
                if (DEBUG) System.err.println("[MACRO-NS] Found " + nsName + "/" + macroName + " in " + resolvedNs);
                return macro;
            }
        }
        return null;
    }

    public String getCurrentNamespace() { return currentNamespace; }
    public void setCurrentNamespace(String ns) { this.currentNamespace = ns; }

    public ClojureNamespace getOrCreateNamespace(String name) {
        return namespaces.computeIfAbsent(name, ClojureNamespace::new);
    }

    @TruffleBoundary
    public ClojureNamespace getNamespace(String name) {
        return namespaces.get(name);
    }

    public java.util.Collection<ClojureNamespace> getAllNamespaces() {
        return namespaces.values();
    }

    public void declareDynamic(String name) {
        dynamicVars.add(name);
    }

    @TruffleBoundary
    public boolean isDynamic(String name) {
        return dynamicVars.contains(name);
    }

    @TruffleBoundary
    public void pushThreadBinding(String name, Object value) {
        threadBindings.get().put(name, value);
    }

    @TruffleBoundary
    public void popThreadBinding(String name) {
        threadBindings.get().remove(name);
    }

    @TruffleBoundary
    public Object getThreadBinding(String name) {
        return threadBindings.get().get(name);
    }

    // Override getVar to check thread-local bindings first for dynamic vars
    @TruffleBoundary
    public Object getVarWithBindings(String name) {
        if (dynamicVars.contains(name)) {
            Object bound = threadBindings.get().get(name);
            if (bound != null) return bound;
        }
        // Dynamic resolution for *ns*
        if ("*ns*".equals(name)) {
            return clojure.lang.Namespace.findOrCreate(clojure.lang.Symbol.intern(currentNamespace));
        }
        return getVar(name);
    }

    public void loadNamespace(String nsName) {
        if (nsName == null || nsName.isEmpty()) {
            System.err.println("[NS-ERROR] Attempted to load empty namespace name!");
            new Exception("Empty namespace trace").printStackTrace(System.err);
            return;
        }
        if (namespaces.containsKey(nsName)) { if (DEBUG) System.err.println("[NS] skip (cached): " + nsName); return; }
        if (DEBUG) System.err.println("[NS] loading: " + nsName + " (current=" + currentNamespace + ")");
        // Built-in pseudo-namespaces
        if (nsName.equals("clojure.string")) {
            registerStringNamespace();
            return;
        }
        if (nsName.equals("clojure.set")) {
            registerSetNamespace();
            return;
        }
        // clojure.walk: load from source (JAR)
        if (nsName.equals("clojure.edn")) {
            registerEdnNamespace();
            return;
        }
        if (nsName.equals("clojure.pprint")) {
            registerPprintNamespace();
            return;
        }
        if (nsName.equals("clojure.java.io")) {
            registerJavaIoNamespace();
            return;
        }
        if (!loadingNamespaces.add(nsName))
            throw new RuntimeException("Circular require detected: " + nsName);
        System.err.println("[NS-LOAD] >> " + nsName);
        long nsStartTime = System.currentTimeMillis();
        try {
            String basePath = nsName.replace('.', '/').replace('-', '_');
            String path = basePath + ".clj";
            java.io.InputStream is = findResource(path);
            if (is == null) {
                path = basePath + ".cljc";
                is = findResource(path);
            }
            if (is == null) {
                throw new RuntimeException("Cannot find namespace: " + nsName + " (searched: " + basePath + ".clj/.cljc)");
            }
            byte[] rawBytes = is.readAllBytes();
            is.close();
            String source = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (nsName.equals("clojure.spec.alpha")) {
                if (DEBUG) System.err.println("[NS-DEBUG] spec.alpha: rawBytes=" + rawBytes.length + " source.length=" + source.length());
            }
            String prevNs = currentNamespace;
            getOrCreateNamespace(nsName);
            currentNamespace = nsName;
            // Set Clojure's *ns* so LispReader can resolve ::alias/keyword
            clojure.lang.Var nsVar = clojure.truffle.runtime.ClojureRT.var("clojure.core", "*ns*");
            Object prevClojureNs = nsVar.deref();
            clojure.lang.Namespace clojureNs = clojure.lang.Namespace.findOrCreate(
                    clojure.lang.Symbol.intern(nsName));
            // Refer all clojure.core vars so syntax-quote resolves symbols properly
            referClojureCoreVars(clojureNs);
            clojure.lang.Var.pushThreadBindings(clojure.truffle.runtime.ClojureRT.map(nsVar, clojureNs));
            try {
                Analyzer analyzer = new Analyzer(language);
                analyzer.setContext(this);
                // Use incremental read-eval so ns/require side effects
                // take effect before reading subsequent forms (needed for ::alias/keyword)
                analyzer.loadSource(source, language);
            } finally {
                clojure.lang.Var.popThreadBindings();
            }
            currentNamespace = prevNs;
            System.err.println("[NS-LOAD] << " + nsName + " (" + (System.currentTimeMillis() - nsStartTime) + "ms)");
            // Post-load hooks for specific namespaces
            if ("clojure.core.async".equals(nsName)) {
                registerAsyncOverrides();
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error loading namespace: " + nsName, e);
        } finally {
            loadingNamespaces.remove(nsName);
        }
    }

    private void referClojureCoreVars(clojure.lang.Namespace targetNs) {
        clojure.lang.Namespace coreNs = clojure.lang.Namespace.findOrCreate(
                clojure.lang.Symbol.intern("clojure.core"));
        // Copy all clojure.core mappings as refers into the target namespace
        for (Object entry : coreNs.getMappings()) {
            if (entry instanceof java.util.Map.Entry<?,?> e) {
                Object key = e.getKey();
                Object val = e.getValue();
                if (key instanceof clojure.lang.Symbol sym && val instanceof clojure.lang.Var v) {
                    try {
                        targetNs.refer(sym, v);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void registerClojureCoreVars() {
        // Register all builtin names as interned Vars in Clojure's clojure.core namespace.
        // This enables LispReader's syntax-quote to resolve symbols like `defn` to
        // clojure.core/defn instead of the current namespace.
        clojure.lang.Namespace coreNs = clojure.lang.Namespace.findOrCreate(
                clojure.lang.Symbol.intern("clojure.core"));
        java.util.Set<String> allNames = new java.util.HashSet<>(globalVars.keySet());
        // Also register special forms that aren't in globalVars
        allNames.addAll(java.util.List.of("if", "do", "def", "let*", "fn*", "quote", "recur",
                "loop*", "try", "throw", "new", "set!", "var", ".", "monitor-enter", "monitor-exit",
                "let", "fn", "loop", "and", "or", "when", "cond", "defn", "defn-",
                "defmacro", "lazy-seq", "delay", "future", "binding",
                "for", "doseq", "dotimes", "case", "condp", "while",
                "if-let", "when-let", "if-some", "when-some", "when-not", "if-not",
                "->", "->>", "as->", "some->", "some->>", "cond->", "cond->>",
                "doto", "..", "comment", "declare", "defonce", "with-open", "with-bindings",
                "ns", "in-ns", "require", "import", "use", "refer", "refer-clojure",
                "reify", "proxy", "extend-type", "extend-protocol",
                "defmulti", "defmethod", "defprotocol", "deftype", "defrecord",
                "locking", "dosync", "assert", "time", "letfn"));
        for (String name : allNames) {
            try {
                clojure.lang.Symbol sym = clojure.lang.Symbol.intern(name);
                // Only intern if not already mapped (avoid overwriting existing RT vars)
                if (coreNs.findInternedVar(sym) == null) {
                    coreNs.intern(sym);
                }
            } catch (Exception ignored) {}
        }
    }

    private void registerBuiltins() {
        // Compiler hint vars (no-op but must be settable)
        for (String v : new String[]{"*warn-on-reflection*", "*unchecked-math*", "*print-meta*",
                "*print-length*", "*print-level*", "*print-dup*", "*print-readably*",
                "*print-namespace-maps*", "*data-readers*", "*default-data-reader-fn*",
                "*read-eval*", "*command-line-args*", "*compile-path*",
                "*compile-files*", "*assert*", "*math-context*", "*file*",
                "*compiler-options*"}) {
            globalVars.put(v, false);
            dynamicVars.add(v);
        }
        // *clojure-version* - version map
        clojure.lang.IPersistentMap versionMap = clojure.truffle.runtime.ClojureRT.map(
                clojure.lang.Keyword.intern("major"), 1L,
                clojure.lang.Keyword.intern("minor"), 12L,
                clojure.lang.Keyword.intern("incremental"), 0L,
                clojure.lang.Keyword.intern("qualifier"), ClojureNil.INSTANCE);
        globalVars.put("*clojure-version*", versionMap);
        dynamicVars.add("*clojure-version*");
        // Standard I/O vars
        globalVars.put("*out*", new java.io.OutputStreamWriter(System.out));
        dynamicVars.add("*out*");
        globalVars.put("*err*", new java.io.PrintWriter(System.err, true));
        dynamicVars.add("*err*");
        globalVars.put("*in*", new java.io.InputStreamReader(System.in));
        dynamicVars.add("*in*");
        globalVars.put("*flush-on-newline*", true);
        dynamicVars.add("*flush-on-newline*");
        globalVars.put("*print-readably*", true);
        dynamicVars.add("*print-readably*");
        globalVars.put("*print-dup*", false);
        dynamicVars.add("*print-dup*");
        globalVars.put("*print-meta*", false);
        dynamicVars.add("*print-meta*");
        globalVars.put("*print-length*", ClojureNil.INSTANCE);
        dynamicVars.add("*print-length*");
        globalVars.put("*print-level*", ClojureNil.INSTANCE);
        dynamicVars.add("*print-level*");
        globalVars.put("*print-namespace-maps*", true);
        dynamicVars.add("*print-namespace-maps*");
        // Macro special vars: &env and &form (nil by default, set during macro expansion)
        globalVars.put("&env", ClojureNil.INSTANCE);
        globalVars.put("&form", ClojureNil.INSTANCE);
        // Arithmetic
        defBuiltin("+", args -> {
            if (args.length == 0) return 0L;
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                result = addNumbers(result, args[i]);
            }
            return result;
        });

        defBuiltin("-", args -> {
            if (args.length == 0) return 0L;
            if (args.length == 1) return negateNumber(args[0]);
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                result = subtractNumbers(result, args[i]);
            }
            return result;
        });

        defBuiltin("*", args -> {
            if (args.length == 0) return 1L;
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                result = multiplyNumbers(result, args[i]);
            }
            return result;
        });

        defBuiltin("/", args -> {
            if (args.length == 1) return divideNumbers(1L, args[0]);
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                result = divideNumbers(result, args[i]);
            }
            return result;
        });

        defBuiltin("mod", args -> {
            checkArity(args, 2, "mod");
            return modNumbers(args[0], args[1]);
        });

        // Comparison
        defBuiltin("=", args -> {
            if (args.length < 2) return true;
            for (int i = 1; i < args.length; i++) {
                if (!clojureEquals(args[0], args[i])) return false;
            }
            return true;
        });

        defBuiltin("<", args -> compareChain(args, -1));
        defBuiltin(">", args -> compareChain(args, 1));
        defBuiltin("<=", args -> compareChainLE(args, false));
        defBuiltin(">=", args -> compareChainLE(args, true));

        // Logic
        defBuiltin("not", args -> {
            checkArity(args, 1, "not");
            return !isTruthy(args[0]);
        });

        // Type predicates
        defBuiltin("nil?", args -> {
            checkArity(args, 1, "nil?");
            return args[0] instanceof ClojureNil;
        });

        defBuiltin("number?", args -> {
            checkArity(args, 1, "number?");
            return args[0] instanceof Number;
        });

        defBuiltin("string?", args -> {
            checkArity(args, 1, "string?");
            return args[0] instanceof String;
        });

        // String
        defBuiltin("str", args -> {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (!(arg instanceof ClojureNil)) {
                    sb.append(printString(arg, false));
                }
            }
            return sb.toString();
        });

        defBuiltin("pr-str", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            return sb.toString();
        });

        // IO
        defBuiltin("print", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], false));
            }
            writeOut(sb.toString());
            return ClojureNil.INSTANCE;
        });

        defBuiltin("pr", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            writeOut(sb.toString());
            return ClojureNil.INSTANCE;
        });

        defBuiltin("println", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], false));
            }
            writeOut(sb.toString() + "\n");
            return ClojureNil.INSTANCE;
        });

        defBuiltin("prn", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            writeOut(sb.toString() + "\n");
            return ClojureNil.INSTANCE;
        });

        // print-method multimethod: dispatches on (class x)
        BuiltinFunction printMethodDispatch = args -> {
            if (args.length < 1) return ClojureNil.INSTANCE;
            Object x = args[0];
            if (x == null || x instanceof ClojureNil) return Void.class;
            return x.getClass();
        };
        var printMethodMM = new ClojureMultiMethod("print-method", printMethodDispatch, this);
        // Default method: write the standard printString representation
        printMethodMM.addMethod(clojure.lang.Keyword.intern("default"), (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("print-method: expected 2 args [object writer]");
            Object obj = args[0];
            Object writer = args[1];
            String s = printString(obj, true);
            if (writer instanceof java.io.Writer w) {
                try { w.write(s); } catch (java.io.IOException e) { throw new RuntimeException(e); }
            }
            return ClojureNil.INSTANCE;
        });
        globalVars.put("print-method", printMethodMM);

        // print-dup multimethod
        var printDupMM = new ClojureMultiMethod("print-dup", printMethodDispatch, this);
        printDupMM.addMethod(clojure.lang.Keyword.intern("default"), (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("print-dup: expected 2 args [object writer]");
            Object obj = args[0];
            Object writer = args[1];
            String s = printString(obj, true);
            if (writer instanceof java.io.Writer w) {
                try { w.write(s); } catch (java.io.IOException e) { throw new RuntimeException(e); }
            }
            return ClojureNil.INSTANCE;
        });
        globalVars.put("print-dup", printDupMM);

        defBuiltin("newline", args -> {
            writeOut("\n");
            return ClojureNil.INSTANCE;
        });

        defBuiltin("flush", args -> {
            try {
                java.io.Writer out = (java.io.Writer) getVar("*out*");
                if (out != null) out.flush();
            } catch (Exception ignored) {}
            return ClojureNil.INSTANCE;
        });

        defBuiltin("shutdown-agents", args -> {
            // No-op: Truffle Clojure doesn't have an agent thread pool
            return ClojureNil.INSTANCE;
        });

        // Collections
        defBuiltin("list", args -> {
            clojure.lang.IPersistentList list = clojure.lang.PersistentList.EMPTY;
            for (int i = args.length - 1; i >= 0; i--) {
                list = (clojure.lang.IPersistentList) list.cons(args[i]);
            }
            return list;
        });

        // list* - prepends args to last arg (which must be a seq)
        defBuiltin("list*", args -> {
            if (args.length == 0) throw new RuntimeException("list*: expected at least 1 arg");
            if (args.length == 1) return seqOf(args[0]);
            Object last = args[args.length - 1];
            clojure.lang.ISeq result = seqOf(last);
            for (int i = args.length - 2; i >= 0; i--) {
                result = new clojure.lang.Cons(args[i], result);
            }
            return result;
        });

        // reductions - returns lazy seq of intermediate reduce values
        defBuiltin("reductions", args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("reductions: expected 2 or 3 args");
            Object f = args[0];
            Object init;
            clojure.lang.ISeq seq;
            if (args.length == 3) {
                init = args[1];
                seq = seqOf(args[2]);
            } else {
                seq = seqOf(args[1]);
                if (seq == null) return clojure.lang.PersistentList.create(java.util.Arrays.asList(callFunction(f, new Object[0])));
                init = seq.first();
                seq = seq.next();
            }
            java.util.List<Object> result = new ArrayList<>();
            result.add(init);
            Object acc = init;
            for (; seq != null; seq = seq.next()) {
                acc = callFunction(f, new Object[]{acc, seq.first()});
                if (acc instanceof Reduced r) { result.add(r.value); break; }
                result.add(acc);
            }
            return clojure.lang.PersistentList.create(result);
        });

        defBuiltin("vector", args ->
                clojure.lang.PersistentVector.create(java.util.Arrays.asList(args)));

        defBuiltin("first", args -> {
            checkArity(args, 1, "first");
            return clojureFirst(args[0]);
        });

        defBuiltin("rest", args -> {
            checkArity(args, 1, "rest");
            return clojureRest(args[0]);
        });

        defBuiltin("cons", args -> {
            checkArity(args, 2, "cons");
            return clojureCons(args[0], args[1]);
        });

        defBuiltin("count", args -> {
            checkArity(args, 1, "count");
            return clojureCount(args[0]);
        });

        defBuiltin("nth", args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("nth: expected 2 or 3 args");
            return clojureNth(args[0], args[1], args.length == 3 ? args[2] : null);
        });

        defBuiltin("conj", args -> {
            if (args.length == 0) return clojure.lang.PersistentVector.EMPTY;
            if (args.length == 1) return args[0];
            return clojureConj(args);
        });

        // Identity
        defBuiltin("identity", args -> {
            checkArity(args, 1, "identity");
            return args[0];
        });

        defBuiltin("type", args -> {
            checkArity(args, 1, "type");
            if (args[0] instanceof ClojureNil) return "nil";
            return args[0].getClass().getName();
        });

        // --- Higher-order functions ---

        defBuiltin("apply", args -> {
            if (args.length < 2) throw new RuntimeException("apply: expected at least 2 args");
            Object fn = args[0];
            // Last arg must be a sequence; preceding args are prepended
            Object lastArg = args[args.length - 1];
            java.util.List<Object> allArgs = new ArrayList<>();
            for (int i = 1; i < args.length - 1; i++) {
                allArgs.add(args[i]);
            }
            // Expand last arg (sequence) into individual args
            if (lastArg instanceof ClojureNil) {
                // no additional args
            } else if (lastArg instanceof clojure.lang.Seqable s) {
                for (clojure.lang.ISeq seq = s.seq(); seq != null; seq = seq.next()) {
                    allArgs.add(seq.first());
                }
            } else {
                throw new RuntimeException("apply: last arg must be a sequence");
            }
            return callFunction(fn, allArgs.toArray());
        });

        defBuiltin("map", args -> {
            if (args.length < 2) throw new RuntimeException("map: expected at least 2 args");
            Object fn = args[0];
            if (args.length == 2) {
                return lazyMap(fn, args[1]);
            }
            // Multi-collection map: (map f coll1 coll2 ...)
            clojure.lang.ISeq[] seqs = new clojure.lang.ISeq[args.length - 1];
            for (int i = 1; i < args.length; i++) {
                seqs[i - 1] = seqOf(args[i]);
                if (seqs[i - 1] == null) return clojure.lang.PersistentList.EMPTY;
            }
            return lazyMapMulti(fn, seqs);
        });

        defBuiltin("filter", args -> {
            checkArity(args, 2, "filter");
            Object fn = args[0];
            Object coll = args[1];
            return lazyFilter(fn, coll);
        });

        defBuiltin("reduce", args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("reduce: expected 2 or 3 args");
            Object fn = args[0];
            if (fn instanceof clojure.truffle.runtime.ClojureNil || fn == null) {
                System.err.println("[REDUCE-DEBUG] reduce called with nil function. args.length=" + args.length);
                if (args.length == 3) System.err.println("  init=" + args[1] + " coll=" + (args[2] != null ? args[2].getClass().getSimpleName() + ":" + args[2] : "null"));
                else System.err.println("  coll=" + (args[1] != null ? args[1].getClass().getSimpleName() + ":" + args[1] : "null"));
                new RuntimeException("reduce-nil-fn trace").printStackTrace(System.err);
            }
            Object acc;
            clojure.lang.ISeq seq;
            if (args.length == 3) {
                acc = args[1];
                seq = seqOf(args[2]);
            } else {
                seq = seqOf(args[1]);
                if (seq == null) return callFunction(fn, new Object[0]);
                acc = seq.first();
                seq = seq.next();
            }
            while (seq != null) {
                acc = callFunction(fn, new Object[]{acc, seq.first()});
                if (acc instanceof Reduced r) return r.value;
                seq = seq.next();
            }
            return acc;
        });

        defBuiltin("range", args -> {
            long start, end, step;
            boolean infinite = false;
            switch (args.length) {
                case 0: start = 0; end = Long.MAX_VALUE; step = 1; infinite = true; break;
                case 1: start = 0; end = ((Number) args[0]).longValue(); step = 1; break;
                case 2: start = ((Number) args[0]).longValue(); end = ((Number) args[1]).longValue(); step = 1; break;
                case 3: start = ((Number) args[0]).longValue(); end = ((Number) args[1]).longValue(); step = ((Number) args[2]).longValue(); break;
                default: throw new RuntimeException("range: too many args");
            }
            return lazyRange(start, end, step);
        });

        // --- eval ---

        defBuiltin("eval", args -> {
            checkArity(args, 1, "eval");
            return evalForm(args[0]);
        });

        defBuiltin("read-string", args -> {
            checkArity(args, 1, "read-string");
            if (!(args[0] instanceof String s))
                throw new RuntimeException("read-string: expected a string");
            try {
                java.io.PushbackReader r = new java.io.PushbackReader(new java.io.StringReader(s), 2);
                return clojure.lang.TruffleReader.read(r, true, null, false, null);
            } catch (Exception e) {
                throw new RuntimeException("read-string: " + e.getMessage(), e);
            }
        });

        // --- More predicates ---

        defBuiltin("empty?", args -> {
            checkArity(args, 1, "empty?");
            if (args[0] instanceof ClojureNil) return true;
            if (args[0] instanceof clojure.lang.Seqable s) return s.seq() == null;
            if (args[0] instanceof String str) return str.isEmpty();
            return false;
        });

        defBuiltin("seq?", args -> {
            checkArity(args, 1, "seq?");
            return args[0] instanceof clojure.lang.ISeq;
        });

        defBuiltin("vector?", args -> {
            checkArity(args, 1, "vector?");
            return args[0] instanceof clojure.lang.IPersistentVector;
        });

        defBuiltin("map?", args -> {
            checkArity(args, 1, "map?");
            return args[0] instanceof clojure.lang.IPersistentMap;
        });

        defBuiltin("keyword?", args -> {
            checkArity(args, 1, "keyword?");
            return args[0] instanceof clojure.lang.Keyword;
        });

        defBuiltin("symbol?", args -> {
            checkArity(args, 1, "symbol?");
            return args[0] instanceof clojure.lang.Symbol;
        });

        defBuiltin("fn?", args -> {
            checkArity(args, 1, "fn?");
            return args[0] instanceof ClojureFunction || args[0] instanceof BuiltinFunction;
        });

        // --- More collection ops ---

        defBuiltin("seq", args -> {
            checkArity(args, 1, "seq");
            if (args[0] == null || args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (args[0] instanceof clojure.lang.Seqable s) {
                clojure.lang.ISeq seq = s.seq();
                return seq == null ? ClojureNil.INSTANCE : seq;
            }
            if (args[0] instanceof CharSequence cs) {
                if (cs.length() == 0) return ClojureNil.INSTANCE;
                return clojure.lang.StringSeq.create(cs.toString());
            }
            if (args[0] instanceof java.lang.Iterable<?> it) {
                java.util.Iterator<?> iter = it.iterator();
                if (!iter.hasNext()) return ClojureNil.INSTANCE;
                java.util.List<Object> items = new java.util.ArrayList<>();
                while (iter.hasNext()) items.add(iter.next());
                return clojure.lang.PersistentList.create(items);
            }
            if (args[0].getClass().isArray()) {
                clojure.lang.ISeq s = clojure.truffle.runtime.ClojureRT.seq(args[0]);
                return s == null ? ClojureNil.INSTANCE : s;
            }
            throw new RuntimeException("seq: not seqable: " + args[0]);
        });

        defBuiltin("into", args -> {
            checkArity(args, 2, "into");
            Object to = args[0];
            Object from = args[1];
            if (!(to instanceof clojure.lang.IPersistentCollection coll))
                throw new RuntimeException("into: first arg must be a collection");
            if (from instanceof ClojureNil) return to;
            for (clojure.lang.ISeq seq = seqOf(from); seq != null; seq = seq.next()) {
                coll = coll.cons(seq.first());
            }
            return coll;
        });

        defBuiltin("reverse", args -> {
            checkArity(args, 1, "reverse");
            if (args[0] instanceof ClojureNil) return clojure.lang.PersistentList.EMPTY;
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                result = result.cons(seq.first());
            }
            return result;
        });

        // --- Math ---

        defBuiltin("inc", args -> {
            checkArity(args, 1, "inc");
            return addNumbers(args[0], 1L);
        });

        defBuiltin("dec", args -> {
            checkArity(args, 1, "dec");
            return subtractNumbers(args[0], 1L);
        });

        // Unchecked math operations - same as checked versions (no overflow checking in Truffle)
        defBuiltin("unchecked-inc", args -> { checkArity(args, 1, "unchecked-inc"); return addNumbers(args[0], 1L); });
        defBuiltin("unchecked-inc-int", args -> { checkArity(args, 1, "unchecked-inc-int"); return addNumbers(args[0], 1L); });
        defBuiltin("unchecked-dec", args -> { checkArity(args, 1, "unchecked-dec"); return subtractNumbers(args[0], 1L); });
        defBuiltin("unchecked-dec-int", args -> { checkArity(args, 1, "unchecked-dec-int"); return subtractNumbers(args[0], 1L); });
        defBuiltin("unchecked-add", args -> { checkArity(args, 2, "unchecked-add"); return addNumbers(args[0], args[1]); });
        defBuiltin("unchecked-add-int", args -> { checkArity(args, 2, "unchecked-add-int"); return addNumbers(args[0], args[1]); });
        defBuiltin("unchecked-subtract", args -> { checkArity(args, 2, "unchecked-subtract"); return subtractNumbers(args[0], args[1]); });
        defBuiltin("unchecked-subtract-int", args -> { checkArity(args, 2, "unchecked-subtract-int"); return subtractNumbers(args[0], args[1]); });
        defBuiltin("unchecked-multiply", args -> { checkArity(args, 2, "unchecked-multiply"); return multiplyNumbers(args[0], args[1]); });
        defBuiltin("unchecked-multiply-int", args -> { checkArity(args, 2, "unchecked-multiply-int"); return multiplyNumbers(args[0], args[1]); });
        defBuiltin("unchecked-negate", args -> { checkArity(args, 1, "unchecked-negate"); return subtractNumbers(0L, args[0]); });
        defBuiltin("unchecked-negate-int", args -> { checkArity(args, 1, "unchecked-negate-int"); return subtractNumbers(0L, args[0]); });

        defBuiltin("max", args -> {
            if (args.length == 0) throw new RuntimeException("max: expected at least 1 arg");
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                if (compareNumbers(result, args[i]) < 0) result = args[i];
            }
            return result;
        });

        defBuiltin("min", args -> {
            if (args.length == 0) throw new RuntimeException("min: expected at least 1 arg");
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                if (compareNumbers(result, args[i]) > 0) result = args[i];
            }
            return result;
        });

        defBuiltin("abs", args -> {
            checkArity(args, 1, "abs");
            if (args[0] instanceof Long l) return Math.abs(l);
            return Math.abs(toDouble(args[0]));
        });

        // --- Misc ---

        defBuiltin("symbol", args -> {
            checkArity(args, 1, "symbol");
            return clojure.lang.Symbol.intern(args[0].toString());
        });

        defBuiltin("keyword", args -> {
            checkArity(args, 1, "keyword");
            String s = args[0].toString();
            if (s.startsWith(":")) s = s.substring(1);
            return clojure.lang.Keyword.intern(s);
        });

        defBuiltin("name", args -> {
            checkArity(args, 1, "name");
            if (args[0] instanceof clojure.lang.Named n) return n.getName();
            if (args[0] instanceof String s) return s;
            throw new RuntimeException("name: not a named value: " + args[0]);
        });

        defBuiltin("hash-map", args -> {
            if (args.length % 2 != 0) throw new RuntimeException("hash-map: odd number of args");
            return clojure.lang.PersistentArrayMap.createAsIfByAssoc(args);
        });

        defBuiltin("get", args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("get: expected 2 or 3 args");
            Object coll = args[0];
            Object key = args[1];
            Object notFound = args.length == 3 ? args[2] : ClojureNil.INSTANCE;
            if (coll instanceof ClojureNil) return notFound;
            if (coll instanceof clojure.lang.ILookup lookup) {
                Object val = lookup.valAt(key, notFound);
                return val == null ? ClojureNil.INSTANCE : val;
            }
            // IPersistentSet doesn't implement ILookup but supports get()
            if (coll instanceof clojure.lang.IPersistentSet s) {
                Object val = s.get(key);
                return val == null ? notFound : val;
            }
            // String indexing: (get "foo" 0) => \f
            if (coll instanceof String s && key instanceof Number n) {
                int idx = n.intValue();
                if (idx >= 0 && idx < s.length()) return s.charAt(idx);
                return notFound;
            }
            return notFound;
        });

        defBuiltin("assoc", args -> {
            if (args.length < 3 || args.length % 2 == 0)
                throw new RuntimeException("assoc: expected odd number of args >= 3");
            Object map = args[0];
            if (map instanceof ClojureNil) map = clojure.lang.PersistentArrayMap.EMPTY;
            if (!(map instanceof clojure.lang.Associative a))
                throw new RuntimeException("assoc: not associative: " + map);
            for (int i = 1; i < args.length; i += 2) {
                a = a.assoc(args[i], args[i + 1]);
            }
            return a;
        });

        defBuiltin("dissoc", args -> {
            if (args.length < 1) throw new RuntimeException("dissoc: expected at least 1 arg");
            Object map = args[0];
            if (map instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (!(map instanceof clojure.lang.IPersistentMap m))
                throw new RuntimeException("dissoc: not a map: " + map);
            for (int i = 1; i < args.length; i++) {
                m = m.without(args[i]);
            }
            return m;
        });

        defBuiltin("contains?", args -> {
            checkArity(args, 2, "contains?");
            if (args[0] instanceof ClojureNil) return false;
            if (args[0] instanceof clojure.lang.Associative a) return a.containsKey(args[1]);
            return false;
        });

        defBuiltin("keys", args -> {
            checkArity(args, 1, "keys");
            if (args[0] == null || args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (args[0] instanceof clojure.lang.IPersistentMap m) {
                java.util.List<Object> keys = new ArrayList<>();
                for (clojure.lang.ISeq s = m.seq(); s != null; s = s.next()) {
                    keys.add(((clojure.lang.IMapEntry) s.first()).key());
                }
                clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
                for (int i = keys.size() - 1; i >= 0; i--) result = result.cons(keys.get(i));
                return result;
            }
            // Support seqs of MapEntry (e.g. from (filter pred map))
            if (args[0] instanceof clojure.lang.Seqable || args[0] instanceof Iterable) {
                clojure.lang.ISeq s = seqOf(args[0]);
                if (s == null) return clojure.lang.PersistentList.EMPTY;
                java.util.List<Object> keys2 = new ArrayList<>();
                for (; s != null; s = s.next()) {
                    Object item = s.first();
                    if (item instanceof clojure.lang.IMapEntry me) {
                        keys2.add(me.key());
                    } else {
                        throw new RuntimeException("keys: element is not a map entry: " + item);
                    }
                }
                clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
                for (int i = keys2.size() - 1; i >= 0; i--) result = result.cons(keys2.get(i));
                return result;
            }
            throw new RuntimeException("keys: not a map, got: " + args[0].getClass().getName());
        });

        defBuiltin("vals", args -> {
            checkArity(args, 1, "vals");
            if (args[0] == null || args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (args[0] instanceof clojure.lang.IPersistentMap m) {
                java.util.List<Object> vals = new ArrayList<>();
                for (clojure.lang.ISeq s = m.seq(); s != null; s = s.next()) {
                    vals.add(((clojure.lang.IMapEntry) s.first()).val());
                }
                clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
                for (int i = vals.size() - 1; i >= 0; i--) result = result.cons(vals.get(i));
                return result;
            }
            // Support seqs of MapEntry (e.g. from (filter pred map))
            // Also handle Seqable types (LazySeq, Cons, etc.)
            if (args[0] instanceof clojure.lang.Seqable || args[0] instanceof Iterable) {
                clojure.lang.ISeq s = seqOf(args[0]);
                if (s == null) return clojure.lang.PersistentList.EMPTY;
                java.util.List<Object> vals = new ArrayList<>();
                for (; s != null; s = s.next()) {
                    Object item = s.first();
                    if (item instanceof clojure.lang.IMapEntry me) {
                        vals.add(me.val());
                    } else {
                        throw new RuntimeException("vals: element is not a map entry: " + item);
                    }
                }
                clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
                for (int i = vals.size() - 1; i >= 0; i--) result = result.cons(vals.get(i));
                return result;
            }
            throw new RuntimeException("vals: not a map, got: " + args[0].getClass().getName());
        });

        // --- Atom ---

        defBuiltin("atom", args -> {
            if (args.length < 1) throw new RuntimeException("atom: expected at least 1 arg");
            ClojureAtom a = new ClojureAtom(args[0]);
            // Process keyword args: :validator fn, :meta m
            for (int i = 1; i + 1 < args.length; i += 2) {
                if (args[i] instanceof clojure.lang.Keyword kw) {
                    if (kw.getName().equals("validator")) {
                        a.setValidator(args[i + 1]);
                    }
                }
            }
            return a;
        });

        // deref for atoms (overridden below with full implementation)

        defBuiltin("reset!", args -> {
            checkArity(args, 2, "reset!");
            if (!(args[0] instanceof ClojureAtom a))
                throw new RuntimeException("reset!: not an atom");
            return a.reset(args[1]);
        });

        defBuiltin("swap!", args -> {
            if (args.length < 2) throw new RuntimeException("swap!: expected at least 2 args");
            if (!(args[0] instanceof ClojureAtom a))
                throw new RuntimeException("swap!: not an atom");
            Object fn = args[1];
            Object[] extraArgs = Arrays.copyOfRange(args, 2, args.length);
            while (true) {
                Object oldVal = a.deref();
                Object[] callArgs = new Object[1 + extraArgs.length];
                callArgs[0] = oldVal;
                System.arraycopy(extraArgs, 0, callArgs, 1, extraArgs.length);
                Object newVal = callFunction(fn, callArgs);
                Object validator = a.getValidator();
                if (validator != null && !isTruthy(callFunction(validator, new Object[]{newVal}))) {
                    throw new RuntimeException("Invalid reference state");
                }
                if (a.compareAndSet(oldVal, newVal)) return newVal;
            }
        });

        defBuiltin("atom?", args -> {
            checkArity(args, 1, "atom?");
            return args[0] instanceof ClojureAtom;
        });

        // swap-vals! - like swap! but returns [old new]
        defBuiltin("swap-vals!", args -> {
            if (args.length < 2) throw new RuntimeException("swap-vals!: expected at least 2 args");
            if (!(args[0] instanceof ClojureAtom a))
                throw new RuntimeException("swap-vals!: not an atom");
            Object fn = args[1];
            Object[] extraArgs = Arrays.copyOfRange(args, 2, args.length);
            while (true) {
                Object oldVal = a.deref();
                Object[] callArgs = new Object[1 + extraArgs.length];
                callArgs[0] = oldVal;
                System.arraycopy(extraArgs, 0, callArgs, 1, extraArgs.length);
                Object newVal = callFunction(fn, callArgs);
                if (a.compareAndSet(oldVal, newVal))
                    return clojure.lang.PersistentVector.create(oldVal, newVal);
            }
        });

        // reset-vals! - like reset! but returns [old new]
        defBuiltin("reset-vals!", args -> {
            checkArity(args, 2, "reset-vals!");
            if (!(args[0] instanceof ClojureAtom a))
                throw new RuntimeException("reset-vals!: not an atom");
            while (true) {
                Object oldVal = a.deref();
                if (a.compareAndSet(oldVal, args[1]))
                    return clojure.lang.PersistentVector.create(oldVal, args[1]);
            }
        });

        // --- More numeric predicates ---

        defBuiltin("zero?", args -> {
            checkArity(args, 1, "zero?");
            return compareNumbers(args[0], 0L) == 0;
        });
        defBuiltin("pos?", args -> {
            checkArity(args, 1, "pos?");
            return compareNumbers(args[0], 0L) > 0;
        });
        defBuiltin("neg?", args -> {
            checkArity(args, 1, "neg?");
            return compareNumbers(args[0], 0L) < 0;
        });
        defBuiltin("even?", args -> {
            checkArity(args, 1, "even?");
            return ((Number) args[0]).longValue() % 2 == 0;
        });
        defBuiltin("odd?", args -> {
            checkArity(args, 1, "odd?");
            return ((Number) args[0]).longValue() % 2 != 0;
        });

        // --- Higher-order extras ---

        defBuiltin("comp", args -> {
            if (args.length == 0) return (BuiltinFunction) a -> { checkArity(a, 1, "identity"); return a[0]; };
            return (BuiltinFunction) callArgs -> {
                Object result = callFunction(args[args.length - 1], callArgs);
                for (int i = args.length - 2; i >= 0; i--) {
                    result = callFunction(args[i], new Object[]{result});
                }
                return result;
            };
        });

        defBuiltin("partial", args -> {
            if (args.length < 1) throw new RuntimeException("partial: expected at least 1 arg");
            Object fn = args[0];
            Object[] partialArgs = Arrays.copyOfRange(args, 1, args.length);
            return (BuiltinFunction) callArgs -> {
                Object[] allArgs = new Object[partialArgs.length + callArgs.length];
                System.arraycopy(partialArgs, 0, allArgs, 0, partialArgs.length);
                System.arraycopy(callArgs, 0, allArgs, partialArgs.length, callArgs.length);
                return callFunction(fn, allArgs);
            };
        });

        defBuiltin("constantly", args -> {
            checkArity(args, 1, "constantly");
            Object val = args[0];
            return (BuiltinFunction) ignored -> val;
        });

        defBuiltin("some", args -> {
            checkArity(args, 2, "some");
            Object fn = args[0];
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object result = callFunction(fn, new Object[]{s.first()});
                if (isTruthy(result)) return result;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("every?", args -> {
            checkArity(args, 2, "every?");
            Object fn = args[0];
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object result = callFunction(fn, new Object[]{s.first()});
                if (!isTruthy(result)) return false;
            }
            return true;
        });

        defBuiltin("take", args -> {
            checkArity(args, 2, "take");
            int n = ((Number) args[0]).intValue();
            java.util.List<Object> items = new ArrayList<>();
            int count = 0;
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null && count < n; s = s.next(), count++) {
                items.add(s.first());
            }
            return clojure.lang.PersistentVector.create(items);
        });

        defBuiltin("nthnext", args -> {
            checkArity(args, 2, "nthnext");
            Object coll = args[0];
            if (coll == null || coll instanceof ClojureNil) return ClojureNil.INSTANCE;
            int n = ((Number) args[1]).intValue();
            clojure.lang.ISeq s = seqOf(coll);
            for (int i = 0; i < n && s != null; i++) s = s.next();
            return s == null ? ClojureNil.INSTANCE : (Object) s;
        });

        defBuiltin("nthrest", args -> {
            checkArity(args, 2, "nthrest");
            Object coll = args[0];
            if (coll == null || coll instanceof ClojureNil) return clojure.lang.PersistentList.EMPTY;
            int n = ((Number) args[1]).intValue();
            clojure.lang.ISeq s = seqOf(coll);
            for (int i = 0; i < n && s != null; i++) s = s.next();
            return s == null ? (Object) clojure.lang.PersistentList.EMPTY : s;
        });

        defBuiltin("drop", args -> {
            checkArity(args, 2, "drop");
            int n = ((Number) args[0]).intValue();
            clojure.lang.ISeq s = seqOf(args[1]);
            for (int i = 0; i < n && s != null; i++) s = s.next();
            if (s == null) return clojure.lang.PersistentVector.EMPTY;
            java.util.List<Object> items = new ArrayList<>();
            for (; s != null; s = s.next()) items.add(s.first());
            return clojure.lang.PersistentVector.create(items);
        });

        defBuiltin("concat", args -> {
            java.util.List<Object> items = new ArrayList<>();
            for (Object coll : args) {
                if (coll instanceof ClojureNil) continue;
                for (clojure.lang.ISeq s = seqOf(coll); s != null; s = s.next()) {
                    items.add(s.first());
                }
            }
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        defBuiltin("mapcat", args -> {
            checkArity(args, 2, "mapcat");
            Object fn = args[0];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object result = callFunction(fn, new Object[]{s.first()});
                if (!(result instanceof ClojureNil)) {
                    for (clojure.lang.ISeq rs = seqOf(result); rs != null; rs = rs.next()) {
                        items.add(rs.first());
                    }
                }
            }
            clojure.lang.IPersistentCollection r = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) r = r.cons(items.get(i));
            return r;
        });

        defBuiltin("not=", args -> {
            if (args.length < 2) return false;
            return !clojureEquals(args[0], args[1]);
        });

        defBuiltin("ex-info", args -> {
            if (args.length < 2) throw new RuntimeException("ex-info: expected at least 2 args");
            String msg = args[0].toString();
            return new clojure.lang.ExceptionInfo(msg, (clojure.lang.IPersistentMap) args[1]);
        });

        defBuiltin("ex-message", args -> {
            checkArity(args, 1, "ex-message");
            if (args[0] instanceof Throwable t) return t.getMessage();
            return ClojureNil.INSTANCE;
        });

        defBuiltin("ex-data", args -> {
            checkArity(args, 1, "ex-data");
            if (args[0] instanceof clojure.lang.ExceptionInfo ei) return ei.getData();
            return ClojureNil.INSTANCE;
        });

        // --- String extras ---

        defBuiltin("subs", args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("subs: expected 2 or 3 args");
            String s = (String) args[0];
            int start = ((Number) args[1]).intValue();
            if (args.length == 3) return s.substring(start, ((Number) args[2]).intValue());
            return s.substring(start);
        });

        defBuiltin("string/join", args -> {
            if (args.length == 1) {
                StringBuilder sb = new StringBuilder();
                for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next())
                    sb.append(printString(s.first(), false));
                return sb.toString();
            }
            String sep = args[0].toString();
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                if (!first) sb.append(sep);
                sb.append(printString(s.first(), false));
                first = false;
            }
            return sb.toString();
        });

        // --- Lazy sequence builtins ---

        defBuiltin("iterate", args -> {
            checkArity(args, 2, "iterate");
            Object fn = args[0];
            Object val = args[1];
            return lazyIterate(fn, val);
        });

        defBuiltin("repeat", args -> {
            if (args.length == 1) {
                // infinite repeat
                Object val = args[0];
                return new LazySeq(() -> new clojure.lang.Cons(val, (clojure.lang.ISeq) callFunction(
                        globalVars.get("repeat"), new Object[]{val})));
            } else if (args.length == 2) {
                long n = ((Number) args[0]).longValue();
                Object val = args[1];
                return lazyRepeat(n, val);
            }
            throw new RuntimeException("repeat: expected 1 or 2 args");
        });

        defBuiltin("take-while", args -> {
            checkArity(args, 2, "take-while");
            Object pred = args[0];
            Object coll = args[1];
            return lazyTakeWhile(pred, coll);
        });

        defBuiltin("drop-while", args -> {
            checkArity(args, 2, "drop-while");
            Object pred = args[0];
            Object coll = args[1];
            clojure.lang.ISeq s = seqOf(coll);
            while (s != null && isTruthy(callFunction(pred, new Object[]{s.first()})))
                s = s.next();
            if (s == null) return clojure.lang.PersistentList.EMPTY;
            return s;
        });

        defBuiltin("take-nth", args -> {
            checkArity(args, 2, "take-nth");
            long n = ((Number) args[0]).longValue();
            Object coll = args[1];
            return lazyTakeNth(n, seqOf(coll));
        });

        defBuiltin("realized?", args -> {
            checkArity(args, 1, "realized?");
            if (args[0] instanceof clojure.lang.IPending p) return p.isRealized();
            return true;
        });

        defBuiltin("doall", args -> {
            if (args.length < 1 || args.length > 2)
                throw new RuntimeException("doall: expected 1 or 2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            clojure.lang.ISeq s = seqOf(coll);
            clojure.lang.ISeq head = s;
            while (s != null) s = s.next();
            return head == null ? clojure.lang.PersistentList.EMPTY : head;
        });

        defBuiltin("dorun", args -> {
            if (args.length < 1 || args.length > 2)
                throw new RuntimeException("dorun: expected 1 or 2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            clojure.lang.ISeq s = seqOf(coll);
            while (s != null) s = s.next();
            return ClojureNil.INSTANCE;
        });

        // --- Type checking ---

        defBuiltin("instance?", args -> {
            checkArity(args, 2, "instance?");
            if (!(args[0] instanceof Class<?> c))
                throw new RuntimeException("instance?: first arg must be a class");
            Object val = args[1];
            if (val instanceof ClojureNil) return false;
            if (c == clojure.lang.Atom.class && val instanceof ClojureAtom) return true;
            return c.isInstance(val);
        });

        defBuiltin("class", args -> {
            checkArity(args, 1, "class");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            return args[0].getClass();
        });

        // --- Metadata ---

        defBuiltin("meta", args -> {
            checkArity(args, 1, "meta");
            if (args[0] instanceof clojure.lang.IMeta m) {
                clojure.lang.IPersistentMap meta = m.meta();
                return meta == null ? ClojureNil.INSTANCE : meta;
            }
            return ClojureNil.INSTANCE;
        });

        // with-meta is defined later in registerBuiltins (after more type support)

        defBuiltin("vary-meta", args -> {
            if (args.length < 2) throw new RuntimeException("vary-meta: expected at least 2 args");
            if (!(args[0] instanceof clojure.lang.IObj obj))
                throw new RuntimeException("vary-meta: object does not support metadata");
            Object fn = args[1];
            clojure.lang.IPersistentMap currentMeta = (args[0] instanceof clojure.lang.IMeta m) ?
                    m.meta() : null;
            if (currentMeta == null) currentMeta = clojure.lang.PersistentHashMap.EMPTY;
            Object[] fnArgs = new Object[1 + args.length - 2];
            fnArgs[0] = currentMeta;
            System.arraycopy(args, 2, fnArgs, 1, args.length - 2);
            Object newMeta = callFunction(fn, fnArgs);
            return obj.withMeta((clojure.lang.IPersistentMap) newMeta);
        });

        // --- Satisfies? ---

        defBuiltin("satisfies?", args -> {
            checkArity(args, 2, "satisfies?");
            if (!(args[0] instanceof ClojureProtocol proto))
                throw new RuntimeException("satisfies?: first arg must be a protocol");
            return proto.hasImplementation(args[1]);
        });

        // --- Type name ---

        defBuiltin("type", args -> {
            checkArity(args, 1, "type");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (args[0] instanceof ClojureDeftypeInstance inst) return inst.getTypeName();
            return args[0].getClass();
        });

        // --- Regex ---

        defBuiltin("re-pattern", args -> {
            checkArity(args, 1, "re-pattern");
            if (args[0] instanceof java.util.regex.Pattern p) return p;
            return java.util.regex.Pattern.compile(args[0].toString());
        });

        defBuiltin("re-find", args -> {
            if (args.length == 1) {
                // (re-find matcher)
                if (!(args[0] instanceof java.util.regex.Matcher m))
                    throw new RuntimeException("re-find: expected a matcher");
                if (m.find()) {
                    if (m.groupCount() == 0) return m.group();
                    java.util.List<Object> groups = new ArrayList<>();
                    groups.add(m.group());
                    for (int i = 1; i <= m.groupCount(); i++)
                        groups.add(m.group(i) == null ? ClojureNil.INSTANCE : m.group(i));
                    return clojure.lang.PersistentVector.create(groups);
                }
                return ClojureNil.INSTANCE;
            }
            checkArity(args, 2, "re-find");
            java.util.regex.Pattern p;
            if (args[0] instanceof java.util.regex.Pattern pp) p = pp;
            else p = java.util.regex.Pattern.compile(args[0].toString());
            java.util.regex.Matcher m = p.matcher(args[1].toString());
            if (m.find()) {
                if (m.groupCount() == 0) return m.group();
                java.util.List<Object> groups = new ArrayList<>();
                groups.add(m.group());
                for (int i = 1; i <= m.groupCount(); i++)
                    groups.add(m.group(i) == null ? ClojureNil.INSTANCE : m.group(i));
                return clojure.lang.PersistentVector.create(groups);
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("re-matches", args -> {
            checkArity(args, 2, "re-matches");
            java.util.regex.Pattern p;
            if (args[0] instanceof java.util.regex.Pattern pp) p = pp;
            else p = java.util.regex.Pattern.compile(args[0].toString());
            java.util.regex.Matcher m = p.matcher(args[1].toString());
            if (m.matches()) {
                if (m.groupCount() == 0) return m.group();
                java.util.List<Object> groups = new ArrayList<>();
                groups.add(m.group());
                for (int i = 1; i <= m.groupCount(); i++)
                    groups.add(m.group(i) == null ? ClojureNil.INSTANCE : m.group(i));
                return clojure.lang.PersistentVector.create(groups);
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("re-seq", args -> {
            checkArity(args, 2, "re-seq");
            java.util.regex.Pattern p;
            if (args[0] instanceof java.util.regex.Pattern pp) p = pp;
            else p = java.util.regex.Pattern.compile(args[0].toString());
            java.util.regex.Matcher m = p.matcher(args[1].toString());
            java.util.List<Object> results = new ArrayList<>();
            while (m.find()) {
                if (m.groupCount() == 0) results.add(m.group());
                else {
                    java.util.List<Object> groups = new ArrayList<>();
                    groups.add(m.group());
                    for (int i = 1; i <= m.groupCount(); i++)
                        groups.add(m.group(i) == null ? ClojureNil.INSTANCE : m.group(i));
                    results.add(clojure.lang.PersistentVector.create(groups));
                }
            }
            if (results.isEmpty()) return ClojureNil.INSTANCE;
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = results.size() - 1; i >= 0; i--) result = result.cons(results.get(i));
            return result;
        });

        defBuiltin("re-matcher", args -> {
            checkArity(args, 2, "re-matcher");
            java.util.regex.Pattern p;
            if (args[0] instanceof java.util.regex.Pattern pp) p = pp;
            else p = java.util.regex.Pattern.compile(args[0].toString());
            return p.matcher(args[1].toString());
        });

        // --- String operations ---

        defBuiltin("clojure.string/split", args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("clojure.string/split: expected 2 or 3 args");
            String s = args[0].toString();
            java.util.regex.Pattern p;
            if (args[1] instanceof java.util.regex.Pattern pp) p = pp;
            else p = java.util.regex.Pattern.compile(args[1].toString());
            String[] parts = args.length == 3 ?
                    p.split(s, ((Number) args[2]).intValue()) : p.split(s);
            java.util.List<Object> list = new ArrayList<>();
            for (String part : parts) list.add(part);
            return clojure.lang.PersistentVector.create(list);
        });

        defBuiltin("clojure.string/replace", args -> {
            checkArity(args, 3, "clojure.string/replace");
            String s = args[0].toString();
            if (args[1] instanceof java.util.regex.Pattern p) {
                return p.matcher(s).replaceAll(args[2].toString());
            }
            return s.replace(args[1].toString(), args[2].toString());
        });

        defBuiltin("clojure.string/trim", args -> {
            checkArity(args, 1, "clojure.string/trim");
            return args[0].toString().trim();
        });

        defBuiltin("clojure.string/lower-case", args -> {
            checkArity(args, 1, "clojure.string/lower-case");
            return args[0].toString().toLowerCase();
        });

        defBuiltin("clojure.string/upper-case", args -> {
            checkArity(args, 1, "clojure.string/upper-case");
            return args[0].toString().toUpperCase();
        });

        defBuiltin("clojure.string/starts-with?", args -> {
            checkArity(args, 2, "clojure.string/starts-with?");
            return args[0].toString().startsWith(args[1].toString());
        });

        defBuiltin("clojure.string/ends-with?", args -> {
            checkArity(args, 2, "clojure.string/ends-with?");
            return args[0].toString().endsWith(args[1].toString());
        });

        defBuiltin("clojure.string/includes?", args -> {
            checkArity(args, 2, "clojure.string/includes?");
            return args[0].toString().contains(args[1].toString());
        });

        defBuiltin("clojure.string/blank?", args -> {
            checkArity(args, 1, "clojure.string/blank?");
            if (args[0] instanceof ClojureNil) return true;
            return args[0].toString().isBlank();
        });

        // --- Set operations ---

        defBuiltin("set", args -> {
            checkArity(args, 1, "set");
            if (args[0] instanceof ClojureNil) return clojure.lang.PersistentHashSet.EMPTY;
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next())
                items.add(s.first());
            return clojure.lang.PersistentHashSet.create(items);
        });

        defBuiltin("set?", args -> {
            checkArity(args, 1, "set?");
            return args[0] instanceof clojure.lang.IPersistentSet;
        });

        defBuiltin("contains?", args -> {
            checkArity(args, 2, "contains?");
            if (args[0] instanceof clojure.lang.IPersistentSet s)
                return s.contains(args[1]);
            if (args[0] instanceof clojure.lang.Associative a)
                return a.containsKey(args[1]);
            return false;
        });

        defBuiltin("disj", args -> {
            if (args.length < 2) throw new RuntimeException("disj: expected at least 2 args");
            if (!(args[0] instanceof clojure.lang.IPersistentSet s))
                throw new RuntimeException("disj: first arg must be a set");
            for (int i = 1; i < args.length; i++)
                s = s.disjoin(args[i]);
            return s;
        });

        defBuiltin("union", args -> {
            clojure.lang.IPersistentSet result = clojure.lang.PersistentHashSet.EMPTY;
            for (Object arg : args) {
                if (arg instanceof ClojureNil) continue;
                for (clojure.lang.ISeq s = seqOf(arg); s != null; s = s.next())
                    result = (clojure.lang.IPersistentSet) result.cons(s.first());
            }
            return result;
        });

        defBuiltin("intersection", args -> {
            if (args.length == 0) return clojure.lang.PersistentHashSet.EMPTY;
            clojure.lang.IPersistentSet result = null;
            for (Object arg : args) {
                if (arg instanceof ClojureNil) return clojure.lang.PersistentHashSet.EMPTY;
                clojure.lang.IPersistentSet s;
                if (arg instanceof clojure.lang.IPersistentSet ps) s = ps;
                else {
                    java.util.List<Object> items = new ArrayList<>();
                    for (clojure.lang.ISeq sq = seqOf(arg); sq != null; sq = sq.next())
                        items.add(sq.first());
                    s = clojure.lang.PersistentHashSet.create(items);
                }
                if (result == null) { result = s; continue; }
                clojure.lang.IPersistentSet newResult = clojure.lang.PersistentHashSet.EMPTY;
                for (clojure.lang.ISeq sq = result.seq(); sq != null; sq = sq.next()) {
                    if (s.contains(sq.first()))
                        newResult = (clojure.lang.IPersistentSet) newResult.cons(sq.first());
                }
                result = newResult;
            }
            return result == null ? clojure.lang.PersistentHashSet.EMPTY : result;
        });

        defBuiltin("difference", args -> {
            if (args.length == 0) return clojure.lang.PersistentHashSet.EMPTY;
            clojure.lang.IPersistentSet result;
            if (args[0] instanceof clojure.lang.IPersistentSet ps) result = ps;
            else {
                java.util.List<Object> items = new ArrayList<>();
                for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next())
                    items.add(s.first());
                result = clojure.lang.PersistentHashSet.create(items);
            }
            for (int i = 1; i < args.length; i++) {
                if (args[i] instanceof ClojureNil) continue;
                for (clojure.lang.ISeq s = seqOf(args[i]); s != null; s = s.next())
                    result = result.disjoin(s.first());
            }
            return result;
        });

        // --- Additional core functions ---

        defBuiltin("name", args -> {
            checkArity(args, 1, "name");
            if (args[0] instanceof clojure.lang.Named n) return n.getName();
            if (args[0] instanceof String s) return s;
            throw new RuntimeException("name: not a named value: " + args[0]);
        });

        defBuiltin("namespace", args -> {
            checkArity(args, 1, "namespace");
            if (args[0] instanceof clojure.lang.Named n) {
                String ns = n.getNamespace();
                return ns == null ? ClojureNil.INSTANCE : ns;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("keyword", args -> {
            if (args.length == 1) {
                if (args[0] instanceof clojure.lang.Keyword k) return k;
                return clojure.lang.Keyword.intern(args[0].toString());
            }
            if (args.length == 2) {
                return clojure.lang.Keyword.intern(args[0].toString(), args[1].toString());
            }
            throw new RuntimeException("keyword: expected 1 or 2 args");
        });

        defBuiltin("symbol", args -> {
            if (args.length == 1) {
                if (args[0] instanceof clojure.lang.Symbol s) return s;
                return clojure.lang.Symbol.intern(args[0].toString());
            }
            if (args.length == 2) {
                return clojure.lang.Symbol.intern(args[0].toString(), args[1].toString());
            }
            throw new RuntimeException("symbol: expected 1 or 2 args");
        });

        defBuiltin("gensym", args -> {
            String prefix = args.length > 0 ? args[0].toString() : "G__";
            return clojure.lang.Symbol.intern(prefix + gensymCounter.incrementAndGet());
        });

        defBuiltin("hash-map", args -> {
            if (args.length % 2 != 0) throw new RuntimeException("hash-map: odd number of args");
            Object[] kvs = args;
            return clojure.lang.PersistentHashMap.create(kvs);
        });

        defBuiltin("hash-set", args -> {
            java.util.List<Object> items = new ArrayList<>();
            for (Object arg : args) items.add(arg);
            return clojure.lang.PersistentHashSet.create(items);
        });

        defBuiltin("sorted-map", args -> {
            if (args.length % 2 != 0) throw new RuntimeException("sorted-map: odd number of args");
            clojure.lang.PersistentTreeMap m = clojure.lang.PersistentTreeMap.EMPTY;
            for (int i = 0; i < args.length; i += 2)
                m = (clojure.lang.PersistentTreeMap) m.assoc(args[i], args[i + 1]);
            return m;
        });

        defBuiltin("into", args -> {
            checkArity(args, 2, "into");
            Object to = args[0];
            Object from = args[1];
            if (to instanceof ClojureNil) to = clojure.lang.PersistentVector.EMPTY;
            for (clojure.lang.ISeq s = seqOf(from); s != null; s = s.next()) {
                Object item = s.first();
                if (to instanceof clojure.lang.IPersistentVector v) {
                    to = v.cons(item);
                } else if (to instanceof clojure.lang.IPersistentMap m && item instanceof clojure.lang.IMapEntry me) {
                    to = m.assoc(me.key(), me.val());
                } else if (to instanceof clojure.lang.IPersistentMap m && item instanceof clojure.lang.IPersistentVector iv && iv.count() == 2) {
                    to = m.assoc(iv.nth(0), iv.nth(1));
                } else if (to instanceof clojure.lang.IPersistentSet st) {
                    to = st.cons(item);
                } else if (to instanceof clojure.lang.IPersistentCollection c) {
                    to = c.cons(item);
                } else {
                    throw new RuntimeException("into: unsupported target type: " + to.getClass().getName());
                }
            }
            return to;
        });

        defBuiltin("frequencies", args -> {
            checkArity(args, 1, "frequencies");
            java.util.Map<Object, Long> freq = new java.util.HashMap<>();
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next()) {
                Object item = s.first();
                freq.merge(item, 1L, Long::sum);
            }
            Object result = clojure.lang.PersistentHashMap.EMPTY;
            for (var e : freq.entrySet())
                result = ((clojure.lang.IPersistentMap) result).assoc(e.getKey(), e.getValue());
            return result;
        });

        defBuiltin("group-by", args -> {
            checkArity(args, 2, "group-by");
            Object fn = args[0];
            java.util.Map<Object, java.util.List<Object>> groups = new java.util.LinkedHashMap<>();
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object item = s.first();
                Object key = callFunction(fn, new Object[]{item});
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }
            Object result = clojure.lang.PersistentHashMap.EMPTY;
            for (var e : groups.entrySet())
                result = ((clojure.lang.IPersistentMap) result).assoc(
                        e.getKey(), clojure.lang.PersistentVector.create(e.getValue()));
            return result;
        });

        defBuiltin("sort", args -> {
            java.util.List<Object> items = new ArrayList<>();
            Object coll = args.length == 1 ? args[0] : args[1];
            for (clojure.lang.ISeq s = seqOf(coll); s != null; s = s.next())
                items.add(s.first());
            if (args.length == 1) {
                items.sort((a, b) -> compareNumbers(a, b));
            } else {
                Object cmp = args[0];
                items.sort((a, b) -> {
                    Object result = callFunction(cmp, new Object[]{a, b});
                    if (result instanceof Number n) return n.intValue();
                    if (result instanceof Boolean bool) return bool ? -1 : 1;
                    return 0;
                });
            }
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        defBuiltin("sort-by", args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("sort-by: expected 2 or 3 args");
            Object keyFn = args[0];
            Object coll = args.length == 2 ? args[1] : args[2];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(coll); s != null; s = s.next())
                items.add(s.first());
            items.sort((a, b) -> {
                Object ka = callFunction(keyFn, new Object[]{a});
                Object kb = callFunction(keyFn, new Object[]{b});
                return compareNumbers(ka, kb);
            });
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        defBuiltin("distinct", args -> {
            if (args.length == 0) {
                // Transducer arity
                return (BuiltinFunction) xfArgs -> {
                    checkArity(xfArgs, 1, "distinct-transducer");
                    Object rf = xfArgs[0];
                    java.util.Set<Object> seen = new java.util.HashSet<>();
                    return new TransducerRf(rf) {
                        @Override public Object step(Object acc, Object input) {
                            if (seen.add(input)) {
                                return callFunction(innerRf, new Object[]{acc, input});
                            }
                            return acc;
                        }
                    };
                };
            }
            checkArity(args, 1, "distinct");
            java.util.Set<Object> seen = new java.util.LinkedHashSet<>();
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next())
                seen.add(s.first());
            java.util.List<Object> items = new ArrayList<>(seen);
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        defBuiltin("flatten", args -> {
            checkArity(args, 1, "flatten");
            java.util.List<Object> items = new ArrayList<>();
            flattenInto(args[0], items);
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        defBuiltin("partition", args -> {
            if (args.length < 2 || args.length > 4)
                throw new RuntimeException("partition: expected 2-4 args");
            int n = ((Number) args[0]).intValue();
            int step = args.length >= 3 ? ((Number) args[1]).intValue() : n;
            Object pad = null;
            Object coll;
            if (args.length == 4) { pad = args[2]; coll = args[3]; }
            else if (args.length == 3) { coll = args[2]; }
            else { coll = args[1]; }
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(coll); s != null; s = s.next())
                items.add(s.first());
            java.util.List<Object> parts = new ArrayList<>();
            for (int i = 0; i + n <= items.size(); i += step)
                parts.add(clojure.lang.PersistentVector.create(items.subList(i, i + n)));
            // Handle pad: if there are remaining elements and pad is provided
            if (pad != null) {
                int lastStart = parts.isEmpty() ? 0 : ((parts.size()) * step);
                if (lastStart < items.size()) {
                    java.util.List<Object> last = new ArrayList<>(items.subList(lastStart, items.size()));
                    // Fill from pad
                    clojure.lang.ISeq padSeq = seqOf(pad);
                    while (last.size() < n && padSeq != null) {
                        last.add(padSeq.first());
                        padSeq = padSeq.next();
                    }
                    parts.add(clojure.lang.PersistentVector.create(last));
                }
            }
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = parts.size() - 1; i >= 0; i--) result = result.cons(parts.get(i));
            return result;
        });

        defBuiltin("partition-all", args -> {
            if (args.length < 1 || args.length > 3)
                throw new RuntimeException("partition-all: expected 1-3 args");
            if (args.length == 1) {
                // Transducer arity
                int n = ((Number) args[0]).intValue();
                return (BuiltinFunction) xfArgs -> {
                    checkArity(xfArgs, 1, "partition-all-transducer");
                    Object rf = xfArgs[0];
                    java.util.List<Object> buf = new ArrayList<>();
                    return new TransducerRf(rf) {
                        @Override public Object step(Object acc, Object input) {
                            buf.add(input);
                            if (buf.size() == n) {
                                Object chunk = clojure.lang.PersistentVector.create(new ArrayList<>(buf));
                                buf.clear();
                                return callFunction(innerRf, new Object[]{acc, chunk});
                            }
                            return acc;
                        }
                        @Override public Object execute(Object[] a) {
                            if (a.length == 1) {
                                // completion - flush remaining
                                Object acc = a[0];
                                if (!buf.isEmpty()) {
                                    Object chunk = clojure.lang.PersistentVector.create(new ArrayList<>(buf));
                                    buf.clear();
                                    acc = callFunction(innerRf, new Object[]{acc, chunk});
                                }
                                return callFunction(innerRf, new Object[]{acc});
                            }
                            return super.execute(a);
                        }
                    };
                };
            }
            int n = ((Number) args[0]).intValue();
            int step = args.length == 3 ? ((Number) args[1]).intValue() : n;
            Object coll = args.length == 2 ? args[1] : args[2];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(coll); s != null; s = s.next())
                items.add(s.first());
            java.util.List<Object> parts = new ArrayList<>();
            for (int i = 0; i < items.size(); i += step)
                parts.add(clojure.lang.PersistentVector.create(items.subList(i, Math.min(i + n, items.size()))));
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = parts.size() - 1; i >= 0; i--) result = result.cons(parts.get(i));
            return result;
        });

        defBuiltin("interleave", args -> {
            if (args.length < 2) throw new RuntimeException("interleave: expected at least 2 args");
            clojure.lang.ISeq[] seqs = new clojure.lang.ISeq[args.length];
            for (int i = 0; i < args.length; i++) seqs[i] = seqOf(args[i]);
            java.util.List<Object> items = new ArrayList<>();
            while (true) {
                boolean allHave = true;
                for (clojure.lang.ISeq s : seqs) { if (s == null) { allHave = false; break; } }
                if (!allHave) break;
                for (int i = 0; i < seqs.length; i++) {
                    items.add(seqs[i].first());
                    seqs[i] = seqs[i].next();
                }
            }
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        defBuiltin("interpose", args -> {
            checkArity(args, 2, "interpose");
            Object sep = args[0];
            java.util.List<Object> items = new ArrayList<>();
            boolean first = true;
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                if (!first) items.add(sep);
                items.add(s.first());
                first = false;
            }
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        defBuiltin("zipmap", args -> {
            checkArity(args, 2, "zipmap");
            clojure.lang.ISeq keys = seqOf(args[0]);
            clojure.lang.ISeq vals = seqOf(args[1]);
            Object result = clojure.lang.PersistentHashMap.EMPTY;
            while (keys != null && vals != null) {
                result = ((clojure.lang.IPersistentMap) result).assoc(keys.first(), vals.first());
                keys = keys.next();
                vals = vals.next();
            }
            return result;
        });

        defBuiltin("map-indexed", args -> {
            checkArity(args, 2, "map-indexed");
            Object fn = args[0];
            java.util.List<Object> items = new ArrayList<>();
            long idx = 0;
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                items.add(callFunction(fn, new Object[]{idx, s.first()}));
                idx++;
            }
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        defBuiltin("keep", args -> {
            if (args.length == 1) {
                // Transducer form: (keep f) returns a transducer fn(rf) -> rf'
                Object fn = args[0];
                return new NamedBuiltin("keep-transducer", rfArgs -> {
                    checkArity(rfArgs, 1, "keep-transducer");
                    Object rf = rfArgs[0];
                    return new NamedBuiltin("keep-xf", xfArgs -> {
                        if (xfArgs.length == 0) return callFunction(rf, new Object[0]);
                        if (xfArgs.length == 1) return callFunction(rf, new Object[]{xfArgs[0]});
                        Object result = xfArgs[0];
                        Object input = xfArgs[1];
                        Object val = callFunction(fn, new Object[]{input});
                        if (val instanceof ClojureNil || val == null) return result;
                        return callFunction(rf, new Object[]{result, val});
                    });
                });
            }
            checkArity(args, 2, "keep");
            Object fn = args[0];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object val = callFunction(fn, new Object[]{s.first()});
                if (!(val instanceof ClojureNil) && val != null) items.add(val);
            }
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        // --- Collection operations ---

        defBuiltin("update", args -> {
            if (args.length < 3) throw new RuntimeException("update: expected at least 3 args");
            if (!(args[0] instanceof clojure.lang.Associative m))
                throw new RuntimeException("update: first arg must be associative");
            Object key = args[1];
            Object fn = args[2];
            Object oldVal = m.valAt(key);
            if (oldVal == null) oldVal = ClojureNil.INSTANCE;
            Object[] fnArgs = new Object[1 + args.length - 3];
            fnArgs[0] = oldVal;
            System.arraycopy(args, 3, fnArgs, 1, args.length - 3);
            Object newVal = callFunction(fn, fnArgs);
            return m.assoc(key, newVal);
        });

        defBuiltin("update-in", args -> {
            if (args.length < 3) throw new RuntimeException("update-in: expected at least 3 args");
            return updateIn(args[0], (clojure.lang.IPersistentVector) args[1], args[2],
                    Arrays.copyOfRange(args, 3, args.length));
        });

        defBuiltin("get-in", args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("get-in: expected 2 or 3 args");
            Object m = args[0];
            clojure.lang.IPersistentVector path = (clojure.lang.IPersistentVector) args[1];
            Object notFound = args.length == 3 ? args[2] : ClojureNil.INSTANCE;
            for (int i = 0; i < path.count(); i++) {
                if (m instanceof ClojureNil) return notFound;
                if (m instanceof clojure.lang.ILookup lookup)
                    m = lookup.valAt(path.nth(i));
                else return notFound;
                if (m == null) return notFound;
            }
            return m == null ? notFound : m;
        });

        defBuiltin("assoc-in", args -> {
            checkArity(args, 3, "assoc-in");
            return assocIn(args[0], (clojure.lang.IPersistentVector) args[1], args[2]);
        });

        defBuiltin("select-keys", args -> {
            checkArity(args, 2, "select-keys");
            if (!(args[0] instanceof clojure.lang.IPersistentMap m))
                throw new RuntimeException("select-keys: first arg must be a map");
            Object result = clojure.lang.PersistentHashMap.EMPTY;
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object key = s.first();
                clojure.lang.IMapEntry entry = m.entryAt(key);
                if (entry != null)
                    result = ((clojure.lang.IPersistentMap) result).assoc(key, entry.val());
            }
            return result;
        });

        defBuiltin("merge", args -> {
            Object result = clojure.lang.PersistentHashMap.EMPTY;
            for (Object arg : args) {
                if (arg instanceof ClojureNil) continue;
                if (arg instanceof clojure.lang.IPersistentMap m) {
                    for (clojure.lang.ISeq s = m.seq(); s != null; s = s.next()) {
                        clojure.lang.IMapEntry e = (clojure.lang.IMapEntry) s.first();
                        result = ((clojure.lang.IPersistentMap) result).assoc(e.key(), e.val());
                    }
                }
            }
            return result;
        });

        defBuiltin("merge-with", args -> {
            if (args.length < 2) throw new RuntimeException("merge-with: expected at least 2 args");
            Object fn = args[0];
            clojure.lang.IPersistentMap result = clojure.lang.PersistentHashMap.EMPTY;
            for (int i = 1; i < args.length; i++) {
                if (args[i] instanceof ClojureNil) continue;
                if (args[i] instanceof clojure.lang.IPersistentMap m) {
                    for (clojure.lang.ISeq s = m.seq(); s != null; s = s.next()) {
                        clojure.lang.IMapEntry e = (clojure.lang.IMapEntry) s.first();
                        Object existing = result.valAt(e.key());
                        if (existing != null) {
                            result = result.assoc(e.key(), callFunction(fn, new Object[]{existing, e.val()}));
                        } else {
                            result = result.assoc(e.key(), e.val());
                        }
                    }
                }
            }
            return result;
        });

        // --- Type predicates ---

        defBuiltin("map?", a -> a[0] instanceof clojure.lang.IPersistentMap);
        defBuiltin("vector?", a -> a[0] instanceof clojure.lang.IPersistentVector);
        defBuiltin("list?", a -> a[0] instanceof clojure.lang.IPersistentList);
        defBuiltin("seq?", a -> a[0] instanceof clojure.lang.ISeq);
        defBuiltin("coll?", a -> a[0] instanceof clojure.lang.IPersistentCollection);
        defBuiltin("sequential?", a -> a[0] instanceof clojure.lang.Sequential);
        defBuiltin("associative?", a -> a[0] instanceof clojure.lang.Associative);
        defBuiltin("fn?", a ->
                a[0] instanceof ClojureFunction || a[0] instanceof MultiArityFunction || a[0] instanceof BuiltinFunction);
        defBuiltin("ifn?", a ->
                a[0] instanceof ClojureFunction || a[0] instanceof MultiArityFunction ||
                        a[0] instanceof BuiltinFunction || a[0] instanceof clojure.lang.Keyword ||
                        a[0] instanceof clojure.lang.IPersistentMap || a[0] instanceof clojure.lang.IPersistentSet);
        defBuiltin("number?", a -> a[0] instanceof Number);
        defBuiltin("integer?", a -> a[0] instanceof Long || a[0] instanceof Integer
                || a[0] instanceof java.math.BigInteger || a[0] instanceof clojure.lang.BigInt
                || a[0] instanceof Short || a[0] instanceof Byte);
        defBuiltin("float?", a -> a[0] instanceof Double || a[0] instanceof Float);
        defBuiltin("string?", a -> a[0] instanceof String);
        defBuiltin("keyword?", a -> a[0] instanceof clojure.lang.Keyword);
        defBuiltin("symbol?", a -> a[0] instanceof clojure.lang.Symbol);
        defBuiltin("boolean?", a -> a[0] instanceof Boolean);
        defBuiltin("true?", a -> Boolean.TRUE.equals(a[0]));
        defBuiltin("false?", a -> Boolean.FALSE.equals(a[0]));
        defBuiltin("char?", a -> a[0] instanceof Character);
        defBuiltin("ratio?", a -> a[0] instanceof clojure.lang.Ratio);

        defBuiltin("numerator", args -> {
            checkArity(args, 1, "numerator");
            if (args[0] instanceof clojure.lang.Ratio r) return r.numerator;
            throw new RuntimeException("numerator: not a ratio: " + args[0]);
        });

        defBuiltin("denominator", args -> {
            checkArity(args, 1, "denominator");
            if (args[0] instanceof clojure.lang.Ratio r) return r.denominator;
            throw new RuntimeException("denominator: not a ratio: " + args[0]);
        });

        // --- Higher order ---

        defBuiltin("juxt", args -> {
            Object[] fns = args.clone();
            return (BuiltinFunction) innerArgs -> {
                java.util.List<Object> results = new ArrayList<>();
                for (Object fn : fns) results.add(callFunction(fn, innerArgs));
                return clojure.lang.PersistentVector.create(results);
            };
        });

        defBuiltin("memoize", args -> {
            checkArity(args, 1, "memoize");
            Object fn = args[0];
            ConcurrentHashMap<Object, Object> cache = new ConcurrentHashMap<>();
            return (BuiltinFunction) innerArgs -> {
                Object key = clojure.lang.PersistentVector.create(java.util.List.of(innerArgs));
                return cache.computeIfAbsent(key, k -> callFunction(fn, innerArgs));
            };
        });

        defBuiltin("trampoline", args -> {
            if (args.length < 1) throw new RuntimeException("trampoline: expected at least 1 arg");
            Object fn = args[0];
            Object[] extraArgs = Arrays.copyOfRange(args, 1, args.length);
            Object result = callFunction(fn, extraArgs);
            while (result instanceof ClojureFunction || result instanceof MultiArityFunction ||
                    result instanceof BuiltinFunction) {
                result = callFunction(result, new Object[0]);
            }
            return result;
        });

        // --- Concurrency ---

        defBuiltin("future-call", args -> {
            checkArity(args, 1, "future-call");
            Object fn = args[0];
            // Capture current thread bindings to propagate to the new thread
            java.util.HashMap<String, Object> parentBindings = new java.util.HashMap<>(threadBindings.get());
            String parentNs = currentNamespace;
            java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors
                    .newSingleThreadExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
            java.util.concurrent.Future<Object> future = exec.submit(() -> {
                // Install parent thread's bindings in this thread
                threadBindings.get().putAll(parentBindings);
                String savedNs = currentNamespace;
                currentNamespace = parentNs;
                try {
                    return callFunction(fn, new Object[0]);
                } catch (Exception e) {
                    System.err.println("[FUTURE-ERROR] " + e.getMessage());
                    throw e;
                } finally {
                    currentNamespace = savedNs;
                }
            });
            return future;
        });

        // deref for futures (merged into final deref builtin below)

        defBuiltin("future-done?", args -> {
            checkArity(args, 1, "future-done?");
            if (args[0] instanceof java.util.concurrent.Future<?> f) return f.isDone();
            return false;
        });

        defBuiltin("future-cancel", args -> {
            checkArity(args, 1, "future-cancel");
            if (args[0] instanceof java.util.concurrent.Future<?> f) return f.cancel(true);
            return false;
        });

        // --- Misc ---

        defBuiltin("identity", args -> { checkArity(args, 1, "identity"); return args[0]; });
        defBuiltin("constantly", args -> {
            checkArity(args, 1, "constantly");
            Object val = args[0];
            return (BuiltinFunction) a -> val;
        });
        defBuiltin("complement", args -> {
            checkArity(args, 1, "complement");
            Object fn = args[0];
            return (BuiltinFunction) a -> !isTruthy(callFunction(fn, a));
        });
        defBuiltin("fnil", args -> {
            if (args.length < 2) throw new RuntimeException("fnil: expected at least 2 args");
            Object fn = args[0];
            Object[] defaults = Arrays.copyOfRange(args, 1, args.length);
            return (BuiltinFunction) a -> {
                Object[] newArgs = a.clone();
                for (int i = 0; i < Math.min(newArgs.length, defaults.length); i++) {
                    if (newArgs[i] instanceof ClojureNil) newArgs[i] = defaults[i];
                }
                return callFunction(fn, newArgs);
            };
        });

        defBuiltin("rand", args -> {
            if (args.length == 0) return Math.random();
            return Math.random() * ((Number) args[0]).doubleValue();
        });

        defBuiltin("rand-int", args -> {
            checkArity(args, 1, "rand-int");
            return (long) (Math.random() * ((Number) args[0]).longValue());
        });

        defBuiltin("rand-nth", args -> {
            checkArity(args, 1, "rand-nth");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next()) items.add(s.first());
            if (items.isEmpty()) throw new RuntimeException("rand-nth: empty collection");
            return items.get((int) (Math.random() * items.size()));
        });

        defBuiltin("shuffle", args -> {
            checkArity(args, 1, "shuffle");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next()) items.add(s.first());
            java.util.Collections.shuffle(items);
            return clojure.lang.PersistentVector.create(items);
        });

        defBuiltin("format", args -> {
            if (args.length < 1) throw new RuntimeException("format: expected at least 1 arg");
            String fmt = args[0].toString();
            Object[] fmtArgs = Arrays.copyOfRange(args, 1, args.length);
            return String.format(fmt, fmtArgs);
        });

        defBuiltin("slurp", args -> {
            checkArity(args, 1, "slurp");
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(args[0].toString()));
            } catch (java.io.IOException e) {
                throw new RuntimeException("slurp: " + e.getMessage());
            }
        });

        defBuiltin("spit", args -> {
            if (args.length < 2) throw new RuntimeException("spit: expected at least 2 args");
            boolean append = false;
            for (int i = 2; i + 1 < args.length; i += 2) {
                if (args[i] instanceof clojure.lang.Keyword kw && kw.getName().equals("append")) {
                    append = isTruthy(args[i + 1]);
                }
            }
            try {
                java.nio.file.Path path = java.nio.file.Path.of(args[0].toString());
                if (append) {
                    java.nio.file.Files.writeString(path, args[1].toString(),
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                } else {
                    java.nio.file.Files.writeString(path, args[1].toString());
                }
            } catch (java.io.IOException e) {
                throw new RuntimeException("spit: " + e.getMessage());
            }
            return ClojureNil.INSTANCE;
        });

        // --- Phase 8: Regex ---
        defBuiltin("re-pattern", args -> {
            checkArity(args, 1, "re-pattern");
            return Pattern.compile(args[0].toString());
        });

        defBuiltin("re-find", args -> {
            if (args.length == 1) {
                // (re-find matcher)
                if (!(args[0] instanceof Matcher m2))
                    throw new RuntimeException("re-find: expected a matcher");
                if (m2.find()) {
                    if (m2.groupCount() == 0) return m2.group();
                    java.util.List<Object> groups2 = new ArrayList<>();
                    groups2.add(m2.group());
                    for (int i = 1; i <= m2.groupCount(); i++)
                        groups2.add(m2.group(i) == null ? ClojureNil.INSTANCE : m2.group(i));
                    return clojure.lang.PersistentVector.create(groups2);
                }
                return ClojureNil.INSTANCE;
            }
            if (args.length != 2) throw new RuntimeException("re-find: expected 1 or 2 args");
            Pattern pat = (args[0] instanceof Pattern p) ? p : Pattern.compile(args[0].toString());
            String s = args[1].toString();
            Matcher m = pat.matcher(s);
            if (!m.find()) return ClojureNil.INSTANCE;
            if (m.groupCount() == 0) return m.group();
            // Return vector of groups
            java.util.List<Object> groups = new ArrayList<>();
            groups.add(m.group(0));
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                groups.add(g == null ? ClojureNil.INSTANCE : g);
            }
            return clojure.lang.PersistentVector.create(groups);
        });

        defBuiltin("re-matches", args -> {
            checkArity(args, 2, "re-matches");
            Pattern pat = (args[0] instanceof Pattern p) ? p : Pattern.compile(args[0].toString());
            Matcher m = pat.matcher(args[1].toString());
            if (!m.matches()) return ClojureNil.INSTANCE;
            if (m.groupCount() == 0) return m.group();
            java.util.List<Object> groups = new ArrayList<>();
            groups.add(m.group(0));
            for (int i = 1; i <= m.groupCount(); i++) {
                String g = m.group(i);
                groups.add(g == null ? ClojureNil.INSTANCE : g);
            }
            return clojure.lang.PersistentVector.create(groups);
        });

        defBuiltin("re-seq", args -> {
            checkArity(args, 2, "re-seq");
            Pattern pat = (args[0] instanceof Pattern p) ? p : Pattern.compile(args[0].toString());
            Matcher m = pat.matcher(args[1].toString());
            java.util.List<Object> results = new ArrayList<>();
            while (m.find()) {
                if (m.groupCount() == 0) {
                    results.add(m.group());
                } else {
                    java.util.List<Object> groups = new ArrayList<>();
                    groups.add(m.group(0));
                    for (int i = 1; i <= m.groupCount(); i++) {
                        String g = m.group(i);
                        groups.add(g == null ? ClojureNil.INSTANCE : g);
                    }
                    results.add(clojure.lang.PersistentVector.create(groups));
                }
            }
            if (results.isEmpty()) return ClojureNil.INSTANCE;
            return clojure.lang.PersistentList.create(results);
        });

        defBuiltin("re-matcher", args -> {
            checkArity(args, 2, "re-matcher");
            Pattern pat = (args[0] instanceof Pattern p) ? p : Pattern.compile(args[0].toString());
            return pat.matcher(args[1].toString());
        });

        // --- Phase 8: Additional seq operations ---
        defBuiltin("group-by", args -> {
            checkArity(args, 2, "group-by");
            Object f = args[0];
            Object coll = args[1];
            java.util.Map<Object, java.util.List<Object>> groups = new java.util.LinkedHashMap<>();
            for (clojure.lang.ISeq seq = seqOf(coll); seq != null; seq = seq.next()) {
                Object item = seq.first();
                Object key = callFunction(f, new Object[]{item});
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (var entry : groups.entrySet()) {
                result = result.assoc(entry.getKey(),
                        clojure.lang.PersistentVector.create(entry.getValue()));
            }
            return result;
        });

        defBuiltin("frequencies", args -> {
            checkArity(args, 1, "frequencies");
            java.util.Map<Object, Long> freqs = new java.util.LinkedHashMap<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                Object item = seq.first();
                freqs.merge(item, 1L, Long::sum);
            }
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (var entry : freqs.entrySet()) {
                result = result.assoc(entry.getKey(), entry.getValue());
            }
            return result;
        });

        defBuiltin("take-while", args -> {
            checkArity(args, 2, "take-while");
            Object pred = args[0];
            return new LazySeq(() -> {
                return lazyTakeWhile(pred, seqOf(args[1]));
            });
        });

        defBuiltin("drop-while", args -> {
            checkArity(args, 2, "drop-while");
            Object pred = args[0];
            Object coll = args[1];
            clojure.lang.ISeq seq = seqOf(coll);
            while (seq != null) {
                Object val = callFunction(pred, new Object[]{seq.first()});
                if (!isTruthy(val)) break;
                seq = seq.next();
            }
            if (seq == null) return ClojureNil.INSTANCE;
            java.util.List<Object> result = new ArrayList<>();
            while (seq != null) { result.add(seq.first()); seq = seq.next(); }
            return clojure.lang.PersistentList.create(result);
        });

        defBuiltin("every?", args -> {
            checkArity(args, 2, "every?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                if (!isTruthy(callFunction(pred, new Object[]{seq.first()}))) return false;
            }
            return true;
        });

        defBuiltin("some", args -> {
            checkArity(args, 2, "some");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                Object result = callFunction(pred, new Object[]{seq.first()});
                if (isTruthy(result)) return result;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("not-every?", args -> {
            checkArity(args, 2, "not-every?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                if (!isTruthy(callFunction(pred, new Object[]{seq.first()}))) return true;
            }
            return false;
        });

        defBuiltin("not-any?", args -> {
            checkArity(args, 2, "not-any?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                if (isTruthy(callFunction(pred, new Object[]{seq.first()}))) return false;
            }
            return true;
        });

        defBuiltin("into", args -> {
            if (args.length < 2 || args.length > 3) throw new RuntimeException("into: expected 2 or 3 args");
            Object to = args[0];
            Object from;
            if (args.length == 3) {
                // (into to xform from) - apply transducer
                Object xform = args[1];
                from = args[2];
                // Build reducing function based on target type
                BuiltinFunction rf;
                if (to instanceof clojure.lang.IPersistentVector) {
                    rf = rArgs -> ((clojure.lang.IPersistentVector) rArgs[0]).cons(rArgs[1]);
                } else if (to instanceof clojure.lang.IPersistentMap) {
                    rf = rArgs -> {
                        Object item = rArgs[1];
                        if (item instanceof clojure.lang.IPersistentVector iv && iv.count() == 2)
                            return ((clojure.lang.IPersistentMap) rArgs[0]).assoc(iv.nth(0), iv.nth(1));
                        if (item instanceof clojure.lang.MapEntry me)
                            return ((clojure.lang.IPersistentMap) rArgs[0]).assoc(me.key(), me.val());
                        if (item instanceof clojure.lang.Seqable) {
                            clojure.lang.ISeq s = seqOf(item);
                            if (s != null && s.next() != null && s.next().next() == null)
                                return ((clojure.lang.IPersistentMap) rArgs[0]).assoc(s.first(), s.next().first());
                        }
                        if (item instanceof java.util.Map.Entry<?,?> jme)
                            return ((clojure.lang.IPersistentMap) rArgs[0]).assoc(jme.getKey(), jme.getValue());
                        throw new RuntimeException("into: map expects [k v] pairs");
                    };
                } else if (to instanceof clojure.lang.IPersistentSet) {
                    rf = rArgs -> ((clojure.lang.IPersistentSet) rArgs[0]).cons(rArgs[1]);
                } else {
                    rf = rArgs -> ((clojure.lang.IPersistentCollection) rArgs[0]).cons(rArgs[1]);
                }
                // Apply transducer: xform is a function that takes rf and returns rf'
                Object xrf = callFunction(xform, new Object[]{rf});
                Object acc = to;
                if (from instanceof ClojureNil) return to;
                for (clojure.lang.ISeq seq = seqOf(from); seq != null; seq = seq.next()) {
                    acc = callFunction(xrf, new Object[]{acc, seq.first()});
                    if (acc instanceof Reduced r) { acc = r.value; break; }
                }
                return acc;
            }
            from = args[1];
            if (to == null || to instanceof ClojureNil) to = clojure.lang.PersistentList.EMPTY;
            if (from instanceof ClojureNil) return to;
            for (clojure.lang.ISeq seq = seqOf(from); seq != null; seq = seq.next()) {
                Object item = seq.first();
                if (to instanceof clojure.lang.IPersistentVector v) {
                    to = v.cons(item);
                } else if (to instanceof clojure.lang.IPersistentMap m) {
                    if (item instanceof clojure.lang.MapEntry me) {
                        to = m.assoc(me.key(), me.val());
                    } else if (item instanceof clojure.lang.IPersistentVector iv && iv.count() == 2) {
                        to = m.assoc(iv.nth(0), iv.nth(1));
                    } else if (item instanceof clojure.lang.Seqable) {
                        // Support lists, lazy-seqs as [k v] pairs
                        clojure.lang.ISeq s = seqOf(item);
                        if (s != null && s.next() != null && s.next().next() == null) {
                            to = m.assoc(s.first(), s.next().first());
                        } else {
                            throw new RuntimeException("into: map expects [k v] pairs, got: " + item);
                        }
                    } else if (item instanceof java.util.Map.Entry<?,?> jme) {
                        to = m.assoc(jme.getKey(), jme.getValue());
                    } else {
                        throw new RuntimeException("into: map expects [k v] pairs, got: " + item.getClass().getName());
                    }
                } else if (to instanceof clojure.lang.IPersistentSet s) {
                    to = s.cons(item);
                } else if (to instanceof clojure.lang.IPersistentCollection c) {
                    to = c.cons(item);
                } else {
                    throw new RuntimeException("into: unsupported target type");
                }
            }
            return to;
        });

        defBuiltin("reduce-kv", args -> {
            checkArity(args, 3, "reduce-kv");
            Object f = args[0];
            Object init = args[1];
            Object coll = args[2];
            if (coll instanceof ClojureNil) return init;
            if (coll instanceof clojure.lang.IPersistentMap m) {
                Object acc = init;
                for (clojure.lang.ISeq seq = m.seq(); seq != null; seq = seq.next()) {
                    clojure.lang.MapEntry entry = (clojure.lang.MapEntry) seq.first();
                    acc = callFunction(f, new Object[]{acc, entry.key(), entry.val()});
                }
                return acc;
            }
            if (coll instanceof clojure.lang.Indexed indexed) {
                Object acc = init;
                for (int i = 0; i < indexed.count(); i++) {
                    acc = callFunction(f, new Object[]{acc, (long) i, indexed.nth(i)});
                }
                return acc;
            }
            throw new RuntimeException("reduce-kv: not a map or vector: " + coll);
        });

        defBuiltin("take-last", args -> {
            checkArity(args, 2, "take-last");
            int n = ((Number) args[0]).intValue();
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            if (n >= items.size()) return clojure.lang.PersistentList.create(items);
            return clojure.lang.PersistentList.create(items.subList(items.size() - n, items.size()));
        });

        defBuiltin("drop-last", args -> {
            int n = args.length == 1 ? 1 : ((Number) args[0]).intValue();
            Object coll = args.length == 1 ? args[0] : args[1];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(coll); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            if (n >= items.size()) return clojure.lang.PersistentList.EMPTY;
            return clojure.lang.PersistentList.create(items.subList(0, items.size() - n));
        });

        defBuiltin("split-at", args -> {
            checkArity(args, 2, "split-at");
            int n = ((Number) args[0]).intValue();
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            int splitPt = Math.min(n, items.size());
            return clojure.lang.PersistentVector.create(
                clojure.lang.PersistentList.create(items.subList(0, splitPt)),
                clojure.lang.PersistentList.create(items.subList(splitPt, items.size())));
        });

        defBuiltin("split-with", args -> {
            checkArity(args, 2, "split-with");
            Object pred = args[0];
            java.util.List<Object> before = new ArrayList<>();
            java.util.List<Object> after = new ArrayList<>();
            boolean splitting = true;
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                Object item = seq.first();
                if (splitting && isTruthy(callFunction(pred, new Object[]{item}))) {
                    before.add(item);
                } else {
                    splitting = false;
                    after.add(item);
                }
            }
            return clojure.lang.PersistentVector.create(
                clojure.lang.PersistentList.create(before),
                clojure.lang.PersistentList.create(after));
        });

        defBuiltin("partition-by", args -> {
            checkArity(args, 2, "partition-by");
            Object f = args[0];
            java.util.List<Object> result = new ArrayList<>();
            java.util.List<Object> current = new ArrayList<>();
            Object lastVal = new Object(); // sentinel
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                Object item = seq.first();
                Object val = callFunction(f, new Object[]{item});
                if (lastVal != val && !lastVal.equals(val) && !current.isEmpty()) {
                    result.add(clojure.lang.PersistentList.create(new ArrayList<>(current)));
                    current.clear();
                }
                current.add(item);
                lastVal = val;
            }
            if (!current.isEmpty()) {
                result.add(clojure.lang.PersistentList.create(current));
            }
            return clojure.lang.PersistentList.create(result);
        });

        // --- Phase 8: Metadata ---
        defBuiltin("meta", args -> {
            checkArity(args, 1, "meta");
            if (args[0] instanceof ClojureAtom atom) {
                Object m = atom.getMeta();
                return m == null ? ClojureNil.INSTANCE : m;
            }
            if (args[0] instanceof clojure.lang.IMeta obj) {
                clojure.lang.IPersistentMap m = obj.meta();
                return m == null ? ClojureNil.INSTANCE : m;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("with-meta", args -> {
            checkArity(args, 2, "with-meta");
            clojure.lang.IPersistentMap m;
            if (args[1] == null || args[1] instanceof ClojureNil) {
                m = null;
            } else if (args[1] instanceof clojure.lang.IPersistentMap pm) {
                m = pm;
            } else {
                throw new RuntimeException("with-meta: metadata must be a map, got: " + args[1].getClass().getName());
            }
            if (args[0] instanceof clojure.lang.IObj obj) {
                return obj.withMeta(m);
            }
            // For non-IObj types, return as-is (best effort)
            return args[0];
        });

        defBuiltin("vary-meta", args -> {
            if (args.length < 2) throw new RuntimeException("vary-meta: expected at least 2 args");
            if (!(args[0] instanceof clojure.lang.IObj obj))
                throw new RuntimeException("vary-meta: object does not support metadata");
            Object f = args[1];
            clojure.lang.IPersistentMap curMeta = obj.meta();
            if (curMeta == null) curMeta = clojure.lang.PersistentArrayMap.EMPTY;
            Object[] fArgs = new Object[args.length - 1];
            fArgs[0] = curMeta;
            System.arraycopy(args, 2, fArgs, 1, args.length - 2);
            Object newMeta = callFunction(f, fArgs);
            return obj.withMeta((clojure.lang.IPersistentMap) newMeta);
        });

        // --- Phase 8: ex-info / ex-data ---
        defBuiltin("ex-info", args -> {
            if (args.length < 2) throw new RuntimeException("ex-info: expected 2-3 args");
            String msg = args[0].toString();
            clojure.lang.IPersistentMap data = (clojure.lang.IPersistentMap) args[1];
            Throwable cause = args.length > 2 && args[2] instanceof Throwable t ? t : null;
            return new clojure.lang.ExceptionInfo(msg, data, cause);
        });

        defBuiltin("ex-data", args -> {
            checkArity(args, 1, "ex-data");
            if (args[0] instanceof clojure.lang.IExceptionInfo ei) {
                return ei.getData();
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("ex-message", args -> {
            checkArity(args, 1, "ex-message");
            if (args[0] instanceof Throwable t) {
                String msg = t.getMessage();
                return msg == null ? ClojureNil.INSTANCE : msg;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("ex-cause", args -> {
            checkArity(args, 1, "ex-cause");
            if (args[0] instanceof Throwable t) {
                Throwable cause = t.getCause();
                return cause == null ? ClojureNil.INSTANCE : cause;
            }
            return ClojureNil.INSTANCE;
        });

        // --- Phase 8: String operations (clojure.string equivalents as builtins) ---
        defBuiltin("str/split", args -> {
            if (args.length < 2) throw new RuntimeException("str/split: expected 2-3 args");
            String s = args[0].toString();
            Pattern pat = (args[1] instanceof Pattern p) ? p : Pattern.compile(args[1].toString());
            String[] parts = args.length > 2
                    ? pat.split(s, ((Number) args[2]).intValue())
                    : pat.split(s);
            return clojure.lang.PersistentVector.create((Object[]) parts);
        });

        defBuiltin("str/join", args -> {
            if (args.length == 1) {
                // (str/join coll)
                StringBuilder sb = new StringBuilder();
                for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                    sb.append(printString(seq.first(), false));
                }
                return sb.toString();
            }
            // (str/join sep coll)
            String sep = args[0].toString();
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                if (!first) sb.append(sep);
                sb.append(printString(seq.first(), false));
                first = false;
            }
            return sb.toString();
        });

        defBuiltin("str/trim", args -> {
            checkArity(args, 1, "str/trim");
            return args[0].toString().trim();
        });

        defBuiltin("str/triml", args -> {
            checkArity(args, 1, "str/triml");
            return args[0].toString().stripLeading();
        });

        defBuiltin("str/trimr", args -> {
            checkArity(args, 1, "str/trimr");
            return args[0].toString().stripTrailing();
        });

        defBuiltin("str/capitalize", args -> {
            checkArity(args, 1, "str/capitalize");
            String s = args[0].toString();
            if (s.isEmpty()) return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
        });

        defBuiltin("str/upper-case", args -> {
            checkArity(args, 1, "str/upper-case");
            return args[0].toString().toUpperCase();
        });

        defBuiltin("str/lower-case", args -> {
            checkArity(args, 1, "str/lower-case");
            return args[0].toString().toLowerCase();
        });

        defBuiltin("str/replace", args -> {
            checkArity(args, 3, "str/replace");
            String s = args[0].toString();
            if (args[1] instanceof Pattern pat) {
                return pat.matcher(s).replaceAll(args[2].toString());
            }
            return s.replace(args[1].toString(), args[2].toString());
        });

        defBuiltin("str/replace-first", args -> {
            checkArity(args, 3, "str/replace-first");
            String s = args[0].toString();
            if (args[1] instanceof Pattern pat) {
                return pat.matcher(s).replaceFirst(args[2].toString());
            }
            return s.replaceFirst(Pattern.quote(args[1].toString()), args[2].toString());
        });

        defBuiltin("str/starts-with?", args -> {
            checkArity(args, 2, "str/starts-with?");
            return args[0].toString().startsWith(args[1].toString());
        });

        defBuiltin("str/ends-with?", args -> {
            checkArity(args, 2, "str/ends-with?");
            return args[0].toString().endsWith(args[1].toString());
        });

        defBuiltin("str/includes?", args -> {
            checkArity(args, 2, "str/includes?");
            return args[0].toString().contains(args[1].toString());
        });

        defBuiltin("str/blank?", args -> {
            checkArity(args, 1, "str/blank?");
            Object o = args[0];
            if (o instanceof ClojureNil) return true;
            return o.toString().isBlank();
        });

        defBuiltin("str/index-of", args -> {
            if (args.length < 2) throw new RuntimeException("str/index-of: expected 2-3 args");
            String s = args[0].toString();
            String target = args[1].toString();
            int idx = args.length > 2
                    ? s.indexOf(target, ((Number) args[2]).intValue())
                    : s.indexOf(target);
            return idx < 0 ? ClojureNil.INSTANCE : (Object) (long) idx;
        });

        defBuiltin("str/last-index-of", args -> {
            if (args.length < 2) throw new RuntimeException("str/last-index-of: expected 2-3 args");
            String s = args[0].toString();
            String target = args[1].toString();
            int idx = args.length > 2
                    ? s.lastIndexOf(target, ((Number) args[2]).intValue())
                    : s.lastIndexOf(target);
            return idx < 0 ? ClojureNil.INSTANCE : (Object) (long) idx;
        });

        defBuiltin("str/reverse", args -> {
            checkArity(args, 1, "str/reverse");
            return new StringBuilder(args[0].toString()).reverse().toString();
        });

        defBuiltin("str/escape", args -> {
            checkArity(args, 2, "str/escape");
            String s = args[0].toString();
            Object cmap = args[1];
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                Object replacement = clojure.truffle.runtime.ClojureRT.get(cmap, c);
                if (replacement != null) {
                    sb.append(replacement);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        });
        globalVars.put("clojure.string/escape", globalVars.get("str/escape"));

        defBuiltin("subs", args -> {
            if (args.length < 2) throw new RuntimeException("subs: expected 2-3 args");
            String s = args[0].toString();
            int start = ((Number) args[1]).intValue();
            if (args.length > 2) {
                return s.substring(start, ((Number) args[2]).intValue());
            }
            return s.substring(start);
        });

        defBuiltin("char", args -> {
            checkArity(args, 1, "char");
            if (args[0] instanceof Number n) return (char) n.intValue();
            if (args[0] instanceof Character c) return c;
            if (args[0] instanceof String s && s.length() == 1) return s.charAt(0);
            throw new RuntimeException("char: cannot convert " + args[0]);
        });

        defBuiltin("int", args -> {
            checkArity(args, 1, "int");
            if (args[0] instanceof Number n) return (long) n.intValue();
            if (args[0] instanceof Character c) return (long) (int) c;
            throw new RuntimeException("int: cannot convert " + args[0]);
        });

        // --- Phase 8: Set operations ---
        defBuiltin("set", args -> {
            checkArity(args, 1, "set");
            if (args[0] instanceof ClojureNil) return clojure.lang.PersistentHashSet.EMPTY;
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            return clojure.lang.PersistentHashSet.create(items);
        });

        defBuiltin("set?", args -> {
            checkArity(args, 1, "set?");
            return args[0] instanceof clojure.lang.IPersistentSet;
        });

        defBuiltin("contains?", args -> {
            checkArity(args, 2, "contains?");
            Object coll = args[0];
            Object key = args[1];
            if (coll instanceof clojure.lang.IPersistentSet s) return s.contains(key);
            if (coll instanceof clojure.lang.IPersistentMap m) return m.containsKey(key);
            if (coll instanceof clojure.lang.Indexed indexed) {
                int idx = ((Number) key).intValue();
                return idx >= 0 && idx < indexed.count();
            }
            return false;
        });

        defBuiltin("disj", args -> {
            if (args.length < 2) throw new RuntimeException("disj: expected at least 2 args");
            if (args[0] == null || args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            clojure.lang.IPersistentSet s = (clojure.lang.IPersistentSet) args[0];
            for (int i = 1; i < args.length; i++) {
                s = s.disjoin(args[i]);
            }
            return s;
        });

        // --- Phase 8: Misc ---
        defBuiltin("name", args -> {
            checkArity(args, 1, "name");
            if (args[0] instanceof clojure.lang.Named named) return named.getName();
            if (args[0] instanceof String s) return s;
            throw new RuntimeException("name: not a Named: " + args[0]);
        });

        defBuiltin("namespace", args -> {
            checkArity(args, 1, "namespace");
            if (args[0] instanceof clojure.lang.Named named) {
                String ns = named.getNamespace();
                return ns == null ? ClojureNil.INSTANCE : ns;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("symbol", args -> {
            if (args.length == 1) return clojure.lang.Symbol.intern(args[0].toString());
            if (args.length == 2) return clojure.lang.Symbol.intern(args[0].toString(), args[1].toString());
            throw new RuntimeException("symbol: expected 1-2 args");
        });

        defBuiltin("keyword", args -> {
            if (args.length == 1) {
                if (args[0] instanceof clojure.lang.Keyword k) return k;
                return clojure.lang.Keyword.intern(args[0].toString());
            }
            if (args.length == 2) return clojure.lang.Keyword.intern(args[0].toString(), args[1].toString());
            throw new RuntimeException("keyword: expected 1-2 args");
        });

        defBuiltin("gensym", args -> {
            String prefix = args.length > 0 ? args[0].toString() : "G__";
            return clojure.lang.Symbol.intern(prefix + gensymCounter.incrementAndGet());
        });

        defBuiltin("hash", args -> {
            checkArity(args, 1, "hash");
            if (args[0] instanceof ClojureNil) return 0L;
            return (long) args[0].hashCode();
        });

        defBuiltin("compare", args -> {
            checkArity(args, 2, "compare");
            @SuppressWarnings("unchecked")
            Comparable<Object> a = (Comparable<Object>) args[0];
            return (long) a.compareTo(args[1]);
        });

        defBuiltin("type", args -> {
            checkArity(args, 1, "type");
            if (args[0] == null || args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            // Check :type metadata first (standard Clojure behavior)
            if (args[0] instanceof clojure.lang.IMeta imeta) {
                clojure.lang.IPersistentMap m = imeta.meta();
                if (m != null) {
                    Object t = m.valAt(clojure.lang.Keyword.intern("type"));
                    if (t != null) return t;
                }
            }
            // For deftype instances, return the type name string (matches defmethod dispatch values)
            if (args[0] instanceof ClojureDeftypeInstance dti) return dti.getTypeName();
            return args[0].getClass();
        });

        defBuiltin("class", args -> {
            checkArity(args, 1, "class");
            if (args[0] == null || args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (args[0] instanceof ClojureDeftypeInstance dti) return dti.getTypeName();
            return args[0].getClass();
        });

        defBuiltin("munge", args -> {
            checkArity(args, 1, "munge");
            return clojure.truffle.runtime.ClojureRT.munge(args[0].toString());
        });

        defBuiltin("demunge", args -> {
            checkArity(args, 1, "demunge");
            return clojure.truffle.runtime.ClojureRT.demunge(args[0].toString());
        });

        defBuiltin("instance?", args -> {
            checkArity(args, 2, "instance?");
            if (args[0] instanceof ClojureProtocol proto) {
                if (args[1] instanceof ClojureNil) return false;
                return proto.hasImplementation(args[1]);
            }
            Class<?> clazz;
            if (args[0] instanceof Class<?> c) {
                clazz = c;
            } else if (args[0] instanceof String className) {
                // Check if it's a deftype name
                if (args[1] instanceof ClojureDeftypeInstance dti) {
                    return className.equals(dti.getTypeName());
                }
                try { clazz = Class.forName(className); }
                catch (ClassNotFoundException e) {
                    // deftype names that aren't the instance type → false
                    return false;
                }
            } else {
                throw new RuntimeException("instance?: first arg must be a Class, got " + args[0].getClass().getName());
            }
            if (args[1] instanceof ClojureNil) return false;
            // ClojureAtom should be recognized as clojure.lang.Atom
            if (clazz == clojure.lang.Atom.class && args[1] instanceof ClojureAtom) return true;
            return clazz.isInstance(args[1]);
        });

        defBuiltin("supers", args -> {
            checkArity(args, 1, "supers");
            if (!(args[0] instanceof Class<?> clazz))
                throw new RuntimeException("supers: expected a Class");
            java.util.Set<Class<?>> supers = new java.util.HashSet<>();
            for (Class<?> c = clazz.getSuperclass(); c != null; c = c.getSuperclass()) {
                supers.add(c);
            }
            for (Class<?> iface : clojure_allInterfaces(clazz)) {
                supers.add(iface);
            }
            return clojure.lang.PersistentHashSet.create(new ArrayList<Object>(supers));
        });

        defBuiltin("min-key", args -> {
            if (args.length < 2) throw new RuntimeException("min-key: expected at least 2 args");
            Object k = args[0];
            Object best = args[1];
            Object bestVal = callFunction(k, new Object[]{best});
            for (int i = 2; i < args.length; i++) {
                Object val = callFunction(k, new Object[]{args[i]});
                @SuppressWarnings("unchecked")
                int cmp = ((Comparable<Object>) val).compareTo(bestVal);
                if (cmp < 0) { best = args[i]; bestVal = val; }
            }
            return best;
        });

        defBuiltin("max-key", args -> {
            if (args.length < 2) throw new RuntimeException("max-key: expected at least 2 args");
            Object k = args[0];
            Object best = args[1];
            Object bestVal = callFunction(k, new Object[]{best});
            for (int i = 2; i < args.length; i++) {
                Object val = callFunction(k, new Object[]{args[i]});
                @SuppressWarnings("unchecked")
                int cmp = ((Comparable<Object>) val).compareTo(bestVal);
                if (cmp > 0) { best = args[i]; bestVal = val; }
            }
            return best;
        });

        defBuiltin("repeatedly", args -> {
            if (args.length == 1) {
                // (repeatedly f) - infinite lazy seq
                Object f = args[0];
                return new LazySeq(() -> {
                    Object val = callFunction(f, new Object[0]);
                    return new clojure.lang.Cons(val, (clojure.lang.ISeq) ((BuiltinFunction) globalVars.get("repeatedly")).execute(new Object[]{f}));
                });
            }
            checkArity(args, 2, "repeatedly");
            int n = ((Number) args[0]).intValue();
            Object f = args[1];
            java.util.List<Object> result = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                result.add(callFunction(f, new Object[0]));
            }
            return clojure.lang.PersistentList.create(result);
        });

        defBuiltin("run!", args -> {
            checkArity(args, 2, "run!");
            Object f = args[0];
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                callFunction(f, new Object[]{seq.first()});
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("mapv", args -> {
            if (args.length < 2) throw new RuntimeException("mapv: expected at least 2 args");
            Object f = args[0];
            java.util.List<Object> result = new ArrayList<>();
            if (args.length == 2) {
                for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                    result.add(callFunction(f, new Object[]{seq.first()}));
                }
            } else {
                // Multi-coll mapv
                clojure.lang.ISeq[] seqs = new clojure.lang.ISeq[args.length - 1];
                for (int i = 1; i < args.length; i++) seqs[i-1] = seqOf(args[i]);
                while (true) {
                    Object[] fArgs = new Object[seqs.length];
                    boolean done = false;
                    for (int i = 0; i < seqs.length; i++) {
                        if (seqs[i] == null) { done = true; break; }
                        fArgs[i] = seqs[i].first();
                        seqs[i] = seqs[i].next();
                    }
                    if (done) break;
                    result.add(callFunction(f, fArgs));
                }
            }
            return clojure.lang.PersistentVector.create(result);
        });

        defBuiltin("filterv", args -> {
            checkArity(args, 2, "filterv");
            Object pred = args[0];
            java.util.List<Object> result = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                Object item = seq.first();
                if (isTruthy(callFunction(pred, new Object[]{item}))) result.add(item);
            }
            return clojure.lang.PersistentVector.create(result);
        });

        // --- Phase 9: Volatile ---
        defBuiltin("volatile!", args -> {
            checkArity(args, 1, "volatile!");
            return new ClojureVolatile(args[0]);
        });

        defBuiltin("vreset!", args -> {
            checkArity(args, 2, "vreset!");
            return ((ClojureVolatile) args[0]).reset(args[1]);
        });

        defBuiltin("vswap!", args -> {
            if (args.length < 2) throw new RuntimeException("vswap!: expected at least 2 args");
            ClojureVolatile vol = (ClojureVolatile) args[0];
            Object f = args[1];
            Object[] fArgs = new Object[args.length - 1];
            fArgs[0] = vol.deref();
            System.arraycopy(args, 2, fArgs, 1, args.length - 2);
            Object newVal = callFunction(f, fArgs);
            return vol.reset(newVal);
        });

        defBuiltin("volatile?", args -> {
            checkArity(args, 1, "volatile?");
            return args[0] instanceof ClojureVolatile;
        });

        // --- Phase 9: Promise/Deliver ---
        defBuiltin("promise", args -> new ClojurePromise());

        defBuiltin("deliver", args -> {
            checkArity(args, 2, "deliver");
            ClojurePromise p = (ClojurePromise) args[0];
            p.deliver(args[1]);
            return p;
        });

        defBuiltin("realized?", args -> {
            checkArity(args, 1, "realized?");
            if (args[0] instanceof ClojurePromise p) return p.isRealized();
            if (args[0] instanceof LazySeq ls) return ls.isRealized();
            return false;
        });

        // Update deref to handle promises and volatiles
        defBuiltin("deref", args -> {
            if (args.length < 1) throw new RuntimeException("deref: expected 1-3 args");
            Object ref = args[0];
            if (ref instanceof ClojureAtom atom) return atom.deref();
            if (ref instanceof ClojureVolatile vol) return vol.deref();
            if (ref instanceof clojure.truffle.runtime.ClojureAgent ag) return ag.deref();
            if (ref instanceof clojure.truffle.runtime.ClojureRef r) return r.deref();
            if (ref instanceof ClojurePromise p) {
                if (args.length == 3) {
                    long timeout = ((Number) args[1]).longValue();
                    return p.deref(timeout, args[2]);
                }
                return p.deref();
            }
            if (ref instanceof java.util.concurrent.Future<?> fut) {
                try {
                    if (args.length == 3) {
                        long timeout = ((Number) args[1]).longValue();
                        return fut.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                    return fut.get();
                } catch (Exception e) {
                    throw new RuntimeException("deref: " + e.getMessage());
                }
            }
            if (ref instanceof clojure.truffle.runtime.ClojureVar v) return v.deref();
            // IBlockingDeref supports timed deref (3-arg)
            if (args.length == 3 && ref instanceof clojure.lang.IBlockingDeref bd) {
                long timeout = ((Number) args[1]).longValue();
                Object result = bd.deref(timeout, args[2]);
                return result == null ? ClojureNil.INSTANCE : result;
            }
            if (ref instanceof clojure.lang.IDeref d) {
                Object result = d.deref();
                return result == null ? ClojureNil.INSTANCE : result;
            }
            // deftype/defrecord instances implementing IDeref protocol via method map
            if (ref instanceof clojure.truffle.runtime.ClojureDeftypeInstance dt) {
                Object derefMethod = dt.getMethod("deref");
                if (derefMethod != null) {
                    try {
                        if (args.length == 3) {
                            return callFunction(derefMethod, new Object[]{ref, args[1], args[2]});
                        }
                        return callFunction(derefMethod, new Object[]{ref});
                    } catch (RuntimeException e) {
                        if (e.getMessage() != null && e.getMessage().contains("Wrong number of args")) {
                            // Try the other arity — deftype may have compiled IBlockingDeref (3-arg)
                            // instead of IDeref (1-arg)
                            if (args.length == 1) {
                                // Try with default timeout
                                return callFunction(derefMethod, new Object[]{ref, Long.valueOf(60000L), ClojureNil.INSTANCE});
                            }
                        }
                        throw e;
                    }
                }
            }
            throw new RuntimeException("deref: not a derefable: " + ref);
        });

        // --- Phase 9: Atom watchers & validators ---
        defBuiltin("add-watch", args -> {
            checkArity(args, 3, "add-watch");
            if (args[0] instanceof ClojureAtom atom) {
                atom.addWatch(args[1], args[2]);
                return atom;
            }
            // For Vars and other reference types, silently accept (no-op)
            return args[0];
        });

        defBuiltin("remove-watch", args -> {
            checkArity(args, 2, "remove-watch");
            if (!(args[0] instanceof ClojureAtom)) return args[0];
            ClojureAtom atom = (ClojureAtom) args[0];
            atom.removeWatch(args[1]);
            return atom;
        });

        defBuiltin("set-validator!", args -> {
            checkArity(args, 2, "set-validator!");
            ClojureAtom atom = (ClojureAtom) args[0];
            atom.setValidator(args[1]);
            return ClojureNil.INSTANCE;
        });

        defBuiltin("get-validator", args -> {
            checkArity(args, 1, "get-validator");
            ClojureAtom atom = (ClojureAtom) args[0];
            Object v = atom.getValidator();
            return v == null ? ClojureNil.INSTANCE : v;
        });

        // Override swap! to support watchers
        defBuiltin("swap!", args -> {
            if (args.length < 2) throw new RuntimeException("swap!: expected at least 2 args");
            ClojureAtom atom = (ClojureAtom) args[0];
            Object f = args[1];
            while (true) {
                Object oldVal = atom.deref();
                Object[] fArgs = new Object[args.length - 1];
                fArgs[0] = oldVal;
                System.arraycopy(args, 2, fArgs, 1, args.length - 2);
                Object newVal = callFunction(f, fArgs);
                // Validate
                Object validator = atom.getValidator();
                if (validator != null) {
                    if (!isTruthy(callFunction(validator, new Object[]{newVal}))) {
                        throw new RuntimeException("Invalid reference state");
                    }
                }
                if (atom.compareAndSet(oldVal, newVal)) {
                    // Notify watches
                    atom.getWatches().forEach((key, watchFn) -> {
                        callFunction(watchFn, new Object[]{key, atom, oldVal, newVal});
                    });
                    return newVal;
                }
            }
        });

        // Override reset! to support watchers
        defBuiltin("reset!", args -> {
            checkArity(args, 2, "reset!");
            ClojureAtom atom = (ClojureAtom) args[0];
            Object newVal = args[1];
            Object validator = atom.getValidator();
            if (validator != null) {
                if (!isTruthy(callFunction(validator, new Object[]{newVal}))) {
                    throw new RuntimeException("Invalid reference state");
                }
            }
            Object oldVal = atom.deref();
            atom.reset(newVal);
            atom.getWatches().forEach((key, watchFn) -> {
                callFunction(watchFn, new Object[]{key, atom, oldVal, newVal});
            });
            return newVal;
        });

        // --- Phase 9: Transducers ---
        defBuiltin("transduce", args -> {
            if (args.length < 3 || args.length > 4)
                throw new RuntimeException("transduce: expected 3-4 args");
            Object xform = args[0];
            Object f = args[1];
            Object init;
            Object coll;
            if (args.length == 4) {
                init = args[2];
                coll = args[3];
            } else {
                // Call f with no args for init
                init = callFunction(f, new Object[0]);
                coll = args[2];
            }
            // Apply xform to f to get the reducing function
            Object xf = callFunction(xform, new Object[]{f});
            Object acc = init;
            for (clojure.lang.ISeq seq = seqOf(coll); seq != null; seq = seq.next()) {
                acc = callFunction(xf, new Object[]{acc, seq.first()});
                // Check for reduced
                if (acc instanceof Reduced r) {
                    acc = r.value;
                    break;
                }
            }
            // Completion step
            return callFunction(xf, new Object[]{acc});
        });

        // map as transducer (1-arity)
        // We modify 'map' to return a transducer when called with 1 arg
        Object origMap = globalVars.get("map");
        defBuiltin("map", args -> {
            if (args.length == 1) {
                // Return a transducer
                Object f = args[0];
                return (BuiltinFunction) xfArgs -> {
                    checkArity(xfArgs, 1, "map-transducer");
                    Object rf = xfArgs[0];
                    return new TransducerRf(rf) {
                        @Override public Object step(Object acc, Object input) {
                            return callFunction(rf, new Object[]{acc, callFunction(f, new Object[]{input})});
                        }
                    };
                };
            }
            // Original map behavior
            return ((BuiltinFunction) origMap).execute(args);
        });

        // filter as transducer
        Object origFilter = globalVars.get("filter");
        defBuiltin("filter", args -> {
            if (args.length == 1) {
                Object pred = args[0];
                return (BuiltinFunction) xfArgs -> {
                    checkArity(xfArgs, 1, "filter-transducer");
                    Object rf = xfArgs[0];
                    return new TransducerRf(rf) {
                        @Override public Object step(Object acc, Object input) {
                            if (isTruthy(callFunction(pred, new Object[]{input}))) {
                                return callFunction(rf, new Object[]{acc, input});
                            }
                            return acc;
                        }
                    };
                };
            }
            return ((BuiltinFunction) origFilter).execute(args);
        });

        // take as transducer
        Object origTake = globalVars.get("take");
        defBuiltin("take", args -> {
            if (args.length == 1 && args[0] instanceof Number) {
                int n = ((Number) args[0]).intValue();
                return (BuiltinFunction) xfArgs -> {
                    checkArity(xfArgs, 1, "take-transducer");
                    Object rf = xfArgs[0];
                    int[] count = {0};
                    return new TransducerRf(rf) {
                        @Override public Object step(Object acc, Object input) {
                            if (count[0]++ < n) {
                                return callFunction(rf, new Object[]{acc, input});
                            }
                            return new Reduced(acc);
                        }
                    };
                };
            }
            return ((BuiltinFunction) origTake).execute(args);
        });

        defBuiltin("reduced", args -> {
            checkArity(args, 1, "reduced");
            return new Reduced(args[0]);
        });

        defBuiltin("reduced?", args -> {
            checkArity(args, 1, "reduced?");
            return args[0] instanceof Reduced;
        });

        defBuiltin("unreduced", args -> {
            checkArity(args, 1, "unreduced");
            if (args[0] instanceof Reduced r) return r.value;
            return args[0];
        });

        defBuiltin("ensure-reduced", args -> {
            checkArity(args, 1, "ensure-reduced");
            if (args[0] instanceof Reduced) return args[0];
            return new Reduced(args[0]);
        });

        // comp for function/transducer composition
        defBuiltin("comp", args -> {
            if (args.length == 0) return (BuiltinFunction) a -> a[0];
            if (args.length == 1) return args[0];
            Object[] fns = args.clone();
            return (BuiltinFunction) innerArgs -> {
                // Apply last function first
                Object result = callFunction(fns[fns.length - 1], innerArgs);
                for (int i = fns.length - 2; i >= 0; i--) {
                    result = callFunction(fns[i], new Object[]{result});
                }
                return result;
            };
        });

        // partial
        defBuiltin("partial", args -> {
            if (args.length < 1) throw new RuntimeException("partial: expected at least 1 arg");
            Object f = args[0];
            Object[] partialArgs = Arrays.copyOfRange(args, 1, args.length);
            return (BuiltinFunction) moreArgs -> {
                Object[] allArgs = new Object[partialArgs.length + moreArgs.length];
                System.arraycopy(partialArgs, 0, allArgs, 0, partialArgs.length);
                System.arraycopy(moreArgs, 0, allArgs, partialArgs.length, moreArgs.length);
                return callFunction(f, allArgs);
            };
        });

        // --- Phase 9: Hierarchy for multimethods ---
        defBuiltin("make-hierarchy", args ->
                clojure.lang.PersistentArrayMap.EMPTY
                        .assoc(clojure.lang.Keyword.intern("parents"),
                                clojure.lang.PersistentArrayMap.EMPTY)
                        .assoc(clojure.lang.Keyword.intern("ancestors"),
                                clojure.lang.PersistentArrayMap.EMPTY)
                        .assoc(clojure.lang.Keyword.intern("descendants"),
                                clojure.lang.PersistentArrayMap.EMPTY));

        // Global hierarchy
        globalVars.put("*hierarchy*", ((BuiltinFunction) globalVars.get("make-hierarchy")).execute(new Object[0]));

        defBuiltin("derive", args -> {
            if (args.length != 2 && args.length != 3)
                throw new RuntimeException("derive: expected 2 or 3 args");
            boolean custom = args.length == 3;
            clojure.lang.IPersistentMap h;
            Object tag, parent;
            if (custom) {
                h = (clojure.lang.IPersistentMap) args[0];
                tag = args[1]; parent = args[2];
            } else {
                tag = args[0]; parent = args[1];
                h = (clojure.lang.IPersistentMap) globalVars.get("*hierarchy*");
            }
            clojure.lang.Keyword kParents = clojure.lang.Keyword.intern("parents");
            clojure.lang.Keyword kAncestors = clojure.lang.Keyword.intern("ancestors");
            clojure.lang.Keyword kDescendants = clojure.lang.Keyword.intern("descendants");

            // Update parents
            clojure.lang.IPersistentMap parents = (clojure.lang.IPersistentMap) h.valAt(kParents);
            Object tagParents = parents.valAt(tag);
            clojure.lang.IPersistentSet newTagParents;
            if (tagParents instanceof clojure.lang.IPersistentSet s) {
                newTagParents = (clojure.lang.IPersistentSet) s.cons(parent);
            } else {
                newTagParents = clojure.lang.PersistentHashSet.create(java.util.List.of(parent));
            }
            h = h.assoc(kParents, parents.assoc(tag, newTagParents));

            // Update ancestors (tag -> all ancestors including parent's ancestors)
            clojure.lang.IPersistentMap ancestors = (clojure.lang.IPersistentMap) h.valAt(kAncestors);
            java.util.Set<Object> tagAnc = new java.util.HashSet<>();
            tagAnc.add(parent);
            Object parentAnc = ancestors.valAt(parent);
            if (parentAnc instanceof clojure.lang.IPersistentSet ps) {
                for (clojure.lang.ISeq s = ps.seq(); s != null; s = s.next()) tagAnc.add(s.first());
            }
            h = h.assoc(kAncestors, ancestors.assoc(tag,
                    clojure.lang.PersistentHashSet.create(new ArrayList<>(tagAnc))));

            // Update descendants (parent and all ancestors of parent get tag as descendant)
            clojure.lang.IPersistentMap descs = (clojure.lang.IPersistentMap) h.valAt(kDescendants);
            // Collect all nodes that should get tag as descendant: parent + parent's ancestors
            java.util.Set<Object> toUpdate = new java.util.HashSet<>();
            toUpdate.add(parent);
            toUpdate.addAll(tagAnc); // tagAnc includes parent and all parent's ancestors
            for (Object ancestor : toUpdate) {
                Object ancDescs = descs.valAt(ancestor);
                clojure.lang.IPersistentSet newDescs;
                if (ancDescs instanceof clojure.lang.IPersistentSet s) {
                    newDescs = (clojure.lang.IPersistentSet) s.cons(tag);
                } else {
                    newDescs = clojure.lang.PersistentHashSet.create(java.util.List.of(tag));
                }
                descs = descs.assoc(ancestor, newDescs);
            }
            h = h.assoc(kDescendants, descs);

            if (custom) return h;
            globalVars.put("*hierarchy*", h);
            return ClojureNil.INSTANCE;
        });

        defBuiltin("isa?", args -> {
            if (args.length < 2 || args.length > 3) throw new RuntimeException("isa?: expected 2 or 3 args");
            Object child, parent;
            Object hier;
            if (args.length == 3) { hier = args[0]; child = args[1]; parent = args[2]; }
            else { child = args[0]; parent = args[1]; hier = globalVars.get("*hierarchy*"); }
            if (child.equals(parent)) return true;
            if (hier instanceof clojure.lang.IPersistentMap h) {
                Object ancestors = ((clojure.lang.IPersistentMap) h.valAt(
                        clojure.lang.Keyword.intern("ancestors"))).valAt(child);
                if (ancestors instanceof clojure.lang.IPersistentSet s) {
                    return s.contains(parent);
                }
            }
            // Java class hierarchy
            if (child instanceof Class<?> cc && parent instanceof Class<?> pc) {
                return pc.isAssignableFrom(cc);
            }
            return false;
        });

        defBuiltin("parents", args -> {
            if (args.length < 1 || args.length > 2) throw new RuntimeException("parents: expected 1 or 2 args");
            Object hier = args.length == 2 ? args[0] : globalVars.get("*hierarchy*");
            Object tag = args.length == 2 ? args[1] : args[0];
            if (hier instanceof clojure.lang.IPersistentMap h) {
                Object p = ((clojure.lang.IPersistentMap) h.valAt(
                        clojure.lang.Keyword.intern("parents"))).valAt(tag);
                return p == null ? ClojureNil.INSTANCE : p;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("ancestors", args -> {
            if (args.length < 1 || args.length > 2) throw new RuntimeException("ancestors: expected 1 or 2 args");
            Object hier = args.length == 2 ? args[0] : globalVars.get("*hierarchy*");
            Object tag = args.length == 2 ? args[1] : args[0];
            if (hier instanceof clojure.lang.IPersistentMap h) {
                Object a = ((clojure.lang.IPersistentMap) h.valAt(
                        clojure.lang.Keyword.intern("ancestors"))).valAt(tag);
                return a == null ? ClojureNil.INSTANCE : a;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("descendants", args -> {
            if (args.length < 1 || args.length > 2) throw new RuntimeException("descendants: expected 1 or 2 args");
            Object hier = args.length == 2 ? args[0] : globalVars.get("*hierarchy*");
            Object tag = args.length == 2 ? args[1] : args[0];
            if (hier instanceof clojure.lang.IPersistentMap h) {
                Object d = ((clojure.lang.IPersistentMap) h.valAt(
                        clojure.lang.Keyword.intern("descendants"))).valAt(tag);
                return d == null ? ClojureNil.INSTANCE : d;
            }
            return ClojureNil.INSTANCE;
        });

        // --- Phase 9: Misc utilities ---
        defBuiltin("tree-seq", args -> {
            checkArity(args, 3, "tree-seq");
            Object branch = args[0];
            Object children = args[1];
            Object root = args[2];
            java.util.List<Object> result = new ArrayList<>();
            java.util.Queue<Object> queue = new java.util.LinkedList<>();
            queue.add(root);
            while (!queue.isEmpty()) {
                Object node = queue.poll();
                result.add(node);
                if (isTruthy(callFunction(branch, new Object[]{node}))) {
                    Object kids = callFunction(children, new Object[]{node});
                    if (kids != null && !(kids instanceof ClojureNil)) {
                        // Add children at front for depth-first
                        java.util.List<Object> childList = new ArrayList<>();
                        for (clojure.lang.ISeq seq = seqOf(kids); seq != null; seq = seq.next()) {
                            childList.add(seq.first());
                        }
                        // Use a stack approach by adding at beginning
                        java.util.List<Object> remaining = new ArrayList<>(queue);
                        queue.clear();
                        queue.addAll(childList);
                        queue.addAll(remaining);
                    }
                }
            }
            return clojure.lang.PersistentList.create(result);
        });

        defBuiltin("iterate", args -> {
            checkArity(args, 2, "iterate");
            Object f = args[0];
            Object x = args[1];
            return new LazySeq(() -> lazyIterate(f, x));
        });

        defBuiltin("cycle", args -> {
            checkArity(args, 1, "cycle");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            if (items.isEmpty()) return ClojureNil.INSTANCE;
            return new LazySeq(() -> lazyCycle(items, 0));
        });

        defBuiltin("not=", args -> {
            checkArity(args, 2, "not=");
            return !clojure.lang.Util.equals(args[0] instanceof ClojureNil ? null : args[0],
                    args[1] instanceof ClojureNil ? null : args[1]);
        });

        defBuiltin("empty", args -> {
            checkArity(args, 1, "empty");
            Object coll = args[0];
            if (coll instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (coll instanceof clojure.lang.IPersistentVector) return clojure.lang.PersistentVector.EMPTY;
            if (coll instanceof clojure.lang.IPersistentMap) return clojure.lang.PersistentArrayMap.EMPTY;
            if (coll instanceof clojure.lang.IPersistentSet) return clojure.lang.PersistentHashSet.EMPTY;
            if (coll instanceof clojure.lang.IPersistentList) return clojure.lang.PersistentList.EMPTY;
            return clojure.lang.PersistentList.EMPTY;
        });

        defBuiltin("empty?", args -> {
            checkArity(args, 1, "empty?");
            Object coll = args[0];
            if (coll instanceof ClojureNil) return true;
            if (coll instanceof clojure.lang.Seqable s) return s.seq() == null;
            if (coll instanceof String str) return str.isEmpty();
            return false;
        });

        defBuiltin("not-empty", args -> {
            checkArity(args, 1, "not-empty");
            Object coll = args[0];
            if (coll instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (coll instanceof String s) return s.isEmpty() ? ClojureNil.INSTANCE : coll;
            if (coll instanceof clojure.lang.Seqable s) return s.seq() == null ? ClojureNil.INSTANCE : coll;
            return coll;
        });

        defBuiltin("bounded-count", args -> {
            checkArity(args, 2, "bounded-count");
            int n = ((Number) args[0]).intValue();
            if (args[1] instanceof clojure.lang.Counted c) return (long) Math.min(c.count(), n);
            long count = 0;
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null && count < n; seq = seq.next()) {
                count++;
            }
            return count;
        });

        defBuiltin("sequence", args -> {
            if (args.length == 1) {
                // (sequence coll) - coerce to seq
                clojure.lang.ISeq seq = seqOf(args[0]);
                return seq == null ? clojure.lang.PersistentList.EMPTY : seq;
            }
            if (args.length == 2) {
                // (sequence xform coll) - apply transducer
                Object xform = args[0];
                Object coll = args[1];
                java.util.List<Object> result = new ArrayList<>();
                Object rf = (BuiltinFunction) rfArgs -> {
                    if (rfArgs.length == 0) return clojure.lang.PersistentList.EMPTY;
                    if (rfArgs.length == 1) return rfArgs[0];
                    // step
                    ((java.util.List<Object>) rfArgs[0]).add(rfArgs[1]);
                    return rfArgs[0];
                };
                Object xf = callFunction(xform, new Object[]{rf});
                Object acc = result;
                for (clojure.lang.ISeq seq = seqOf(coll); seq != null; seq = seq.next()) {
                    acc = callFunction(xf, new Object[]{acc, seq.first()});
                    if (acc instanceof Reduced r) { acc = r.value; break; }
                }
                callFunction(xf, new Object[]{acc});
                return result.isEmpty() ? clojure.lang.PersistentList.EMPTY
                        : clojure.lang.PersistentList.create(result);
            }
            throw new RuntimeException("sequence: expected 1-2 args");
        });

        // --- Phase 10: Missing core functions ---
        defBuiltin("vec", args -> {
            checkArity(args, 1, "vec");
            if (args[0] instanceof ClojureNil) return clojure.lang.PersistentVector.EMPTY;
            if (args[0] instanceof clojure.lang.IPersistentVector v) return v;
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            return clojure.lang.PersistentVector.create(items);
        });

        defBuiltin("second", args -> {
            checkArity(args, 1, "second");
            clojure.lang.ISeq seq = seqOf(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            seq = seq.next();
            if (seq == null) return ClojureNil.INSTANCE;
            return seq.first();
        });

        defBuiltin("last", args -> {
            checkArity(args, 1, "last");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            Object result = ClojureNil.INSTANCE;
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                result = seq.first();
            }
            return result;
        });

        defBuiltin("butlast", args -> {
            checkArity(args, 1, "butlast");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            if (items.isEmpty()) return ClojureNil.INSTANCE;
            items.remove(items.size() - 1);
            return items.isEmpty() ? ClojureNil.INSTANCE : clojure.lang.PersistentList.create(items);
        });

        defBuiltin("peek", args -> {
            checkArity(args, 1, "peek");
            if (args[0] instanceof clojure.lang.IPersistentStack s) {
                Object v = s.peek();
                return v == null ? ClojureNil.INSTANCE : v;
            }
            throw new RuntimeException("peek: not a stack");
        });

        defBuiltin("pop", args -> {
            checkArity(args, 1, "pop");
            if (args[0] instanceof clojure.lang.IPersistentStack s) return s.pop();
            throw new RuntimeException("pop: not a stack");
        });

        defBuiltin("subvec", args -> {
            if (args.length < 2) throw new RuntimeException("subvec: expected 2-3 args");
            clojure.lang.IPersistentVector v = (clojure.lang.IPersistentVector) args[0];
            int start = ((Number) args[1]).intValue();
            int end = args.length > 2 ? ((Number) args[2]).intValue() : v.count();
            return clojure.truffle.runtime.ClojureRT.subvec(v, start, end);
        });

        defBuiltin("nfirst", args -> {
            checkArity(args, 1, "nfirst");
            // nfirst = (next (first x))
            clojure.lang.ISeq seq = seqOf(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            clojure.lang.ISeq firstSeq = seqOf(seq.first());
            if (firstSeq == null) return ClojureNil.INSTANCE;
            clojure.lang.ISeq n = firstSeq.next();
            return n == null ? (Object) ClojureNil.INSTANCE : n;
        });

        defBuiltin("nnext", args -> {
            checkArity(args, 1, "nnext");
            clojure.lang.ISeq seq = seqOf(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            seq = seq.next();
            if (seq == null) return ClojureNil.INSTANCE;
            return seq.next() == null ? (Object) ClojureNil.INSTANCE : seq.next();
        });

        defBuiltin("ffirst", args -> {
            checkArity(args, 1, "ffirst");
            clojure.lang.ISeq seq = seqOf(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            clojure.lang.ISeq inner = seqOf(seq.first());
            if (inner == null) return ClojureNil.INSTANCE;
            return inner.first();
        });

        defBuiltin("fnext", args -> {
            checkArity(args, 1, "fnext");
            clojure.lang.ISeq seq = seqOf(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            seq = seq.next();
            if (seq == null) return ClojureNil.INSTANCE;
            return seq.first();
        });

        defBuiltin("next", args -> {
            checkArity(args, 1, "next");
            clojure.lang.ISeq seq = seqOf(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            clojure.lang.ISeq n = seq.next();
            return n == null ? (Object) ClojureNil.INSTANCE : n;
        });

        // Ensure nth works on lists too
        defBuiltin("nth", args -> {
            if (args.length < 2) throw new RuntimeException("nth: expected 2-3 args");
            Object notFound = args.length > 2 ? args[2] : null;
            int idx = ((Number) args[1]).intValue();
            Object coll = args[0];
            if (coll == null || coll instanceof ClojureNil) {
                if (notFound != null) return notFound;
                return ClojureNil.INSTANCE;
            }
            // For deftype instances that implement nth as a type method
            if (coll instanceof ClojureDeftypeInstance dti) {
                Object nthFn = lookupTypeMethod(dti.getTypeName(), "nth");
                if (nthFn != null) {
                    try {
                        if (args.length > 2) {
                            return callFunction(nthFn, new Object[]{dti, args[1], args[2]});
                        } else {
                            return callFunction(nthFn, new Object[]{dti, args[1]});
                        }
                    } catch (Exception e) {
                        if (notFound != null) return notFound;
                        throw e;
                    }
                }
            }
            if (coll instanceof clojure.lang.Indexed indexed) {
                if (idx < 0 || idx >= indexed.count()) {
                    if (notFound != null) return notFound;
                    throw new IndexOutOfBoundsException("Index " + idx + " on " + coll.getClass().getName() + " (count=" + indexed.count() + ")");
                }
                return indexed.nth(idx);
            }
            // Fall back to seq traversal for lists
            clojure.lang.ISeq seq = seqOf(coll);
            for (int i = 0; i < idx && seq != null; i++) seq = seq.next();
            if (seq == null) {
                if (notFound != null) return notFound;
                throw new IndexOutOfBoundsException("Index " + idx + " on " + coll.getClass().getName());
            }
            return seq.first();
        });

        // assoc on vectors
        Object origAssoc = globalVars.get("assoc");
        defBuiltin("assoc", args -> {
            if (args.length < 3 || args.length % 2 != 1)
                throw new RuntimeException("assoc: expected odd number of args (coll k v ...)");
            Object coll = args[0];
            if (coll instanceof ClojureNil) coll = clojure.lang.PersistentArrayMap.EMPTY;
            for (int i = 1; i < args.length; i += 2) {
                if (coll instanceof clojure.lang.Associative a) {
                    coll = a.assoc(args[i], args[i + 1]);
                } else {
                    throw new RuntimeException("assoc: not associative: " + coll);
                }
            }
            return coll;
        });

        // Improved apply: (apply f x y [z1 z2]) spreads last arg
        java.util.concurrent.atomic.AtomicInteger applyDepth = new java.util.concurrent.atomic.AtomicInteger(0);
        defBuiltin("apply", args -> {
            if (args.length < 2) throw new RuntimeException("apply: expected at least 2 args");
            Object fn = args[0];
            int depth = applyDepth.incrementAndGet();
            if (depth > 50) {
                if (DEBUG) System.err.println("[APPLY-DEEP] depth=" + depth + " fn=" + fn + " fnType=" + fn.getClass().getSimpleName());
                if (fn instanceof ClojureFunction cf) {
                    if (DEBUG) System.err.println("[APPLY-DEEP] callTarget=" + cf.getCallTarget());
                }
                if (depth > 100) {
                    applyDepth.decrementAndGet();
                    throw new RuntimeException("[APPLY-DEEP] depth exceeded 100, aborting");
                }
            }
            // Last arg must be seqable
            Object lastArg = args[args.length - 1];
            java.util.List<Object> allArgs = new ArrayList<>();
            // Middle args (between fn and last)
            for (int i = 1; i < args.length - 1; i++) {
                allArgs.add(args[i]);
            }
            // Spread last arg
            if (lastArg instanceof ClojureNil) {
                // no-op
            } else {
                for (clojure.lang.ISeq seq = seqOf(lastArg); seq != null; seq = seq.next()) {
                    allArgs.add(seq.first());
                }
            }
            try {
                return callFunction(fn, allArgs.toArray(new Object[0]));
            } finally {
                applyDepth.decrementAndGet();
            }
        });

        // sort (no-arg comparator)
        defBuiltin("sort", args -> {
            if (args.length < 1 || args.length > 2) throw new RuntimeException("sort: expected 1-2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(coll); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            if (args.length == 2) {
                Object comp = args[0];
                items.sort((a, b) -> {
                    Object result = callFunction(comp, new Object[]{a, b});
                    if (result instanceof Number n) return n.intValue();
                    if (result instanceof Boolean bool) return bool ? -1 : 1;
                    return ((Comparable) result).compareTo(0);
                });
            } else {
                items.sort((a, b) -> ((Comparable) a).compareTo(b));
            }
            return clojure.lang.PersistentList.create(items);
        });

        // reverse
        defBuiltin("reverse", args -> {
            checkArity(args, 1, "reverse");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            java.util.Collections.reverse(items);
            return clojure.lang.PersistentList.create(items);
        });

        // repeat (finite and infinite)
        defBuiltin("repeat", args -> {
            if (args.length == 1) {
                // Infinite repeat
                Object x = args[0];
                return lazyRepeatInfinite(x);
            }
            checkArity(args, 2, "repeat");
            int n = ((Number) args[0]).intValue();
            Object x = args[1];
            java.util.List<Object> items = new ArrayList<>();
            for (int i = 0; i < n; i++) items.add(x);
            return clojure.lang.PersistentList.create(items);
        });

        // max, min
        defBuiltin("max", args -> {
            if (args.length == 0) throw new RuntimeException("max: expected at least 1 arg");
            Object best = args[0];
            for (int i = 1; i < args.length; i++) {
                if (((Comparable) args[i]).compareTo(best) > 0) best = args[i];
            }
            return best;
        });

        defBuiltin("min", args -> {
            if (args.length == 0) throw new RuntimeException("min: expected at least 1 arg");
            Object best = args[0];
            for (int i = 1; i < args.length; i++) {
                if (((Comparable) args[i]).compareTo(best) < 0) best = args[i];
            }
            return best;
        });

        // abs
        defBuiltin("abs", args -> {
            checkArity(args, 1, "abs");
            if (args[0] instanceof Long l) return Math.abs(l);
            if (args[0] instanceof Double d) return Math.abs(d);
            return Math.abs(((Number) args[0]).doubleValue());
        });

        // range improvements (0-arity and 3-arity)
        Object origRange = globalVars.get("range");
        defBuiltin("range", args -> {
            if (args.length == 0) {
                // Infinite range
                return new LazySeq(() -> lazyRange(0, Long.MAX_VALUE, 1));
            }
            return ((BuiltinFunction) origRange).execute(args);
        });

        // mapcat
        defBuiltin("mapcat", args -> {
            if (args.length < 2) throw new RuntimeException("mapcat: expected at least 2 args");
            Object f = args[0];
            // For simplicity, handle single collection case
            java.util.List<Object> result = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                Object mapped = callFunction(f, new Object[]{seq.first()});
                if (mapped == null || mapped instanceof ClojureNil) continue;
                clojure.lang.ISeq inner = null;
                try {
                    inner = seqOf(mapped);
                } catch (Exception e) {
                    // Non-seqable result (e.g. scalar from for :when) - treat as single element
                    result.add(mapped);
                    continue;
                }
                for (; inner != null; inner = inner.next()) {
                    result.add(inner.first());
                }
            }
            return result.isEmpty() ? clojure.lang.PersistentList.EMPTY
                    : clojure.lang.PersistentList.create(result);
        });

        // keep-indexed
        defBuiltin("keep-indexed", args -> {
            checkArity(args, 2, "keep-indexed");
            Object f = args[0];
            java.util.List<Object> result = new ArrayList<>();
            long idx = 0;
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                Object val = callFunction(f, new Object[]{idx++, seq.first()});
                if (val != null && !(val instanceof ClojureNil)) result.add(val);
            }
            return result.isEmpty() ? clojure.lang.PersistentList.EMPTY
                    : clojure.lang.PersistentList.create(result);
        });

        // some? (not nil?)
        defBuiltin("some?", args -> {
            checkArity(args, 1, "some?");
            return !(args[0] instanceof ClojureNil) && args[0] != null;
        });

        // true?, false?
        defBuiltin("true?", args -> {
            checkArity(args, 1, "true?");
            return Boolean.TRUE.equals(args[0]);
        });

        defBuiltin("false?", args -> {
            checkArity(args, 1, "false?");
            return Boolean.FALSE.equals(args[0]);
        });

        // zero?, pos?, neg? (may already exist but ensure)
        defBuiltin("zero?", args -> {
            checkArity(args, 1, "zero?");
            if (args[0] instanceof Long l) return l == 0L;
            if (args[0] instanceof Double d) return d == 0.0;
            return ((Number) args[0]).doubleValue() == 0.0;
        });

        defBuiltin("pos?", args -> {
            checkArity(args, 1, "pos?");
            if (args[0] instanceof Long l) return l > 0L;
            if (args[0] instanceof Double d) return d > 0.0;
            return ((Number) args[0]).doubleValue() > 0.0;
        });

        defBuiltin("neg?", args -> {
            checkArity(args, 1, "neg?");
            if (args[0] instanceof Long l) return l < 0L;
            if (args[0] instanceof Double d) return d < 0.0;
            return ((Number) args[0]).doubleValue() < 0.0;
        });

        defBuiltin("even?", args -> {
            checkArity(args, 1, "even?");
            return ((Number) args[0]).longValue() % 2 == 0;
        });

        defBuiltin("odd?", args -> {
            checkArity(args, 1, "odd?");
            return ((Number) args[0]).longValue() % 2 != 0;
        });

        // pos-int?, neg-int?, nat-int?
        defBuiltin("pos-int?", args -> {
            checkArity(args, 1, "pos-int?");
            return args[0] instanceof Long l && l > 0;
        });

        defBuiltin("neg-int?", args -> {
            checkArity(args, 1, "neg-int?");
            return args[0] instanceof Long l && l < 0;
        });

        defBuiltin("nat-int?", args -> {
            checkArity(args, 1, "nat-int?");
            return args[0] instanceof Long l && l >= 0;
        });

        // int?, double?, integer?
        defBuiltin("int?", args -> {
            checkArity(args, 1, "int?");
            return args[0] instanceof Long;
        });

        defBuiltin("double?", args -> {
            checkArity(args, 1, "double?");
            return args[0] instanceof Double;
        });

        defBuiltin("integer?", args -> {
            checkArity(args, 1, "integer?");
            return args[0] instanceof Long || args[0] instanceof Integer
                || args[0] instanceof java.math.BigInteger || args[0] instanceof clojure.lang.BigInt
                || args[0] instanceof Short || args[0] instanceof Byte;
        });

        defBuiltin("float?", args -> {
            checkArity(args, 1, "float?");
            return args[0] instanceof Double || args[0] instanceof Float;
        });

        // associative?, counted?, indexed?
        defBuiltin("associative?", args -> {
            checkArity(args, 1, "associative?");
            return args[0] instanceof clojure.lang.Associative;
        });

        defBuiltin("counted?", args -> {
            checkArity(args, 1, "counted?");
            return args[0] instanceof clojure.lang.Counted;
        });

        defBuiltin("indexed?", args -> {
            checkArity(args, 1, "indexed?");
            return args[0] instanceof clojure.lang.Indexed;
        });

        defBuiltin("reversible?", args -> {
            checkArity(args, 1, "reversible?");
            return args[0] instanceof clojure.lang.Reversible;
        });

        defBuiltin("sorted?", args -> {
            checkArity(args, 1, "sorted?");
            return args[0] instanceof clojure.lang.Sorted;
        });

        // atom?
        defBuiltin("atom?", args -> {
            checkArity(args, 1, "atom?");
            return args[0] instanceof ClojureAtom;
        });

        // str improvements - handle nil as ""
        defBuiltin("str", args -> {
            if (args.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (arg instanceof ClojureNil) continue;
                sb.append(printString(arg, false));
            }
            return sb.toString();
        });

        // Improved println-str, pr-str
        defBuiltin("pr-str", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            return sb.toString();
        });

        defBuiltin("println-str", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], false));
            }
            sb.append("\n");
            return sb.toString();
        });

        // --- Phase 11: read-string ---
        defBuiltin("read-string", args -> {
            checkArity(args, 1, "read-string");
            String s = args[0].toString();
            try {
                java.io.PushbackReader rdr = new java.io.PushbackReader(new java.io.StringReader(s));
                Object form = clojure.lang.TruffleReader.read(rdr, true, null, false, null);
                return form == null ? ClojureNil.INSTANCE : form;
            } catch (Exception e) {
                throw new RuntimeException("read-string: " + e.getMessage());
            }
        });

        // --- Phase 11: walk functions ---
        // NOTE: walk/postwalk/prewalk are NOT clojure.core functions.
        // They belong to clojure.walk namespace only. Registered in registerWalkNamespace()
        // to avoid polluting globalVars and shadowing user-defined 'walk' functions.

        // --- Phase 11: update-keys, update-vals (Clojure 1.11+) ---
        defBuiltin("update-keys", args -> {
            checkArity(args, 2, "update-keys");
            clojure.lang.IPersistentMap m = (clojure.lang.IPersistentMap) args[0];
            Object f = args[1];
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq seq = m.seq(); seq != null; seq = seq.next()) {
                clojure.lang.MapEntry entry = (clojure.lang.MapEntry) seq.first();
                Object newKey = callFunction(f, new Object[]{entry.key()});
                result = result.assoc(newKey, entry.val());
            }
            return result;
        });

        defBuiltin("update-vals", args -> {
            checkArity(args, 2, "update-vals");
            clojure.lang.IPersistentMap m = (clojure.lang.IPersistentMap) args[0];
            Object f = args[1];
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq seq = m.seq(); seq != null; seq = seq.next()) {
                clojure.lang.MapEntry entry = (clojure.lang.MapEntry) seq.first();
                Object newVal = callFunction(f, new Object[]{entry.val()});
                result = result.assoc(entry.key(), newVal);
            }
            return result;
        });

        // --- Phase 11: Misc missing ---
        defBuiltin("map-entry", args -> {
            checkArity(args, 2, "map-entry");
            return clojure.lang.MapEntry.create(args[0], args[1]);
        });

        defBuiltin("key", args -> {
            checkArity(args, 1, "key");
            return ((clojure.lang.IMapEntry) args[0]).key();
        });

        defBuiltin("val", args -> {
            checkArity(args, 1, "val");
            return ((clojure.lang.IMapEntry) args[0]).val();
        });

        defBuiltin("find", args -> {
            checkArity(args, 2, "find");
            if (args[0] instanceof clojure.lang.IPersistentMap m) {
                clojure.lang.IMapEntry entry = m.entryAt(args[1]);
                return entry == null ? ClojureNil.INSTANCE : entry;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("map-entry?", args -> {
            checkArity(args, 1, "map-entry?");
            return args[0] instanceof clojure.lang.IMapEntry;
        });

        defBuiltin("not", args -> {
            checkArity(args, 1, "not");
            return !isTruthy(args[0]);
        });

        defBuiltin("mod", args -> {
            checkArity(args, 2, "mod");
            if (args[0] instanceof Long a && args[1] instanceof Long b) {
                return Math.floorMod(a, b);
            }
            double a = ((Number) args[0]).doubleValue();
            double b = ((Number) args[1]).doubleValue();
            return a - b * Math.floor(a / b);
        });

        defBuiltin("rem", args -> {
            checkArity(args, 2, "rem");
            if (args[0] instanceof Long a && args[1] instanceof Long b) return a % b;
            return ((Number) args[0]).doubleValue() % ((Number) args[1]).doubleValue();
        });

        defBuiltin("quot", args -> {
            checkArity(args, 2, "quot");
            if (args[0] instanceof Long a && args[1] instanceof Long b) return a / b;
            return (long) (((Number) args[0]).doubleValue() / ((Number) args[1]).doubleValue());
        });

        defBuiltin("bit-and", args -> {
            checkArity(args, 2, "bit-and");
            return ((Number) args[0]).longValue() & ((Number) args[1]).longValue();
        });

        defBuiltin("bit-or", args -> {
            checkArity(args, 2, "bit-or");
            return ((Number) args[0]).longValue() | ((Number) args[1]).longValue();
        });

        defBuiltin("bit-xor", args -> {
            checkArity(args, 2, "bit-xor");
            return ((Number) args[0]).longValue() ^ ((Number) args[1]).longValue();
        });

        defBuiltin("bit-not", args -> {
            checkArity(args, 1, "bit-not");
            return ~((Number) args[0]).longValue();
        });

        defBuiltin("bit-shift-left", args -> {
            checkArity(args, 2, "bit-shift-left");
            return ((Number) args[0]).longValue() << ((Number) args[1]).intValue();
        });

        defBuiltin("bit-shift-right", args -> {
            checkArity(args, 2, "bit-shift-right");
            return ((Number) args[0]).longValue() >> ((Number) args[1]).intValue();
        });

        defBuiltin("unsigned-bit-shift-right", args -> {
            checkArity(args, 2, "unsigned-bit-shift-right");
            return ((Number) args[0]).longValue() >>> ((Number) args[1]).intValue();
        });

        defBuiltin("bit-test", args -> {
            checkArity(args, 2, "bit-test");
            long x = ((Number) args[0]).longValue();
            int n = ((Number) args[1]).intValue();
            return (x & (1L << n)) != 0;
        });

        defBuiltin("bit-set", args -> {
            checkArity(args, 2, "bit-set");
            long x = ((Number) args[0]).longValue();
            int n = ((Number) args[1]).intValue();
            return x | (1L << n);
        });

        defBuiltin("bit-clear", args -> {
            checkArity(args, 2, "bit-clear");
            long x = ((Number) args[0]).longValue();
            int n = ((Number) args[1]).intValue();
            return x & ~(1L << n);
        });

        defBuiltin("bit-flip", args -> {
            checkArity(args, 2, "bit-flip");
            long x = ((Number) args[0]).longValue();
            int n = ((Number) args[1]).intValue();
            return x ^ (1L << n);
        });

        defBuiltin("long", args -> {
            checkArity(args, 1, "long");
            if (args[0] instanceof Long l) return l;
            return ((Number) args[0]).longValue();
        });

        defBuiltin("double", args -> {
            checkArity(args, 1, "double");
            if (args[0] instanceof Double d) return d;
            return ((Number) args[0]).doubleValue();
        });

        defBuiltin("boolean", args -> {
            checkArity(args, 1, "boolean");
            return isTruthy(args[0]);
        });

        defBuiltin("bigint", args -> {
            checkArity(args, 1, "bigint");
            if (args[0] instanceof java.math.BigInteger bi) return bi;
            return java.math.BigInteger.valueOf(((Number) args[0]).longValue());
        });

        defBuiltin("bigdec", args -> {
            checkArity(args, 1, "bigdec");
            if (args[0] instanceof java.math.BigDecimal bd) return bd;
            return java.math.BigDecimal.valueOf(((Number) args[0]).doubleValue());
        });

        // with-redefs support via dynamic binding
        defBuiltin("alter-var-root", args -> {
            checkArity(args, 2, "alter-var-root");
            Object f = args[1];
            if (args[0] instanceof clojure.truffle.runtime.ClojureVar cvar) {
                Object oldVal = cvar.deref();
                if (oldVal == null) oldVal = ClojureNil.INSTANCE;
                Object newVal = callFunction(f, new Object[]{oldVal});
                // Set the new value in the var's namespace
                String ns = cvar.getNamespace();
                String name = cvar.getName();
                if (ns != null) {
                    ClojureNamespace namespace = getNamespace(ns);
                    if (namespace != null) {
                        namespace.intern(name, newVal);
                    }
                    setVar(ns + "/" + name, newVal);
                } else {
                    setVar(name, newVal);
                }
                return newVal;
            }
            String varName = args[0].toString();
            Object oldVal = getVar(varName);
            Object newVal = callFunction(f, new Object[]{oldVal == null ? ClojureNil.INSTANCE : oldVal});
            setVar(varName, newVal);
            return newVal;
        });

        // --- Phase 12: delay/force ---
        defBuiltin("delay", args -> {
            // Note: in real Clojure, delay is a macro. Here we treat it as a builtin
            // that takes a thunk (fn of no args)
            checkArity(args, 1, "delay");
            Object thunk = args[0];
            ClojureContext ctx = this;
            return new clojure.lang.Delay(new clojure.lang.AFn() {
                @Override
                public Object invoke() {
                    return ctx.callFunction(thunk, new Object[0]);
                }
            });
        });

        defBuiltin("force", args -> {
            checkArity(args, 1, "force");
            Object x = args[0];
            if (x instanceof clojure.lang.Delay d) return d.deref();
            if (x instanceof clojure.lang.IDeref d) return d.deref();
            return x; // If not a delay, return as-is
        });

        // --- Phase 12: Java array interop ---
        defBuiltin("make-array", args -> {
            if (args.length < 2) throw new RuntimeException("make-array: expected at least 2 args");
            Class<?> clazz = (Class<?>) args[0];
            int size = ((Number) args[1]).intValue();
            return java.lang.reflect.Array.newInstance(clazz, size);
        });

        defBuiltin("byte-array", args -> {
            if (args.length == 1) {
                if (args[0] instanceof Number n) {
                    return new byte[n.intValue()];
                }
                // Convert collection to byte array
                java.util.List<Object> items = new ArrayList<>();
                for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next())
                    items.add(seq.first());
                byte[] result = new byte[items.size()];
                for (int i = 0; i < items.size(); i++)
                    result[i] = ((Number) items.get(i)).byteValue();
                return result;
            } else if (args.length == 2) {
                int size = ((Number) args[0]).intValue();
                byte[] result = new byte[size];
                if (args[1] instanceof Number n) {
                    java.util.Arrays.fill(result, n.byteValue());
                } else {
                    int i = 0;
                    for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null && i < size; seq = seq.next(), i++)
                        result[i] = ((Number) seq.first()).byteValue();
                }
                return result;
            }
            throw new RuntimeException("byte-array: expected 1-2 args");
        });

        defBuiltin("int-array", args -> {
            if (args.length == 1) {
                if (args[0] instanceof Number n) {
                    return new int[n.intValue()];
                }
                java.util.List<Object> items = new ArrayList<>();
                for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next())
                    items.add(seq.first());
                int[] result = new int[items.size()];
                for (int i = 0; i < items.size(); i++)
                    result[i] = ((Number) items.get(i)).intValue();
                return result;
            } else if (args.length == 2) {
                int size = ((Number) args[0]).intValue();
                int[] result = new int[size];
                if (args[1] instanceof Number n) {
                    java.util.Arrays.fill(result, n.intValue());
                } else {
                    int i = 0;
                    for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null && i < size; seq = seq.next(), i++)
                        result[i] = ((Number) seq.first()).intValue();
                }
                return result;
            }
            throw new RuntimeException("int-array: expected 1-2 args");
        });

        defBuiltin("long-array", args -> {
            if (args.length == 1) {
                if (args[0] instanceof Number n) {
                    return new long[n.intValue()];
                }
                java.util.List<Object> items = new ArrayList<>();
                for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next())
                    items.add(seq.first());
                long[] result = new long[items.size()];
                for (int i = 0; i < items.size(); i++)
                    result[i] = ((Number) items.get(i)).longValue();
                return result;
            } else if (args.length == 2) {
                int size = ((Number) args[0]).intValue();
                long[] result = new long[size];
                if (args[1] instanceof Number n) {
                    java.util.Arrays.fill(result, n.longValue());
                } else {
                    int i = 0;
                    for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null && i < size; seq = seq.next(), i++)
                        result[i] = ((Number) seq.first()).longValue();
                }
                return result;
            }
            throw new RuntimeException("long-array: expected 1-2 args");
        });

        defBuiltin("double-array", args -> {
            if (args.length == 1) {
                if (args[0] instanceof Number n) {
                    return new double[n.intValue()];
                }
                java.util.List<Object> items = new ArrayList<>();
                for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next())
                    items.add(seq.first());
                double[] result = new double[items.size()];
                for (int i = 0; i < items.size(); i++)
                    result[i] = ((Number) items.get(i)).doubleValue();
                return result;
            } else if (args.length == 2) {
                int size = ((Number) args[0]).intValue();
                double[] result = new double[size];
                if (args[1] instanceof Number n) {
                    java.util.Arrays.fill(result, n.doubleValue());
                } else {
                    int i = 0;
                    for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null && i < size; seq = seq.next(), i++)
                        result[i] = ((Number) seq.first()).doubleValue();
                }
                return result;
            }
            throw new RuntimeException("double-array: expected 1-2 args");
        });

        defBuiltin("char-array", args -> {
            if (args.length == 1) {
                if (args[0] instanceof Number n) {
                    return new char[n.intValue()];
                }
                if (args[0] instanceof String s) {
                    return s.toCharArray();
                }
                java.util.List<Object> items = new ArrayList<>();
                for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next())
                    items.add(seq.first());
                char[] result = new char[items.size()];
                for (int i = 0; i < items.size(); i++) {
                    Object item = items.get(i);
                    result[i] = (item instanceof Character c) ? c : (char) ((Number) item).intValue();
                }
                return result;
            }
            throw new RuntimeException("char-array: expected 1 arg");
        });

        defBuiltin("boolean-array", args -> {
            if (args.length == 1) {
                if (args[0] instanceof Number n) {
                    return new boolean[n.intValue()];
                }
                java.util.List<Object> items = new ArrayList<>();
                for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next())
                    items.add(seq.first());
                boolean[] result = new boolean[items.size()];
                for (int i = 0; i < items.size(); i++)
                    result[i] = isTruthy(items.get(i));
                return result;
            }
            throw new RuntimeException("boolean-array: expected 1 arg");
        });

        defBuiltin("object-array", args -> {
            checkArity(args, 1, "object-array");
            if (args[0] instanceof Number n) {
                return new Object[n.intValue()];
            }
            // Convert collection to array
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            return items.toArray();
        });

        defBuiltin("to-array", args -> {
            checkArity(args, 1, "to-array");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            return items.toArray();
        });

        defBuiltin("into-array", args -> {
            if (args.length == 1) {
                java.util.List<Object> items = new ArrayList<>();
                for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                    items.add(seq.first());
                }
                return items.toArray();
            }
            checkArity(args, 2, "into-array");
            Class<?> clazz = (Class<?>) args[0];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            Object arr = java.lang.reflect.Array.newInstance(clazz, items.size());
            for (int i = 0; i < items.size(); i++) {
                java.lang.reflect.Array.set(arr, i, items.get(i));
            }
            return arr;
        });

        defBuiltin("aset", args -> {
            checkArity(args, 3, "aset");
            int idx = ((Number) args[1]).intValue();
            java.lang.reflect.Array.set(args[0], idx, args[2]);
            return args[2];
        });

        defBuiltin("aget", args -> {
            checkArity(args, 2, "aget");
            int idx = ((Number) args[1]).intValue();
            Object val = java.lang.reflect.Array.get(args[0], idx);
            return val == null ? ClojureNil.INSTANCE : val;
        });

        defBuiltin("alength", args -> {
            checkArity(args, 1, "alength");
            return (long) java.lang.reflect.Array.getLength(args[0]);
        });

        defBuiltin("aclone", args -> {
            checkArity(args, 1, "aclone");
            int len = java.lang.reflect.Array.getLength(args[0]);
            Object newArr = java.lang.reflect.Array.newInstance(
                    args[0].getClass().getComponentType(), len);
            System.arraycopy(args[0], 0, newArr, 0, len);
            return newArr;
        });

        defBuiltin("array?", args -> {
            checkArity(args, 1, "array?");
            return args[0] != null && args[0].getClass().isArray();
        });

        // --- Phase 12: Protocol extension ---
        defBuiltin("extend-type", args -> {
            // (extend-type Type Protocol (method [args] body) ...)
            // This is handled by the analyzer as a special form
            throw new RuntimeException("extend-type should be handled by analyzer");
        });

        // (extend atype & proto+mmaps)
        // (extend Type Protocol {:method-name fn} Protocol2 {:method fn} ...)
        defBuiltin("extend", args -> {
            if (args.length < 3 || (args.length - 1) % 2 != 0)
                throw new RuntimeException("extend: expects type followed by protocol/map pairs");
            Object typeArg = args[0];
            // Resolve type key: nil -> Void.class for protocol dispatch
            Object typeKey;
            if (typeArg == null || typeArg instanceof clojure.truffle.runtime.ClojureNil) {
                typeKey = Void.class;
            } else if (typeArg instanceof Class<?> clazz) {
                typeKey = clazz;
            } else if (typeArg instanceof String s) {
                typeKey = s;
            } else {
                typeKey = typeArg;
            }
            for (int i = 1; i < args.length; i += 2) {
                Object protoArg = args[i];
                Object mapArg = args[i + 1];
                if (!(protoArg instanceof clojure.truffle.runtime.ClojureProtocol proto))
                    throw new RuntimeException("extend: expected protocol, got: " + protoArg + " (" + (protoArg == null ? "null" : protoArg.getClass().getName()) + ")");
                if (!(mapArg instanceof clojure.lang.IPersistentMap pmap))
                    throw new RuntimeException("extend: expected map of methods, got: " + mapArg);
                java.util.Map<String, Object> methods = new java.util.HashMap<>();
                for (var entry : (Iterable<java.util.Map.Entry<Object, Object>>) (Iterable) pmap) {
                    Object key = entry.getKey();
                    String methodName;
                    if (key instanceof clojure.lang.Keyword kw) {
                        methodName = kw.getName();
                    } else if (key instanceof String s) {
                        methodName = s;
                    } else {
                        methodName = key.toString();
                    }
                    methods.put(methodName, entry.getValue());
                }
                proto.extend(typeKey, methods);
            }
            return clojure.truffle.runtime.ClojureNil.INSTANCE;
        });

        defBuiltin("satisfies?", args -> {
            checkArity(args, 2, "satisfies?");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureProtocol proto))
                throw new RuntimeException("satisfies?: first arg must be a protocol");
            return proto.hasImplementation(args[1]);
        });

        defBuiltin("prefer-method", args -> {
            checkArity(args, 3, "prefer-method");
            if (!(args[0] instanceof ClojureMultiMethod mm))
                throw new RuntimeException("prefer-method: first arg must be a multimethod");
            mm.preferMethod(args[1], args[2]);
            return mm;
        });

        defBuiltin("methods", args -> {
            checkArity(args, 1, "methods");
            if (!(args[0] instanceof ClojureMultiMethod mm))
                throw new RuntimeException("methods: first arg must be a multimethod");
            return mm.getMethodTable();
        });

        // --- Phase 12: Misc ---
        defBuiltin("dorun", args -> {
            if (args.length < 1) throw new RuntimeException("dorun: expected 1-2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            for (clojure.lang.ISeq seq = seqOf(coll); seq != null; seq = seq.next()) {
                // force evaluation
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("doall", args -> {
            if (args.length < 1) throw new RuntimeException("doall: expected 1-2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            clojure.lang.ISeq seq = seqOf(coll);
            if (seq == null) return clojure.lang.PersistentList.EMPTY;
            // Force full realization
            java.util.List<Object> items = new ArrayList<>();
            for (; seq != null; seq = seq.next()) items.add(seq.first());
            return clojure.lang.PersistentList.create(items);
        });

        defBuiltin("line-seq", args -> {
            checkArity(args, 1, "line-seq");
            java.io.BufferedReader rdr = (java.io.BufferedReader) args[0];
            java.util.List<Object> lines = new ArrayList<>();
            try {
                String line;
                while ((line = rdr.readLine()) != null) lines.add(line);
            } catch (java.io.IOException e) {
                throw new RuntimeException("line-seq: " + e.getMessage());
            }
            return clojure.lang.PersistentList.create(lines);
        });

        defBuiltin("with-out-str", args -> {
            // Takes a thunk (0-arg function) and captures its output
            checkArity(args, 1, "with-out-str");
            java.io.StringWriter sw = new java.io.StringWriter();
            java.io.Writer prev = outOverride.get();
            outOverride.set(sw);
            try {
                callFunction(args[0], new Object[0]);
                return sw.toString();
            } finally {
                outOverride.set(prev);
            }
        });

        defBuiltin("time", args -> {
            // time as a function taking a thunk
            checkArity(args, 1, "time");
            long start = System.nanoTime();
            Object result = callFunction(args[0], new Object[0]);
            long elapsed = System.nanoTime() - start;
            PrintStream out = new PrintStream(env.out());
            out.println("\"Elapsed time: " + (elapsed / 1000000.0) + " msecs\"");
            return result;
        });

        defBuiltin("rand", args -> {
            if (args.length == 0) return Math.random();
            return Math.random() * ((Number) args[0]).doubleValue();
        });

        // compare-and-set!
        defBuiltin("compare-and-set!", args -> {
            checkArity(args, 3, "compare-and-set!");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAtom atom))
                throw new RuntimeException("compare-and-set!: first arg must be an atom");
            return atom.compareAndSet(args[1], args[2]);
        });

        // every-pred
        defBuiltin("every-pred", args -> {
            if (args.length < 1) throw new RuntimeException("every-pred: expected at least 1 arg");
            Object[] preds = args.clone();
            return (BuiltinFunction) testArgs -> {
                for (Object pred : preds) {
                    for (Object x : testArgs) {
                        if (!isTruthy(callFunction(pred, new Object[]{x}))) return false;
                    }
                }
                return true;
            };
        });

        // some-fn
        defBuiltin("some-fn", args -> {
            if (args.length < 1) throw new RuntimeException("some-fn: expected at least 1 arg");
            Object[] preds = args.clone();
            return (BuiltinFunction) testArgs -> {
                for (Object pred : preds) {
                    for (Object x : testArgs) {
                        Object result = callFunction(pred, new Object[]{x});
                        if (isTruthy(result)) return result;
                    }
                }
                return ClojureNil.INSTANCE;
            };
        });

        // dedupe
        defBuiltin("cat", args -> {
            // cat is a transducer that concatenates elements from inner collections
            checkArity(args, 1, "cat");
            Object rf = args[0];
            return new TransducerRf(rf) {
                @Override public Object step(Object acc, Object input) {
                    Object result = acc;
                    for (clojure.lang.ISeq s = seqOf(input); s != null; s = s.next()) {
                        result = callFunction(innerRf, new Object[]{result, s.first()});
                        if (result instanceof Reduced) return result;
                    }
                    return result;
                }
            };
        });

        defBuiltin("dedupe", args -> {
            if (args.length == 0) {
                // Return transducer
                return (BuiltinFunction) xfArgs -> {
                    Object rf = xfArgs[0];
                    final Object[] prev = {new Object()}; // sentinel
                    return (BuiltinFunction) stepArgs -> {
                        if (stepArgs.length == 1) return stepArgs[0]; // completion
                        Object acc = stepArgs[0], item = stepArgs[1];
                        if (item.equals(prev[0])) return acc;
                        prev[0] = item;
                        return callFunction(rf, new Object[]{acc, item});
                    };
                };
            }
            checkArity(args, 1, "dedupe");
            java.util.List<Object> result = new ArrayList<>();
            Object prev = new Object(); // sentinel
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                Object item = seq.first();
                if (!item.equals(prev)) {
                    result.add(item);
                    prev = item;
                }
            }
            return clojure.lang.PersistentList.create(result);
        });

        // sorted-set
        defBuiltin("sorted-set", args -> {
            clojure.lang.PersistentTreeSet s = clojure.lang.PersistentTreeSet.EMPTY;
            for (Object arg : args) {
                s = (clojure.lang.PersistentTreeSet) s.cons(arg);
            }
            return s;
        });

        // prn-str
        defBuiltin("prn-str", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            sb.append("\n");
            return sb.toString();
        });

        // rename-keys
        defBuiltin("rename-keys", args -> {
            checkArity(args, 2, "rename-keys");
            if (!(args[0] instanceof clojure.lang.IPersistentMap m))
                throw new RuntimeException("rename-keys: first arg must be a map");
            if (!(args[1] instanceof clojure.lang.IPersistentMap kmap))
                throw new RuntimeException("rename-keys: second arg must be a map");
            clojure.lang.IPersistentMap result = m;
            for (clojure.lang.ISeq seq = kmap.seq(); seq != null; seq = seq.next()) {
                clojure.lang.MapEntry entry = (clojure.lang.MapEntry) seq.first();
                Object oldKey = entry.key(), newKey = entry.val();
                if (result.containsKey(oldKey)) {
                    Object val = result.valAt(oldKey);
                    result = result.without(oldKey);
                    result = result.assoc(newKey, val);
                }
            }
            return result;
        });

        // completing
        defBuiltin("completing", args -> {
            if (args.length < 1 || args.length > 2)
                throw new RuntimeException("completing: expected 1 or 2 args");
            Object f = args[0];
            Object cf = args.length == 2 ? args[1] : null;
            return (BuiltinFunction) rfArgs -> {
                if (rfArgs.length == 1) {
                    // completion arity
                    return cf != null ? callFunction(cf, new Object[]{rfArgs[0]}) : rfArgs[0];
                }
                return callFunction(f, rfArgs);
            };
        });

        // eduction
        defBuiltin("eduction", args -> {
            if (args.length < 2) throw new RuntimeException("eduction: expected xform and coll");
            Object xform = args[0];
            Object coll = args[args.length - 1];
            // Compose multiple xforms if more than 2 args: (eduction xf1 xf2 ... coll)
            // comp(xf1, xf2, ...)(rf) = xf1(xf2(...(rf)))
            if (args.length > 2) {
                for (int i = 1; i < args.length - 1; i++) {
                    final Object outerXf = xform;
                    final Object innerXf = args[i];
                    xform = (BuiltinFunction) compArgs -> {
                        Object inner = callFunction(innerXf, compArgs);
                        return callFunction(outerXf, new Object[]{inner});
                    };
                }
            }
            // Apply transducer eagerly for now
            java.util.List<Object> result = new ArrayList<>();
            BuiltinFunction collectRf = rfArgs -> {
                if (rfArgs.length == 1) return rfArgs[0];
                ((java.util.List<Object>) rfArgs[0]).add(rfArgs[1]);
                return rfArgs[0];
            };
            Object xrf = callFunction(xform, new Object[]{collectRf});
            Object acc = result;
            for (clojure.lang.ISeq seq = seqOf(coll); seq != null; seq = seq.next()) {
                acc = callFunction(xrf, new Object[]{acc, seq.first()});
                if (acc instanceof Reduced r) { acc = r.value; break; }
            }
            return clojure.lang.PersistentList.create(result);
        });

        // rand-int
        defBuiltin("rand-int", args -> {
            checkArity(args, 1, "rand-int");
            long n = ((Number) args[0]).longValue();
            return (long) (Math.random() * n);
        });

        // rand-nth
        defBuiltin("rand-nth", args -> {
            checkArity(args, 1, "rand-nth");
            if (args[0] instanceof clojure.lang.IPersistentVector v) {
                return v.nth((int) (Math.random() * v.count()));
            }
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next())
                items.add(seq.first());
            return items.get((int) (Math.random() * items.size()));
        });

        // shuffle
        defBuiltin("shuffle", args -> {
            checkArity(args, 1, "shuffle");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next())
                items.add(seq.first());
            java.util.Collections.shuffle(items);
            return clojure.lang.PersistentVector.create(items);
        });

        // not-any?
        defBuiltin("not-any?", args -> {
            checkArity(args, 2, "not-any?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                if (isTruthy(callFunction(pred, new Object[]{seq.first()}))) return false;
            }
            return true;
        });

        // not-every?
        defBuiltin("not-every?", args -> {
            checkArity(args, 2, "not-every?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next()) {
                if (!isTruthy(callFunction(pred, new Object[]{seq.first()}))) return true;
            }
            return false;
        });

        // --- Phase 15 builtins ---

        // Agents
        defBuiltin("agent", args -> {
            checkArity(args, 1, "agent");
            return new clojure.truffle.runtime.ClojureAgent(args[0]);
        });

        defBuiltin("send", args -> {
            if (args.length < 2) throw new RuntimeException("send: expected at least 2 args");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAgent ag))
                throw new RuntimeException("send: first arg must be an agent");
            Object fn = args[1];
            Object[] extraArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, extraArgs, 0, extraArgs.length);
            ag.send(fn, extraArgs, this);
            return ag;
        });

        defBuiltin("send-off", args -> {
            if (args.length < 2) throw new RuntimeException("send-off: expected at least 2 args");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAgent ag))
                throw new RuntimeException("send-off: first arg must be an agent");
            Object fn = args[1];
            Object[] extraArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, extraArgs, 0, extraArgs.length);
            ag.sendOff(fn, extraArgs, this);
            return ag;
        });

        defBuiltin("await", args -> {
            for (Object arg : args) {
                if (arg instanceof clojure.truffle.runtime.ClojureAgent ag) {
                    ag.awaitActions();
                }
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("agent-error", args -> {
            checkArity(args, 1, "agent-error");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAgent ag))
                throw new RuntimeException("agent-error: first arg must be an agent");
            Throwable err = ag.getError();
            return err != null ? err : ClojureNil.INSTANCE;
        });

        defBuiltin("restart-agent", args -> {
            checkArity(args, 2, "restart-agent");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAgent ag))
                throw new RuntimeException("restart-agent: first arg must be an agent");
            ag.restart(args[1]);
            return ag;
        });

        defBuiltin("agent?", args -> {
            checkArity(args, 1, "agent?");
            return args[0] instanceof clojure.truffle.runtime.ClojureAgent;
        });

        // Refs (simplified, no real STM)
        defBuiltin("ref", args -> {
            checkArity(args, 1, "ref");
            return new clojure.truffle.runtime.ClojureRef(args[0]);
        });

        defBuiltin("ref-set", args -> {
            checkArity(args, 2, "ref-set");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureRef r))
                throw new RuntimeException("ref-set: first arg must be a ref");
            return r.refSet(args[1]);
        });

        defBuiltin("alter", args -> {
            if (args.length < 2) throw new RuntimeException("alter: expected at least 2 args");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureRef r))
                throw new RuntimeException("alter: first arg must be a ref");
            Object fn = args[1];
            Object[] moreArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, moreArgs, 0, moreArgs.length);
            return r.alter(fn, moreArgs, this);
        });

        defBuiltin("commute", args -> {
            if (args.length < 2) throw new RuntimeException("commute: expected at least 2 args");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureRef r))
                throw new RuntimeException("commute: first arg must be a ref");
            Object fn = args[1];
            Object[] moreArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, moreArgs, 0, moreArgs.length);
            return r.commute(fn, moreArgs, this);
        });

        defBuiltin("ref?", args -> {
            checkArity(args, 1, "ref?");
            return args[0] instanceof clojure.truffle.runtime.ClojureRef;
        });

        // --- Phase 14 builtins ---

        // identical?
        defBuiltin("identical?", args -> {
            checkArity(args, 2, "identical?");
            return args[0] == args[1];
        });

        // transient collections
        defBuiltin("transient", args -> {
            checkArity(args, 1, "transient");
            if (args[0] instanceof clojure.lang.IEditableCollection ec)
                return ec.asTransient();
            throw new RuntimeException("transient: not supported for " + args[0].getClass().getName());
        });

        defBuiltin("persistent!", args -> {
            checkArity(args, 1, "persistent!");
            if (args[0] instanceof clojure.lang.ITransientCollection tc)
                return tc.persistent();
            throw new RuntimeException("persistent!: not a transient collection");
        });

        defBuiltin("conj!", args -> {
            checkArity(args, 2, "conj!");
            if (args[0] instanceof clojure.lang.ITransientCollection tc)
                return tc.conj(args[1]);
            throw new RuntimeException("conj!: not a transient collection");
        });

        defBuiltin("assoc!", args -> {
            if (args.length < 3 || args.length % 2 == 0)
                throw new RuntimeException("assoc!: expected odd number of args >= 3");
            Object m = args[0];
            if (!(m instanceof clojure.lang.ITransientAssociative ta))
                throw new RuntimeException("assoc!: not a transient associative");
            for (int i = 1; i < args.length; i += 2) {
                ta = (clojure.lang.ITransientAssociative) ta.assoc(args[i], args[i + 1]);
            }
            return ta;
        });

        defBuiltin("dissoc!", args -> {
            checkArity(args, 2, "dissoc!");
            if (args[0] instanceof clojure.lang.ITransientMap tm)
                return tm.without(args[1]);
            throw new RuntimeException("dissoc!: not a transient map");
        });

        defBuiltin("pop!", args -> {
            checkArity(args, 1, "pop!");
            if (args[0] instanceof clojure.lang.ITransientVector tv)
                return tv.pop();
            throw new RuntimeException("pop!: not a transient vector");
        });

        // remove-method
        defBuiltin("remove-method", args -> {
            checkArity(args, 2, "remove-method");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureMultiMethod mm))
                throw new RuntimeException("remove-method: first arg must be a multimethod");
            mm.removeMethod(args[1]);
            return mm;
        });

        defBuiltin("remove-all-methods", args -> {
            checkArity(args, 1, "remove-all-methods");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureMultiMethod mm))
                throw new RuntimeException("remove-all-methods: first arg must be a multimethod");
            mm.removeAllMethods();
            return mm;
        });

        // alter-meta!
        defBuiltin("alter-meta!", args -> {
            if (args.length < 2) throw new RuntimeException("alter-meta!: expected at least 2 args");
            Object f = args[1];
            Object[] fArgs = new Object[args.length - 1];
            System.arraycopy(args, 2, fArgs, 1, args.length - 2);
            if (args[0] instanceof clojure.truffle.runtime.ClojureAtom atom) {
                fArgs[0] = atom.getMeta();
                Object newMeta = callFunction(f, fArgs);
                atom.setMeta(newMeta);
                return newMeta;
            }
            if (args[0] instanceof clojure.truffle.runtime.ClojureVar cvar) {
                fArgs[0] = cvar.getMeta();
                if (fArgs[0] == null || fArgs[0] instanceof ClojureNil) {
                    fArgs[0] = clojure.lang.PersistentArrayMap.EMPTY;
                }
                Object newMeta = callFunction(f, fArgs);
                if (newMeta instanceof clojure.lang.IPersistentMap m) {
                    cvar.setMeta(m);
                }
                return newMeta;
            }
            if (args[0] instanceof clojure.truffle.runtime.ClojureFunction cfn) {
                fArgs[0] = cfn.getMeta();
                if (fArgs[0] == null || fArgs[0] instanceof ClojureNil) {
                    fArgs[0] = clojure.lang.PersistentArrayMap.EMPTY;
                }
                Object newMeta = callFunction(f, fArgs);
                if (newMeta instanceof clojure.lang.IPersistentMap m) {
                    cfn.setMeta(m);
                }
                return newMeta;
            }
            if (args[0] instanceof clojure.truffle.runtime.MultiArityFunction maf) {
                fArgs[0] = maf.getMeta();
                if (fArgs[0] == null || fArgs[0] instanceof ClojureNil) {
                    fArgs[0] = clojure.lang.PersistentArrayMap.EMPTY;
                }
                Object newMeta = callFunction(f, fArgs);
                if (newMeta instanceof clojure.lang.IPersistentMap m) {
                    maf.setMeta(m);
                }
                return newMeta;
            }
            if (args[0] instanceof NamedBuiltin nb) {
                fArgs[0] = nb.getMeta();
                if (fArgs[0] == null || fArgs[0] instanceof ClojureNil) {
                    fArgs[0] = clojure.lang.PersistentArrayMap.EMPTY;
                }
                Object newMeta = callFunction(f, fArgs);
                if (newMeta instanceof clojure.lang.IPersistentMap m) {
                    nb.setMeta(m);
                }
                return newMeta;
            }
            // For other IObj types, try with-meta approach
            if (args[0] instanceof clojure.lang.IObj iobj) {
                fArgs[0] = iobj.meta();
                if (fArgs[0] == null) fArgs[0] = clojure.lang.PersistentArrayMap.EMPTY;
                Object newMeta = callFunction(f, fArgs);
                if (newMeta instanceof clojure.lang.IPersistentMap m) {
                    // Can't mutate IObj, just return the result
                    return newMeta;
                }
            }
            // Fallback: apply f but can't store the result
            fArgs[0] = clojure.lang.PersistentArrayMap.EMPTY;
            return callFunction(f, fArgs);
        });

        // pmap
        defBuiltin("pmap", args -> {
            if (args.length < 2) throw new RuntimeException("pmap: expected at least 2 args");
            Object f = args[0];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(args[1]); seq != null; seq = seq.next())
                items.add(seq.first());
            java.util.List<java.util.concurrent.Future<Object>> futures = new ArrayList<>();
            java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newFixedThreadPool(
                            Math.min(items.size(), Runtime.getRuntime().availableProcessors() + 2),
                            r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
            try {
                for (Object item : items) {
                    futures.add(executor.submit(() -> callFunction(f, new Object[]{item})));
                }
                java.util.List<Object> results = new ArrayList<>();
                for (var future : futures) {
                    try {
                        results.add(future.get());
                    } catch (Exception e) {
                        throw new RuntimeException("pmap: " + e.getMessage(), e);
                    }
                }
                return clojure.lang.PersistentList.create(results);
            } finally {
                executor.shutdown();
            }
        });

        // bean
        defBuiltin("bean", args -> {
            checkArity(args, 1, "bean");
            Object obj = args[0];
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            try {
                java.beans.BeanInfo info = java.beans.Introspector.getBeanInfo(obj.getClass());
                for (java.beans.PropertyDescriptor pd : info.getPropertyDescriptors()) {
                    java.lang.reflect.Method getter = pd.getReadMethod();
                    if (getter != null) {
                        Object val = getter.invoke(obj);
                        result = result.assoc(clojure.lang.Keyword.intern(pd.getName()), val);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("bean: " + e.getMessage(), e);
            }
            return result;
        });

        // bases
        defBuiltin("bases", args -> {
            checkArity(args, 1, "bases");
            Class<?> clazz = (args[0] instanceof Class<?> c) ? c : args[0].getClass();
            java.util.List<Object> result = new ArrayList<>();
            Class<?> sup = clazz.getSuperclass();
            if (sup != null) result.add(sup);
            for (Class<?> iface : clazz.getInterfaces()) result.add(iface);
            return result.isEmpty() ? ClojureNil.INSTANCE
                    : clojure.lang.PersistentList.create(result);
        });

        // future?
        defBuiltin("future?", args -> {
            checkArity(args, 1, "future?");
            return args[0] instanceof java.util.concurrent.Future;
        });

        defBuiltin("delay?", args -> {
            checkArity(args, 1, "delay?");
            return args[0] instanceof clojure.lang.Delay;
        });

        // realized? enhancement for futures
        // (already exists, but ensure it handles futures)

        // bit operations
        defBuiltin("unsigned-bit-shift-right", args -> {
            checkArity(args, 2, "unsigned-bit-shift-right");
            return ((Number) args[0]).longValue() >>> ((Number) args[1]).longValue();
        });

        // --- Phase 16: Clojure conformance ---

        // Internal helper for assert
        defBuiltin("new-assertion-error", args -> {
            checkArity(args, 1, "new-assertion-error");
            return new AssertionError(args[0]);
        });

        // remove - filter complement
        defBuiltin("remove", args -> {
            if (args.length == 1) {
                // Transducer form: (remove pred) returns a transducer
                Object pred = args[0];
                return (BuiltinFunction) xfArgs -> {
                    checkArity(xfArgs, 1, "remove-transducer");
                    Object rf = xfArgs[0];
                    return new TransducerRf(rf) {
                        @Override public Object step(Object acc, Object input) {
                            if (!isTruthy(callFunction(pred, new Object[]{input}))) {
                                return callFunction(rf, new Object[]{acc, input});
                            }
                            return acc;
                        }
                    };
                };
            }
            if (args.length != 2) throw new RuntimeException("remove: expected 1 or 2 args");
            Object pred = args[0];
            Object coll = args[1];
            java.util.List<Object> result = new java.util.ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(coll); s != null; s = s.next()) {
                Object item = s.first();
                Object test = callFunction(pred, new Object[]{item});
                if (!isTruthy(test)) {
                    result.add(item);
                }
            }
            return result.isEmpty() ? clojure.lang.PersistentList.EMPTY
                    : clojure.lang.PersistentList.create(result);
        });

        // rseq - reverse of sorted/vector collections
        defBuiltin("rseq", args -> {
            checkArity(args, 1, "rseq");
            if (args[0] instanceof clojure.lang.Reversible r) {
                clojure.lang.ISeq result = r.rseq();
                return result == null ? ClojureNil.INSTANCE : result;
            }
            throw new RuntimeException("rseq: not reversible: " + args[0]);
        });

        // array-map - creates insertion-order-preserving map
        defBuiltin("array-map", args -> {
            if (args.length % 2 != 0) throw new RuntimeException("array-map: expects even number of args");
            return clojure.lang.PersistentArrayMap.createAsIfByAssoc(args);
        });

        // sorted-map-by
        defBuiltin("sorted-map-by", args -> {
            if (args.length < 1) throw new RuntimeException("sorted-map-by: requires comparator");
            Object comp = args[0];
            java.util.Comparator<Object> comparator = (a, b) -> {
                Object result = callFunction(comp, new Object[]{a, b});
                if (result instanceof Number n) return n.intValue();
                if (result instanceof Boolean bool) {
                    if (bool) return -1;
                    Object rev = callFunction(comp, new Object[]{b, a});
                    return (rev instanceof Boolean rb && rb) ? 1 : 0;
                }
                return 0;
            };
            Object[] kvs = new Object[args.length - 1];
            System.arraycopy(args, 1, kvs, 0, kvs.length);
            clojure.lang.PersistentTreeMap map = clojure.lang.PersistentTreeMap.create(comparator, null);
            for (int i = 0; i < kvs.length; i += 2) {
                map = (clojure.lang.PersistentTreeMap) map.assoc(kvs[i], kvs[i + 1]);
            }
            return map;
        });

        // macroexpand-1
        defBuiltin("macroexpand-1", args -> {
            checkArity(args, 1, "macroexpand-1");
            Object form = args[0];
            if (form instanceof clojure.lang.ISeq seq && seq.first() instanceof clojure.lang.Symbol sym) {
                Object macro = getMacro(sym.getName());
                if (macro != null) {
                    java.util.List<Object> macroArgs = new java.util.ArrayList<>();
                    // User-defined macros expect &form and &env as first two args
                    boolean isUserMacro = (macro instanceof clojure.truffle.runtime.ClojureFunction
                            || macro instanceof clojure.truffle.runtime.MultiArityFunction);
                    if (isUserMacro) {
                        macroArgs.add(form); // &form
                        macroArgs.add(clojure.truffle.runtime.ClojureNil.INSTANCE); // &env
                    }
                    for (clojure.lang.ISeq s = seq.next(); s != null; s = s.next()) {
                        macroArgs.add(s.first());
                    }
                    return callFunction(macro, macroArgs.toArray());
                }
            }
            return form; // not a macro call, return as-is
        });

        // macroexpand - repeatedly expand until form doesn't change
        defBuiltin("macroexpand", args -> {
            checkArity(args, 1, "macroexpand");
            Object form = args[0];
            while (true) {
                Object expanded = callFunction(getVar("macroexpand-1"), new Object[]{form});
                if (expanded == form || expanded.equals(form)) return expanded;
                form = expanded;
            }
        });

        // var? - check if object is a Var
        defBuiltin("var?", args -> {
            checkArity(args, 1, "var?");
            return args[0] instanceof clojure.truffle.runtime.ClojureVar;
        });

        // bound? - check if var is bound
        defBuiltin("bound?", args -> {
            checkArity(args, 1, "bound?");
            if (args[0] instanceof clojure.truffle.runtime.ClojureVar v) {
                return v.isBound();
            }
            return false;
        });

        // time* - internal helper for (time expr)
        defBuiltin("time*", args -> {
            checkArity(args, 1, "time*");
            long start = System.nanoTime();
            Object result = callFunction(args[0], new Object[]{});
            long elapsed = System.nanoTime() - start;
            double ms = elapsed / 1_000_000.0;
            writeOut(String.format("\"Elapsed time: %.6f msecs\"\n", ms));
            return result;
        });

        // with-in-str* - internal helper
        defBuiltin("with-in-str*", args -> {
            checkArity(args, 2, "with-in-str*");
            String s = args[0].toString();
            Object oldIn = globalVars.get("*in*");
            java.io.BufferedReader newIn = new java.io.BufferedReader(new java.io.StringReader(s));
            globalVars.put("*in*", newIn);
            try {
                return callFunction(args[1], new Object[]{});
            } finally {
                if (oldIn != null) globalVars.put("*in*", oldIn);
                else globalVars.remove("*in*");
            }
        });

        defBuiltin("read-line", args -> {
            Object in = globalVars.get("*in*");
            java.io.BufferedReader reader;
            if (in instanceof java.io.BufferedReader br) {
                reader = br;
            } else {
                reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            }
            try {
                String line = reader.readLine();
                return line == null ? ClojureNil.INSTANCE : line;
            } catch (java.io.IOException e) {
                throw new RuntimeException("read-line: " + e.getMessage());
            }
        });

        // not= - complement of =
        defBuiltin("not=", args -> {
            if (args.length < 2) throw new RuntimeException("not=: expected at least 2 args");
            return !clojure.lang.Util.equiv(args[0], args[1]);
        });

        // == (numeric equality)
        defBuiltin("==", args -> {
            if (args.length < 2) throw new RuntimeException("==: expected at least 2 args");
            for (int i = 1; i < args.length; i++) {
                if (((Number)args[0]).doubleValue() != ((Number)args[i]).doubleValue()) return false;
            }
            return true;
        });

        // supers - returns set of supertypes
        defBuiltin("supers", args -> {
            checkArity(args, 1, "supers");
            Class<?> clazz = (Class<?>) args[0];
            java.util.Set<Class<?>> result = new java.util.HashSet<>();
            java.util.Queue<Class<?>> queue = new java.util.LinkedList<>();
            if (clazz.getSuperclass() != null) queue.add(clazz.getSuperclass());
            for (Class<?> iface : clazz.getInterfaces()) queue.add(iface);
            while (!queue.isEmpty()) {
                Class<?> c = queue.poll();
                if (result.add(c)) {
                    if (c.getSuperclass() != null) queue.add(c.getSuperclass());
                    for (Class<?> iface : c.getInterfaces()) queue.add(iface);
                }
            }
            return clojure.lang.PersistentHashSet.create(new java.util.ArrayList<>(result));
        });

        // class? - check if value is a Class
        defBuiltin("class?", args -> {
            checkArity(args, 1, "class?");
            return args[0] instanceof Class;
        });

        // cast
        defBuiltin("cast", args -> {
            checkArity(args, 2, "cast");
            Class<?> clazz = (Class<?>) args[0];
            if (args[1] == ClojureNil.INSTANCE || args[1] == null) return ClojureNil.INSTANCE;
            if (!clazz.isInstance(args[1])) {
                throw new ClassCastException("Cannot cast " + args[1].getClass().getName() + " to " + clazz.getName());
            }
            return args[1];
        });

        // num / long / double / int / short / byte / float / char coercion
        defBuiltin("num", args -> { checkArity(args, 1, "num"); return args[0]; });
        defBuiltin("long", args -> { checkArity(args, 1, "long"); return ((Number) args[0]).longValue(); });
        defBuiltin("double", args -> { checkArity(args, 1, "double"); return ((Number) args[0]).doubleValue(); });
        defBuiltin("int", args -> {
            checkArity(args, 1, "int");
            if (args[0] instanceof Character c) return (long) (int) c;
            return (long)((Number) args[0]).intValue();
        });
        defBuiltin("short", args -> { checkArity(args, 1, "short"); return (long)((Number) args[0]).shortValue(); });
        defBuiltin("byte", args -> { checkArity(args, 1, "byte"); return (long)((Number) args[0]).byteValue(); });
        defBuiltin("float", args -> { checkArity(args, 1, "float"); return (double)((Number) args[0]).floatValue(); });
        defBuiltin("char", args -> {
            checkArity(args, 1, "char");
            if (args[0] instanceof Character) return args[0];
            if (args[0] instanceof Number n) return (char) n.intValue();
            throw new RuntimeException("char: cannot coerce " + args[0]);
        });
        defBuiltin("boolean", args -> {
            checkArity(args, 1, "boolean");
            if (args[0] == null || args[0] == ClojureNil.INSTANCE) return false;
            if (args[0] instanceof Boolean b) return b;
            return true;
        });

        // realized? - check if delay/lazy-seq/future/promise is realized
        defBuiltin("realized?", args -> {
            checkArity(args, 1, "realized?");
            if (args[0] instanceof clojure.truffle.runtime.ClojurePromise p) return p.isRealized();
            if (args[0] instanceof clojure.truffle.runtime.LazySeq ls) return ls.isRealized();
            if (args[0] instanceof clojure.lang.IPending p) return p.isRealized();
            if (args[0] instanceof java.util.concurrent.Future<?> f) return f.isDone();
            return true; // regular values are always "realized"
        });

        // flatten
        defBuiltin("flatten", args -> {
            checkArity(args, 1, "flatten");
            java.util.List<Object> result = new java.util.ArrayList<>();
            flattenHelper(args[0], result);
            return clojure.lang.PersistentList.create(result);
        });

        // group-by
        defBuiltin("group-by", args -> {
            checkArity(args, 2, "group-by");
            Object f = args[0];
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object item = s.first();
                Object key = callFunction(f, new Object[]{item});
                Object existing = result.valAt(key);
                clojure.lang.IPersistentVector vec;
                if (existing == null) {
                    vec = clojure.lang.PersistentVector.create(item);
                } else {
                    vec = (clojure.lang.IPersistentVector) ((clojure.lang.IPersistentVector) existing).cons(item);
                }
                result = result.assoc(key, vec);
            }
            return result;
        });

        // frequencies
        defBuiltin("frequencies", args -> {
            checkArity(args, 1, "frequencies");
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next()) {
                Object item = s.first();
                Object count = result.valAt(item);
                long n = (count == null) ? 0L : ((Number) count).longValue();
                result = result.assoc(item, n + 1);
            }
            return result;
        });

        // partition-by
        defBuiltin("partition-by", args -> {
            checkArity(args, 2, "partition-by");
            Object f = args[0];
            java.util.List<Object> result = new java.util.ArrayList<>();
            java.util.List<Object> current = new java.util.ArrayList<>();
            Object lastKey = new Object(); // sentinel
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object item = s.first();
                Object key = callFunction(f, new Object[]{item});
                if (!current.isEmpty() && !clojure.lang.Util.equiv(key, lastKey)) {
                    result.add(clojure.lang.PersistentList.create(current));
                    current = new java.util.ArrayList<>();
                }
                current.add(item);
                lastKey = key;
            }
            if (!current.isEmpty()) result.add(clojure.lang.PersistentList.create(current));
            return clojure.lang.PersistentList.create(result);
        });

        // map-indexed
        defBuiltin("map-indexed", args -> {
            if (args.length == 1) {
                // Transducer form: (map-indexed f) returns a transducer
                Object f = args[0];
                return (BuiltinFunction) xfArgs -> {
                    checkArity(xfArgs, 1, "map-indexed-transducer");
                    Object rf = xfArgs[0];
                    long[] idx = {0};
                    return new TransducerRf(rf) {
                        @Override public Object step(Object acc, Object input) {
                            Object result = callFunction(rf, new Object[]{acc, callFunction(f, new Object[]{idx[0], input})});
                            idx[0]++;
                            return result;
                        }
                    };
                };
            }
            checkArity(args, 2, "map-indexed");
            Object f = args[0];
            java.util.List<Object> result = new java.util.ArrayList<>();
            long idx = 0;
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                result.add(callFunction(f, new Object[]{idx, s.first()}));
                idx++;
            }
            return clojure.lang.PersistentList.create(result);
        });

        // juxt
        defBuiltin("juxt", args -> {
            Object[] fns = args.clone();
            return (BuiltinFunction) innerArgs -> {
                java.util.List<Object> results = new java.util.ArrayList<>();
                for (Object fn : fns) {
                    results.add(callFunction(fn, innerArgs));
                }
                return clojure.lang.PersistentVector.create(results);
            };
        });

        // fnil
        defBuiltin("fnil", args -> {
            if (args.length < 2) throw new RuntimeException("fnil: expected at least 2 args");
            Object f = args[0];
            Object[] defaults = new Object[args.length - 1];
            System.arraycopy(args, 1, defaults, 0, defaults.length);
            return (BuiltinFunction) innerArgs -> {
                Object[] fixed = new Object[innerArgs.length];
                for (int i = 0; i < innerArgs.length; i++) {
                    if ((innerArgs[i] == null || innerArgs[i] == ClojureNil.INSTANCE) && i < defaults.length) {
                        fixed[i] = defaults[i];
                    } else {
                        fixed[i] = innerArgs[i];
                    }
                }
                return callFunction(f, fixed);
            };
        });

        // update-in
        defBuiltin("update-in", args -> {
            if (args.length < 3) throw new RuntimeException("update-in: expected at least 3 args");
            Object m = args[0];
            clojure.lang.IPersistentVector ks = (clojure.lang.IPersistentVector) args[1];
            Object f = args[2];
            Object[] extraArgs = new Object[args.length - 3];
            System.arraycopy(args, 3, extraArgs, 0, extraArgs.length);
            return updateIn(m, ks, 0, f, extraArgs);
        });

        // assoc-in
        defBuiltin("assoc-in", args -> {
            checkArity(args, 3, "assoc-in");
            Object m = args[0];
            clojure.lang.IPersistentVector ks = (clojure.lang.IPersistentVector) args[1];
            Object v = args[2];
            return assocIn(m, ks, 0, v);
        });

        // get-in
        defBuiltin("get-in", args -> {
            if (args.length < 2) throw new RuntimeException("get-in: expected at least 2 args");
            Object m = args[0];
            clojure.lang.IPersistentVector ks = (clojure.lang.IPersistentVector) args[1];
            Object notFound = args.length > 2 ? args[2] : ClojureNil.INSTANCE;
            for (int i = 0; i < ks.count(); i++) {
                if (m == null || m == ClojureNil.INSTANCE) return notFound;
                if (m instanceof clojure.lang.ILookup lk) {
                    m = lk.valAt(ks.nth(i), notFound);
                } else if (m instanceof java.util.Map<?,?> map) {
                    Object key = ks.nth(i);
                    m = map.containsKey(key) ? map.get(key) : notFound;
                } else {
                    return notFound;
                }
            }
            return m == null ? ClojureNil.INSTANCE : m;
        });

        // select-keys
        defBuiltin("select-keys", args -> {
            checkArity(args, 2, "select-keys");
            clojure.lang.IPersistentMap m = (clojure.lang.IPersistentMap) args[0];
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object key = s.first();
                clojure.lang.IMapEntry entry = m.entryAt(key);
                if (entry != null) {
                    result = result.assoc(key, entry.val());
                }
            }
            return result;
        });

        // zipmap
        defBuiltin("zipmap", args -> {
            checkArity(args, 2, "zipmap");
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            if (args[0] == null || args[0] instanceof ClojureNil ||
                args[1] == null || args[1] instanceof ClojureNil) return result;
            clojure.lang.ISeq ks = seqOf(args[0]);
            clojure.lang.ISeq vs = seqOf(args[1]);
            while (ks != null && vs != null) {
                result = result.assoc(ks.first(), vs.first());
                ks = ks.next();
                vs = vs.next();
            }
            return result;
        });

        // sorted-set-by
        defBuiltin("sorted-set-by", args -> {
            if (args.length < 1) throw new RuntimeException("sorted-set-by: requires comparator");
            Object comp = args[0];
            java.util.Comparator<Object> comparator = (a, b) -> {
                Object result = callFunction(comp, new Object[]{a, b});
                if (result instanceof Number n) return n.intValue();
                if (result instanceof Boolean bool) {
                    if (bool) return -1;
                    Object rev = callFunction(comp, new Object[]{b, a});
                    return (rev instanceof Boolean rb && rb) ? 1 : 0;
                }
                return 0;
            };
            clojure.lang.PersistentTreeSet s = clojure.lang.PersistentTreeSet.create(comparator, null);
            for (int i = 1; i < args.length; i++) {
                s = (clojure.lang.PersistentTreeSet) s.cons(args[i]);
            }
            return s;
        });

        // re-find, re-matches, re-seq enhancement - ensure these exist
        globalVars.putIfAbsent("re-groups", (BuiltinFunction) args -> {
            checkArity(args, 1, "re-groups");
            java.util.regex.Matcher m = (java.util.regex.Matcher) args[0];
            if (m.groupCount() == 0) return m.group();
            java.util.List<Object> groups = new java.util.ArrayList<>();
            for (int i = 0; i <= m.groupCount(); i++) {
                String g = m.group(i);
                groups.add(g == null ? ClojureNil.INSTANCE : g);
            }
            return clojure.lang.PersistentVector.create(groups);
        });

        // re-matcher
        globalVars.putIfAbsent("re-matcher", (BuiltinFunction) args -> {
            checkArity(args, 2, "re-matcher");
            java.util.regex.Pattern p = (java.util.regex.Pattern) args[0];
            return p.matcher((String) args[1]);
        });

        // --- Phase 17: Namespace system ---

        defBuiltin("ns-name", args -> {
            checkArity(args, 1, "ns-name");
            ClojureNamespace ns = resolveNsArg(args[0]);
            return clojure.lang.Symbol.intern(ns.getName());
        });

        defBuiltin("the-ns", args -> {
            checkArity(args, 1, "the-ns");
            if (args[0] instanceof ClojureNamespace ns) return ns;
            String nsName = nsNameFromArg(args[0]);
            ClojureNamespace ns = getNamespace(nsName);
            if (ns == null) throw new RuntimeException("No namespace: " + nsName + " found");
            return ns;
        });

        defBuiltin("create-ns", args -> {
            checkArity(args, 1, "create-ns");
            return getOrCreateNamespace(nsNameFromArg(args[0]));
        });

        defBuiltin("remove-ns", args -> {
            checkArity(args, 1, "remove-ns");
            String nsName = nsNameFromArg(args[0]);
            ClojureNamespace removed = namespaces.remove(nsName);
            return removed == null ? ClojureNil.INSTANCE : removed;
        });

        defBuiltin("find-ns", args -> {
            checkArity(args, 1, "find-ns");
            ClojureNamespace ns = getNamespace(nsNameFromArg(args[0]));
            return ns == null ? ClojureNil.INSTANCE : ns;
        });

        defBuiltin("all-ns", args -> {
            return clojure.lang.PersistentList.create(new java.util.ArrayList<>(namespaces.values()));
        });

        defBuiltin("ns-publics", args -> {
            checkArity(args, 1, "ns-publics");
            ClojureNamespace ns = resolveNsArg(args[0]);
            return nsMapToClojure(ns.getInterns());
        });

        defBuiltin("ns-interns", args -> {
            checkArity(args, 1, "ns-interns");
            ClojureNamespace ns = resolveNsArg(args[0]);
            String nsName = ns.getName();
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (var entry : ns.getInterns().entrySet()) {
                result = result.assoc(clojure.lang.Symbol.intern(entry.getKey()),
                    new clojure.truffle.runtime.ClojureVar(this, nsName, entry.getKey()));
            }
            return result;
        });

        defBuiltin("ns-refers", args -> {
            checkArity(args, 1, "ns-refers");
            ClojureNamespace ns = resolveNsArg(args[0]);
            return nsMapToClojure(ns.getRefers());
        });

        defBuiltin("ns-imports", args -> {
            checkArity(args, 1, "ns-imports");
            ClojureNamespace ns = resolveNsArg(args[0]);
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (var entry : ns.getImports().entrySet()) {
                result = result.assoc(clojure.lang.Symbol.intern(entry.getKey()), entry.getValue());
            }
            return result;
        });

        defBuiltin("ns-aliases", args -> {
            checkArity(args, 1, "ns-aliases");
            ClojureNamespace ns = resolveNsArg(args[0]);
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (var entry : ns.getAliases().entrySet()) {
                result = result.assoc(clojure.lang.Symbol.intern(entry.getKey()), entry.getValue());
            }
            return result;
        });

        defBuiltin("ns-unalias", args -> {
            checkArity(args, 2, "ns-unalias");
            ClojureNamespace ns = resolveNsArg(args[0]);
            String alias = nsNameFromArg(args[1]);
            ns.unalias(alias);
            return ClojureNil.INSTANCE;
        });

        defBuiltin("alias", args -> {
            checkArity(args, 2, "alias");
            String aliasName = nsNameFromArg(args[0]);
            String nsName = nsNameFromArg(args[1]);
            ClojureNamespace currentNs = getOrCreateNamespace(currentNamespace);
            currentNs.alias(aliasName, getOrCreateNamespace(nsName));
            // Also register in Clojure's namespace system for LispReader
            try {
                clojure.lang.Namespace clojureCurrentNs = clojure.lang.Namespace.findOrCreate(
                        clojure.lang.Symbol.intern(currentNamespace));
                clojure.lang.Namespace clojureTargetNs = clojure.lang.Namespace.findOrCreate(
                        clojure.lang.Symbol.intern(nsName));
                clojureCurrentNs.addAlias(clojure.lang.Symbol.intern(aliasName), clojureTargetNs);
            } catch (Exception ignored) {}
            return ClojureNil.INSTANCE;
        });

        defBuiltin("ns-unmap", args -> {
            checkArity(args, 2, "ns-unmap");
            ClojureNamespace ns = resolveNsArg(args[0]);
            String sym = nsNameFromArg(args[1]);
            ns.unmap(sym);
            return ClojureNil.INSTANCE;
        });

        defBuiltin("ns-map", args -> {
            checkArity(args, 1, "ns-map");
            ClojureNamespace ns = resolveNsArg(args[0]);
            return nsMapToClojure(ns.getMap());
        });

        defBuiltin("ns-resolve", args -> {
            if (args.length < 2) throw new RuntimeException("ns-resolve: expected 2 args");
            ClojureNamespace ns = resolveNsArg(args[0]);
            String symName = nsNameFromArg(args[1]);
            Object val = ns.resolve(symName);
            return val == null ? ClojureNil.INSTANCE : new clojure.truffle.runtime.ClojureVar(this, ns.getName(), symName);
        });

        defBuiltin("resolve", args -> {
            // (resolve sym) or (resolve env sym) — env is ignored in our implementation
            if (args.length < 1 || args.length > 2) throw new RuntimeException("resolve: expected 1 or 2 args, got " + args.length);
            Object symArg = args.length == 2 ? args[1] : args[0];
            String symName;
            if (symArg instanceof clojure.lang.Symbol sym) {
                // Preserve namespace qualifier: taoensso.truss/have -> "taoensso.truss/have"
                symName = sym.getNamespace() != null ? sym.getNamespace() + "/" + sym.getName() : sym.getName();
            } else {
                symName = nsNameFromArg(symArg);
            }
            Object val = getVarWithBindings(symName);
            if (val == null) return ClojureNil.INSTANCE;
            return new clojure.truffle.runtime.ClojureVar(this, currentNamespace, symName);
        });

        defBuiltin("find-var", args -> {
            checkArity(args, 1, "find-var");
            if (!(args[0] instanceof clojure.lang.Symbol sym)) {
                throw new RuntimeException("find-var: expected a qualified symbol, got " + args[0]);
            }
            String ns = sym.getNamespace();
            String name = sym.getName();
            if (ns == null) {
                throw new RuntimeException("find-var: symbol must be namespace-qualified: " + sym);
            }
            ClojureNamespace targetNs = namespaces.get(ns);
            if (targetNs == null) return ClojureNil.INSTANCE;
            Object val = targetNs.resolve(name);
            if (val == null) return ClojureNil.INSTANCE;
            return new clojure.truffle.runtime.ClojureVar(this, ns, name);
        });

        defBuiltin("var-get", args -> {
            checkArity(args, 1, "var-get");
            if (args[0] instanceof clojure.truffle.runtime.ClojureVar cvar) {
                Object val = cvar.deref();
                return val == null ? ClojureNil.INSTANCE : val;
            }
            throw new RuntimeException("var-get: expected a Var, got " + args[0].getClass().getName());
        });

        defBuiltin("requiring-resolve", args -> {
            checkArity(args, 1, "requiring-resolve");
            if (!(args[0] instanceof clojure.lang.Symbol sym)) {
                throw new RuntimeException("requiring-resolve: expected a qualified symbol");
            }
            String ns = sym.getNamespace();
            if (ns == null) {
                throw new RuntimeException("requiring-resolve: symbol must be namespace-qualified: " + sym);
            }
            try {
                loadNamespace(ns);
            } catch (Exception ignored) {}
            // Resolve the var from the namespace
            ClojureNamespace targetNs = namespaces.get(ns);
            if (targetNs != null) {
                Object val = targetNs.resolve(sym.getName());
                if (val != null) {
                    return new clojure.truffle.runtime.ClojureVar(this, ns, sym.getName());
                }
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("intern", args -> {
            if (args.length < 2 || args.length > 3) throw new RuntimeException("intern: expected 2-3 args");
            ClojureNamespace ns = resolveNsArg(args[0]);
            String symName = nsNameFromArg(args[1]);
            if (args.length == 3) {
                ns.intern(symName, args[2]);
            }
            return new clojure.truffle.runtime.ClojureVar(this, ns.getName(), symName);
        });

        globalVars.put("*ns*", ClojureNil.INSTANCE); // placeholder, resolved dynamically
        defBuiltin("namespace", args -> {
            checkArity(args, 1, "namespace");
            if (args[0] instanceof clojure.lang.Symbol sym) {
                String ns = sym.getNamespace();
                return ns == null ? ClojureNil.INSTANCE : ns;
            }
            if (args[0] instanceof clojure.lang.Keyword kw) {
                String ns = kw.getNamespace();
                return ns == null ? ClojureNil.INSTANCE : ns;
            }
            return ClojureNil.INSTANCE;
        });

        // --- Phase 19: Missing builtin functions ---

        defBuiltin("parse-long", args -> {
            checkArity(args, 1, "parse-long");
            if (args[0] instanceof String s) {
                try { return Long.parseLong(s.trim()); }
                catch (NumberFormatException e) { return ClojureNil.INSTANCE; }
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("parse-double", args -> {
            checkArity(args, 1, "parse-double");
            if (args[0] instanceof String s) {
                try { return Double.parseDouble(s.trim()); }
                catch (NumberFormatException e) { return ClojureNil.INSTANCE; }
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("parse-boolean", args -> {
            checkArity(args, 1, "parse-boolean");
            if (args[0] instanceof String s) {
                if ("true".equals(s)) return Boolean.TRUE;
                if ("false".equals(s)) return Boolean.FALSE;
                return ClojureNil.INSTANCE;
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("parse-uuid", args -> {
            checkArity(args, 1, "parse-uuid");
            if (args[0] instanceof String s) {
                try { return java.util.UUID.fromString(s.trim()); }
                catch (IllegalArgumentException e) { return ClojureNil.INSTANCE; }
            }
            return ClojureNil.INSTANCE;
        });

        defBuiltin("random-uuid", args -> {
            checkArity(args, 0, "random-uuid");
            return java.util.UUID.randomUUID();
        });

        defBuiltin("print-str", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                Object v = args[i];
                if (v == null || v instanceof ClojureNil) sb.append("nil");
                else sb.append(v);
            }
            return sb.toString();
        });

        defBuiltin("println-str", args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(' ');
                Object v = args[i];
                if (v == null || v instanceof ClojureNil) sb.append("nil");
                else sb.append(v);
            }
            sb.append('\n');
            return sb.toString();
        });

        defBuiltin("printf", args -> {
            if (args.length < 1) throw new RuntimeException("printf: expected at least 1 arg");
            String fmt = args[0].toString();
            Object[] fmtArgs = new Object[args.length - 1];
            for (int i = 1; i < args.length; i++) {
                Object a = args[i];
                if (a instanceof ClojureNil) fmtArgs[i - 1] = null;
                else fmtArgs[i - 1] = a;
            }
            writeOut(String.format(fmt, fmtArgs));
            return ClojureNil.INSTANCE;
        });

        defBuiltin("rational?", args -> {
            checkArity(args, 1, "rational?");
            return args[0] instanceof Long || args[0] instanceof Integer
                    || args[0] instanceof Short || args[0] instanceof Byte
                    || args[0] instanceof clojure.lang.Ratio
                    || args[0] instanceof java.math.BigInteger
                    || args[0] instanceof java.math.BigDecimal
                    || args[0] instanceof clojure.lang.BigInt;
        });

        defBuiltin("decimal?", args -> {
            checkArity(args, 1, "decimal?");
            return args[0] instanceof java.math.BigDecimal;
        });

        defBuiltin("ident?", args -> {
            checkArity(args, 1, "ident?");
            return args[0] instanceof clojure.lang.Keyword || args[0] instanceof clojure.lang.Symbol;
        });

        defBuiltin("simple-ident?", args -> {
            checkArity(args, 1, "simple-ident?");
            if (args[0] instanceof clojure.lang.Keyword kw) return kw.getNamespace() == null;
            if (args[0] instanceof clojure.lang.Symbol sym) return sym.getNamespace() == null;
            return false;
        });

        defBuiltin("qualified-ident?", args -> {
            checkArity(args, 1, "qualified-ident?");
            if (args[0] instanceof clojure.lang.Keyword kw) return kw.getNamespace() != null;
            if (args[0] instanceof clojure.lang.Symbol sym) return sym.getNamespace() != null;
            return false;
        });

        defBuiltin("simple-keyword?", args -> {
            checkArity(args, 1, "simple-keyword?");
            if (args[0] instanceof clojure.lang.Keyword kw) return kw.getNamespace() == null;
            return false;
        });

        defBuiltin("qualified-keyword?", args -> {
            checkArity(args, 1, "qualified-keyword?");
            if (args[0] instanceof clojure.lang.Keyword kw) return kw.getNamespace() != null;
            return false;
        });

        defBuiltin("simple-symbol?", args -> {
            checkArity(args, 1, "simple-symbol?");
            if (args[0] instanceof clojure.lang.Symbol sym) return sym.getNamespace() == null;
            return false;
        });

        defBuiltin("qualified-symbol?", args -> {
            checkArity(args, 1, "qualified-symbol?");
            if (args[0] instanceof clojure.lang.Symbol sym) return sym.getNamespace() != null;
            return false;
        });

        defBuiltin("inst?", args -> {
            checkArity(args, 1, "inst?");
            return args[0] instanceof java.util.Date;
        });

        defBuiltin("uuid?", args -> {
            checkArity(args, 1, "uuid?");
            return args[0] instanceof java.util.UUID;
        });

        defBuiltin("uri?", args -> {
            checkArity(args, 1, "uri?");
            return args[0] instanceof java.net.URI;
        });

        defBuiltin("any?", args -> {
            checkArity(args, 1, "any?");
            return true;
        });

        defBuiltin("NaN?", args -> {
            checkArity(args, 1, "NaN?");
            if (args[0] instanceof Double d) return Double.isNaN(d);
            if (args[0] instanceof Float f) return Float.isNaN(f);
            return false;
        });

        defBuiltin("infinite?", args -> {
            checkArity(args, 1, "infinite?");
            if (args[0] instanceof Double d) return Double.isInfinite(d);
            if (args[0] instanceof Float f) return Float.isInfinite(f);
            return false;
        });

        defBuiltin("pos-int?", args -> {
            checkArity(args, 1, "pos-int?");
            if (args[0] instanceof Long l) return l > 0;
            if (args[0] instanceof Integer i) return i > 0;
            return false;
        });

        defBuiltin("neg-int?", args -> {
            checkArity(args, 1, "neg-int?");
            if (args[0] instanceof Long l) return l < 0;
            if (args[0] instanceof Integer i) return i < 0;
            return false;
        });

        defBuiltin("nat-int?", args -> {
            checkArity(args, 1, "nat-int?");
            if (args[0] instanceof Long l) return l >= 0;
            if (args[0] instanceof Integer i) return i >= 0;
            return false;
        });

        defBuiltin("bytes?", args -> {
            checkArity(args, 1, "bytes?");
            return args[0] instanceof byte[];
        });

        defBuiltin("indexed?", args -> {
            checkArity(args, 1, "indexed?");
            return args[0] instanceof clojure.lang.Indexed;
        });

        defBuiltin("seqable?", args -> {
            checkArity(args, 1, "seqable?");
            if (args[0] == null || args[0] instanceof ClojureNil) return true;
            return args[0] instanceof clojure.lang.Seqable
                    || args[0] instanceof Iterable
                    || args[0] instanceof CharSequence
                    || args[0] instanceof java.util.Map
                    || args[0].getClass().isArray();
        });

        // --- Phase 21: Missing predicates & functions ---

        defBuiltin("byte?", args -> {
            checkArity(args, 1, "byte?");
            return args[0] instanceof Byte;
        });

        defBuiltin("short?", args -> {
            checkArity(args, 1, "short?");
            return args[0] instanceof Short;
        });

        defBuiltin("extends?", args -> {
            checkArity(args, 2, "extends?");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureProtocol proto))
                throw new RuntimeException("extends?: first arg must be a protocol");
            return proto.hasImplementationForType(args[1]);
        });

        defBuiltin("get-method", args -> {
            checkArity(args, 2, "get-method");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureMultiMethod mm))
                throw new RuntimeException("get-method: first arg must be a multimethod");
            Object result = mm.getMethod(args[1]);
            return result == null ? ClojureNil.INSTANCE : result;
        });

        defBuiltin("replace", args -> {
            checkArity(args, 2, "replace");
            Object smap = args[0];
            Object coll = args[1];
            if (coll instanceof clojure.lang.IPersistentVector v) {
                java.util.List<Object> result = new java.util.ArrayList<>();
                for (int i = 0; i < v.count(); i++) {
                    Object item = v.nth(i);
                    Object replacement = clojure.truffle.runtime.ClojureRT.get(smap, item);
                    result.add(replacement != null ? replacement : item);
                }
                return clojure.lang.PersistentVector.create(result);
            }
            // For seqs
            clojure.lang.ISeq s = seqOf(coll);
            java.util.List<Object> result = new java.util.ArrayList<>();
            while (s != null) {
                Object item = s.first();
                Object replacement = clojure.truffle.runtime.ClojureRT.get(smap, item);
                result.add(replacement != null ? replacement : item);
                s = s.next();
            }
            return clojure.lang.PersistentList.create(result);
        });

        defBuiltin("halt-when", args -> {
            if (args.length < 1 || args.length > 2) throw new RuntimeException("halt-when: 1-2 args");
            Object pred = args[0];
            Object retf = args.length == 2 ? args[1] : null;
            final Object finalRetf = retf;
            ClojureContext ctx = this;
            return (BuiltinFunction) xfArgs -> {
                checkArity(xfArgs, 1, "halt-when xform");
                Object rf = xfArgs[0];
                return (BuiltinFunction) stepArgs -> {
                    if (stepArgs.length == 0) return ctx.callFunction(rf, new Object[0]);
                    if (stepArgs.length == 1) return ctx.callFunction(rf, stepArgs);
                    Object result = stepArgs[0];
                    Object input = stepArgs[1];
                    if (isTruthy(ctx.callFunction(pred, new Object[]{input}))) {
                        Object haltVal = finalRetf != null
                                ? ctx.callFunction(finalRetf, new Object[]{result, input})
                                : result;
                        return new Reduced(haltVal);
                    }
                    return ctx.callFunction(rf, stepArgs);
                };
            };
        });

        // --- Phase 22: monitor-enter/monitor-exit & ensure ---

        defBuiltin("monitor-enter", args -> {
            checkArity(args, 1, "monitor-enter");
            // In practice, monitor-enter/exit are handled by locking macro
            // This is a no-op placeholder for compatibility
            return ClojureNil.INSTANCE;
        });

        defBuiltin("monitor-exit", args -> {
            checkArity(args, 1, "monitor-exit");
            return ClojureNil.INSTANCE;
        });

        defBuiltin("ensure", args -> {
            checkArity(args, 1, "ensure");
            // In simplified STM, ensure just returns current ref value
            if (args[0] instanceof clojure.truffle.runtime.ClojureRef ref) {
                return ref.deref();
            }
            throw new RuntimeException("ensure: arg must be a ref");
        });

        defBuiltin("delivered?", args -> {
            checkArity(args, 1, "delivered?");
            if (args[0] instanceof clojure.truffle.runtime.ClojurePromise p) {
                return p.isRealized();
            }
            throw new RuntimeException("delivered?: arg must be a promise");
        });

        defBuiltin("record?", args -> {
            checkArity(args, 1, "record?");
            if (args[0] instanceof clojure.lang.IRecord) return true;
            if (args[0] instanceof clojure.truffle.runtime.ClojureDeftypeInstance inst) {
                return inst.isRecord();
            }
            return false;
        });

        defBuiltin("await-for", args -> {
            if (args.length < 2) throw new RuntimeException("await-for: expected at least 2 args");
            long timeoutMs = ((Number) args[0]).longValue();
            for (int i = 1; i < args.length; i++) {
                if (args[i] instanceof clojure.truffle.runtime.ClojureAgent ag) {
                    ag.awaitActions(timeoutMs);
                }
            }
            // Return true if all agents have no error (simplified)
            for (int i = 1; i < args.length; i++) {
                if (args[i] instanceof clojure.truffle.runtime.ClojureAgent ag) {
                    if (ag.getError() != null) return false;
                }
            }
            return true;
        });

        // Copy all builtins into clojure.core namespace
        ClojureNamespace core = namespaces.get("clojure.core");
        if (core != null) {
            globalVars.forEach(core::intern);
        }
    }

    /**
     * Search for a resource (.clj file) in classpath entries, then classloader, then filesystem.
     */
    public java.io.InputStream findResource(String path) {
        // 1. Search -cp classpath entries (JARs and directories)
        for (String entry : classpathEntries) {
            java.io.File f = new java.io.File(entry);
            if (f.isDirectory()) {
                java.io.File target = new java.io.File(f, path);
                if (target.exists()) {
                    if (path.contains("spec/alpha")) if (DEBUG) System.err.println("[FIND] dir hit: " + target + " size=" + target.length());
                    try { return new java.io.FileInputStream(target); }
                    catch (java.io.FileNotFoundException e) { /* continue */ }
                }
            } else if (f.isFile() && f.getName().endsWith(".jar")) {
                try {
                    java.util.jar.JarFile jar = new java.util.jar.JarFile(f);
                    java.util.jar.JarEntry je = jar.getJarEntry(path);
                    if (je != null) {
                        if (path.contains("spec/alpha")) if (DEBUG) System.err.println("[FIND] jar hit: " + f + " entry=" + je.getName() + " size=" + je.getSize() + " compSize=" + je.getCompressedSize());
                        // Read into byte array so we can close the JarFile
                        java.io.InputStream jis = jar.getInputStream(je);
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = jis.read(buf)) != -1) {
                            baos.write(buf, 0, n);
                        }
                        jis.close();
                        jar.close();
                        return new java.io.ByteArrayInputStream(baos.toByteArray());
                    }
                    jar.close();
                } catch (java.io.IOException e) { /* continue */ }
            }
        }
        // 2. Classloader (for classes bundled in the binary)
        java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(path);
        if (is != null) return is;
        // 3. Filesystem (current dir, src/)
        java.io.File file = new java.io.File(path);
        if (!file.exists()) file = new java.io.File("src/" + path);
        if (file.exists()) {
            try { return new java.io.FileInputStream(file); }
            catch (java.io.FileNotFoundException e) { /* fall through */ }
        }
        return null;
    }

    /** Write text to current output (respects with-out-str override) */
    private void writeOut(String text) {
        java.io.Writer w = outOverride.get();
        if (w != null) {
            try { w.write(text); } catch (java.io.IOException e) { throw new RuntimeException(e); }
        } else {
            new PrintStream(env.out()).print(text);
        }
    }

    @TruffleBoundary
    public Object callFunction(Object fn, Object[] args) {
        // Dereference ClojureVar to its value
        if (fn instanceof clojure.truffle.runtime.ClojureVar cvar) {
            Object val = cvar.deref();
            return callFunction(val, args);
        }
        if (fn instanceof ClojureFunction clf) {
                Object[] callArgs = new Object[args.length + 1];
                callArgs[0] = clf;
                System.arraycopy(args, 0, callArgs, 1, args.length);
                return clf.getCallTarget().call(callArgs);
            } else if (fn instanceof MultiArityFunction maf) {
                ClojureFunction clf = maf.resolve(args.length);
                Object[] callArgs = new Object[args.length + 1];
                callArgs[0] = clf;
                System.arraycopy(args, 0, callArgs, 1, args.length);
                return clf.getCallTarget().call(callArgs);
            } else if (fn instanceof BuiltinFunction builtin) {
                return builtin.execute(args);
            } else if (fn instanceof ClojureMultiMethod mm) {
                return mm.invoke(args);
            } else if (fn instanceof clojure.lang.Keyword kw) {
                // Keyword as function: (:key map) → (get map :key)
                if (args.length < 1 || args.length > 2)
                    throw new IllegalArgumentException("Wrong number of args (" + args.length + ") passed to: " + kw);
                Object map = args[0];
                if (map instanceof clojure.lang.ILookup lookup) {
                        Object notFound = args.length == 2 ? args[1] : ClojureNil.INSTANCE;
                    Object val = lookup.valAt(kw, notFound);
                    return val == null ? ClojureNil.INSTANCE : val;
                }
                // IPersistentSet doesn't implement ILookup but supports get()
                if (map instanceof clojure.lang.IPersistentSet s) {
                    Object val = s.get(kw);
                    return val == null ? (args.length == 2 ? args[1] : ClojureNil.INSTANCE) : val;
                }
                return args.length == 2 ? args[1] : ClojureNil.INSTANCE;
            } else if (fn instanceof clojure.lang.IPersistentSet s) {
                if (args.length != 1)
                    throw new RuntimeException("Set lookup expects 1 arg");
                Object val = s.get(args[0]);
                return val == null ? ClojureNil.INSTANCE : val;
            } else if (fn instanceof clojure.lang.IPersistentMap m) {
                if (args.length < 1 || args.length > 2)
                    throw new RuntimeException("Map lookup expects 1 or 2 args");
                Object val = m.valAt(args[0],
                        args.length == 2 ? args[1] : ClojureNil.INSTANCE);
                return val == null ? ClojureNil.INSTANCE : val;
            } else if (fn instanceof clojure.lang.IPersistentVector v) {
                if (args.length != 1)
                    throw new RuntimeException("Vector lookup expects 1 arg");
                int idx = ((Number) args[0]).intValue();
                return v.nth(idx);
            }
            throw new RuntimeException("Not a function: " + fn);
    }

    // --- eval ---

    public Object evalForm(Object form) {
        Analyzer analyzer = new Analyzer(language);
        analyzer.setContext(this);
        ExpressionNode node = analyzer.analyzeForm(form);
        EvalRootNode root = new EvalRootNode(language, analyzer.getFrameDescriptor(),
                new ExpressionNode[]{node});
        return root.getCallTarget().call();
    }

    // --- Seq helper ---

    private static clojure.lang.ISeq seqOf(Object coll) {
        if (coll == null || coll instanceof ClojureNil) return null;
        if (coll instanceof LazySeq ls) return ls.seq();
        if (coll instanceof clojure.lang.ISeq seq) {
            // Must call seq() to normalize: EmptyList is ISeq but seq() returns null
            return seq.seq();
        }
        if (coll instanceof clojure.lang.Seqable s) return s.seq();
        // Delegate to RT.seq for String, Iterable, arrays, Map etc.
        try {
            return clojure.truffle.runtime.ClojureRT.seq(coll);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Not seqable: " + coll);
        }
    }

    // --- Lazy helpers ---

    private Object lazyMap(Object fn, Object coll) {
        return new LazySeq(() -> {
            clojure.lang.ISeq s;
            if (coll == null || coll instanceof ClojureNil) return null;
            if (coll instanceof clojure.lang.Seqable sq) s = sq.seq();
            else {
                s = seqOf(coll); // handles arrays, strings, Iterables via RT.seq
            }
            if (s == null) return null;
            Object first = callFunction(fn, new Object[]{s.first()});
            clojure.lang.ISeq rest = s.next();
            LazySeq lazyRest = (LazySeq) lazyMap(fn, rest == null ? (Object) ClojureNil.INSTANCE : rest);
            return (Object) new clojure.lang.Cons(first, lazyRest);
        });
    }

    private Object lazyMapMulti(Object fn, clojure.lang.ISeq[] seqs) {
        return new LazySeq(() -> {
            Object[] firsts = new Object[seqs.length];
            clojure.lang.ISeq[] nexts = new clojure.lang.ISeq[seqs.length];
            for (int i = 0; i < seqs.length; i++) {
                if (seqs[i] == null) return null;
                firsts[i] = seqs[i].first();
                nexts[i] = seqs[i].next();
            }
            Object result = callFunction(fn, firsts);
            // Check if any seq is exhausted
            boolean hasMore = true;
            for (clojure.lang.ISeq n : nexts) {
                if (n == null) { hasMore = false; break; }
            }
            if (!hasMore) return (Object) new clojure.lang.Cons(result, null);
            return (Object) new clojure.lang.Cons(result, (LazySeq) lazyMapMulti(fn, nexts));
        });
    }

    private Object lazyFilter(Object fn, Object coll) {
        return new LazySeq(() -> {
            clojure.lang.ISeq s;
            if (coll instanceof ClojureNil) return null;
            if (coll instanceof clojure.lang.Seqable sq) s = sq.seq();
            else return null;
            while (s != null) {
                Object item = s.first();
                if (isTruthy(callFunction(fn, new Object[]{item}))) {
                    clojure.lang.ISeq rest = s.next();
                    LazySeq lazyRest = (LazySeq) lazyFilter(fn,
                            rest == null ? (Object) ClojureNil.INSTANCE : rest);
                    return (Object) new clojure.lang.Cons(item, lazyRest);
                }
                s = s.next();
            }
            return null;
        });
    }

    private Object lazyRange(long start, long end, long step) {
        return new LazySeq(() -> {
            if (step > 0 && start >= end) return null;
            if (step < 0 && start <= end) return null;
            if (step == 0) return null;
            LazySeq rest = (LazySeq) lazyRange(start + step, end, step);
            return (Object) new clojure.lang.Cons(start, rest);
        });
    }

    private Object lazyIterate(Object fn, Object val) {
        return new LazySeq(() -> {
            LazySeq rest = (LazySeq) lazyIterate(fn, callFunction(fn, new Object[]{val}));
            return (Object) new clojure.lang.Cons(val, rest);
        });
    }

    private Object lazyRepeat(long n, Object val) {
        return new LazySeq(() -> {
            if (n <= 0) return null;
            LazySeq rest = (LazySeq) lazyRepeat(n - 1, val);
            return (Object) new clojure.lang.Cons(val, rest);
        });
    }

    private Object lazyRepeatInfinite(Object val) {
        return new LazySeq(() -> new clojure.lang.Cons(val, (clojure.lang.ISeq) lazyRepeatInfinite(val)));
    }

    private Object lazyTakeNth(long n, clojure.lang.ISeq s) {
        return new LazySeq(() -> {
            if (s == null) return null;
            // drop n elements for the next step
            clojure.lang.ISeq rest = s;
            for (long i = 0; i < n; i++) {
                rest = rest != null ? rest.next() : null;
            }
            return (Object) new clojure.lang.Cons(s.first(), (clojure.lang.ISeq) lazyTakeNth(n, rest));
        });
    }

    private Object lazyTakeWhile(Object pred, Object coll) {
        return new LazySeq(() -> {
            clojure.lang.ISeq s;
            if (coll instanceof ClojureNil) return null;
            if (coll instanceof clojure.lang.ISeq is) s = is;
            else if (coll instanceof clojure.lang.Seqable sq) s = sq.seq();
            else return null;
            if (s == null) return null;
            Object item = s.first();
            if (!isTruthy(callFunction(pred, new Object[]{item}))) return null;
            clojure.lang.ISeq rest = s.next();
            LazySeq lazyRest = (LazySeq) lazyTakeWhile(pred,
                    rest == null ? (Object) ClojureNil.INSTANCE : rest);
            return (Object) new clojure.lang.Cons(item, lazyRest);
        });
    }

    // --- Nested collection helpers ---

    private Object assocIn(Object m, clojure.lang.IPersistentVector path, Object val) {
        if (path.count() == 1) {
            if (m instanceof clojure.lang.Associative a) return a.assoc(path.nth(0), val);
            return clojure.lang.PersistentHashMap.EMPTY.assoc(path.nth(0), val);
        }
        Object key = path.nth(0);
        clojure.lang.IPersistentVector restPath = clojure.lang.PersistentVector.EMPTY;
        for (int i = 1; i < path.count(); i++) restPath = restPath.cons(path.nth(i));
        Object child = m instanceof clojure.lang.ILookup l ? l.valAt(key) : null;
        if (child == null) child = clojure.lang.PersistentHashMap.EMPTY;
        if (m instanceof clojure.lang.Associative a) return a.assoc(key, assocIn(child, restPath, val));
        return clojure.lang.PersistentHashMap.EMPTY.assoc(key, assocIn(child, restPath, val));
    }

    private Object updateIn(Object m, clojure.lang.IPersistentVector path, Object fn, Object[] extraArgs) {
        if (path.count() == 1) {
            Object key = path.nth(0);
            Object oldVal = m instanceof clojure.lang.ILookup l ? l.valAt(key) : null;
            if (oldVal == null) oldVal = ClojureNil.INSTANCE;
            Object[] fnArgs = new Object[1 + extraArgs.length];
            fnArgs[0] = oldVal;
            System.arraycopy(extraArgs, 0, fnArgs, 1, extraArgs.length);
            Object newVal = callFunction(fn, fnArgs);
            if (m instanceof clojure.lang.Associative a) return a.assoc(key, newVal);
            return clojure.lang.PersistentHashMap.EMPTY.assoc(key, newVal);
        }
        Object key = path.nth(0);
        clojure.lang.IPersistentVector restPath = clojure.lang.PersistentVector.EMPTY;
        for (int i = 1; i < path.count(); i++) restPath = restPath.cons(path.nth(i));
        Object child = m instanceof clojure.lang.ILookup l ? l.valAt(key) : null;
        if (child == null) child = clojure.lang.PersistentHashMap.EMPTY;
        if (m instanceof clojure.lang.Associative a)
            return a.assoc(key, updateIn(child, restPath, fn, extraArgs));
        return clojure.lang.PersistentHashMap.EMPTY.assoc(key, updateIn(child, restPath, fn, extraArgs));
    }

    // --- Collection helpers (private) ---

    private void flattenInto(Object coll, java.util.List<Object> result) {
        if (coll instanceof ClojureNil) return;
        if (coll instanceof clojure.lang.Sequential || coll instanceof clojure.lang.ISeq) {
            for (clojure.lang.ISeq s = seqOf(coll); s != null; s = s.next())
                flattenInto(s.first(), result);
        } else {
            result.add(coll);
        }
    }

    // --- Arithmetic helpers ---

    private static Object addNumbers(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la + lb;
        return toDouble(a) + toDouble(b);
    }

    private static Object subtractNumbers(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la - lb;
        return toDouble(a) - toDouble(b);
    }

    private static Object multiplyNumbers(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la * lb;
        return toDouble(a) * toDouble(b);
    }

    private static Object divideNumbers(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) {
            if (lb == 0) throw new ArithmeticException("Divide by zero");
            if (la % lb == 0) return la / lb;
            return (double) la / lb;
        }
        double db = toDouble(b);
        // Float division by zero returns NaN or Infinity, not an exception (Clojure semantics)
        return toDouble(a) / db;
    }

    private static Object modNumbers(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return la % lb;
        return toDouble(a) % toDouble(b);
    }

    private static Object negateNumber(Object a) {
        if (a instanceof Long l) return -l;
        return -toDouble(a);
    }

    @TruffleBoundary
    private static double toDouble(Object o) {
        if (o instanceof Long l) return l.doubleValue();
        if (o instanceof Double d) return d;
        if (o instanceof Number n) return n.doubleValue();
        throw new RuntimeException("Not a number: " + o);
    }

    // --- Comparison helpers ---

    @SuppressWarnings("unchecked")
    @TruffleBoundary
    private static int compareNumbers(Object a, Object b) {
        if (a instanceof Long la && b instanceof Long lb) return Long.compare(la, lb);
        return Double.compare(toDouble(a), toDouble(b));
    }

    private static boolean compareChain(Object[] args, int expected) {
        if (args.length < 2) return true;
        for (int i = 0; i < args.length - 1; i++) {
            int cmp = compareNumbers(args[i], args[i + 1]);
            if (Integer.signum(cmp) != expected) return false;
        }
        return true;
    }

    private static boolean compareChainLE(Object[] args, boolean ge) {
        if (args.length < 2) return true;
        for (int i = 0; i < args.length - 1; i++) {
            int cmp = compareNumbers(args[i], args[i + 1]);
            if (ge ? cmp < 0 : cmp > 0) return false;
        }
        return true;
    }

    // --- Equality ---

    private static boolean clojureEquals(Object a, Object b) {
        if (a instanceof ClojureNil && b instanceof ClojureNil) return true;
        if (a instanceof ClojureNil || b instanceof ClojureNil) return false;
        if (a instanceof Number && b instanceof Number) {
            return compareNumbers(a, b) == 0;
        }
        return a.equals(b);
    }

    // --- Truthiness ---

    public static boolean isTruthy(Object val) {
        if (val instanceof ClojureNil) return false;
        if (val instanceof Boolean b) return b;
        return true;
    }

    // --- Print ---

    public static String printString(Object val, boolean readably) {
        if (val instanceof ClojureNil) return "nil";
        if (val instanceof String s) {
            if (readably) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\t", "\\t") + "\"";
            return s;
        }
        if (val instanceof Boolean b) return b.toString();
        if (val instanceof clojure.lang.Keyword kw) return kw.toString();
        if (val instanceof clojure.lang.Symbol sym) return sym.toString();
        return val.toString();
    }

    // --- Collection helpers ---

    private static Object clojureFirst(Object coll) {
        if (coll == null || coll instanceof ClojureNil) return ClojureNil.INSTANCE;
        if (coll instanceof clojure.lang.ISeq seq) {
            Object f = seq.first();
            return f == null ? ClojureNil.INSTANCE : f;
        }
        if (coll instanceof clojure.lang.Seqable s) {
            clojure.lang.ISeq seq = s.seq();
            if (seq == null) return ClojureNil.INSTANCE;
            Object f = seq.first();
            return f == null ? ClojureNil.INSTANCE : f;
        }
        if (coll instanceof CharSequence cs) {
            return cs.length() == 0 ? ClojureNil.INSTANCE : Character.valueOf(cs.charAt(0));
        }
        if (coll.getClass().isArray()) {
            clojure.lang.ISeq seq = clojure.truffle.runtime.ClojureRT.seq(coll);
            return seq == null ? ClojureNil.INSTANCE : seq.first();
        }
        if (coll instanceof java.lang.Iterable<?> it) {
            java.util.Iterator<?> iter = it.iterator();
            return iter.hasNext() ? iter.next() : ClojureNil.INSTANCE;
        }
        throw new RuntimeException("first: not a sequence: " + coll);
    }

    private static Object clojureRest(Object coll) {
        if (coll == null || coll instanceof ClojureNil) return clojure.lang.PersistentList.EMPTY;
        if (coll instanceof clojure.lang.ISeq seq) {
            clojure.lang.ISeq r = seq.next();
            return r == null ? clojure.lang.PersistentList.EMPTY : r;
        }
        if (coll instanceof clojure.lang.Seqable s) {
            clojure.lang.ISeq seq = s.seq();
            if (seq == null) return clojure.lang.PersistentList.EMPTY;
            clojure.lang.ISeq r = seq.next();
            return r == null ? clojure.lang.PersistentList.EMPTY : r;
        }
        if (coll instanceof String str) {
            if (str.length() <= 1) return clojure.lang.PersistentList.EMPTY;
            clojure.lang.ISeq seq = clojure.lang.StringSeq.create(str);
            if (seq == null) return clojure.lang.PersistentList.EMPTY;
            clojure.lang.ISeq r = seq.next();
            return r == null ? clojure.lang.PersistentList.EMPTY : r;
        }
        if (coll.getClass().isArray()) {
            clojure.lang.ISeq seq = clojure.truffle.runtime.ClojureRT.seq(coll);
            if (seq == null) return clojure.lang.PersistentList.EMPTY;
            clojure.lang.ISeq r = seq.next();
            return r == null ? clojure.lang.PersistentList.EMPTY : r;
        }
        // Fallback: try RT.seq for Iterable and other types
        try {
            clojure.lang.ISeq seq = clojure.truffle.runtime.ClojureRT.seq(coll);
            if (seq == null) return clojure.lang.PersistentList.EMPTY;
            clojure.lang.ISeq r = seq.next();
            return r == null ? clojure.lang.PersistentList.EMPTY : r;
        } catch (Exception e) {
            throw new RuntimeException("rest: not a sequence: " + coll);
        }
    }

    private static Object clojureCons(Object elem, Object coll) {
        if (coll == null || coll instanceof ClojureNil) {
            return clojure.lang.PersistentList.create(java.util.List.of(elem));
        }
        if (coll instanceof clojure.lang.ISeq seq) {
            // Don't call .seq() to avoid realizing lazy sequences
            return new clojure.lang.Cons(elem, seq);
        }
        if (coll instanceof clojure.lang.Seqable s) {
            return new clojure.lang.Cons(elem, s.seq());
        }
        throw new RuntimeException("cons: not a sequence: " + coll);
    }

    private static long clojureCount(Object coll) {
        if (coll instanceof ClojureNil) return 0L;
        if (coll instanceof clojure.lang.Counted c) return (long) c.count();
        if (coll instanceof clojure.lang.Seqable s) {
            long count = 0;
            for (clojure.lang.ISeq seq = s.seq(); seq != null; seq = seq.next()) count++;
            return count;
        }
        if (coll instanceof String str) return (long) str.length();
        throw new RuntimeException("count: not countable: " + coll);
    }

    private static Object clojureNth(Object coll, Object index, Object notFound) {
        int idx = ((Number) index).intValue();
        if (coll instanceof clojure.lang.Indexed indexed) {
            if (idx < 0 || idx >= indexed.count()) {
                if (notFound != null) return notFound;
                throw new IndexOutOfBoundsException("Index " + idx);
            }
            return indexed.nth(idx);
        }
        throw new RuntimeException("nth: not indexed: " + coll);
    }

    private static Object clojureConj(Object[] args) {
        Object coll = args[0];
        // (conj nil x) => (list x) — Clojure treats nil as empty list
        if (coll == null || coll instanceof ClojureNil) {
            coll = clojure.lang.PersistentList.EMPTY;
        }
        for (int i = 1; i < args.length; i++) {
            Object val = args[i];
            // (conj coll nil) is a no-op in Clojure
            if (val == null || val instanceof ClojureNil) continue;
            if (coll instanceof clojure.lang.IPersistentCollection pc) {
                coll = pc.cons(val);
            } else {
                throw new RuntimeException("conj: not a collection: " + coll);
            }
        }
        return coll;
    }

    /**
     * Override core.async channel operations with simple Java implementations
     * using LinkedBlockingQueue. This avoids protocol dispatch issues with proxy objects.
     */
    private void registerAsyncOverrides() {
        ClojureNamespace ns = getNamespace("clojure.core.async");
        if (ns == null) return;

        // chan: create a channel backed by LinkedBlockingQueue
        NamedBuiltin chanFunc = new NamedBuiltin("chan", args -> {
            int bufSize = (args.length > 0 && args[0] instanceof Number n) ? n.intValue() : 1024;
            java.util.concurrent.LinkedBlockingQueue<Object> q = new java.util.concurrent.LinkedBlockingQueue<>(Math.max(bufSize, 1));
            return new AsyncChannel(q);
        });
        ns.intern("chan", chanFunc);

        // >!! (blocking put)
        NamedBuiltin putBlockingFunc = new NamedBuiltin(">!!", args -> {
            if (args.length < 2) throw new RuntimeException(">!!: requires channel and value");
            if (args[0] instanceof AsyncChannel ch) {
                if (ch.closed) return false;
                try {
                    ch.queue.put(args[1]);
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            throw new RuntimeException(">!!: first arg must be a channel, got " + (args[0] != null ? args[0].getClass().getName() : "nil"));
        });
        ns.intern(">!!", putBlockingFunc);

        // <!! (blocking take)
        NamedBuiltin takeBlockingFunc = new NamedBuiltin("<!!", args -> {
            if (args.length < 1) throw new RuntimeException("<!!: requires channel");
            if (args[0] instanceof AsyncChannel ch) {
                if (ch.closed && ch.queue.isEmpty()) return ClojureNil.INSTANCE;
                try {
                    Object val = ch.queue.poll(60, java.util.concurrent.TimeUnit.SECONDS);
                    return val != null ? val : ClojureNil.INSTANCE;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ClojureNil.INSTANCE;
                }
            }
            throw new RuntimeException("<!!: first arg must be a channel, got " + (args[0] != null ? args[0].getClass().getName() : "nil"));
        });
        ns.intern("<!!", takeBlockingFunc);

        // >! (park put - in our fake go blocks, same as >!!)
        ns.intern(">!", putBlockingFunc);

        // <! (park take - in our fake go blocks, same as <!!)
        ns.intern("<!", takeBlockingFunc);

        // close!
        NamedBuiltin closeFunc = new NamedBuiltin("close!", args -> {
            if (args.length > 0 && args[0] instanceof AsyncChannel ch) {
                ch.closed = true;
            }
            return ClojureNil.INSTANCE;
        });
        ns.intern("close!", closeFunc);

        // put! (async put with callback - simplified to blocking)
        NamedBuiltin putAsyncFunc = new NamedBuiltin("put!", args -> {
            if (args.length < 2) throw new RuntimeException("put!: requires channel and value");
            if (args[0] instanceof AsyncChannel ch) {
                if (ch.closed) return false;
                ch.queue.offer(args[1]);
                if (args.length > 2 && args[2] != null && !(args[2] instanceof clojure.truffle.runtime.ClojureNil)) {
                    try { callFunction(args[2], new Object[]{true}); } catch (Exception ignored) {}
                }
                return true;
            }
            throw new RuntimeException("put!: first arg must be a channel");
        });
        ns.intern("put!", putAsyncFunc);

        // poll! (non-blocking take, returns nil if nothing available)
        NamedBuiltin pollFunc = new NamedBuiltin("poll!", args -> {
            if (args.length < 1) throw new RuntimeException("poll!: requires channel");
            if (args[0] instanceof AsyncChannel ch) {
                Object val = ch.queue.poll();
                return val != null ? val : ClojureNil.INSTANCE;
            }
            return ClojureNil.INSTANCE;
        });
        ns.intern("poll!", pollFunc);

        // offer! (non-blocking put, returns true/false)
        NamedBuiltin offerFunc = new NamedBuiltin("offer!", args -> {
            if (args.length < 2) throw new RuntimeException("offer!: requires channel and value");
            if (args[0] instanceof AsyncChannel ch) {
                if (ch.closed) return false;
                return ch.queue.offer(args[1]);
            }
            return false;
        });
        ns.intern("offer!", offerFunc);

        // timeout (returns a channel that closes after ms)
        NamedBuiltin timeoutFunc = new NamedBuiltin("timeout", args -> {
            if (args.length < 1) throw new RuntimeException("timeout: requires ms");
            long ms = ((Number) args[0]).longValue();
            AsyncChannel ch = new AsyncChannel(new java.util.concurrent.LinkedBlockingQueue<>(1));
            Thread t = new Thread(() -> {
                try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
                ch.queue.offer(ClojureNil.INSTANCE);
                ch.closed = true;
            });
            t.setDaemon(true);
            t.start();
            return ch;
        });
        ns.intern("timeout", timeoutFunc);

        // alts!! (blocking choice on multiple channels)
        NamedBuiltin altsBlockingFunc = new NamedBuiltin("alts!!", args -> {
            if (args.length < 1) throw new RuntimeException("alts!!: requires channels vector");
            Object portsArg = args[0];
            java.util.List<AsyncChannel> channels = new java.util.ArrayList<>();
            for (clojure.lang.ISeq s = clojure.truffle.runtime.ClojureRT.seq(portsArg); s != null; s = s.next()) {
                Object port = s.first();
                if (port instanceof AsyncChannel ac) channels.add(ac);
            }
            // Busy-poll all channels until one has a value
            while (true) {
                for (int i = 0; i < channels.size(); i++) {
                    AsyncChannel ch = channels.get(i);
                    Object val = ch.queue.poll();
                    if (val != null) {
                        return clojure.lang.PersistentVector.create(val, ch);
                    }
                    if (ch.closed && ch.queue.isEmpty()) {
                        return clojure.lang.PersistentVector.create(ClojureNil.INSTANCE, ch);
                    }
                }
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
            }
            return clojure.lang.PersistentVector.create(ClojureNil.INSTANCE, channels.get(0));
        });
        ns.intern("alts!!", altsBlockingFunc);
        ns.intern("alts!", altsBlockingFunc);
    }

    /** Simple channel implementation backed by a blocking queue */
    public static class AsyncChannel {
        public final java.util.concurrent.LinkedBlockingQueue<Object> queue;
        public volatile boolean closed = false;

        public AsyncChannel(java.util.concurrent.LinkedBlockingQueue<Object> queue) {
            this.queue = queue;
        }

        @Override
        public String toString() {
            return "#<Channel" + (closed ? " (closed)" : "") + ">";
        }
    }

    private void registerStringNamespace() {
        ClojureNamespace ns = getOrCreateNamespace("clojure.string");
        // Map str/xxx builtins to clojure.string/xxx
        String[] fns = {"split", "join", "trim", "triml", "trimr", "upper-case", "lower-case",
                "capitalize", "replace", "replace-first", "starts-with?", "ends-with?", "includes?",
                "blank?", "index-of", "last-index-of", "reverse"};
        for (String fn : fns) {
            Object val = globalVars.get("str/" + fn);
            if (val != null) ns.intern(fn, val);
        }
    }

    private void registerSetNamespace() {
        ClojureNamespace ns = getOrCreateNamespace("clojure.set");
        ns.intern("union", (BuiltinFunction) args -> {
            clojure.lang.IPersistentSet result = clojure.lang.PersistentHashSet.EMPTY;
            for (Object arg : args) {
                for (clojure.lang.ISeq seq = seqOf(arg); seq != null; seq = seq.next()) {
                    result = (clojure.lang.IPersistentSet) result.cons(seq.first());
                }
            }
            return result;
        });
        ns.intern("intersection", (BuiltinFunction) args -> {
            if (args.length == 0) return clojure.lang.PersistentHashSet.EMPTY;
            clojure.lang.IPersistentSet result = (clojure.lang.IPersistentSet) args[0];
            for (int i = 1; i < args.length; i++) {
                clojure.lang.IPersistentSet other = (clojure.lang.IPersistentSet) args[i];
                java.util.List<Object> toKeep = new ArrayList<>();
                for (clojure.lang.ISeq seq = result.seq(); seq != null; seq = seq.next()) {
                    if (other.contains(seq.first())) toKeep.add(seq.first());
                }
                result = clojure.lang.PersistentHashSet.create(toKeep);
            }
            return result;
        });
        ns.intern("difference", (BuiltinFunction) args -> {
            if (args.length == 0) return clojure.lang.PersistentHashSet.EMPTY;
            clojure.lang.IPersistentSet result = (clojure.lang.IPersistentSet) args[0];
            for (int i = 1; i < args.length; i++) {
                clojure.lang.IPersistentSet other = (clojure.lang.IPersistentSet) args[i];
                for (clojure.lang.ISeq seq = other.seq(); seq != null; seq = seq.next()) {
                    result = result.disjoin(seq.first());
                }
            }
            return result;
        });
        ns.intern("subset?", (BuiltinFunction) args -> {
            checkArity(args, 2, "subset?");
            clojure.lang.IPersistentSet a = (clojure.lang.IPersistentSet) args[0];
            clojure.lang.IPersistentSet b = (clojure.lang.IPersistentSet) args[1];
            for (clojure.lang.ISeq seq = a.seq(); seq != null; seq = seq.next()) {
                if (!b.contains(seq.first())) return false;
            }
            return true;
        });
        ns.intern("superset?", (BuiltinFunction) args -> {
            checkArity(args, 2, "superset?");
            clojure.lang.IPersistentSet a = (clojure.lang.IPersistentSet) args[0];
            clojure.lang.IPersistentSet b = (clojure.lang.IPersistentSet) args[1];
            for (clojure.lang.ISeq seq = b.seq(); seq != null; seq = seq.next()) {
                if (!a.contains(seq.first())) return false;
            }
            return true;
        });
        ns.intern("map-invert", (BuiltinFunction) args -> {
            checkArity(args, 1, "map-invert");
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                clojure.lang.MapEntry entry = (clojure.lang.MapEntry) seq.first();
                result = result.assoc(entry.val(), entry.key());
            }
            return result;
        });
        ns.intern("rename-keys", (BuiltinFunction) args -> {
            checkArity(args, 2, "rename-keys");
            clojure.lang.IPersistentMap m = (clojure.lang.IPersistentMap) args[0];
            clojure.lang.IPersistentMap kmap = (clojure.lang.IPersistentMap) args[1];
            clojure.lang.IPersistentMap result = m;
            for (clojure.lang.ISeq seq = kmap.seq(); seq != null; seq = seq.next()) {
                clojure.lang.MapEntry entry = (clojure.lang.MapEntry) seq.first();
                Object oldKey = entry.key();
                Object newKey = entry.val();
                if (result.containsKey(oldKey)) {
                    Object val = result.valAt(oldKey);
                    result = result.without(oldKey);
                    result = result.assoc(newKey, val);
                }
            }
            return result;
        });
        ns.intern("join", (BuiltinFunction) args -> {
            // clojure.set/join: natural join of two relations
            if (args.length < 2) throw new RuntimeException("clojure.set/join: expected 2+ args");
            // Simplified: return first arg
            return args[0];
        });
        ns.intern("index", (BuiltinFunction) args -> {
            checkArity(args, 2, "clojure.set/index");
            Object xrel = args[0]; // set of maps
            Object ks = args[1]; // keys to index by
            clojure.lang.IPersistentMap result = clojure.lang.PersistentHashMap.EMPTY;
            for (clojure.lang.ISeq s = seqOf(xrel); s != null; s = s.next()) {
                clojure.lang.IPersistentMap row = (clojure.lang.IPersistentMap) s.first();
                // Build index key: select-keys of row by ks
                clojure.lang.IPersistentMap indexKey = clojure.lang.PersistentHashMap.EMPTY;
                for (clojure.lang.ISeq ks2 = seqOf(ks); ks2 != null; ks2 = ks2.next()) {
                    Object k = ks2.first();
                    indexKey = indexKey.assoc(k, row.valAt(k));
                }
                clojure.lang.IPersistentSet existing = (clojure.lang.IPersistentSet) result.valAt(indexKey);
                if (existing == null) existing = clojure.lang.PersistentHashSet.EMPTY;
                existing = (clojure.lang.IPersistentSet) existing.cons(row);
                result = result.assoc(indexKey, existing);
            }
            return result;
        });
        ns.intern("select", (BuiltinFunction) args -> {
            checkArity(args, 2, "clojure.set/select");
            Object pred = args[0];
            clojure.lang.IPersistentSet s = (clojure.lang.IPersistentSet) args[1];
            clojure.lang.IPersistentSet result = clojure.lang.PersistentHashSet.EMPTY;
            for (clojure.lang.ISeq seq = s.seq(); seq != null; seq = seq.next()) {
                if (isTruthy(callFunction(pred, new Object[]{seq.first()}))) {
                    result = (clojure.lang.IPersistentSet) result.cons(seq.first());
                }
            }
            return result;
        });
    }

    private void registerWalkNamespace() {
        ClojureNamespace ns = getOrCreateNamespace("clojure.walk");
        // Register walk functions as namespace-local builtins (NOT global)
        ns.intern("walk", new NamedBuiltin("walk", args -> {
            checkArity(args, 3, "walk");
            Object inner = args[0];
            Object outer = args[1];
            Object form = args[2];
            Object walked;
            if (form instanceof clojure.lang.IMapEntry me) {
                walked = clojure.lang.MapEntry.create(
                        callFunction(inner, new Object[]{me.key()}),
                        callFunction(inner, new Object[]{me.val()}));
            } else if (form instanceof clojure.lang.IPersistentList) {
                java.util.List<Object> result = new ArrayList<>();
                for (clojure.lang.ISeq seq = seqOf(form); seq != null; seq = seq.next()) {
                    result.add(callFunction(inner, new Object[]{seq.first()}));
                }
                walked = clojure.lang.PersistentList.create(result);
            } else if (form instanceof clojure.lang.IPersistentVector v) {
                java.util.List<Object> result = new ArrayList<>();
                for (int i = 0; i < v.count(); i++) {
                    result.add(callFunction(inner, new Object[]{v.nth(i)}));
                }
                walked = clojure.lang.PersistentVector.create(result);
            } else if (form instanceof clojure.lang.IPersistentMap m) {
                clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
                for (clojure.lang.ISeq seq = m.seq(); seq != null; seq = seq.next()) {
                    Object entry = callFunction(inner, new Object[]{seq.first()});
                    if (entry instanceof clojure.lang.IMapEntry me2) {
                        result = result.assoc(me2.key(), me2.val());
                    } else if (entry instanceof clojure.lang.IPersistentVector ev && ev.count() == 2) {
                        result = result.assoc(ev.nth(0), ev.nth(1));
                    }
                }
                walked = result;
            } else if (form instanceof clojure.lang.ISeq) {
                java.util.List<Object> result = new ArrayList<>();
                for (clojure.lang.ISeq seq = (clojure.lang.ISeq) form; seq != null; seq = seq.next()) {
                    result.add(callFunction(inner, new Object[]{seq.first()}));
                }
                walked = clojure.lang.PersistentList.create(result);
            } else {
                walked = form;
            }
            return callFunction(outer, new Object[]{walked});
        }));
        ns.intern("postwalk", new NamedBuiltin("postwalk", args -> {
            checkArity(args, 2, "postwalk");
            return postwalk(args[0], args[1]);
        }));
        ns.intern("prewalk", new NamedBuiltin("prewalk", args -> {
            checkArity(args, 2, "prewalk");
            return prewalk(args[0], args[1]);
        }));
        ns.intern("postwalk-replace", new NamedBuiltin("postwalk-replace", args -> {
            checkArity(args, 2, "postwalk-replace");
            Object smap = args[0];
            return postwalk((BuiltinFunction) a -> {
                if (a[0] instanceof ClojureNil) return a[0];
                if (smap instanceof clojure.lang.IPersistentMap m) {
                    Object replacement = m.valAt(a[0]);
                    return replacement != null ? replacement : a[0];
                }
                return a[0];
            }, args[1]);
        }));
        ns.intern("prewalk-replace", new NamedBuiltin("prewalk-replace", args -> {
            checkArity(args, 2, "prewalk-replace");
            Object smap = args[0];
            return prewalk((BuiltinFunction) a -> {
                if (a[0] instanceof ClojureNil) return a[0];
                if (smap instanceof clojure.lang.IPersistentMap m) {
                    Object replacement = m.valAt(a[0]);
                    return replacement != null ? replacement : a[0];
                }
                return a[0];
            }, args[1]);
        }));
    }

    private void registerEdnNamespace() {
        ClojureNamespace ns = getOrCreateNamespace("clojure.edn");
        Object readStr = globalVars.get("read-string");
        if (readStr != null) ns.intern("read-string", readStr);
    }

    private void registerPprintNamespace() {
        ClojureNamespace ns = getOrCreateNamespace("clojure.pprint");

        // pprint: pretty-print an object
        ns.intern("pprint", (BuiltinFunction) args -> {
            if (args.length < 1 || args.length > 2) throw new RuntimeException("pprint: expected 1-2 args");
            Object obj = args[0];
            java.io.Writer writer = (args.length == 2 && args[1] instanceof java.io.Writer w) ? w : new java.io.OutputStreamWriter(System.out);
            String s = printString(obj, true);
            try {
                writer.write(s);
                writer.write("\n");
                writer.flush();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            return ClojureNil.INSTANCE;
        });

        // cl-format: simplified - just delegate to format
        ns.intern("cl-format", (BuiltinFunction) args -> {
            // Simplified: cl-format writer fmt args...
            // For basic use, just return formatted string
            if (args.length < 2) throw new RuntimeException("cl-format: expected at least 2 args");
            return ClojureNil.INSTANCE; // stub
        });

        // print-table: print a collection of maps as a table
        ns.intern("print-table", (BuiltinFunction) args -> {
            // Simplified stub
            if (args.length < 1) throw new RuntimeException("print-table: expected at least 1 arg");
            Object rows = args.length == 2 ? args[1] : args[0];
            for (clojure.lang.ISeq seq = seqOf(rows); seq != null; seq = seq.next()) {
                System.out.println(printString(seq.first(), true));
            }
            return ClojureNil.INSTANCE;
        });

        // write: stub
        ns.intern("write", (BuiltinFunction) args -> {
            if (args.length >= 1) System.out.print(printString(args[0], true));
            return ClojureNil.INSTANCE;
        });

        // *print-right-margin*
        ns.intern("*print-right-margin*", 72L);
    }

    private void registerJavaIoNamespace() {
        ClojureNamespace ns = getOrCreateNamespace("clojure.java.io");

        ns.intern("reader", (BuiltinFunction) args -> {
            if (args.length < 1) throw new RuntimeException("reader: expected at least 1 arg");
            Object x = args[0];
            try {
                if (x instanceof String s) {
                    return new java.io.BufferedReader(new java.io.FileReader(s));
                }
                if (x instanceof java.io.File f) {
                    return new java.io.BufferedReader(new java.io.FileReader(f));
                }
                if (x instanceof java.io.InputStream is) {
                    return new java.io.BufferedReader(new java.io.InputStreamReader(is));
                }
                if (x instanceof java.io.Reader r) {
                    return (r instanceof java.io.BufferedReader) ? r : new java.io.BufferedReader(r);
                }
                throw new RuntimeException("reader: cannot coerce to reader: " + x.getClass().getName());
            } catch (java.io.FileNotFoundException e) {
                throw new RuntimeException("reader: " + e.getMessage());
            }
        });

        ns.intern("writer", (BuiltinFunction) args -> {
            if (args.length < 1) throw new RuntimeException("writer: expected at least 1 arg");
            Object x = args[0];
            try {
                if (x instanceof String s) {
                    return new java.io.BufferedWriter(new java.io.FileWriter(s));
                }
                if (x instanceof java.io.File f) {
                    return new java.io.BufferedWriter(new java.io.FileWriter(f));
                }
                if (x instanceof java.io.OutputStream os) {
                    return new java.io.BufferedWriter(new java.io.OutputStreamWriter(os));
                }
                if (x instanceof java.io.Writer w) {
                    return (w instanceof java.io.BufferedWriter) ? w : new java.io.BufferedWriter(w);
                }
                throw new RuntimeException("writer: cannot coerce to writer: " + x.getClass().getName());
            } catch (java.io.IOException e) {
                throw new RuntimeException("writer: " + e.getMessage());
            }
        });

        ns.intern("file", (BuiltinFunction) args -> {
            if (args.length == 0) throw new RuntimeException("file: expected at least 1 arg");
            java.io.File f = new java.io.File(args[0].toString());
            for (int i = 1; i < args.length; i++) {
                f = new java.io.File(f, args[i].toString());
            }
            return f;
        });

        ns.intern("input-stream", (BuiltinFunction) args -> {
            checkArity(args, 1, "input-stream");
            Object x = args[0];
            try {
                if (x instanceof String s) return new java.io.FileInputStream(s);
                if (x instanceof java.io.File f) return new java.io.FileInputStream(f);
                if (x instanceof java.io.InputStream is) return is;
                throw new RuntimeException("input-stream: cannot coerce: " + x.getClass().getName());
            } catch (java.io.FileNotFoundException e) {
                throw new RuntimeException("input-stream: " + e.getMessage());
            }
        });

        ns.intern("output-stream", (BuiltinFunction) args -> {
            checkArity(args, 1, "output-stream");
            Object x = args[0];
            try {
                if (x instanceof String s) return new java.io.FileOutputStream(s);
                if (x instanceof java.io.File f) return new java.io.FileOutputStream(f);
                if (x instanceof java.io.OutputStream os) return os;
                throw new RuntimeException("output-stream: cannot coerce: " + x.getClass().getName());
            } catch (java.io.FileNotFoundException e) {
                throw new RuntimeException("output-stream: " + e.getMessage());
            }
        });

        ns.intern("delete-file", (BuiltinFunction) args -> {
            if (args.length < 1) throw new RuntimeException("delete-file: expected 1 arg");
            java.io.File f = (args[0] instanceof java.io.File) ?
                    (java.io.File) args[0] : new java.io.File(args[0].toString());
            boolean deleted = f.delete();
            if (!deleted && (args.length < 2 || isTruthy(args[1])))
                throw new RuntimeException("delete-file: could not delete " + f);
            return deleted;
        });

        ns.intern("make-parents", (BuiltinFunction) args -> {
            checkArity(args, 1, "make-parents");
            java.io.File f = (args[0] instanceof java.io.File) ?
                    (java.io.File) args[0] : new java.io.File(args[0].toString());
            java.io.File parent = f.getParentFile();
            return parent != null && parent.mkdirs();
        });

        ns.intern("copy", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("copy: expected at least 2 args");
            Object input = args[0];
            Object output = args[1];
            try {
                java.io.InputStream in = null;
                boolean closeIn = false;
                if (input instanceof java.io.InputStream is) { in = is; }
                else if (input instanceof java.io.File f) { in = new java.io.FileInputStream(f); closeIn = true; }
                else if (input instanceof String s) { in = new java.io.FileInputStream(s); closeIn = true; }
                else if (input instanceof byte[] b) { in = new java.io.ByteArrayInputStream(b); }
                if (in != null) {
                    try {
                        if (output instanceof java.io.OutputStream os) {
                            in.transferTo(os);
                        } else if (output instanceof java.io.File f) {
                            try (var os = new java.io.FileOutputStream(f)) { in.transferTo(os); }
                        } else if (output instanceof String s) {
                            try (var os = new java.io.FileOutputStream(s)) { in.transferTo(os); }
                        } else {
                            throw new RuntimeException("copy: unsupported output type: " + output.getClass().getName());
                        }
                    } finally { if (closeIn) in.close(); }
                    return clojure.truffle.runtime.ClojureNil.INSTANCE;
                }
                throw new RuntimeException("copy: unsupported input type: " + input.getClass().getName());
            } catch (java.io.IOException e) {
                throw new RuntimeException("copy: " + e.getMessage(), e);
            }
        });

        ns.intern("resource", (BuiltinFunction) args -> {
            if (args.length < 1) throw new RuntimeException("resource: expected at least 1 arg");
            String name = args[0].toString();
            ClassLoader loader = args.length > 1 && args[1] instanceof ClassLoader cl
                    ? cl : Thread.currentThread().getContextClassLoader();
            java.net.URL url = loader.getResource(name);
            return url != null ? url : clojure.truffle.runtime.ClojureNil.INSTANCE;
        });

        ns.intern("as-file", (BuiltinFunction) args -> {
            checkArity(args, 1, "as-file");
            Object x = args[0];
            if (x == null || x instanceof clojure.truffle.runtime.ClojureNil) return clojure.truffle.runtime.ClojureNil.INSTANCE;
            if (x instanceof java.io.File f) return f;
            return new java.io.File(x.toString());
        });

        ns.intern("as-url", (BuiltinFunction) args -> {
            checkArity(args, 1, "as-url");
            Object x = args[0];
            if (x == null || x instanceof clojure.truffle.runtime.ClojureNil) return clojure.truffle.runtime.ClojureNil.INSTANCE;
            if (x instanceof java.net.URL u) return u;
            try {
                return new java.net.URI(x.toString()).toURL();
            } catch (Exception e) {
                throw new RuntimeException("as-url: " + e.getMessage(), e);
            }
        });

        ns.intern("as-relative-path", (BuiltinFunction) args -> {
            checkArity(args, 1, "as-relative-path");
            java.io.File f = (args[0] instanceof java.io.File) ?
                    (java.io.File) args[0] : new java.io.File(args[0].toString());
            if (f.isAbsolute()) throw new RuntimeException(f + " is not a relative path");
            return f.getPath();
        });

        // IOFactory protocol and default-streams-impl
        var ioProto = new clojure.truffle.runtime.ClojureProtocol("IOFactory",
                java.util.List.of("make-reader", "make-writer", "make-input-stream", "make-output-stream"));
        ns.intern("IOFactory", ioProto);

        // make-reader, make-writer, make-input-stream, make-output-stream protocol dispatch functions
        ns.intern("make-reader", new NamedBuiltin("clojure.java.io/make-reader", fnArgs -> {
            if (fnArgs.length < 2) throw new RuntimeException("make-reader: expected 2 args");
            Object fn = ioProto.findMethod("make-reader", fnArgs[0]);
            if (fn == null) throw new RuntimeException("make-reader: no implementation for " +
                    (fnArgs[0] == null ? "nil" : fnArgs[0].getClass().getName()));
            return callFunction(fn, fnArgs);
        }));
        ns.intern("make-writer", new NamedBuiltin("clojure.java.io/make-writer", fnArgs -> {
            if (fnArgs.length < 2) throw new RuntimeException("make-writer: expected 2 args");
            Object fn = ioProto.findMethod("make-writer", fnArgs[0]);
            if (fn == null) throw new RuntimeException("make-writer: no implementation for " +
                    (fnArgs[0] == null ? "nil" : fnArgs[0].getClass().getName()));
            return callFunction(fn, fnArgs);
        }));
        ns.intern("make-input-stream", new NamedBuiltin("clojure.java.io/make-input-stream", fnArgs -> {
            if (fnArgs.length < 2) throw new RuntimeException("make-input-stream: expected 2 args");
            Object fn = ioProto.findMethod("make-input-stream", fnArgs[0]);
            if (fn == null) throw new RuntimeException("make-input-stream: no implementation for " +
                    (fnArgs[0] == null ? "nil" : fnArgs[0].getClass().getName()));
            return callFunction(fn, fnArgs);
        }));
        ns.intern("make-output-stream", new NamedBuiltin("clojure.java.io/make-output-stream", fnArgs -> {
            if (fnArgs.length < 2) throw new RuntimeException("make-output-stream: expected 2 args");
            Object fn = ioProto.findMethod("make-output-stream", fnArgs[0]);
            if (fn == null) throw new RuntimeException("make-output-stream: no implementation for " +
                    (fnArgs[0] == null ? "nil" : fnArgs[0].getClass().getName()));
            return callFunction(fn, fnArgs);
        }));

        // default-streams-impl map
        BuiltinFunction makeReaderDefault = fnArgs -> {
            Object is = ioProto.findMethod("make-input-stream", fnArgs[0]);
            if (is == null) throw new RuntimeException("Cannot open as InputStream: " + fnArgs[0]);
            Object stream = callFunction(is, fnArgs);
            Object readerFn = ioProto.findMethod("make-reader", stream);
            if (readerFn != null) return callFunction(readerFn, new Object[]{stream, fnArgs.length > 1 ? fnArgs[1] : null});
            return new java.io.BufferedReader(new java.io.InputStreamReader(
                    (java.io.InputStream) stream, java.nio.charset.StandardCharsets.UTF_8));
        };
        BuiltinFunction makeWriterDefault = fnArgs -> {
            Object os = ioProto.findMethod("make-output-stream", fnArgs[0]);
            if (os == null) throw new RuntimeException("Cannot open as OutputStream: " + fnArgs[0]);
            Object stream = callFunction(os, fnArgs);
            Object writerFn = ioProto.findMethod("make-writer", stream);
            if (writerFn != null) return callFunction(writerFn, new Object[]{stream, fnArgs.length > 1 ? fnArgs[1] : null});
            return new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    (java.io.OutputStream) stream, java.nio.charset.StandardCharsets.UTF_8));
        };
        BuiltinFunction makeInputStreamDefault = fnArgs -> {
            throw new RuntimeException("Cannot open <" + fnArgs[0] + "> as an InputStream.");
        };
        BuiltinFunction makeOutputStreamDefault = fnArgs -> {
            throw new RuntimeException("Cannot open <" + fnArgs[0] + "> as an OutputStream.");
        };

        clojure.lang.IPersistentMap defaultImpl = clojure.lang.PersistentHashMap.create(
                clojure.lang.Keyword.intern("make-reader"), makeReaderDefault,
                clojure.lang.Keyword.intern("make-writer"), makeWriterDefault,
                clojure.lang.Keyword.intern("make-input-stream"), makeInputStreamDefault,
                clojure.lang.Keyword.intern("make-output-stream"), makeOutputStreamDefault
        );
        ns.intern("default-streams-impl", defaultImpl);

        // Register IOFactory implementations for common types
        // InputStream
        ioProto.extend(java.io.InputStream.class, java.util.Map.of(
                "make-input-stream", (BuiltinFunction) a -> new java.io.BufferedInputStream((java.io.InputStream) a[0]),
                "make-reader", (BuiltinFunction) a -> {
                    String enc = "UTF-8";
                    if (a.length > 1 && a[1] instanceof clojure.lang.IPersistentMap m) {
                        Object e = m.valAt(clojure.lang.Keyword.intern("encoding"));
                        if (e instanceof String s) enc = s;
                    }
                    return new java.io.BufferedReader(new java.io.InputStreamReader((java.io.InputStream) a[0], java.nio.charset.Charset.forName(enc)));
                }
        ));
        // BufferedInputStream
        ioProto.extend(java.io.BufferedInputStream.class, java.util.Map.of(
                "make-input-stream", (BuiltinFunction) a -> a[0],
                "make-reader", (BuiltinFunction) a -> {
                    String enc = "UTF-8";
                    if (a.length > 1 && a[1] instanceof clojure.lang.IPersistentMap m) {
                        Object e = m.valAt(clojure.lang.Keyword.intern("encoding"));
                        if (e instanceof String s) enc = s;
                    }
                    return new java.io.BufferedReader(new java.io.InputStreamReader((java.io.InputStream) a[0], java.nio.charset.Charset.forName(enc)));
                }
        ));
        // Reader
        ioProto.extend(java.io.Reader.class, java.util.Map.of(
                "make-reader", (BuiltinFunction) a -> new java.io.BufferedReader((java.io.Reader) a[0])
        ));
        // BufferedReader
        ioProto.extend(java.io.BufferedReader.class, java.util.Map.of(
                "make-reader", (BuiltinFunction) a -> a[0]
        ));
        // Writer
        ioProto.extend(java.io.Writer.class, java.util.Map.of(
                "make-writer", (BuiltinFunction) a -> new java.io.BufferedWriter((java.io.Writer) a[0])
        ));
        // BufferedWriter
        ioProto.extend(java.io.BufferedWriter.class, java.util.Map.of(
                "make-writer", (BuiltinFunction) a -> a[0]
        ));
        // OutputStream
        ioProto.extend(java.io.OutputStream.class, java.util.Map.of(
                "make-output-stream", (BuiltinFunction) a -> new java.io.BufferedOutputStream((java.io.OutputStream) a[0]),
                "make-writer", (BuiltinFunction) a -> {
                    String enc = "UTF-8";
                    if (a.length > 1 && a[1] instanceof clojure.lang.IPersistentMap m) {
                        Object e = m.valAt(clojure.lang.Keyword.intern("encoding"));
                        if (e instanceof String s) enc = s;
                    }
                    return new java.io.BufferedWriter(new java.io.OutputStreamWriter((java.io.OutputStream) a[0], java.nio.charset.Charset.forName(enc)));
                }
        ));
        // BufferedOutputStream
        ioProto.extend(java.io.BufferedOutputStream.class, java.util.Map.of(
                "make-output-stream", (BuiltinFunction) a -> a[0],
                "make-writer", (BuiltinFunction) a -> {
                    String enc = "UTF-8";
                    if (a.length > 1 && a[1] instanceof clojure.lang.IPersistentMap m) {
                        Object e = m.valAt(clojure.lang.Keyword.intern("encoding"));
                        if (e instanceof String s) enc = s;
                    }
                    return new java.io.BufferedWriter(new java.io.OutputStreamWriter((java.io.OutputStream) a[0], java.nio.charset.Charset.forName(enc)));
                }
        ));
        // File
        ioProto.extend(java.io.File.class, java.util.Map.of(
                "make-input-stream", (BuiltinFunction) a -> {
                    try { return new java.io.BufferedInputStream(new java.io.FileInputStream((java.io.File) a[0])); }
                    catch (java.io.FileNotFoundException e) { throw new RuntimeException(e.getMessage(), e); }
                },
                "make-output-stream", (BuiltinFunction) a -> {
                    try {
                        boolean append = false;
                        if (a.length > 1 && a[1] instanceof clojure.lang.IPersistentMap m) {
                            Object ap = m.valAt(clojure.lang.Keyword.intern("append"));
                            append = isTruthy(ap);
                        }
                        return new java.io.BufferedOutputStream(new java.io.FileOutputStream((java.io.File) a[0], append));
                    } catch (java.io.FileNotFoundException e) { throw new RuntimeException(e.getMessage(), e); }
                }
        ));
        // String
        ioProto.extend(String.class, java.util.Map.of(
                "make-input-stream", (BuiltinFunction) a -> {
                    try { return new java.io.BufferedInputStream(new java.io.FileInputStream(a[0].toString())); }
                    catch (java.io.FileNotFoundException e) { throw new RuntimeException(e.getMessage(), e); }
                },
                "make-output-stream", (BuiltinFunction) a -> {
                    try {
                        boolean append = false;
                        if (a.length > 1 && a[1] instanceof clojure.lang.IPersistentMap m) {
                            Object ap = m.valAt(clojure.lang.Keyword.intern("append"));
                            append = isTruthy(ap);
                        }
                        return new java.io.BufferedOutputStream(new java.io.FileOutputStream(a[0].toString(), append));
                    } catch (java.io.FileNotFoundException e) { throw new RuntimeException(e.getMessage(), e); }
                }
        ));
        // byte[]
        ioProto.extend(byte[].class, java.util.Map.of(
                "make-input-stream", (BuiltinFunction) a -> new java.io.BufferedInputStream(new java.io.ByteArrayInputStream((byte[]) a[0]))
        ));
    }

    private static java.util.Set<Class<?>> clojure_allInterfaces(Class<?> clazz) {
        java.util.Set<Class<?>> result = new java.util.HashSet<>();
        java.util.Queue<Class<?>> queue = new java.util.LinkedList<>();
        queue.add(clazz);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            for (Class<?> iface : c.getInterfaces()) {
                if (result.add(iface)) queue.add(iface);
            }
            if (c.getSuperclass() != null) queue.add(c.getSuperclass());
        }
        return result;
    }

    // Reduced wrapper for transducers
    public static class Reduced implements clojure.lang.IDeref {
        public final Object value;
        public Reduced(Object value) { this.value = value; }
        @Override
        public Object deref() { return value; }
    }

    // Base class for transducer reducing functions
    public abstract class TransducerRf implements BuiltinFunction {
        protected final Object innerRf;
        public TransducerRf(Object innerRf) { this.innerRf = innerRf; }

        @Override
        public Object execute(Object[] args) {
            if (args.length == 0) return callFunction(innerRf, new Object[0]);
            if (args.length == 1) return callFunction(innerRf, new Object[]{args[0]}); // completion
            return step(args[0], args[1]);
        }

        public abstract Object step(Object acc, Object input);
    }

    private Object postwalk(Object f, Object form) {
        Object walked;
        if (form instanceof clojure.lang.IPersistentVector v) {
            java.util.List<Object> result = new ArrayList<>();
            for (int i = 0; i < v.count(); i++) result.add(postwalk(f, v.nth(i)));
            walked = clojure.lang.PersistentVector.create(result);
        } else if (form instanceof clojure.lang.IPersistentMap m) {
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq seq = m.seq(); seq != null; seq = seq.next()) {
                clojure.lang.MapEntry entry = (clojure.lang.MapEntry) seq.first();
                result = result.assoc(postwalk(f, entry.key()), postwalk(f, entry.val()));
            }
            walked = result;
        } else if (form instanceof clojure.lang.IPersistentList || form instanceof clojure.lang.ISeq) {
            java.util.List<Object> result = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(form); seq != null; seq = seq.next()) {
                result.add(postwalk(f, seq.first()));
            }
            walked = result.isEmpty() ? clojure.lang.PersistentList.EMPTY : clojure.lang.PersistentList.create(result);
        } else if (form instanceof clojure.lang.IPersistentSet s) {
            java.util.List<Object> result = new ArrayList<>();
            for (clojure.lang.ISeq seq = s.seq(); seq != null; seq = seq.next()) {
                result.add(postwalk(f, seq.first()));
            }
            walked = clojure.lang.PersistentHashSet.create(result);
        } else {
            walked = form;
        }
        return callFunction(f, new Object[]{walked});
    }

    private Object prewalk(Object f, Object form) {
        Object prewalked = callFunction(f, new Object[]{form});
        if (prewalked instanceof clojure.lang.IPersistentVector v) {
            java.util.List<Object> result = new ArrayList<>();
            for (int i = 0; i < v.count(); i++) result.add(prewalk(f, v.nth(i)));
            return clojure.lang.PersistentVector.create(result);
        } else if (prewalked instanceof clojure.lang.IPersistentMap m) {
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq seq = m.seq(); seq != null; seq = seq.next()) {
                clojure.lang.MapEntry entry = (clojure.lang.MapEntry) seq.first();
                result = result.assoc(prewalk(f, entry.key()), prewalk(f, entry.val()));
            }
            return result;
        } else if (prewalked instanceof clojure.lang.IPersistentList || prewalked instanceof clojure.lang.ISeq) {
            java.util.List<Object> result = new ArrayList<>();
            for (clojure.lang.ISeq seq = seqOf(prewalked); seq != null; seq = seq.next()) {
                result.add(prewalk(f, seq.first()));
            }
            return result.isEmpty() ? clojure.lang.PersistentList.EMPTY : clojure.lang.PersistentList.create(result);
        } else if (prewalked instanceof clojure.lang.IPersistentSet s) {
            java.util.List<Object> result = new ArrayList<>();
            for (clojure.lang.ISeq seq = s.seq(); seq != null; seq = seq.next()) {
                result.add(prewalk(f, seq.first()));
            }
            return clojure.lang.PersistentHashSet.create(result);
        }
        return prewalked;
    }

    private Object lazyCycle(java.util.List<Object> items, int idx) {
        return new clojure.lang.Cons(items.get(idx),
                new LazySeq(() -> lazyCycle(items, (idx + 1) % items.size())));
    }

    private ClojureNamespace resolveNsArg(Object arg) {
        if (arg instanceof ClojureNamespace ns) return ns;
        String nsName = nsNameFromArg(arg);
        ClojureNamespace ns = getNamespace(nsName);
        if (ns == null) throw new RuntimeException("No namespace: " + nsName + " found");
        return ns;
    }

    private String nsNameFromArg(Object arg) {
        if (arg instanceof clojure.lang.Symbol sym) return sym.getName();
        if (arg instanceof ClojureNamespace ns) return ns.getName();
        return arg.toString();
    }

    private clojure.lang.IPersistentMap nsMapToClojure(java.util.Map<String, Object> map) {
        clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
        for (var entry : map.entrySet()) {
            result = result.assoc(clojure.lang.Symbol.intern(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static void checkArity(Object[] args, int expected, String name) {
        if (args.length != expected)
            throw new RuntimeException(name + ": expected " + expected + " args, got " + args.length);
    }

    private void flattenHelper(Object coll, java.util.List<Object> result) {
        if (coll == null || coll == ClojureNil.INSTANCE) return;
        if (coll instanceof clojure.lang.Seqable) {
            for (clojure.lang.ISeq s = seqOf(coll); s != null; s = s.next()) {
                Object item = s.first();
                if (item instanceof clojure.lang.Seqable && !(item instanceof String)
                        && !(item instanceof clojure.lang.MapEntry)) {
                    flattenHelper(item, result);
                } else {
                    result.add(item);
                }
            }
        } else {
            result.add(coll);
        }
    }

    private Object updateIn(Object m, clojure.lang.IPersistentVector ks, int i, Object f, Object[] extraArgs) {
        if (m == null || m instanceof ClojureNil) m = clojure.lang.PersistentArrayMap.EMPTY;
        if (i == ks.count() - 1) {
            Object key = ks.nth(i);
            Object oldVal = ClojureNil.INSTANCE;
            if (m instanceof clojure.lang.ILookup lk) oldVal = lk.valAt(key, ClojureNil.INSTANCE);
            Object[] allArgs = new Object[1 + extraArgs.length];
            allArgs[0] = oldVal;
            System.arraycopy(extraArgs, 0, allArgs, 1, extraArgs.length);
            Object newVal = callFunction(f, allArgs);
            return ((clojure.lang.Associative) m).assoc(key, newVal);
        } else {
            Object key = ks.nth(i);
            Object nested = ClojureNil.INSTANCE;
            if (m instanceof clojure.lang.ILookup lk) nested = lk.valAt(key, ClojureNil.INSTANCE);
            if (nested == null || nested == ClojureNil.INSTANCE) nested = clojure.lang.PersistentArrayMap.EMPTY;
            Object updated = updateIn(nested, ks, i + 1, f, extraArgs);
            return ((clojure.lang.Associative) m).assoc(key, updated);
        }
    }

    private Object assocIn(Object m, clojure.lang.IPersistentVector ks, int i, Object v) {
        if (i == ks.count() - 1) {
            return ((clojure.lang.Associative) m).assoc(ks.nth(i), v);
        } else {
            Object key = ks.nth(i);
            Object nested = ClojureNil.INSTANCE;
            if (m instanceof clojure.lang.ILookup lk) nested = lk.valAt(key, ClojureNil.INSTANCE);
            if (nested == null || nested == ClojureNil.INSTANCE) nested = clojure.lang.PersistentArrayMap.EMPTY;
            Object updated = assocIn(nested, ks, i + 1, v);
            return ((clojure.lang.Associative) m).assoc(key, updated);
        }
    }
}
