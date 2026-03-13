package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Reads a value through a mutable cell (Object[1] array) stored in a frame slot.
 * Used by letfn to enable mutual recursion: functions capture the cell reference,
 * and when the cell is updated (after all fns are assigned), all references
 * see the latest value.
 */
public class LetfnCellReadNode extends ExpressionNode {
    private final int cellSlot;

    public LetfnCellReadNode(int cellSlot) {
        this.cellSlot = cellSlot;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] cell = (Object[]) frame.getObject(cellSlot);
        return cell[0];
    }
}
