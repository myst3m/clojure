package clojure.truffle.runtime;

import java.util.Map;

/**
 * Runtime representation of a reified object.
 * Stores method implementations as functions in a map.
 */
public class ClojureReified {
    private final Map<String, Object> methods;

    public ClojureReified(Map<String, Object> methods) {
        this.methods = methods;
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
