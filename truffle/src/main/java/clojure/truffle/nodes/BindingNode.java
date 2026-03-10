package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;

/**
 * Dynamic binding node: (binding [*var* val ...] body)
 * Pushes thread-local bindings, executes body, restores bindings.
 */
public class BindingNode extends ExpressionNode {

    private final String[] varNames;
    @Children private final ExpressionNode[] valueNodes;
    @Children private final ExpressionNode[] bodyNodes;
    private final ClojureContext context;

    public BindingNode(ClojureContext context, String[] varNames,
                       ExpressionNode[] valueNodes, ExpressionNode[] bodyNodes) {
        this.context = context;
        this.varNames = varNames;
        this.valueNodes = valueNodes;
        this.bodyNodes = bodyNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        // Save old values and push new bindings
        Object[] oldValues = new Object[varNames.length];
        for (int i = 0; i < varNames.length; i++) {
            oldValues[i] = context.getThreadBinding(varNames[i]);
            Object newVal = valueNodes[i].executeGeneric(frame);
            context.pushThreadBinding(varNames[i], newVal);
        }
        try {
            Object result = clojure.truffle.runtime.ClojureNil.INSTANCE;
            for (ExpressionNode node : bodyNodes) {
                result = node.executeGeneric(frame);
            }
            return result;
        } finally {
            // Restore old bindings
            for (int i = 0; i < varNames.length; i++) {
                if (oldValues[i] == null) {
                    context.popThreadBinding(varNames[i]);
                } else {
                    context.pushThreadBinding(varNames[i], oldValues[i]);
                }
            }
        }
    }
}
