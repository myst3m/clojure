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
    @CompilerDirectives.TruffleBoundary
    protected Object subGeneric(Object a, Object b) {
        return toDouble(a) - toDouble(b);
    }

    @CompilerDirectives.TruffleBoundary
    private static double toDouble(Object o) {
        if (o instanceof Long l) return l.doubleValue();
        if (o instanceof Double d) return d;
        if (o instanceof Number n) return n.doubleValue();
        throw nonNumberError("subtract", o);
    }

    @CompilerDirectives.TruffleBoundary
    private static RuntimeException nonNumberError(String op, Object o) {
        return new RuntimeException("Cannot " + op + " non-number: " + o);
    }

    public static SubNode create(ExpressionNode left, ExpressionNode right) {
        return SubNodeGen.create(left, right);
    }
}
