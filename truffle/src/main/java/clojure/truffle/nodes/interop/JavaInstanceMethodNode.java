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
        // For non-public classes (e.g. Collections$UnmodifiableMap), search public interfaces
        if (method == null || !java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            Method ifaceMethod = null;
            for (Class<?> iface : JavaInteropUtil.getAllInterfaces(clazz)) {
                ifaceMethod = JavaInteropUtil.findMethod(iface, methodName, args, false);
                if (ifaceMethod != null) break;
            }
            if (ifaceMethod != null) method = ifaceMethod;
        }
        // For Proxy objects, search interfaces if method not found on proxy class
        if (method == null && java.lang.reflect.Proxy.isProxyClass(clazz)) {
            for (Class<?> iface : clazz.getInterfaces()) {
                method = JavaInteropUtil.findMethod(iface, methodName, args, false);
                if (method != null) break;
            }
        }
        if (method == null && args.length == 0) {
            // Fall back to field access: (.name obj) may be a public field
            try {
                java.lang.reflect.Field field = JavaInteropUtil.findField(clazz, methodName);
                if (field != null) {
                    field.setAccessible(true);
                    return JavaInteropUtil.wrapResult(field.get(target));
                }
            } catch (Exception ignored) {}
        }
        if (method == null) {
            throw new RuntimeException("No such method: " + clazz.getName() + "." + methodName
                    + " with " + args.length + " args");
        }

        try {
            method.setAccessible(true);
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
