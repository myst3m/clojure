package clojure.truffle.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public class ClojureAgent {

    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() + 2, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<Object> value;
    private volatile Throwable error;

    public ClojureAgent(Object initialValue) {
        this.value = new AtomicReference<>(initialValue);
    }

    public Object deref() {
        return value.get();
    }

    public void send(Object fn, Object[] extraArgs, clojure.truffle.ClojureContext context) {
        POOL.submit(() -> {
            try {
                Object[] args = new Object[extraArgs.length + 1];
                args[0] = value.get();
                System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
                Object newVal = context.callFunction(fn, args);
                value.set(newVal);
            } catch (Throwable t) {
                error = t;
            }
        });
    }

    public Throwable getError() { return error; }

    public void restart(Object newValue) {
        error = null;
        value.set(newValue);
    }

    @Override
    public String toString() {
        return "<agent " + value.get() + ">";
    }

    public static void shutdown() {
        POOL.shutdown();
    }
}
