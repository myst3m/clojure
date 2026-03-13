package clojure.truffle.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ClojureAgent {

    // Shared pool for send-off (potentially blocking actions)
    private static final ExecutorService SEND_OFF_POOL =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            });

    // Per-agent single-thread executor ensures serial execution
    private final ExecutorService executor;
    private final AtomicReference<Object> value;
    private volatile Throwable error;

    public ClojureAgent(Object initialValue) {
        this.value = new AtomicReference<>(initialValue);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    public Object deref() {
        return value.get();
    }

    public void send(Object fn, Object[] extraArgs, clojure.truffle.ClojureContext context) {
        executor.submit(() -> {
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

    public void sendOff(Object fn, Object[] extraArgs, clojure.truffle.ClojureContext context) {
        SEND_OFF_POOL.submit(() -> {
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

    public void awaitActions() {
        CountDownLatch latch = new CountDownLatch(1);
        executor.submit(latch::countDown);
        try { latch.await(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void awaitActions(long timeoutMs) {
        CountDownLatch latch = new CountDownLatch(1);
        executor.submit(latch::countDown);
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        SEND_OFF_POOL.shutdown();
    }
}
