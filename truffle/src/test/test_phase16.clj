; Phase 16: Clojure Conformance Tests

;; === var special form / #' ===
(def my-val 42)
(assert (= 42 (deref #'my-val)))
(assert (var? #'my-val))
(println "PASS: #' (var quote)")

;; === var via (var sym) ===
(def another-val "hello")
(let [v (var another-val)]
  (assert (var? v))
  (assert (= "hello" (deref v))))
(println "PASS: (var sym)")

;; === set! for dynamic vars ===
(def ^:dynamic *test-var* 10)
(binding [*test-var* 20]
  (assert (= 20 *test-var*))
  (set! *test-var* 30)
  (assert (= 30 *test-var*)))
(assert (= 10 *test-var*))
(println "PASS: set! dynamic var")

;; === remove ===
(assert (= '(1 3 5) (remove even? [1 2 3 4 5])))
(assert (= '("a" "b") (remove nil? [nil "a" nil "b"])))
(println "PASS: remove")

;; === rseq ===
(assert (= '(5 4 3 2 1) (rseq [1 2 3 4 5])))
(assert (= '(3 2 1) (rseq (sorted-set 1 2 3))))
(println "PASS: rseq")

;; === array-map ===
(let [m (array-map :a 1 :b 2 :c 3)]
  (assert (= 1 (:a m)))
  (assert (= 2 (:b m)))
  (assert (= 3 (:c m))))
(println "PASS: array-map")

;; === set literal #{} ===
(assert (= #{1 2 3} (set [1 2 3])))
(assert (contains? #{:a :b :c} :b))
(assert (not (contains? #{:a :b :c} :d)))
(println "PASS: set literal #{}")

;; === :syms destructuring ===
(let [m {'a 1 'b 2 'c 3}
      {:syms [a b c]} m]
  (assert (= 1 a))
  (assert (= 2 b))
  (assert (= 3 c)))
(println "PASS: :syms destructuring")

;; === macroexpand-1 ===
(defmacro my-when [test & body]
  (list 'if test (cons 'do body)))
(let [expanded (macroexpand-1 '(my-when true 1 2))]
  (assert (seq? expanded))
  (assert (= 'if (first expanded))))
(println "PASS: macroexpand-1")

;; === when-first ===
(assert (= 1 (when-first [x [1 2 3]] x)))
(assert (nil? (when-first [x []] x)))
(assert (nil? (when-first [x nil] x)))
(println "PASS: when-first")

;; === assert ===
(assert true)
(assert (= 1 1))
(println "PASS: assert")

;; === time ===
(time (reduce + (range 1000)))
(println "PASS: time")

;; === with-redefs ===
(def orig-fn (fn [] "original"))
(assert (= "original" (orig-fn)))
(with-redefs [orig-fn (fn [] "redefined")]
  (assert (= "redefined" (orig-fn))))
(assert (= "original" (orig-fn)))
(println "PASS: with-redefs")

;; === not= ===
(assert (not= 1 2))
(assert (not (not= 1 1)))
(println "PASS: not=")

;; === == numeric equality ===
(assert (== 1 1.0))
(assert (== 3 3.0))
(println "PASS: == numeric equality")

;; === group-by ===
(assert (= {true [2 4] false [1 3 5]} (group-by even? [1 2 3 4 5])))
(println "PASS: group-by")

;; === frequencies ===
(assert (= {"a" 2 "b" 3 "c" 1} (frequencies ["a" "b" "a" "b" "c" "b"])))
(println "PASS: frequencies")

;; === partition-by ===
(assert (= '((1 1) (2 2) (3) (1)) (partition-by identity [1 1 2 2 3 1])))
(println "PASS: partition-by")

;; === map-indexed ===
(assert (= '([0 "a"] [1 "b"] [2 "c"]) (map-indexed vector ["a" "b" "c"])))
(println "PASS: map-indexed")

;; === juxt ===
(assert (= [1 3] ((juxt first last) [1 2 3])))
(assert (= [1 2 3] ((juxt :a :b :c) {:a 1 :b 2 :c 3})))
(println "PASS: juxt")

;; === fnil ===
(let [safe-inc (fnil inc 0)]
  (assert (= 1 (safe-inc nil)))
  (assert (= 6 (safe-inc 5))))
(println "PASS: fnil")

;; === get-in ===
(assert (= 3 (get-in {:a {:b {:c 3}}} [:a :b :c])))
(assert (= :not-found (get-in {:a 1} [:b :c] :not-found)))
(println "PASS: get-in")

;; === assoc-in ===
(assert (= {:a {:b {:c 42}}} (assoc-in {} [:a :b :c] 42)))
(assert (= {:a {:b 99}} (assoc-in {:a {:b 1}} [:a :b] 99)))
(println "PASS: assoc-in")

;; === update-in ===
(assert (= {:a {:b 2}} (update-in {:a {:b 1}} [:a :b] inc)))
(println "PASS: update-in")

;; === select-keys ===
(assert (= {:a 1 :c 3} (select-keys {:a 1 :b 2 :c 3} [:a :c])))
(println "PASS: select-keys")

;; === zipmap ===
(assert (= {:a 1 :b 2 :c 3} (zipmap [:a :b :c] [1 2 3])))
(println "PASS: zipmap")

;; === flatten ===
(assert (= '(1 2 3 4 5) (flatten [[1 2] [3 [4 5]]])))
(assert (= '() (flatten nil)))
(println "PASS: flatten")

;; === realized? ===
(let [d (delay 42)]
  (assert (not (realized? d)))
  (force d)
  (assert (realized? d)))
(println "PASS: realized?")

;; === type coercion ===
(assert (= 42 (long 42.9)))
(assert (= 42.0 (double 42)))
(assert (= true (boolean 1)))
(assert (= false (boolean nil)))
(println "PASS: type coercion")

;; === import ===
(import java.util.Date)
(assert (instance? Date (Date.)))
(println "PASS: import")

;; === import package list ===
(import (java.util UUID))
(assert (instance? UUID (UUID/randomUUID)))
(println "PASS: import package list")

;; === memfn ===
(let [to-upper (memfn toUpperCase)]
  (assert (= "HELLO" (to-upper "hello"))))
(println "PASS: memfn")

;; === namespace functions ===
(assert (find-ns 'user))
(assert (find-ns 'clojure.core))
(assert (not (nil? (all-ns))))
(println "PASS: namespace functions")

;; === cast ===
(assert (= "hello" (cast String "hello")))
(println "PASS: cast")

;; === supers ===
(assert (contains? (supers String) Object))
(println "PASS: supers")

;; === sorted-map-by ===
(let [m (sorted-map-by (fn [a b] (compare b a)) :a 1 :b 2 :c 3)]
  (assert (= '(:c :b :a) (keys m))))
(println "PASS: sorted-map-by")

(println "\n=== Phase 16: All tests passed! ===")
