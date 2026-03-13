package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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
        Object[] values = new Object[elements.length];
        for (int i = 0; i < elements.length; i++) {
            values[i] = elements[i].executeGeneric(frame);
        }
        return createSet(values);
    }

    @TruffleBoundary
    private static Object createSet(Object[] values) {
        List<Object> list = new ArrayList<>(values.length);
        for (Object v : values) list.add(v);
        return PersistentHashSet.create(list);
    }
}
