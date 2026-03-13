package clojure.truffle.runtime;

import clojure.lang.*;

/**
 * Replacement for clojure.lang.RT static methods.
 * Avoids triggering RT's static initializer which loads clojure/core.clj.
 */
public final class ClojureRT {

    private ClojureRT() {}

    public static ISeq seq(Object coll) {
        if (coll == null || coll instanceof ClojureNil) return null;
        if (coll instanceof ASeq) return (ASeq) coll;
        if (coll instanceof LazySeq) return ((LazySeq) coll).seq();
        return seqFrom(coll);
    }

    private static ISeq seqFrom(Object coll) {
        if (coll instanceof Seqable) return ((Seqable) coll).seq();
        if (coll == null) return null;
        if (coll instanceof Iterable) return chunkIteratorSeq(((Iterable<?>) coll).iterator());
        if (coll.getClass().isArray()) {
            // ArraySeq.createFromObject is package-private, use reflection or manual conversion
            Object[] arr;
            if (coll instanceof Object[]) {
                arr = (Object[]) coll;
            } else {
                int len = java.lang.reflect.Array.getLength(coll);
                arr = new Object[len];
                for (int i = 0; i < len; i++) arr[i] = java.lang.reflect.Array.get(coll, i);
            }
            return PersistentList.create(java.util.Arrays.asList(arr)).seq();
        }
        if (coll instanceof CharSequence) return StringSeq.create((CharSequence) coll);
        if (coll instanceof java.util.Map) return seq(((java.util.Map<?,?>) coll).entrySet());
        throw new IllegalArgumentException("Don't know how to create ISeq from: " + coll.getClass().getName());
    }

    private static ISeq chunkIteratorSeq(java.util.Iterator<?> iter) {
        if (!iter.hasNext()) return null;
        return IteratorSeq.create(iter);
    }

    public static Object get(Object coll, Object key) {
        if (coll instanceof ILookup) return ((ILookup) coll).valAt(key);
        if (coll == null) return null;
        if (coll instanceof java.util.Map) return ((java.util.Map<?,?>) coll).get(key);
        if (coll instanceof IPersistentSet) return ((IPersistentSet) coll).get(key);
        if (coll instanceof ITransientSet) return ((ITransientSet) coll).get(key);
        return null;
    }

    public static Object get(Object coll, Object key, Object notFound) {
        if (coll instanceof ILookup) return ((ILookup) coll).valAt(key, notFound);
        if (coll == null) return notFound;
        if (coll instanceof java.util.Map) {
            java.util.Map<?,?> m = (java.util.Map<?,?>) coll;
            return m.containsKey(key) ? m.get(key) : notFound;
        }
        if (coll instanceof IPersistentSet) {
            Object v = ((IPersistentSet) coll).get(key);
            return v != null ? v : notFound;
        }
        return notFound;
    }

    public static IPersistentMap map(Object... init) {
        if (init == null || init.length == 0) return PersistentArrayMap.EMPTY;
        if (init.length <= 16)  // PersistentArrayMap.HASHTABLE_THRESHOLD
            return PersistentArrayMap.createWithCheck(init);
        return PersistentHashMap.createWithCheck(init);
    }

    public static IPersistentVector subvec(IPersistentVector v, int start, int end) {
        if (end < start || start < 0 || end > v.count())
            throw new IndexOutOfBoundsException();
        if (start == end) return PersistentVector.EMPTY;
        return new APersistentVector.SubVector(null, v, start, end);
    }

    public static Var var(String ns, String name) {
        return Var.intern(Namespace.findOrCreate(Symbol.intern(null, ns)),
                          Symbol.intern(null, name));
    }

    public static Object[] seqToArray(ISeq seq) {
        int len = length(seq);
        Object[] ret = new Object[len];
        for (int i = 0; seq != null; ++i, seq = seq.next())
            ret[i] = seq.first();
        return ret;
    }

    private static int length(ISeq seq) {
        int i = 0;
        for (ISeq s = seq; s != null; s = s.next()) i++;
        return i;
    }

