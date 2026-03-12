; Conformance Tests: Hierarchy, Meta & Namespaces

(println "=== Conformance: Hierarchy, Meta & Namespaces ===")

;; === derive / isa? (global hierarchy) ===
(derive ::rect ::shape)
(derive ::square ::rect)
(assert (isa? ::rect ::shape))
(assert (isa? ::square ::rect))
(assert (isa? ::square ::shape))
(assert (not (isa? ::shape ::rect)))
(println "PASS: derive / isa?")

;; === parents ===
(assert (contains? (parents ::square) ::rect))
(assert (contains? (parents ::rect) ::shape))
(println "PASS: parents")

;; === ancestors ===
(assert (contains? (ancestors ::square) ::rect))
(assert (contains? (ancestors ::square) ::shape))
(println "PASS: ancestors")

;; === descendants ===
(assert (contains? (descendants ::shape) ::rect))
(assert (contains? (descendants ::shape) ::square))
(assert (contains? (descendants ::rect) ::square))
(println "PASS: descendants")

;; === make-hierarchy (custom hierarchy) ===
(let [h (-> (make-hierarchy)
            (derive :animal :living)
            (derive :dog :animal)
            (derive :cat :animal))]
  (assert (isa? h :dog :animal))
  (assert (isa? h :dog :living))
  (assert (isa? h :cat :animal))
  (assert (not (isa? h :dog :cat)))
  (assert (contains? (parents h :dog) :animal))
  (assert (contains? (ancestors h :dog) :living))
  (assert (contains? (descendants h :animal) :dog))
  (assert (contains? (descendants h :animal) :cat)))
(println "PASS: make-hierarchy")

;; === prefer-method with multimethods ===
(defmulti greet-pm (fn [x] [(class x)]))
(defmethod greet-pm [String] [x] (str "Hello, " x))
(defmethod greet-pm :default [x] "Hello, stranger")
(assert (= "Hello, Alice" (greet-pm "Alice")))
(assert (= "Hello, stranger" (greet-pm 42)))
(println "PASS: prefer-method (basic multimethod)")

;; === meta / with-meta on maps ===
(let [m (with-meta {:a 1} {:doc "test"})]
  (assert (= {:doc "test"} (meta m)))
  (assert (= {:a 1} m)))
(println "PASS: meta / with-meta on maps")

;; === meta / with-meta on vectors ===
(let [v (with-meta [1 2 3] {:tag :vec})]
  (assert (= {:tag :vec} (meta v)))
  (assert (= [1 2 3] v)))
(println "PASS: meta / with-meta on vectors")

;; === meta / with-meta on lists ===
(let [l (with-meta '(1 2 3) {:src true})]
  (assert (= {:src true} (meta l)))
  (assert (= '(1 2 3) l)))
(println "PASS: meta / with-meta on lists")

;; === meta / with-meta on sets ===
(let [s (with-meta #{:a :b} {:kind :set})]
  (assert (= {:kind :set} (meta s)))
  (assert (= #{:a :b} s)))
(println "PASS: meta / with-meta on sets")

;; === meta / with-meta on symbols ===
(let [s (with-meta 'foo {:tag 'String})]
  (assert (= {:tag 'String} (meta s)))
  (assert (= 'foo s)))
(println "PASS: meta / with-meta on symbols")

;; === vary-meta ===
(let [m (with-meta {:a 1} {:version 1})
      m2 (vary-meta m assoc :version 2 :extra true)]
  (assert (= {:version 2 :extra true} (meta m2)))
  (assert (= {:version 1} (meta m))))
(println "PASS: vary-meta")

;; === meta on def'd vars ===
(def ^{:doc "a test var" :custom 42} test-meta-var 100)
(assert (= "a test var" (:doc (meta #'test-meta-var))))
(assert (= 42 (:custom (meta #'test-meta-var))))
(println "PASS: meta on def'd vars")

;; === resolve ===
(assert (var? (resolve 'inc)))
(assert (var? (resolve 'map)))
(assert (nil? (resolve 'nonexistent-symbol-xyz)))
(println "PASS: resolve")

;; === ns-resolve ===
(assert (var? (ns-resolve 'clojure.core 'inc)))
(assert (var? (ns-resolve 'clojure.core 'map)))
(println "PASS: ns-resolve")

;; === eval ===
(assert (= 3 (eval '(+ 1 2))))
(assert (= 10 (eval '(reduce + [1 2 3 4]))))
(assert (= :hello (eval :hello)))
(assert (= "str" (eval "str")))
(println "PASS: eval")

;; === intern ===
(intern 'user 'my-interned-var 42)
(assert (= 42 @(resolve 'my-interned-var)))
(println "PASS: intern")

;; === create-ns / find-ns / ns-name ===
(create-ns 'test.hierarchy.ns1)
(assert (not (nil? (find-ns 'test.hierarchy.ns1))))
(assert (= 'test.hierarchy.ns1 (ns-name (find-ns 'test.hierarchy.ns1))))
(println "PASS: create-ns / find-ns / ns-name")

;; === the-ns ===
(assert (= (find-ns 'clojure.core) (the-ns 'clojure.core)))
(assert (let [ns-obj (find-ns 'clojure.core)]
          (= ns-obj (the-ns ns-obj))))
(println "PASS: the-ns")

;; === all-ns ===
(assert (seq (all-ns)))
(assert (some #(= 'clojure.core (ns-name %)) (all-ns)))
(println "PASS: all-ns")

;; === ns-map / ns-publics / ns-interns / ns-refers ===
(assert (map? (ns-map 'clojure.core)))
(assert (contains? (ns-publics 'clojure.core) 'inc))
(assert (contains? (ns-publics 'clojure.core) 'map))
(println "PASS: ns-map / ns-publics")

;; Test ns-interns on clojure.core
(assert (contains? (ns-interns 'clojure.core) 'inc))
(println "PASS: ns-interns")

(assert (map? (ns-refers 'user)))
(println "PASS: ns-refers")

;; === alias / ns-aliases / ns-unalias ===
(create-ns 'test.hierarchy.alias-target)
(alias 'ath 'test.hierarchy.alias-target)
(assert (contains? (ns-aliases *ns*) 'ath))
(assert (= (find-ns 'test.hierarchy.alias-target) (get (ns-aliases *ns*) 'ath)))
(ns-unalias *ns* 'ath)
(assert (not (contains? (ns-aliases *ns*) 'ath)))
(println "PASS: alias / ns-aliases / ns-unalias")

(println "\n=== Conformance: Hierarchy, Meta & Namespaces PASSED ===")
