package clojure.truffle.runtime;

import clojure.lang.AFn;
import clojure.lang.ISeq;
import clojure.lang.IPersistentMap;
import clojure.truffle.runtime.ClojureRT;

public class MultiArityFunction extends AFn {
    private final int[] arities;
    private final ClojureFunction[] functions;
    private final int variadicIndex;
    private volatile IPersistentMap meta;

    public MultiArityFunction(int[] arities, ClojureFunction[] functions, int variadicIndex) {
        this.arities = arities;
        this.functions = functions;
        this.variadicIndex = variadicIndex;
        // Set parent reference so self-calls in fn bodies dispatch through MultiArityFunction
        for (ClojureFunction fn : functions) {
            if (fn != null) fn.setMultiArityParent(this);
        }
    }

    public ClojureFunction resolve(int argCount) {
        for (int i = 0; i < arities.length; i++) {
            if (i == variadicIndex) continue;
            if (arities[i] == argCount) return functions[i];
        }
        if (variadicIndex >= 0 && argCount >= arities[variadicIndex]) {
            return functions[variadicIndex];
        }
        com.oracle.truffle.api.CompilerDirectives.transferToInterpreterAndInvalidate();
        throwArityError(argCount);
        return null; // unreachable
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private static void throwArityError(int argCount) {
        throw new RuntimeException("Wrong number of args (" + argCount + ") passed to fn");
    }

    private Object doInvoke(Object... args) {
        ClojureFunction clf = resolve(args.length);
        Object[] callArgs = new Object[args.length + 1];
        callArgs[0] = clf;
        System.arraycopy(args, 0, callArgs, 1, args.length);
        return clf.getCallTarget().call(callArgs);
    }

    @Override
    public Object invoke() { return doInvoke(); }
    @Override
    public Object invoke(Object a1) { return doInvoke(a1); }
    @Override
    public Object invoke(Object a1, Object a2) { return doInvoke(a1, a2); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3) { return doInvoke(a1, a2, a3); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4) { return doInvoke(a1, a2, a3, a4); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5) { return doInvoke(a1, a2, a3, a4, a5); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) { return doInvoke(a1, a2, a3, a4, a5, a6); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) { return doInvoke(a1, a2, a3, a4, a5, a6, a7); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19); }
    @Override
    public Object invoke(Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7, Object a8, Object a9, Object a10, Object a11, Object a12, Object a13, Object a14, Object a15, Object a16, Object a17, Object a18, Object a19, Object a20) { return doInvoke(a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, a13, a14, a15, a16, a17, a18, a19, a20); }

    @Override
    public Object applyTo(ISeq arglist) {
        Object[] args = ClojureRT.seqToArray(arglist);
        return doInvoke(args);
    }

    public int[] getArities() { return arities; }
    public ClojureFunction[] getFunctions() { return functions; }
    public int getVariadicIndex() { return variadicIndex; }
    public IPersistentMap getMeta() { return meta; }
    public void setMeta(IPersistentMap meta) { this.meta = meta; }

    @Override
    public String toString() {
        return "<fn multi-arity>";
    }
}
