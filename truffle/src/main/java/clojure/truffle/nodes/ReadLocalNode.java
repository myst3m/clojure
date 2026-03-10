package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class ReadLocalNode extends ExpressionNode {
    private final int slot;

    public ReadLocalNode(int slot) {
        this.slot = slot;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return frame.getObject(slot);
    }
}
