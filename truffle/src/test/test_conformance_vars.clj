; Conformance Tests: Vars, Bindings & Special Forms

(println "=== Conformance: Vars, Bindings & Special Forms ===")

;; === def ===
(def my-var 42)
(assert (= 42 my-var))
(def my-var 99)
(assert (= 99 my-var))
(println "PASS: def")

;; === defn ===
(defn my-add [a b] (+ a b))
(assert (= 5 (my-add 2 3)))
(defn my-greet
  ([] "hello")
  ([name] (str "hello " name)))
(assert (= "hello" (my-greet)))
(assert (= "hello world" (my-greet "world")))
(println "PASS: defn")

;; === defn- (private) ===
(defn- private-fn [] :secret)
(assert (= :secret (private-fn)))
(assert (:private (meta #'private-fn)))
(println "PASS: defn-")

;; === defonce ===
(defonce once-var 100)
(defonce once-var 200)
(assert (= 100 once-var))
(println "PASS: defonce")

;; === ^:dynamic and binding ===
(def ^:dynamic *dyn-var* 10)
(assert (= 10 *dyn-var*))
(binding [*dyn-var* 20]
  (assert (= 20 *dyn-var*)))
(assert (= 10 *dyn-var*))
(println "PASS: dynamic vars and binding")

;; === set! in binding context ===
(def ^:dynamic *set-var* 1)
(binding [*set-var* 2]
  (set! *set-var* 3)
  (assert (= 3 *set-var*)))
(assert (= 1 *set-var*))
(println "PASS: set! in binding")

;; === alter-var-root ===
(def alter-test 10)
(alter-var-root #'alter-test (fn [old] (+ old 5)))
(assert (= 15 alter-test))
(println "PASS: alter-var-root")

;; === bound? ===
(def ^:dynamic *bound-test* 42)
(assert (bound? #'*bound-test*))
(println "PASS: bound?")

;; === var? and deref on vars ===
(def var-test 123)
(assert (var? #'var-test))
(assert (not (var? 42)))
(assert (= 123 (deref #'var-test)))
(assert (= 123 @#'var-test))
(println "PASS: var? and deref")

;; === with-redefs ===
(def redef-var 10)
(defn redef-fn [] redef-var)
(with-redefs [redef-var 99]
  (assert (= 99 redef-var)))
(assert (= 10 redef-var))
(println "PASS: with-redefs")

;; === declare ===
(declare forward-fn)
(defn calls-forward [] (forward-fn))
(defn forward-fn [] :forward)
(assert (= :forward (calls-forward)))
(println "PASS: declare")

;; === defmacro ===
(defmacro my-when [test & body]
  `(if ~test (do ~@body)))
(assert (= 42 (my-when true 42)))
(assert (nil? (my-when false 42)))
(println "PASS: defmacro")

;; === macroexpand-1 ===
(defmacro my-inc [x] `(+ ~x 1))
(let [expanded (macroexpand-1 '(my-inc 5))]
  (assert (seq? expanded))
  (assert (= 6 (eval expanded))))
(println "PASS: macroexpand-1")

;; === macroexpand ===
(let [expanded (macroexpand '(my-inc 5))]
  (assert (= 6 (eval expanded))))
(println "PASS: macroexpand")

;; === -> (thread-first) ===
(assert (= 3 (-> 1 inc inc)))
(assert (= "HELLO" (-> "hello" clojure.string/upper-case)))
(assert (= [1 2 3] (-> [1] (conj 2) (conj 3))))
(assert (= 5 (-> {:a {:b 5}} :a :b)))
(println "PASS: ->")

;; === ->> (thread-last) ===
(assert (= 15 (->> (range 1 6) (reduce +))))
(assert (= '(2 4 6) (->> (range 1 7) (filter even?))))
(assert (= [1 4 9] (->> [1 2 3] (map #(* % %)) (into []))))
(println "PASS: ->>")

;; === some-> ===
(assert (= 2 (some-> 1 inc)))
(assert (nil? (some-> nil inc)))
(assert (= "HELLO" (some-> "hello" clojure.string/upper-case)))
(assert (nil? (some-> {:a 1} :b clojure.string/upper-case)))
(println "PASS: some->")

;; === some->> ===
(assert (= 6 (some->> 3 (+ 1) (+ 2))))
(assert (nil? (some->> nil (+ 1))))
(println "PASS: some->>")

;; === cond-> ===
(assert (= 3 (cond-> 1 true inc true inc)))
(assert (= 2 (cond-> 1 true inc false inc)))
(assert (= 1 (cond-> 1 false inc false inc)))
(println "PASS: cond->")

;; === cond->> ===
(assert (= 6 (cond->> 1 true (+ 2) true (+ 3))))
(assert (= 3 (cond->> 1 true (+ 2) false (+ 3))))
(println "PASS: cond->>")

;; === as-> ===
(assert (= 3 (as-> 1 x (inc x) (inc x))))
(assert (= [1 2 3] (as-> [1] x (conj x 2) (conj x 3))))
(assert (= 6 (as-> 0 v (inc v) (+ v 2) (* v 2))))
(println "PASS: as->")

;; === when-let ===
(assert (= 2 (when-let [x 1] (inc x))))
(assert (nil? (when-let [x nil] (inc x))))
(assert (nil? (when-let [x false] :yes)))
(println "PASS: when-let")

;; === if-let ===
(assert (= 2 (if-let [x 1] (inc x) :none)))
(assert (= :none (if-let [x nil] (inc x) :none)))
(assert (= :none (if-let [x false] :yes :none)))
(println "PASS: if-let")

;; === when-some ===
(assert (= 2 (when-some [x 1] (inc x))))
(assert (nil? (when-some [x nil] (inc x))))
(assert (= :yes (when-some [x false] :yes)))
(println "PASS: when-some")

;; === if-some ===
(assert (= 2 (if-some [x 1] (inc x) :none)))
(assert (= :none (if-some [x nil] (inc x) :none)))
(assert (= :yes (if-some [x false] :yes :none)))
(println "PASS: if-some")

;; === loop/recur ===
(assert (= 10 (loop [i 0 sum 0]
                (if (>= i 5)
                  sum
                  (recur (inc i) (+ sum i))))))
;; recur in fn
(defn factorial [n]
  (loop [i n acc 1]
    (if (<= i 1)
      acc
      (recur (dec i) (* acc i)))))
(assert (= 120 (factorial 5)))
(assert (= 1 (factorial 0)))
;; nested loop
(assert (= [[0 0] [0 1] [1 0] [1 1]]
           (loop [i 0 result []]
             (if (>= i 2)
               result
               (recur (inc i)
                      (loop [j 0 r result]
                        (if (>= j 2)
                          r
                          (recur (inc j) (conj r [i j])))))))))
(println "PASS: loop/recur")

;; === lazy-cat ===
(assert (= [1 2 3 4 5 6] (lazy-cat [1 2 3] [4 5 6])))
(assert (= [1 2 3] (lazy-cat [1 2 3] [])))
(assert (= () (lazy-cat)))
(println "PASS: lazy-cat")

;; === or (short-circuit) ===
(assert (= 1 (or 1 2 3)))
(assert (= 1 (or nil false 1)))
(assert (nil? (or nil false nil)))
(assert (= false (or nil false false)))  ;; last value if all falsy
(let [a (atom 0)]
  (or true (do (swap! a inc) false))
  (assert (= 0 @a)))  ;; short-circuit: a not incremented
(println "PASS: or")

;; === and (short-circuit) ===
(assert (= 3 (and 1 2 3)))
(assert (= nil (and 1 nil 3)))
(assert (= false (and false 2 3)))
(let [a (atom 0)]
  (and false (do (swap! a inc) true))
  (assert (= 0 @a)))  ;; short-circuit: a not incremented
(println "PASS: and")

;; === do ===
(assert (= 3 (do 1 2 3)))
(let [a (atom 0)]
  (do (swap! a inc) (swap! a inc) (swap! a inc))
  (assert (= 3 @a)))
(println "PASS: do")

;; === let with shadowing ===
(let [x 1]
  (assert (= 1 x))
  (let [x 2]
    (assert (= 2 x))
    (let [x 3]
      (assert (= 3 x)))
    (assert (= 2 x)))
  (assert (= 1 x)))
;; shadowing in same let
(let [x 1
      x (+ x 10)
      x (* x 2)]
  (assert (= 22 x)))
(println "PASS: let shadowing")

;; === if ===
(assert (= :yes (if true :yes :no)))
(assert (= :no (if false :yes :no)))
(assert (= :no (if nil :yes :no)))
(assert (= :yes (if 0 :yes :no)))       ;; 0 is truthy
(assert (= :yes (if "" :yes :no)))       ;; "" is truthy
(assert (nil? (if false :yes)))          ;; no else → nil
(println "PASS: if")

;; === when ===
(assert (= :yes (when true :yes)))
(assert (nil? (when false :yes)))
(let [a (atom 0)]
  (when true (swap! a inc) (swap! a inc))
  (assert (= 2 @a)))
(println "PASS: when")

;; === when-not ===
(assert (nil? (when-not true :yes)))
(assert (= :yes (when-not false :yes)))
(assert (= :yes (when-not nil :yes)))
(println "PASS: when-not")

;; === if-not ===
(assert (= :no (if-not true :yes :no)))
(assert (= :yes (if-not false :yes :no)))
(assert (= :yes (if-not nil :yes :no)))
(println "PASS: if-not")

;; === cond ===
(assert (= :a (cond true :a true :b)))
(assert (= :b (cond false :a true :b)))
(assert (nil? (cond false :a false :b)))
(assert (= :default (cond
                      (= 1 2) :nope
                      (= 2 3) :nope
                      :else :default)))
(println "PASS: cond")

;; === doto ===
(let [sb (doto (java.util.ArrayList.)
           (.add 1)
           (.add 2)
           (.add 3))]
  (assert (= 3 (.size sb)))
  (assert (= 1 (.get sb 0)))
  (assert (= 2 (.get sb 1)))
  (assert (= 3 (.get sb 2))))
(println "PASS: doto")

;; === .. (dot-dot) ===
(assert (= 5 (.. "hello" (length))))
(assert (= "HELLO" (.. "hello" (toUpperCase))))
(assert (= "ELL" (.. "hello" (toUpperCase) (substring 1 4))))
(println "PASS: ..")

(println "\n=== Conformance: Vars, Bindings & Special Forms PASSED ===")
