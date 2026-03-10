package clojure.truffle.nodes.interop;

import clojure.truffle.nodes.ExpressionNode;
import com.oracle.truffle.api.frame.VirtualFrame;

import java.lang.reflect.Method;

public class JavaStaticMethodNode extends ExpressionNode {

    private final Class<?> clazz;
    private final String methodName;
    @Children private final ExpressionNode[] argNodes;

    public JavaStaticMethodNode(Class<?> clazz, String methodName, ExpressionNode[] argNodes) {
        this.clazz = clazz;
        this.methodName = methodName;
        this.argNodes = argNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].executeGeneric(frame);
        }

        Method method = JavaInteropUtil.findMethod(clazz, methodName, args, true);
        if (method == null) {
            throw new RuntimeException("No such static method: " + clazz.getName() + "/" +
                    methodName + " with " + args.length + " args");
        }

        try {
            Object[] coerced = JavaInteropUtil.coerceArgs(args, method.getParameterTypes());
            Object result = method.invoke(null, coerced);
            return JavaInteropUtil.wrapResult(result);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException("Static method invocation failed: " + clazz.getName() +
                    "/" + methodName + ": " + e.getMessage(), e);
        }
    }
}
