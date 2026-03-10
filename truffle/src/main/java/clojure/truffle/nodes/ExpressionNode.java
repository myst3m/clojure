package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

public abstract class ExpressionNode extends Node {

    public abstract Object executeGeneric(VirtualFrame frame);

    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        Object value = executeGeneric(frame);
        if (value instanceof Long l) return l;
        throw new UnexpectedResultException(value);
    }

    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        Object value = executeGeneric(frame);
        if (value instanceof Double d) return d;
        throw new UnexpectedResultException(value);
    }

    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        Object value = executeGeneric(frame);
        if (value instanceof Boolean b) return b;
        throw new UnexpectedResultException(value);
    }
}
