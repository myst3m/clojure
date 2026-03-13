; Phase 20-22: Reader, Missing Functions, Sync/STM

;; === Phase 20: Reader Features ===

;; #_ discard reader macro
(assert (= 4 (+ 1 #_ 2 3)))
(println "PASS: #_ discard reader macro")

;; #inst tagged literal
(let [d #inst "2024-01-15"]
  (assert (inst? d)))
(println "PASS: #inst tagged literal")

;; #uuid tagged literal
(let [u #uuid "550e8400-e29b-41d4-a716-446655440000"]
  (assert (uuid? u)))
(println "PASS: #uuid tagged literal")

;; Reader conditionals
(assert (= 1 #?(:clj 1 :cljs 2)))
(println "PASS: #? reader conditionals")

;; ##NaN, ##Inf, ##-Inf (symbolic values)
(assert (NaN? ##NaN))
(assert (infinite? ##Inf))
(assert (infinite? ##-Inf))
(println "PASS: ##NaN ##Inf ##-Inf symbolic values")

;; === Phase 21: Missing Functions ===

;; byte? / short?
(assert (not (byte? 42)))
(assert (not (short? 42)))
(println "PASS: byte? / short?")

;; extends?
(defprotocol Greetable
  (greet [this]))
(extend-type String
  Greetable
  (greet [this] (str "Hello, " this)))
(assert (extends? Greetable String))
(println "PASS: extends?")

;; get-method
(defmulti area :shape)
(defmethod area :circle [{:keys [r]}] (* 3.14 r r))
(defmethod area :rect [{:keys [w h]}] (* w h))
(assert (not (nil? (get-method area :circle))))
(assert (nil? (get-method area :triangle)))
(println "PASS: get-method")

;; replace (sequence)
(assert (= [0 1 2 0 1] (replace {3 0 4 1 5 2} [3 4 5 3 4])))
(assert (= [:a :b :c] (replace {} [:a :b :c])))
(println "PASS: replace")

;; str/escape
(assert (= "a&amp;b" (str/escape "a&b" {\& "&amp;"})))
(println "PASS: str/escape")

;; halt-when — returns accumulator when predicate matches
(let [result (transduce (halt-when #(> % 5)) conj [] [1 2 3 6 7 8])]
  (assert (= [1 2 3] result)))
(println "PASS: halt-when")

;; === Phase 22: Sync / STM ===

;; monitor-enter / monitor-exit (no-op but callable)
(let [obj (Object.)]
  (monitor-enter obj)
  (monitor-exit obj))
(println "PASS: monitor-enter / monitor-exit")

;; ensure with ref
(let [r (ref 42)]
  (dosync
    (assert (= 42 (ensure r)))))
(println "PASS: ensure")

(println "\n=== Phase 20-22: All tests passed! ===")
