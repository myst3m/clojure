package clojure.truffle;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;

/**
 * GraalVM native-image substitutions to break virtual thread reachability chains.
 *
 * In GraalVM 25.0.1, Truffle runtime compilation (JIT) and VM continuations
 * (required for virtual threads) are mutually exclusive. When reflection scanning
 * covers Thread/Executors methods, virtual-thread methods pull in StoredContinuationAccess,
 * which triggers a fatal error. These substitutions replace those methods with stubs.
 */
@TargetClass(java.util.concurrent.Executors.class)
final class Target_java_util_concurrent_Executors {

    @Substitute
    public static ExecutorService newVirtualThreadPerTaskExecutor() {
        throw new UnsupportedOperationException(
                "Virtual threads are not supported in this native image (Truffle JIT mode)");
    }

    @Substitute
    public static ExecutorService newThreadPerTaskExecutor(ThreadFactory factory) {
        throw new UnsupportedOperationException(
                "ThreadPerTaskExecutor is not supported in this native image (Truffle JIT mode)");
    }
}

@TargetClass(java.lang.Thread.class)
final class Target_java_lang_Thread {

    @Substitute
    public static Thread startVirtualThread(Runnable task) {
        throw new UnsupportedOperationException(
                "Virtual threads are not supported in this native image (Truffle JIT mode)");
    }

    @Substitute
    public static Thread.Builder.OfVirtual ofVirtual() {
        throw new UnsupportedOperationException(
                "Virtual threads are not supported in this native image (Truffle JIT mode)");
    }
}
