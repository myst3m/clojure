package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

@NodeChild("left")
@NodeChild("right")
public abstract class DivNode extends ExpressionNode {

    @Specialization
    protected Object divLong(long a, long b) {
        if (b == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new ArithmeticException("Divide by zero");
        }
        if (a % b == 0) return a / b;
        return (double) a / b;
    }

    @Specialization
    protected double divDouble(double a, double b) {
        return a / b;
    }

    @Specialization
    protected double divLongDouble(long a, double b) {
        return a / b;
    }

    @Specialization
    protected double divDoubleLong(double a, long b) {
        return a / b;
    }

    @Fallback
    @CompilerDirectives.TruffleBoundary
    protected Object divGeneric(Object a, Object b) {
        double db = toDouble(b);
        return toDouble(a) / db;
    }

    @CompilerDirectives.TruffleBoundary
    private static double toDouble(Object o) {
        if (o instanceof Long l) return l.doubleValue();
        if (o instanceof Double d) return d;
        if (o instanceof Number n) return n.doubleValue();
        throw nonNumberError("divide", o);
    }

    @CompilerDirectives.TruffleBoundary
    private static RuntimeException nonNumberError(String op, Object o) {
        return new RuntimeException("Cannot " + op + " non-number: " + o);
    }

    public static DivNode create(ExpressionNode left, ExpressionNode right) {
        return DivNodeGen.create(left, right);
    }
}
