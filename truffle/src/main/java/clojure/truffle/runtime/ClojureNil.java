package clojure.truffle.runtime;

import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.interop.InteropLibrary;

@ExportLibrary(InteropLibrary.class)
public final class ClojureNil implements TruffleObject {
    public static final ClojureNil INSTANCE = new ClojureNil();

    private ClojureNil() {
    }

    @ExportMessage
    boolean isNull() {
        return true;
    }

    @Override
    public String toString() {
        return "nil";
    }
}
