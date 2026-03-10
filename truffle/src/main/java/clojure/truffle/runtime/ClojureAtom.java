package clojure.truffle.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.TruffleLanguage;
import clojure.truffle.ClojureTruffleLanguage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@ExportLibrary(InteropLibrary.class)
public class ClojureAtom implements TruffleObject {

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

    @ExportMessage
    boolean hasLanguage() {
        return true;
    }

    @ExportMessage
    Class<? extends TruffleLanguage<?>> getLanguage() {
        return ClojureTruffleLanguage.class;
    }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return toString();
    }

    @Override
    public String toString() {
        return "<atom " + value.get() + ">";
    }
}
