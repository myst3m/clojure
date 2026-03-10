package clojure.truffle.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.TruffleLanguage;
import clojure.truffle.ClojureTruffleLanguage;

import java.util.concurrent.atomic.AtomicReference;

@ExportLibrary(InteropLibrary.class)
public class ClojureAtom implements TruffleObject {

    private final AtomicReference<Object> value;

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
