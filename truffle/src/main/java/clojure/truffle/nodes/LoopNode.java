package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.runtime.RecurException;

public class LoopNode extends ExpressionNode {

    @Children private final ExpressionNode[] initValues;
    @Child private ExpressionNode bodyNode;
    private final int[] slots;

    public LoopNode(int[] slots, ExpressionNode[] initValues, ExpressionNode bodyNode) {
        this.slots = slots;
        this.initValues = initValues;
        this.bodyNode = bodyNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        for (int i = 0; i < initValues.length; i++) {
            frame.setObject(slots[i], initValues[i].executeGeneric(frame));
        }
        while (true) {
            try {
                return bodyNode.executeGeneric(frame);
            } catch (RecurException e) {
                Object[] newValues = e.getValues();
                for (int i = 0; i < slots.length; i++) {
                    frame.setObject(slots[i], newValues[i]);
                }
            }
        }
    }
}
