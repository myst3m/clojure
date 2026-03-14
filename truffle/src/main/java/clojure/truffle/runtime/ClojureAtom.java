package clojure.truffle.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.TruffleLanguage;
import clojure.truffle.ClojureTruffleLanguage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@ExportLibrary(InteropLibrary.class)
public class ClojureAtom implements TruffleObject,
        java.util.function.Supplier<Object>,
        java.util.function.BooleanSupplier,
        java.util.function.IntSupplier,
        java.util.function.LongSupplier,
        java.util.function.DoubleSupplier {

    private final AtomicReference<Object> value;
    private volatile Object validator;
    private final ConcurrentHashMap<Object, Object> watches = new ConcurrentHashMap<>();

    public ClojureAtom(Object initialValue) {
        this.value = new AtomicReference<>(initialValue);
    }

    public Object deref() {
        return value.get();
    }

    public Object reset(Object newValue) {
        value.set(newValue);
        return newValue;
    }

    public boolean compareAndSet(Object oldVal, Object newVal) {
        return value.compareAndSet(oldVal, newVal);
    }

    public void setValidator(Object validatorFn) {
        this.validator = validatorFn;
    }

    public Object getValidator() {
        return validator;
    }

    public void addWatch(Object key, Object fn) {
        watches.put(key, fn);
    }

    public void removeWatch(Object key) {
        watches.remove(key);
    }

    public ConcurrentHashMap<Object, Object> getWatches() {
        return watches;
    }

    private volatile Object meta;

    public Object getMeta() {
        return meta != null ? meta : ClojureNil.INSTANCE;
    }

    public void setMeta(Object newMeta) {
        this.meta = newMeta;
    }

    // java.util.function.Supplier
    @Override
    public Object get() {
        return deref();
    }

    // java.util.function.BooleanSupplier
    @Override
    public boolean getAsBoolean() {
        Object v = deref();
        if (v instanceof Boolean b) return b;
        return v != null && !(v instanceof ClojureNil);
    }

    // java.util.function.IntSupplier
    @Override
    public int getAsInt() {
        return ((Number) deref()).intValue();
    }

    // java.util.function.LongSupplier
    @Override
    public long getAsLong() {
        return ((Number) deref()).longValue();
    }

    // java.util.function.DoubleSupplier
    @Override
    public double getAsDouble() {
        return ((Number) deref()).doubleValue();
    }

    @ExportMessage
    boolean hasLanguage() {
        return true;
    }

    @ExportMessage
    Class<? extends TruffleLanguage<?>> getLanguage() {
        return ClojureTruffleLanguage.class;
    }

    @ExportMessage
    @TruffleBoundary
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return toString();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        return "<atom " + value.get() + ">";
    }
}
