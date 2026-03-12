; Conformance Tests: Arrays & Java Interop

(println "=== Conformance: Arrays & Java Interop ===")

;; ============================================================
;; 1. make-array / aget / aset / alength / aclone
;; ============================================================

;; === make-array ===
(let [a (make-array Object 5)]
  (assert (= 5 (alength a)))
  (aset a 0 "hello")
  (assert (= "hello" (aget a 0)))
  (assert (nil? (aget a 1))))
(println "PASS: make-array / aget / aset / alength")

;; === aclone ===
(let [a (make-array Object 3)]
  (aset a 0 "x")
  (aset a 1 "y")
  (aset a 2 "z")
  (let [b (aclone a)]
    (assert (= "x" (aget b 0)))
    (assert (= "y" (aget b 1)))
    (assert (= "z" (aget b 2)))
    (aset b 0 "changed")
    (assert (= "x" (aget a 0)))
    (assert (= "changed" (aget b 0)))))
(println "PASS: aclone")

;; ============================================================
;; 2. into-array / to-array / object-array
;; ============================================================

;; === into-array ===
(let [a (into-array String ["a" "b" "c"])]
  (assert (= 3 (alength a)))
  (assert (= "a" (aget a 0)))
  (assert (= "c" (aget a 2))))
(println "PASS: into-array")

;; === to-array ===
(let [a (to-array [1 2 3])]
  (assert (= 3 (alength a)))
  (assert (= 1 (aget a 0)))
  (assert (= 3 (aget a 2))))
(println "PASS: to-array")

;; === object-array ===
(let [a (object-array 5)]
  (assert (= 5 (alength a)))
  (assert (nil? (aget a 0))))
(let [a (object-array [10 20 30])]
  (assert (= 3 (alength a)))
  (assert (= 10 (aget a 0)))
  (assert (= 30 (aget a 2))))
(println "PASS: object-array")

;; ============================================================
;; 3. int-array / long-array / double-array / byte-array / char-array / boolean-array
;; ============================================================

;; === int-array ===
(let [a (int-array [1 2 3])]
  (assert (= 3 (alength a)))
  (assert (= 2 (aget a 1))))
(let [a (int-array 3)]
  (assert (= 3 (alength a))))
(println "PASS: int-array")

;; === long-array ===
(let [a (long-array [10 20 30])]
  (assert (= 3 (alength a)))
  (assert (= 20 (aget a 1))))
(let [a (long-array 3)]
  (assert (= 3 (alength a))))
(println "PASS: long-array")

;; === double-array ===
(let [a (double-array [1.1 2.2 3.3])]
  (assert (= 3 (alength a)))
  (assert (= 2.2 (aget a 1))))
(let [a (double-array 2)]
  (assert (= 2 (alength a))))
(println "PASS: double-array")

;; === byte-array ===
(let [a (byte-array [1 2 3])]
  (assert (= 3 (alength a)))
  (assert (= 1 (aget a 0))))
(let [a (byte-array 4)]
  (assert (= 4 (alength a))))
(println "PASS: byte-array")

;; === char-array ===
(let [a (char-array [\x \y \z])]
  (assert (= 3 (alength a)))
  (assert (= \x (aget a 0)))
  (assert (= \z (aget a 2))))
(let [a (char-array 3)]
  (assert (= 3 (alength a))))
(println "PASS: char-array")

;; === boolean-array ===
(let [a (boolean-array [true false true])]
  (assert (= 3 (alength a)))
  (assert (= true (aget a 0)))
  (assert (= false (aget a 1))))
(let [a (boolean-array 3)]
  (assert (= 3 (alength a))))
(println "PASS: boolean-array")

;; ============================================================
;; 4. Java interop: instance methods and static methods
;; ============================================================

;; === instance method ===
(assert (= 5 (.length "hello")))
(assert (= "HELLO" (.toUpperCase "hello")))
(assert (= "hello" (.toLowerCase "HELLO")))
(assert (.startsWith "hello world" "hello"))
(assert (.contains "hello world" "world"))
(println "PASS: Java instance methods")

;; === static methods ===
(assert (= 42 (Integer/parseInt "42")))
(assert (= 255 (Integer/parseInt "FF" 16)))
(assert (= "42" (String/valueOf 42)))
(assert (= 3 (Math/max 1 3)))
(assert (= 5.0 (Math/abs -5.0)))
(println "PASS: Java static methods")

