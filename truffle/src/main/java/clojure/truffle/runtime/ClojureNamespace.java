package clojure.truffle.runtime;

import java.util.concurrent.ConcurrentHashMap;

public class ClojureNamespace {

    private final String name;
    private final ConcurrentHashMap<String, Object> interns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> refers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> referSources = new ConcurrentHashMap<>(); // sym -> source ns name
    private final ConcurrentHashMap<String, ClojureNamespace> aliases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Class<?>> imports = new ConcurrentHashMap<>();

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
        val = refers.get(sym);
        if (val != null) return val;
        Class<?> imported = imports.get(sym);
        return imported;
    }

    public Object resolveIntern(String sym) {
        return interns.get(sym);
    }

    public void refer(String sym, Object val) {
        refers.put(sym, val);
    }

    public void refer(String sym, Object val, String sourceNs) {
        refers.put(sym, val);
        if (sourceNs != null) referSources.put(sym, sourceNs);
    }

    public void referAll(ClojureNamespace other) {
        refers.putAll(other.interns);
        for (String sym : other.interns.keySet()) {
            referSources.put(sym, other.getName());
        }
    }

    public void referOnly(ClojureNamespace other, java.util.List<String> syms) {
        for (String sym : syms) {
            Object val = other.resolve(sym);
            if (val != null) {
                refers.put(sym, val);
                referSources.put(sym, other.getName());
            }
        }
    }

    public void referWithExclude(ClojureNamespace other, java.util.Set<String> excludes) {
        for (var entry : other.interns.entrySet()) {
            if (!excludes.contains(entry.getKey())) {
                refers.put(entry.getKey(), entry.getValue());
                referSources.put(entry.getKey(), other.getName());
            }
        }
    }

    public void referWithRename(ClojureNamespace other, java.util.Map<String, String> renames) {
        for (var entry : other.interns.entrySet()) {
            String newName = renames.getOrDefault(entry.getKey(), entry.getKey());
            refers.put(newName, entry.getValue());
            referSources.put(newName, other.getName());
        }
    }

    /** Get the source namespace for a referred symbol */
    public String getReferSource(String sym) {
        return referSources.get(sym);
    }

    public void unmap(String sym) {
        interns.remove(sym);
        refers.remove(sym);
        imports.remove(sym);
    }

    public void unalias(String shortName) {
        aliases.remove(shortName);
    }

    public void alias(String shortName, ClojureNamespace ns) {
        aliases.put(shortName, ns);
    }

    public ClojureNamespace resolveAlias(String shortName) {
        return aliases.get(shortName);
    }

    public void importClass(String simpleName, Class<?> clazz) {
        imports.put(simpleName, clazz);
    }

    public ConcurrentHashMap<String, Object> getInterns() { return interns; }
    public ConcurrentHashMap<String, Object> getRefers() { return refers; }
    public ConcurrentHashMap<String, ClojureNamespace> getAliases() { return aliases; }
    public ConcurrentHashMap<String, Class<?>> getImports() { return imports; }

    /** Returns all mappings: interns + refers + imports */
    public ConcurrentHashMap<String, Object> getMap() {
        ConcurrentHashMap<String, Object> all = new ConcurrentHashMap<>();
        all.putAll(refers);
        for (var e : imports.entrySet()) all.put(e.getKey(), e.getValue());
        all.putAll(interns); // interns take precedence
        return all;
    }

    @Override
    public String toString() {
        return "#namespace[" + name + "]";
    }
}
