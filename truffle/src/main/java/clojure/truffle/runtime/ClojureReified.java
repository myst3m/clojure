package clojure.truffle.runtime;

import java.util.Map;

import clojure.lang.IObj;
import clojure.lang.IPersistentMap;

/**
 * Runtime representation of a reified object.
 * Stores method implementations as functions in a map.
 */
public class ClojureReified implements IObj {
    private final Map<String, Object> methods;
    private final IPersistentMap meta;

    public ClojureReified(Map<String, Object> methods) {
        this(methods, null);
    }

    public ClojureReified(Map<String, Object> methods, IPersistentMap meta) {
        this.methods = methods;
        this.meta = meta;
    }

    @Override
    public IPersistentMap meta() {
        return meta;
    }

    @Override
    public IObj withMeta(IPersistentMap meta) {
        return new ClojureReified(this.methods, meta);
    }

    public Object getMethod(String name) {
        return methods.get(name);
    }

    public boolean hasMethod(String name) {
        return methods.containsKey(name);
    }

    public Map<String, Object> getMethods() {
        return methods;
    }

    @Override
    public String toString() {
        return "#reify{" + String.join(", ", methods.keySet()) + "}";
    }
}
