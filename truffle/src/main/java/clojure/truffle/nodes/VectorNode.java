package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.lang.PersistentVector;

import java.util.Arrays;

public class VectorNode extends ExpressionNode {

    @Children private final ExpressionNode[] elements;

    public VectorNode(ExpressionNode[] elements) {
        this.elements = elements;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] values = new Object[elements.length];
        for (int i = 0; i < elements.length; i++) {
            values[i] = elements[i].executeGeneric(frame);
        }
        return createVector(values);
    }

    @TruffleBoundary
    private static PersistentVector createVector(Object[] values) {
        return PersistentVector.create(Arrays.asList(values));
    }
}
