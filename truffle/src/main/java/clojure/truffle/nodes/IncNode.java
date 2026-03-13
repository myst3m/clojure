package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

@NodeChild("operand")
public abstract class IncNode extends ExpressionNode {

    @Specialization
    protected long incLong(long a) {
        return a + 1;
    }

    @Specialization
    protected double incDouble(double a) {
        return a + 1.0;
    }

    @Fallback
    protected Object incGeneric(Object a) {
        if (a instanceof Number n) return n.doubleValue() + 1.0;
        CompilerDirectives.transferToInterpreterAndInvalidate();
        throw new RuntimeException("Cannot increment non-number: " + a);
    }

    public static IncNode create(ExpressionNode operand) {
        return IncNodeGen.create(operand);
    }
}
