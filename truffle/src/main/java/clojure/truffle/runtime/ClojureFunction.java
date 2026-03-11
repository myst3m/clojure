package clojure.truffle.runtime;

import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;
import com.oracle.truffle.api.CallTarget;

public class ClojureFunction {
    private final String name;
    private final CallTarget callTarget;
    private final Object[] capturedValues;
    private volatile Object multiArityParent; // set when this is part of a MultiArityFunction
    private volatile IPersistentMap meta;

    public ClojureFunction(String name, CallTarget callTarget, Object[] capturedValues) {
        this.name = name;
        this.callTarget = callTarget;
        this.capturedValues = capturedValues;
    }

    public void setMultiArityParent(Object parent) {
        this.multiArityParent = parent;
    }

    public Object getMultiArityParent() {
        return multiArityParent;
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

    public IPersistentMap getMeta() {
        return meta;
    }

    public void setMeta(IPersistentMap meta) {
        this.meta = meta;
    }

    @Override
    public String toString() {
        return "<fn" + (name != null ? " " + name : "") + ">";
    }
}
