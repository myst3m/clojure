package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;
import clojure.truffle.runtime.ClojureVar;

/**
 * (var symbol) special form - returns the Var object itself, not its value.
 * This is what #'symbol expands to via LispReader.
 */
public class VarNode extends ExpressionNode {
    private final ClojureContext context;
    private final String name;

    public VarNode(ClojureContext context, String name) {
        this.context = context;
        this.name = name;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return context.getOrCreateVar(name);
    }
}
