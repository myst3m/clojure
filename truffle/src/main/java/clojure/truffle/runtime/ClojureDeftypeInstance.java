package clojure.truffle.runtime;

import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;
import com.oracle.truffle.api.TruffleLanguage;
import clojure.truffle.ClojureTruffleLanguage;

import java.util.Map;

@ExportLibrary(InteropLibrary.class)
public class ClojureDeftypeInstance implements TruffleObject {

    private final String typeName;
    private final Object[] fields;
    private final Map<String, Integer> fieldIndex;

    public ClojureDeftypeInstance(String typeName, Object[] fields, Map<String, Integer> fieldIndex) {
        this.typeName = typeName;
        this.fields = fields;
        this.fieldIndex = fieldIndex;
    }

    public String getTypeName() { return typeName; }

    public Object getField(String name) {
        Integer idx = fieldIndex.get(name);
        if (idx == null) throw new RuntimeException("No field '" + name + "' on type " + typeName);
        return fields[idx];
    }

    public boolean hasField(String name) {
        return fieldIndex.containsKey(name);
    }

    public Map<String, Integer> getFieldIndex() { return fieldIndex; }
    public Object[] getFields() { return fields; }

    @ExportMessage
    boolean hasLanguage() { return true; }

    @ExportMessage
    Class<? extends TruffleLanguage<?>> getLanguage() { return ClojureTruffleLanguage.class; }

    @ExportMessage
    Object toDisplayString(@SuppressWarnings("unused") boolean allowSideEffects) {
        return toString();
    }

    @Override
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
