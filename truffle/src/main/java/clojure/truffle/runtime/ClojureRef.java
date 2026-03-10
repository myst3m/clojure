package clojure.truffle.runtime;

import java.util.concurrent.atomic.AtomicReference;

public class ClojureRef {

    private final AtomicReference<Object> value;
    private volatile Object validator;

    public ClojureRef(Object initialValue) {
        this.value = new AtomicReference<>(initialValue);
    }

    public Object deref() {
        return value.get();
    }

    public Object refSet(Object newVal) {
        if (validator != null) {
            // validation would happen here
        }
        value.set(newVal);
        return newVal;
    }

    public Object alter(Object fn, Object[] moreArgs, clojure.truffle.ClojureContext context) {
        // Simple non-STM implementation: CAS loop
        while (true) {
            Object oldVal = value.get();
            Object[] args = new Object[moreArgs.length + 1];
            args[0] = oldVal;
            System.arraycopy(moreArgs, 0, args, 1, moreArgs.length);
            Object newVal = context.callFunction(fn, args);
            if (value.compareAndSet(oldVal, newVal)) return newVal;
        }
    }

    public Object commute(Object fn, Object[] moreArgs, clojure.truffle.ClojureContext context) {
        // Commute is like alter but allows reordering (simplified: same as alter)
        return alter(fn, moreArgs, context);
    }

    public void setValidator(Object validatorFn) {
        this.validator = validatorFn;
    }

    @Override
    public String toString() {
        return "<ref " + value.get() + ">";
    }
}
