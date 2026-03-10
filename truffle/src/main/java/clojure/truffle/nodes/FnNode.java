package clojure.truffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.runtime.ClojureFunction;

public class FnNode extends ExpressionNode {
    private final CallTarget callTarget;
    private final String name;
    private final int[] outerCaptureSlots;

    public FnNode(String name, CallTarget callTarget, int[] outerCaptureSlots) {
        this.name = name;
        this.callTarget = callTarget;
        this.outerCaptureSlots = outerCaptureSlots;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] captured = null;
        if (outerCaptureSlots != null && outerCaptureSlots.length > 0) {
            captured = new Object[outerCaptureSlots.length];
            for (int i = 0; i < outerCaptureSlots.length; i++) {
                captured[i] = frame.getObject(outerCaptureSlots[i]);
            }
        }
        return new ClojureFunction(name, callTarget, captured);
    }
}
