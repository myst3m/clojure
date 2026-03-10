package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.runtime.ClojureNil;

public class NilNode extends ExpressionNode {
    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return ClojureNil.INSTANCE;
    }
}