    // Compiler.munge/demunge replacements
    private static final java.util.Map<Character, String> CHAR_MAP = new java.util.HashMap<>();
    private static final java.util.Map<String, Character> DEMUNGE_MAP = new java.util.HashMap<>();
    static {
        CHAR_MAP.put('-', "_");
        CHAR_MAP.put('.', "_DOT_");
        CHAR_MAP.put(':', "_COLON_");
        CHAR_MAP.put('+', "_PLUS_");
        CHAR_MAP.put('>', "_GT_");
        CHAR_MAP.put('<', "_LT_");
        CHAR_MAP.put('=', "_EQ_");
        CHAR_MAP.put('~', "_TILDE_");
        CHAR_MAP.put('!', "_BANG_");
        CHAR_MAP.put('@', "_CIRCA_");
        CHAR_MAP.put('#', "_SHARP_");
        CHAR_MAP.put('\'', "_SINGLEQUOTE_");
        CHAR_MAP.put('"', "_DOUBLEQUOTE_");
        CHAR_MAP.put('%', "_PERCENT_");
        CHAR_MAP.put('^', "_CARET_");
        CHAR_MAP.put('&', "_AMPERSAND_");
        CHAR_MAP.put('*', "_STAR_");
        CHAR_MAP.put('|', "_BAR_");
        CHAR_MAP.put('{', "_LBRACE_");
        CHAR_MAP.put('}', "_RBRACE_");
        CHAR_MAP.put('[', "_LBRACK_");
        CHAR_MAP.put(']', "_RBRACK_");
        CHAR_MAP.put('/', "_SLASH_");
        CHAR_MAP.put('\\', "_BSLASH_");
        CHAR_MAP.put('?', "_QMARK_");
        for (var e : CHAR_MAP.entrySet()) {
            DEMUNGE_MAP.put(e.getValue(), e.getKey());
        }
    }

    public static String munge(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toCharArray()) {
            String sub = CHAR_MAP.get(c);
            if (sub != null) sb.append(sub);
            else sb.append(c);
        }
        return sb.toString();
    }

    public static String demunge(String mungedName) {
        String result = mungedName;
        for (var e : DEMUNGE_MAP.entrySet()) {
            result = result.replace(e.getKey(), String.valueOf(e.getValue()));
        }
        return result;
    }

    public static IPersistentVector vector(Object... init) {
        return LazilyPersistentVector.createOwning(init);
    }

    public static Object first(Object x) {
        if (x instanceof ISeq) return ((ISeq) x).first();
        ISeq s = seq(x);
        if (s == null) return null;
        return s.first();
    }

    public static Object second(Object x) {
        return first(next(x));
    }

    public static Object third(Object x) {
        return first(next(next(x)));
    }

    public static ISeq next(Object x) {
        if (x instanceof ISeq) return ((ISeq) x).next();
        ISeq s = seq(x);
        if (s == null) return null;
        return s.next();
    }

    public static ISeq cons(Object x, Object coll) {
        if (coll == null) return new PersistentList(x);
        else if (coll instanceof ISeq) return new Cons(x, (ISeq) coll);
        else return new Cons(x, seq(coll));
    }

    public static ISeq list() { return null; }
    public static ISeq list(Object a1) { return new PersistentList(a1); }
    public static ISeq list(Object a1, Object a2) { return listStar(a1, a2, null); }
    public static ISeq list(Object a1, Object a2, Object a3) { return listStar(a1, a2, a3, null); }
    public static ISeq list(Object a1, Object a2, Object a3, Object a4) { return listStar(a1, a2, a3, a4, null); }
    public static ISeq list(Object a1, Object a2, Object a3, Object a4, Object a5) { return listStar(a1, a2, a3, a4, a5, null); }

    public static ISeq listStar(Object a1, ISeq rest) { return (ISeq) cons(a1, rest); }
    public static ISeq listStar(Object a1, Object a2, ISeq rest) { return (ISeq) cons(a1, cons(a2, rest)); }
    public static ISeq listStar(Object a1, Object a2, Object a3, ISeq rest) { return (ISeq) cons(a1, cons(a2, cons(a3, rest))); }
    public static ISeq listStar(Object a1, Object a2, Object a3, Object a4, ISeq rest) { return (ISeq) cons(a1, cons(a2, cons(a3, cons(a4, rest)))); }
    public static ISeq listStar(Object a1, Object a2, Object a3, Object a4, Object a5, ISeq rest) { return (ISeq) cons(a1, cons(a2, cons(a3, cons(a4, cons(a5, rest))))); }

    public static Associative assoc(Object coll, Object key, Object val) {
        if (coll == null) return new PersistentArrayMap(new Object[]{key, val});
        return ((Associative) coll).assoc(key, val);
    }
}
