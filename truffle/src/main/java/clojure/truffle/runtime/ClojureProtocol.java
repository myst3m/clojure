package clojure.truffle.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClojureProtocol {

    private final String name;
    private final List<String> methodNames;
    // Type key -> { methodName -> implementation fn }
    private final ConcurrentHashMap<Object, Map<String, Object>> implementations = new ConcurrentHashMap<>();

    public ClojureProtocol(String name, List<String> methodNames) {
        this.name = name;
        this.methodNames = methodNames;
    }

    public String getName() { return name; }
    public List<String> getMethodNames() { return methodNames; }

    public void extend(Object typeKey, Map<String, Object> methods) {
        implementations.put(typeKey, methods);
    }

    public Object findMethod(String methodName, Object target) {
        // Reified objects carry their own method implementations
        if (target instanceof ClojureReified reified) {
            Object fn = reified.getMethod(methodName);
            if (fn != null) return fn;
        }
        if (target instanceof ClojureDeftypeInstance inst) {
            Map<String, Object> methods = implementations.get(inst.getTypeName());
            if (methods != null) {
                Object fn = methods.get(methodName);
                if (fn != null) return fn;
            }
        }
        // Try by Java class (ClojureNil maps to Void.class like null)
        Class<?> clazz = (target == null || target instanceof ClojureNil) ? Void.class : target.getClass();
        while (clazz != null) {
            Map<String, Object> methods = implementations.get(clazz);
            if (methods != null) {
                Object fn = methods.get(methodName);
                if (fn != null) return fn;
            }
            // Check interfaces
            for (Class<?> iface : clazz.getInterfaces()) {
                Map<String, Object> methods2 = implementations.get(iface);
                if (methods2 != null) {
                    Object fn = methods2.get(methodName);
                    if (fn != null) return fn;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    public Object resolve(String methodName, Object typeKey) {
        Map<String, Object> methods = implementations.get(typeKey);
        if (methods != null) return methods.get(methodName);
        return null;
    }

    public boolean hasImplementation(Object target) {
        if (target instanceof ClojureReified reified) {
            // Check if reified has any of this protocol's methods
            for (String methodName : methodNames) {
                if (reified.hasMethod(methodName)) return true;
            }
            return false;
        }
        if (target instanceof ClojureDeftypeInstance inst) {
            return implementations.containsKey(inst.getTypeName());
        }
        Class<?> clazz = (target == null || target instanceof ClojureNil) ? Void.class : target.getClass();
        while (clazz != null) {
            if (implementations.containsKey(clazz)) return true;
            for (Class<?> iface : clazz.getInterfaces()) {
                if (implementations.containsKey(iface)) return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    public boolean hasImplementationForType(Object typeKey) {
        if (implementations.containsKey(typeKey)) return true;
        // If typeKey is a String (deftype name), check directly
        if (typeKey instanceof String) return implementations.containsKey(typeKey);
        // If typeKey is a Class, check class hierarchy
        if (typeKey instanceof Class<?> clazz) {
            while (clazz != null) {
                if (implementations.containsKey(clazz)) return true;
                for (Class<?> iface : clazz.getInterfaces()) {
                    if (implementations.containsKey(iface)) return true;
                }
                clazz = clazz.getSuperclass();
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "<protocol " + name + ">";
    }
}
