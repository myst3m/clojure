package clojure.truffle.runtime;

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
public class ClojureVar implements TruffleObject, IDeref {

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

    @Override
    public Object deref() {
        return context.getVarWithBindings(name);
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

    public IPersistentMap getMeta() { return meta; }
    public void setMeta(IPersistentMap meta) { this.meta = meta; }

    @ExportMessage
    boolean hasLanguage() { return true; }

    @ExportMessage
    Class<? extends com.oracle.truffle.api.TruffleLanguage<?>> getLanguage() {
        return clojure.truffle.ClojureTruffleLanguage.class;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return "#'" + getQualifiedName();
    }

    @ExportMessage
    boolean hasMetaObject() { return true; }

    @ExportMessage
    Object getMetaObject() { return "clojure.lang.Var"; }
}
