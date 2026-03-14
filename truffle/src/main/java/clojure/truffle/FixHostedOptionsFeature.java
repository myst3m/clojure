package clojure.truffle;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * Feature for Truffle native image build.
 * 1. Forces ContinuationsFeature.supported = true for virtual thread support.
 * 2. Registers ClojureFunction for reflection.
 */
public class FixHostedOptionsFeature implements Feature {

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        forceContinuationsSupported();
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        forceContinuationsSupported();
        try {
            Class<?> cfClass = access.findClassByName("clojure.truffle.runtime.ClojureFunction");
            if (cfClass != null) {
                RuntimeReflection.register(cfClass);
                RuntimeReflection.register(cfClass.getDeclaredConstructors());
            }
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private void forceContinuationsSupported() {
        try {
            Class<?> contFeatureClass = Class.forName("com.oracle.svm.core.thread.ContinuationsFeature");
            Class<?> imageSingletons = Class.forName("org.graalvm.nativeimage.ImageSingletons");
            var containsMethod = imageSingletons.getMethod("contains", Class.class);
            if (!(boolean) containsMethod.invoke(null, contFeatureClass)) return;
            var lookupMethod = imageSingletons.getMethod("lookup", Class.class);
            Object contFeature = lookupMethod.invoke(null, contFeatureClass);
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            var supportedField = contFeatureClass.getDeclaredField("supported");
            long offset = unsafe.objectFieldOffset(supportedField);
            unsafe.putObject(contFeature, offset, Boolean.TRUE);
            System.out.println("[FixHostedOptions] Forced ContinuationsFeature.supported = true");
        } catch (Exception e) {
            System.err.println("[FixHostedOptions] Warning (continuations): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
