package clojure.truffle.runtime;

import clojure.truffle.ClojureContext;

import java.util.concurrent.ConcurrentHashMap;

public class ClojureMultiMethod {

    private final String name;
    private final Object dispatchFn;
    private final ClojureContext context;
    private final ConcurrentHashMap<Object, Object> methods = new ConcurrentHashMap<>();
    private volatile Object defaultMethod;
    private volatile Object hierarchy; // ClojureVar or atom holding a hierarchy map

    public ClojureMultiMethod(String name, Object dispatchFn, ClojureContext context) {
        this.name = name;
        this.dispatchFn = dispatchFn;
        this.context = context;
    }

    public void setHierarchy(Object hierarchy) {
        this.hierarchy = hierarchy;
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
        java.util.List<Object> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            boolean dominated = false;
            for (int j = 0; j < matches.size(); j++) {
                if (i == j) continue;
                if (isPreferred(matches.get(j), matches.get(i))) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) candidates.add(matches.get(i));
        }
        if (candidates.size() == 1) return methods.get(candidates.get(0));
        // Ambiguous - throw
        throw new IllegalArgumentException("Multiple methods in multimethod '" + name +
                "' match dispatch value: " + dispatchVal + " -> " + candidates +
                ", and none is preferred");
    }

    private boolean isaCheck(Object child, Object parent) {
        if (hierarchy != null) {
            // Dereference the hierarchy var to get the hierarchy map
            Object h = hierarchy;
            if (h instanceof ClojureVar cv) {
                h = cv.deref();
            }
            return context.isaCheck(child, parent, h);
        }
        return context.isaCheckPublic(child, parent);
    }

    private boolean isPreferred(Object x, Object y) {
        // Direct preference
        java.util.Set<Object> prefs = preferences.get(x);
        if (prefs != null && prefs.contains(y)) return true;
        // x preferred over ancestor of y?
        if (prefs != null) {
            for (Object pref : prefs) {
                if (isaCheck(y, pref)) return true;
            }
        }
        // Ancestor of x preferred over y?
        for (var entry : preferences.entrySet()) {
            if (isaCheck(x, entry.getKey()) && entry.getValue().contains(y)) return true;
        }
        // Also check if x is derived from y (more specific)
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

    public clojure.lang.IPersistentMap getPreferTable() {
        clojure.lang.IPersistentMap result = clojure.lang.PersistentArrayMap.EMPTY;
        for (var entry : preferences.entrySet()) {
            clojure.lang.IPersistentSet vals = clojure.lang.PersistentHashSet.create(
                    new java.util.ArrayList<>(entry.getValue()));
            result = result.assoc(entry.getKey(), vals);
        }
        return result;
    }

    public String getName() { return name; }

    @Override
    public String toString() {
        return "<multimethod " + name + ">";
    }
}
