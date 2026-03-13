package clojure.truffle.nodes;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.runtime.ClojureNil;

/**
 * Clojure (loop [bindings] body) node using Truffle's LoopNode for OSR support.
 * Initializes loop variables, then delegates to LoopRepeatingNode via Truffle's
 * built-in LoopNode which handles OSR and safepoint polling automatically.
 */
public class LoopNode extends ExpressionNode {

    @Children private final ExpressionNode[] initValues;
    @Child private com.oracle.truffle.api.nodes.LoopNode loopNode;
    private final int[] slots;
    private final int resultSlot;

    public LoopNode(int[] slots, ExpressionNode[] initValues, ExpressionNode bodyNode, int resultSlot) {
        this.slots = slots;
        this.initValues = initValues;
        this.resultSlot = resultSlot;
        LoopRepeatingNode repeatingNode = new LoopRepeatingNode(bodyNode, slots, resultSlot);
        this.loopNode = Truffle.getRuntime().createLoopNode(repeatingNode);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        // Initialize loop variables
        for (int i = 0; i < initValues.length; i++) {
            frame.setObject(slots[i], initValues[i].executeGeneric(frame));
        }
        // Initialize result slot
        frame.setObject(resultSlot, ClojureNil.INSTANCE);
        // Execute the loop with OSR support
        loopNode.execute(frame);
        // Return the result stored by the RepeatingNode
        return frame.getObject(resultSlot);
    }
}
