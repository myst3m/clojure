; Conformance Tests: Edge Cases & Deep Coverage
; Tests for subtle Clojure behaviors and edge cases

(println "=== Conformance: Edge Cases ===")

;; === Numeric edge cases ===
(assert (= 1 (+ 1)))
(assert (= 0 (+)))
(assert (= 1 (* 1)))
(assert (= 1 (*)))
(assert (= -1 (- 1)))
(assert (= 0 (- 1 1)))
(assert (= Double/POSITIVE_INFINITY (/ 1.0 0.0)))
(assert (= Double/NEGATIVE_INFINITY (/ -1.0 0.0)))
(assert (NaN? (/ 0.0 0.0)))
(assert (= 1 (min 1 2 3)))
(assert (= 3 (max 1 2 3)))
(assert (= 1 (min 1)))
(assert (= 1 (max 1)))
(assert (= 7 (bit-or 3 5)))
(assert (= 1 (bit-and 3 5)))
(assert (= 6 (bit-xor 3 5)))
(assert (= -1 (bit-not 0)))
(assert (= 4 (bit-shift-left 1 2)))
(assert (= 2 (bit-shift-right 8 2)))
(assert (= 2 (unsigned-bit-shift-right 8 2)))
(println "PASS: numeric edge cases")

;; === Ratio ===
(assert (= 1/2 (/ 1 2)))
(assert (= 1 (/ 2 2)))
(assert (ratio? 1/3))
(assert (not (ratio? 1)))
(assert (== 1 (numerator 1/3)))
(assert (== 3 (denominator 1/3)))
(println "PASS: ratio")

;; === BigInt / BigDecimal ===
(assert (= 1N (bigint 1)))
(assert (= 1.0M (bigdec 1)))
(assert (= 100N (* 10N 10)))
(assert (integer? 1N))
(assert (decimal? 1.0M))
(println "PASS: bigint/bigdec")

;; === String edge cases ===
(assert (= "" (str)))
(assert (= "" (str nil)))
(assert (= "12" (str 1 2)))
(assert (= "null" (str "null")))
(assert (= "a" (str \a)))
(assert (= 5 (count "hello")))
(assert (= \h (first "hello")))
(assert (= \o (last "hello")))
(assert (= "ello" (subs "hello" 1)))
(assert (= "ell" (subs "hello" 1 4)))
(assert (= [\h \e \l \l \o] (vec "hello")))
(println "PASS: string edge cases")

;; === Char operations ===
(assert (= 97 (int \a)))
(assert (= \a (char 97)))
(assert (char? \a))
(assert (not (char? "a")))
(println "PASS: char operations")

;; === Collection edge cases ===
;; assoc on vector
(assert (= [10 2 3] (assoc [1 2 3] 0 10)))
(assert (= [1 2 10] (assoc [1 2 3] 2 10)))
;; get with default
(assert (= :default (get {} :a :default)))
(assert (= :default (get nil :a :default)))
(assert (= nil (get {} :a)))
;; nth with default
(assert (= :default (nth [] 0 :default)))
(assert (= :default (nth nil 0 :default)))
;; update
(assert (= {:a 2} (update {:a 1} :a inc)))
(assert (= {:a 1} (update {} :a (fnil inc 0))))
;; update-in
(assert (= {:a {:b 2}} (update-in {:a {:b 1}} [:a :b] inc)))
;; assoc-in
(assert (= {:a {:b 1}} (assoc-in {} [:a :b] 1)))
;; get-in
(assert (= 1 (get-in {:a {:b 1}} [:a :b])))
(assert (= :nope (get-in {:a {:b 1}} [:a :c] :nope)))
;; select-keys
(assert (= {:a 1} (select-keys {:a 1 :b 2 :c 3} [:a])))
(assert (= {} (select-keys {:a 1} [:b])))
;; find
(assert (= [:a 1] (find {:a 1 :b 2} :a)))
(assert (nil? (find {:a 1} :b)))
(println "PASS: collection edge cases")

