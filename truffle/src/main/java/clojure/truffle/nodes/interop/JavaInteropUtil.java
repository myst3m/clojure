package clojure.truffle.nodes.interop;

import clojure.truffle.runtime.ClojureNil;

import java.lang.reflect.*;
import java.util.HashMap;
import java.util.Map;

public class JavaInteropUtil {

    private static final Map<String, Class<?>> CLASS_CACHE = new HashMap<>();

    static {
        // java.lang.*
        for (String name : new String[]{
                "Object", "String", "Integer", "Long", "Double", "Float", "Boolean",
                "Byte", "Short", "Character", "Number", "Math", "System",
                "StringBuilder", "StringBuffer", "Thread", "Class",
                "Throwable", "Exception", "RuntimeException", "Error",
                "IllegalArgumentException", "IllegalStateException",
                "UnsupportedOperationException", "NullPointerException",
                "IndexOutOfBoundsException", "ArithmeticException",
                "ClassCastException", "ClassNotFoundException",
                "StackOverflowError", "OutOfMemoryError",
                "AssertionError", "Comparable", "Iterable", "Runnable",
                "AutoCloseable", "Cloneable", "CharSequence", "Appendable",
                "Readable", "ProcessBuilder", "Process"
        }) {
            try {
                CLASS_CACHE.put(name, Class.forName("java.lang." + name));
            } catch (ClassNotFoundException ignored) {}
        }
        // java.util.*
        for (String name : new String[]{
                "ArrayList", "HashMap", "HashSet", "LinkedList", "TreeMap", "TreeSet",
                "Collections", "Arrays", "List", "Map", "Set", "Iterator",
                "Comparator", "Optional", "Objects", "UUID", "Date",
                "Properties", "Random", "regex.Pattern", "regex.Matcher",
                "concurrent.Future", "concurrent.Callable"
        }) {
            try {
                String simpleName = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
                CLASS_CACHE.put(simpleName, Class.forName("java.util." + name));
            } catch (ClassNotFoundException ignored) {}
        }
        // java.math.*
        for (String name : new String[]{"BigDecimal", "BigInteger"}) {
            try {
                CLASS_CACHE.put(name, Class.forName("java.math." + name));
            } catch (ClassNotFoundException ignored) {}
        }
        // java.io.*
        for (String name : new String[]{
                "File", "InputStream", "OutputStream", "Reader", "Writer",
                "BufferedReader", "BufferedWriter", "FileReader", "FileWriter",
                "StringReader", "StringWriter", "PrintStream", "PrintWriter",
                "Serializable"
        }) {
            try {
                CLASS_CACHE.put(name, Class.forName("java.io." + name));
            } catch (ClassNotFoundException ignored) {}
        }
    }

