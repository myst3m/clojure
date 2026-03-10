package clojure.truffle.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ClojurePromise {
    private volatile Object value;
    private volatile boolean delivered = false;
    private final CountDownLatch latch = new CountDownLatch(1);

    public Object deref() {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException("Promise deref interrupted", e);
        }
        return value;
    }

    public Object deref(long timeoutMs, Object timeoutVal) {
        try {
            if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return value;
            }
            return timeoutVal;
        } catch (InterruptedException e) {
            throw new RuntimeException("Promise deref interrupted", e);
        }
    }

    public boolean deliver(Object val) {
        if (delivered) return false;
        synchronized (this) {
            if (delivered) return false;
            this.value = val;
            this.delivered = true;
            latch.countDown();
            return true;
        }
    }

    public boolean isRealized() { return delivered; }

    @Override
    public String toString() {
        if (delivered) return "#promise[" + value + "]";
        return "#promise[:pending]";
    }
}
