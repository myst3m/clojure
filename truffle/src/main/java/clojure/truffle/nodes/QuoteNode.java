package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class QuoteNode extends ExpressionNode {
    private final Object value;

    public QuoteNode(Object value) {
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}
