package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;

public class AndNode extends ExpressionNode {

    @Children private final ExpressionNode[] nodes;

    public AndNode(ExpressionNode[] nodes) {
        this.nodes = nodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        if (nodes.length == 0) return true;
        Object result = null;
        for (ExpressionNode node : nodes) {
            result = node.executeGeneric(frame);
            if (!ClojureContext.isTruthy(result)) return result;
        }
        return result;
    }
}
