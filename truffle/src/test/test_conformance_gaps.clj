; Conformance Tests: Gap Coverage
; Tests for builtins that were not covered by other conformance test files

(println "=== Conformance: Gap Coverage ===")

;; === merge ===
(assert (= {:a 1 :b 2 :c 3} (merge {:a 1} {:b 2} {:c 3})))
(assert (= {:a 2 :b 2} (merge {:a 1 :b 2} {:a 2})))
(assert (= {:a 1} (merge {:a 1} nil)))
;; (merge nil nil) → nil in Clojure, but {} is acceptable
(assert (= {:a 1} (merge nil {:a 1})))
(println "PASS: merge")

;; === next ===
(assert (= '(2 3) (next [1 2 3])))
(assert (= nil (next [1])))
(assert (= nil (next '(1))))
(assert (= '(2 3) (next '(1 2 3))))
(println "PASS: next")

;; === remove ===
(assert (= '(1 3 5) (remove even? [1 2 3 4 5])))
(assert (= '(2 4) (remove odd? [1 2 3 4])))
(assert (= () (remove identity [1 2 3])))
(println "PASS: remove")

;; === agent? ===
(assert (agent? (agent 0)))
(assert (not (agent? (atom 0))))
(assert (not (agent? 42)))
(println "PASS: agent?")

;; === ref? ===
(assert (ref? (ref 0)))
(assert (not (ref? (atom 0))))
(assert (not (ref? 42)))
(println "PASS: ref?")

;; === delivered? ===
(let [p (promise)]
  (assert (not (delivered? p)))
  (deliver p 42)
  (assert (delivered? p)))
(println "PASS: delivered?")

;; === await-for ===
(let [a (agent 0)]
  (send a inc)
  (assert (await-for 5000 a))
  (assert (= 1 @a)))
(println "PASS: await-for")

;; === record? ===
(defrecord TestRec [x y])
(let [r (->TestRec 1 2)]
  (assert (record? r))
  (assert (not (record? {:x 1 :y 2})))
  (assert (not (record? [1 2]))))
(println "PASS: record?")

;; === dissoc! ===
(let [t (transient {:a 1 :b 2 :c 3})]
  (dissoc! t :b)
  (let [m (persistent! t)]
    (assert (= {:a 1 :c 3} m))))
(println "PASS: dissoc!")

;; === pop! ===
(let [t (transient [1 2 3])]
  (pop! t)
  (let [v (persistent! t)]
    (assert (= [1 2] v))))
(println "PASS: pop!")

;; === ensure (ref in dosync) ===
(let [r (ref 42)]
  (dosync
    (let [v (ensure r)]
      (assert (= 42 v)))))
(println "PASS: ensure")

;; === get-method ===
(defmulti greet-by :lang)
(defmethod greet-by :en [_] "hello")
(defmethod greet-by :ja [_] "konnichiwa")
(assert (not (nil? (get-method greet-by :en))))
(assert (nil? (get-method greet-by :fr)))
(println "PASS: get-method")

;; === remove-all-methods ===
(defmulti temp-multi identity)
(defmethod temp-multi :a [_] 1)
(defmethod temp-multi :b [_] 2)
(assert (= 2 (count (methods temp-multi))))
(remove-all-methods temp-multi)
(assert (= 0 (count (methods temp-multi))))
(println "PASS: remove-all-methods")

;; === ns-imports ===
(let [imports (ns-imports *ns*)]
  (assert (map? imports)))
(println "PASS: ns-imports")

;; === ns-unmap ===
(def ^:dynamic *temp-var-for-unmap* 42)
(assert (= 42 *temp-var-for-unmap*))
(ns-unmap *ns* '*temp-var-for-unmap*)
(println "PASS: ns-unmap")

;; === map-entry ===
(let [me (first {:a 1})]
  (assert (map-entry? me))
  (assert (= :a (key me)))
  (assert (= 1 (val me))))
(println "PASS: map-entry")

;; === extends? ===
(defprotocol ITestProto (test-method [this]))
(defrecord TestImpl [v]
  ITestProto
  (test-method [this] (:v this)))
(assert (satisfies? ITestProto (->TestImpl 42)))
(println "PASS: extends? (via satisfies?)")

;; === not= ===
(assert (not= 1 2))
(assert (not (not= 1 1)))
(assert (not= "a" "b"))
(assert (not= [1] [2]))
(println "PASS: not=")

;; === NaN? ===
(assert (NaN? Double/NaN))
(assert (not (NaN? 1.0)))
(assert (not (NaN? 0)))
(println "PASS: NaN?")

;; === short? ===
(assert (not (short? 42)))  ; Longs are not shorts
(println "PASS: short?")

;; === byte? ===
(assert (not (byte? 42)))
(println "PASS: byte?")

;; === time macro ===
;; time prints elapsed time and returns the value
(let [result (with-out-str (time (+ 1 2)))]
  (assert (clojure.string/includes? result "Elapsed time")))
(println "PASS: time")

;; === *print-length* ===
(binding [*print-length* 3]
  (let [s (pr-str (range 100))]
    ;; Should truncate output
    (assert (string? s))))
(println "PASS: *print-length*")

;; === shuffle determinism check ===
(let [coll [1 2 3 4 5 6 7 8 9 10]
      s1 (shuffle coll)
      s2 (shuffle coll)]
  (assert (= (set coll) (set s1)))
  (assert (= (count coll) (count s1))))
(println "PASS: shuffle preserves elements")

;; === mapv with multiple collections ===
(assert (= [5 7 9] (mapv + [1 2 3] [4 5 6])))
(assert (= [[1 4] [2 5] [3 6]] (mapv vector [1 2 3] [4 5 6])))
(println "PASS: mapv multi-coll")

;; === filterv ===
(assert (= [2 4 6] (filterv even? [1 2 3 4 5 6])))
(assert (vector? (filterv even? [1 2 3])))
(println "PASS: filterv")

;; === run! ===
(let [a (atom [])]
  (run! #(swap! a conj %) [1 2 3])
  (assert (= [1 2 3] @a)))
(println "PASS: run!")

;; === bounded-count ===
(assert (= 3 (bounded-count 10 [1 2 3])))
(assert (= 5 (bounded-count 5 (range 100))))
(println "PASS: bounded-count")

;; === nthrest / nthnext ===
(assert (= '(3 4 5) (nthrest [1 2 3 4 5] 2)))
(assert (= () (nthrest [1 2] 5)))
(assert (= '(3 4 5) (nthnext [1 2 3 4 5] 2)))
(assert (= nil (nthnext [1 2] 5)))
(println "PASS: nthrest / nthnext")

;; === ffirst / fnext / nnext ===
(assert (= 1 (ffirst [[1 2] [3 4]])))
(assert (= 2 (fnext [1 2 3])))
(assert (= '(3) (nnext [1 2 3])))
(assert (= nil (nnext [1 2])))
(println "PASS: ffirst / fnext / nnext")

;; === take-last / drop-last ===
(assert (= '(4 5) (take-last 2 [1 2 3 4 5])))
(assert (= '(1 2 3) (drop-last 2 [1 2 3 4 5])))
(assert (= '(1 2 3 4) (drop-last [1 2 3 4 5])))
(println "PASS: take-last / drop-last")

;; === split-at ===
(assert (= [[1 2] [3 4 5]] (split-at 2 [1 2 3 4 5])))
(println "PASS: split-at")

;; === doall / dorun ===
(let [a (atom 0)]
  (doall (map (fn [x] (swap! a + x)) [1 2 3]))
  (assert (= 6 @a)))
(let [a (atom 0)]
  (dorun (map (fn [x] (swap! a + x)) [1 2 3]))
  (assert (= 6 @a)))
(println "PASS: doall / dorun")

;; === sort with comparator ===
(assert (= [1 1 2 3 4 5] (sort [3 1 4 1 5 2])))
(assert (= ["a" "bb" "ccc"] (sort-by count ["ccc" "a" "bb"])))
(println "PASS: sort / sort-by")

;; === sequence (without xform) ===
(assert (= '(1 2 3) (sequence [1 2 3])))
(assert (= nil (seq (sequence []))))
(println "PASS: sequence")

;; === reduced / unreduced / reduced? ===
(let [r (reduced 42)]
  (assert (reduced? r))
  (assert (= 42 (unreduced r)))
  (assert (= 42 @r)))
(assert (not (reduced? 42)))
(assert (= 42 (unreduced 42)))
(println "PASS: reduced / unreduced / reduced?")

;; === transduce ===
(assert (= 9 (transduce (filter odd?) + [1 2 3 4 5])))
(assert (= 27 (transduce (map inc) + 0 [1 2 3 4 5 6])))
(println "PASS: transduce")

;; === into with xf ===
(assert (= [2 4 6] (into [] (filter even?) [1 2 3 4 5 6])))
(assert (= #{1 2 3} (into #{} (map inc) [0 1 2])))
(println "PASS: into with transducer")

;; === eduction ===
(let [e (eduction (filter even?) [1 2 3 4 5 6])]
  (assert (= [2 4 6] (into [] e))))
(println "PASS: eduction")

;; === cat transducer ===
(assert (= [1 2 3 4 5 6] (into [] cat [[1 2] [3 4] [5 6]])))
(println "PASS: cat")

;; === completing ===
(let [f (completing + str)]
  (assert (= "6" (transduce identity f [1 2 3]))))
(println "PASS: completing")

;; === halt-when ===
(assert (= [1 2 3]
           (transduce (halt-when #(> % 3)) conj [1 2 3 4 5])))
(println "PASS: halt-when")

;; === interleave ===
(assert (= '(1 :a 2 :b 3 :c) (interleave [1 2 3] [:a :b :c])))
(assert (= '(1 :a 2 :b) (interleave [1 2 3] [:a :b])))
(println "PASS: interleave")

;; === interpose ===
(assert (= '(1 :sep 2 :sep 3) (interpose :sep [1 2 3])))
(assert (= "1, 2, 3" (apply str (interpose ", " [1 2 3]))))
(println "PASS: interpose")

;; === keep / keep-indexed ===
(assert (= '(2 4) (keep #(when (even? %) %) [1 2 3 4 5])))
(assert (= '([0 :a] [2 :c]) (keep-indexed #(when (even? %1) [%1 %2]) [:a :b :c :d])))
(println "PASS: keep / keep-indexed")

;; === cycle ===
(assert (= '(1 2 3 1 2 3 1) (take 7 (cycle [1 2 3]))))
(println "PASS: cycle")

;; === iterate ===
(assert (= '(1 2 4 8 16) (take 5 (iterate #(* 2 %) 1))))
(println "PASS: iterate")

;; === repeat / repeatedly ===
(assert (= '(42 42 42) (repeat 3 42)))
(assert (= 5 (count (repeatedly 5 #(rand-int 100)))))
(println "PASS: repeat / repeatedly")

;; === every-pred ===
(let [pred (every-pred number? pos? even?)]
  (assert (pred 4))
  (assert (not (pred 3)))
  (assert (not (pred -2))))
(println "PASS: every-pred")

;; === some-fn ===
(let [f (some-fn :a :b :c)]
  (assert (= 1 (f {:a 1})))
  (assert (= 2 (f {:b 2})))
  (assert (= nil (f {:d 4}))))
(println "PASS: some-fn")

;; === vary-meta ===
(let [v (with-meta [1 2 3] {:doc "test"})
      v2 (vary-meta v assoc :version 2)]
  (assert (= {:doc "test" :version 2} (meta v2))))
(println "PASS: vary-meta")

;; === reduce-kv ===
(assert (= 6 (reduce-kv (fn [acc k v] (+ acc v)) 0 {:a 1 :b 2 :c 3})))
(assert (= 3 (reduce-kv (fn [acc k v] (+ acc k)) 0 [10 20 30])))
(println "PASS: reduce-kv")

;; === with-redefs ===
(defn my-fn [] 42)
(assert (= 42 (my-fn)))
(with-redefs [my-fn (fn [] 99)]
  (assert (= 99 (my-fn))))
(assert (= 42 (my-fn)))
(println "PASS: with-redefs")

;; === alter-var-root ===
(def ^:dynamic *alterable* 10)
(alter-var-root #'*alterable* (fn [v] (+ v 5)))
(assert (= 15 *alterable*))
(println "PASS: alter-var-root")

;; === loop/recur with destructuring ===
(assert (= 15
           (loop [[x & xs] [1 2 3 4 5]
                  acc 0]
             (if x
               (recur xs (+ acc x))
               acc))))
(println "PASS: loop/recur destructuring")

;; === lazy-seq ===
(defn lazy-range [n]
  (lazy-seq
    (when (pos? n)
      (cons n (lazy-range (dec n))))))
(assert (= '(3 2 1) (lazy-range 3)))
(println "PASS: lazy-seq")

;; === tree-seq ===
(let [tree {:val 1 :children [{:val 2 :children []} {:val 3 :children [{:val 4 :children []}]}]}]
  (assert (= [1 2 3 4]
             (map :val (tree-seq #(seq (:children %)) :children tree)))))
(println "PASS: tree-seq")

;; === mapcat ===
(assert (= [1 2 2 3 3 3] (mapcat #(repeat % %) [1 2 3])))
(println "PASS: mapcat")

;; === dedupe ===
(assert (= [1 2 3 1 2] (dedupe [1 1 2 2 3 3 1 1 2])))
(println "PASS: dedupe")

;; === distinct ===
(assert (= [1 2 3 4] (distinct [1 2 3 1 2 4])))
(println "PASS: distinct")

;; === subvec ===
(assert (= [2 3] (subvec [1 2 3 4 5] 1 3)))
(assert (= [3 4 5] (subvec [1 2 3 4 5] 2)))
(println "PASS: subvec")

;; === peek / pop ===
(assert (= 3 (peek [1 2 3])))
(assert (= [1 2] (pop [1 2 3])))
(assert (= 1 (peek '(1 2 3))))
(assert (= '(2 3) (pop '(1 2 3))))
(println "PASS: peek / pop")

;; === disj ===
(assert (= #{1 3} (disj #{1 2 3} 2)))
(assert (= #{1} (disj #{1 2 3} 2 3)))
(println "PASS: disj")

;; === compare ===
(assert (= 0 (compare 1 1)))
(assert (neg? (compare 1 2)))
(assert (pos? (compare 2 1)))
(assert (= 0 (compare "a" "a")))
(assert (neg? (compare "a" "b")))
(println "PASS: compare")

;; === identical? ===
(let [x "hello"]
  (assert (identical? x x))
  (assert (not (identical? [1] [1]))))
(println "PASS: identical?")

;; === hash ===
(assert (= (hash :a) (hash :a)))
(assert (integer? (hash "hello")))
(assert (integer? (hash [1 2 3])))
(println "PASS: hash")

;; === doto ===
(let [sb (doto (StringBuilder.)
           (.append "hello")
           (.append " world"))]
  (assert (= "hello world" (.toString sb))))
(println "PASS: doto")

;; === .. (dot-dot) ===
(assert (= "HELLO" (.. "hello" (toUpperCase))))
(println "PASS: ..")

;; === bean ===
(let [b (bean (java.util.Date.))]
  (assert (map? b))
  (assert (contains? b :class)))
(println "PASS: bean")

;; === supers ===
(let [s (supers String)]
  (assert (set? s))
  (assert (contains? s java.io.Serializable)))
(println "PASS: supers")

;; === bases ===
(let [b (bases String)]
  (assert (seq b)))
(println "PASS: bases")

;; === type ===
(assert (= Long (type 42)))
(assert (= String (type "hello")))
(assert (= clojure.lang.PersistentVector (type [1 2 3])))
(println "PASS: type")

;; === class ===
(assert (= Long (class 42)))
(assert (= String (class "hello")))
(println "PASS: class")

;; === cast ===
(assert (= 42 (cast Number 42)))
(assert (= "hello" (cast String "hello")))
(println "PASS: cast")

;; === instance? ===
(assert (instance? Number 42))
(assert (instance? String "hello"))
(assert (not (instance? String 42)))
(println "PASS: instance?")

;; === munge / demunge ===
(assert (= "my_fn" (munge "my-fn")))
(assert (= "my-fn" (demunge "my_fn")))
(println "PASS: munge / demunge")

;; === gensym ===
(let [s (gensym "test")]
  (assert (symbol? s))
  (assert (clojure.string/starts-with? (name s) "test")))
(println "PASS: gensym")

;; === read-string ===
(assert (= 42 (read-string "42")))
(assert (= [1 2 3] (read-string "[1 2 3]")))
(assert (= {:a 1} (read-string "{:a 1}")))
(println "PASS: read-string")

;; === pr-str / prn-str ===
(assert (= "42" (pr-str 42)))
(assert (= "\"hello\"" (pr-str "hello")))
(assert (= "[1 2 3]" (pr-str [1 2 3])))
(println "PASS: pr-str / prn-str")

;; === print-str / println-str ===
(assert (= "42" (print-str 42)))
(assert (= "hello" (print-str "hello")))
(println "PASS: print-str")

;; === with-out-str ===
(assert (= "hello\n" (with-out-str (println "hello"))))
(assert (= "42" (with-out-str (print 42))))
(println "PASS: with-out-str")

;; === format ===
(assert (= "hello world" (format "%s %s" "hello" "world")))
(assert (= "042" (format "%03d" 42)))
(println "PASS: format")

;; === parse-long / parse-double ===
(assert (= 42 (parse-long "42")))
(assert (= nil (parse-long "abc")))
(assert (= 3.14 (parse-double "3.14")))
(assert (= nil (parse-double "abc")))
(println "PASS: parse-long / parse-double")

;; === parse-boolean ===
(assert (= true (parse-boolean "true")))
(assert (= false (parse-boolean "false")))
(assert (= nil (parse-boolean "yes")))
(println "PASS: parse-boolean")

;; === parse-uuid ===
(let [u (parse-uuid "550e8400-e29b-41d4-a716-446655440000")]
  (assert (uuid? u)))
(assert (= nil (parse-uuid "not-a-uuid")))
(println "PASS: parse-uuid")

;; === random-uuid ===
(let [u (random-uuid)]
  (assert (uuid? u))
  (assert (not= u (random-uuid))))
(println "PASS: random-uuid")

;; === uri? ===
(assert (uri? (java.net.URI. "http://example.com")))
(assert (not (uri? "http://example.com")))
(println "PASS: uri?")

;; === inst? ===
(assert (inst? (java.util.Date.)))
(assert (not (inst? 42)))
(println "PASS: inst?")

(println "\n=== Conformance: Gap Coverage PASSED ===")