;; === static fields ===
(assert (= 2147483647 Integer/MAX_VALUE))
(assert (= -2147483648 Integer/MIN_VALUE))
(assert (> Math/PI 3.14))
(assert (< Math/PI 3.15))
(println "PASS: Java static fields")

;; ============================================================
;; 5. bean
;; ============================================================

;; bean on a simple Java object
(let [b (bean "hello")]
  (assert (= String (:class b))))
(println "PASS: bean")

;; ============================================================
;; 6. instance? with Java classes
;; ============================================================

(assert (instance? String "hello"))
(assert (instance? Number 42))
(assert (instance? Long 42))
(assert (instance? Double 3.14))
(assert (instance? Boolean true))
(assert (not (instance? String 42)))
(assert (not (instance? Number "hello")))
(assert (instance? java.util.Map {}))
(assert (instance? java.util.List [1 2 3]))
(println "PASS: instance?")

;; ============================================================
;; 7. class / type / supers / bases
;; ============================================================

;; === class ===
(assert (= String (class "hello")))
(assert (= Long (class 42)))
(assert (= Double (class 3.14)))
(assert (= Boolean (class true)))
(assert (nil? (class nil)))
(println "PASS: class")

;; === type (same as class when no :type metadata) ===
(assert (= String (type "hello")))
(assert (= Long (type 42)))
(println "PASS: type")

;; === supers ===
(let [s (supers String)]
  (assert (contains? s Object))
  (assert (contains? s java.io.Serializable))
  (assert (contains? s Comparable))
  (assert (contains? s CharSequence)))
(println "PASS: supers")

;; === bases ===
(let [b (bases String)]
  (assert (some #(= Object %) b)))
(println "PASS: bases")

;; ============================================================
;; 8. cast
;; ============================================================

(assert (= "hello" (cast String "hello")))
(assert (= 42 (cast Number 42)))
;; Test that invalid cast throws
(let [threw (try (cast String 42) false (catch ClassCastException _ true))]
  (assert threw "cast should throw ClassCastException"))
(println "PASS: cast")

;; ============================================================
;; 9. munge / demunge
;; ============================================================

;; NOTE: not yet supported — munge/demunge require clojure.lang.Compiler and clojure.main
;; (assert (= "foo_bar" (clojure.lang.Compiler/munge "foo-bar")))
;; (assert (= "foo-bar" (clojure.main/demunge "foo_bar")))

;; Test munge via Clojure's built-in munge function
(assert (= "foo_bar" (munge "foo-bar")))
(assert (= "foo_BANG_" (munge "foo!")))
(assert (= "foo_QMARK_" (munge "foo?")))
(println "PASS: munge")

(assert (= "foo-bar" (demunge "foo_bar")))
(assert (= "foo!" (demunge "foo_BANG_")))
(assert (= "foo?" (demunge "foo_QMARK_")))
(println "PASS: demunge")

;; ============================================================
;; 10. import
;; ============================================================

(import 'java.util.Date)
(import 'java.util.UUID)
(import 'java.util.ArrayList)
(import 'java.util.HashMap)

(let [d (Date.)]
  (assert (instance? Date d)))
(println "PASS: import Date")

(let [id (UUID/randomUUID)]
  (assert (instance? UUID id))
  (assert (string? (str id))))
(println "PASS: import UUID")

(let [al (ArrayList.)]
  (.add al "one")
  (.add al "two")
  (assert (= 2 (.size al)))
  (assert (= "one" (.get al 0))))
(println "PASS: import ArrayList")

(let [hm (HashMap.)]
  (.put hm "key" "value")
  (assert (= "value" (.get hm "key")))
  (assert (= 1 (.size hm))))
(println "PASS: import HashMap")

;; ============================================================
;; 11. doto
;; ============================================================

(let [al (doto (ArrayList.)
           (.add "a")
           (.add "b")
           (.add "c"))]
  (assert (= 3 (.size al)))
  (assert (= "a" (.get al 0)))
  (assert (= "c" (.get al 2))))
(println "PASS: doto")

;; ============================================================
;; 12. .. (dot-dot threading)
;; ============================================================

(assert (= "HELLO" (.. "hello" toUpperCase)))
(assert (= 5 (.. "hello world" (substring 0 5) length)))
(assert (= "HI" (.. "  hi  " trim toUpperCase)))
(println "PASS: .. (dot-dot threading)")

(println "\n=== Conformance: Arrays & Java Interop PASSED ===")
