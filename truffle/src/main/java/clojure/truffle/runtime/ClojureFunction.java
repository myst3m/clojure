package clojure.truffle.runtime;

import com.oracle.truffle.api.CallTarget;

public class ClojureFunction {
    private final String name;
    private final CallTarget callTarget;
    private final Object[] capturedValues;

    public ClojureFunction(String name, CallTarget callTarget, Object[] capturedValues) {
        this.name = name;
        this.callTarget = callTarget;
        this.capturedValues = capturedValues;
    }

    public String getName() {
        return name;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public Object[] getCapturedValues() {
        return capturedValues;
    }

    @Override
    public String toString() {
        return "<fn" + (name != null ? " " + name : "") + ">";
    }
}
