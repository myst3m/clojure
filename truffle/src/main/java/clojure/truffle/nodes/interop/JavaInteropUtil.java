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
                "StackOverflowError", "OutOfMemoryError"
        }) {
            try {
                CLASS_CACHE.put(name, Class.forName("java.lang." + name));
            } catch (ClassNotFoundException ignored) {}
        }
        // java.util.*
        for (String name : new String[]{
                "ArrayList", "HashMap", "HashSet", "LinkedList",
                "Collections", "Arrays", "List", "Map", "Set"
        }) {
            try {
                CLASS_CACHE.put(name, Class.forName("java.util." + name));
            } catch (ClassNotFoundException ignored) {}
        }
    }

    public static Class<?> resolveClass(String name) {
        Class<?> cached = CLASS_CACHE.get(name);
        if (cached != null) return cached;
        try {
            Class<?> clz = Class.forName(name);
            CLASS_CACHE.put(name, clz);
            return clz;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + name);
        }
    }

    public static Method findMethod(Class<?> clazz, String name, Object[] args, boolean isStatic) {
        Method best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (isStatic != Modifier.isStatic(m.getModifiers())) continue;
            if (m.getParameterCount() != args.length) {
                if (m.isVarArgs() && args.length >= m.getParameterCount() - 1) {
                    if (best == null) best = m;
                }
                continue;
            }
            int score = matchScore(m.getParameterTypes(), args);
            if (score < bestScore) {
                bestScore = score;
                best = m;
            }
        }
        return best;
    }

    private static int matchScore(Class<?>[] paramTypes, Object[] args) {
        int score = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            Object arg = args[i] instanceof ClojureNil ? null : args[i];
            Class<?> pt = paramTypes[i];
            if (arg == null) { score += 1; continue; }
            Class<?> at = arg.getClass();
            if (pt == at || pt.isAssignableFrom(at)) { score += 0; continue; }
            // Number widening
            if (arg instanceof Long) {
                if (pt == long.class || pt == Long.class) { score += 0; continue; }
                if (pt == int.class || pt == Integer.class) { score += 1; continue; }
                if (pt == double.class || pt == Double.class) { score += 2; continue; }
                if (pt == float.class || pt == Float.class) { score += 3; continue; }
            }
            if (arg instanceof Double) {
                if (pt == double.class || pt == Double.class) { score += 0; continue; }
                if (pt == float.class || pt == Float.class) { score += 1; continue; }
            }
            if (arg instanceof Boolean && (pt == boolean.class)) { score += 0; continue; }
            // Fallback
            score += 10;
        }
        return score;
    }

    public static Constructor<?> findConstructor(Class<?> clazz, int argCount) {
        for (Constructor<?> c : clazz.getConstructors()) {
            if (c.getParameterCount() == argCount) return c;
            if (c.isVarArgs() && argCount >= c.getParameterCount() - 1) return c;
        }
        return null;
    }

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
        // Clojure keyword/symbol → string
        if (target == String.class) return val.toString();

        return val;
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
