package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;

/**
 * (with-redefs [var val ...] body...) -> temporarily redefine global vars.
 * Saves old values, sets new values, executes body, restores old values.
 */
public class WithRedefsNode extends ExpressionNode {

    private final ClojureContext context;
    private final String[] varNames;
    @Children private final ExpressionNode[] valueNodes;
    @Children private final ExpressionNode[] bodyNodes;

    public WithRedefsNode(ClojureContext context, String[] varNames,
                          ExpressionNode[] valueNodes, ExpressionNode[] bodyNodes) {
        this.context = context;
        this.varNames = varNames;
        this.valueNodes = valueNodes;
        this.bodyNodes = bodyNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        // Save old values
        Object[] oldValues = new Object[varNames.length];
        for (int i = 0; i < varNames.length; i++) {
            oldValues[i] = context.getVar(varNames[i]);
        }
        // Set new values
        for (int i = 0; i < varNames.length; i++) {
            Object newVal = valueNodes[i].executeGeneric(frame);
            context.setVar(varNames[i], newVal);
        }
        try {
            // Execute body
            Object result = null;
            for (ExpressionNode node : bodyNodes) {
                result = node.executeGeneric(frame);
            }
            return result == null ? clojure.truffle.runtime.ClojureNil.INSTANCE : result;
        } finally {
            // Restore old values
            for (int i = 0; i < varNames.length; i++) {
                if (oldValues[i] != null) {
                    context.setVar(varNames[i], oldValues[i]);
                }
            }
        }
    }
}
