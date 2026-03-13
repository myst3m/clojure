package clojure.truffle.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;

/**
 * Wraps a Truffle NFI bound function so it can be called from Clojure.
 * Created by (native-fn lib "name" "signature").
 */
public class NativeFunction extends clojure.lang.AFn {
    private final String name;
    private final String signature;
    private final Object boundFunction;

    public NativeFunction(String name, String signature, Object boundFunction) {
        this.name = name;
        this.signature = signature;
        this.boundFunction = boundFunction;
    }

    @Override
    public Object invoke() {
        try {
            return convertResult(InteropLibrary.getUncached().execute(boundFunction));
        } catch (Exception e) {
            throw new RuntimeException("native call " + name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Object invoke(Object arg1) {
        try {
            return convertResult(InteropLibrary.getUncached().execute(boundFunction, convertArg(arg1)));
        } catch (Exception e) {
            throw new RuntimeException("native call " + name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Object invoke(Object arg1, Object arg2) {
        try {
            return convertResult(InteropLibrary.getUncached().execute(boundFunction,
                    convertArg(arg1), convertArg(arg2)));
        } catch (Exception e) {
            throw new RuntimeException("native call " + name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3) {
        try {
            return convertResult(InteropLibrary.getUncached().execute(boundFunction,
                    convertArg(arg1), convertArg(arg2), convertArg(arg3)));
        } catch (Exception e) {
            throw new RuntimeException("native call " + name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4) {
        try {
            return convertResult(InteropLibrary.getUncached().execute(boundFunction,
                    convertArg(arg1), convertArg(arg2), convertArg(arg3), convertArg(arg4)));
        } catch (Exception e) {
            throw new RuntimeException("native call " + name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {
        try {
            return convertResult(InteropLibrary.getUncached().execute(boundFunction,
                    convertArg(arg1), convertArg(arg2), convertArg(arg3),
                    convertArg(arg4), convertArg(arg5)));
        } catch (Exception e) {
            throw new RuntimeException("native call " + name + " failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4,
                         Object arg5, Object arg6) {
        try {
            return convertResult(InteropLibrary.getUncached().execute(boundFunction,
                    convertArg(arg1), convertArg(arg2), convertArg(arg3),
                    convertArg(arg4), convertArg(arg5), convertArg(arg6)));
        } catch (Exception e) {
            throw new RuntimeException("native call " + name + " failed: " + e.getMessage(), e);
        }
    }

    private Object convertArg(Object arg) {
        if (arg instanceof ClojureNil) return 0; // nil → NULL/0
        if (arg instanceof clojure.lang.Keyword kw) return kw.getName();
        if (arg instanceof Long l) return l;
        if (arg instanceof Double d) return d;
        if (arg instanceof String s) return s;
        return arg;
    }

    private Object convertResult(Object result) {
        if (result == null) return ClojureNil.INSTANCE;
        if (result instanceof Integer i) return (long) i;
        if (result instanceof Short s) return (long) s;
        if (result instanceof Byte b) return (long) b;
        if (result instanceof Float f) return (double) f;
        // NFI NativeString → Java String
        InteropLibrary interop = InteropLibrary.getUncached();
        if (interop.isString(result)) {
            try {
                return interop.asString(result);
            } catch (Exception e) {
                // fall through
            }
        }
        if (interop.isNull(result)) return ClojureNil.INSTANCE;
        return result;
    }

    public Object getBoundFunction() {
        return boundFunction;
    }

    @Override
    public String toString() {
        return "#<native-fn " + name + " " + signature + ">";
    }
}