;; === Seq operations edge cases ===
(assert (= '(1 2 3 4) (concat [1 2] [3 4])))
(assert (= () (concat)))
(assert (= () (concat nil nil)))
(assert (= '(1 2) (concat [1] [2])))
(assert (= '(1 2 3) (flatten [1 [2 [3]]])))
(assert (= '(1 2 3) (flatten '(1 (2 (3))))))
(assert (= () (flatten nil)))
(assert (= '([1 :a] [2 :b]) (map vector [1 2] [:a :b])))
(assert (= '(1 2 3) (mapcat #(list % ) [1 2 3])))
(assert (= '(1 3) (keep-indexed (fn [i v] (when (even? i) v)) [1 2 3 4])))
(assert (= 6 (reduce + [1 2 3])))
(assert (= 10 (reduce + 0 [1 2 3 4])))
(assert (= 0 (reduce + 0 [])))
(assert (= [1 3 6] (reductions + [1 2 3])))
(assert (= [0 1 3 6] (reductions + 0 [1 2 3])))
(println "PASS: seq operations edge cases")

;; === Sorted collections ===
(assert (= [1 2 3] (vec (sorted-set 3 1 2))))
(assert (= [:a :b :c] (keys (sorted-map :b 2 :a 1 :c 3))))
(assert (= [1 2 3] (vec (sorted-set-by < 3 1 2))))
(assert (= [3 2 1] (vec (sorted-set-by > 3 1 2))))
(println "PASS: sorted collections")

;; === Transient collections ===
(assert (= [1 2 3] (persistent! (conj! (conj! (conj! (transient []) 1) 2) 3))))
(assert (= {:a 1 :b 2} (persistent! (assoc! (transient {}) :a 1 :b 2))))
(assert (= {:a 1} (persistent! (dissoc! (transient {:a 1 :b 2}) :b))))
(println "PASS: transient collections")

;; === Lazy sequences ===
(assert (= '(0 1 2 3 4) (take 5 (range))))
(assert (= '(0 1 2 3 4) (range 5)))
(assert (= '(2 3 4) (range 2 5)))
(assert (= '(0 2 4) (range 0 5 2)))
(assert (= () (range 0)))
(assert (= '(1 1 1) (take 3 (repeat 1))))
(assert (= '(1 1 1) (repeat 3 1)))
(assert (= '(:a :a :a) (repeat 3 :a)))
(assert (= '(0 1 2 3 4) (take 5 (iterate inc 0))))
(assert (= '(1 2 3 1 2 3) (take 6 (cycle [1 2 3]))))
(println "PASS: lazy sequences")

;; === let/loop/recur ===
(assert (= 55 (loop [i 0 sum 0]
                (if (> i 10) sum (recur (inc i) (+ sum i))))))
(assert (= 120 (let [fact (fn [n]
                           (loop [i n acc 1]
                             (if (<= i 1) acc (recur (dec i) (* acc i)))))]
                 (fact 5))))
(println "PASS: let/loop/recur")

;; === Destructuring edge cases ===
(let [[a b & rest] [1 2 3 4 5]]
  (assert (= 1 a))
  (assert (= 2 b))
  (assert (= '(3 4 5) rest)))
(let [{:keys [a b] :or {b 99}} {:a 1}]
  (assert (= 1 a))
  (assert (= 99 b)))
(let [[a [b c]] [1 [2 3]]]
  (assert (= 1 a))
  (assert (= 2 b))
  (assert (= 3 c)))
(let [{:keys [a] :as m} {:a 1 :b 2}]
  (assert (= 1 a))
  (assert (= {:a 1 :b 2} m)))
(println "PASS: destructuring edge cases")

;; === Truthiness ===
(assert (if true :yes :no))
(assert (if 0 :yes :no))      ; 0 is truthy in Clojure
(assert (if "" :yes :no))     ; "" is truthy
(assert (if [] :yes :no))     ; [] is truthy
(assert (not (if nil :yes false)))
(assert (not (if false :yes false)))
(println "PASS: truthiness")

;; === when/when-not/when-let ===
(assert (= 1 (when true 1)))
(assert (nil? (when false 1)))
(assert (nil? (when-not true 1)))
(assert (= 1 (when-not false 1)))
(assert (= 1 (when-let [x 1] x)))
(assert (nil? (when-let [x nil] x)))
(println "PASS: when/when-not/when-let")

;; === if-let/if-some ===
(assert (= :yes (if-let [x 1] :yes :no)))
(assert (= :no (if-let [x nil] :yes :no)))
(assert (= :yes (if-some [x 0] :yes :no)))    ; 0 is not nil
(assert (= :yes (if-some [x false] :yes :no)))  ; false is not nil
(assert (= :no (if-some [x nil] :yes :no)))
(println "PASS: if-let/if-some")

;; === cond/condp/case ===
(assert (= :pos (cond (pos? 1) :pos (neg? 1) :neg :else :zero)))
(assert (= :no (condp = 3 1 :one 2 :two :no)))
(assert (= :one (case 1 1 :one 2 :two :other)))
(assert (= :other (case 99 1 :one 2 :two :other)))
(println "PASS: cond/condp/case")

;; === Multi-arity fn ===
(let [f (fn
          ([x] x)
          ([x y] (+ x y))
          ([x y z] (+ x y z)))]
  (assert (= 1 (f 1)))
  (assert (= 3 (f 1 2)))
  (assert (= 6 (f 1 2 3))))
(println "PASS: multi-arity fn")

;; === apply ===
(assert (= 6 (apply + [1 2 3])))
(assert (= 10 (apply + 1 [2 3 4])))
(assert (= 15 (apply + 1 2 [3 4 5])))
(assert (= "abc" (apply str ["a" "b" "c"])))
(println "PASS: apply")

;; === comp/partial/juxt ===
(assert (= 5 ((comp inc inc inc) 2)))
(assert (= 10 ((partial + 3) 7)))
(assert (= [1 -1] ((juxt inc dec) 0)))
(assert (= 12 ((comp (partial * 3) (partial + 1)) 3)))
(println "PASS: comp/partial/juxt")

;; === every?/some/not-any?/not-every? ===
(assert (every? even? [2 4 6]))
(assert (not (every? even? [2 3 4])))
(assert (some even? [1 2 3]))
(assert (not (some even? [1 3 5])))
(assert (not-any? even? [1 3 5]))
(assert (not (not-any? even? [1 2 3])))
(assert (not-every? even? [1 2 3]))
(assert (not (not-every? even? [2 4 6])))
(println "PASS: every?/some/not-any?/not-every?")

;; === group-by/frequencies/distinct ===
(assert (= {true [2 4] false [1 3 5]} (group-by even? [1 2 3 4 5])))
(assert (= {:a 2 :b 1 :c 1} (frequencies [:a :b :a :c])))
(assert (= '(1 2 3) (distinct [1 2 1 3 2])))
(println "PASS: group-by/frequencies/distinct")

;; === zipmap/interleave/interpose ===
(assert (= {:a 1 :b 2} (zipmap [:a :b] [1 2])))
(assert (= '(:a 1 :b 2) (interleave [:a :b] [1 2])))
(assert (= '(1 :sep 2 :sep 3) (interpose :sep [1 2 3])))
(println "PASS: zipmap/interleave/interpose")

;; === partition/partition-all/partition-by ===
(assert (= '((1 2) (3 4)) (partition 2 [1 2 3 4 5])))
(assert (= '((1 2) (3 4) (5)) (partition-all 2 [1 2 3 4 5])))
(assert (= '([1 1] [2 2] [1]) (partition-by identity [1 1 2 2 1])))
(assert (= '((1 2) (2 3) (3 4)) (partition 2 1 [1 2 3 4])))
(println "PASS: partition/partition-all/partition-by")

;; === take-while/drop-while/split-with ===
(assert (= '(1 2 3) (take-while #(< % 4) [1 2 3 4 5])))
(assert (= '(4 5) (drop-while #(< % 4) [1 2 3 4 5])))
(assert (= ['(1 2 3) '(4 5)] (split-with #(< % 4) [1 2 3 4 5])))
(println "PASS: take-while/drop-while/split-with")

;; === take-nth/take-last/drop-last ===
(assert (= '(0 3 6 9) (take-nth 3 (range 10))))
(assert (= '(3 4) (take-last 2 [1 2 3 4])))
(assert (= '(1 2) (drop-last 2 [1 2 3 4])))
(assert (= '(1 2 3) (drop-last [1 2 3 4])))
(println "PASS: take-nth/take-last/drop-last")

;; === Metadata ===
(let [v (with-meta [1 2 3] {:tag "test"})]
  (assert (= {:tag "test"} (meta v)))
  (assert (= [1 2 3] v)))
(assert (nil? (meta [1 2 3])))
(assert (= {:a 1 :b 2} (meta (vary-meta (with-meta [] {:a 1}) assoc :b 2))))
(println "PASS: metadata")

;; === Atoms with validators and watches ===
(let [a (atom 0 :validator pos?)]
  ;; Initial value 0 is not pos?, so this may throw
  ;; Actually Clojure allows initial value that fails validator
  ;; Let's test with valid initial value
  true)
(let [a (atom 1 :validator pos?)]
  (swap! a inc)
  (assert (= 2 @a))
  (try (swap! a (fn [_] -1)) (catch Exception e nil))
  (assert (= 2 @a))) ; value unchanged after failed validation
(println "PASS: atom validators")

;; === Watches ===
(let [log (atom [])
      a (atom 0)]
  (add-watch a :w (fn [k r old new] (swap! log conj [old new])))
  (swap! a inc)
  (swap! a inc)
  (assert (= [[0 1] [1 2]] @log))
  (remove-watch a :w)
  (swap! a inc)
  (assert (= [[0 1] [1 2]] @log))) ; no more logging after remove
(println "PASS: watches")

;; === Vars: dynamic binding ===
(def ^:dynamic *test-var* 10)
(assert (= 10 *test-var*))
(binding [*test-var* 20]
  (assert (= 20 *test-var*)))
(assert (= 10 *test-var*))
(println "PASS: dynamic binding")

;; === try/catch/finally ===
(assert (= :caught (try (throw (Exception. "test")) (catch Exception e :caught))))
(let [side (atom nil)]
  (try (throw (Exception. "test"))
       (catch Exception e nil)
       (finally (reset! side :done)))
  (assert (= :done @side)))
(assert (= 42 (try 42 (finally nil))))
(println "PASS: try/catch/finally")

;; === ex-info/ex-data/ex-message ===
(let [e (ex-info "test error" {:code 42})]
  (assert (= "test error" (ex-message e)))
  (assert (= {:code 42} (ex-data e))))
(let [e (ex-info "wrapped" {:a 1} (Exception. "cause"))]
  (assert (= "wrapped" (ex-message e)))
  (assert (instance? Exception (ex-cause e))))
(println "PASS: ex-info/ex-data/ex-message")

;; === Multimethods: default and hierarchy ===
(defmulti shape-area :shape)
(defmethod shape-area :circle [{:keys [r]}] (* Math/PI r r))
(defmethod shape-area :square [{:keys [side]}] (* side side))
(defmethod shape-area :default [_] 0)
(assert (> (shape-area {:shape :circle :r 1}) 3.14))
(assert (= 9 (shape-area {:shape :square :side 3})))
(assert (= 0 (shape-area {:shape :unknown})))
(println "PASS: multimethods")

;; === Protocols ===
(defprotocol Greetable
  (greet [this]))
(defrecord Person [name]
  Greetable
  (greet [this] (str "Hello, " (:name this))))
(assert (= "Hello, World" (greet (->Person "World"))))
(assert (satisfies? Greetable (->Person "X")))
(println "PASS: protocols")

;; === Infinite lazy seq safety ===
(assert (= 10 (count (take 10 (range)))))
(assert (= 5 (nth (range) 5)))
(assert (= '(true false true false true) (take 5 (cycle [true false]))))
(println "PASS: infinite lazy seq safety")

;; === Regex ===
(assert (some? (re-find #"hello" "hello world")))
(assert (nil? (re-find #"xyz" "hello")))
(assert (= "hello" (re-find #"\w+" "hello world")))
(assert (= ["hello" "world"] (re-seq #"\w+" "hello world")))
(assert (= ["hello" "hel"] (re-find #"(hel)\w+" "hello")))
(assert (= [["hello" "hel"] ["help" "hel"]] (re-seq #"(hel)\w+" "hello help")))
(assert (= "h-ll-" (clojure.string/replace "hello" #"[eo]" "-")))
(println "PASS: regex")

;; === Threading macros ===
(assert (= 3 (-> 1 inc inc)))
(assert (= 5 (-> 10 (/ 2))))
(assert (= [1 2 3] (-> [] (conj 1) (conj 2) (conj 3))))
(assert (= 2 (->> 1 inc)))
(assert (= 10 (->> (range 5) (reduce +))))
(assert (= [2 4 6] (->> [1 2 3] (map #(* 2 %)))))
(println "PASS: threading macros")

;; === some-> / some->> ===
(assert (= 3 (some-> 1 inc inc)))
(assert (nil? (some-> nil inc)))
(assert (= 2 (some->> 1 inc)))
(assert (nil? (some->> nil inc)))
(println "PASS: some->/some->>")

;; === as-> ===
(assert (= 3 (as-> 1 x (inc x) (inc x))))
(assert (= "1!" (as-> 1 x (str x) (str x "!"))))
(println "PASS: as->")

;; === cond-> / cond->> ===
(assert (= 2 (cond-> 1 true inc false dec)))
(assert (= 0 (cond-> 1 true dec true identity)))
(assert (= [1 2 3] (cond->> [1 2 3] false (map inc))))
(assert (= '(2 3 4) (cond->> [1 2 3] true (map inc))))
(println "PASS: cond->/cond->>")

;; === doto ===
(let [sb (doto (java.util.ArrayList.)
           (.add 1)
           (.add 2)
           (.add 3))]
  (assert (= 3 (.size sb)))
  (assert (= 1 (.get sb 0))))
(println "PASS: doto")

;; === for (list comprehension) ===
(assert (= '(2 3 3 4 4 5) (for [x [1 2 3] y [1 2]] (+ x y))))
(assert (= '(0 2 4) (for [x (range 5) :when (even? x)] x)))
(assert (= '([1 0] [2 0] [2 1]) (for [x (range 3) y (range x)] [x y])))
(println "PASS: for")

;; === dotimes ===
(let [counter (atom 0)]
  (dotimes [_ 5] (swap! counter inc))
  (assert (= 5 @counter)))
(println "PASS: dotimes")

;; === while ===
(let [a (atom 0)]
  (while (< @a 5) (swap! a inc))
  (assert (= 5 @a)))
(println "PASS: while")

;; === -> with Java interop ===
(assert (= "HELLO" (.toUpperCase "hello")))
(assert (= 5 (.length "hello")))
(assert (= "123" (String/valueOf 123)))
(assert (= 42 (Integer/parseInt "42")))
(println "PASS: Java interop")

;; === empty/not-empty ===
(assert (= [] (empty [1 2 3])))
(assert (= {} (empty {:a 1})))
(assert (= #{} (empty #{1 2})))
(assert (= () (empty '(1 2))))
(assert (nil? (not-empty [])))
(assert (nil? (not-empty {})))
(assert (= [1 2] (not-empty [1 2])))
(println "PASS: empty/not-empty")

;; === into edge cases ===
(assert (= {:a 1 :b 2} (into {} [[:a 1] [:b 2]])))
(assert (= #{1 2 3} (into #{} [1 2 3 2 1])))
(assert (= [1 2 3 4] (into [1 2] [3 4])))
(assert (= '(2 1) (into () [1 2])))
(println "PASS: into edge cases")

;; === Keyword/Symbol operations ===
(assert (= :hello (keyword "hello")))
(assert (= :foo/bar (keyword "foo" "bar")))
(assert (= 'hello (symbol "hello")))
(assert (= 'foo/bar (symbol "foo" "bar")))
(assert (= "hello" (name :hello)))
(assert (= "bar" (name :foo/bar)))
(assert (= "foo" (namespace :foo/bar)))
(assert (nil? (namespace :hello)))
(println "PASS: keyword/symbol operations")

;; === Comparison ===
(assert (= -1 (compare 1 2)))
(assert (= 0 (compare 1 1)))
(assert (= 1 (compare 2 1)))
(assert (= -1 (compare "a" "b")))
(assert (= 0 (compare :a :a)))
(println "PASS: compare")

;; === identity/constantly ===
(assert (= 42 (identity 42)))
(assert (nil? (identity nil)))
(assert (= 42 ((constantly 42) 1 2 3)))
(assert (= nil ((constantly nil))))
(println "PASS: identity/constantly")

;; === memoize ===
(let [call-count (atom 0)
      f (memoize (fn [x] (swap! call-count inc) (* x x)))]
  (assert (= 4 (f 2)))
  (assert (= 4 (f 2)))
  (assert (= 1 @call-count))) ; called only once
(println "PASS: memoize")

;; === trampoline ===
(defn even-t? [n]
  (if (zero? n) true #(odd-t? (dec n))))
(defn odd-t? [n]
  (if (zero? n) false #(even-t? (dec n))))
(assert (= true (trampoline even-t? 10)))
(assert (= false (trampoline even-t? 11)))
(println "PASS: trampoline")

;; === tree-seq ===
(assert (= [[1 [2 [3]]] 1 [2 [3]] 2 [3] 3] (tree-seq sequential? seq [1 [2 [3]]])))
(println "PASS: tree-seq")

;; === mapv/filterv ===
(assert (= [2 4 6] (mapv #(* 2 %) [1 2 3])))
(assert (vector? (mapv inc [1 2])))
(assert (= [2 4] (filterv even? [1 2 3 4 5])))
(assert (vector? (filterv even? [1 2])))
(println "PASS: mapv/filterv")

;; === reduce-kv ===
(assert (= 6 (reduce-kv (fn [acc k v] (+ acc v)) 0 {:a 1 :b 2 :c 3})))
(assert (= {:a 2 :b 4} (reduce-kv (fn [m k v] (assoc m k (* 2 v))) {} {:a 1 :b 2})))
(println "PASS: reduce-kv")

;; === bounded-count ===
(assert (= 3 (bounded-count 10 [1 2 3])))
(assert (= 10 (bounded-count 10 (range))))
(println "PASS: bounded-count")

;; === run! ===
(let [a (atom [])]
  (run! #(swap! a conj %) [1 2 3])
  (assert (= [1 2 3] @a)))
(println "PASS: run!")

;; === Chained operations ===
(assert (= 40 (->> (range 10) (filter even?) (map #(* 2 %)) (reduce +))))
(assert (= {:b 2 :d 4} (->> {:a 1 :b 2 :c 3 :d 4}
                             (filter (fn [[k v]] (even? v)))
                             (into {}))))
(println "PASS: chained operations")

(println "=== Conformance: Edge Cases PASSED ===")
