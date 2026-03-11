package clojure.truffle.nodes.interop;

import clojure.truffle.nodes.ExpressionNode;
import com.oracle.truffle.api.frame.VirtualFrame;

import java.lang.reflect.Method;

public class JavaInstanceMethodNode extends ExpressionNode {

    private final String methodName;
    @Child private ExpressionNode targetNode;
    @Children private final ExpressionNode[] argNodes;

    public JavaInstanceMethodNode(String methodName, ExpressionNode targetNode, ExpressionNode[] argNodes) {
        this.methodName = methodName;
        this.targetNode = targetNode;
        this.argNodes = argNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object target = targetNode.executeGeneric(frame);
        Object[] args = new Object[argNodes.length];
        for (int i = 0; i < argNodes.length; i++) {
            args[i] = argNodes[i].executeGeneric(frame);
        }

        Class<?> clazz = target.getClass();
        Method method = JavaInteropUtil.findMethod(clazz, methodName, args, false);
        if (method == null) {
            throw new RuntimeException("No such method: " + clazz.getName() + "." + methodName
                    + " with " + args.length + " args");
        }

        try {
            Object[] coerced = JavaInteropUtil.coerceArgs(method, args);
            Object result = method.invoke(target, coerced);
            return JavaInteropUtil.wrapResult(result);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException("Method invocation failed: " + methodName + " on " +
                    clazz.getName() + ": " + e.getMessage(), e);
        }
    }
}
