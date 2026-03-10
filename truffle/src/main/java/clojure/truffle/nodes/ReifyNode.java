package clojure.truffle.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;
import clojure.truffle.runtime.ClojureReified;

import java.util.LinkedHashMap;
import java.util.Map;

public class ReifyNode extends ExpressionNode {
    private final ClojureContext context;
    private final String[] methodNames;
    @Children private final ExpressionNode[] methodNodes;

    public ReifyNode(ClojureContext context, String[] methodNames, ExpressionNode[] methodNodes) {
        this.context = context;
        this.methodNames = methodNames;
        this.methodNodes = methodNodes;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Map<String, Object> methods = new LinkedHashMap<>();
        for (int i = 0; i < methodNames.length; i++) {
            methods.put(methodNames[i], methodNodes[i].executeGeneric(frame));
        }
        return new ClojureReified(methods);
    }
}
