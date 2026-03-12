; Conformance Tests: clojure.walk, clojure.set, I/O, Macros

(println "=== Conformance: Walk, Set, I/O, Macros ===")

;; === clojure.set/union ===
(require 'clojure.set)
(assert (= #{1 2 3 4} (clojure.set/union #{1 2} #{3 4})))
(assert (= #{1 2 3} (clojure.set/union #{1 2} #{2 3})))
(println "PASS: set/union")

;; === clojure.set/intersection ===
(assert (= #{2 3} (clojure.set/intersection #{1 2 3} #{2 3 4})))
(assert (= #{} (clojure.set/intersection #{1 2} #{3 4})))
(println "PASS: set/intersection")

;; === clojure.set/difference ===
(assert (= #{1} (clojure.set/difference #{1 2 3} #{2 3 4})))
(assert (= #{1 2 3} (clojure.set/difference #{1 2 3} #{})))
(println "PASS: set/difference")

;; === clojure.set/subset? / superset? ===
(assert (clojure.set/subset? #{1 2} #{1 2 3}))
(assert (not (clojure.set/subset? #{1 4} #{1 2 3})))
(assert (clojure.set/superset? #{1 2 3} #{1 2}))
(println "PASS: set/subset? superset?")

;; === clojure.set/rename-keys ===
(assert (= {:x 1 :y 2} (clojure.set/rename-keys {:a 1 :b 2} {:a :x :b :y})))
(println "PASS: set/rename-keys")

;; === clojure.set/map-invert ===
(assert (= {1 :a 2 :b} (clojure.set/map-invert {:a 1 :b 2})))
(println "PASS: set/map-invert")

;; === clojure.set/index ===
(let [data #{{:name "a" :age 1} {:name "b" :age 2} {:name "c" :age 1}}
      idx (clojure.set/index data [:age])]
  (assert (= 2 (count (get idx {:age 1}))))
  (assert (= 1 (count (get idx {:age 2})))))
(println "PASS: set/index")

;; === clojure.walk/walk ===
(require 'clojure.walk)
(assert (= [2 3 4] (clojure.walk/walk inc identity [1 2 3])))
(println "PASS: walk/walk")

;; === clojure.walk/postwalk ===
(assert (= {:a 2 :b {:c 4}}
           (clojure.walk/postwalk #(if (number? %) (* 2 %) %) {:a 1 :b {:c 2}})))
(println "PASS: walk/postwalk")

;; === clojure.walk/prewalk ===
(assert (= [2 [4 [6]]]
           (clojure.walk/prewalk #(if (number? %) (* 2 %) %) [1 [2 [3]]])))
(println "PASS: walk/prewalk")

;; === clojure.walk/postwalk-replace ===
(assert (= [1 :two 3] (clojure.walk/postwalk-replace {2 :two} [1 2 3])))
(println "PASS: walk/postwalk-replace")

;; === clojure.walk/prewalk-replace ===
(assert (= [:one 2 :three] (clojure.walk/prewalk-replace {1 :one 3 :three} [1 2 3])))
(println "PASS: walk/prewalk-replace")

;; === clojure.walk/stringify-keys / keywordize-keys ===
(assert (= {"a" 1 "b" 2} (clojure.walk/stringify-keys {:a 1 :b 2})))
(assert (= {:a 1 :b 2} (clojure.walk/keywordize-keys {"a" 1 "b" 2})))
(println "PASS: walk/stringify-keys keywordize-keys")

;; === slurp / spit ===
(let [tmpfile "/tmp/tclj_test_io.txt"]
  (spit tmpfile "hello from tclj")
  (assert (= "hello from tclj" (slurp tmpfile)))
  ;; spit with :append
  (spit tmpfile "\nline2" :append true)
  (assert (= "hello from tclj\nline2" (slurp tmpfile))))
(println "PASS: slurp/spit")

;; === pr-str / prn-str ===
(assert (= "42" (pr-str 42)))
(assert (= "\"hello\"" (pr-str "hello")))
(assert (= ":a" (pr-str :a)))
(assert (= "[1 2 3]" (pr-str [1 2 3])))
(println "PASS: pr-str")

;; === with-out-str ===
(assert (= "hello\n" (with-out-str (println "hello"))))
(assert (= "42" (with-out-str (print 42))))
(println "PASS: with-out-str")

;; === defmacro ===
(defmacro unless [test & body]
  `(if (not ~test) (do ~@body)))
(assert (= "yes" (unless false "yes")))
(assert (nil? (unless true "yes")))
(println "PASS: defmacro")

;; === syntax-quote unquote ===
(let [x 42
      result `(identity ~x)]
  (assert (= 42 (second result))))
(println "PASS: syntax-quote unquote")

;; === unquote-splicing ===
(let [xs [1 2 3]
      result `(+ ~@xs)]
  (assert (= '(1 2 3) (rest result))))
(println "PASS: unquote-splicing")

;; === gensym ===
(let [s1 (gensym)
      s2 (gensym)
      s3 (gensym "prefix")]
  (assert (symbol? s1))
  (assert (not= s1 s2))
  (assert (clojure.string/starts-with? (name s3) "prefix")))
(println "PASS: gensym")

;; === macroexpand ===
(let [expanded (macroexpand '(unless true :body))]
  (assert (seq? expanded)))
(println "PASS: macroexpand")

;; === -> with Java interop ===
(assert (= "HELLO" (-> "hello" .toUpperCase)))
(assert (= 5 (-> "hello" .length)))
(println "PASS: -> with Java interop")

;; === ->> practical ===
(assert (= 20 (->> (range 10) (filter even?) (reduce +))))
(println "PASS: ->> practical")

;; === comp with multiple functions ===
(def process (comp str inc (partial * 2)))
(assert (= "7" (process 3)))   ;; 3*2=6, 6+1=7, "7"
(println "PASS: comp chain")

;; === juxt practical ===
(def stats (juxt count #(apply min %) #(apply max %)))
(assert (= [5 1 5] (stats [3 1 4 1 5])))
(println "PASS: juxt practical")

;; === iterate + take practical ===
(defn fib-seq []
  (map first (iterate (fn [[a b]] [b (+ a b)]) [0 1])))
(assert (= '(0 1 1 2 3 5 8 13 21 34) (take 10 (fib-seq))))
(println "PASS: fibonacci via iterate")

;; === tree-seq ===
(let [tree {:v 1 :children [{:v 2 :children [{:v 4}]}
                             {:v 3 :children [{:v 5}]}]}]
  (assert (= [1 2 4 3 5]
             (map :v (tree-seq :children :children tree)))))
(println "PASS: tree-seq")

(println "\n=== Conformance: Walk, Set, I/O, Macros PASSED ===")
