package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;
import clojure.truffle.nodes.interop.JavaInteropUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * (proxy [Interface1 Interface2] []
 *   (method1 [this arg] body)
 *   (method2 [this arg1 arg2] body))
 *
 * Creates a proxy that implements the specified interfaces and/or extends a class,
 * dispatching method calls to the provided Clojure functions.
 *
 * For interfaces only: uses java.lang.reflect.Proxy
 * For class extension: uses ASM to generate a subclass at runtime
 */
public class ProxyNode extends ExpressionNode {
    private final ClojureContext context;
    private final Class<?>[] interfaces;
    private final String[] methodNames;
    @Children private final ExpressionNode[] methodNodes;

    public ProxyNode(ClojureContext context, Class<?>[] interfaces,
                     String[] methodNames, ExpressionNode[] methodNodes) {
        this.context = context;
        this.interfaces = interfaces;
        this.methodNames = methodNames;
        this.methodNodes = methodNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Map<String, Object> methods = new LinkedHashMap<>();
        for (int i = 0; i < methodNames.length; i++) {
            methods.put(methodNames[i], methodNodes[i].executeGeneric(frame));
        }

        // Separate superclass from interfaces
        Class<?> superClass = null;
        List<Class<?>> ifaceList = new ArrayList<>();
        for (Class<?> c : interfaces) {
            if (c.isInterface()) {
                ifaceList.add(c);
            } else {
                if (superClass != null) {
                    throw new RuntimeException("proxy: can only extend one class, got both " +
                            superClass.getName() + " and " + c.getName());
                }
                superClass = c;
            }
        }

        if (superClass != null) {
            return createClassProxy(superClass, ifaceList.toArray(new Class<?>[0]), methods);
        } else {
            return createInterfaceProxy(ifaceList.toArray(new Class<?>[0]), methods);
        }
    }

    private Object createInterfaceProxy(Class<?>[] ifaces, Map<String, Object> methods) {
        InvocationHandler handler = (proxy, method, args) -> {
            return invokeProxyMethod(proxy, method, args, methods);
        };
        return java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                ifaces,
                handler
        );
    }

    private Object createClassProxy(Class<?> superClass, Class<?>[] ifaces, Map<String, Object> methods) {
        try {
            return ClassProxyGenerator.createProxy(superClass, ifaces, methods, context);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create class proxy for " + superClass.getName() + ": " + e.getMessage(), e);
        }
    }

    private Object invokeProxyMethod(Object proxy, Method method, Object[] args, Map<String, Object> methods) {
        String name = method.getName();
        Object fn = methods.get(name);
        if (fn != null) {
            Object[] clojureArgs;
            if (args == null) {
                clojureArgs = new Object[]{proxy};
            } else {
                clojureArgs = new Object[args.length + 1];
                clojureArgs[0] = proxy;
                for (int i = 0; i < args.length; i++) {
                    clojureArgs[i + 1] = JavaInteropUtil.wrapResult(args[i]);
                }
            }
            Object result = context.callFunction(fn, clojureArgs);
            Class<?> returnType = method.getReturnType();
            if (returnType == void.class || returnType == Void.class) return null;
            if (result instanceof clojure.truffle.runtime.ClojureNil) return null;
            return JavaInteropUtil.coerce(result, returnType);
        }
        // Default implementations for Object methods
        if (name.equals("toString")) return "proxy[" + superClassAndIfacesString() + "]";
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("equals")) return proxy == (args != null ? args[0] : null);
        throw new UnsupportedOperationException("No implementation for method: " + name);
    }

    private String superClassAndIfacesString() {
        return String.join(",",
                java.util.Arrays.stream(interfaces).map(Class::getSimpleName).toArray(String[]::new));
    }
}
