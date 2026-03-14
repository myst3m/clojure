package clojure.truffle.runtime;

import clojure.lang.*;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

/**
 * Lazy sequence implementation matching Clojure's LazySeq semantics.
 * The thunk is called at most once, and the result is cached.
 */
public class LazySeq implements ISeq, Seqable, Sequential, IPending, IObj, IMeta {

    private Supplier<Object> thunk;
    private ISeq seq;
    private boolean realized;
    private IPersistentMap meta;

    public LazySeq(Supplier<Object> thunk) {
        this.thunk = thunk;
    }

    private LazySeq(IPersistentMap meta, ISeq seq) {
        this.meta = meta;
        this.seq = seq;
        this.realized = true;
    }

    @Override
    public IObj withMeta(IPersistentMap meta) {
        // Force realization and wrap with metadata
        return new LazySeq(meta, seq());
    }

    @Override
    public IPersistentMap meta() {
        return meta;
    }

    @TruffleBoundary
    private synchronized ISeq sval() {
        if (!realized) {
            realized = true;
            Object result = thunk.get();
            thunk = null; // allow GC
            if (result instanceof LazySeq) {
                seq = ((LazySeq) result).seq();
            } else if (result == null || result instanceof ClojureNil) {
                seq = null;
            } else if (result instanceof ISeq s) {
                seq = s;
            } else if (result instanceof Seqable s) {
                seq = s.seq();
            } else {
                throw new RuntimeException("lazy-seq body must return a seq or nil, got: " +
                        result.getClass().getName());
            }
        }
        return seq;
    }

    @TruffleBoundary
    @Override
    public ISeq seq() {
        return sval();
    }

    @TruffleBoundary
    @Override
    public Object first() {
        ISeq s = seq();
        return s == null ? null : s.first();
    }

    @TruffleBoundary
    @Override
    public ISeq next() {
        ISeq s = seq();
        return s == null ? null : s.next();
    }

    @TruffleBoundary
    @Override
    public ISeq more() {
        ISeq s = seq();
        if (s == null) return PersistentList.EMPTY;
        ISeq r = s.next();
        return r == null ? PersistentList.EMPTY : r;
    }

    @TruffleBoundary
    @Override
    public ISeq cons(Object o) {
        return new Cons(o, seq());
    }

    @TruffleBoundary
    @Override
    public int count() {
        int c = 0;
        for (ISeq s = seq(); s != null; s = s.next()) c++;
        return c;
    }

    @Override
    public IPersistentCollection empty() {
        return PersistentList.EMPTY;
    }

    @Override
    public boolean equiv(Object o) {
        ISeq s = seq();
        if (s != null) return s.equiv(o);
        return (o instanceof Sequential || o instanceof java.util.List) &&
                ClojureRT.seq(o) == null;
    }

    @Override
    public boolean isRealized() {
        return realized;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("(");
        int count = 0;
        for (ISeq s = seq(); s != null; s = s.next()) {
            if (count > 0) sb.append(" ");
            if (count >= 20) { sb.append("..."); break; }
            sb.append(s.first());
            count++;
        }
        sb.append(")");
        return sb.toString();
    }
}
