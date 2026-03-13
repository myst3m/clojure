package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.lang.PersistentArrayMap;

public class MapNode extends ExpressionNode {

    @Children private final ExpressionNode[] kvNodes;

    public MapNode(ExpressionNode[] kvNodes) {
        this.kvNodes = kvNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] kvs = new Object[kvNodes.length];
        for (int i = 0; i < kvNodes.length; i++) {
            kvs[i] = kvNodes[i].executeGeneric(frame);
        }
        return createMap(kvs);
    }

    @TruffleBoundary
    private static Object createMap(Object[] kvs) {
        return PersistentArrayMap.createAsIfByAssoc(kvs);
    }
}
