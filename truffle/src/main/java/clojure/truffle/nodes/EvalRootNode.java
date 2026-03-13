package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import clojure.truffle.ClojureTruffleLanguage;
import clojure.truffle.runtime.ClojureNil;

/**
 * Root node for internal eval (no polyglot wrapping).
 */
public class EvalRootNode extends RootNode {

    @Children private final ExpressionNode[] bodyNodes;

    public EvalRootNode(ClojureTruffleLanguage language,
                        FrameDescriptor frameDescriptor,
                        ExpressionNode[] bodyNodes) {
        super(language, frameDescriptor);
        this.bodyNodes = bodyNodes;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return executeBody(bodyNodes, frame);
    }

    @TruffleBoundary
    private static Object executeBody(ExpressionNode[] bodyNodes, Object frameObj) {
        VirtualFrame frame = (VirtualFrame) frameObj;
        Object result = ClojureNil.INSTANCE;
        for (ExpressionNode node : bodyNodes) {
            result = node.executeGeneric(frame);
        }
        return result;
    }

    @Override
    public String getName() {
        return "<eval>";
    }
}
