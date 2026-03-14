package clojure.core;

import clojure.lang.*;

/**
 * Primitive vector type for vector-of.
 * In standard Clojure this is a deftype with primitive array backing.
 * Here it wraps a PersistentVector for simplicity while providing
 * the correct type for instance? checks.
 */
public class Vec extends APersistentVector implements IObj, IEditableCollection {

    private final IPersistentVector delegate;
    private final IPersistentMap meta;

    public Vec(IPersistentVector delegate) {
        this(delegate, null);
    }

    public Vec(IPersistentVector delegate, IPersistentMap meta) {
        this.delegate = delegate;
        this.meta = meta;
    }

    @Override
    public Object nth(int i) {
        return delegate.nth(i);
    }

    @Override
    public Object nth(int i, Object notFound) {
        return delegate.nth(i, notFound);
    }

    @Override
    public int count() {
        return delegate.count();
    }

    @Override
    public IPersistentVector assocN(int i, Object val) {
        return new Vec(delegate.assocN(i, val), meta);
    }

    @Override
    public IPersistentVector cons(Object o) {
        return new Vec(delegate.cons(o), meta);
    }

    @Override
    public IPersistentCollection empty() {
        return new Vec(PersistentVector.EMPTY, meta);
    }

    @Override
    public IPersistentStack pop() {
        IPersistentStack popped = (IPersistentStack) delegate.pop();
        return new Vec((IPersistentVector) popped, meta);
    }

    @Override
    public ISeq seq() {
        // Delegate to the underlying PersistentVector so we get ChunkedSeq (IChunkedSeq support)
        return delegate.seq();
    }

    @Override
    public IObj withMeta(IPersistentMap meta) {
        return new Vec(delegate, meta);
    }

    @Override
    public IPersistentMap meta() {
        return meta;
    }

    @Override
    public ITransientCollection asTransient() {
        if (delegate instanceof IEditableCollection ec) {
            return ec.asTransient();
        }
        throw new UnsupportedOperationException("Vec does not support transient");
    }
}
