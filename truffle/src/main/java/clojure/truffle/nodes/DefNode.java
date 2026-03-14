package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.truffle.ClojureContext;

public class DefNode extends ExpressionNode {

    private final ClojureContext context;
    private final String name;
    @Child private ExpressionNode valueNode;

    // For evaluating metadata at runtime (e.g., :test (fn [] body...))
    @Children private final ExpressionNode[] metaValueNodes;
    private final Object[] metaKeys;
    private final IPersistentMap staticMeta; // metadata entries that don't need runtime evaluation

    public DefNode(ClojureContext context, String name, ExpressionNode valueNode) {
        this(context, name, valueNode, null, null, null);
    }

    public DefNode(ClojureContext context, String name, ExpressionNode valueNode,
                   IPersistentMap staticMeta, Object[] metaKeys, ExpressionNode[] metaValueNodes) {
        this.context = context;
        this.name = name;
        this.valueNode = valueNode;
        this.staticMeta = staticMeta;
        this.metaKeys = metaKeys;
        this.metaValueNodes = metaValueNodes != null ? metaValueNodes : new ExpressionNode[0];
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = valueNode.executeGeneric(frame);
        context.setVar(name, value);

        if ((staticMeta != null && staticMeta.count() > 0) || metaValueNodes.length > 0) {
            // Evaluate dynamic meta value nodes in the Truffle frame
            Object[] dynValues = new Object[metaValueNodes.length];
            for (int i = 0; i < metaValueNodes.length; i++) {
                dynValues[i] = metaValueNodes[i].executeGeneric(frame);
            }
            setVarMeta(dynValues);
        }

        return value;
    }

    @TruffleBoundary
    private void setVarMeta(Object[] dynValues) {
        IPersistentMap meta = staticMeta != null ? staticMeta : clojure.lang.PersistentArrayMap.EMPTY;
        for (int i = 0; i < dynValues.length; i++) {
            meta = meta.assoc(metaKeys[i], dynValues[i]);
        }
        String qname = context.getCurrentNamespace() + "/" + name;
        context.setVarMeta(qname, meta);
    }
}