    public static Class<?> resolveClass(String name) {
        Class<?> cached = CLASS_CACHE.get(name);
        if (cached != null) return cached;
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            Class<?> clz = cl != null ? Class.forName(name, true, cl) : Class.forName(name);
            CLASS_CACHE.put(name, clz);
            return clz;
        } catch (ClassNotFoundException e) {
            // Try java.lang. prefix for simple class names (e.g. ThreadLocal → java.lang.ThreadLocal)
            if (!name.contains(".")) {
                try {
                    Class<?> clz = cl != null
                            ? Class.forName("java.lang." + name, true, cl)
                            : Class.forName("java.lang." + name);
                    CLASS_CACHE.put(name, clz);
                    return clz;
                } catch (ClassNotFoundException ignored) {}
            }
            throw new RuntimeException("Class not found: " + name);
        }
    }

    public static java.util.Set<Class<?>> getAllInterfaces(Class<?> clazz) {
        java.util.Set<Class<?>> result = new java.util.LinkedHashSet<>();
        while (clazz != null) {
            for (Class<?> iface : clazz.getInterfaces()) {
                result.add(iface);
            }
            clazz = clazz.getSuperclass();
        }
        return result;
    }

    public static Method findMethod(Class<?> clazz, String name, Object[] args, boolean isStatic) {
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        Method bestVarArgs = null;
        int bestVarArgsScore = Integer.MAX_VALUE;

        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (isStatic != Modifier.isStatic(m.getModifiers())) continue;

            if (m.isVarArgs()) {
                int fixedCount = m.getParameterCount() - 1;
                if (args.length >= fixedCount) {
                    int score = varArgsMatchScore(m.getParameterTypes(), args);
                    if (score < bestVarArgsScore) {
                        bestVarArgsScore = score;
                        bestVarArgs = m;
                    }
                }
            }

            if (m.getParameterCount() == args.length) {
                int score = matchScore(m.getParameterTypes(), args);
                if (score < bestScore) {
                    bestScore = score;
                    best = m;
                }
            }
        }
        // Prefer exact-arity over varargs
        if (best != null && bestScore <= bestVarArgsScore) return best;
        if (bestVarArgs != null) return bestVarArgs;
        return best;
    }

    private static int matchScore(Class<?>[] paramTypes, Object[] args) {
        int score = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            score += singleArgScore(paramTypes[i], args[i]);
        }
        return score;
    }

    private static int varArgsMatchScore(Class<?>[] paramTypes, Object[] args) {
        int score = 0;
        int fixedCount = paramTypes.length - 1;
        // Score fixed params
        for (int i = 0; i < fixedCount; i++) {
            score += singleArgScore(paramTypes[i], args[i]);
        }
        // Score varargs params against the component type
        Class<?> varArgType = paramTypes[fixedCount].getComponentType();
        for (int i = fixedCount; i < args.length; i++) {
            score += singleArgScore(varArgType, args[i]);
        }
        score += 1; // slight penalty for varargs to prefer exact arity
        return score;
    }

    private static int singleArgScore(Class<?> pt, Object arg) {
        if (arg instanceof ClojureNil) arg = null;
        if (arg == null) return 1;
        Class<?> at = arg.getClass();
        if (pt == at || pt.isAssignableFrom(at)) return 0;
        // Number widening
        if (arg instanceof Long) {
            if (pt == long.class || pt == Long.class) return 0;
            if (pt == int.class || pt == Integer.class) return 1;
            if (pt == double.class || pt == Double.class) return 2;
            if (pt == float.class || pt == Float.class) return 3;
            if (pt == short.class || pt == Short.class) return 2;
            if (pt == byte.class || pt == Byte.class) return 3;
        }
        if (arg instanceof Double) {
            if (pt == double.class || pt == Double.class) return 0;
            if (pt == float.class || pt == Float.class) return 1;
        }
        if (arg instanceof Boolean && (pt == boolean.class)) return 0;
        if (arg instanceof String && (pt == CharSequence.class)) return 0;
        // Clojure collection to Java collection
        if (arg instanceof clojure.lang.Seqable) {
            if (pt == Iterable.class || pt.isAssignableFrom(java.util.List.class)) return 2;
        }
        if (pt == Object.class) return 5;
        return 10;
    }

    public static Constructor<?> findConstructor(Class<?> clazz, Object[] args) {
        Constructor<?> best = null;
        int bestScore = Integer.MAX_VALUE;
        Constructor<?> bestVarArgs = null;
        int bestVarArgsScore = Integer.MAX_VALUE;

        for (Constructor<?> c : clazz.getConstructors()) {
            if (c.isVarArgs() && args.length >= c.getParameterCount() - 1) {
                int score = varArgsMatchScore(c.getParameterTypes(), args);
                if (score < bestVarArgsScore) {
                    bestVarArgsScore = score;
                    bestVarArgs = c;
                }
            }
            if (c.getParameterCount() == args.length) {
                int score = matchScore(c.getParameterTypes(), args);
                if (score < bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        if (best != null && bestScore <= bestVarArgsScore) return best;
        if (bestVarArgs != null) return bestVarArgs;
        return best;
    }

    // Keep old signature for backward compatibility
    public static Constructor<?> findConstructor(Class<?> clazz, int argCount) {
        // Create dummy args for scoring
        Object[] dummyArgs = new Object[argCount];
        for (int i = 0; i < argCount; i++) dummyArgs[i] = null;
        return findConstructor(clazz, dummyArgs);
    }

    public static Object[] coerceArgs(Executable method, Object[] args) {
        Class<?>[] paramTypes = method.getParameterTypes();
        if (method instanceof Method m && m.isVarArgs() && args.length != paramTypes.length) {
            return coerceVarArgs(paramTypes, args);
        }
        if (method instanceof Constructor<?> c && c.isVarArgs() && args.length != paramTypes.length) {
            return coerceVarArgs(paramTypes, args);
        }
        // Non-varargs or exact-arity varargs call
        Object[] result = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            result[i] = coerce(args[i], paramTypes[i]);
        }
        return result;
    }

    /** Coerce arguments, packing trailing args into a varargs array. */
    private static Object[] coerceVarArgs(Class<?>[] paramTypes, Object[] args) {
        int fixedCount = paramTypes.length - 1;
        Class<?> varArgArrayType = paramTypes[fixedCount];
        Class<?> componentType = varArgArrayType.getComponentType();

        Object[] result = new Object[paramTypes.length];
        // Coerce fixed params
        for (int i = 0; i < fixedCount; i++) {
            result[i] = coerce(args[i], paramTypes[i]);
        }
        // Pack remaining args into varargs array
        int varArgCount = args.length - fixedCount;
        Object varArgArray = java.lang.reflect.Array.newInstance(componentType, varArgCount);
        for (int i = 0; i < varArgCount; i++) {
            java.lang.reflect.Array.set(varArgArray, i, coerce(args[fixedCount + i], componentType));
        }
        result[fixedCount] = varArgArray;
        return result;
    }

    // Keep old signature for compatibility
    public static Object[] coerceArgs(Object[] args, Class<?>[] paramTypes) {
        Object[] result = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            result[i] = coerce(args[i], paramTypes[i]);
        }
        return result;
    }

    public static Object coerce(Object val, Class<?> target) {
        if (val instanceof ClojureNil) val = null;
        if (val == null) return null;
        if (target.isInstance(val)) return val;

        // Number coercions
        if (val instanceof Long l) {
            if (target == int.class || target == Integer.class) return l.intValue();
            if (target == long.class || target == Long.class) return l;
            if (target == double.class || target == Double.class) return l.doubleValue();
            if (target == float.class || target == Float.class) return l.floatValue();
            if (target == short.class || target == Short.class) return l.shortValue();
            if (target == byte.class || target == Byte.class) return l.byteValue();
            if (target == char.class || target == Character.class) return (char) l.intValue();
        }
        if (val instanceof Double d) {
            if (target == float.class || target == Float.class) return d.floatValue();
            if (target == double.class || target == Double.class) return d;
            if (target == int.class || target == Integer.class) return d.intValue();
            if (target == long.class || target == Long.class) return d.longValue();
        }
        if (val instanceof Boolean b) {
            if (target == boolean.class) return b;
        }
        if (val instanceof String s) {
            if (target == char.class || target == Character.class) {
                if (s.length() == 1) return s.charAt(0);
            }
            if (target == CharSequence.class) return s;
        }

        // Clojure collections → Java collections
        if (target.isAssignableFrom(java.util.List.class) || target == java.util.List.class
                || target == java.util.Collection.class || target == Iterable.class) {
            if (val instanceof clojure.lang.Seqable) {
                return seqToList(val);
            }
        }
        if (target == java.util.Map.class && val instanceof clojure.lang.IPersistentMap m) {
            java.util.Map<Object, Object> map = new java.util.HashMap<>();
            for (clojure.lang.ISeq s = m.seq(); s != null; s = s.next()) {
                clojure.lang.IMapEntry entry = (clojure.lang.IMapEntry) s.first();
                map.put(entry.key(), entry.val());
            }
            return map;
        }
        if (target == java.util.Set.class && val instanceof clojure.lang.IPersistentSet ps) {
            java.util.Set<Object> set = new java.util.HashSet<>();
            for (clojure.lang.ISeq s = ps.seq(); s != null; s = s.next()) {
                set.add(s.first());
            }
            return set;
        }

        // Array coercion: Clojure collection → typed array
        if (target.isArray()) {
            Class<?> comp = target.getComponentType();
            if (val instanceof clojure.lang.Seqable) {
                java.util.List<Object> items = seqToList(val);
                Object arr = java.lang.reflect.Array.newInstance(comp, items.size());
                for (int i = 0; i < items.size(); i++) {
                    java.lang.reflect.Array.set(arr, i, coerce(items.get(i), comp));
                }
                return arr;
            }
        }

        // Clojure keyword/symbol → string
        if (target == String.class) return val.toString();

        return val;
    }

    private static java.util.List<Object> seqToList(Object val) {
        java.util.List<Object> list = new java.util.ArrayList<>();
        for (clojure.lang.ISeq s = clojure.lang.RT.seq(val); s != null; s = s.next()) {
            list.add(s.first());
        }
        return list;
    }

    public static Object wrapResult(Object result) {
        if (result == null) return ClojureNil.INSTANCE;
        if (result instanceof Void) return ClojureNil.INSTANCE;
        // Convert Java int/short/byte to Long for Clojure
        if (result instanceof Integer i) return (long) i;
        if (result instanceof Short s) return (long) s;
        if (result instanceof Byte b) return (long) b;
        if (result instanceof Float f) return (double) f;
        if (result instanceof Character c) return String.valueOf(c);
        return result;
    }
}
