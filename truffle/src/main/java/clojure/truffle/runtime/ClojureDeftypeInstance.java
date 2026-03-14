package clojure.truffle.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import clojure.truffle.ClojureTruffleLanguage;

import java.util.Map;
import java.util.LinkedHashMap;
import clojure.truffle.ClojureContext;

@ExportLibrary(InteropLibrary.class)
public class ClojureDeftypeInstance implements TruffleObject, clojure.lang.ILookup, clojure.lang.Associative,
        clojure.lang.Seqable, clojure.lang.IPersistentCollection, clojure.lang.Counted,
        clojure.lang.IObj, clojure.lang.IMeta {

    private final String typeName;
    private final Object[] fields;
    private final Map<String, Integer> fieldIndex;
    // Extra fields added via assoc (not part of original deftype/defrecord)
    private final clojure.lang.IPersistentMap extras;
    // Per-type method implementations (IFn invoke, IDeref deref, etc.)
    private Map<String, Object> methods;
    // Whether this instance was created via defrecord (vs deftype)
    private boolean record;
    // Metadata
    private clojure.lang.IPersistentMap meta;

    public ClojureDeftypeInstance(String typeName, Object[] fields, Map<String, Integer> fieldIndex) {
        this(typeName, fields, fieldIndex, clojure.lang.PersistentArrayMap.EMPTY);
    }

    public ClojureDeftypeInstance(String typeName, Object[] fields, Map<String, Integer> fieldIndex,
                                  clojure.lang.IPersistentMap extras) {
        this.typeName = typeName;
        this.fields = fields;
        this.fieldIndex = fieldIndex;
        this.extras = extras;
    }

    @Override
    public clojure.lang.IObj withMeta(clojure.lang.IPersistentMap meta) {
        ClojureDeftypeInstance copy = new ClojureDeftypeInstance(typeName, fields, fieldIndex, extras);
        copy.methods = this.methods;
        copy.record = this.record;
        copy.meta = meta;
        return copy;
    }

    @Override
    public clojure.lang.IPersistentMap meta() {
        return meta;
    }

    public void setMethods(Map<String, Object> methods) { this.methods = methods; }
    public Object getMethod(String name) { return methods != null ? methods.get(name) : null; }
    public boolean isRecord() { return record; }
    public void setRecord(boolean record) { this.record = record; }

    public String getTypeName() { return typeName; }

    public Object getField(String name) {
        Integer idx = fieldIndex.get(name);
        if (idx == null) {
            // Check extras
            clojure.lang.Keyword kw = clojure.lang.Keyword.intern(name);
            Object val = extras.valAt(kw);
            if (val != null) return val;
            throw new RuntimeException("No field '" + name + "' on type " + typeName);
        }
        return fields[idx];
    }

    public boolean hasField(String name) {
        return fieldIndex.containsKey(name) || extras.containsKey(clojure.lang.Keyword.intern(name));
    }

    public Map<String, Integer> getFieldIndex() { return fieldIndex; }
    public Object[] getFields() { return fields; }

    // ILookup
    @Override
    public Object valAt(Object key) {
        return valAt(key, null);
    }

    @Override
    public Object valAt(Object key, Object notFound) {
        if (key instanceof clojure.lang.Keyword kw) {
            Integer idx = fieldIndex.get(kw.getName());
            if (idx != null) return fields[idx];
            Object val = extras.valAt(kw);
            return val != null ? val : notFound;
        }
        if (key instanceof String s) {
            Integer idx = fieldIndex.get(s);
            if (idx != null) return fields[idx];
        }
        Object val = extras.valAt(key);
        return val != null ? val : notFound;
    }

    // Associative
    @Override
    public clojure.lang.Associative assoc(Object key, Object val) {
        if (key instanceof clojure.lang.Keyword kw) {
            Integer idx = fieldIndex.get(kw.getName());
            if (idx != null) {
                Object[] newFields = fields.clone();
                newFields[idx] = val;
                ClojureDeftypeInstance inst = new ClojureDeftypeInstance(typeName, newFields, fieldIndex, extras);
                inst.methods = this.methods;
                inst.record = this.record;
                inst.meta = this.meta;
                return inst;
            }
        }
        ClojureDeftypeInstance inst = new ClojureDeftypeInstance(typeName, fields, fieldIndex, extras.assoc(key, val));
        inst.methods = this.methods;
        inst.record = this.record;
        inst.meta = this.meta;
        return inst;
    }

    @Override
    public boolean containsKey(Object key) {
        if (key instanceof clojure.lang.Keyword kw) {
            return fieldIndex.containsKey(kw.getName()) || extras.containsKey(kw);
        }
        return extras.containsKey(key);
    }

    @Override
    public clojure.lang.IMapEntry entryAt(Object key) {
        Object val = valAt(key);
        return val != null ? new clojure.lang.MapEntry(key, val) : null;
    }

    // Seqable
    @Override
    public clojure.lang.ISeq seq() {
        // Check if deftype defines a custom seq method
        Object seqFn = getMethod("seq");
        if (seqFn instanceof clojure.lang.IFn fn) {
            Object result = fn.invoke(this);
            if (result == null || result instanceof ClojureNil) return null;
            if (result instanceof clojure.lang.ISeq s) return s;
            if (result instanceof clojure.lang.Seqable s) return s.seq();
            return ClojureRT.seq(result);
        }
        // Default: treat as map of fields
        java.util.List<Object> entries = new java.util.ArrayList<>();
        for (Map.Entry<String, Integer> e : fieldIndex.entrySet()) {
            entries.add(new clojure.lang.MapEntry(
                    clojure.lang.Keyword.intern(e.getKey()), fields[e.getValue()]));
        }
        for (clojure.lang.ISeq s = extras.seq(); s != null; s = s.next()) {
            entries.add(s.first());
        }
        return entries.isEmpty() ? null : clojure.lang.PersistentList.create(entries).seq();
    }

    // Counted
    @Override
    public int count() {
        // Check if deftype defines a custom count method
        Object countFn = getMethod("count");
        if (countFn instanceof clojure.lang.IFn fn) {
            Object result = fn.invoke(this);
            return ((Number) result).intValue();
        }
        return fieldIndex.size() + extras.count();
    }

    // IPersistentCollection
    @Override
    public clojure.lang.IPersistentCollection cons(Object o) {
        if (o instanceof clojure.lang.MapEntry me) {
            return (clojure.lang.IPersistentCollection) assoc(me.key(), me.val());
        }
        if (o instanceof clojure.lang.IPersistentVector v && v.count() == 2) {
            return (clojure.lang.IPersistentCollection) assoc(v.nth(0), v.nth(1));
        }
        throw new RuntimeException("Can only conj [k v] pairs onto records");
    }

    @Override
    public clojure.lang.IPersistentCollection empty() {
        return clojure.lang.PersistentArrayMap.EMPTY;
    }

    @Override
    public boolean equiv(Object o) {
        return equals(o);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClojureDeftypeInstance other)) {
            // Also compare with regular maps
            if (o instanceof clojure.lang.IPersistentMap m) {
                if (m.count() != count()) return false;
                for (clojure.lang.ISeq s = seq(); s != null; s = s.next()) {
                    clojure.lang.MapEntry me = (clojure.lang.MapEntry) s.first();
                    Object v = m.valAt(me.key());
                    if (v == null || !clojure.lang.Util.equals(me.val(), v)) return false;
                }
                return true;
            }
            return false;
        }
        if (!typeName.equals(other.typeName)) return false;
        if (fields.length != other.fields.length) return false;
        for (int i = 0; i < fields.length; i++) {
            if (!clojure.lang.Util.equals(fields[i], other.fields[i])) return false;
        }
        return clojure.lang.Util.equals(extras, other.extras);
    }

    @Override
    public int hashCode() {
        int h = typeName.hashCode();
        for (Object f : fields) h = h * 31 + (f == null ? 0 : f.hashCode());
        return h;
    }

    @ExportMessage
    boolean hasLanguage() { return true; }

    @ExportMessage
    Class<? extends TruffleLanguage<?>> getLanguage() { return ClojureTruffleLanguage.class; }

    @ExportMessage
    @TruffleBoundary
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return toString();
    }

    @Override
    @TruffleBoundary
    public String toString() {
        StringBuilder sb = new StringBuilder("#" + typeName + "{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : fieldIndex.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append(" ").append(fields[e.getValue()]);
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
