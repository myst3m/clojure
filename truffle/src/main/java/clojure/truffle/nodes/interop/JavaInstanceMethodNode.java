package clojure.truffle.nodes.interop;

import clojure.truffle.nodes.ExpressionNode;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
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
        return invokeMethod(target, methodName, args);
    }

    private static boolean isAccessibleClass(Class<?> clazz) {
        int mod = clazz.getModifiers();
        if (!java.lang.reflect.Modifier.isPublic(mod)) return false;
        Module module = clazz.getModule();
        if (module != null && module.isNamed()) {
            String pkg = clazz.getPackageName();
            // Check if the package is exported to everyone (or at least to unnamed modules)
            if (!module.isExported(pkg)) return false;
        }
        return true;
    }

    @TruffleBoundary
    private static Object invokeMethod(Object target, String methodName, Object[] args) {
        Class<?> clazz = target.getClass();
        Method method = JavaInteropUtil.findMethod(clazz, methodName, args, false);
        // Prefer interface/superclass method when declaring class is non-public
        // or in a non-exported module package (avoids InaccessibleObjectException)
        if (method == null || !isAccessibleClass(method.getDeclaringClass())) {
            Method ifaceMethod = null;
            for (Class<?> iface : JavaInteropUtil.getAllInterfaces(clazz)) {
                ifaceMethod = JavaInteropUtil.findMethod(iface, methodName, args, false);
                if (ifaceMethod != null) break;
            }
            if (ifaceMethod != null) method = ifaceMethod;
            // Also check public superclasses
            if (method == null || !isAccessibleClass(method.getDeclaringClass())) {
                for (Class<?> sup = clazz.getSuperclass(); sup != null; sup = sup.getSuperclass()) {
                    if (isAccessibleClass(sup)) {
                        Method supMethod = JavaInteropUtil.findMethod(sup, methodName, args, false);
                        if (supMethod != null) { method = supMethod; break; }
                    }
                }
            }
        }
        if (method == null && java.lang.reflect.Proxy.isProxyClass(clazz)) {
            for (Class<?> iface : clazz.getInterfaces()) {
                method = JavaInteropUtil.findMethod(iface, methodName, args, false);
                if (method != null) break;
            }
        }
        if (method == null && args.length == 0) {
            try {
                java.lang.reflect.Field field = JavaInteropUtil.findField(clazz, methodName);
                if (field != null) {
                    field.setAccessible(true);
                    return JavaInteropUtil.wrapResult(field.get(target));
                }
            } catch (Exception ignored) {}
        }
        // For ClojureDeftypeInstance, try field access
        if (method == null && target instanceof clojure.truffle.runtime.ClojureDeftypeInstance dti) {
            if (args.length == 0 && dti.hasField(methodName)) {
                return dti.getField(methodName);
            }
            // Also check deftype methods
            Object methodFn = dti.getMethod(methodName);
            if (methodFn instanceof clojure.lang.IFn fn) {
                Object[] fullArgs = new Object[args.length + 1];
                fullArgs[0] = target;
                System.arraycopy(args, 0, fullArgs, 1, args.length);
                return fn.applyTo(clojure.lang.RT.seq(fullArgs));
            }
        }
        // For ClojureReified, try method map
        if (method == null && target instanceof clojure.truffle.runtime.ClojureReified reified) {
            Object methodFn = null;
            // Try type-suffixed lookup first (for overloaded methods like hinted(int) vs hinted(String))
            if (args.length > 0) {
                StringBuilder typedKey = new StringBuilder(methodName);
                for (Object arg : args) {
                    if (arg instanceof Integer || arg instanceof Long) {
                        typedKey.append("__int");
                    } else if (arg instanceof String) {
                        typedKey.append("__String");
                    } else if (arg instanceof Double || arg instanceof Float) {
                        typedKey.append("__double");
                    } else if (arg != null) {
                        typedKey.append("__").append(arg.getClass().getSimpleName());
                    }
                }
                methodFn = reified.getMethod(typedKey.toString());
            }
            if (methodFn == null) methodFn = reified.getMethod(methodName);
            if (methodFn instanceof clojure.lang.IFn fn) {
                Object[] fullArgs = new Object[args.length + 1];
                fullArgs[0] = target;
                System.arraycopy(args, 0, fullArgs, 1, args.length);
                return fn.applyTo(clojure.lang.RT.seq(fullArgs));
            }
        }
        if (method == null) {
            String argWord = args.length == 1 ? "arg" : "args";
            throw new IllegalArgumentException("No matching method " + methodName +
                    " found taking " + args.length + " " + argWord + " for " + clazz.getName());
        }
        try {
            try { method.setAccessible(true); } catch (Exception ignored) {}
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
