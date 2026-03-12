; Conformance Tests: Functions, Closures, Destructuring

(println "=== Conformance: Functions & Destructuring ===")

;; === multi-arity fn ===
(defn multi-arity
  ([] "zero")
  ([x] (str "one:" x))
  ([x y] (str "two:" x "," y))
  ([x y z] (str "three:" x "," y "," z)))
(assert (= "zero" (multi-arity)))
(assert (= "one:a" (multi-arity "a")))
(assert (= "two:a,b" (multi-arity "a" "b")))
(assert (= "three:a,b,c" (multi-arity "a" "b" "c")))
(println "PASS: multi-arity fn")

;; === variadic fn ===
(defn variadic [x & more] [x more])
(assert (= [1 nil] (variadic 1)))
(assert (= [1 '(2 3)] (variadic 1 2 3)))
(println "PASS: variadic fn")

;; === partial ===
(def add5 (partial + 5))
(assert (= 8 (add5 3)))
(assert (= 15 (add5 10)))
(def prepend-hello (partial str "Hello "))
(assert (= "Hello World" (prepend-hello "World")))
(println "PASS: partial")

;; === comp ===
(def inc-then-double (comp (partial * 2) inc))
(assert (= 6 (inc-then-double 2)))
(assert (= 10 (inc-then-double 4)))
(def upper-trim (comp clojure.string/upper-case clojure.string/trim))
(assert (= "HELLO" (upper-trim "  hello  ")))
(println "PASS: comp")

;; === complement ===
(def odd-not-even? (complement even?))
(assert (odd-not-even? 3))
(assert (not (odd-not-even? 4)))
(println "PASS: complement")

;; === constantly ===
(assert (= 42 ((constantly 42) 1 2 3)))
(assert (= '(42 42 42) (map (constantly 42) [1 2 3])))
(println "PASS: constantly")

;; === memoize ===
(def call-count (atom 0))
(def memo-fn (memoize (fn [x] (swap! call-count inc) (* x x))))
(assert (= 25 (memo-fn 5)))
(assert (= 25 (memo-fn 5)))
(assert (= 1 @call-count)) ;; called only once
(assert (= 9 (memo-fn 3)))
(assert (= 2 @call-count))
(println "PASS: memoize")

;; === letfn ===
(assert (= true (letfn [(even?' [n] (if (zero? n) true (odd?' (dec n))))
                         (odd?' [n] (if (zero? n) false (even?' (dec n))))]
                  (even?' 10))))
(println "PASS: letfn")

;; === apply ===
(assert (= 15 (apply + [1 2 3 4 5])))
(assert (= 15 (apply + 1 2 [3 4 5])))
(assert (= "abc" (apply str ["a" "b" "c"])))
(println "PASS: apply")

;; === identity ===
(assert (= 42 (identity 42)))
(assert (= [1 2 3] (filter identity [1 nil 2 false 3])))
(println "PASS: identity")

;; === Sequential destructuring ===
(let [[a b c] [1 2 3]]
  (assert (= 1 a))
  (assert (= 2 b))
  (assert (= 3 c)))
(println "PASS: sequential destructuring")

;; === Sequential destructuring with & rest ===
(let [[a b & rest] [1 2 3 4 5]]
  (assert (= 1 a))
  (assert (= 2 b))
  (assert (= '(3 4 5) rest)))
(println "PASS: sequential destructuring with & rest")

;; === Sequential destructuring with :as ===
(let [[a b :as all] [1 2 3]]
  (assert (= 1 a))
  (assert (= 2 b))
  (assert (= [1 2 3] all)))
(println "PASS: sequential destructuring with :as")

;; === Map destructuring ===
(let [{:keys [a b c]} {:a 1 :b 2 :c 3}]
  (assert (= 1 a))
  (assert (= 2 b))
  (assert (= 3 c)))
(println "PASS: map destructuring :keys")

;; === Map destructuring with :or defaults ===
(let [{:keys [a b c] :or {c 99}} {:a 1 :b 2}]
  (assert (= 1 a))
  (assert (= 2 b))
  (assert (= 99 c)))
(println "PASS: map destructuring :or")

;; === Map destructuring with :as ===
(let [{:keys [a b] :as m} {:a 1 :b 2 :c 3}]
  (assert (= 1 a))
  (assert (= 2 b))
  (assert (= {:a 1 :b 2 :c 3} m)))
(println "PASS: map destructuring :as")

;; === Map destructuring :strs ===
(let [{:strs [foo bar]} {"foo" 1 "bar" 2}]
  (assert (= 1 foo))
  (assert (= 2 bar)))
(println "PASS: map destructuring :strs")

;; === Nested destructuring ===
(let [{[a b] :pair} {:pair [10 20]}]
  (assert (= 10 a))
  (assert (= 20 b)))
(println "PASS: nested destructuring")

;; === fn destructuring ===
(defn point-str [{:keys [x y]}] (str x "," y))
(assert (= "1,2" (point-str {:x 1 :y 2})))
(println "PASS: fn destructuring")

;; === every-pred ===
(def pos-even? (every-pred pos? even?))
(assert (pos-even? 2))
(assert (pos-even? 4))
(assert (not (pos-even? -2)))
(assert (not (pos-even? 3)))
(println "PASS: every-pred")

;; === some-fn ===
(def num-or-str? (some-fn number? string?))
(assert (num-or-str? 42))
(assert (num-or-str? "hello"))
(assert (not (num-or-str? :keyword)))
(println "PASS: some-fn")

(println "\n=== Conformance: Functions & Destructuring PASSED ===")
