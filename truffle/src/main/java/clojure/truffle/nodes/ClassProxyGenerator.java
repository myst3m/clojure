package clojure.truffle.nodes;

import clojure.truffle.ClojureContext;
import clojure.truffle.nodes.interop.JavaInteropUtil;
import clojure.truffle.runtime.ClojureNil;

import clojure.asm.*;
import clojure.asm.commons.GeneratorAdapter;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates proxy subclasses at runtime using ASM bytecode generation.
 * Used when (proxy) extends a class (not just interfaces).
 *
 * Each generated class has:
 * - A static Map field "__methods" to hold the method implementations
 * - A static ClojureContext field "__context" for function invocation
 * - A no-arg constructor calling super()
 * - Overridden methods that delegate to Clojure functions
 */
public class ClassProxyGenerator {

    private static final AtomicLong PROXY_COUNTER = new AtomicLong(0);
    private static final ConcurrentHashMap<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static Object createProxy(Class<?> superClass, Class<?>[] ifaces,
                                      Map<String, Object> methods, ClojureContext context) throws Exception {

        // Generate a unique proxy class for this superclass + interfaces combination
        String cacheKey = superClass.getName() + ":" + String.join(",",
                java.util.Arrays.stream(ifaces).map(Class::getName).toArray(String[]::new));

        long id = PROXY_COUNTER.getAndIncrement();
        String proxyInternalName = "clojure/truffle/proxy/Proxy$" + id;
        String proxyClassName = proxyInternalName.replace('/', '.');

        byte[] bytecode = generateBytecode(proxyInternalName, superClass, ifaces, methods);

        // Define the class using a custom classloader
        ProxyClassLoader loader = new ProxyClassLoader(
                Thread.currentThread().getContextClassLoader(), proxyClassName, bytecode);
        Class<?> proxyClass = loader.loadClass(proxyClassName);

        // Set the static fields
        proxyClass.getField("__methods").set(null, methods);
        proxyClass.getField("__context").set(null, context);

        // Create instance using no-arg constructor
        Constructor<?> ctor = proxyClass.getDeclaredConstructor();
        return ctor.newInstance();
    }

    private static byte[] generateBytecode(String proxyInternalName, Class<?> superClass,
                                            Class<?>[] ifaces, Map<String, Object> methods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

        String superInternalName = Type.getInternalName(superClass);
        String[] ifaceNames = new String[ifaces.length];
        for (int i = 0; i < ifaces.length; i++) {
            ifaceNames[i] = Type.getInternalName(ifaces[i]);
        }

        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                proxyInternalName, null, superInternalName, ifaceNames);

        // Static fields: __methods (Map) and __context (ClojureContext)
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "__methods", "Ljava/util/Map;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "__context", "Lclojure/truffle/ClojureContext;", null, null).visitEnd();

        // No-arg constructor: super()
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, superInternalName, "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // Generate method overrides
        for (String methodName : methods.keySet()) {
            Method target = findOverridableMethod(superClass, ifaces, methodName);
            if (target != null) {
                generateMethodOverride(cw, proxyInternalName, target, methodName);
            }
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Method findOverridableMethod(Class<?> superClass, Class<?>[] ifaces, String name) {
        // Search superclass methods
        for (Method m : superClass.getMethods()) {
            if (m.getName().equals(name) && !Modifier.isFinal(m.getModifiers())) {
                return m;
            }
        }
        // Search protected methods
        Class<?> c = superClass;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && Modifier.isProtected(m.getModifiers())
                        && !Modifier.isFinal(m.getModifiers())) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        // Search interfaces
        for (Class<?> iface : ifaces) {
            for (Method m : iface.getMethods()) {
                if (m.getName().equals(name)) return m;
            }
        }
        return null;
    }

    /**
     * Generate a method override that:
     * 1. Gets the __methods map and __context
     * 2. Looks up the Clojure function for this method name
     * 3. Builds an args array with [this, arg1, arg2, ...]
     * 4. Calls context.callFunction(fn, args)
     * 5. Coerces and returns the result
     */
    private static void generateMethodOverride(ClassWriter cw, String proxyInternalName,
                                                Method target, String methodName) {
        String desc = Type.getMethodDescriptor(target);
        Class<?>[] paramTypes = target.getParameterTypes();
        Class<?> returnType = target.getReturnType();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, target.getName(), desc, null, null);
        mv.visitCode();

