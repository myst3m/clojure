package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;

public class DefNode extends ExpressionNode {

    private final ClojureContext context;
    private final String name;
    @Child private ExpressionNode valueNode;

    public DefNode(ClojureContext context, String name, ExpressionNode valueNode) {
        this.context = context;
        this.name = name;
        this.valueNode = valueNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = valueNode.executeGeneric(frame);
        context.setVar(name, value);
        return value;
    }
}
