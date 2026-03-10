package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import clojure.truffle.ClojureContext;
import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.ClojureMultiMethod;
import clojure.truffle.runtime.MultiArityFunction;

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
            Object[] callArgs = new Object[argValues.length + 1];
            callArgs[0] = fn;
            System.arraycopy(argValues, 0, callArgs, 1, argValues.length);
            return callNode.call(fn.getCallTarget(), callArgs);
        } else if (function instanceof MultiArityFunction maf) {
            ClojureFunction fn = maf.resolve(argValues.length);
            Object[] callArgs = new Object[argValues.length + 1];
            callArgs[0] = fn;
            System.arraycopy(argValues, 0, callArgs, 1, argValues.length);
            return callNode.call(fn.getCallTarget(), callArgs);
        } else if (function instanceof ClojureContext.BuiltinFunction builtin) {
            return builtin.execute(argValues);
        } else if (function instanceof ClojureMultiMethod mm) {
            return mm.invoke(argValues);
        } else if (function instanceof clojure.lang.Keyword kw) {
            // Keywords as functions: (:key map) → (get map :key)
            if (argValues.length < 1 || argValues.length > 2)
                throw new RuntimeException("Keyword lookup expects 1 or 2 args");
            Object map = argValues[0];
            if (map instanceof clojure.lang.ILookup lookup) {
                Object notFound = argValues.length == 2 ? argValues[1] :
                        clojure.truffle.runtime.ClojureNil.INSTANCE;
                Object val = lookup.valAt(kw, notFound);
                return val == null ? clojure.truffle.runtime.ClojureNil.INSTANCE : val;
            }
            return argValues.length == 2 ? argValues[1] :
                    clojure.truffle.runtime.ClojureNil.INSTANCE;
        }

        throw new RuntimeException("Cannot invoke: " + function + " (type: " +
                (function == null ? "null" : function.getClass().getName()) + ")");
    }
}
