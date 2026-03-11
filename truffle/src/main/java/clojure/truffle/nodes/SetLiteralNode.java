package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.lang.PersistentHashSet;

import java.util.ArrayList;
import java.util.List;

/**
 * #{a b c} set literal - evaluates elements and creates a PersistentHashSet.
 */
public class SetLiteralNode extends ExpressionNode {

    @Children private final ExpressionNode[] elements;

    public SetLiteralNode(ExpressionNode[] elements) {
        this.elements = elements;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        List<Object> values = new ArrayList<>(elements.length);
        for (ExpressionNode element : elements) {
            values.add(element.executeGeneric(frame));
        }
        return PersistentHashSet.create(values);
    }
}
