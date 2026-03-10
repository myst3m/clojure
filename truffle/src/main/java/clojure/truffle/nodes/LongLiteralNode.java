package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class LongLiteralNode extends ExpressionNode {
    private final long value;

    public LongLiteralNode(long value) {
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}
