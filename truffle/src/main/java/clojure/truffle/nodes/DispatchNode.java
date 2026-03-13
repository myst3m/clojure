package clojure.truffle.nodes;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;

/**
 * Inline caching dispatch node. Caches up to 4 call targets as DirectCallNodes
 * (which Truffle can inline), then falls back to IndirectCallNode.
 */
public abstract class DispatchNode extends Node {

    public abstract Object executeDispatch(CallTarget target, Object[] args);

    @Specialization(guards = "target == cachedTarget", limit = "4")
    protected static Object doDirect(CallTarget target, Object[] args,
            @Cached("target") CallTarget cachedTarget,
            @Cached("create(cachedTarget)") DirectCallNode directCall) {
        return directCall.call(args);
    }

    @Specialization(replaces = "doDirect")
    protected static Object doIndirect(CallTarget target, Object[] args,
            @Cached(neverDefault = true) IndirectCallNode indirectCall) {
        return indirectCall.call(target, args);
    }

    public static DispatchNode create() {
        return DispatchNodeGen.create();
    }
}
