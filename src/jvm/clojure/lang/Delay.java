/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jun 28, 2007 */

package clojure.lang;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings("rawtypes")
public class Delay implements IDeref, IPending,
        java.util.function.Supplier,
        java.util.function.BooleanSupplier,
        java.util.function.IntSupplier,
        java.util.function.LongSupplier,
        java.util.function.DoubleSupplier {
Object val;
Throwable exception;
IFn fn;
volatile Lock lock;

public Delay(IFn f){
	fn = f;
	val = null;
	exception = null;
	lock = new ReentrantLock();
}

static public Object force(Object x) {
	return (x instanceof Delay) ?
	       ((Delay) x).deref()
	       : x;
}

private void realize() {
	Lock l = lock;
	if(l != null) {
		l.lock();
		try {
			if(fn!=null) {
				try {
					val = fn.invoke();
				} catch (Throwable t) {
					exception = t;
				}
				fn = null;
				lock = null;
			}
		} finally {
			l.unlock();
		}
	}
}

public Object deref() {
	if(lock != null)
		realize();
	if(exception != null)
		throw Util.sneakyThrow(exception);
	return val;
}

public boolean isRealized(){
	return lock == null;
}

// java.util.function.Supplier
public Object get() {
	return deref();
}

// java.util.function.BooleanSupplier
public boolean getAsBoolean() {
	Object v = deref();
	if (v instanceof Boolean b) return b;
	if (v == null || v == Boolean.FALSE) return false;
	// Check for Clojure nil
	if (v.getClass().getName().equals("clojure.truffle.runtime.ClojureNil")) return false;
	return true;
}

// java.util.function.IntSupplier
public int getAsInt() {
	return ((Number) deref()).intValue();
}

// java.util.function.LongSupplier
public long getAsLong() {
	return ((Number) deref()).longValue();
}

// java.util.function.DoubleSupplier
public double getAsDouble() {
	return ((Number) deref()).doubleValue();
}
}
