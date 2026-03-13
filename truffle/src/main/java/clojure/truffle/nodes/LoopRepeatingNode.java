package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import clojure.truffle.runtime.RecurException;

/**
 * RepeatingNode for Clojure loop/recur.
 * Used with Truffle's LoopNode for OSR (On-Stack Replacement) support.
 * Each iteration executes the body; if RecurException is thrown,
 * loop variables are updated and the loop continues.
 */
public class LoopRepeatingNode extends Node implements RepeatingNode {

    @Child private ExpressionNode bodyNode;
    private final int[] slots;
    private final int resultSlot;

    public LoopRepeatingNode(ExpressionNode bodyNode, int[] slots, int resultSlot) {
        this.bodyNode = bodyNode;
        this.slots = slots;
        this.resultSlot = resultSlot;
    }

    @Override
    public boolean executeRepeating(VirtualFrame frame) {
        try {
            Object result = bodyNode.executeGeneric(frame);
            frame.setObject(resultSlot, result);
            return false; // done, exit loop
        } catch (RecurException e) {
            Object[] newValues = e.getValues();
            for (int i = 0; i < slots.length; i++) {
                frame.setObject(slots[i], newValues[i]);
            }
            return true; // continue loop
        }
    }
}
