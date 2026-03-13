package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import clojure.truffle.ClojureContext;
import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.ClojureMultiMethod;
import clojure.truffle.runtime.MultiArityFunction;

public class InvokeNode extends ExpressionNode {

    @Child private ExpressionNode functionNode;
    @Children private final ExpressionNode[] argumentNodes;
    @Child private IndirectCallNode callNode;

    public InvokeNode(ExpressionNode functionNode, ExpressionNode[] argumentNodes) {
        this.functionNode = functionNode;
        this.argumentNodes = argumentNodes;
        this.callNode = IndirectCallNode.create();
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object function = functionNode.executeGeneric(frame);

        // Dereference ClojureVar to its value
        if (function instanceof clojure.truffle.runtime.ClojureVar cvar) {
            function = cvar.deref();
        }

        Object[] argValues = new Object[argumentNodes.length];
        for (int i = 0; i < argumentNodes.length; i++) {
            argValues[i] = argumentNodes[i].executeGeneric(frame);
        }

        if (function instanceof ClojureFunction fn) {
            Object[] callArgs = new Object[argValues.length + 1];
            callArgs[0] = fn;
            System.arraycopy(argValues, 0, callArgs, 1, argValues.length);
            return callNode.call(fn.getCallTarget(), callArgs);
        } else if (function instanceof MultiArityFunction maf) {
            ClojureFunction fn = maf.resolve(argValues.length);
            Object[] callArgs = new Object[argValues.length + 1];
            callArgs[0] = fn;
            System.arraycopy(argValues, 0, callArgs, 1, argValues.length);
            return callNode.call(fn.getCallTarget(), callArgs);
        } else if (function instanceof ClojureContext.BuiltinFunction builtin) {
            return invokeBuiltin(builtin, argValues);
        } else if (function instanceof ClojureMultiMethod mm) {
            return invokeMultiMethod(mm, argValues);
        } else {
            return invokeSlowPath(function, argValues, functionNode);
        }
    }

    @TruffleBoundary
    private static Object invokeBuiltin(ClojureContext.BuiltinFunction builtin, Object[] argValues) {
        try {
            return builtin.execute(argValues);
        } catch (Exception e) {
            throw new RuntimeException("in builtin '" + builtin.name() + "': " + e.getMessage(), e);
        }
    }

    @TruffleBoundary
    private static Object invokeMultiMethod(ClojureMultiMethod mm, Object[] argValues) {
        return mm.invoke(argValues);
    }

    @TruffleBoundary
    private static Object invokeSlowPath(Object function, Object[] argValues, ExpressionNode functionNode) {
        if (function instanceof clojure.lang.Keyword kw) {
            if (argValues.length < 1 || argValues.length > 2)
                throw new RuntimeException("Keyword lookup expects 1 or 2 args");
            Object map = argValues[0];
            if (map instanceof clojure.lang.ILookup lookup) {
                Object notFound = argValues.length == 2 ? argValues[1] :
                        clojure.truffle.runtime.ClojureNil.INSTANCE;
                Object val = lookup.valAt(kw, notFound);
                return val == null ? clojure.truffle.runtime.ClojureNil.INSTANCE : val;
            }
            return argValues.length == 2 ? argValues[1] :
                    clojure.truffle.runtime.ClojureNil.INSTANCE;
        }
        if (function instanceof clojure.lang.IPersistentMap m) {
            if (argValues.length < 1 || argValues.length > 2)
                throw new RuntimeException("Map lookup expects 1 or 2 args");
            Object val = m.valAt(argValues[0],
                    argValues.length == 2 ? argValues[1] : clojure.truffle.runtime.ClojureNil.INSTANCE);
            return val == null ? clojure.truffle.runtime.ClojureNil.INSTANCE : val;
        }
        if (function instanceof clojure.lang.IPersistentVector v) {
            if (argValues.length != 1)
                throw new RuntimeException("Vector lookup expects 1 arg");
            int idx = ((Number) argValues[0]).intValue();
            return v.nth(idx);
        }
        if (function instanceof clojure.lang.IPersistentSet s) {
            if (argValues.length != 1)
                throw new RuntimeException("Set lookup expects 1 arg");
            Object val = s.get(argValues[0]);
            return val == null ? clojure.truffle.runtime.ClojureNil.INSTANCE : val;
        }
        if (function instanceof clojure.lang.IFn ifn) {
            return switch (argValues.length) {
                case 0 -> ifn.invoke();
                case 1 -> ifn.invoke(argValues[0]);
                case 2 -> ifn.invoke(argValues[0], argValues[1]);
                case 3 -> ifn.invoke(argValues[0], argValues[1], argValues[2]);
                case 4 -> ifn.invoke(argValues[0], argValues[1], argValues[2], argValues[3]);
                case 5 -> ifn.invoke(argValues[0], argValues[1], argValues[2], argValues[3], argValues[4]);
                case 6 -> ifn.invoke(argValues[0], argValues[1], argValues[2], argValues[3], argValues[4], argValues[5]);
                default -> throw new RuntimeException("IFn invoke with " + argValues.length + " args not supported");
            };
        }
        if (function instanceof clojure.truffle.runtime.ClojureDeftypeInstance dt) {
            Object invokeFn = dt.getMethod("invoke");
            if (invokeFn != null) {
                Object[] fnArgs = new Object[argValues.length + 1];
                fnArgs[0] = dt;
                System.arraycopy(argValues, 0, fnArgs, 1, argValues.length);
                if (invokeFn instanceof ClojureContext.BuiltinFunction bf) {
                    return bf.execute(fnArgs);
                }
                // For ClojureFunction/MultiArityFunction in deftype invoke, use callFunction
                if (invokeFn instanceof ClojureFunction fn2) {
                    Object[] callArgs2 = new Object[fnArgs.length + 1];
                    callArgs2[0] = fn2;
                    System.arraycopy(fnArgs, 0, callArgs2, 1, fnArgs.length);
                    return fn2.getCallTarget().call(callArgs2);
                } else if (invokeFn instanceof MultiArityFunction maf2) {
                    ClojureFunction fn2 = maf2.resolve(fnArgs.length);
                    Object[] callArgs2 = new Object[fnArgs.length + 1];
                    callArgs2[0] = fn2;
                    System.arraycopy(fnArgs, 0, callArgs2, 1, fnArgs.length);
                    return fn2.getCallTarget().call(callArgs2);
                }
            }
        }

        String fnDesc = (functionNode instanceof SymbolNode sn) ? "symbol=" + sn.getName() : functionNode.getClass().getSimpleName();
        throw new RuntimeException("Cannot invoke: " + function + " (type: " +
                (function == null ? "null" : function.getClass().getName()) + ", source: " + fnDesc + ")");
    }
}
