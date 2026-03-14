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
public class ClojureDeftypeInstance implements TruffleObject, clojure.lang.ILookup, clojure.lang.IPersistentMap,
        clojure.lang.Seqable, clojure.lang.IPersistentCollection, clojure.lang.Counted,
        clojure.lang.IObj, clojure.lang.IMeta, java.lang.Iterable, java.util.Map {

    private final String typeName;
    private String qualifiedTypeName; // namespace-qualified name for printing (e.g., clojure.test_clojure.protocols.Foo)
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
        copy.qualifiedTypeName = this.qualifiedTypeName;
        copy.methods = this.methods;
        copy.record = this.record;
        copy.meta = meta;
        return copy;
    }

    @Override
    public clojure.lang.IPersistentMap meta() {
        return meta;
    }

    public void setQualifiedTypeName(String name) { this.qualifiedTypeName = name; }
    public String getQualifiedTypeName() { return qualifiedTypeName != null ? qualifiedTypeName : typeName; }
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

    // Associative / IPersistentMap
    @Override
    public clojure.lang.IPersistentMap assoc(Object key, Object val) {
        if (key instanceof clojure.lang.Keyword kw) {
            Integer idx = fieldIndex.get(kw.getName());
            if (idx != null) {
                Object[] newFields = fields.clone();
                newFields[idx] = val;
                ClojureDeftypeInstance inst = new ClojureDeftypeInstance(typeName, newFields, fieldIndex, extras);
                inst.qualifiedTypeName = this.qualifiedTypeName;
                inst.methods = this.methods;
                inst.record = this.record;
                inst.meta = this.meta;
                return inst;
            }
        }
        ClojureDeftypeInstance inst = new ClojureDeftypeInstance(typeName, fields, fieldIndex, extras.assoc(key, val));
        inst.qualifiedTypeName = this.qualifiedTypeName;
        inst.methods = this.methods;
        inst.record = this.record;
        inst.meta = this.meta;
        return inst;
    }

    @Override
    public clojure.lang.IPersistentMap assocEx(Object key, Object val) {
        if (containsKey(key)) throw new RuntimeException("Key already present: " + key);
        return (clojure.lang.IPersistentMap) assoc(key, val);
    }

    @Override
    public clojure.lang.IPersistentMap without(Object key) {
        if (key instanceof clojure.lang.Keyword kw) {
            Integer idx = fieldIndex.get(kw.getName());
            if (idx != null) {
                // dissoc of a defined field converts record to a plain map
                clojure.lang.IPersistentMap result = clojure.lang.PersistentHashMap.EMPTY;
                for (Map.Entry<String, Integer> e : fieldIndex.entrySet()) {
                    if (e.getValue() == idx) continue; // skip the dissoc'd field
                    result = result.assoc(clojure.lang.Keyword.intern(e.getKey()), fields[e.getValue()]);
                }
                // Include extras
                for (clojure.lang.ISeq s = extras.seq(); s != null; s = s.next()) {
                    clojure.lang.IMapEntry me = (clojure.lang.IMapEntry) s.first();
                    result = result.assoc(me.key(), me.val());
                }
                return result;
            }
        }
        clojure.lang.IPersistentMap newExtras = extras.without(key);
        ClojureDeftypeInstance inst = new ClojureDeftypeInstance(typeName, fields, fieldIndex, newExtras);
        inst.qualifiedTypeName = this.qualifiedTypeName;
        inst.methods = this.methods;
        inst.record = this.record;
        inst.meta = this.meta;
        return inst;
    }

    @Override
    public java.util.Iterator iterator() {
        return new java.util.Iterator<Object>() {
            clojure.lang.ISeq s = seq();
            public boolean hasNext() { return s != null; }
            public Object next() {
                Object val = s.first();
                s = s.next();
                return val;
            }
        };
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
        if (o == null) return this;
        if (o instanceof ClojureNil) return this;
        if (o instanceof clojure.lang.MapEntry me) {
            return (clojure.lang.IPersistentCollection) assoc(me.key(), me.val());
        }
        if (o instanceof clojure.lang.IPersistentVector v && v.count() == 2) {
            return (clojure.lang.IPersistentCollection) assoc(v.nth(0), v.nth(1));
        }
        if (o instanceof java.util.Map.Entry me) {
            return (clojure.lang.IPersistentCollection) assoc(me.getKey(), me.getValue());
        }
        // Check if it's a defrecord implementing Map.Entry (has getKey/getValue methods)
        if (o instanceof ClojureDeftypeInstance dti) {
            Object getKeyFn = dti.getMethod("getKey");
            Object getValueFn = dti.getMethod("getValue");
            if (getKeyFn instanceof clojure.lang.IFn kfn && getValueFn instanceof clojure.lang.IFn vfn) {
                return (clojure.lang.IPersistentCollection) assoc(kfn.invoke(o), vfn.invoke(o));
            }
        }
        if (o instanceof clojure.lang.IPersistentMap m) {
            clojure.lang.IPersistentMap result = this;
            for (clojure.lang.ISeq s = m.seq(); s != null; s = s.next()) {
                clojure.lang.IMapEntry me = (clojure.lang.IMapEntry) s.first();
                result = result.assoc(me.key(), me.val());
            }
            return (clojure.lang.IPersistentCollection) result;
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
            return false;
        }
        if (!typeName.equals(other.typeName)) return false;
        if (fields.length != other.fields.length) return false;
        for (int i = 0; i < fields.length; i++) {
            Object a = fields[i] instanceof ClojureNil ? null : fields[i];
            Object b = other.fields[i] instanceof ClojureNil ? null : other.fields[i];
            if (!clojure.lang.Util.equals(a, b)) return false;
        }
        return clojure.lang.Util.equals(extras, other.extras);
    }

    @Override
    public int hashCode() {
        int h = typeName.hashCode();
        for (Object f : fields) h = h * 31 + (f == null ? 0 : f.hashCode());
        return h;
    }

    // java.util.Map implementation (read-only, delegates to seq-based iteration)
    @Override public int size() { return count(); }
    @Override public boolean isEmpty() { return count() == 0; }
    @Override public boolean containsValue(Object value) {
        for (clojure.lang.ISeq s = seq(); s != null; s = s.next()) {
            clojure.lang.IMapEntry me = (clojure.lang.IMapEntry) s.first();
            if (clojure.lang.Util.equals(me.val(), value)) return true;
        }
        return false;
    }
    @Override public Object get(Object key) { return valAt(key); }
    @Override public Object put(Object key, Object value) { throw new UnsupportedOperationException(); }
    @Override public Object remove(Object key) { throw new UnsupportedOperationException(); }
    @Override public void putAll(java.util.Map m) { throw new UnsupportedOperationException(); }
    @Override public void clear() { throw new UnsupportedOperationException(); }
    @Override public java.util.Set keySet() {
        java.util.LinkedHashSet<Object> keys = new java.util.LinkedHashSet<>();
        for (clojure.lang.ISeq s = seq(); s != null; s = s.next()) {
            clojure.lang.IMapEntry me = (clojure.lang.IMapEntry) s.first();
            keys.add(me.key());
        }
        return keys;
    }
    @Override public java.util.Collection values() {
        java.util.ArrayList<Object> vals = new java.util.ArrayList<>();
        for (clojure.lang.ISeq s = seq(); s != null; s = s.next()) {
            clojure.lang.IMapEntry me = (clojure.lang.IMapEntry) s.first();
            vals.add(me.val());
        }
        return vals;
    }
    @Override public java.util.Set<java.util.Map.Entry> entrySet() {
        java.util.LinkedHashSet<java.util.Map.Entry> entries = new java.util.LinkedHashSet<>();
        for (clojure.lang.ISeq s = seq(); s != null; s = s.next()) {
            clojure.lang.IMapEntry me = (clojure.lang.IMapEntry) s.first();
            entries.add(new clojure.lang.MapEntry(me.key(), me.val()));
        }
        return entries;
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
        String displayName = qualifiedTypeName != null ? qualifiedTypeName : typeName;
        StringBuilder sb = new StringBuilder("#" + displayName + "{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : fieldIndex.entrySet()) {
            if (!first) sb.append(", ");
            if (record) {
                sb.append(":").append(e.getKey()).append(" ");
            } else {
                sb.append(e.getKey()).append(" ");
            }
            Object val = fields[e.getValue()];
            sb.append(clojure.lang.RT.printString(val));
            first = false;
        }
        // Include extras for records
        if (record && extras != null) {
            for (clojure.lang.ISeq s = extras.seq(); s != null; s = s.next()) {
                clojure.lang.IMapEntry me = (clojure.lang.IMapEntry) s.first();
                if (!first) sb.append(", ");
                sb.append(clojure.lang.RT.printString(me.key())).append(" ").append(clojure.lang.RT.printString(me.val()));
                first = false;
            }
        }
        sb.append("}");
        return sb.toString();
    }
}
