package clojure.truffle;

import com.oracle.truffle.api.TruffleLanguage;
import clojure.truffle.nodes.EvalRootNode;
import clojure.truffle.nodes.ExpressionNode;
import clojure.truffle.parser.Analyzer;
import clojure.truffle.runtime.ClojureAtom;
import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.ClojureNil;
import clojure.truffle.runtime.MultiArityFunction;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class ClojureContext {

    private final ClojureTruffleLanguage language;
    private final TruffleLanguage.Env env;
    private final ConcurrentHashMap<String, Object> globalVars = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> macros = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface BuiltinFunction {
        Object execute(Object[] args);
    }

    public ClojureContext(ClojureTruffleLanguage language, TruffleLanguage.Env env) {
        this.language = language;
        this.env = env;
        registerBuiltins();
    }

    public ClojureTruffleLanguage getLanguage() {
        return language;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    public void setVar(String name, Object value) {
        globalVars.put(name, value);
    }

    public Object getVar(String name) {
        return globalVars.get(name);
    }

    public void setMacro(String name, Object fn) {
        macros.put(name, fn);
    }

    public Object getMacro(String name) {
        return macros.get(name);
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
            // Single collection for now
            Object coll = args[1];
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            java.util.List<Object> items = new ArrayList<>();
            if (!(coll instanceof ClojureNil)) {
                for (clojure.lang.ISeq seq = seqOf(coll); seq != null; seq = seq.next()) {
                    items.add(callFunction(fn, new Object[]{seq.first()}));
                }
            }
            // Build list in reverse to maintain order
            for (int i = items.size() - 1; i >= 0; i--) {
                result = result.cons(items.get(i));
            }
            return result;
        });

        globalVars.put("filter", (BuiltinFunction) args -> {
            checkArity(args, 2, "filter");
            Object fn = args[0];
            Object coll = args[1];
            java.util.List<Object> items = new ArrayList<>();
            if (!(coll instanceof ClojureNil)) {
                for (clojure.lang.ISeq seq = seqOf(coll); seq != null; seq = seq.next()) {
                    Object item = seq.first();
                    Object result = callFunction(fn, new Object[]{item});
                    if (isTruthy(result)) items.add(item);
                }
            }
            clojure.lang.IPersistentCollection result = clojure.lang.PersistentList.EMPTY;
            for (int i = items.size() - 1; i >= 0; i--) {
                result = result.cons(items.get(i));
            }
            return result;
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
            switch (args.length) {
                case 0: throw new RuntimeException("range: infinite range not yet supported");
                case 1: start = 0; end = ((Number) args[0]).longValue(); step = 1; break;
                case 2: start = ((Number) args[0]).longValue(); end = ((Number) args[1]).longValue(); step = 1; break;
                case 3: start = ((Number) args[0]).longValue(); end = ((Number) args[1]).longValue(); step = ((Number) args[2]).longValue(); break;
                default: throw new RuntimeException("range: too many args");
            }
            java.util.List<Object> items = new ArrayList<>();
            if (step > 0) {
                for (long i = start; i < end; i += step) items.add(i);
            } else if (step < 0) {
                for (long i = start; i > end; i += step) items.add(i);
            }
            return clojure.lang.PersistentVector.create(items);
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
    }

    // --- Function calling helper ---

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
