package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

@NodeChild("left")
@NodeChild("right")
public abstract class SubNode extends ExpressionNode {

    @Specialization
    protected long subLong(long a, long b) {
        return a - b;
    }

    @Specialization
    protected double subDouble(double a, double b) {
        return a - b;
    }

    @Specialization
    protected double subLongDouble(long a, double b) {
        return a - b;
    }

    @Specialization
    protected double subDoubleLong(double a, long b) {
        return a - b;
    }

    @Fallback
    protected Object subGeneric(Object a, Object b) {
        return toDouble(a) - toDouble(b);
    }

    private static double toDouble(Object o) {
        if (o instanceof Long l) return l.doubleValue();
        if (o instanceof Double d) return d;
        if (o instanceof Number n) return n.doubleValue();
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("Cannot subtract non-number: " + o);
    }

    public static SubNode create(ExpressionNode left, ExpressionNode right) {
        return SubNodeGen.create(left, right);
    }
}
