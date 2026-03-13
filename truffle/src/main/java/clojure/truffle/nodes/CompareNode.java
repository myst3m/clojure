package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

@NodeChild("left")
@NodeChild("right")
public abstract class CompareNode extends ExpressionNode {

    public enum Op { LT, GT, LE, GE, EQ }

    @CompilationFinal private Op op;

    protected CompareNode(Op op) {
        this.op = op;
    }

    @Specialization
    protected boolean compareLong(long a, long b) {
        return switch (op) {
            case LT -> a < b;
            case GT -> a > b;
            case LE -> a <= b;
            case GE -> a >= b;
            case EQ -> a == b;
        };
    }

    @Specialization
    protected boolean compareDouble(double a, double b) {
        return switch (op) {
            case LT -> a < b;
            case GT -> a > b;
            case LE -> a <= b;
            case GE -> a >= b;
            case EQ -> a == b;
        };
    }

    @Specialization
    protected boolean compareLongDouble(long a, double b) {
        return compareDouble(a, b);
    }

    @Specialization
    protected boolean compareDoubleLong(double a, long b) {
        return compareDouble(a, b);
    }

    @Fallback
    @CompilerDirectives.TruffleBoundary
    protected boolean compareGeneric(Object a, Object b) {
        return compareDouble(toDouble(a), toDouble(b));
    }

    @CompilerDirectives.TruffleBoundary
    private static double toDouble(Object o) {
        if (o instanceof Long l) return l.doubleValue();
        if (o instanceof Double d) return d;
        if (o instanceof Number n) return n.doubleValue();
        throw nonNumberError("compare", o);
    }

    @CompilerDirectives.TruffleBoundary
    private static RuntimeException nonNumberError(String op, Object o) {
        return new RuntimeException("Cannot " + op + " non-number: " + o);
    }

    public static CompareNode create(Op op, ExpressionNode left, ExpressionNode right) {
        return CompareNodeGen.create(op, left, right);
    }
}
