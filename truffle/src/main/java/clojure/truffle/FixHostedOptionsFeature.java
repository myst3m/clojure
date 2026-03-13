package clojure.truffle;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;
import java.util.Map;
import java.util.Set;
import java.util.Iterator;

/**
 * Feature for Truffle native image build.
 * 1. Removes Enterprise GraalVM sboutlining synthetic methods from the compilation blocklist.
 * 2. Registers runtime classes for reflection so deopt %%D variants are seen during parsing.
 */
public class FixHostedOptionsFeature implements Feature {

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        // Force ContinuationsFeature.supported = true so that continuation lowerings
        // are registered even though DeoptimizationSupport.enabled() is true (Truffle JIT).
        // Without this, VirtualThread internals being reachable triggers a fatal error.
        forceContinuationsSupported();
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        // Register ClojureFunction constructor so its %%D deopt variant is parsed
        try {
            Class<?> cfClass = access.findClassByName("clojure.truffle.runtime.ClojureFunction");
            if (cfClass != null) {
                RuntimeReflection.register(cfClass);
                RuntimeReflection.register(cfClass.getDeclaredConstructors());
                System.out.println("[FixHostedOptions] Registered ClojureFunction for reflection");
            }
        } catch (Exception e) {
            System.err.println("[FixHostedOptions] Warning (reflection): " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void forceContinuationsSupported() {
        try {
            Class<?> contFeatureClass = Class.forName("com.oracle.svm.core.thread.ContinuationsFeature");
            Class<?> imageSingletons = Class.forName("org.graalvm.nativeimage.ImageSingletons");

            // Check if ContinuationsFeature is registered
            var containsMethod = imageSingletons.getMethod("contains", Class.class);
            if (!(boolean) containsMethod.invoke(null, contFeatureClass)) {
                System.out.println("[FixHostedOptions] ContinuationsFeature not registered, skipping");
                return;
            }

            var lookupMethod = imageSingletons.getMethod("lookup", Class.class);
            Object contFeature = lookupMethod.invoke(null, contFeatureClass);

            // Force supported = Boolean.TRUE via Unsafe
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);

            var supportedField = contFeatureClass.getDeclaredField("supported");
            long offset = unsafe.objectFieldOffset(supportedField);
            Object oldValue = unsafe.getObject(contFeature, offset);
            unsafe.putObject(contFeature, offset, Boolean.TRUE);
            System.out.println("[FixHostedOptions] Forced ContinuationsFeature.supported = true (was " + oldValue + ")");
        } catch (Exception e) {
            System.err.println("[FixHostedOptions] Warning (continuations): " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    @Override
    public void afterAnalysis(AfterAnalysisAccess access) {
        cleanBlocklist();
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        cleanBlocklist();
        cleanDeoptEntries();
    }

    @SuppressWarnings("unchecked")
    private void cleanDeoptEntries() {
        try {
            // Access SubstrateCompilationDirectives.singleton()
            Class<?> dirClass = Class.forName("com.oracle.svm.hosted.code.SubstrateCompilationDirectives");
            var singletonMethod = dirClass.getMethod("singleton");
            Object directives = singletonMethod.invoke(null);

            // Access the private deoptEntries field via Unsafe
            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);

            java.lang.reflect.Field deoptField = null;
            for (java.lang.reflect.Field f : dirClass.getDeclaredFields()) {
                if (f.getName().equals("deoptEntries")) {
                    deoptField = f;
                    break;
                }
            }
            // Try superclass if not found
            if (deoptField == null) {
                for (Class<?> c = dirClass.getSuperclass(); c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                        if (f.getName().equals("deoptEntries")) {
                            deoptField = f;
                            break;
                        }
                    }
                    if (deoptField != null) break;
                }
            }

            if (deoptField == null) {
                // Try to find any Map field
                System.err.println("[FixHostedOptions] deoptEntries field not found. Fields in class:");
                for (java.lang.reflect.Field f : dirClass.getDeclaredFields()) {
                    System.err.println("  " + f.getName() + " : " + f.getType().getName());
                }
                return;
            }

            long offset = unsafe.objectFieldOffset(deoptField);
            java.util.Map<Object, Object> deoptEntries =
                (java.util.Map<Object, Object>) unsafe.getObject(directives, offset);

            if (deoptEntries == null) {
                System.out.println("[FixHostedOptions] deoptEntries is null");
                return;
            }

            System.out.println("[FixHostedOptions] deoptEntries has " + deoptEntries.size() + " entries");

            // Remove entries for methods that contain %%D and ClojureFunction
            Iterator<java.util.Map.Entry<Object, Object>> it = deoptEntries.entrySet().iterator();
            int removed = 0;
            while (it.hasNext()) {
                var entry = it.next();
                String methodStr = entry.getKey().toString();
                if (methodStr.contains("ClojureFunction")) {
                    System.out.println("[FixHostedOptions] Removing deopt entry: " +
                        methodStr.substring(0, Math.min(methodStr.length(), 120)));
                    it.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                System.out.println("[FixHostedOptions] Removed " + removed + " deopt entries");
            }
        } catch (Exception e) {
            System.err.println("[FixHostedOptions] Warning (deopt): " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @SuppressWarnings("unchecked")
    private void cleanBlocklist() {
        try {
            Class<?> truffleFeatureClass = Class.forName("com.oracle.svm.truffle.TruffleFeature");
            Class<?> imageSingletons = Class.forName("org.graalvm.nativeimage.ImageSingletons");
            var lookupMethod = imageSingletons.getMethod("lookup", Class.class);
            Object truffleFeature = lookupMethod.invoke(null, truffleFeatureClass);

            var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            var unsafe = (sun.misc.Unsafe) unsafeField.get(null);

            var blocklistField = truffleFeatureClass.getDeclaredField("blocklistMethods");
            long offset = unsafe.objectFieldOffset(blocklistField);
            Set<Object> blocklist = (Set<Object>) unsafe.getObject(truffleFeature, offset);

            Iterator<Object> it = blocklist.iterator();
            int removed = 0;
            while (it.hasNext()) {
                Object method = it.next();
                String s = method.toString();
                if (s.contains("sboutlining")) {
                    it.remove();
                    removed++;
                    System.out.println("[FixHostedOptions] Removed from blocklist: " +
                        s.substring(0, Math.min(s.length(), 100)));
                }
            }
            if (removed > 0) {
                System.out.println("[FixHostedOptions] Removed " + removed + " sboutlining entries");
            }
        } catch (Exception e) {
            System.err.println("[FixHostedOptions] Warning: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
