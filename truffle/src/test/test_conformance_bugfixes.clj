;; Tests for bugs found during yaac.cli integration
;; Each section targets a specific fix

;; === 1. Set lookup: get and keyword-as-function ===
(assert (= :bracket (:bracket #{:bracket :colon})) "keyword lookup on set")
(assert (= :colon (:colon #{:bracket :colon})) "keyword lookup on set 2")
(assert (nil? (:missing #{:bracket :colon})) "keyword lookup on set: missing key")
(assert (= :bracket (get #{:bracket :colon} :bracket)) "get on set")
(assert (nil? (get #{:bracket :colon} :missing)) "get on set: missing")
(assert (= :default (get #{:bracket :colon} :missing :default)) "get on set: not-found")
(assert (= :a (#{:a :b :c} :a)) "set as function")
(assert (nil? (#{:a :b :c} :d)) "set as function: missing")
(println "PASS: set lookup")

;; === 2. ::keys destructuring (namespace-qualified) ===
(ns test.nskeys)
(let [{::keys [a b] :or {a "default-a" b "default-b"}} nil]
  (assert (= "default-a" a) "::keys with :or on nil map")
  (assert (= "default-b" b) "::keys with :or on nil map 2"))

(let [{::keys [x y]} {::x 1 ::y 2}]
  (assert (= 1 x) "::keys basic destructuring")
  (assert (= 2 y) "::keys basic destructuring 2"))

(let [{::keys [p] :or {p 42}} {}]
  (assert (= 42 p) "::keys with :or on empty map"))

(let [{::keys [q] :as all} {::q "hello"}]
  (assert (= "hello" q) "::keys with :as")
  (assert (= {::q "hello"} all) "::keys :as captures whole map"))
(println "PASS: ::keys destructuring")

;; === 3. reduce with reduced ===
(ns test.reduce)
(assert (= 3 (reduce (fn [acc x] (if (= x 3) (reduced acc) (+ acc x))) 0 [1 2 3 4 5]))
        "reduce with reduced short-circuit")
(assert (= 10 (reduce + 0 [1 2 3 4])) "reduce normal")
(assert (= 0 (reduce + 0 [])) "reduce empty with init")
(assert (= :done (reduce (fn [_ _] (reduced :done)) nil [1])) "reduce immediate reduced")
(assert (= 6 (reduce + [1 2 3])) "reduce no init")
(println "PASS: reduce with reduced")

;; === 4. IPersistentSet in get/keyword with various types ===
(ns test.set-ops)
(assert (= "hello" (get #{"hello" "world"} "hello")) "get on string set")
(assert (= 42 (get #{42 99} 42)) "get on integer set")
(assert (contains? #{:a :b} :a) "contains? on set")
(assert (not (contains? #{:a :b} :c)) "contains? negative")
(println "PASS: set operations")

;; === 5. #= read-eval (compile-time evaluation) ===
(ns test.read-eval)
(assert (= 31536000000 #=(* 1000 60 60 24 365)) "#= arithmetic")
(assert (= 6 #=(+ 1 2 3)) "#= simple add")
(println "PASS: #= read-eval")

(println "\n=== Conformance: Bugfixes PASSED ===")
