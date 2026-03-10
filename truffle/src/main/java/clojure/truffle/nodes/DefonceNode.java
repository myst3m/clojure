package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;

public class DefonceNode extends ExpressionNode {
    private final ClojureContext context;
    private final String name;
    @Child private ExpressionNode valueNode;

    public DefonceNode(ClojureContext context, String name, ExpressionNode valueNode) {
        this.context = context;
        this.name = name;
        this.valueNode = valueNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object existing = context.getVar(name);
        if (existing != null) {
            return existing;
        }
        Object value = valueNode.executeGeneric(frame);
        context.setVar(name, value);
        return value;
    }
}
