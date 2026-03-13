package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import clojure.truffle.ClojureTruffleLanguage;
import com.oracle.truffle.api.interop.TruffleObject;
import clojure.truffle.runtime.ClojureNil;
import clojure.truffle.runtime.ClojureObject;

public class ProgramRootNode extends RootNode {

    @Children private final ExpressionNode[] bodyNodes;

    public ProgramRootNode(ClojureTruffleLanguage language,
                           FrameDescriptor frameDescriptor,
                           ExpressionNode[] bodyNodes) {
        super(language, frameDescriptor);
        this.bodyNodes = bodyNodes;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        // Delegate to boundary method to prevent Truffle PE from analyzing
        // all top-level expression nodes (which include heavy operations
        // like reflection, context lookups, etc.)
        // JIT compilation only applies to FnBodyNode (function bodies).
        return executeBody(bodyNodes, frame);
    }

    @TruffleBoundary
    private static Object executeBody(ExpressionNode[] bodyNodes, Object frameObj) {
        VirtualFrame frame = (VirtualFrame) frameObj;
        Object result = ClojureNil.INSTANCE;
        for (ExpressionNode node : bodyNodes) {
            result = node.executeGeneric(frame);
        }
        return wrapForPolyglot(result);
    }

    @Override
    public String getName() {
        return "<program>";
    }

    private static Object wrapForPolyglot(Object value) {
        if (value instanceof Long || value instanceof Double ||
            value instanceof Boolean || value instanceof String ||
            value instanceof TruffleObject) {
            return value;
        }
        return new ClojureObject(value);
    }
}
