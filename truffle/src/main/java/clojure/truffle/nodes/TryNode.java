package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.runtime.ClojureNil;

public class TryNode extends ExpressionNode {

    @Child private ExpressionNode bodyNode;
    @Children private final CatchHandlerNode[] catchHandlers;
    @Child private ExpressionNode finallyNode;  // may be null

    public TryNode(ExpressionNode bodyNode, CatchHandlerNode[] catchHandlers,
                   ExpressionNode finallyNode) {
        this.bodyNode = bodyNode;
        this.catchHandlers = catchHandlers;
        this.finallyNode = finallyNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object result;
        try {
            result = bodyNode.executeGeneric(frame);
        } catch (Throwable e) {
            // Skip Truffle control flow exceptions
            if (e instanceof com.oracle.truffle.api.nodes.ControlFlowException) throw e;

            result = ClojureNil.INSTANCE;
            boolean caught = false;
            for (CatchHandlerNode handler : catchHandlers) {
                if (handler.matches(e)) {
                    result = handler.handle(frame, e);
                    caught = true;
                    break;
                }
            }
            if (!caught) {
                if (finallyNode != null) finallyNode.executeGeneric(frame);
                throw e;
            }
        }
        if (finallyNode != null) {
            finallyNode.executeGeneric(frame);
        }
        return result;
    }
}
