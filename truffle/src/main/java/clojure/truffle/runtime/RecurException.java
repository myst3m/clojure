package clojure.truffle.runtime;

import com.oracle.truffle.api.nodes.ControlFlowException;

public final class RecurException extends ControlFlowException {
    private final Object[] values;

    public RecurException(Object[] values) {
        this.values = values;
    }

    public Object[] getValues() {
        return values;
    }
}
