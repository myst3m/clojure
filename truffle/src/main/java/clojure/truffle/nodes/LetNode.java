package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

public class LetNode extends ExpressionNode {

    @Children private final ExpressionNode[] bindingValues;
    @Child private ExpressionNode bodyNode;
    private final int[] slots;

    public LetNode(int[] slots, ExpressionNode[] bindingValues, ExpressionNode bodyNode) {
        this.slots = slots;
        this.bindingValues = bindingValues;
        this.bodyNode = bodyNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        for (int i = 0; i < bindingValues.length; i++) {
            frame.setObject(slots[i], bindingValues[i].executeGeneric(frame));
        }
        return bodyNode.executeGeneric(frame);
    }
}
