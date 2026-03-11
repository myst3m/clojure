package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;
import clojure.truffle.nodes.interop.JavaInteropUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * (proxy [Interface1 Interface2] []
 *   (method1 [this arg] body)
 *   (method2 [this arg1 arg2] body))
 *
 * Creates a java.lang.reflect.Proxy that implements the specified interfaces,
 * dispatching method calls to the provided Clojure functions.
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

        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            Object fn = methods.get(name);
            if (fn != null) {
                // Clojure proxy methods include 'this' as first arg
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
                // Coerce result back to Java type if needed
                Class<?> returnType = method.getReturnType();
                if (returnType == void.class || returnType == Void.class) return null;
                if (result instanceof clojure.truffle.runtime.ClojureNil) return null;
                return JavaInteropUtil.coerce(result, returnType);
            }
            // Default implementations for Object methods
            if (name.equals("toString")) return "proxy[" + String.join(",",
                    java.util.Arrays.stream(interfaces).map(Class::getSimpleName).toArray(String[]::new)) + "]";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == (args != null ? args[0] : null);
            throw new UnsupportedOperationException("No implementation for method: " + name);
        };

        return java.lang.reflect.Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                interfaces,
                handler
        );
    }
}
