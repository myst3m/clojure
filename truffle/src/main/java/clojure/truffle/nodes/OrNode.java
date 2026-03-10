package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;
import clojure.truffle.runtime.ClojureNil;

public class OrNode extends ExpressionNode {

    @Children private final ExpressionNode[] nodes;

    public OrNode(ExpressionNode[] nodes) {
        this.nodes = nodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        if (nodes.length == 0) return ClojureNil.INSTANCE;
        Object result = null;
        for (ExpressionNode node : nodes) {
            result = node.executeGeneric(frame);
            if (ClojureContext.isTruthy(result)) return result;
        }
        return result;
    }
}
