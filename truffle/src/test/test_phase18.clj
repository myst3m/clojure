; Phase 18: Java Interop Enhancement Tests

;; === Varargs: String.format ===
(assert (= "Hello World" (String/format "Hello %s" (into-array Object ["World"]))))
(println "PASS: String.format with array")

;; === Varargs: String.join with CharSequence array ===
(assert (= "a-b-c" (String/join "-" (into-array CharSequence ["a" "b" "c"]))))
(println "PASS: String/join varargs")

;; === Method overload resolution ===
(let [sb (StringBuilder.)]
  (.append sb "hello")
  (.append sb " ")
  (.append sb "world")
  (assert (= "hello world" (.toString sb))))
(println "PASS: StringBuilder method overloads")

;; === Number coercion in method calls ===
(assert (= "A" (String/valueOf (char 65))))
(println "PASS: Number coercion")

;; === Constructor overload ===
(let [sb (StringBuilder. "initial")]
  (assert (= "initial" (.toString sb))))
(let [sb (StringBuilder. 100)]
  (assert (= "" (.toString sb))))
(println "PASS: Constructor overloads")

;; === Proxy with Java interface (Runnable) ===
(let [ran (atom false)
      r (proxy [Runnable] []
          (run [this] (reset! ran true)))]
  (assert (instance? Runnable r))
  (.run r)
  (assert (= true @ran)))
(println "PASS: proxy Runnable")

;; === Proxy with Comparable ===
(let [c (proxy [Comparable] []
          (compareTo [this other]
            (- (long this) (long other))))]
  (assert (instance? Comparable c)))
(println "PASS: proxy Comparable")

;; === Proxy with Callable ===
(import java.util.concurrent.Callable)
(let [c (proxy [Callable] []
          (call [this] 42))]
  (assert (instance? Callable c))
  (assert (= 42 (.call c))))
(println "PASS: proxy Callable")

;; === Reify with Java interface ===
(let [r (reify Runnable
          (run [this] nil))]
  (assert (instance? Runnable r)))
(println "PASS: reify with Java interface")

;; === Collection coercion: Clojure vector -> Java List ===
(import java.util.Collections)
(let [v [3 1 2]
      al (java.util.ArrayList. v)]
  (Collections/sort al)
  (assert (= 1 (.get al 0)))
  (assert (= 2 (.get al 1)))
  (assert (= 3 (.get al 2))))
(println "PASS: Clojure vector -> Java List coercion")

;; === ArrayList interop ===
(let [al (java.util.ArrayList.)]
  (.add al "a")
  (.add al "b")
  (.add al "c")
  (assert (= 3 (.size al)))
  (assert (= "b" (.get al 1)))
  (.set al 1 "B")
  (assert (= "B" (.get al 1))))
(println "PASS: ArrayList interop")

;; === HashMap interop ===
(let [m (java.util.HashMap.)]
  (.put m "key" "value")
  (assert (= "value" (.get m "key")))
  (assert (.containsKey m "key"))
  (assert (not (.containsKey m "other"))))
(println "PASS: HashMap interop")

;; === into-array ===
(let [arr (into-array String ["a" "b" "c"])]
  (assert (= 3 (alength arr)))
  (assert (= "a" (aget arr 0)))
  (assert (= "c" (aget arr 2))))
(println "PASS: into-array with type")

;; === Proxy with multiple interfaces ===
(let [p (proxy [Runnable Callable] []
          (run [this] nil)
          (call [this] "result"))]
  (assert (instance? Runnable p))
  (assert (instance? Callable p))
  (assert (= "result" (.call p))))
(println "PASS: proxy multiple interfaces")

(println "\n=== Phase 18: All tests passed! ===")
