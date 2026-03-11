package clojure.truffle.runtime;

import clojure.lang.IPersistentMap;

public class MultiArityFunction {
    private final int[] arities;
    private final ClojureFunction[] functions;
    private final int variadicIndex;
    private volatile IPersistentMap meta;

    public MultiArityFunction(int[] arities, ClojureFunction[] functions, int variadicIndex) {
        this.arities = arities;
        this.functions = functions;
        this.variadicIndex = variadicIndex;
        // Set parent reference so self-calls in fn bodies dispatch through MultiArityFunction
        for (ClojureFunction fn : functions) {
            if (fn != null) fn.setMultiArityParent(this);
        }
    }

    public ClojureFunction resolve(int argCount) {
        for (int i = 0; i < arities.length; i++) {
            if (i == variadicIndex) continue;
            if (arities[i] == argCount) return functions[i];
        }
        if (variadicIndex >= 0 && argCount >= arities[variadicIndex]) {
            return functions[variadicIndex];
        }
        throw new RuntimeException("Wrong number of args (" + argCount + ") passed to fn");
    }

    public IPersistentMap getMeta() { return meta; }
    public void setMeta(IPersistentMap meta) { this.meta = meta; }

    @Override
    public String toString() {
        return "<fn multi-arity>";
    }
}
