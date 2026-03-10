package clojure.truffle.runtime;

import clojure.truffle.ClojureContext;

import java.util.concurrent.ConcurrentHashMap;

public class ClojureMultiMethod {

    private final String name;
    private final Object dispatchFn;
    private final ClojureContext context;
    private final ConcurrentHashMap<Object, Object> methods = new ConcurrentHashMap<>();
    private volatile Object defaultMethod;

    public ClojureMultiMethod(String name, Object dispatchFn, ClojureContext context) {
        this.name = name;
        this.dispatchFn = dispatchFn;
        this.context = context;
    }

    public void addMethod(Object dispatchVal, Object fn) {
        if (dispatchVal instanceof clojure.lang.Keyword kw && kw.getName().equals("default")) {
            defaultMethod = fn;
        } else {
            methods.put(dispatchVal, fn);
        }
    }

    public Object invoke(Object[] args) {
        Object dispatchVal = context.callFunction(dispatchFn, args);
        Object method = methods.get(dispatchVal);
        if (method == null) {
            // Try class hierarchy for dispatch values that are classes
            if (dispatchVal instanceof Class<?> clazz) {
                method = findByHierarchy(clazz);
            }
        }
        if (method == null) method = defaultMethod;
        if (method == null) {
            throw new RuntimeException("No method in multimethod '" + name +
                    "' for dispatch value: " + dispatchVal);
        }
        return context.callFunction(method, args);
    }

    private Object findByHierarchy(Class<?> clazz) {
        Class<?> c = clazz.getSuperclass();
        while (c != null) {
            Object method = methods.get(c);
            if (method != null) return method;
            c = c.getSuperclass();
        }
        return null;
    }

    private final ConcurrentHashMap<Object, java.util.Set<Object>> preferences = new ConcurrentHashMap<>();

    public void preferMethod(Object preferred, Object other) {
        preferences.computeIfAbsent(preferred, k -> java.util.Collections.newSetFromMap(new ConcurrentHashMap<>())).add(other);
    }

    public clojure.lang.IPersistentMap getMethodTable() {
        clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
        for (var entry : methods.entrySet()) {
            result = result.assoc(entry.getKey(), entry.getValue());
        }
        return result;
    }

    public void removeMethod(Object dispatchVal) {
        if (dispatchVal instanceof clojure.lang.Keyword kw && kw.getName().equals("default")) {
            defaultMethod = null;
        } else {
            methods.remove(dispatchVal);
        }
    }

    public void removeAllMethods() {
        methods.clear();
        defaultMethod = null;
    }

    public String getName() { return name; }

    @Override
    public String toString() {
        return "<multimethod " + name + ">";
    }
}
