package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

public class CatchHandlerNode extends Node {

    private final Class<? extends Throwable> exceptionClass;
    private final int bindingSlot;
    @Child private ExpressionNode handlerBody;

    public CatchHandlerNode(Class<? extends Throwable> exceptionClass,
                            int bindingSlot, ExpressionNode handlerBody) {
        this.exceptionClass = exceptionClass;
        this.bindingSlot = bindingSlot;
        this.handlerBody = handlerBody;
    }

    public boolean matches(Throwable e) {
        return exceptionClass.isInstance(e);
    }

    public Object handle(VirtualFrame frame, Throwable e) {
        frame.setObject(bindingSlot, e);
        return handlerBody.executeGeneric(frame);
    }
}
