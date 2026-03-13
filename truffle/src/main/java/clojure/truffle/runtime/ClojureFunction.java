package clojure.truffle.runtime;

import clojure.lang.AFn;
import clojure.lang.IFn;
import clojure.lang.ISeq;
import clojure.lang.IPersistentMap;
import clojure.lang.PersistentArrayMap;
import clojure.lang.RT;
import com.oracle.truffle.api.CallTarget;

public class ClojureFunction extends AFn {
    private final String name;
    private final CallTarget callTarget;
    private final Object[] capturedValues;
    private volatile Object multiArityParent; // set when this is part of a MultiArityFunction
    private volatile IPersistentMap meta;

    public ClojureFunction(String name, CallTarget callTarget, Object[] capturedValues) {
        this.name = name;
        this.callTarget = callTarget;
        this.capturedValues = capturedValues;
    }

    private Object doInvoke(Object... args) {
        // If this is part of a MultiArityFunction, dispatch through it
        if (multiArityParent instanceof MultiArityFunction maf) {
            ClojureFunction resolved = maf.resolve(args.length);
            Object[] callArgs = new Object[args.length + 1];
            callArgs[0] = resolved;
            System.arraycopy(args, 0, callArgs, 1, args.length);
            return resolved.getCallTarget().call(callArgs);
        }
        Object[] callArgs = new Object[args.length + 1];
        callArgs[0] = this;
        System.arraycopy(args, 0, callArgs, 1, args.length);
        return callTarget.call(callArgs);
    }

    @Override
    public Object invoke() {
        return doInvoke();
    }

    @Override
    public Object invoke(Object arg1) {
        return doInvoke(arg1);
    }

    @Override
    public Object invoke(Object arg1, Object arg2) {
        return doInvoke(arg1, arg2);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3) {
        return doInvoke(arg1, arg2, arg3);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4) {
        return doInvoke(arg1, arg2, arg3, arg4);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16, arg17);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16, arg17, arg18);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16, arg17, arg18, arg19);
    }

    @Override
    public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7, Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14, Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20) {
        return doInvoke(arg1, arg2, arg3, arg4, arg5, arg6, arg7, arg8, arg9, arg10, arg11, arg12, arg13, arg14, arg15, arg16, arg17, arg18, arg19, arg20);
    }

    @Override
    public Object applyTo(ISeq arglist) {
        Object[] args = RT.seqToArray(arglist);
        return doInvoke(args);
    }

    public void setMultiArityParent(Object parent) {
        this.multiArityParent = parent;
    }

    public Object getMultiArityParent() {
        return multiArityParent;
    }

    public String getName() {
        return name;
    }

    public CallTarget getCallTarget() {
        return callTarget;
    }

    public Object[] getCapturedValues() {
        return capturedValues;
    }

    public int getArity() {
        if (callTarget instanceof com.oracle.truffle.api.impl.DefaultCallTarget dct) {
            com.oracle.truffle.api.nodes.RootNode root = dct.getRootNode();
            if (root instanceof clojure.truffle.nodes.FnBodyNode fb) {
                return fb.getParamCount();
            }
        }
        return -1; // unknown
    }

    public boolean isVariadic() {
        if (callTarget instanceof com.oracle.truffle.api.impl.DefaultCallTarget dct) {
            com.oracle.truffle.api.nodes.RootNode root = dct.getRootNode();
            if (root instanceof clojure.truffle.nodes.FnBodyNode fb) {
                return fb.getVariadicSlot() >= 0;
            }
        }
        return false;
    }

    public IPersistentMap getMeta() {
        return meta;
    }

    public void setMeta(IPersistentMap meta) {
        this.meta = meta;
    }

    @Override
    public String toString() {
        return "<fn" + (name != null ? " " + name : "") + ">";
    }
}
