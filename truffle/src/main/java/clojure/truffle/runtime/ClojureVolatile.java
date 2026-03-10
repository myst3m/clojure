package clojure.truffle.runtime;

public class ClojureVolatile {
    private volatile Object value;

    public ClojureVolatile(Object value) {
        this.value = value;
    }

    public Object deref() { return value; }
    public Object reset(Object newVal) { this.value = newVal; return newVal; }

    @Override
    public String toString() {
        return "#volatile[" + value + "]";
    }
}
