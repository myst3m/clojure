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
        if (exceptionClass.isInstance(e)) return true;
        // Check cause chain: Java method invocations wrap checked exceptions in RuntimeException
        Throwable cause = e.getCause();
        while (cause != null) {
            if (exceptionClass.isInstance(cause)) return true;
            cause = cause.getCause();
        }
        return false;
    }

    public Object handle(VirtualFrame frame, Throwable e) {
        // Bind the most specific matching exception
        Throwable toBindDirect = exceptionClass.isInstance(e) ? e : null;
        if (toBindDirect == null) {
            Throwable cause = e.getCause();
            while (cause != null) {
                if (exceptionClass.isInstance(cause)) { toBindDirect = cause; break; }
                cause = cause.getCause();
            }
        }
        frame.setObject(bindingSlot, toBindDirect != null ? toBindDirect : e);
        return handlerBody.executeGeneric(frame);
    }
}
