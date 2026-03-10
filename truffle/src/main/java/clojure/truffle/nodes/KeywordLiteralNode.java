package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.lang.Keyword;

public class KeywordLiteralNode extends ExpressionNode {
    private final Keyword value;

    public KeywordLiteralNode(Keyword value) {
        this.value = value;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        return value;
    }
}
