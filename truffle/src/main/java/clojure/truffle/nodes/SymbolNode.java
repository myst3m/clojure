package clojure.truffle.nodes;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.utilities.CyclicAssumption;
import clojure.truffle.ClojureContext;

public class SymbolNode extends ExpressionNode {
    private final ClojureContext context;
    private final String name;
    private final String compileNs;  // namespace at compile time

    /**
     * Cached value for stable vars. When a var's value doesn't change
     * between calls (the common case for defn, defmacro, etc.),
     * we cache the resolved value and guard it with an Assumption.
     * This lets Truffle treat the var lookup as a constant during
     * partial evaluation, enabling inlining of the called function.
     */
    @CompilationFinal private Object cachedValue;
    @CompilationFinal private Assumption cachedAssumption;

    public SymbolNode(ClojureContext context, String name) {
        this.context = context;
        this.name = name;
        this.compileNs = context != null ? context.getCurrentNamespace() : null;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        // Fast path: return cached value if assumption still valid
        if (cachedAssumption != null && cachedAssumption.isValid()) {
            return cachedValue;
        }

        Object value = resolveSymbol(context, name, compileNs);

        // Cache the resolved value with an assumption from the context
        // Dynamic vars (*var*) should not be cached as they change per-thread
        if (!isDynamicVar(name)) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            CyclicAssumption varAssumption = context.getVarAssumption(name);
            if (varAssumption != null) {
                cachedValue = value;
                cachedAssumption = varAssumption.getAssumption();
            }
        }

        return value;
    }

    @TruffleBoundary
    private static boolean isDynamicVar(String name) {
        // Convention: dynamic vars are surrounded by *earmuffs*
        String simpleName = name;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) simpleName = name.substring(slash + 1);
        return simpleName.startsWith("*") && simpleName.endsWith("*") && simpleName.length() > 2;
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
