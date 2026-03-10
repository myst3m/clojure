package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.runtime.RecurException;

public class RecurNode extends ExpressionNode {

    @Children private final ExpressionNode[] argNodes;

    public RecurNode(ExpressionNode[] argNodes) {
        this.argNodes = argNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] values = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++) {
            values[i] = argNodes[i].executeGeneric(frame);
        }
        throw new RecurException(values);
    }
}
