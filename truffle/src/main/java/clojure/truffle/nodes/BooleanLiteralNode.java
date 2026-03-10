package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class BooleanLiteralNode extends ExpressionNode {
    private final boolean value;

    public BooleanLiteralNode(boolean value) {
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}
