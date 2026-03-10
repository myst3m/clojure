package clojure.truffle.runtime;

public class MultiArityFunction {
    private final int[] arities;
    private final ClojureFunction[] functions;
    private final int variadicIndex;

    public MultiArityFunction(int[] arities, ClojureFunction[] functions, int variadicIndex) {
        this.arities = arities;
        this.functions = functions;
        this.variadicIndex = variadicIndex;
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

    @Override
    public String toString() {
        return "<fn multi-arity>";
    }
}
