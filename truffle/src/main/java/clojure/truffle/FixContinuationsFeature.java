package clojure.truffle;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Substitute StoredContinuationAccess.allocate to prevent ContinuationsFeature
 * from detecting virtual thread internals as reachable.
 *
 * ContinuationsFeature registers a reachability handler that throws an error
 * when StoredContinuationAccess becomes reachable but VM continuations are not
 * supported (which is the case with Truffle JIT). By substituting the allocate
 * method, we prevent the reachability handler from triggering.
 */
@TargetClass(className = "com.oracle.svm.core.heap.StoredContinuationAccess")
final class Target_com_oracle_svm_core_heap_StoredContinuationAccess {

    @Substitute
    @SuppressWarnings("unused")
    static Target_com_oracle_svm_core_heap_StoredContinuation allocate(int size) {
        throw new UnsupportedOperationException("Continuations not supported");
    }
}

@TargetClass(className = "com.oracle.svm.core.heap.StoredContinuation")
final class Target_com_oracle_svm_core_heap_StoredContinuation {
}
