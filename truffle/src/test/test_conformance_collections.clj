; Conformance Tests: Collections, Sequences, Lazy Sequences

(println "=== Conformance: Collections & Sequences ===")

;; === subvec ===
(assert (= [2 3 4] (subvec [1 2 3 4 5] 1 4)))
(assert (= [3 4 5] (subvec [1 2 3 4 5] 2)))
(println "PASS: subvec")

;; === peek / pop vector ===
(assert (= 3 (peek [1 2 3])))
(assert (= [1 2] (pop [1 2 3])))
(println "PASS: peek/pop vector")

;; === peek / pop list ===
(assert (= 1 (peek '(1 2 3))))
(assert (= '(2 3) (pop '(1 2 3))))
(println "PASS: peek/pop list")

;; === disj ===
(assert (= #{1 3} (disj #{1 2 3} 2)))
(assert (= #{1} (disj #{1 2 3} 2 3)))
(println "PASS: disj")

;; === interleave ===
(assert (= '(1 :a 2 :b 3 :c) (interleave [1 2 3] [:a :b :c])))
(assert (= '(1 :a 2 :b) (interleave [1 2 3] [:a :b])))
(println "PASS: interleave")

;; === interpose ===
(assert (= '(1 :sep 2 :sep 3) (interpose :sep [1 2 3])))
(assert (= "a-b-c" (apply str (interpose "-" ["a" "b" "c"]))))
(println "PASS: interpose")

;; === distinct ===
(assert (= '(1 2 3 4) (distinct [1 2 1 3 2 4 3])))
(println "PASS: distinct")

;; === dedupe ===
(assert (= '(1 2 3 2 1) (dedupe [1 1 2 2 3 3 2 2 1])))
(println "PASS: dedupe")

;; === transient / persistent! ===
(let [t (transient [])
      t (conj! t 1)
      t (conj! t 2)
      t (conj! t 3)
      v (persistent! t)]
  (assert (= [1 2 3] v)))
(println "PASS: transient vector")

(let [t (transient {})
      t (assoc! t :a 1)
      t (assoc! t :b 2)
      v (persistent! t)]
  (assert (= {:a 1 :b 2} v)))
(println "PASS: transient map")

;; === lazy-seq ===
(defn lazy-range [n]
  (lazy-seq
    (when (> n 0)
      (cons n (lazy-range (dec n))))))
(assert (= '(5 4 3 2 1) (lazy-range 5)))
(println "PASS: lazy-seq")

;; === iterate ===
(assert (= '(1 2 4 8 16) (take 5 (iterate #(* 2 %) 1))))
(assert (= '(0 1 2 3 4) (take 5 (iterate inc 0))))
(println "PASS: iterate")

;; === cycle ===
(assert (= '(1 2 3 1 2 3 1) (take 7 (cycle [1 2 3]))))
(println "PASS: cycle")

;; === repeat ===
(assert (= '(42 42 42) (repeat 3 42)))
(assert (= '(:a :a :a :a) (take 4 (repeat :a))))
(println "PASS: repeat")

;; === repeatedly ===
(let [counter (atom 0)
      results (take 3 (repeatedly #(swap! counter inc)))]
  (assert (= '(1 2 3) (doall results))))
(println "PASS: repeatedly")

;; === take-while ===
(assert (= '(1 2 3) (take-while #(< % 4) [1 2 3 4 5])))
(assert (= '() (take-while neg? [1 2 3])))
(println "PASS: take-while")

;; === drop-while ===
(assert (= '(4 5 6) (drop-while #(< % 4) [1 2 3 4 5 6])))
(println "PASS: drop-while")

;; === split-with ===
(assert (= ['(1 2 3) '(4 5)] (split-with #(< % 4) [1 2 3 4 5])))
(println "PASS: split-with")

;; === take-nth ===
(assert (= '(0 3 6 9) (take-nth 3 (range 10))))
(println "PASS: take-nth")

;; === mapcat ===
(assert (= '(1 1 2 2 3 3) (mapcat #(list % %) [1 2 3])))
(assert (= '(1 2 3 4 5 6) (mapcat identity [[1 2] [3 4] [5 6]])))
(println "PASS: mapcat")

;; === keep ===
(assert (= '(2 4 6) (keep #(when (even? %) %) [1 2 3 4 5 6])))
(println "PASS: keep")

;; === keep-indexed ===
(assert (= '(:a :c :e) (keep-indexed #(when (even? %1) %2) [:a :b :c :d :e])))
(println "PASS: keep-indexed")

;; === sorted-set ===
(assert (= '(1 2 3 4 5) (seq (sorted-set 3 1 4 1 5 2))))
(println "PASS: sorted-set")

;; === sorted-set-by ===
(assert (= '(5 4 3 2 1) (seq (sorted-set-by #(compare %2 %1) 3 1 4 1 5 2))))
(println "PASS: sorted-set-by")

;; === into ===
(assert (= [1 2 3 4 5] (into [1 2] [3 4 5])))
(assert (= {:a 1 :b 2} (into {} [[:a 1] [:b 2]])))
(assert (= #{1 2 3} (into #{} [1 2 3 2 1])))
(println "PASS: into")

;; === empty ===
(assert (= [] (empty [1 2 3])))
(assert (= {} (empty {:a 1})))
(assert (= #{} (empty #{1 2})))
(assert (= '() (empty '(1 2 3))))
(println "PASS: empty")

;; === not-empty ===
(assert (= [1 2] (not-empty [1 2])))
(assert (nil? (not-empty [])))
(assert (nil? (not-empty {})))
(println "PASS: not-empty")

;; === every? ===
(assert (every? even? [2 4 6]))
(assert (not (every? even? [2 3 6])))
(println "PASS: every?")

;; === some ===
(assert (= true (some even? [1 2 3])))
(assert (nil? (some even? [1 3 5])))
(assert (= :b (some #{:b} [:a :b :c])))
(println "PASS: some")

;; === not-every? ===
(assert (not-every? even? [2 3 4]))
(assert (not (not-every? even? [2 4 6])))
(println "PASS: not-every?")

;; === not-any? ===
(assert (not-any? even? [1 3 5]))
(assert (not (not-any? even? [1 2 3])))
(println "PASS: not-any?")

;; === merge-with ===
(assert (= {:a 3 :b 2} (merge-with + {:a 1 :b 2} {:a 2})))
(println "PASS: merge-with")

;; === update ===
(assert (= {:a 2} (update {:a 1} :a inc)))
(assert (= {:a 1 :b 10} (update {:a 1 :b 5} :b + 5)))
(println "PASS: update")

;; === vec ===
(assert (= [1 2 3] (vec '(1 2 3))))
(assert (= #{1 2 3} (set (vec #{1 2 3}))))  ;; set order is unspecified
(assert (= [\h \e \l \l \o] (vec "hello")))
(println "PASS: vec")

;; === set ===
(assert (= #{1 2 3} (set [1 2 3 2 1])))
(assert (= #{\h \e \l \o} (set "hello")))
(println "PASS: set")

;; === reduce-kv ===
(assert (= 6 (reduce-kv (fn [acc k v] (+ acc v)) 0 {:a 1 :b 2 :c 3})))
(assert (= {:a 2 :b 4}
           (reduce-kv (fn [m k v] (assoc m k (* 2 v))) {} {:a 1 :b 2})))
(println "PASS: reduce-kv")

(println "\n=== Conformance: Collections & Sequences PASSED ===")
