package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RepeatingNode;
import clojure.truffle.runtime.RecurException;

/**
 * RepeatingNode for function-level recur (defn with recur to itself).
 * Used with Truffle's LoopNode for OSR support in recursive functions
 * that use recur instead of self-calls.
 */
public class FnRecurRepeatingNode extends Node implements RepeatingNode {

    @Child private ExpressionNode bodyNode;
    private final int[] paramSlots;
    private final int paramCount;
    private final int variadicSlot;
    private final int resultSlot;

    public FnRecurRepeatingNode(ExpressionNode bodyNode, int[] paramSlots,
                                 int variadicSlot, int resultSlot) {
        this.bodyNode = bodyNode;
        this.paramSlots = paramSlots;
        this.paramCount = paramSlots.length;
        this.variadicSlot = variadicSlot;
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
            // If fewer values than params (e.g., reify/deftype method where 'this' is implicit)
            // preserve leading params and update trailing ones
            int offset = paramCount - newValues.length;
            if (offset < 0) offset = 0;
            for (int i = offset; i < paramCount; i++) {
                frame.setObject(paramSlots[i], newValues[i - offset]);
            }
            if (variadicSlot >= 0 && newValues.length > paramCount) {
                frame.setObject(variadicSlot, newValues[paramCount]);
            }
            return true; // continue loop
        }
    }
}
