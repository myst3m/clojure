package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class DoubleLiteralNode extends ExpressionNode {
    private final double value;

    public DoubleLiteralNode(double value) {
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}
