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
            // Try isa?-based dispatch (hierarchy + Java class hierarchy)
            method = findByIsa(dispatchVal);
        }
        if (method == null) method = defaultMethod;
        if (method == null) {
            throw new RuntimeException("No method in multimethod '" + name +
                    "' for dispatch value: " + dispatchVal);
        }
        return context.callFunction(method, args);
    }

    private Object findByIsa(Object dispatchVal) {
        // Check each registered method key with isa?
        java.util.List<Object> matches = new java.util.ArrayList<>();
        for (var entry : methods.entrySet()) {
            Object methodKey = entry.getKey();
            if (isaCheck(dispatchVal, methodKey)) {
                matches.add(methodKey);
            }
        }
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return methods.get(matches.get(0));
        // Multiple matches: check preferences
        for (int i = 0; i < matches.size(); i++) {
            boolean preferred = true;
            for (int j = 0; j < matches.size(); j++) {
                if (i == j) continue;
                if (isPreferred(matches.get(j), matches.get(i))) {
                    preferred = false;
                    break;
                }
            }
            if (preferred) return methods.get(matches.get(i));
        }
        // Ambiguous - return first match
        return methods.get(matches.get(0));
    }

    private boolean isaCheck(Object child, Object parent) {
        if (child == null || parent == null) return false;
        if (child.equals(parent)) return true;
        // Vector dispatch values: check element-wise
        if (child instanceof clojure.lang.IPersistentVector cv &&
                parent instanceof clojure.lang.IPersistentVector pv) {
            if (cv.count() != pv.count()) return false;
            for (int i = 0; i < cv.count(); i++) {
                if (!isaCheck(cv.nth(i), pv.nth(i))) return false;
            }
            return true;
        }
        // Check hierarchy (ancestors of child contain parent?)
        Object hier = context.getGlobalVar("*hierarchy*");
        if (hier instanceof clojure.lang.IPersistentMap h) {
            Object ancestors = ((clojure.lang.IPersistentMap) h.valAt(
                    clojure.lang.Keyword.intern("ancestors"))).valAt(child);
            if (ancestors instanceof clojure.lang.IPersistentSet s) {
                if (s.contains(parent)) return true;
            }
        }
        // Java class hierarchy
        if (child instanceof Class<?> cc && parent instanceof Class<?> pc) {
            return pc.isAssignableFrom(cc);
        }
        return false;
    }

    private boolean isPreferred(Object x, Object y) {
        java.util.Set<Object> prefs = preferences.get(x);
        if (prefs != null && prefs.contains(y)) return true;
        // Also check if x is derived from y
        return isaCheck(x, y);
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

    public Object getMethod(Object dispatchVal) {
        if (dispatchVal instanceof clojure.lang.Keyword kw && kw.getName().equals("default")) {
            return defaultMethod;
        }
        return methods.get(dispatchVal);
    }

    public String getName() { return name; }

    @Override
    public String toString() {
        return "<multimethod " + name + ">";
    }
}
