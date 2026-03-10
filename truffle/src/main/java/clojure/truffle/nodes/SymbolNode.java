package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;

public class SymbolNode extends ExpressionNode {
    private final ClojureContext context;
    private final String name;

    public SymbolNode(ClojureContext context, String name) {
        this.context = context;
        this.name = name;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = context.getVar(name);
        if (value == null) {
            throw new RuntimeException("Unable to resolve symbol: " + name + " in this context");
        }
        return value;
    }
}
