package clojure.truffle.nodes;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import clojure.truffle.ClojureContext;

public class SymbolNode extends ExpressionNode {
    private final ClojureContext context;
    private final String name;
    private final String compileNs;  // namespace at compile time

    public SymbolNode(ClojureContext context, String name) {
        this.context = context;
        this.name = name;
        this.compileNs = context != null ? context.getCurrentNamespace() : null;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object value = resolveSymbol(context, name, compileNs);
        return value;
    }

    @TruffleBoundary
    private static Object resolveSymbol(ClojureContext context, String name, String compileNs) {
        Object value = context.getVarWithBindings(name);
        if (value == null && compileNs != null && !name.contains("/")) {
            clojure.truffle.runtime.ClojureNamespace ns = context.getNamespace(compileNs);
            if (ns != null) {
                value = ns.resolve(name);
            }
        }
        if (value == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new RuntimeException("Unable to resolve symbol: " + name + " in this context");
        }
        return value;
    }

    public String getName() { return name; }
}
