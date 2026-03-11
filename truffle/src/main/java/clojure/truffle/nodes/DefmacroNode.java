package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;

public class DefmacroNode extends ExpressionNode {

    private final ClojureContext context;
    private final String name;
    @Child private ExpressionNode fnNode;

    public DefmacroNode(ClojureContext context, String name, ExpressionNode fnNode) {
        this.context = context;
        this.name = name;
        this.fnNode = fnNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object fn = fnNode.executeGeneric(frame);
        context.setMacro(name, fn);
        return fn;
    }
}
