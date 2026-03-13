package clojure.truffle.nodes.interop;

import clojure.truffle.nodes.ExpressionNode;
import com.oracle.truffle.api.frame.VirtualFrame;

import java.lang.reflect.Constructor;

public class JavaConstructorNode extends ExpressionNode {

    private final Class<?> clazz;
    @Children private final ExpressionNode[] argNodes;

    public JavaConstructorNode(Class<?> clazz, ExpressionNode[] argNodes) {
        this.clazz = clazz;
        this.argNodes = argNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].executeGeneric(frame);
        }

        Constructor<?> ctor = JavaInteropUtil.findConstructor(clazz, args);
        if (ctor == null) {
            throw new RuntimeException("No matching constructor for " + clazz.getName() +
                    " with " + args.length + " args");
        }

        try {
            ctor.setAccessible(true);
            Object[] coerced = JavaInteropUtil.coerceArgs(ctor, args);
            return ctor.newInstance(coerced);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException("Constructor invocation failed: " + clazz.getName() +
                    ": " + e.getMessage(), e);
        }
    }
}
