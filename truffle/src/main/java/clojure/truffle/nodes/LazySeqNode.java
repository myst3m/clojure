package clojure.truffle.nodes;

import clojure.truffle.runtime.ClojureFunction;
import clojure.truffle.runtime.LazySeq;
import com.oracle.truffle.api.frame.VirtualFrame;

public class LazySeqNode extends ExpressionNode {

    @Child private ExpressionNode thunkNode;

    public LazySeqNode(ExpressionNode thunkNode) {
        this.thunkNode = thunkNode;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object thunkFn = thunkNode.executeGeneric(frame);
        if (thunkFn instanceof ClojureFunction fn) {
            return new LazySeq(() -> fn.getCallTarget().call(fn));
        }
        throw new RuntimeException("lazy-seq: thunk must be a function");
    }
}
