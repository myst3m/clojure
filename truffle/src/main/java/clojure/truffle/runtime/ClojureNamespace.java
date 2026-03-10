package clojure.truffle.runtime;

import java.util.concurrent.ConcurrentHashMap;

public class ClojureNamespace {

    private final String name;
    private final ConcurrentHashMap<String, Object> interns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> refers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClojureNamespace> aliases = new ConcurrentHashMap<>();

    public ClojureNamespace(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    public void intern(String sym, Object val) {
        interns.put(sym, val);
    }

    public Object resolve(String sym) {
        Object val = interns.get(sym);
        if (val != null) return val;
        return refers.get(sym);
    }

    public void refer(String sym, Object val) {
        refers.put(sym, val);
    }

    public void referAll(ClojureNamespace other) {
        refers.putAll(other.interns);
    }

    public void alias(String shortName, ClojureNamespace ns) {
        aliases.put(shortName, ns);
    }

    public ClojureNamespace resolveAlias(String shortName) {
        return aliases.get(shortName);
    }

    public ConcurrentHashMap<String, Object> getInterns() { return interns; }

    @Override
    public String toString() {
        return "#namespace[" + name + "]";
    }
}
