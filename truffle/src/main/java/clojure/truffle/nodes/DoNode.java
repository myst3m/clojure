package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.runtime.ClojureNil;

public class DoNode extends ExpressionNode {

    @Children private final ExpressionNode[] bodyNodes;

    public DoNode(ExpressionNode[] bodyNodes) {
        this.bodyNodes = bodyNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object result = ClojureNil.INSTANCE;
        for (ExpressionNode node : bodyNodes) {
            result = node.executeGeneric(frame);
        }
        return result;
    }
}
