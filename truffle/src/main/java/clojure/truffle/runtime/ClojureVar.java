package clojure.truffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import clojure.lang.IDeref;
import clojure.lang.IPersistentMap;

/**
 * Represents a Clojure Var - a mutable reference to a value in a namespace.
 * This is the object returned by (var x) or #'x.
 */
@ExportLibrary(InteropLibrary.class)
public class ClojureVar implements TruffleObject, IDeref, clojure.lang.IMeta, clojure.lang.IHashEq {

    private final String namespace;
    private final String name;
    private final clojure.truffle.ClojureContext context;
    private volatile IPersistentMap meta;

    public ClojureVar(clojure.truffle.ClojureContext context, String namespace, String name) {
        this.context = context;
        this.namespace = namespace;
        this.name = name;
    }

    public String getNamespace() { return namespace; }
    public String getName() { return name; }
    public String getQualifiedName() {
        return namespace != null ? namespace + "/" + name : name;
    }

    @TruffleBoundary
    @Override
    public Object deref() {
        // Try namespace-qualified lookup first
        if (namespace != null) {
            String qname = namespace + "/" + name;
            Object val = context.getVarWithBindings(qname);
            if (val != null) return val;
            // Also check namespace's own vars
            ClojureNamespace ns = context.getNamespace(namespace);
            if (ns != null) {
                val = ns.resolve(name);
                if (val != null) return val;
            }
        }
        return context.getVarWithBindings(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClojureVar other)) return false;
        return java.util.Objects.equals(namespace, other.namespace)
            && java.util.Objects.equals(name, other.name);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(namespace, name);
    }

    @Override
    public int hasheq() {
        return clojure.lang.Util.hashCombine(
            clojure.lang.Util.hasheq(namespace),
            clojure.lang.Util.hasheq(name));
    }

    public Object get() {
        return deref();
    }

    public void set(Object value) {
        context.setVar(name, value);
    }

    public boolean isDynamic() {
        return context.isDynamic(name);
    }

    public boolean isBound() {
        return context.getVar(name) != null;
    }

    public IPersistentMap getMeta() {
        if (meta != null) return meta;
        // Look up from context's var metadata store
        if (context != null) {
            String qname = namespace != null ? namespace + "/" + name : name;
            return context.getVarMeta(qname);
        }
        return null;
    }
    public void setMeta(IPersistentMap meta) {
        this.meta = meta;
        // Write through to context's central store
        if (context != null && meta != null) {
            String qname = namespace != null ? namespace + "/" + name : name;
            context.setVarMeta(qname, meta);
        }
    }

    @Override
    public IPersistentMap meta() { return getMeta(); }

    @ExportMessage
    boolean hasLanguage() { return true; }

    @ExportMessage
    Class<? extends com.oracle.truffle.api.TruffleLanguage<?>> getLanguage() {
        return clojure.truffle.ClojureTruffleLanguage.class;
    }

    @ExportMessage
    @TruffleBoundary
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return "#'" + getQualifiedName();
    }

    @ExportMessage
    boolean hasMetaObject() { return true; }

    @ExportMessage
    Object getMetaObject() { return "clojure.lang.Var"; }
}
