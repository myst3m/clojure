package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.lang.PersistentArrayMap;
import clojure.truffle.runtime.ClojureNil;

public class MapNode extends ExpressionNode {

    @Children private final ExpressionNode[] kvNodes;

    public MapNode(ExpressionNode[] kvNodes) {
        this.kvNodes = kvNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object[] kvs = new Object[kvNodes.length];
        for (int i = 0; i < kvNodes.length; i++) {
            Object v = kvNodes[i].executeGeneric(frame);
            // Normalize ClojureNil keys to Java null so Clojure persistent collections
            // handle nil keys correctly (e.g., containsKey(null), valAt(null))
            if (i % 2 == 0 && v == ClojureNil.INSTANCE) {
                kvs[i] = null;
            } else {
                kvs[i] = v;
            }
        }
        return createMap(kvs);
    }

    @TruffleBoundary
    private static Object createMap(Object[] kvs) {
        return PersistentArrayMap.createAsIfByAssoc(kvs);
    }
}
