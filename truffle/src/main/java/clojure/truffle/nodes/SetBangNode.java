package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;

/**
 * (set! *dynamic-var* value) - sets the thread-local binding of a dynamic var.
 * Only works for vars that have been declared dynamic and have an active binding.
 */
public class SetBangNode extends ExpressionNode {
    private final ClojureContext context;
    private final String varName;
    @Child private ExpressionNode valueNode;

    public SetBangNode(ClojureContext context, String varName, ExpressionNode valueNode) {
        this.context = context;
        this.varName = varName;
        this.valueNode = valueNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = valueNode.executeGeneric(frame);
        if (!context.isDynamic(varName)) {
            throwNonDynamic(varName);
        }
        context.pushThreadBinding(varName, value);
        return value;
    }

    @TruffleBoundary
    private static void throwNonDynamic(String varName) {
        throw new RuntimeException("Can't set! non-dynamic var: " + varName);
    }
}
