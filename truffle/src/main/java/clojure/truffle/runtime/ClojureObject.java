package clojure.truffle.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import clojure.truffle.ClojureTruffleLanguage;

/**
 * Wraps Clojure values (Keyword, Symbol, PersistentVector, etc.)
 * as TruffleObject so they can cross the polyglot boundary.
 */
@ExportLibrary(InteropLibrary.class)
public class ClojureObject implements TruffleObject {

    private final Object value;

    public ClojureObject(Object value) {
        this.value = value;
    }

    public Object getValue() {
        return value;
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
        return value.toString();
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
