package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

@NodeChild("operand")
public abstract class DecNode extends ExpressionNode {

    @Specialization
    protected long decLong(long a) {
        return a - 1;
    }

    @Specialization
    protected double decDouble(double a) {
        return a - 1.0;
    }

    @Fallback
    @CompilerDirectives.TruffleBoundary
    protected Object decGeneric(Object a) {
        if (a instanceof Number n) return n.doubleValue() - 1.0;
        throw nonNumberError("decrement", a);
    }

    @CompilerDirectives.TruffleBoundary
    private static RuntimeException nonNumberError(String op, Object o) {
        return new RuntimeException("Cannot " + op + " non-number: " + o);
    }

    public static DecNode create(ExpressionNode operand) {
        return DecNodeGen.create(operand);
    }
}
