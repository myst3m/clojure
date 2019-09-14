/* Copyright © 2019 Tsutomu Miyashita
 * All rights reserved.
 *
 * The use and distribution terms for this software are covered by the Eclipse
 * Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
 * can be found in the file epl-v10.html at the root of this distribution.  By
 * using this software in any fashion, you are agreeing to be bound by the
 * terms of this license.  You must not remove this notice, or any other, from
 * this software.
 */


package clojure.lang;

public class DynamicClassLoader extends AbstractDynamicClassLoader {
    public DynamicClassLoader() {
        super();
    }

    public DynamicClassLoader(ClassLoader parent) {
        super(parent);
    }

    /**
     * Defines a missing class using Java's standard {@link #defineClass}
     * functionality.
     *
     * @param name the name of the class to define
     * @param bytes the JVM bytecodes for the class
     * @param srcForm the Clojure form for the class
     */
    @Override
    protected Class<?> defineMissingClass(final String name, final byte[] bytes, final Object srcForm) {
        return defineClass(name,bytes,0,bytes.length);
    }
}
