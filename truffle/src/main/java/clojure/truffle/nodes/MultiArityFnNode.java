package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.MultiArityFunction;

public class MultiArityFnNode extends ExpressionNode {

    @Children private final FnNode[] arityNodes;
    private final int[] arities;
    private final int variadicIndex;

    public MultiArityFnNode(String name, FnNode[] arityNodes, int[] arities, int variadicIndex) {
        this.arityNodes = arityNodes;
        this.arities = arities;
        this.variadicIndex = variadicIndex;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        ClojureFunction[] fns = new ClojureFunction[arityNodes.length];
        for (int i = 0; i < arityNodes.length; i++) {
            fns[i] = (ClojureFunction) arityNodes[i].executeGeneric(frame);
        }
        return new MultiArityFunction(arities, fns, variadicIndex);
    }
}
