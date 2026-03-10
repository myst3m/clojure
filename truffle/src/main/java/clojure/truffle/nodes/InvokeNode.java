package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import clojure.truffle.ClojureContext;
import clojure.truffle.runtime.ClojureFunction;

public class InvokeNode extends ExpressionNode {

    @Child private ExpressionNode functionNode;
    @Children private final ExpressionNode[] argumentNodes;
    @Child private IndirectCallNode callNode;

    public InvokeNode(ExpressionNode functionNode, ExpressionNode[] argumentNodes) {
        this.functionNode = functionNode;
        this.argumentNodes = argumentNodes;
        this.callNode = IndirectCallNode.create();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object function = functionNode.executeGeneric(frame);

        Object[] argValues = new Object[argumentNodes.length];
        for (int i = 0; i < argumentNodes.length; i++) {
            argValues[i] = argumentNodes[i].executeGeneric(frame);
        }

        if (function instanceof ClojureFunction fn) {
            // args[0] = function itself (for closure captured values access)
            Object[] callArgs = new Object[argValues.length + 1];
            callArgs[0] = fn;
            System.arraycopy(argValues, 0, callArgs, 1, argValues.length);
            return callNode.call(fn.getCallTarget(), callArgs);
        } else if (function instanceof ClojureContext.BuiltinFunction builtin) {
            return builtin.execute(argValues);
        }

        throw new RuntimeException("Cannot invoke: " + function + " (type: " +
                (function == null ? "null" : function.getClass().getName()) + ")");
    }
}
