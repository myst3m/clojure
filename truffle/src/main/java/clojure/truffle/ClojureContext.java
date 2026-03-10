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
import clojure.truffle.runtime.LazySeq;
import clojure.truffle.runtime.MultiArityFunction;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

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

    public void loadNamespace(String nsName) {
        if (namespaces.containsKey(nsName)) return; // already loaded
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
            PrintStream out = new PrintStream(env.out());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], false));
            }
            out.print(sb.toString());
            out.flush();
            return ClojureNil.INSTANCE;
        });

        globalVars.put("pr", (BuiltinFunction) args -> {
            PrintStream out = new PrintStream(env.out());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            out.print(sb.toString());
            out.flush();
            return ClojureNil.INSTANCE;
        });

        globalVars.put("println", (BuiltinFunction) args -> {
            PrintStream out = new PrintStream(env.out());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], false));
            }
            out.println(sb.toString());
            return ClojureNil.INSTANCE;
        });

        globalVars.put("prn", (BuiltinFunction) args -> {
            PrintStream out = new PrintStream(env.out());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(" ");
                sb.append(printString(args[i], true));
            }
            out.println(sb.toString());
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
            java.util.concurrent.Future<Object> future = java.util.concurrent.Executors
                    .newSingleThreadExecutor().submit(() -> callFunction(fn, new Object[0]));
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

        // Copy all builtins into clojure.core namespace
        ClojureNamespace core = namespaces.get("clojure.core");
        if (core != null) {
            globalVars.forEach(core::intern);
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
        if (coll instanceof ClojureNil) {
            return clojure.lang.PersistentList.create(java.util.List.of(elem));
        }
        if (coll instanceof clojure.lang.Seqable s) {
            clojure.lang.ISeq seq = s.seq();
            return new clojure.lang.Cons(elem, seq);
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

    private static void checkArity(Object[] args, int expected, String name) {
        if (args.length != expected)
            throw new RuntimeException(name + ": expected " + expected + " args, got " + args.length);
    }
}