        // Get __methods static field → Map
        mv.visitFieldInsn(Opcodes.GETSTATIC, proxyInternalName, "__methods", "Ljava/util/Map;");
        // Get method name
        mv.visitLdcInsn(methodName);
        // Map.get(methodName)
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        // Store fn in local
        int fnLocal = paramTypes.length + 1;
        mv.visitVarInsn(Opcodes.ASTORE, fnLocal);

        // Get __context
        mv.visitFieldInsn(Opcodes.GETSTATIC, proxyInternalName, "__context",
                "Lclojure/truffle/ClojureContext;");
        int ctxLocal = fnLocal + 1;
        mv.visitVarInsn(Opcodes.ASTORE, ctxLocal);

        // Build args array: [this, arg1, arg2, ...]
        int totalArgs = paramTypes.length + 1; // +1 for this
        pushInt(mv, totalArgs);
        mv.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        int arrLocal = ctxLocal + 1;
        mv.visitVarInsn(Opcodes.ASTORE, arrLocal);

        // args[0] = this
        mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
        pushInt(mv, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitInsn(Opcodes.AASTORE);

        // args[i+1] = param_i (boxed)
        int localIdx = 1;
        for (int i = 0; i < paramTypes.length; i++) {
            mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
            pushInt(mv, i + 1);
            loadAndBox(mv, paramTypes[i], localIdx);
            mv.visitInsn(Opcodes.AASTORE);
            localIdx += (paramTypes[i] == long.class || paramTypes[i] == double.class) ? 2 : 1;
        }

        // context.callFunction(fn, args)
        mv.visitVarInsn(Opcodes.ALOAD, ctxLocal);
        mv.visitVarInsn(Opcodes.ALOAD, fnLocal);
        mv.visitVarInsn(Opcodes.ALOAD, arrLocal);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "clojure/truffle/ClojureContext",
                "callFunction", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);

        // Handle return type
        if (returnType == void.class) {
            mv.visitInsn(Opcodes.POP);
            mv.visitInsn(Opcodes.RETURN);
        } else if (returnType.isPrimitive()) {
            unboxReturn(mv, returnType);
        } else {
            mv.visitTypeInsn(Opcodes.CHECKCAST, Type.getInternalName(returnType));
            mv.visitInsn(Opcodes.ARETURN);
        }

        mv.visitMaxs(0, 0); // Let COMPUTE_FRAMES handle it
        mv.visitEnd();
    }

    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    private static void loadAndBox(MethodVisitor mv, Class<?> type, int localIdx) {
        if (type == int.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIdx);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
                    "(I)Ljava/lang/Integer;", false);
        } else if (type == long.class) {
            mv.visitVarInsn(Opcodes.LLOAD, localIdx);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                    "(J)Ljava/lang/Long;", false);
        } else if (type == double.class) {
            mv.visitVarInsn(Opcodes.DLOAD, localIdx);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
                    "(D)Ljava/lang/Double;", false);
        } else if (type == float.class) {
            mv.visitVarInsn(Opcodes.FLOAD, localIdx);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
                    "(F)Ljava/lang/Float;", false);
        } else if (type == boolean.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIdx);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                    "(Z)Ljava/lang/Boolean;", false);
        } else if (type == byte.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIdx);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf",
                    "(B)Ljava/lang/Byte;", false);
        } else if (type == short.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIdx);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf",
                    "(S)Ljava/lang/Short;", false);
        } else if (type == char.class) {
            mv.visitVarInsn(Opcodes.ILOAD, localIdx);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf",
                    "(C)Ljava/lang/Character;", false);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, localIdx);
        }
    }

    private static void unboxReturn(MethodVisitor mv, Class<?> type) {
        if (type == int.class) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I", false);
            mv.visitInsn(Opcodes.IRETURN);
        } else if (type == long.class) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J", false);
            mv.visitInsn(Opcodes.LRETURN);
        } else if (type == double.class) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D", false);
            mv.visitInsn(Opcodes.DRETURN);
        } else if (type == float.class) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Number");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F", false);
            mv.visitInsn(Opcodes.FRETURN);
        } else if (type == boolean.class) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Boolean");
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false);
            mv.visitInsn(Opcodes.IRETURN);
        } else {
            mv.visitInsn(Opcodes.ARETURN);
        }
    }

    /**
     * Custom classloader to define the proxy class.
     */
    private static class ProxyClassLoader extends ClassLoader {
        private final String className;
        private final byte[] bytecode;

        ProxyClassLoader(ClassLoader parent, String className, byte[] bytecode) {
            super(parent);
            this.className = className;
            this.bytecode = bytecode;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (name.equals(className)) {
                return defineClass(name, bytecode, 0, bytecode.length);
            }
            return super.findClass(name);
        }
    }
}
