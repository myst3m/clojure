package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

@NodeChild("left")
@NodeChild("right")
public abstract class AddNode extends ExpressionNode {

    @Specialization
    protected long addLong(long a, long b) {
        return a + b;
    }

    @Specialization
    protected double addDouble(double a, double b) {
        return a + b;
    }

    @Specialization
    protected double addLongDouble(long a, double b) {
        return a + b;
    }

    @Specialization
    protected double addDoubleLong(double a, long b) {
        return a + b;
    }

    @Fallback
    protected Object addGeneric(Object a, Object b) {
        return toDouble(a) + toDouble(b);
    }

    private static double toDouble(Object o) {
        if (o instanceof Long l) return l.doubleValue();
        if (o instanceof Double d) return d;
        if (o instanceof Number n) return n.doubleValue();
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("Cannot add non-number: " + o);
    }

    public static AddNode create(ExpressionNode left, ExpressionNode right) {
        return AddNodeGen.create(left, right);
    }
}
