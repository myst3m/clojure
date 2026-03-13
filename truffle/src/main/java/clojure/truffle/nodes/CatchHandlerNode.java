package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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

    @TruffleBoundary
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
        frame.setObject(bindingSlot, findMatchingException(e));
        return handlerBody.executeGeneric(frame);
    }

    @TruffleBoundary
    private Throwable findMatchingException(Throwable e) {
        if (exceptionClass.isInstance(e)) return e;
        Throwable cause = e.getCause();
        while (cause != null) {
            if (exceptionClass.isInstance(cause)) return cause;
            cause = cause.getCause();
        }
        return e;
    }
}
