package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class ThrowNode extends ExpressionNode {

    @Child private ExpressionNode exprNode;

    public ThrowNode(ExpressionNode exprNode) {
        this.exprNode = exprNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = exprNode.executeGeneric(frame);
        if (value instanceof Throwable t) {
            sneakyThrow(t);
        }
        throw new RuntimeException(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
