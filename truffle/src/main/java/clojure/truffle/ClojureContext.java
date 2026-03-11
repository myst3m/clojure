package clojure.truffle;

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
    private final ConcurrentHashMap<String, Object> globalVars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> macros = new ConcurrentHashMap<>();
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

    @FunctionalInterface
    public interface BuiltinFunction {
        Object execute(Object[] args);
    }

    public ClojureContext(ClojureTruffleLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        // Create clojure.core and user namespaces
        namespaces.put("clojure.core", new ClojureNamespace("clojure.core"));
        namespaces.put("user", new ClojureNamespace("user"));
        registerBuiltins();
        // User namespace refers all of clojure.core
        namespaces.get("user").referAll(namespaces.get("clojure.core"));
    }

    public ClojureTruffleLanguage getLanguage() {
        return language;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    public void setVar(String name, Object value) {
        globalVars.put(name, value);
        // Also intern in current namespace
        ClojureNamespace ns = namespaces.get(currentNamespace);
        if (ns != null) ns.intern(name, value);
    }

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
        // Flat lookup (backwards compat + core builtins)
        Object val = globalVars.get(name);
        if (val != null) return val;
        // Check current namespace
        ClojureNamespace ns = namespaces.get(currentNamespace);
        if (ns != null) {
            val = ns.resolve(name);
            if (val != null) return val;
        }
        return null;
    }

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

    public void setMacro(String name, Object fn) {
        macros.put(name, fn);
    }

    public Object getMacro(String name) {
        return macros.get(name);
    }

    public String getCurrentNamespace() { return currentNamespace; }
    public void setCurrentNamespace(String ns) { this.currentNamespace = ns; }

    public ClojureNamespace getOrCreateNamespace(String name) {
        return namespaces.computeIfAbsent(name, ClojureNamespace::new);
    }

    public ClojureNamespace getNamespace(String name) {
        return namespaces.get(name);
    }

    public void declareDynamic(String name) {
        dynamicVars.add(name);
    }

    public boolean isDynamic(String name) {
        return dynamicVars.contains(name);
    }

    public void pushThreadBinding(String name, Object value) {
        threadBindings.get().put(name, value);
    }

    public void popThreadBinding(String name) {
        threadBindings.get().remove(name);
    }

    public Object getThreadBinding(String name) {
        return threadBindings.get().get(name);
    }

    // Override getVar to check thread-local bindings first for dynamic vars
    public Object getVarWithBindings(String name) {
        if (dynamicVars.contains(name)) {
            Object bound = threadBindings.get().get(name);
            if (bound != null) return bound;
        }
        return getVar(name);
    }

    public void loadNamespace(String nsName) {
        if (namespaces.containsKey(nsName)) return; // already loaded
        // Built-in pseudo-namespaces
        if (nsName.equals("clojure.string")) {
            registerStringNamespace();
            return;
        }
        if (nsName.equals("clojure.set")) {
            registerSetNamespace();
            return;
        }
        if (nsName.equals("clojure.walk")) {
            registerWalkNamespace();
            return;
        }
        if (nsName.equals("clojure.edn")) {
            registerEdnNamespace();
            return;
        }
        if (nsName.equals("clojure.java.io")) {
            registerJavaIoNamespace();
            return;
        }
        if (!loadingNamespaces.add(nsName))
            throw new RuntimeException("Circular require detected: " + nsName);
        try {
            String path = nsName.replace('.', '/') + ".clj";
            java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(path);
            if (is == null) {
                // Try file system
                java.io.File file = new java.io.File(path);
                if (!file.exists()) {
                    // Also try relative to current dir
                    file = new java.io.File("src/" + path);
                }
                if (file.exists()) {
                    is = new java.io.FileInputStream(file);
                }
            }
            if (is == null) {
                throw new RuntimeException("Cannot find namespace: " + nsName + " (searched: " + path + ")");
            }
            String source = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            is.close();
            String prevNs = currentNamespace;
            getOrCreateNamespace(nsName);
            currentNamespace = nsName;
            Analyzer analyzer = new Analyzer(language);
            analyzer.setContext(this);
            ExpressionNode[] nodes = analyzer.analyzeProgram(source);
            EvalRootNode root = new EvalRootNode(language, analyzer.getFrameDescriptor(), nodes);
            root.getCallTarget().call();
            currentNamespace = prevNs;
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error loading namespace: " + nsName, e);
        } finally {
            loadingNamespaces.remove(nsName);
        }
    }

    private void registerBuiltins() {
        // Arithmetic
        globalVars.put("+", (BuiltinFunction) args -> {
            if (args.length == 0) return 0L;
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                result = addNumbers(result, args[i]);
            }
            return result;
        });

        globalVars.put("-", (BuiltinFunction) args -> {
            if (args.length == 0) return 0L;
            if (args.length == 1) return negateNumber(args[0]);
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                result = subtractNumbers(result, args[i]);
            }
            return result;
        });

        globalVars.put("*", (BuiltinFunction) args -> {
            if (args.length == 0) return 1L;
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                result = multiplyNumbers(result, args[i]);
            }
            return result;
        });

        globalVars.put("/", (BuiltinFunction) args -> {
            if (args.length == 1) return divideNumbers(1L, args[0]);
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                result = divideNumbers(result, args[i]);
            }
            return result;
        });

        globalVars.put("mod", (BuiltinFunction) args -> {
            checkArity(args, 2, "mod");
            return modNumbers(args[0], args[1]);
        });

        // Comparison
        globalVars.put("=", (BuiltinFunction) args -> {
            if (args.length < 2) return true;
            for (int i = 1; i < args.length; i++) {
                if (!clojureEquals(args[0], args[i])) return false;
            }
            return true;
        });

        globalVars.put("<", (BuiltinFunction) args -> compareChain(args, -1));
        globalVars.put(">", (BuiltinFunction) args -> compareChain(args, 1));
        globalVars.put("<=", (BuiltinFunction) args -> compareChainLE(args, false));
        globalVars.put(">=", (BuiltinFunction) args -> compareChainLE(args, true));

        // Logic
        globalVars.put("not", (BuiltinFunction) args -> {
            checkArity(args, 1, "not");
            return !isTruthy(args[0]);
        });

        // Type predicates
        globalVars.put("nil?", (BuiltinFunction) args -> {
            checkArity(args, 1, "nil?");
            return args[0] instanceof ClojureNil;
        });

        globalVars.put("number?", (BuiltinFunction) args -> {
            checkArity(args, 1, "number?");
            return args[0] instanceof Number;
        });

        globalVars.put("string?", (BuiltinFunction) args -> {
            checkArity(args, 1, "string?");
            return args[0] instanceof String;
        });

        // String
        globalVars.put("str", (BuiltinFunction) args -> {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (!(arg instanceof ClojureNil)) {
                    sb.append(printString(arg, false));
                }
            }
            return sb.toString();
        });

        globalVars.put("pr-str", (BuiltinFunction) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            return sb.toString();
        });

        // IO
        globalVars.put("print", (BuiltinFunction) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], false));
            }
            writeOut(sb.toString());
            return ClojureNil.INSTANCE;
        });

        globalVars.put("pr", (BuiltinFunction) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            writeOut(sb.toString());
            return ClojureNil.INSTANCE;
        });

        globalVars.put("println", (BuiltinFunction) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], false));
            }
            writeOut(sb.toString() + "\n");
            return ClojureNil.INSTANCE;
        });

        globalVars.put("prn", (BuiltinFunction) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            writeOut(sb.toString() + "\n");
            return ClojureNil.INSTANCE;
        });

        // Collections
        globalVars.put("list", (BuiltinFunction) args -> {
            clojure.lang.IPersistentList list = clojure.lang.PersistentList.EMPTY;
            for (int i = args.length - 1; i >= 0; i--) {
                list = (clojure.lang.IPersistentList) list.cons(args[i]);
            }
            return list;
        });

        globalVars.put("vector", (BuiltinFunction) args ->
                clojure.lang.PersistentVector.create(java.util.Arrays.asList(args)));

        globalVars.put("first", (BuiltinFunction) args -> {
            checkArity(args, 1, "first");
            return clojureFirst(args[0]);
        });

        globalVars.put("rest", (BuiltinFunction) args -> {
            checkArity(args, 1, "rest");
            return clojureRest(args[0]);
        });

        globalVars.put("cons", (BuiltinFunction) args -> {
            checkArity(args, 2, "cons");
            return clojureCons(args[0], args[1]);
        });

        globalVars.put("count", (BuiltinFunction) args -> {
            checkArity(args, 1, "count");
            return clojureCount(args[0]);
        });

        globalVars.put("nth", (BuiltinFunction) args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("nth: expected 2 or 3 args");
            return clojureNth(args[0], args[1], args.length == 3 ? args[2] : null);
        });

        globalVars.put("conj", (BuiltinFunction) args -> {
            if (args.length < 2)
                throw new RuntimeException("conj: expected at least 2 args");
            return clojureConj(args);
        });

        // Identity
        globalVars.put("identity", (BuiltinFunction) args -> {
            checkArity(args, 1, "identity");
            return args[0];
        });

        globalVars.put("type", (BuiltinFunction) args -> {
            checkArity(args, 1, "type");
            if (args[0] instanceof ClojureNil) return "nil";
            return args[0].getClass().getName();
        });

        // --- Higher-order functions ---

        globalVars.put("apply", (BuiltinFunction) args -> {
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

        globalVars.put("map", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("map: expected at least 2 args");
            Object fn = args[0];
            Object coll = args[1];
            return lazyMap(fn, coll);
        });

        globalVars.put("filter", (BuiltinFunction) args -> {
            checkArity(args, 2, "filter");
            Object fn = args[0];
            Object coll = args[1];
            return lazyFilter(fn, coll);
        });

        globalVars.put("reduce", (BuiltinFunction) args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("reduce: expected 2 or 3 args");
            Object fn = args[0];
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
                seq = seq.next();
            }
            return acc;
        });

        globalVars.put("range", (BuiltinFunction) args -> {
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

        globalVars.put("eval", (BuiltinFunction) args -> {
            checkArity(args, 1, "eval");
            return evalForm(args[0]);
        });

        globalVars.put("read-string", (BuiltinFunction) args -> {
            checkArity(args, 1, "read-string");
            if (!(args[0] instanceof String s))
                throw new RuntimeException("read-string: expected a string");
            try {
                java.io.PushbackReader r = new java.io.PushbackReader(new java.io.StringReader(s), 2);
                return clojure.lang.LispReader.read(r, true, null, false, null);
            } catch (Exception e) {
                throw new RuntimeException("read-string: " + e.getMessage(), e);
            }
        });

        // --- More predicates ---

        globalVars.put("empty?", (BuiltinFunction) args -> {
            checkArity(args, 1, "empty?");
            if (args[0] instanceof ClojureNil) return true;
            if (args[0] instanceof clojure.lang.Seqable s) return s.seq() == null;
            if (args[0] instanceof String str) return str.isEmpty();
            return false;
        });

        globalVars.put("seq?", (BuiltinFunction) args -> {
            checkArity(args, 1, "seq?");
            return args[0] instanceof clojure.lang.ISeq;
        });

        globalVars.put("vector?", (BuiltinFunction) args -> {
            checkArity(args, 1, "vector?");
            return args[0] instanceof clojure.lang.IPersistentVector;
        });

        globalVars.put("map?", (BuiltinFunction) args -> {
            checkArity(args, 1, "map?");
            return args[0] instanceof clojure.lang.IPersistentMap;
        });

        globalVars.put("keyword?", (BuiltinFunction) args -> {
            checkArity(args, 1, "keyword?");
            return args[0] instanceof clojure.lang.Keyword;
        });

        globalVars.put("symbol?", (BuiltinFunction) args -> {
            checkArity(args, 1, "symbol?");
            return args[0] instanceof clojure.lang.Symbol;
        });

        globalVars.put("fn?", (BuiltinFunction) args -> {
            checkArity(args, 1, "fn?");
            return args[0] instanceof ClojureFunction || args[0] instanceof BuiltinFunction;
        });

        // --- More collection ops ---

        globalVars.put("seq", (BuiltinFunction) args -> {
            checkArity(args, 1, "seq");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (args[0] instanceof clojure.lang.Seqable s) {
                clojure.lang.ISeq seq = s.seq();
                return seq == null ? ClojureNil.INSTANCE : seq;
            }
            throw new RuntimeException("seq: not seqable: " + args[0]);
        });

        globalVars.put("into", (BuiltinFunction) args -> {
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

        globalVars.put("reverse", (BuiltinFunction) args -> {
            checkArity(args, 1, "reverse");
            if (args[0] instanceof ClojureNil) return clojure.lang.PersistentList.EMPTY;
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (clojure.lang.ISeq seq = seqOf(args[0]); seq != null; seq = seq.next()) {
                result = result.cons(seq.first());
            }
            return result;
        });

        // --- Math ---

        globalVars.put("inc", (BuiltinFunction) args -> {
            checkArity(args, 1, "inc");
            return addNumbers(args[0], 1L);
        });

        globalVars.put("dec", (BuiltinFunction) args -> {
            checkArity(args, 1, "dec");
            return subtractNumbers(args[0], 1L);
        });

        globalVars.put("max", (BuiltinFunction) args -> {
            if (args.length == 0) throw new RuntimeException("max: expected at least 1 arg");
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                if (compareNumbers(result, args[i]) < 0) result = args[i];
            }
            return result;
        });

        globalVars.put("min", (BuiltinFunction) args -> {
            if (args.length == 0) throw new RuntimeException("min: expected at least 1 arg");
            Object result = args[0];
            for (int i = 1; i < args.length; i++) {
                if (compareNumbers(result, args[i]) > 0) result = args[i];
            }
            return result;
        });

        globalVars.put("abs", (BuiltinFunction) args -> {
            checkArity(args, 1, "abs");
            if (args[0] instanceof Long l) return Math.abs(l);
            return Math.abs(toDouble(args[0]));
        });

        // --- Misc ---

        globalVars.put("symbol", (BuiltinFunction) args -> {
            checkArity(args, 1, "symbol");
            return clojure.lang.Symbol.intern(args[0].toString());
        });

        globalVars.put("keyword", (BuiltinFunction) args -> {
            checkArity(args, 1, "keyword");
            String s = args[0].toString();
            if (s.startsWith(":")) s = s.substring(1);
            return clojure.lang.Keyword.intern(s);
        });

        globalVars.put("name", (BuiltinFunction) args -> {
            checkArity(args, 1, "name");
            if (args[0] instanceof clojure.lang.Named n) return n.getName();
            if (args[0] instanceof String s) return s;
            throw new RuntimeException("name: not a named value: " + args[0]);
        });

        globalVars.put("hash-map", (BuiltinFunction) args -> {
            if (args.length % 2 != 0) throw new RuntimeException("hash-map: odd number of args");
            return clojure.lang.PersistentArrayMap.createAsIfByAssoc(args);
        });

        globalVars.put("get", (BuiltinFunction) args -> {
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
            return notFound;
        });

        globalVars.put("assoc", (BuiltinFunction) args -> {
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

        globalVars.put("dissoc", (BuiltinFunction) args -> {
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

        globalVars.put("contains?", (BuiltinFunction) args -> {
            checkArity(args, 2, "contains?");
            if (args[0] instanceof ClojureNil) return false;
            if (args[0] instanceof clojure.lang.Associative a) return a.containsKey(args[1]);
            return false;
        });

        globalVars.put("keys", (BuiltinFunction) args -> {
            checkArity(args, 1, "keys");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (args[0] instanceof clojure.lang.IPersistentMap m) {
                java.util.List<Object> keys = new ArrayList<>();
                for (clojure.lang.ISeq s = m.seq(); s != null; s = s.next()) {
                    keys.add(((clojure.lang.IMapEntry) s.first()).key());
                }
                clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
                for (int i = keys.size() - 1; i >= 0; i--) result = result.cons(keys.get(i));
                return result;
            }
            throw new RuntimeException("keys: not a map");
        });

        globalVars.put("vals", (BuiltinFunction) args -> {
            checkArity(args, 1, "vals");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (args[0] instanceof clojure.lang.IPersistentMap m) {
                java.util.List<Object> vals = new ArrayList<>();
                for (clojure.lang.ISeq s = m.seq(); s != null; s = s.next()) {
                    vals.add(((clojure.lang.IMapEntry) s.first()).val());
                }
                clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
                for (int i = vals.size() - 1; i >= 0; i--) result = result.cons(vals.get(i));
                return result;
            }
            throw new RuntimeException("vals: not a map");
        });

        // --- Atom ---

        globalVars.put("atom", (BuiltinFunction) args -> {
            checkArity(args, 1, "atom");
            return new ClojureAtom(args[0]);
        });

        globalVars.put("deref", (BuiltinFunction) args -> {
            checkArity(args, 1, "deref");
            if (args[0] instanceof ClojureAtom a) return a.deref();
            throw new RuntimeException("deref: not an atom: " + args[0]);
        });

        globalVars.put("reset!", (BuiltinFunction) args -> {
            checkArity(args, 2, "reset!");
            if (!(args[0] instanceof ClojureAtom a))
                throw new RuntimeException("reset!: not an atom");
            return a.reset(args[1]);
        });

        globalVars.put("swap!", (BuiltinFunction) args -> {
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
                if (a.compareAndSet(oldVal, newVal)) return newVal;
            }
        });

        globalVars.put("atom?", (BuiltinFunction) args -> {
            checkArity(args, 1, "atom?");
            return args[0] instanceof ClojureAtom;
        });

        // --- More numeric predicates ---

        globalVars.put("zero?", (BuiltinFunction) args -> {
            checkArity(args, 1, "zero?");
            return compareNumbers(args[0], 0L) == 0;
        });
        globalVars.put("pos?", (BuiltinFunction) args -> {
            checkArity(args, 1, "pos?");
            return compareNumbers(args[0], 0L) > 0;
        });
        globalVars.put("neg?", (BuiltinFunction) args -> {
            checkArity(args, 1, "neg?");
            return compareNumbers(args[0], 0L) < 0;
        });
        globalVars.put("even?", (BuiltinFunction) args -> {
            checkArity(args, 1, "even?");
            return ((Number) args[0]).longValue() % 2 == 0;
        });
        globalVars.put("odd?", (BuiltinFunction) args -> {
            checkArity(args, 1, "odd?");
            return ((Number) args[0]).longValue() % 2 != 0;
        });

        // --- Higher-order extras ---

        globalVars.put("comp", (BuiltinFunction) args -> {
            if (args.length == 0) return (BuiltinFunction) a -> { checkArity(a, 1, "identity"); return a[0]; };
            return (BuiltinFunction) callArgs -> {
                Object result = callFunction(args[args.length - 1], callArgs);
                for (int i = args.length - 2; i >= 0; i--) {
                    result = callFunction(args[i], new Object[]{result});
                }
                return result;
            };
        });

        globalVars.put("partial", (BuiltinFunction) args -> {
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

        globalVars.put("constantly", (BuiltinFunction) args -> {
            checkArity(args, 1, "constantly");
            Object val = args[0];
            return (BuiltinFunction) ignored -> val;
        });

        globalVars.put("some", (BuiltinFunction) args -> {
            checkArity(args, 2, "some");
            Object fn = args[0];
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object result = callFunction(fn, new Object[]{s.first()});
                if (isTruthy(result)) return result;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("every?", (BuiltinFunction) args -> {
            checkArity(args, 2, "every?");
            Object fn = args[0];
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null; s = s.next()) {
                Object result = callFunction(fn, new Object[]{s.first()});
                if (!isTruthy(result)) return false;
            }
            return true;
        });

        globalVars.put("take", (BuiltinFunction) args -> {
            checkArity(args, 2, "take");
            int n = ((Number) args[0]).intValue();
            java.util.List<Object> items = new ArrayList<>();
            int count = 0;
            for (clojure.lang.ISeq s = seqOf(args[1]); s != null && count < n; s = s.next(), count++) {
                items.add(s.first());
            }
            return clojure.lang.PersistentVector.create(items);
        });

        globalVars.put("drop", (BuiltinFunction) args -> {
            checkArity(args, 2, "drop");
            int n = ((Number) args[0]).intValue();
            clojure.lang.ISeq s = seqOf(args[1]);
            for (int i = 0; i < n && s != null; i++) s = s.next();
            if (s == null) return clojure.lang.PersistentVector.EMPTY;
            java.util.List<Object> items = new ArrayList<>();
            for (; s != null; s = s.next()) items.add(s.first());
            return clojure.lang.PersistentVector.create(items);
        });

        globalVars.put("concat", (BuiltinFunction) args -> {
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

        globalVars.put("mapcat", (BuiltinFunction) args -> {
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

        globalVars.put("not=", (BuiltinFunction) args -> {
            if (args.length < 2) return false;
            return !clojureEquals(args[0], args[1]);
        });

        globalVars.put("ex-info", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("ex-info: expected at least 2 args");
            String msg = args[0].toString();
            return new clojure.lang.ExceptionInfo(msg, (clojure.lang.IPersistentMap) args[1]);
        });

        globalVars.put("ex-message", (BuiltinFunction) args -> {
            checkArity(args, 1, "ex-message");
            if (args[0] instanceof Throwable t) return t.getMessage();
            return ClojureNil.INSTANCE;
        });

        globalVars.put("ex-data", (BuiltinFunction) args -> {
            checkArity(args, 1, "ex-data");
            if (args[0] instanceof clojure.lang.ExceptionInfo ei) return ei.getData();
            return ClojureNil.INSTANCE;
        });

        // --- String extras ---

        globalVars.put("subs", (BuiltinFunction) args -> {
            if (args.length < 2 || args.length > 3)
                throw new RuntimeException("subs: expected 2 or 3 args");
            String s = (String) args[0];
            int start = ((Number) args[1]).intValue();
            if (args.length == 3) return s.substring(start, ((Number) args[2]).intValue());
            return s.substring(start);
        });

        globalVars.put("string/join", (BuiltinFunction) args -> {
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

        globalVars.put("iterate", (BuiltinFunction) args -> {
            checkArity(args, 2, "iterate");
            Object fn = args[0];
            Object val = args[1];
            return lazyIterate(fn, val);
        });

        globalVars.put("repeat", (BuiltinFunction) args -> {
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

        globalVars.put("take-while", (BuiltinFunction) args -> {
            checkArity(args, 2, "take-while");
            Object pred = args[0];
            Object coll = args[1];
            return lazyTakeWhile(pred, coll);
        });

        globalVars.put("drop-while", (BuiltinFunction) args -> {
            checkArity(args, 2, "drop-while");
            Object pred = args[0];
            Object coll = args[1];
            clojure.lang.ISeq s = seqOf(coll);
            while (s != null && isTruthy(callFunction(pred, new Object[]{s.first()})))
                s = s.next();
            if (s == null) return clojure.lang.PersistentList.EMPTY;
            return s;
        });

        globalVars.put("realized?", (BuiltinFunction) args -> {
            checkArity(args, 1, "realized?");
            if (args[0] instanceof clojure.lang.IPending p) return p.isRealized();
            return true;
        });

        globalVars.put("doall", (BuiltinFunction) args -> {
            if (args.length < 1 || args.length > 2)
                throw new RuntimeException("doall: expected 1 or 2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            clojure.lang.ISeq s = seqOf(coll);
            clojure.lang.ISeq head = s;
            while (s != null) s = s.next();
            return head == null ? clojure.lang.PersistentList.EMPTY : head;
        });

        globalVars.put("dorun", (BuiltinFunction) args -> {
            if (args.length < 1 || args.length > 2)
                throw new RuntimeException("dorun: expected 1 or 2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            clojure.lang.ISeq s = seqOf(coll);
            while (s != null) s = s.next();
            return ClojureNil.INSTANCE;
        });

        // --- Type checking ---

        globalVars.put("instance?", (BuiltinFunction) args -> {
            checkArity(args, 2, "instance?");
            if (!(args[0] instanceof Class<?> c))
                throw new RuntimeException("instance?: first arg must be a class");
            Object val = args[1];
            if (val instanceof ClojureNil) return false;
            return c.isInstance(val);
        });

        globalVars.put("class", (BuiltinFunction) args -> {
            checkArity(args, 1, "class");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            return args[0].getClass();
        });

        // --- Metadata ---

        globalVars.put("meta", (BuiltinFunction) args -> {
            checkArity(args, 1, "meta");
            if (args[0] instanceof clojure.lang.IMeta m) {
                clojure.lang.IPersistentMap meta = m.meta();
                return meta == null ? ClojureNil.INSTANCE : meta;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("with-meta", (BuiltinFunction) args -> {
            checkArity(args, 2, "with-meta");
            if (!(args[0] instanceof clojure.lang.IObj obj))
                throw new RuntimeException("with-meta: object does not support metadata");
            if (args[1] instanceof ClojureNil)
                return obj.withMeta(null);
            if (!(args[1] instanceof clojure.lang.IPersistentMap meta))
                throw new RuntimeException("with-meta: metadata must be a map");
            return obj.withMeta(meta);
        });

        globalVars.put("vary-meta", (BuiltinFunction) args -> {
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

        globalVars.put("satisfies?", (BuiltinFunction) args -> {
            checkArity(args, 2, "satisfies?");
            if (!(args[0] instanceof ClojureProtocol proto))
                throw new RuntimeException("satisfies?: first arg must be a protocol");
            return proto.hasImplementation(args[1]);
        });

        // --- Type name ---

        globalVars.put("type", (BuiltinFunction) args -> {
            checkArity(args, 1, "type");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (args[0] instanceof ClojureDeftypeInstance inst) return inst.getTypeName();
            return args[0].getClass();
        });

        // --- Regex ---

        globalVars.put("re-pattern", (BuiltinFunction) args -> {
            checkArity(args, 1, "re-pattern");
            if (args[0] instanceof java.util.regex.Pattern p) return p;
            return java.util.regex.Pattern.compile(args[0].toString());
        });

        globalVars.put("re-find", (BuiltinFunction) args -> {
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

        globalVars.put("re-matches", (BuiltinFunction) args -> {
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

        globalVars.put("re-seq", (BuiltinFunction) args -> {
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

        globalVars.put("re-matcher", (BuiltinFunction) args -> {
            checkArity(args, 2, "re-matcher");
            java.util.regex.Pattern p;
            if (args[0] instanceof java.util.regex.Pattern pp) p = pp;
            else p = java.util.regex.Pattern.compile(args[0].toString());
            return p.matcher(args[1].toString());
        });

        // --- String operations ---

        globalVars.put("clojure.string/split", (BuiltinFunction) args -> {
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

        globalVars.put("clojure.string/replace", (BuiltinFunction) args -> {
            checkArity(args, 3, "clojure.string/replace");
            String s = args[0].toString();
            if (args[1] instanceof java.util.regex.Pattern p) {
                return p.matcher(s).replaceAll(args[2].toString());
            }
            return s.replace(args[1].toString(), args[2].toString());
        });

        globalVars.put("clojure.string/trim", (BuiltinFunction) args -> {
            checkArity(args, 1, "clojure.string/trim");
            return args[0].toString().trim();
        });

        globalVars.put("clojure.string/lower-case", (BuiltinFunction) args -> {
            checkArity(args, 1, "clojure.string/lower-case");
            return args[0].toString().toLowerCase();
        });

        globalVars.put("clojure.string/upper-case", (BuiltinFunction) args -> {
            checkArity(args, 1, "clojure.string/upper-case");
            return args[0].toString().toUpperCase();
        });

        globalVars.put("clojure.string/starts-with?", (BuiltinFunction) args -> {
            checkArity(args, 2, "clojure.string/starts-with?");
            return args[0].toString().startsWith(args[1].toString());
        });

        globalVars.put("clojure.string/ends-with?", (BuiltinFunction) args -> {
            checkArity(args, 2, "clojure.string/ends-with?");
            return args[0].toString().endsWith(args[1].toString());
        });

        globalVars.put("clojure.string/includes?", (BuiltinFunction) args -> {
            checkArity(args, 2, "clojure.string/includes?");
            return args[0].toString().contains(args[1].toString());
        });

        globalVars.put("clojure.string/blank?", (BuiltinFunction) args -> {
            checkArity(args, 1, "clojure.string/blank?");
            if (args[0] instanceof ClojureNil) return true;
            return args[0].toString().isBlank();
        });

        // --- Set operations ---

        globalVars.put("set", (BuiltinFunction) args -> {
            checkArity(args, 1, "set");
            if (args[0] instanceof ClojureNil) return clojure.lang.PersistentHashSet.EMPTY;
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next())
                items.add(s.first());
            return clojure.lang.PersistentHashSet.create(items);
        });

        globalVars.put("set?", (BuiltinFunction) args -> {
            checkArity(args, 1, "set?");
            return args[0] instanceof clojure.lang.IPersistentSet;
        });

        globalVars.put("contains?", (BuiltinFunction) args -> {
            checkArity(args, 2, "contains?");
            if (args[0] instanceof clojure.lang.IPersistentSet s)
                return s.contains(args[1]);
            if (args[0] instanceof clojure.lang.Associative a)
                return a.containsKey(args[1]);
            return false;
        });

        globalVars.put("disj", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("disj: expected at least 2 args");
            if (!(args[0] instanceof clojure.lang.IPersistentSet s))
                throw new RuntimeException("disj: first arg must be a set");
            for (int i = 1; i < args.length; i++)
                s = s.disjoin(args[i]);
            return s;
        });

        globalVars.put("union", (BuiltinFunction) args -> {
            clojure.lang.IPersistentSet result = clojure.lang.PersistentHashSet.EMPTY;
            for (Object arg : args) {
                if (arg instanceof ClojureNil) continue;
                for (clojure.lang.ISeq s = seqOf(arg); s != null; s = s.next())
                    result = (clojure.lang.IPersistentSet) result.cons(s.first());
            }
            return result;
        });

        globalVars.put("intersection", (BuiltinFunction) args -> {
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

        globalVars.put("difference", (BuiltinFunction) args -> {
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

        // --- Namespace builtins ---

        globalVars.put("*ns*", "user");

        globalVars.put("ns-name", (BuiltinFunction) args -> {
            checkArity(args, 1, "ns-name");
            if (args[0] instanceof ClojureNamespace ns) return clojure.lang.Symbol.intern(ns.getName());
            return clojure.lang.Symbol.intern(currentNamespace);
        });

        globalVars.put("find-ns", (BuiltinFunction) args -> {
            checkArity(args, 1, "find-ns");
            String name = args[0].toString();
            ClojureNamespace ns = namespaces.get(name);
            return ns == null ? ClojureNil.INSTANCE : ns;
        });

        globalVars.put("all-ns", (BuiltinFunction) args -> {
            java.util.List<Object> nsList = new ArrayList<>(namespaces.values());
            return clojure.lang.PersistentVector.create(nsList);
        });

        // --- Additional core functions ---

        globalVars.put("name", (BuiltinFunction) args -> {
            checkArity(args, 1, "name");
            if (args[0] instanceof clojure.lang.Named n) return n.getName();
            if (args[0] instanceof String s) return s;
            throw new RuntimeException("name: not a named value: " + args[0]);
        });

        globalVars.put("namespace", (BuiltinFunction) args -> {
            checkArity(args, 1, "namespace");
            if (args[0] instanceof clojure.lang.Named n) {
                String ns = n.getNamespace();
                return ns == null ? ClojureNil.INSTANCE : ns;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("keyword", (BuiltinFunction) args -> {
            if (args.length == 1) {
                if (args[0] instanceof clojure.lang.Keyword k) return k;
                return clojure.lang.Keyword.intern(args[0].toString());
            }
            if (args.length == 2) {
                return clojure.lang.Keyword.intern(args[0].toString(), args[1].toString());
            }
            throw new RuntimeException("keyword: expected 1 or 2 args");
        });

        globalVars.put("symbol", (BuiltinFunction) args -> {
            if (args.length == 1) {
                if (args[0] instanceof clojure.lang.Symbol s) return s;
                return clojure.lang.Symbol.intern(args[0].toString());
            }
            if (args.length == 2) {
                return clojure.lang.Symbol.intern(args[0].toString(), args[1].toString());
            }
            throw new RuntimeException("symbol: expected 1 or 2 args");
        });

        globalVars.put("gensym", (BuiltinFunction) args -> {
            String prefix = args.length > 0 ? args[0].toString() : "G__";
            return clojure.lang.Symbol.intern(prefix + gensymCounter.incrementAndGet());
        });

        globalVars.put("hash-map", (BuiltinFunction) args -> {
            if (args.length % 2 != 0) throw new RuntimeException("hash-map: odd number of args");
            Object[] kvs = args;
            return clojure.lang.PersistentHashMap.create(kvs);
        });

        globalVars.put("hash-set", (BuiltinFunction) args -> {
            java.util.List<Object> items = new ArrayList<>();
            for (Object arg : args) items.add(arg);
            return clojure.lang.PersistentHashSet.create(items);
        });

        globalVars.put("sorted-map", (BuiltinFunction) args -> {
            if (args.length % 2 != 0) throw new RuntimeException("sorted-map: odd number of args");
            clojure.lang.PersistentTreeMap m = clojure.lang.PersistentTreeMap.EMPTY;
            for (int i = 0; i < args.length; i += 2)
                m = (clojure.lang.PersistentTreeMap) m.assoc(args[i], args[i + 1]);
            return m;
        });

        globalVars.put("into", (BuiltinFunction) args -> {
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

        globalVars.put("frequencies", (BuiltinFunction) args -> {
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

        globalVars.put("group-by", (BuiltinFunction) args -> {
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

        globalVars.put("sort", (BuiltinFunction) args -> {
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

        globalVars.put("sort-by", (BuiltinFunction) args -> {
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

        globalVars.put("distinct", (BuiltinFunction) args -> {
            checkArity(args, 1, "distinct");
            java.util.Set<Object> seen = new java.util.LinkedHashSet<>();
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next())
                seen.add(s.first());
            java.util.List<Object> items = new ArrayList<>(seen);
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        globalVars.put("flatten", (BuiltinFunction) args -> {
            checkArity(args, 1, "flatten");
            java.util.List<Object> items = new ArrayList<>();
            flattenInto(args[0], items);
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) result = result.cons(items.get(i));
            return result;
        });

        globalVars.put("partition", (BuiltinFunction) args -> {
            if (args.length < 2 || args.length > 4)
                throw new RuntimeException("partition: expected 2-4 args");
            int n = ((Number) args[0]).intValue();
            int step = args.length >= 3 ? ((Number) args[1]).intValue() : n;
            Object coll = args.length == 2 ? args[1] : (args.length == 3 ? args[2] : args[3]);
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(coll); s != null; s = s.next())
                items.add(s.first());
            java.util.List<Object> parts = new ArrayList<>();
            for (int i = 0; i + n <= items.size(); i += step)
                parts.add(clojure.lang.PersistentVector.create(items.subList(i, i + n)));
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = parts.size() - 1; i >= 0; i--) result = result.cons(parts.get(i));
            return result;
        });

        globalVars.put("interleave", (BuiltinFunction) args -> {
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

        globalVars.put("interpose", (BuiltinFunction) args -> {
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

        globalVars.put("zipmap", (BuiltinFunction) args -> {
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

        globalVars.put("map-indexed", (BuiltinFunction) args -> {
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

        globalVars.put("keep", (BuiltinFunction) args -> {
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

        globalVars.put("update", (BuiltinFunction) args -> {
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

        globalVars.put("update-in", (BuiltinFunction) args -> {
            if (args.length < 3) throw new RuntimeException("update-in: expected at least 3 args");
            return updateIn(args[0], (clojure.lang.IPersistentVector) args[1], args[2],
                    Arrays.copyOfRange(args, 3, args.length));
        });

        globalVars.put("get-in", (BuiltinFunction) args -> {
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

        globalVars.put("assoc-in", (BuiltinFunction) args -> {
            checkArity(args, 3, "assoc-in");
            return assocIn(args[0], (clojure.lang.IPersistentVector) args[1], args[2]);
        });

        globalVars.put("select-keys", (BuiltinFunction) args -> {
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

        globalVars.put("merge", (BuiltinFunction) args -> {
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

        globalVars.put("merge-with", (BuiltinFunction) args -> {
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

        globalVars.put("map?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.IPersistentMap);
        globalVars.put("vector?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.IPersistentVector);
        globalVars.put("list?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.IPersistentList);
        globalVars.put("seq?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.ISeq);
        globalVars.put("coll?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.IPersistentCollection);
        globalVars.put("sequential?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.Sequential);
        globalVars.put("associative?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.Associative);
        globalVars.put("fn?", (BuiltinFunction) a ->
                a[0] instanceof ClojureFunction || a[0] instanceof MultiArityFunction || a[0] instanceof BuiltinFunction);
        globalVars.put("ifn?", (BuiltinFunction) a ->
                a[0] instanceof ClojureFunction || a[0] instanceof MultiArityFunction ||
                        a[0] instanceof BuiltinFunction || a[0] instanceof clojure.lang.Keyword ||
                        a[0] instanceof clojure.lang.IPersistentMap || a[0] instanceof clojure.lang.IPersistentSet);
        globalVars.put("number?", (BuiltinFunction) a -> a[0] instanceof Number);
        globalVars.put("integer?", (BuiltinFunction) a -> a[0] instanceof Long || a[0] instanceof Integer);
        globalVars.put("float?", (BuiltinFunction) a -> a[0] instanceof Double || a[0] instanceof Float);
        globalVars.put("string?", (BuiltinFunction) a -> a[0] instanceof String);
        globalVars.put("keyword?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.Keyword);
        globalVars.put("symbol?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.Symbol);
        globalVars.put("boolean?", (BuiltinFunction) a -> a[0] instanceof Boolean);
        globalVars.put("true?", (BuiltinFunction) a -> Boolean.TRUE.equals(a[0]));
        globalVars.put("false?", (BuiltinFunction) a -> Boolean.FALSE.equals(a[0]));
        globalVars.put("char?", (BuiltinFunction) a -> a[0] instanceof Character);
        globalVars.put("ratio?", (BuiltinFunction) a -> a[0] instanceof clojure.lang.Ratio);

        // --- Higher order ---

        globalVars.put("juxt", (BuiltinFunction) args -> {
            Object[] fns = args.clone();
            return (BuiltinFunction) innerArgs -> {
                java.util.List<Object> results = new ArrayList<>();
                for (Object fn : fns) results.add(callFunction(fn, innerArgs));
                return clojure.lang.PersistentVector.create(results);
            };
        });

        globalVars.put("memoize", (BuiltinFunction) args -> {
            checkArity(args, 1, "memoize");
            Object fn = args[0];
            ConcurrentHashMap<Object, Object> cache = new ConcurrentHashMap<>();
            return (BuiltinFunction) innerArgs -> {
                Object key = clojure.lang.PersistentVector.create(java.util.List.of(innerArgs));
                return cache.computeIfAbsent(key, k -> callFunction(fn, innerArgs));
            };
        });

        globalVars.put("trampoline", (BuiltinFunction) args -> {
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

        globalVars.put("future-call", (BuiltinFunction) args -> {
            checkArity(args, 1, "future-call");
            Object fn = args[0];
            java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors
                    .newSingleThreadExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
            java.util.concurrent.Future<Object> future = exec.submit(() -> callFunction(fn, new Object[0]));
            return future;
        });

        globalVars.put("deref", (BuiltinFunction) dargs -> {
            if (dargs.length < 1 || dargs.length > 3)
                throw new RuntimeException("deref: expected 1-3 args");
            Object target = dargs[0];
            if (target instanceof ClojureAtom a) return a.deref();
            if (target instanceof java.util.concurrent.Future<?> f) {
                try {
                    if (dargs.length >= 2) {
                        long timeout = ((Number) dargs[1]).longValue();
                        Object timeoutVal = dargs.length == 3 ? dargs[2] : ClojureNil.INSTANCE;
                        try {
                            Object result = f.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                            return result == null ? ClojureNil.INSTANCE : result;
                        } catch (java.util.concurrent.TimeoutException e) {
                            return timeoutVal;
                        }
                    }
                    Object result = f.get();
                    return result == null ? ClojureNil.INSTANCE : result;
                } catch (Exception e) {
                    throw new RuntimeException("deref failed: " + e.getMessage(), e);
                }
            }
            throw new RuntimeException("deref: not a dereferenceable: " + target);
        });

        globalVars.put("future-done?", (BuiltinFunction) args -> {
            checkArity(args, 1, "future-done?");
            if (args[0] instanceof java.util.concurrent.Future<?> f) return f.isDone();
            return false;
        });

        globalVars.put("future-cancel", (BuiltinFunction) args -> {
            checkArity(args, 1, "future-cancel");
            if (args[0] instanceof java.util.concurrent.Future<?> f) return f.cancel(true);
            return false;
        });

        // --- Misc ---

        globalVars.put("identity", (BuiltinFunction) args -> { checkArity(args, 1, "identity"); return args[0]; });
        globalVars.put("constantly", (BuiltinFunction) args -> {
            checkArity(args, 1, "constantly");
            Object val = args[0];
            return (BuiltinFunction) a -> val;
        });
        globalVars.put("complement", (BuiltinFunction) args -> {
            checkArity(args, 1, "complement");
            Object fn = args[0];
            return (BuiltinFunction) a -> !isTruthy(callFunction(fn, a));
        });
        globalVars.put("fnil", (BuiltinFunction) args -> {
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

        globalVars.put("rand", (BuiltinFunction) args -> {
            if (args.length == 0) return Math.random();
            return Math.random() * ((Number) args[0]).doubleValue();
        });

        globalVars.put("rand-int", (BuiltinFunction) args -> {
            checkArity(args, 1, "rand-int");
            return (long) (Math.random() * ((Number) args[0]).longValue());
        });

        globalVars.put("rand-nth", (BuiltinFunction) args -> {
            checkArity(args, 1, "rand-nth");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next()) items.add(s.first());
            if (items.isEmpty()) throw new RuntimeException("rand-nth: empty collection");
            return items.get((int) (Math.random() * items.size()));
        });

        globalVars.put("shuffle", (BuiltinFunction) args -> {
            checkArity(args, 1, "shuffle");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq s = seqOf(args[0]); s != null; s = s.next()) items.add(s.first());
            java.util.Collections.shuffle(items);
            return clojure.lang.PersistentVector.create(items);
        });

        globalVars.put("format", (BuiltinFunction) args -> {
            if (args.length < 1) throw new RuntimeException("format: expected at least 1 arg");
            String fmt = args[0].toString();
            Object[] fmtArgs = Arrays.copyOfRange(args, 1, args.length);
            return String.format(fmt, fmtArgs);
        });

        globalVars.put("slurp", (BuiltinFunction) args -> {
            checkArity(args, 1, "slurp");
            try {
                return java.nio.file.Files.readString(java.nio.file.Path.of(args[0].toString()));
            } catch (java.io.IOException e) {
                throw new RuntimeException("slurp: " + e.getMessage());
            }
        });

        globalVars.put("spit", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("spit: expected at least 2 args");
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of(args[0].toString()),
                        args[1].toString());
            } catch (java.io.IOException e) {
                throw new RuntimeException("spit: " + e.getMessage());
            }
            return ClojureNil.INSTANCE;
        });

        // --- Phase 8: Regex ---
        globalVars.put("re-pattern", (BuiltinFunction) args -> {
            checkArity(args, 1, "re-pattern");
            return Pattern.compile(args[0].toString());
        });

        globalVars.put("re-find", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("re-find: expected 2 args");
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

        globalVars.put("re-matches", (BuiltinFunction) args -> {
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

        globalVars.put("re-seq", (BuiltinFunction) args -> {
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

        globalVars.put("re-matcher", (BuiltinFunction) args -> {
            checkArity(args, 2, "re-matcher");
            Pattern pat = (args[0] instanceof Pattern p) ? p : Pattern.compile(args[0].toString());
            return pat.matcher(args[1].toString());
        });

        // --- Phase 8: Additional seq operations ---
        globalVars.put("group-by", (BuiltinFunction) args -> {
            checkArity(args, 2, "group-by");
            Object f = args[0];
            Object coll = args[1];
            java.util.Map<Object, java.util.List<Object>> groups = new java.util.LinkedHashMap<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(coll); seq != null; seq = seq.next()) {
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

        globalVars.put("frequencies", (BuiltinFunction) args -> {
            checkArity(args, 1, "frequencies");
            java.util.Map<Object, Long> freqs = new java.util.LinkedHashMap<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                Object item = seq.first();
                freqs.merge(item, 1L, Long::sum);
            }
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (var entry : freqs.entrySet()) {
                result = result.assoc(entry.getKey(), entry.getValue());
            }
            return result;
        });

        globalVars.put("take-while", (BuiltinFunction) args -> {
            checkArity(args, 2, "take-while");
            Object pred = args[0];
            return new LazySeq(() -> {
                return lazyTakeWhile(pred, clojure.lang.RT.seq(args[1]));
            });
        });

        globalVars.put("drop-while", (BuiltinFunction) args -> {
            checkArity(args, 2, "drop-while");
            Object pred = args[0];
            Object coll = args[1];
            clojure.lang.ISeq seq = clojure.lang.RT.seq(coll);
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

        globalVars.put("every?", (BuiltinFunction) args -> {
            checkArity(args, 2, "every?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                if (!isTruthy(callFunction(pred, new Object[]{seq.first()}))) return false;
            }
            return true;
        });

        globalVars.put("some", (BuiltinFunction) args -> {
            checkArity(args, 2, "some");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                Object result = callFunction(pred, new Object[]{seq.first()});
                if (isTruthy(result)) return result;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("not-every?", (BuiltinFunction) args -> {
            checkArity(args, 2, "not-every?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                if (!isTruthy(callFunction(pred, new Object[]{seq.first()}))) return true;
            }
            return false;
        });

        globalVars.put("not-any?", (BuiltinFunction) args -> {
            checkArity(args, 2, "not-any?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                if (isTruthy(callFunction(pred, new Object[]{seq.first()}))) return false;
            }
            return true;
        });

        globalVars.put("into", (BuiltinFunction) args -> {
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
                for (clojure.lang.ISeq seq = clojure.lang.RT.seq(from); seq != null; seq = seq.next()) {
                    acc = callFunction(xrf, new Object[]{acc, seq.first()});
                    if (acc instanceof Reduced r) { acc = r.value; break; }
                }
                return acc;
            }
            from = args[1];
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(from); seq != null; seq = seq.next()) {
                Object item = seq.first();
                if (to instanceof clojure.lang.IPersistentVector v) {
                    to = v.cons(item);
                } else if (to instanceof clojure.lang.IPersistentMap m) {
                    if (item instanceof clojure.lang.MapEntry me) {
                        to = m.assoc(me.key(), me.val());
                    } else if (item instanceof clojure.lang.IPersistentVector iv && iv.count() == 2) {
                        to = m.assoc(iv.nth(0), iv.nth(1));
                    } else {
                        throw new RuntimeException("into: map expects [k v] pairs");
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

        globalVars.put("reduce-kv", (BuiltinFunction) args -> {
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

        globalVars.put("take-last", (BuiltinFunction) args -> {
            checkArity(args, 2, "take-last");
            int n = ((Number) args[0]).intValue();
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            if (n >= items.size()) return clojure.lang.PersistentList.create(items);
            return clojure.lang.PersistentList.create(items.subList(items.size() - n, items.size()));
        });

        globalVars.put("drop-last", (BuiltinFunction) args -> {
            int n = args.length == 1 ? 1 : ((Number) args[0]).intValue();
            Object coll = args.length == 1 ? args[0] : args[1];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(coll); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            if (n >= items.size()) return clojure.lang.PersistentList.EMPTY;
            return clojure.lang.PersistentList.create(items.subList(0, items.size() - n));
        });

        globalVars.put("split-at", (BuiltinFunction) args -> {
            checkArity(args, 2, "split-at");
            int n = ((Number) args[0]).intValue();
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            int splitPt = Math.min(n, items.size());
            return clojure.lang.PersistentVector.create(
                clojure.lang.PersistentList.create(items.subList(0, splitPt)),
                clojure.lang.PersistentList.create(items.subList(splitPt, items.size())));
        });

        globalVars.put("split-with", (BuiltinFunction) args -> {
            checkArity(args, 2, "split-with");
            Object pred = args[0];
            java.util.List<Object> before = new ArrayList<>();
            java.util.List<Object> after = new ArrayList<>();
            boolean splitting = true;
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
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

        globalVars.put("partition-by", (BuiltinFunction) args -> {
            checkArity(args, 2, "partition-by");
            Object f = args[0];
            java.util.List<Object> result = new ArrayList<>();
            java.util.List<Object> current = new ArrayList<>();
            Object lastVal = new Object(); // sentinel
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
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
        globalVars.put("meta", (BuiltinFunction) args -> {
            checkArity(args, 1, "meta");
            if (args[0] instanceof clojure.lang.IMeta obj) {
                clojure.lang.IPersistentMap m = obj.meta();
                return m == null ? ClojureNil.INSTANCE : m;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("with-meta", (BuiltinFunction) args -> {
            checkArity(args, 2, "with-meta");
            if (!(args[1] instanceof clojure.lang.IPersistentMap m))
                throw new RuntimeException("with-meta: metadata must be a map");
            if (args[0] instanceof clojure.lang.IObj obj) {
                return obj.withMeta(m);
            }
            throw new RuntimeException("with-meta: object does not support metadata");
        });

        globalVars.put("vary-meta", (BuiltinFunction) args -> {
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
        globalVars.put("ex-info", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("ex-info: expected 2-3 args");
            String msg = args[0].toString();
            clojure.lang.IPersistentMap data = (clojure.lang.IPersistentMap) args[1];
            Throwable cause = args.length > 2 && args[2] instanceof Throwable t ? t : null;
            return new clojure.lang.ExceptionInfo(msg, data, cause);
        });

        globalVars.put("ex-data", (BuiltinFunction) args -> {
            checkArity(args, 1, "ex-data");
            if (args[0] instanceof clojure.lang.IExceptionInfo ei) {
                return ei.getData();
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("ex-message", (BuiltinFunction) args -> {
            checkArity(args, 1, "ex-message");
            if (args[0] instanceof Throwable t) {
                String msg = t.getMessage();
                return msg == null ? ClojureNil.INSTANCE : msg;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("ex-cause", (BuiltinFunction) args -> {
            checkArity(args, 1, "ex-cause");
            if (args[0] instanceof Throwable t) {
                Throwable cause = t.getCause();
                return cause == null ? ClojureNil.INSTANCE : cause;
            }
            return ClojureNil.INSTANCE;
        });

        // --- Phase 8: String operations (clojure.string equivalents as builtins) ---
        globalVars.put("str/split", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("str/split: expected 2-3 args");
            String s = args[0].toString();
            Pattern pat = (args[1] instanceof Pattern p) ? p : Pattern.compile(args[1].toString());
            String[] parts = args.length > 2
                    ? pat.split(s, ((Number) args[2]).intValue())
                    : pat.split(s);
            return clojure.lang.PersistentVector.create((Object[]) parts);
        });

        globalVars.put("str/join", (BuiltinFunction) args -> {
            if (args.length == 1) {
                // (str/join coll)
                StringBuilder sb = new StringBuilder();
                for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                    sb.append(printString(seq.first(), false));
                }
                return sb.toString();
            }
            // (str/join sep coll)
            String sep = args[0].toString();
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                if (!first) sb.append(sep);
                sb.append(printString(seq.first(), false));
                first = false;
            }
            return sb.toString();
        });

        globalVars.put("str/trim", (BuiltinFunction) args -> {
            checkArity(args, 1, "str/trim");
            return args[0].toString().trim();
        });

        globalVars.put("str/triml", (BuiltinFunction) args -> {
            checkArity(args, 1, "str/triml");
            return args[0].toString().stripLeading();
        });

        globalVars.put("str/trimr", (BuiltinFunction) args -> {
            checkArity(args, 1, "str/trimr");
            return args[0].toString().stripTrailing();
        });

        globalVars.put("str/upper-case", (BuiltinFunction) args -> {
            checkArity(args, 1, "str/upper-case");
            return args[0].toString().toUpperCase();
        });

        globalVars.put("str/lower-case", (BuiltinFunction) args -> {
            checkArity(args, 1, "str/lower-case");
            return args[0].toString().toLowerCase();
        });

        globalVars.put("str/replace", (BuiltinFunction) args -> {
            checkArity(args, 3, "str/replace");
            String s = args[0].toString();
            if (args[1] instanceof Pattern pat) {
                return pat.matcher(s).replaceAll(args[2].toString());
            }
            return s.replace(args[1].toString(), args[2].toString());
        });

        globalVars.put("str/replace-first", (BuiltinFunction) args -> {
            checkArity(args, 3, "str/replace-first");
            String s = args[0].toString();
            if (args[1] instanceof Pattern pat) {
                return pat.matcher(s).replaceFirst(args[2].toString());
            }
            return s.replaceFirst(Pattern.quote(args[1].toString()), args[2].toString());
        });

        globalVars.put("str/starts-with?", (BuiltinFunction) args -> {
            checkArity(args, 2, "str/starts-with?");
            return args[0].toString().startsWith(args[1].toString());
        });

        globalVars.put("str/ends-with?", (BuiltinFunction) args -> {
            checkArity(args, 2, "str/ends-with?");
            return args[0].toString().endsWith(args[1].toString());
        });

        globalVars.put("str/includes?", (BuiltinFunction) args -> {
            checkArity(args, 2, "str/includes?");
            return args[0].toString().contains(args[1].toString());
        });

        globalVars.put("str/blank?", (BuiltinFunction) args -> {
            checkArity(args, 1, "str/blank?");
            Object o = args[0];
            if (o instanceof ClojureNil) return true;
            return o.toString().isBlank();
        });

        globalVars.put("str/index-of", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("str/index-of: expected 2-3 args");
            String s = args[0].toString();
            String target = args[1].toString();
            int idx = args.length > 2
                    ? s.indexOf(target, ((Number) args[2]).intValue())
                    : s.indexOf(target);
            return idx < 0 ? ClojureNil.INSTANCE : (Object) (long) idx;
        });

        globalVars.put("str/last-index-of", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("str/last-index-of: expected 2-3 args");
            String s = args[0].toString();
            String target = args[1].toString();
            int idx = args.length > 2
                    ? s.lastIndexOf(target, ((Number) args[2]).intValue())
                    : s.lastIndexOf(target);
            return idx < 0 ? ClojureNil.INSTANCE : (Object) (long) idx;
        });

        globalVars.put("str/reverse", (BuiltinFunction) args -> {
            checkArity(args, 1, "str/reverse");
            return new StringBuilder(args[0].toString()).reverse().toString();
        });

        globalVars.put("subs", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("subs: expected 2-3 args");
            String s = args[0].toString();
            int start = ((Number) args[1]).intValue();
            if (args.length > 2) {
                return s.substring(start, ((Number) args[2]).intValue());
            }
            return s.substring(start);
        });

        globalVars.put("char", (BuiltinFunction) args -> {
            checkArity(args, 1, "char");
            if (args[0] instanceof Number n) return (char) n.intValue();
            if (args[0] instanceof Character c) return c;
            if (args[0] instanceof String s && s.length() == 1) return s.charAt(0);
            throw new RuntimeException("char: cannot convert " + args[0]);
        });

        globalVars.put("int", (BuiltinFunction) args -> {
            checkArity(args, 1, "int");
            if (args[0] instanceof Number n) return (long) n.intValue();
            if (args[0] instanceof Character c) return (long) (int) c;
            throw new RuntimeException("int: cannot convert " + args[0]);
        });

        // --- Phase 8: Set operations ---
        globalVars.put("set", (BuiltinFunction) args -> {
            checkArity(args, 1, "set");
            if (args[0] instanceof ClojureNil) return clojure.lang.PersistentHashSet.EMPTY;
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            return clojure.lang.PersistentHashSet.create(items);
        });

        globalVars.put("set?", (BuiltinFunction) args -> {
            checkArity(args, 1, "set?");
            return args[0] instanceof clojure.lang.IPersistentSet;
        });

        globalVars.put("contains?", (BuiltinFunction) args -> {
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

        globalVars.put("disj", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("disj: expected at least 2 args");
            clojure.lang.IPersistentSet s = (clojure.lang.IPersistentSet) args[0];
            for (int i = 1; i < args.length; i++) {
                s = s.disjoin(args[i]);
            }
            return s;
        });

        // --- Phase 8: Misc ---
        globalVars.put("name", (BuiltinFunction) args -> {
            checkArity(args, 1, "name");
            if (args[0] instanceof clojure.lang.Named named) return named.getName();
            if (args[0] instanceof String s) return s;
            throw new RuntimeException("name: not a Named: " + args[0]);
        });

        globalVars.put("namespace", (BuiltinFunction) args -> {
            checkArity(args, 1, "namespace");
            if (args[0] instanceof clojure.lang.Named named) {
                String ns = named.getNamespace();
                return ns == null ? ClojureNil.INSTANCE : ns;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("symbol", (BuiltinFunction) args -> {
            if (args.length == 1) return clojure.lang.Symbol.intern(args[0].toString());
            if (args.length == 2) return clojure.lang.Symbol.intern(args[0].toString(), args[1].toString());
            throw new RuntimeException("symbol: expected 1-2 args");
        });

        globalVars.put("keyword", (BuiltinFunction) args -> {
            if (args.length == 1) {
                if (args[0] instanceof clojure.lang.Keyword k) return k;
                return clojure.lang.Keyword.intern(args[0].toString());
            }
            if (args.length == 2) return clojure.lang.Keyword.intern(args[0].toString(), args[1].toString());
            throw new RuntimeException("keyword: expected 1-2 args");
        });

        globalVars.put("gensym", (BuiltinFunction) args -> {
            String prefix = args.length > 0 ? args[0].toString() : "G__";
            return clojure.lang.Symbol.intern(prefix + gensymCounter.incrementAndGet());
        });

        globalVars.put("hash", (BuiltinFunction) args -> {
            checkArity(args, 1, "hash");
            if (args[0] instanceof ClojureNil) return 0L;
            return (long) args[0].hashCode();
        });

        globalVars.put("compare", (BuiltinFunction) args -> {
            checkArity(args, 2, "compare");
            @SuppressWarnings("unchecked")
            Comparable<Object> a = (Comparable<Object>) args[0];
            return (long) a.compareTo(args[1]);
        });

        globalVars.put("type", (BuiltinFunction) args -> {
            checkArity(args, 1, "type");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            return args[0].getClass();
        });

        globalVars.put("class", (BuiltinFunction) args -> {
            checkArity(args, 1, "class");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            return args[0].getClass();
        });

        globalVars.put("instance?", (BuiltinFunction) args -> {
            checkArity(args, 2, "instance?");
            if (!(args[0] instanceof Class<?> clazz))
                throw new RuntimeException("instance?: first arg must be a Class");
            if (args[1] instanceof ClojureNil) return false;
            return clazz.isInstance(args[1]);
        });

        globalVars.put("supers", (BuiltinFunction) args -> {
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

        globalVars.put("min-key", (BuiltinFunction) args -> {
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

        globalVars.put("max-key", (BuiltinFunction) args -> {
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

        globalVars.put("repeatedly", (BuiltinFunction) args -> {
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

        globalVars.put("run!", (BuiltinFunction) args -> {
            checkArity(args, 2, "run!");
            Object f = args[0];
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                callFunction(f, new Object[]{seq.first()});
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("mapv", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("mapv: expected at least 2 args");
            Object f = args[0];
            java.util.List<Object> result = new ArrayList<>();
            if (args.length == 2) {
                for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                    result.add(callFunction(f, new Object[]{seq.first()}));
                }
            } else {
                // Multi-coll mapv
                clojure.lang.ISeq[] seqs = new clojure.lang.ISeq[args.length - 1];
                for (int i = 1; i < args.length; i++) seqs[i-1] = clojure.lang.RT.seq(args[i]);
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

        globalVars.put("filterv", (BuiltinFunction) args -> {
            checkArity(args, 2, "filterv");
            Object pred = args[0];
            java.util.List<Object> result = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                Object item = seq.first();
                if (isTruthy(callFunction(pred, new Object[]{item}))) result.add(item);
            }
            return clojure.lang.PersistentVector.create(result);
        });

        // --- Phase 9: Volatile ---
        globalVars.put("volatile!", (BuiltinFunction) args -> {
            checkArity(args, 1, "volatile!");
            return new ClojureVolatile(args[0]);
        });

        globalVars.put("vreset!", (BuiltinFunction) args -> {
            checkArity(args, 2, "vreset!");
            return ((ClojureVolatile) args[0]).reset(args[1]);
        });

        globalVars.put("vswap!", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("vswap!: expected at least 2 args");
            ClojureVolatile vol = (ClojureVolatile) args[0];
            Object f = args[1];
            Object[] fArgs = new Object[args.length - 1];
            fArgs[0] = vol.deref();
            System.arraycopy(args, 2, fArgs, 1, args.length - 2);
            Object newVal = callFunction(f, fArgs);
            return vol.reset(newVal);
        });

        globalVars.put("volatile?", (BuiltinFunction) args -> {
            checkArity(args, 1, "volatile?");
            return args[0] instanceof ClojureVolatile;
        });

        // --- Phase 9: Promise/Deliver ---
        globalVars.put("promise", (BuiltinFunction) args -> new ClojurePromise());

        globalVars.put("deliver", (BuiltinFunction) args -> {
            checkArity(args, 2, "deliver");
            ClojurePromise p = (ClojurePromise) args[0];
            p.deliver(args[1]);
            return p;
        });

        globalVars.put("realized?", (BuiltinFunction) args -> {
            checkArity(args, 1, "realized?");
            if (args[0] instanceof ClojurePromise p) return p.isRealized();
            if (args[0] instanceof LazySeq ls) return ls.isRealized();
            return false;
        });

        // Update deref to handle promises and volatiles
        globalVars.put("deref", (BuiltinFunction) args -> {
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
            if (ref instanceof clojure.lang.IDeref d) return d.deref();
            throw new RuntimeException("deref: not a derefable: " + ref);
        });

        // --- Phase 9: Atom watchers & validators ---
        globalVars.put("add-watch", (BuiltinFunction) args -> {
            checkArity(args, 3, "add-watch");
            ClojureAtom atom = (ClojureAtom) args[0];
            atom.addWatch(args[1], args[2]);
            return atom;
        });

        globalVars.put("remove-watch", (BuiltinFunction) args -> {
            checkArity(args, 2, "remove-watch");
            ClojureAtom atom = (ClojureAtom) args[0];
            atom.removeWatch(args[1]);
            return atom;
        });

        globalVars.put("set-validator!", (BuiltinFunction) args -> {
            checkArity(args, 2, "set-validator!");
            ClojureAtom atom = (ClojureAtom) args[0];
            atom.setValidator(args[1]);
            return ClojureNil.INSTANCE;
        });

        globalVars.put("get-validator", (BuiltinFunction) args -> {
            checkArity(args, 1, "get-validator");
            ClojureAtom atom = (ClojureAtom) args[0];
            Object v = atom.getValidator();
            return v == null ? ClojureNil.INSTANCE : v;
        });

        // Override swap! to support watchers
        globalVars.put("swap!", (BuiltinFunction) args -> {
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
        globalVars.put("reset!", (BuiltinFunction) args -> {
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
        globalVars.put("transduce", (BuiltinFunction) args -> {
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
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(coll); seq != null; seq = seq.next()) {
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
        globalVars.put("map", (BuiltinFunction) args -> {
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
        globalVars.put("filter", (BuiltinFunction) args -> {
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
        globalVars.put("take", (BuiltinFunction) args -> {
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

        globalVars.put("reduced", (BuiltinFunction) args -> {
            checkArity(args, 1, "reduced");
            return new Reduced(args[0]);
        });

        globalVars.put("reduced?", (BuiltinFunction) args -> {
            checkArity(args, 1, "reduced?");
            return args[0] instanceof Reduced;
        });

        globalVars.put("unreduced", (BuiltinFunction) args -> {
            checkArity(args, 1, "unreduced");
            if (args[0] instanceof Reduced r) return r.value;
            return args[0];
        });

        // comp for function/transducer composition
        globalVars.put("comp", (BuiltinFunction) args -> {
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
        globalVars.put("partial", (BuiltinFunction) args -> {
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
        globalVars.put("make-hierarchy", (BuiltinFunction) args ->
                clojure.lang.PersistentArrayMap.EMPTY
                        .assoc(clojure.lang.Keyword.intern("parents"),
                                clojure.lang.PersistentArrayMap.EMPTY)
                        .assoc(clojure.lang.Keyword.intern("ancestors"),
                                clojure.lang.PersistentArrayMap.EMPTY)
                        .assoc(clojure.lang.Keyword.intern("descendants"),
                                clojure.lang.PersistentArrayMap.EMPTY));

        // Global hierarchy
        globalVars.put("*hierarchy*", ((BuiltinFunction) globalVars.get("make-hierarchy")).execute(new Object[0]));

        globalVars.put("derive", (BuiltinFunction) args -> {
            if (args.length != 2) throw new RuntimeException("derive: expected 2 args (tag parent)");
            Object tag = args[0];
            Object parent = args[1];
            // Modify global hierarchy
            Object hier = globalVars.get("*hierarchy*");
            clojure.lang.IPersistentMap h = (clojure.lang.IPersistentMap) hier;
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

            globalVars.put("*hierarchy*", h);
            return ClojureNil.INSTANCE;
        });

        globalVars.put("isa?", (BuiltinFunction) args -> {
            checkArity(args, 2, "isa?");
            Object child = args[0];
            Object parent = args[1];
            if (child.equals(parent)) return true;
            // Check hierarchy
            Object hier = globalVars.get("*hierarchy*");
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

        globalVars.put("parents", (BuiltinFunction) args -> {
            checkArity(args, 1, "parents");
            Object hier = globalVars.get("*hierarchy*");
            if (hier instanceof clojure.lang.IPersistentMap h) {
                Object p = ((clojure.lang.IPersistentMap) h.valAt(
                        clojure.lang.Keyword.intern("parents"))).valAt(args[0]);
                return p == null ? ClojureNil.INSTANCE : p;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("ancestors", (BuiltinFunction) args -> {
            checkArity(args, 1, "ancestors");
            Object hier = globalVars.get("*hierarchy*");
            if (hier instanceof clojure.lang.IPersistentMap h) {
                Object a = ((clojure.lang.IPersistentMap) h.valAt(
                        clojure.lang.Keyword.intern("ancestors"))).valAt(args[0]);
                return a == null ? ClojureNil.INSTANCE : a;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("descendants", (BuiltinFunction) args -> {
            checkArity(args, 1, "descendants");
            Object hier = globalVars.get("*hierarchy*");
            if (hier instanceof clojure.lang.IPersistentMap h) {
                Object d = ((clojure.lang.IPersistentMap) h.valAt(
                        clojure.lang.Keyword.intern("descendants"))).valAt(args[0]);
                return d == null ? ClojureNil.INSTANCE : d;
            }
            return ClojureNil.INSTANCE;
        });

        // --- Phase 9: Misc utilities ---
        globalVars.put("tree-seq", (BuiltinFunction) args -> {
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
                        for (clojure.lang.ISeq seq = clojure.lang.RT.seq(kids); seq != null; seq = seq.next()) {
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

        globalVars.put("iterate", (BuiltinFunction) args -> {
            checkArity(args, 2, "iterate");
            Object f = args[0];
            Object x = args[1];
            return new LazySeq(() -> lazyIterate(f, x));
        });

        globalVars.put("cycle", (BuiltinFunction) args -> {
            checkArity(args, 1, "cycle");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            if (items.isEmpty()) return ClojureNil.INSTANCE;
            return new LazySeq(() -> lazyCycle(items, 0));
        });

        globalVars.put("not=", (BuiltinFunction) args -> {
            checkArity(args, 2, "not=");
            return !clojure.lang.Util.equals(args[0] instanceof ClojureNil ? null : args[0],
                    args[1] instanceof ClojureNil ? null : args[1]);
        });

        globalVars.put("empty", (BuiltinFunction) args -> {
            checkArity(args, 1, "empty");
            Object coll = args[0];
            if (coll instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (coll instanceof clojure.lang.IPersistentVector) return clojure.lang.PersistentVector.EMPTY;
            if (coll instanceof clojure.lang.IPersistentMap) return clojure.lang.PersistentArrayMap.EMPTY;
            if (coll instanceof clojure.lang.IPersistentSet) return clojure.lang.PersistentHashSet.EMPTY;
            if (coll instanceof clojure.lang.IPersistentList) return clojure.lang.PersistentList.EMPTY;
            return clojure.lang.PersistentList.EMPTY;
        });

        globalVars.put("empty?", (BuiltinFunction) args -> {
            checkArity(args, 1, "empty?");
            Object coll = args[0];
            if (coll instanceof ClojureNil) return true;
            if (coll instanceof clojure.lang.Seqable s) return s.seq() == null;
            if (coll instanceof String str) return str.isEmpty();
            return false;
        });

        globalVars.put("not-empty", (BuiltinFunction) args -> {
            checkArity(args, 1, "not-empty");
            Object coll = args[0];
            if (coll instanceof ClojureNil) return ClojureNil.INSTANCE;
            if (coll instanceof clojure.lang.Seqable s) return s.seq() == null ? ClojureNil.INSTANCE : coll;
            return coll;
        });

        globalVars.put("bounded-count", (BuiltinFunction) args -> {
            checkArity(args, 2, "bounded-count");
            int n = ((Number) args[0]).intValue();
            if (args[1] instanceof clojure.lang.Counted c) return (long) Math.min(c.count(), n);
            long count = 0;
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null && count < n; seq = seq.next()) {
                count++;
            }
            return count;
        });

        globalVars.put("sequence", (BuiltinFunction) args -> {
            if (args.length == 1) {
                // (sequence coll) - coerce to seq
                clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]);
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
                for (clojure.lang.ISeq seq = clojure.lang.RT.seq(coll); seq != null; seq = seq.next()) {
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
        globalVars.put("vec", (BuiltinFunction) args -> {
            checkArity(args, 1, "vec");
            if (args[0] instanceof ClojureNil) return clojure.lang.PersistentVector.EMPTY;
            if (args[0] instanceof clojure.lang.IPersistentVector v) return v;
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            return clojure.lang.PersistentVector.create(items);
        });

        globalVars.put("second", (BuiltinFunction) args -> {
            checkArity(args, 1, "second");
            clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            seq = seq.next();
            if (seq == null) return ClojureNil.INSTANCE;
            return seq.first();
        });

        globalVars.put("last", (BuiltinFunction) args -> {
            checkArity(args, 1, "last");
            if (args[0] instanceof ClojureNil) return ClojureNil.INSTANCE;
            Object result = ClojureNil.INSTANCE;
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                result = seq.first();
            }
            return result;
        });

        globalVars.put("butlast", (BuiltinFunction) args -> {
            checkArity(args, 1, "butlast");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            if (items.isEmpty()) return ClojureNil.INSTANCE;
            items.remove(items.size() - 1);
            return items.isEmpty() ? ClojureNil.INSTANCE : clojure.lang.PersistentList.create(items);
        });

        globalVars.put("peek", (BuiltinFunction) args -> {
            checkArity(args, 1, "peek");
            if (args[0] instanceof clojure.lang.IPersistentStack s) {
                Object v = s.peek();
                return v == null ? ClojureNil.INSTANCE : v;
            }
            throw new RuntimeException("peek: not a stack");
        });

        globalVars.put("pop", (BuiltinFunction) args -> {
            checkArity(args, 1, "pop");
            if (args[0] instanceof clojure.lang.IPersistentStack s) return s.pop();
            throw new RuntimeException("pop: not a stack");
        });

        globalVars.put("subvec", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("subvec: expected 2-3 args");
            clojure.lang.IPersistentVector v = (clojure.lang.IPersistentVector) args[0];
            int start = ((Number) args[1]).intValue();
            int end = args.length > 2 ? ((Number) args[2]).intValue() : v.count();
            return clojure.lang.RT.subvec(v, start, end);
        });

        globalVars.put("nfirst", (BuiltinFunction) args -> {
            checkArity(args, 1, "nfirst");
            clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            return clojure.lang.RT.seq(seq.first());
        });

        globalVars.put("nnext", (BuiltinFunction) args -> {
            checkArity(args, 1, "nnext");
            clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            seq = seq.next();
            if (seq == null) return ClojureNil.INSTANCE;
            return seq.next() == null ? (Object) ClojureNil.INSTANCE : seq.next();
        });

        globalVars.put("ffirst", (BuiltinFunction) args -> {
            checkArity(args, 1, "ffirst");
            clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            clojure.lang.ISeq inner = clojure.lang.RT.seq(seq.first());
            if (inner == null) return ClojureNil.INSTANCE;
            return inner.first();
        });

        globalVars.put("fnext", (BuiltinFunction) args -> {
            checkArity(args, 1, "fnext");
            clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            seq = seq.next();
            if (seq == null) return ClojureNil.INSTANCE;
            return seq.first();
        });

        globalVars.put("next", (BuiltinFunction) args -> {
            checkArity(args, 1, "next");
            clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]);
            if (seq == null) return ClojureNil.INSTANCE;
            clojure.lang.ISeq n = seq.next();
            return n == null ? (Object) ClojureNil.INSTANCE : n;
        });

        // Ensure nth works on lists too
        globalVars.put("nth", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("nth: expected 2-3 args");
            Object notFound = args.length > 2 ? args[2] : null;
            int idx = ((Number) args[1]).intValue();
            Object coll = args[0];
            if (coll instanceof clojure.lang.Indexed indexed) {
                if (idx < 0 || idx >= indexed.count()) {
                    if (notFound != null) return notFound;
                    throw new IndexOutOfBoundsException("Index " + idx);
                }
                return indexed.nth(idx);
            }
            // Fall back to seq traversal for lists
            clojure.lang.ISeq seq = clojure.lang.RT.seq(coll);
            for (int i = 0; i < idx && seq != null; i++) seq = seq.next();
            if (seq == null) {
                if (notFound != null) return notFound;
                throw new IndexOutOfBoundsException("Index " + idx);
            }
            return seq.first();
        });

        // assoc on vectors
        Object origAssoc = globalVars.get("assoc");
        globalVars.put("assoc", (BuiltinFunction) args -> {
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
        globalVars.put("apply", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("apply: expected at least 2 args");
            Object fn = args[0];
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
                for (clojure.lang.ISeq seq = clojure.lang.RT.seq(lastArg); seq != null; seq = seq.next()) {
                    allArgs.add(seq.first());
                }
            }
            return callFunction(fn, allArgs.toArray(new Object[0]));
        });

        // sort (no-arg comparator)
        globalVars.put("sort", (BuiltinFunction) args -> {
            if (args.length < 1 || args.length > 2) throw new RuntimeException("sort: expected 1-2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(coll); seq != null; seq = seq.next()) {
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
        globalVars.put("reverse", (BuiltinFunction) args -> {
            checkArity(args, 1, "reverse");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            java.util.Collections.reverse(items);
            return clojure.lang.PersistentList.create(items);
        });

        // repeat (finite)
        globalVars.put("repeat", (BuiltinFunction) args -> {
            if (args.length == 1) {
                // Infinite repeat
                Object x = args[0];
                return new LazySeq(() -> lazyRepeat(-1, x));
            }
            checkArity(args, 2, "repeat");
            int n = ((Number) args[0]).intValue();
            Object x = args[1];
            java.util.List<Object> items = new ArrayList<>();
            for (int i = 0; i < n; i++) items.add(x);
            return clojure.lang.PersistentList.create(items);
        });

        // max, min
        globalVars.put("max", (BuiltinFunction) args -> {
            if (args.length == 0) throw new RuntimeException("max: expected at least 1 arg");
            Object best = args[0];
            for (int i = 1; i < args.length; i++) {
                if (((Comparable) args[i]).compareTo(best) > 0) best = args[i];
            }
            return best;
        });

        globalVars.put("min", (BuiltinFunction) args -> {
            if (args.length == 0) throw new RuntimeException("min: expected at least 1 arg");
            Object best = args[0];
            for (int i = 1; i < args.length; i++) {
                if (((Comparable) args[i]).compareTo(best) < 0) best = args[i];
            }
            return best;
        });

        // abs
        globalVars.put("abs", (BuiltinFunction) args -> {
            checkArity(args, 1, "abs");
            if (args[0] instanceof Long l) return Math.abs(l);
            if (args[0] instanceof Double d) return Math.abs(d);
            return Math.abs(((Number) args[0]).doubleValue());
        });

        // range improvements (0-arity and 3-arity)
        Object origRange = globalVars.get("range");
        globalVars.put("range", (BuiltinFunction) args -> {
            if (args.length == 0) {
                // Infinite range
                return new LazySeq(() -> lazyRange(0, Long.MAX_VALUE, 1));
            }
            return ((BuiltinFunction) origRange).execute(args);
        });

        // mapcat
        globalVars.put("mapcat", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("mapcat: expected at least 2 args");
            Object f = args[0];
            // For simplicity, handle single collection case
            java.util.List<Object> result = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                Object mapped = callFunction(f, new Object[]{seq.first()});
                if (mapped == null || mapped instanceof ClojureNil) continue;
                clojure.lang.ISeq inner = null;
                try {
                    inner = clojure.lang.RT.seq(mapped);
                } catch (IllegalArgumentException e) {
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
        globalVars.put("keep-indexed", (BuiltinFunction) args -> {
            checkArity(args, 2, "keep-indexed");
            Object f = args[0];
            java.util.List<Object> result = new ArrayList<>();
            long idx = 0;
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                Object val = callFunction(f, new Object[]{idx++, seq.first()});
                if (val != null && !(val instanceof ClojureNil)) result.add(val);
            }
            return result.isEmpty() ? clojure.lang.PersistentList.EMPTY
                    : clojure.lang.PersistentList.create(result);
        });

        // some? (not nil?)
        globalVars.put("some?", (BuiltinFunction) args -> {
            checkArity(args, 1, "some?");
            return !(args[0] instanceof ClojureNil) && args[0] != null;
        });

        // true?, false?
        globalVars.put("true?", (BuiltinFunction) args -> {
            checkArity(args, 1, "true?");
            return Boolean.TRUE.equals(args[0]);
        });

        globalVars.put("false?", (BuiltinFunction) args -> {
            checkArity(args, 1, "false?");
            return Boolean.FALSE.equals(args[0]);
        });

        // zero?, pos?, neg? (may already exist but ensure)
        globalVars.put("zero?", (BuiltinFunction) args -> {
            checkArity(args, 1, "zero?");
            if (args[0] instanceof Long l) return l == 0L;
            if (args[0] instanceof Double d) return d == 0.0;
            return ((Number) args[0]).doubleValue() == 0.0;
        });

        globalVars.put("pos?", (BuiltinFunction) args -> {
            checkArity(args, 1, "pos?");
            if (args[0] instanceof Long l) return l > 0L;
            if (args[0] instanceof Double d) return d > 0.0;
            return ((Number) args[0]).doubleValue() > 0.0;
        });

        globalVars.put("neg?", (BuiltinFunction) args -> {
            checkArity(args, 1, "neg?");
            if (args[0] instanceof Long l) return l < 0L;
            if (args[0] instanceof Double d) return d < 0.0;
            return ((Number) args[0]).doubleValue() < 0.0;
        });

        globalVars.put("even?", (BuiltinFunction) args -> {
            checkArity(args, 1, "even?");
            return ((Number) args[0]).longValue() % 2 == 0;
        });

        globalVars.put("odd?", (BuiltinFunction) args -> {
            checkArity(args, 1, "odd?");
            return ((Number) args[0]).longValue() % 2 != 0;
        });

        // pos-int?, neg-int?, nat-int?
        globalVars.put("pos-int?", (BuiltinFunction) args -> {
            checkArity(args, 1, "pos-int?");
            return args[0] instanceof Long l && l > 0;
        });

        globalVars.put("neg-int?", (BuiltinFunction) args -> {
            checkArity(args, 1, "neg-int?");
            return args[0] instanceof Long l && l < 0;
        });

        globalVars.put("nat-int?", (BuiltinFunction) args -> {
            checkArity(args, 1, "nat-int?");
            return args[0] instanceof Long l && l >= 0;
        });

        // int?, double?, integer?
        globalVars.put("int?", (BuiltinFunction) args -> {
            checkArity(args, 1, "int?");
            return args[0] instanceof Long;
        });

        globalVars.put("double?", (BuiltinFunction) args -> {
            checkArity(args, 1, "double?");
            return args[0] instanceof Double;
        });

        globalVars.put("integer?", (BuiltinFunction) args -> {
            checkArity(args, 1, "integer?");
            return args[0] instanceof Long || args[0] instanceof Integer;
        });

        globalVars.put("float?", (BuiltinFunction) args -> {
            checkArity(args, 1, "float?");
            return args[0] instanceof Double || args[0] instanceof Float;
        });

        // associative?, counted?, indexed?
        globalVars.put("associative?", (BuiltinFunction) args -> {
            checkArity(args, 1, "associative?");
            return args[0] instanceof clojure.lang.Associative;
        });

        globalVars.put("counted?", (BuiltinFunction) args -> {
            checkArity(args, 1, "counted?");
            return args[0] instanceof clojure.lang.Counted;
        });

        globalVars.put("indexed?", (BuiltinFunction) args -> {
            checkArity(args, 1, "indexed?");
            return args[0] instanceof clojure.lang.Indexed;
        });

        globalVars.put("reversible?", (BuiltinFunction) args -> {
            checkArity(args, 1, "reversible?");
            return args[0] instanceof clojure.lang.Reversible;
        });

        globalVars.put("sorted?", (BuiltinFunction) args -> {
            checkArity(args, 1, "sorted?");
            return args[0] instanceof clojure.lang.Sorted;
        });

        // atom?
        globalVars.put("atom?", (BuiltinFunction) args -> {
            checkArity(args, 1, "atom?");
            return args[0] instanceof ClojureAtom;
        });

        // str improvements - handle nil as ""
        globalVars.put("str", (BuiltinFunction) args -> {
            if (args.length == 0) return "";
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (arg instanceof ClojureNil) continue;
                sb.append(printString(arg, false));
            }
            return sb.toString();
        });

        // Improved println-str, pr-str
        globalVars.put("pr-str", (BuiltinFunction) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            return sb.toString();
        });

        globalVars.put("println-str", (BuiltinFunction) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], false));
            }
            sb.append("\n");
            return sb.toString();
        });

        // --- Phase 11: read-string ---
        globalVars.put("read-string", (BuiltinFunction) args -> {
            checkArity(args, 1, "read-string");
            String s = args[0].toString();
            try {
                java.io.PushbackReader rdr = new java.io.PushbackReader(new java.io.StringReader(s));
                Object form = clojure.lang.LispReader.read(rdr, true, null, false, null);
                return form == null ? ClojureNil.INSTANCE : form;
            } catch (Exception e) {
                throw new RuntimeException("read-string: " + e.getMessage());
            }
        });

        // --- Phase 11: walk functions ---
        globalVars.put("walk", (BuiltinFunction) args -> {
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
                for (clojure.lang.ISeq seq = clojure.lang.RT.seq(form); seq != null; seq = seq.next()) {
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
        });

        globalVars.put("postwalk", (BuiltinFunction) args -> {
            checkArity(args, 2, "postwalk");
            return postwalk(args[0], args[1]);
        });

        globalVars.put("prewalk", (BuiltinFunction) args -> {
            checkArity(args, 2, "prewalk");
            return prewalk(args[0], args[1]);
        });

        globalVars.put("postwalk-replace", (BuiltinFunction) args -> {
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
        });

        globalVars.put("prewalk-replace", (BuiltinFunction) args -> {
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
        });

        // --- Phase 11: update-keys, update-vals (Clojure 1.11+) ---
        globalVars.put("update-keys", (BuiltinFunction) args -> {
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

        globalVars.put("update-vals", (BuiltinFunction) args -> {
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
        globalVars.put("map-entry", (BuiltinFunction) args -> {
            checkArity(args, 2, "map-entry");
            return clojure.lang.MapEntry.create(args[0], args[1]);
        });

        globalVars.put("key", (BuiltinFunction) args -> {
            checkArity(args, 1, "key");
            return ((clojure.lang.IMapEntry) args[0]).key();
        });

        globalVars.put("val", (BuiltinFunction) args -> {
            checkArity(args, 1, "val");
            return ((clojure.lang.IMapEntry) args[0]).val();
        });

        globalVars.put("find", (BuiltinFunction) args -> {
            checkArity(args, 2, "find");
            if (args[0] instanceof clojure.lang.IPersistentMap m) {
                clojure.lang.IMapEntry entry = m.entryAt(args[1]);
                return entry == null ? ClojureNil.INSTANCE : entry;
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("map-entry?", (BuiltinFunction) args -> {
            checkArity(args, 1, "map-entry?");
            return args[0] instanceof clojure.lang.IMapEntry;
        });

        globalVars.put("not", (BuiltinFunction) args -> {
            checkArity(args, 1, "not");
            return !isTruthy(args[0]);
        });

        globalVars.put("mod", (BuiltinFunction) args -> {
            checkArity(args, 2, "mod");
            if (args[0] instanceof Long a && args[1] instanceof Long b) {
                return Math.floorMod(a, b);
            }
            double a = ((Number) args[0]).doubleValue();
            double b = ((Number) args[1]).doubleValue();
            return a - b * Math.floor(a / b);
        });

        globalVars.put("rem", (BuiltinFunction) args -> {
            checkArity(args, 2, "rem");
            if (args[0] instanceof Long a && args[1] instanceof Long b) return a % b;
            return ((Number) args[0]).doubleValue() % ((Number) args[1]).doubleValue();
        });

        globalVars.put("quot", (BuiltinFunction) args -> {
            checkArity(args, 2, "quot");
            if (args[0] instanceof Long a && args[1] instanceof Long b) return a / b;
            return (long) (((Number) args[0]).doubleValue() / ((Number) args[1]).doubleValue());
        });

        globalVars.put("bit-and", (BuiltinFunction) args -> {
            checkArity(args, 2, "bit-and");
            return ((Number) args[0]).longValue() & ((Number) args[1]).longValue();
        });

        globalVars.put("bit-or", (BuiltinFunction) args -> {
            checkArity(args, 2, "bit-or");
            return ((Number) args[0]).longValue() | ((Number) args[1]).longValue();
        });

        globalVars.put("bit-xor", (BuiltinFunction) args -> {
            checkArity(args, 2, "bit-xor");
            return ((Number) args[0]).longValue() ^ ((Number) args[1]).longValue();
        });

        globalVars.put("bit-not", (BuiltinFunction) args -> {
            checkArity(args, 1, "bit-not");
            return ~((Number) args[0]).longValue();
        });

        globalVars.put("bit-shift-left", (BuiltinFunction) args -> {
            checkArity(args, 2, "bit-shift-left");
            return ((Number) args[0]).longValue() << ((Number) args[1]).intValue();
        });

        globalVars.put("bit-shift-right", (BuiltinFunction) args -> {
            checkArity(args, 2, "bit-shift-right");
            return ((Number) args[0]).longValue() >> ((Number) args[1]).intValue();
        });

        globalVars.put("unsigned-bit-shift-right", (BuiltinFunction) args -> {
            checkArity(args, 2, "unsigned-bit-shift-right");
            return ((Number) args[0]).longValue() >>> ((Number) args[1]).intValue();
        });

        globalVars.put("long", (BuiltinFunction) args -> {
            checkArity(args, 1, "long");
            if (args[0] instanceof Long l) return l;
            return ((Number) args[0]).longValue();
        });

        globalVars.put("double", (BuiltinFunction) args -> {
            checkArity(args, 1, "double");
            if (args[0] instanceof Double d) return d;
            return ((Number) args[0]).doubleValue();
        });

        globalVars.put("boolean", (BuiltinFunction) args -> {
            checkArity(args, 1, "boolean");
            return isTruthy(args[0]);
        });

        globalVars.put("bigint", (BuiltinFunction) args -> {
            checkArity(args, 1, "bigint");
            if (args[0] instanceof java.math.BigInteger bi) return bi;
            return java.math.BigInteger.valueOf(((Number) args[0]).longValue());
        });

        globalVars.put("bigdec", (BuiltinFunction) args -> {
            checkArity(args, 1, "bigdec");
            if (args[0] instanceof java.math.BigDecimal bd) return bd;
            return java.math.BigDecimal.valueOf(((Number) args[0]).doubleValue());
        });

        // with-redefs support via dynamic binding
        globalVars.put("alter-var-root", (BuiltinFunction) args -> {
            checkArity(args, 2, "alter-var-root");
            String varName = args[0].toString();
            Object f = args[1];
            Object oldVal = getVar(varName);
            Object newVal = callFunction(f, new Object[]{oldVal == null ? ClojureNil.INSTANCE : oldVal});
            setVar(varName, newVal);
            return newVal;
        });

        // --- Phase 12: delay/force ---
        globalVars.put("delay", (BuiltinFunction) args -> {
            // Note: in real Clojure, delay is a macro. Here we treat it as a builtin
            // that takes a thunk (fn of no args)
            checkArity(args, 1, "delay");
            Object thunk = args[0];
            return new Object() {
                private volatile Object value;
                private volatile boolean realized = false;
                public synchronized Object deref() {
                    if (!realized) {
                        value = callFunction(thunk, new Object[0]);
                        realized = true;
                    }
                    return value;
                }
                public boolean isRealized() { return realized; }
                @Override public String toString() {
                    return realized ? "#delay[" + value + "]" : "#delay[:pending]";
                }
            };
        });

        globalVars.put("force", (BuiltinFunction) args -> {
            checkArity(args, 1, "force");
            Object x = args[0];
            try {
                java.lang.reflect.Method m = x.getClass().getMethod("deref");
                return m.invoke(x);
            } catch (Exception e) {
                return x; // If not a delay, return as-is
            }
        });

        // --- Phase 12: Java array interop ---
        globalVars.put("make-array", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("make-array: expected at least 2 args");
            Class<?> clazz = (Class<?>) args[0];
            int size = ((Number) args[1]).intValue();
            return java.lang.reflect.Array.newInstance(clazz, size);
        });

        globalVars.put("object-array", (BuiltinFunction) args -> {
            checkArity(args, 1, "object-array");
            if (args[0] instanceof Number n) {
                return new Object[n.intValue()];
            }
            // Convert collection to array
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            return items.toArray();
        });

        globalVars.put("to-array", (BuiltinFunction) args -> {
            checkArity(args, 1, "to-array");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            return items.toArray();
        });

        globalVars.put("into-array", (BuiltinFunction) args -> {
            if (args.length == 1) {
                java.util.List<Object> items = new ArrayList<>();
                for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                    items.add(seq.first());
                }
                return items.toArray();
            }
            checkArity(args, 2, "into-array");
            Class<?> clazz = (Class<?>) args[0];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                items.add(seq.first());
            }
            Object arr = java.lang.reflect.Array.newInstance(clazz, items.size());
            for (int i = 0; i < items.size(); i++) {
                java.lang.reflect.Array.set(arr, i, items.get(i));
            }
            return arr;
        });

        globalVars.put("aset", (BuiltinFunction) args -> {
            checkArity(args, 3, "aset");
            int idx = ((Number) args[1]).intValue();
            java.lang.reflect.Array.set(args[0], idx, args[2]);
            return args[2];
        });

        globalVars.put("aget", (BuiltinFunction) args -> {
            checkArity(args, 2, "aget");
            int idx = ((Number) args[1]).intValue();
            Object val = java.lang.reflect.Array.get(args[0], idx);
            return val == null ? ClojureNil.INSTANCE : val;
        });

        globalVars.put("alength", (BuiltinFunction) args -> {
            checkArity(args, 1, "alength");
            return (long) java.lang.reflect.Array.getLength(args[0]);
        });

        globalVars.put("aclone", (BuiltinFunction) args -> {
            checkArity(args, 1, "aclone");
            int len = java.lang.reflect.Array.getLength(args[0]);
            Object newArr = java.lang.reflect.Array.newInstance(
                    args[0].getClass().getComponentType(), len);
            System.arraycopy(args[0], 0, newArr, 0, len);
            return newArr;
        });

        globalVars.put("array?", (BuiltinFunction) args -> {
            checkArity(args, 1, "array?");
            return args[0] != null && args[0].getClass().isArray();
        });

        // --- Phase 12: Protocol extension ---
        globalVars.put("extend-type", (BuiltinFunction) args -> {
            // (extend-type Type Protocol (method [args] body) ...)
            // This is handled by the analyzer as a special form
            throw new RuntimeException("extend-type should be handled by analyzer");
        });

        globalVars.put("satisfies?", (BuiltinFunction) args -> {
            checkArity(args, 2, "satisfies?");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureProtocol proto))
                throw new RuntimeException("satisfies?: first arg must be a protocol");
            Object obj = args[1];
            String typeKey;
            if (obj instanceof clojure.truffle.runtime.ClojureDeftypeInstance dti) {
                typeKey = dti.getTypeName();
            } else if (obj instanceof ClojureNil) {
                typeKey = "nil";
            } else {
                typeKey = obj.getClass().getName();
            }
            // Check if any method of the protocol is implemented for this type
            for (String methodName : proto.getMethodNames()) {
                if (proto.resolve(methodName, typeKey) != null) return true;
            }
            return false;
        });

        globalVars.put("prefer-method", (BuiltinFunction) args -> {
            checkArity(args, 3, "prefer-method");
            if (!(args[0] instanceof ClojureMultiMethod mm))
                throw new RuntimeException("prefer-method: first arg must be a multimethod");
            mm.preferMethod(args[1], args[2]);
            return mm;
        });

        globalVars.put("methods", (BuiltinFunction) args -> {
            checkArity(args, 1, "methods");
            if (!(args[0] instanceof ClojureMultiMethod mm))
                throw new RuntimeException("methods: first arg must be a multimethod");
            return mm.getMethodTable();
        });

        // --- Phase 12: Misc ---
        globalVars.put("dorun", (BuiltinFunction) args -> {
            if (args.length < 1) throw new RuntimeException("dorun: expected 1-2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(coll); seq != null; seq = seq.next()) {
                // force evaluation
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("doall", (BuiltinFunction) args -> {
            if (args.length < 1) throw new RuntimeException("doall: expected 1-2 args");
            Object coll = args.length == 1 ? args[0] : args[1];
            clojure.lang.ISeq seq = clojure.lang.RT.seq(coll);
            if (seq == null) return clojure.lang.PersistentList.EMPTY;
            // Force full realization
            java.util.List<Object> items = new ArrayList<>();
            for (; seq != null; seq = seq.next()) items.add(seq.first());
            return clojure.lang.PersistentList.create(items);
        });

        globalVars.put("line-seq", (BuiltinFunction) args -> {
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

        globalVars.put("with-out-str", (BuiltinFunction) args -> {
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

        globalVars.put("time", (BuiltinFunction) args -> {
            // time as a function taking a thunk
            checkArity(args, 1, "time");
            long start = System.nanoTime();
            Object result = callFunction(args[0], new Object[0]);
            long elapsed = System.nanoTime() - start;
            PrintStream out = new PrintStream(env.out());
            out.println("\"Elapsed time: " + (elapsed / 1000000.0) + " msecs\"");
            return result;
        });

        globalVars.put("rand", (BuiltinFunction) args -> {
            if (args.length == 0) return Math.random();
            return Math.random() * ((Number) args[0]).doubleValue();
        });

        // compare-and-set!
        globalVars.put("compare-and-set!", (BuiltinFunction) args -> {
            checkArity(args, 3, "compare-and-set!");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAtom atom))
                throw new RuntimeException("compare-and-set!: first arg must be an atom");
            return atom.compareAndSet(args[1], args[2]);
        });

        // every-pred
        globalVars.put("every-pred", (BuiltinFunction) args -> {
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
        globalVars.put("some-fn", (BuiltinFunction) args -> {
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
        globalVars.put("dedupe", (BuiltinFunction) args -> {
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
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next()) {
                Object item = seq.first();
                if (!item.equals(prev)) {
                    result.add(item);
                    prev = item;
                }
            }
            return clojure.lang.PersistentList.create(result);
        });

        // sorted-set
        globalVars.put("sorted-set", (BuiltinFunction) args -> {
            clojure.lang.PersistentTreeSet s = clojure.lang.PersistentTreeSet.EMPTY;
            for (Object arg : args) {
                s = (clojure.lang.PersistentTreeSet) s.cons(arg);
            }
            return s;
        });

        // prn-str
        globalVars.put("prn-str", (BuiltinFunction) args -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            sb.append("\n");
            return sb.toString();
        });

        // rename-keys
        globalVars.put("rename-keys", (BuiltinFunction) args -> {
            checkArity(args, 2, "rename-keys");
            if (!(args[0] instanceof clojure.lang.IPersistentMap m))
                throw new RuntimeException("rename-keys: first arg must be a map");
            if (!(args[1] instanceof clojure.lang.IPersistentMap kmap))
                throw new RuntimeException("rename-keys: second arg must be a map");
            clojure.lang.IPersistentMap result = m;
            for (clojure.lang.ISeq seq = kmap.seq(); seq != null; seq = seq.next()) {
                clojure.lang.MapEntry entry = (clojure.lang.MapEntry) seq.first();
                Object oldKey = entry.key(), newKey = entry.val();
                if (m.containsKey(oldKey)) {
                    Object val = m.valAt(oldKey);
                    result = result.without(oldKey);
                    result = result.assoc(newKey, val);
                }
            }
            return result;
        });

        // completing
        globalVars.put("completing", (BuiltinFunction) args -> {
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
        globalVars.put("eduction", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("eduction: expected xform and coll");
            Object xform = args[0];
            Object coll = args[args.length - 1];
            // Compose multiple xforms if more than 2 args
            if (args.length > 2) {
                for (int i = args.length - 2; i >= 1; i--) {
                    final Object outerXf = args[i];
                    final Object innerXf = xform;
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
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(coll); seq != null; seq = seq.next()) {
                acc = callFunction(xrf, new Object[]{acc, seq.first()});
                if (acc instanceof Reduced r) { acc = r.value; break; }
            }
            return clojure.lang.PersistentList.create(result);
        });

        // rand-int
        globalVars.put("rand-int", (BuiltinFunction) args -> {
            checkArity(args, 1, "rand-int");
            long n = ((Number) args[0]).longValue();
            return (long) (Math.random() * n);
        });

        // rand-nth
        globalVars.put("rand-nth", (BuiltinFunction) args -> {
            checkArity(args, 1, "rand-nth");
            if (args[0] instanceof clojure.lang.IPersistentVector v) {
                return v.nth((int) (Math.random() * v.count()));
            }
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next())
                items.add(seq.first());
            return items.get((int) (Math.random() * items.size()));
        });

        // shuffle
        globalVars.put("shuffle", (BuiltinFunction) args -> {
            checkArity(args, 1, "shuffle");
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[0]); seq != null; seq = seq.next())
                items.add(seq.first());
            java.util.Collections.shuffle(items);
            return clojure.lang.PersistentVector.create(items);
        });

        // not-any?
        globalVars.put("not-any?", (BuiltinFunction) args -> {
            checkArity(args, 2, "not-any?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                if (isTruthy(callFunction(pred, new Object[]{seq.first()}))) return false;
            }
            return true;
        });

        // not-every?
        globalVars.put("not-every?", (BuiltinFunction) args -> {
            checkArity(args, 2, "not-every?");
            Object pred = args[0];
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next()) {
                if (!isTruthy(callFunction(pred, new Object[]{seq.first()}))) return true;
            }
            return false;
        });

        // --- Phase 15 builtins ---

        // Agents
        globalVars.put("agent", (BuiltinFunction) args -> {
            checkArity(args, 1, "agent");
            return new clojure.truffle.runtime.ClojureAgent(args[0]);
        });

        globalVars.put("send", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("send: expected at least 2 args");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAgent ag))
                throw new RuntimeException("send: first arg must be an agent");
            Object fn = args[1];
            Object[] extraArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, extraArgs, 0, extraArgs.length);
            ag.send(fn, extraArgs, this);
            return ag;
        });

        globalVars.put("send-off", (BuiltinFunction) args -> {
            // Same as send for our simplified implementation
            if (args.length < 2) throw new RuntimeException("send-off: expected at least 2 args");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAgent ag))
                throw new RuntimeException("send-off: first arg must be an agent");
            Object fn = args[1];
            Object[] extraArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, extraArgs, 0, extraArgs.length);
            ag.send(fn, extraArgs, this);
            return ag;
        });

        globalVars.put("await", (BuiltinFunction) args -> {
            // Simple implementation: sleep briefly to let agent actions complete
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ClojureNil.INSTANCE;
        });

        globalVars.put("agent-error", (BuiltinFunction) args -> {
            checkArity(args, 1, "agent-error");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAgent ag))
                throw new RuntimeException("agent-error: first arg must be an agent");
            Throwable err = ag.getError();
            return err != null ? err : ClojureNil.INSTANCE;
        });

        globalVars.put("restart-agent", (BuiltinFunction) args -> {
            checkArity(args, 2, "restart-agent");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureAgent ag))
                throw new RuntimeException("restart-agent: first arg must be an agent");
            ag.restart(args[1]);
            return ag;
        });

        globalVars.put("agent?", (BuiltinFunction) args -> {
            checkArity(args, 1, "agent?");
            return args[0] instanceof clojure.truffle.runtime.ClojureAgent;
        });

        // Refs (simplified, no real STM)
        globalVars.put("ref", (BuiltinFunction) args -> {
            checkArity(args, 1, "ref");
            return new clojure.truffle.runtime.ClojureRef(args[0]);
        });

        globalVars.put("ref-set", (BuiltinFunction) args -> {
            checkArity(args, 2, "ref-set");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureRef r))
                throw new RuntimeException("ref-set: first arg must be a ref");
            return r.refSet(args[1]);
        });

        globalVars.put("alter", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("alter: expected at least 2 args");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureRef r))
                throw new RuntimeException("alter: first arg must be a ref");
            Object fn = args[1];
            Object[] moreArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, moreArgs, 0, moreArgs.length);
            return r.alter(fn, moreArgs, this);
        });

        globalVars.put("commute", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("commute: expected at least 2 args");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureRef r))
                throw new RuntimeException("commute: first arg must be a ref");
            Object fn = args[1];
            Object[] moreArgs = new Object[args.length - 2];
            System.arraycopy(args, 2, moreArgs, 0, moreArgs.length);
            return r.commute(fn, moreArgs, this);
        });

        globalVars.put("ref?", (BuiltinFunction) args -> {
            checkArity(args, 1, "ref?");
            return args[0] instanceof clojure.truffle.runtime.ClojureRef;
        });

        // --- Phase 14 builtins ---

        // identical?
        globalVars.put("identical?", (BuiltinFunction) args -> {
            checkArity(args, 2, "identical?");
            return args[0] == args[1];
        });

        // transient collections
        globalVars.put("transient", (BuiltinFunction) args -> {
            checkArity(args, 1, "transient");
            if (args[0] instanceof clojure.lang.IEditableCollection ec)
                return ec.asTransient();
            throw new RuntimeException("transient: not supported for " + args[0].getClass().getName());
        });

        globalVars.put("persistent!", (BuiltinFunction) args -> {
            checkArity(args, 1, "persistent!");
            if (args[0] instanceof clojure.lang.ITransientCollection tc)
                return tc.persistent();
            throw new RuntimeException("persistent!: not a transient collection");
        });

        globalVars.put("conj!", (BuiltinFunction) args -> {
            checkArity(args, 2, "conj!");
            if (args[0] instanceof clojure.lang.ITransientCollection tc)
                return tc.conj(args[1]);
            throw new RuntimeException("conj!: not a transient collection");
        });

        globalVars.put("assoc!", (BuiltinFunction) args -> {
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

        globalVars.put("dissoc!", (BuiltinFunction) args -> {
            checkArity(args, 2, "dissoc!");
            if (args[0] instanceof clojure.lang.ITransientMap tm)
                return tm.without(args[1]);
            throw new RuntimeException("dissoc!: not a transient map");
        });

        globalVars.put("pop!", (BuiltinFunction) args -> {
            checkArity(args, 1, "pop!");
            if (args[0] instanceof clojure.lang.ITransientVector tv)
                return tv.pop();
            throw new RuntimeException("pop!: not a transient vector");
        });

        // remove-method
        globalVars.put("remove-method", (BuiltinFunction) args -> {
            checkArity(args, 2, "remove-method");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureMultiMethod mm))
                throw new RuntimeException("remove-method: first arg must be a multimethod");
            mm.removeMethod(args[1]);
            return mm;
        });

        globalVars.put("remove-all-methods", (BuiltinFunction) args -> {
            checkArity(args, 1, "remove-all-methods");
            if (!(args[0] instanceof clojure.truffle.runtime.ClojureMultiMethod mm))
                throw new RuntimeException("remove-all-methods: first arg must be a multimethod");
            mm.removeAllMethods();
            return mm;
        });

        // alter-meta!
        globalVars.put("alter-meta!", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("alter-meta!: expected at least 2 args");
            // For atoms and other reference types
            if (args[0] instanceof clojure.truffle.runtime.ClojureAtom atom) {
                Object f = args[1];
                Object[] fArgs = new Object[args.length - 1];
                fArgs[0] = atom.getMeta();
                System.arraycopy(args, 2, fArgs, 1, args.length - 2);
                Object newMeta = callFunction(f, fArgs);
                atom.setMeta(newMeta);
                return newMeta;
            }
            throw new RuntimeException("alter-meta!: unsupported reference type");
        });

        // pmap
        globalVars.put("pmap", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("pmap: expected at least 2 args");
            Object f = args[0];
            java.util.List<Object> items = new ArrayList<>();
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(args[1]); seq != null; seq = seq.next())
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
        globalVars.put("bean", (BuiltinFunction) args -> {
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
        globalVars.put("bases", (BuiltinFunction) args -> {
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
        globalVars.put("future?", (BuiltinFunction) args -> {
            checkArity(args, 1, "future?");
            return args[0] instanceof java.util.concurrent.Future;
        });

        // realized? enhancement for futures
        // (already exists, but ensure it handles futures)

        // bit operations
        globalVars.put("unsigned-bit-shift-right", (BuiltinFunction) args -> {
            checkArity(args, 2, "unsigned-bit-shift-right");
            return ((Number) args[0]).longValue() >>> ((Number) args[1]).longValue();
        });

        // --- Phase 16: Clojure conformance ---

        // Internal helper for assert
        globalVars.put("new-assertion-error", (BuiltinFunction) args -> {
            checkArity(args, 1, "new-assertion-error");
            return new AssertionError(args[0]);
        });

        // remove - filter complement
        globalVars.put("remove", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("remove: expected 2 args");
            Object pred = args[0];
            Object coll = args[1];
            java.util.List<Object> result = new java.util.ArrayList<>();
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(coll); s != null; s = s.next()) {
                Object item = s.first();
                Object test = callFunction(pred, new Object[]{item});
                if (!clojure.lang.RT.booleanCast(test)) {
                    result.add(item);
                }
            }
            return result.isEmpty() ? clojure.lang.PersistentList.EMPTY
                    : clojure.lang.PersistentList.create(result);
        });

        // rseq - reverse of sorted/vector collections
        globalVars.put("rseq", (BuiltinFunction) args -> {
            checkArity(args, 1, "rseq");
            if (args[0] instanceof clojure.lang.Reversible r) {
                clojure.lang.ISeq result = r.rseq();
                return result == null ? ClojureNil.INSTANCE : result;
            }
            throw new RuntimeException("rseq: not reversible: " + args[0]);
        });

        // array-map - creates insertion-order-preserving map
        globalVars.put("array-map", (BuiltinFunction) args -> {
            if (args.length % 2 != 0) throw new RuntimeException("array-map: expects even number of args");
            return clojure.lang.PersistentArrayMap.createAsIfByAssoc(args);
        });

        // sorted-map-by
        globalVars.put("sorted-map-by", (BuiltinFunction) args -> {
            if (args.length < 1) throw new RuntimeException("sorted-map-by: requires comparator");
            Object comp = args[0];
            java.util.Comparator<Object> comparator = (a, b) -> {
                Object result = callFunction(comp, new Object[]{a, b});
                return ((Number) result).intValue();
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
        globalVars.put("macroexpand-1", (BuiltinFunction) args -> {
            checkArity(args, 1, "macroexpand-1");
            Object form = args[0];
            if (form instanceof clojure.lang.ISeq seq && seq.first() instanceof clojure.lang.Symbol sym) {
                Object macro = getMacro(sym.getName());
                if (macro != null) {
                    java.util.List<Object> macroArgs = new java.util.ArrayList<>();
                    for (clojure.lang.ISeq s = seq.next(); s != null; s = s.next()) {
                        macroArgs.add(s.first());
                    }
                    return callFunction(macro, macroArgs.toArray());
                }
            }
            return form; // not a macro call, return as-is
        });

        // var? - check if object is a Var
        globalVars.put("var?", (BuiltinFunction) args -> {
            checkArity(args, 1, "var?");
            return args[0] instanceof clojure.truffle.runtime.ClojureVar;
        });

        // bound? - check if var is bound
        globalVars.put("bound?", (BuiltinFunction) args -> {
            checkArity(args, 1, "bound?");
            if (args[0] instanceof clojure.truffle.runtime.ClojureVar v) {
                return v.isBound();
            }
            return false;
        });

        // time* - internal helper for (time expr)
        globalVars.put("time*", (BuiltinFunction) args -> {
            checkArity(args, 1, "time*");
            long start = System.nanoTime();
            Object result = callFunction(args[0], new Object[]{});
            long elapsed = System.nanoTime() - start;
            double ms = elapsed / 1_000_000.0;
            writeOut(String.format("\"Elapsed time: %.6f msecs\"\n", ms));
            return result;
        });

        // with-in-str* - internal helper
        globalVars.put("with-in-str*", (BuiltinFunction) args -> {
            checkArity(args, 2, "with-in-str*");
            // For now just execute the thunk - full *in* binding requires reader refactoring
            return callFunction(args[1], new Object[]{});
        });

        // not= - complement of =
        globalVars.put("not=", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("not=: expected at least 2 args");
            return !clojure.lang.Util.equiv(args[0], args[1]);
        });

        // == (numeric equality)
        globalVars.put("==", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("==: expected at least 2 args");
            for (int i = 1; i < args.length; i++) {
                if (((Number)args[0]).doubleValue() != ((Number)args[i]).doubleValue()) return false;
            }
            return true;
        });

        // supers - returns set of supertypes
        globalVars.put("supers", (BuiltinFunction) args -> {
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
        globalVars.put("class?", (BuiltinFunction) args -> {
            checkArity(args, 1, "class?");
            return args[0] instanceof Class;
        });

        // cast
        globalVars.put("cast", (BuiltinFunction) args -> {
            checkArity(args, 2, "cast");
            Class<?> clazz = (Class<?>) args[0];
            if (args[1] == ClojureNil.INSTANCE || args[1] == null) return ClojureNil.INSTANCE;
            if (!clazz.isInstance(args[1])) {
                throw new ClassCastException("Cannot cast " + args[1].getClass().getName() + " to " + clazz.getName());
            }
            return args[1];
        });

        // num / long / double / int / short / byte / float / char coercion
        globalVars.put("num", (BuiltinFunction) args -> { checkArity(args, 1, "num"); return args[0]; });
        globalVars.put("long", (BuiltinFunction) args -> { checkArity(args, 1, "long"); return ((Number) args[0]).longValue(); });
        globalVars.put("double", (BuiltinFunction) args -> { checkArity(args, 1, "double"); return ((Number) args[0]).doubleValue(); });
        globalVars.put("int", (BuiltinFunction) args -> { checkArity(args, 1, "int"); return (long)((Number) args[0]).intValue(); });
        globalVars.put("short", (BuiltinFunction) args -> { checkArity(args, 1, "short"); return (long)((Number) args[0]).shortValue(); });
        globalVars.put("byte", (BuiltinFunction) args -> { checkArity(args, 1, "byte"); return (long)((Number) args[0]).byteValue(); });
        globalVars.put("float", (BuiltinFunction) args -> { checkArity(args, 1, "float"); return (double)((Number) args[0]).floatValue(); });
        globalVars.put("char", (BuiltinFunction) args -> {
            checkArity(args, 1, "char");
            if (args[0] instanceof Character) return args[0];
            if (args[0] instanceof Number n) return (char) n.intValue();
            throw new RuntimeException("char: cannot coerce " + args[0]);
        });
        globalVars.put("boolean", (BuiltinFunction) args -> {
            checkArity(args, 1, "boolean");
            if (args[0] == null || args[0] == ClojureNil.INSTANCE) return false;
            if (args[0] instanceof Boolean b) return b;
            return true;
        });

        // realized? - check if delay/lazy-seq/future/promise is realized
        globalVars.put("realized?", (BuiltinFunction) args -> {
            checkArity(args, 1, "realized?");
            if (args[0] instanceof clojure.lang.IPending p) return p.isRealized();
            if (args[0] instanceof java.util.concurrent.Future<?> f) return f.isDone();
            // Check for our anonymous delay objects that have isRealized()
            try {
                java.lang.reflect.Method m = args[0].getClass().getMethod("isRealized");
                return (boolean) m.invoke(args[0]);
            } catch (Exception ignored) {}
            return true; // regular values are always "realized"
        });

        // flatten
        globalVars.put("flatten", (BuiltinFunction) args -> {
            checkArity(args, 1, "flatten");
            java.util.List<Object> result = new java.util.ArrayList<>();
            flattenHelper(args[0], result);
            return clojure.lang.PersistentList.create(result);
        });

        // group-by
        globalVars.put("group-by", (BuiltinFunction) args -> {
            checkArity(args, 2, "group-by");
            Object f = args[0];
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(args[1]); s != null; s = s.next()) {
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
        globalVars.put("frequencies", (BuiltinFunction) args -> {
            checkArity(args, 1, "frequencies");
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(args[0]); s != null; s = s.next()) {
                Object item = s.first();
                Object count = result.valAt(item);
                long n = (count == null) ? 0L : ((Number) count).longValue();
                result = result.assoc(item, n + 1);
            }
            return result;
        });

        // partition-by
        globalVars.put("partition-by", (BuiltinFunction) args -> {
            checkArity(args, 2, "partition-by");
            Object f = args[0];
            java.util.List<Object> result = new java.util.ArrayList<>();
            java.util.List<Object> current = new java.util.ArrayList<>();
            Object lastKey = new Object(); // sentinel
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(args[1]); s != null; s = s.next()) {
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
        globalVars.put("map-indexed", (BuiltinFunction) args -> {
            checkArity(args, 2, "map-indexed");
            Object f = args[0];
            java.util.List<Object> result = new java.util.ArrayList<>();
            long idx = 0;
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(args[1]); s != null; s = s.next()) {
                result.add(callFunction(f, new Object[]{idx, s.first()}));
                idx++;
            }
            return clojure.lang.PersistentList.create(result);
        });

        // juxt
        globalVars.put("juxt", (BuiltinFunction) args -> {
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
        globalVars.put("fnil", (BuiltinFunction) args -> {
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
        globalVars.put("update-in", (BuiltinFunction) args -> {
            if (args.length < 3) throw new RuntimeException("update-in: expected at least 3 args");
            Object m = args[0];
            clojure.lang.IPersistentVector ks = (clojure.lang.IPersistentVector) args[1];
            Object f = args[2];
            Object[] extraArgs = new Object[args.length - 3];
            System.arraycopy(args, 3, extraArgs, 0, extraArgs.length);
            return updateIn(m, ks, 0, f, extraArgs);
        });

        // assoc-in
        globalVars.put("assoc-in", (BuiltinFunction) args -> {
            checkArity(args, 3, "assoc-in");
            Object m = args[0];
            clojure.lang.IPersistentVector ks = (clojure.lang.IPersistentVector) args[1];
            Object v = args[2];
            return assocIn(m, ks, 0, v);
        });

        // get-in
        globalVars.put("get-in", (BuiltinFunction) args -> {
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
        globalVars.put("select-keys", (BuiltinFunction) args -> {
            checkArity(args, 2, "select-keys");
            clojure.lang.IPersistentMap m = (clojure.lang.IPersistentMap) args[0];
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(args[1]); s != null; s = s.next()) {
                Object key = s.first();
                clojure.lang.IMapEntry entry = m.entryAt(key);
                if (entry != null) {
                    result = result.assoc(key, entry.val());
                }
            }
            return result;
        });

        // zipmap
        globalVars.put("zipmap", (BuiltinFunction) args -> {
            checkArity(args, 2, "zipmap");
            clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
            clojure.lang.ISeq ks = clojure.lang.RT.seq(args[0]);
            clojure.lang.ISeq vs = clojure.lang.RT.seq(args[1]);
            while (ks != null && vs != null) {
                result = result.assoc(ks.first(), vs.first());
                ks = ks.next();
                vs = vs.next();
            }
            return result;
        });

        // sorted-set-by
        globalVars.put("sorted-set-by", (BuiltinFunction) args -> {
            if (args.length < 1) throw new RuntimeException("sorted-set-by: requires comparator");
            Object comp = args[0];
            java.util.Comparator<Object> comparator = (a, b) -> {
                Object result = callFunction(comp, new Object[]{a, b});
                return ((Number) result).intValue();
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

        // namespace functions
        globalVars.put("ns-name", (BuiltinFunction) args -> {
            checkArity(args, 1, "ns-name");
            if (args[0] instanceof String s) return clojure.lang.Symbol.intern(s);
            if (args[0] instanceof ClojureNamespace ns) return clojure.lang.Symbol.intern(ns.getName());
            return clojure.lang.Symbol.intern(args[0].toString());
        });

        globalVars.put("the-ns", (BuiltinFunction) args -> {
            checkArity(args, 1, "the-ns");
            String nsName;
            if (args[0] instanceof clojure.lang.Symbol sym) nsName = sym.getName();
            else nsName = args[0].toString();
            ClojureNamespace ns = getNamespace(nsName);
            if (ns == null) throw new RuntimeException("No namespace: " + nsName + " found");
            return ns;
        });

        globalVars.put("create-ns", (BuiltinFunction) args -> {
            checkArity(args, 1, "create-ns");
            String nsName;
            if (args[0] instanceof clojure.lang.Symbol sym) nsName = sym.getName();
            else nsName = args[0].toString();
            return getOrCreateNamespace(nsName);
        });

        globalVars.put("find-ns", (BuiltinFunction) args -> {
            checkArity(args, 1, "find-ns");
            String nsName;
            if (args[0] instanceof clojure.lang.Symbol sym) nsName = sym.getName();
            else nsName = args[0].toString();
            ClojureNamespace ns = getNamespace(nsName);
            return ns == null ? ClojureNil.INSTANCE : ns;
        });

        globalVars.put("all-ns", (BuiltinFunction) args -> {
            java.util.List<Object> result = new java.util.ArrayList<>(namespaces.values());
            return clojure.lang.PersistentList.create(result);
        });

        globalVars.put("ns-resolve", (BuiltinFunction) args -> {
            if (args.length < 2) throw new RuntimeException("ns-resolve: expected 2 args");
            String nsName;
            if (args[0] instanceof clojure.lang.Symbol sym) nsName = sym.getName();
            else if (args[0] instanceof ClojureNamespace ns) nsName = ns.getName();
            else nsName = args[0].toString();
            String symName;
            if (args[1] instanceof clojure.lang.Symbol sym) symName = sym.getName();
            else symName = args[1].toString();
            ClojureNamespace ns = getNamespace(nsName);
            if (ns == null) return ClojureNil.INSTANCE;
            Object val = ns.resolve(symName);
            return val == null ? ClojureNil.INSTANCE : new clojure.truffle.runtime.ClojureVar(this, nsName, symName);
        });

        globalVars.put("resolve", (BuiltinFunction) args -> {
            checkArity(args, 1, "resolve");
            String symName;
            if (args[0] instanceof clojure.lang.Symbol sym) symName = sym.getName();
            else symName = args[0].toString();
            Object val = getVarWithBindings(symName);
            if (val == null) return ClojureNil.INSTANCE;
            return new clojure.truffle.runtime.ClojureVar(this, currentNamespace, symName);
        });

        // Copy all builtins into clojure.core namespace
        ClojureNamespace core = namespaces.get("clojure.core");
        if (core != null) {
            globalVars.forEach(core::intern);
        }
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

    public Object callFunction(Object fn, Object[] args) {
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
                throw new RuntimeException("Keyword lookup expects 1 or 2 args");
            Object map = args[0];
            if (map instanceof clojure.lang.ILookup lookup) {
                Object notFound = args.length == 2 ? args[1] : ClojureNil.INSTANCE;
                Object val = lookup.valAt(kw, notFound);
                return val == null ? ClojureNil.INSTANCE : val;
            }
            return args.length == 2 ? args[1] : ClojureNil.INSTANCE;
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
        if (coll instanceof ClojureNil) return null;
        if (coll instanceof LazySeq ls) return ls.seq();
        if (coll instanceof clojure.lang.ISeq seq) return seq;
        if (coll instanceof clojure.lang.Seqable s) return s.seq();
        throw new RuntimeException("Not seqable: " + coll);
    }

    // --- Lazy helpers ---

    private Object lazyMap(Object fn, Object coll) {
        return new LazySeq(() -> {
            clojure.lang.ISeq s;
            if (coll instanceof ClojureNil) return null;
            if (coll instanceof clojure.lang.ISeq is) s = is;
            else if (coll instanceof clojure.lang.Seqable sq) s = sq.seq();
            else return null;
            if (s == null) return null;
            Object first = callFunction(fn, new Object[]{s.first()});
            clojure.lang.ISeq rest = s.next();
            LazySeq lazyRest = (LazySeq) lazyMap(fn, rest == null ? (Object) ClojureNil.INSTANCE : rest);
            return (Object) new clojure.lang.Cons(first, lazyRest);
        });
    }

    private Object lazyFilter(Object fn, Object coll) {
        return new LazySeq(() -> {
            clojure.lang.ISeq s;
            if (coll instanceof ClojureNil) return null;
            if (coll instanceof clojure.lang.ISeq is) s = is;
            else if (coll instanceof clojure.lang.Seqable sq) s = sq.seq();
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
        if (db == 0.0) throw new ArithmeticException("Divide by zero");
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

    private static double toDouble(Object o) {
        if (o instanceof Long l) return l.doubleValue();
        if (o instanceof Double d) return d;
        if (o instanceof Number n) return n.doubleValue();
        throw new RuntimeException("Not a number: " + o);
    }

    // --- Comparison helpers ---

    @SuppressWarnings("unchecked")
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
        if (coll instanceof ClojureNil) return ClojureNil.INSTANCE;
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
        throw new RuntimeException("first: not a sequence: " + coll);
    }

    private static Object clojureRest(Object coll) {
        if (coll instanceof ClojureNil) return clojure.lang.PersistentList.EMPTY;
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
        throw new RuntimeException("rest: not a sequence: " + coll);
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
        for (int i = 1; i < args.length; i++) {
            if (coll instanceof clojure.lang.IPersistentCollection pc) {
                coll = pc.cons(args[i]);
            } else {
                throw new RuntimeException("conj: not a collection: " + coll);
            }
        }
        return coll;
    }

    private void registerStringNamespace() {
        ClojureNamespace ns = getOrCreateNamespace("clojure.string");
        // Map str/xxx builtins to clojure.string/xxx
        String[] fns = {"split", "join", "trim", "triml", "trimr", "upper-case", "lower-case",
                "replace", "replace-first", "starts-with?", "ends-with?", "includes?",
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
                for (clojure.lang.ISeq seq = clojure.lang.RT.seq(arg); seq != null; seq = seq.next()) {
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
    }

    private void registerWalkNamespace() {
        ClojureNamespace ns = getOrCreateNamespace("clojure.walk");
        String[] fns = {"walk", "postwalk", "prewalk", "postwalk-replace", "prewalk-replace"};
        for (String fn : fns) {
            Object val = globalVars.get(fn);
            if (val != null) ns.intern(fn, val);
        }
    }

    private void registerEdnNamespace() {
        ClojureNamespace ns = getOrCreateNamespace("clojure.edn");
        Object readStr = globalVars.get("read-string");
        if (readStr != null) ns.intern("read-string", readStr);
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
            if (args.length == 1) return new java.io.File(args[0].toString());
            if (args.length == 2) return new java.io.File(args[0].toString(), args[1].toString());
            throw new RuntimeException("file: expected 1 or 2 args");
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
    public static class Reduced {
        public final Object value;
        public Reduced(Object value) { this.value = value; }
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
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(form); seq != null; seq = seq.next()) {
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
            for (clojure.lang.ISeq seq = clojure.lang.RT.seq(prewalked); seq != null; seq = seq.next()) {
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

    private static void checkArity(Object[] args, int expected, String name) {
        if (args.length != expected)
            throw new RuntimeException(name + ": expected " + expected + " args, got " + args.length);
    }

    private void flattenHelper(Object coll, java.util.List<Object> result) {
        if (coll == null || coll == ClojureNil.INSTANCE) return;
        if (coll instanceof clojure.lang.Seqable) {
            for (clojure.lang.ISeq s = clojure.lang.RT.seq(coll); s != null; s = s.next()) {
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
