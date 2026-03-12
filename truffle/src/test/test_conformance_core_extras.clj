; Conformance Tests: Core Extras (previously untested builtins)

(println "=== Conformance: Core Extras ===")

;; === concat ===
(assert (= '(1 2 3 4 5 6) (concat [1 2] [3 4] [5 6])))
(assert (= '(1 2 3) (concat [1] [2] [3])))
(assert (= () (concat)))
(assert (= '(1 2 3) (concat [1 2 3])))
(assert (= '(1 2 3) (concat [1 2 3] nil)))
(assert (= '(1 2 3) (concat nil [1 2 3])))
(println "PASS: concat")

;; === dissoc ===
(assert (= {:b 2} (dissoc {:a 1 :b 2} :a)))
(assert (= {} (dissoc {:a 1 :b 2} :a :b)))
(assert (= {:a 1 :b 2} (dissoc {:a 1 :b 2} :c)))
(assert (= {} (dissoc {})))
(println "PASS: dissoc")

;; === empty? ===
(assert (empty? []))
(assert (empty? '()))
(assert (empty? {}))
(assert (empty? #{}))
(assert (empty? ""))
(assert (empty? nil))
(assert (not (empty? [1])))
(assert (not (empty? "x")))
(println "PASS: empty?")

;; === flatten ===
(assert (= [1 2 3 4 5] (flatten [1 [2 [3 [4 [5]]]]])))
(assert (= [1 2 3 4] (flatten [[1 2] [3 4]])))
(assert (= [] (flatten [])))
(assert (= [] (flatten [[[]]])))
(assert (= [1 2 3] (flatten [1 2 3])))
(println "PASS: flatten")

;; === get-in ===
(assert (= 3 (get-in {:a {:b {:c 3}}} [:a :b :c])))
(assert (= {:c 3} (get-in {:a {:b {:c 3}}} [:a :b])))
(assert (nil? (get-in {:a 1} [:b :c])))
(assert (= :default (get-in {:a 1} [:b :c] :default)))
(assert (= 1 (get-in {:a 1} [:a])))
(println "PASS: get-in")

;; === assoc-in ===
(assert (= {:a {:b {:c 42}}} (assoc-in {:a {:b {:c 0}}} [:a :b :c] 42)))
(assert (= {:a {:b 42}} (assoc-in {} [:a :b] 42)))
(assert (= {:a 42} (assoc-in {} [:a] 42)))
(println "PASS: assoc-in")

;; === update-in ===
(assert (= {:a {:b 2}} (update-in {:a {:b 1}} [:a :b] inc)))
(assert (= {:a {:b 10}} (update-in {:a {:b 5}} [:a :b] + 5)))
(assert (= {:a {:b 1}} (update-in {} [:a :b] (fnil inc 0))))
(println "PASS: update-in")

;; === group-by ===
(assert (= {true [2 4 6] false [1 3 5]} (group-by even? [1 2 3 4 5 6])))
(assert (= {1 ["a"] 2 ["bb"] 3 ["ccc"]} (group-by count ["a" "bb" "ccc"])))
(println "PASS: group-by")

;; === frequencies ===
(assert (= {"a" 3 "b" 2 "c" 1} (frequencies ["a" "b" "a" "b" "a" "c"])))
(assert (= {1 3 2 2 3 1} (frequencies [1 2 1 2 1 3])))
(assert (= {} (frequencies [])))
(println "PASS: frequencies")

;; === zipmap ===
(assert (= {:a 1 :b 2 :c 3} (zipmap [:a :b :c] [1 2 3])))
(assert (= {:a 1 :b 2} (zipmap [:a :b :c] [1 2]))) ;; shorter vals
(assert (= {} (zipmap [] [1 2 3])))
(println "PASS: zipmap")

;; === map-indexed ===
(assert (= '([0 :a] [1 :b] [2 :c]) (map-indexed vector [:a :b :c])))
(assert (= '(0 1 2) (map-indexed (fn [i _] i) [:a :b :c])))
(assert (= () (map-indexed vector [])))
(println "PASS: map-indexed")

;; === partition-by ===
(assert (= '((1 1) (2 2) (3 3)) (partition-by identity [1 1 2 2 3 3])))
(assert (= '(("a") ("bb" "cc") ("d")) (partition-by count ["a" "bb" "cc" "d"])))
(println "PASS: partition-by")

;; === butlast ===
(assert (= [1 2 3] (butlast [1 2 3 4])))
(assert (= '(1) (butlast [1 2])))
(assert (nil? (butlast [1])))
(assert (nil? (butlast [])))
(assert (nil? (butlast nil)))
(println "PASS: butlast")

;; === last ===
(assert (= 4 (last [1 2 3 4])))
(assert (= 1 (last [1])))
(assert (nil? (last [])))
(assert (nil? (last nil)))
(println "PASS: last")

;; === rseq ===
(assert (= '(3 2 1) (rseq [1 2 3])))
(assert (nil? (rseq [])))
(println "PASS: rseq")

;; === max-key / min-key ===
(assert (= "ccc" (max-key count "a" "bb" "ccc")))
(assert (= "a" (min-key count "a" "bb" "ccc")))
(assert (= {:a 3} (max-key :a {:a 1} {:a 3} {:a 2})))
(assert (= {:a 1} (min-key :a {:a 1} {:a 3} {:a 2})))
(println "PASS: max-key / min-key")

;; === fnil ===
(let [safe-inc (fnil inc 0)]
  (assert (= 1 (safe-inc nil)))
  (assert (= 6 (safe-inc 5))))
(let [safe-add (fnil + 0 0)]
  (assert (= 5 (safe-add nil 5)))
  (assert (= 5 (safe-add 5 nil)))
  (assert (= 3 (safe-add 1 2))))
(println "PASS: fnil")

;; === force ===
(let [d (delay 42)]
  (assert (= 42 (force d)))
  (assert (= 42 (force 42)))) ;; force on non-delay returns as-is
(println "PASS: force")

;; === ex-cause ===
(let [cause (Exception. "root cause")
      e (ex-info "wrapped" {:data 1} cause)]
  (assert (= cause (ex-cause e)))
  (assert (= "root cause" (.getMessage (ex-cause e)))))
(println "PASS: ex-cause")

;; === boolean? ===
(assert (boolean? true))
(assert (boolean? false))
(assert (not (boolean? 1)))
(assert (not (boolean? nil)))
(assert (not (boolean? "true")))
(println "PASS: boolean?")

;; === char? ===
(assert (char? \a))
(assert (char? \space))
(assert (not (char? "a")))
(assert (not (char? 65)))
(println "PASS: char?")

;; === class? ===
(assert (class? String))
(assert (class? Long))
(assert (not (class? "String")))
(assert (not (class? 42)))
(println "PASS: class?")

;; === pmap ===
(let [results (pmap inc [1 2 3 4 5])]
  (assert (= [2 3 4 5 6] (vec results))))
(println "PASS: pmap")

;; === some-> / some->> with nil short-circuit ===
(assert (nil? (some-> nil inc inc)))
(assert (= 3 (some-> 1 inc inc)))
(assert (nil? (some->> nil (+ 1))))
(println "PASS: some-> some->> nil")

;; === not-empty ===
(assert (= [1 2] (not-empty [1 2])))
(assert (nil? (not-empty [])))
(assert (nil? (not-empty "")))
(assert (= "hi" (not-empty "hi")))
(println "PASS: not-empty")

;; === contains? on various types ===
(assert (contains? {:a 1} :a))
(assert (not (contains? {:a 1} :b)))
(assert (contains? [10 20 30] 0))   ;; index-based
(assert (contains? [10 20 30] 2))
(assert (not (contains? [10 20 30] 3)))
(assert (contains? #{:a :b} :a))
(assert (not (contains? #{:a :b} :c)))
(println "PASS: contains?")

;; === juxt ===
(assert (= [1 5] ((juxt first last) [1 2 3 4 5])))
(assert (= [3 :pos] ((juxt count (fn [v] (if (pos? (first v)) :pos :neg))) [1 2 3])))
(println "PASS: juxt")

;; === trampoline ===
(defn my-even? [n]
  (if (zero? n) true #(my-odd? (dec n))))
(defn my-odd? [n]
  (if (zero? n) false #(my-even? (dec n))))
(assert (= true (trampoline my-even? 10)))
(assert (= false (trampoline my-even? 11)))
(println "PASS: trampoline")

;; === list* ===
(assert (= '(1 2 3 4) (list* 1 2 [3 4])))
(assert (= '(1 2 3) (list* 1 [2 3])))
(assert (= '(1 2 3) (list* [1 2 3])))
(println "PASS: list*")

;; === mapv with multiple colls ===
(assert (= [5 7 9] (mapv + [1 2 3] [4 5 6])))
(assert (= [1 4 9] (mapv * [1 2 3] [1 2 3])))
(println "PASS: mapv multi-coll")

;; === reductions ===
(assert (= [1 3 6 10] (reductions + [1 2 3 4])))
(assert (= [0 1 3 6 10] (reductions + 0 [1 2 3 4])))
(println "PASS: reductions")

;; === second ===
(assert (= 2 (second [1 2 3])))
(assert (= :b (second [:a :b :c])))
(assert (nil? (second [1])))
(assert (nil? (second nil)))
(println "PASS: second")

;; === nth with default ===
(assert (= :a (nth [:a :b :c] 0)))
(assert (= :c (nth [:a :b :c] 2)))
(assert (= :default (nth [:a :b] 5 :default)))
(println "PASS: nth")

;; === when-first ===
(assert (= 1 (when-first [x [1 2 3]] x)))
(assert (nil? (when-first [x []] :body)))
(assert (nil? (when-first [x nil] :body)))
(println "PASS: when-first")

;; === with-open ===
(let [f (java.io.File/createTempFile "clj-withopen" ".txt")]
  (.deleteOnExit f)
  (spit f "hello\nworld")
  (let [content (with-open [rdr (java.io.BufferedReader. (java.io.FileReader. f))]
                  (vec (line-seq rdr)))]
    (assert (= ["hello" "world"] content))))
(println "PASS: with-open")

;; === comment ===
(assert (nil? (comment 1 2 3 (throw (Exception. "never")))))
(println "PASS: comment")

;; === memfn ===
(let [len (memfn length)]
  (assert (= 5 (len "hello")))
  (assert (= [5 3 4] (map (memfn length) ["hello" "foo" "test"]))))
(println "PASS: memfn")

;; === number coercion edge cases ===
(assert (= 2 (quot 7 3)))
(assert (= 1 (rem 7 3)))
(assert (= 1 (mod 7 3)))
(assert (= -3 (quot -7 2)))
(assert (= -1 (rem -7 2)))
(println "PASS: number coercion edge cases")

;; === Math interop ===
(assert (= 4.0 (Math/sqrt 16)))
(assert (= 8.0 (Math/pow 2 3)))
(assert (= 1 (Math/min 1 2)))
(assert (= 2 (Math/max 1 2)))
(assert (= 3 (Math/round 3.4)))
(println "PASS: Math interop")

;; === seq on string ===
(assert (= '(\h \e \l \l \o) (seq "hello")))
(assert (= \h (first "hello")))
(assert (= '(\e \l \l \o) (rest "hello")))
(println "PASS: seq on string")

;; === into-array type inference ===
(let [a (into-array [1 2 3])]
  (assert (= 3 (alength a)))
  (assert (= 1 (aget a 0))))
(println "PASS: into-array")

;; === amap / areduce ===
;; areduce: (areduce a idx ret init expr)
;; amap: (amap a idx ret expr)
;; These may or may not be implemented as special forms

;; === while with side effects ===
(let [a (atom 0)]
  (while (< @a 5) (swap! a inc))
  (assert (= 5 @a)))
(println "PASS: while")

;; === case with multiple values per branch ===
(defn classify [x]
  (case x
    (1 2 3) :small
    (4 5 6) :medium
    :large))
(assert (= :small (classify 1)))
(assert (= :small (classify 3)))
(assert (= :medium (classify 4)))
(assert (= :large (classify 7)))
(println "PASS: case multi-value")

;; === condp with custom pred ===
(assert (= :found (condp = 2
                    1 :not
                    2 :found
                    3 :not)))
(println "PASS: condp =")

;; === map on multiple collections ===
(assert (= '(5 7 9) (map + [1 2 3] [4 5 6])))
(assert (= '(1 4) (map * [1 2 3] [1 2]))) ;; stops at shortest
(println "PASS: map multi-coll")

;; === reduce with no init ===
(assert (= 15 (reduce + [1 2 3 4 5])))
(assert (= "abc" (reduce str ["a" "b" "c"])))
(println "PASS: reduce no-init")

;; === for :while (approximate — filters rather than stops) ===
(assert (= [0 1 2] (for [x (range 5) :while (< x 3)] x)))
(println "PASS: for :while")

;; === doseq with :when ===
(let [a (atom [])]
  (doseq [x [1 2 3 4 5] :when (odd? x)]
    (swap! a conj x))
  (assert (= [1 3 5] @a)))
(println "PASS: doseq :when")

;; === repeatedly ===
(let [a (atom 0)
      results (vec (repeatedly 5 #(swap! a inc)))]
  (assert (= [1 2 3 4 5] results)))
(println "PASS: repeatedly")

;; === range ===
(assert (= '(0 1 2 3 4) (range 5)))
(assert (= '(2 3 4) (range 2 5)))
(assert (= '(0 2 4 6 8) (range 0 10 2)))
(assert (= () (range 5 0)))
(println "PASS: range")

;; === distinct ===
(assert (= [1 2 3] (distinct [1 2 1 3 2 3])))
(assert (= [] (distinct [])))
(println "PASS: distinct")

;; === some with set as pred ===
(assert (= 3 (some #{3 4} [1 2 3 4 5])))
(assert (nil? (some #{6} [1 2 3])))
(println "PASS: some with set")

;; === every? / not-any? / not-every? ===
(assert (every? even? [2 4 6]))
(assert (not (every? even? [2 3 6])))
(assert (not-any? even? [1 3 5]))
(assert (not (not-any? even? [1 2 3])))
(assert (not-every? even? [1 2 3]))
(assert (not (not-every? even? [2 4 6])))
(println "PASS: every?/not-any?/not-every?")

;; === keyword / symbol as fn ===
(assert (= 1 (:a {:a 1 :b 2})))
(assert (= 2 (:b {:a 1 :b 2})))
(assert (nil? (:c {:a 1 :b 2})))
(assert (= :default (:c {:a 1} :default)))
(println "PASS: keyword as fn")

;; === set as fn ===
(assert (= 3 (#{1 2 3} 3)))
(assert (nil? (#{1 2 3} 4)))
(println "PASS: set as fn")

;; === vector as fn ===
(assert (= :b ([0 :a :b :c] 2)))
(println "PASS: vector as fn")

;; === map as fn ===
(assert (= 1 ({:a 1 :b 2} :a)))
(assert (nil? ({:a 1} :b)))
(assert (= :default ({:a 1} :b :default)))
(println "PASS: map as fn")

;; === str/replace with regex and fn ===
(require '[clojure.string :as str])
(assert (= "1-2-3" (str/replace "1.2.3" "." "-")))
(println "PASS: str/replace string arg")

;; === str/split with limit ===
(assert (= ["a" "b" "c"] (str/split "a.b.c" #"\.")))
(assert (= ["a" "b.c"] (str/split "a.b.c" #"\." 2)))
(println "PASS: str/split with limit")

;; === cons ===
(assert (= '(0 1 2 3) (cons 0 [1 2 3])))
(assert (= '(0) (cons 0 nil)))
(assert (= '(0) (cons 0 [])))
(println "PASS: cons")

;; === conj on various types ===
(assert (= [1 2 3] (conj [1 2] 3)))
(assert (= '(0 1 2) (conj '(1 2) 0)))
(assert (= #{1 2 3} (conj #{1 2} 3)))
(assert (= {:a 1 :b 2} (conj {:a 1} [:b 2])))
(println "PASS: conj")

;; === into on various types ===
(assert (= [1 2 3 4] (into [1 2] [3 4])))
(assert (= {:a 1 :b 2} (into {} [[:a 1] [:b 2]])))
(assert (= #{1 2 3} (into #{1} [2 3])))
(assert (= '(3 2 1) (into '() [1 2 3])))
(println "PASS: into")

;; === assoc on vector ===
(assert (= [:x :b :c] (assoc [:a :b :c] 0 :x)))
(assert (= [:a :b :x] (assoc [:a :b :c] 2 :x)))
(println "PASS: assoc on vector")

;; === get on nil ===
(assert (nil? (get nil :a)))
(assert (= :default (get nil :a :default)))
(println "PASS: get on nil")

(println "\n=== Conformance: Core Extras PASSED ===")
