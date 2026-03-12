; Conformance Tests: Transducers & Sequence Ops

(println "=== Conformance: Transducers & Sequence Ops ===")

;; === transduce with map ===
(assert (= 9 (transduce (map inc) + [1 2 3])))
(assert (= 19 (transduce (map inc) + 10 [1 2 3])))
(println "PASS: transduce with map")

;; === transduce with filter ===
(assert (= 9 (transduce (filter odd?) + [1 2 3 4 5])))
(assert (= 14 (transduce (filter odd?) + 10 [1 2 3])))
(println "PASS: transduce with filter")

;; === transduce with comp ===
(assert (= 12 (transduce (comp (filter odd?) (map inc)) + [1 2 3 4 5])))
(println "PASS: transduce with comp")

;; === into with transducer ===
(assert (= [2 3 4] (into [] (map inc) [1 2 3])))
(assert (= [1 3 5] (into [] (filter odd?) [1 2 3 4 5])))
(assert (= [2 4 6] (into [] (comp (filter odd?) (map inc)) [1 2 3 4 5])))
(assert (= #{2 3 4} (into #{} (map inc) [1 2 3])))
(println "PASS: into with transducer")

;; === sequence with transducer ===
(assert (= '(2 3 4) (sequence (map inc) [1 2 3])))
(assert (= '(1 3 5) (sequence (filter odd?) [1 2 3 4 5])))
(println "PASS: sequence with transducer")

;; === eduction (single xform) ===
(let [e (eduction (map inc) [1 2 3])]
  (assert (= [2 3 4] (into [] e))))
(let [e (eduction (filter odd?) (map inc) [1 2 3 4 5])]
  (assert (= [2 4 6] (into [] e))))
(println "PASS: eduction")

(let [f (completing + str)]
  (assert (= "6" (transduce identity f [1 2 3]))))
(println "PASS: completing")

;; === cat transducer ===
(assert (= [1 2 3 4 5 6] (into [] cat [[1 2] [3 4] [5 6]])))
(assert (= [1 2 3] (into [] cat [[1] [2] [3]])))
(println "PASS: cat transducer")

(assert (= [1 2] (transduce (halt-when #(> % 2)) conj [] [1 2 3 4 5])))
(println "PASS: halt-when")

;; === reduced / reduced? / unreduced ===
(assert (reduced? (reduced 42)))
(assert (not (reduced? 42)))
(assert (= 42 (unreduced (reduced 42))))
(assert (= 42 (unreduced 42)))
(assert (= 42 (deref (reduced 42))))
(assert (= 42 @(reduced 42)))
(let [r (ensure-reduced 42)]
  (assert (reduced? r))
  (assert (= 42 @r)))
(println "PASS: reduced / reduced? / unreduced")

;; === run! ===
(let [a (atom [])
      _ (run! #(swap! a conj %) [1 2 3])]
  (assert (= [1 2 3] @a)))
(assert (nil? (run! identity [])))
(println "PASS: run!")

;; === doall / dorun ===
(let [a (atom 0)
      s (doall (map #(do (swap! a inc) %) [1 2 3]))]
  (assert (= '(1 2 3) s))
  (assert (= 3 @a)))
(let [a (atom 0)
      _ (dorun (map #(do (swap! a inc) %) [1 2 3]))]
  (assert (= 3 @a)))
(println "PASS: doall / dorun")

;; === mapv / filterv ===
(assert (= [2 3 4] (mapv inc [1 2 3])))
(assert (vector? (mapv inc [1 2 3])))
(assert (= [1 3 5] (filterv odd? [1 2 3 4 5])))
(assert (vector? (filterv odd? [1 2 3 4 5])))
(assert (= [5 7 9] (mapv + [1 2 3] [4 5 6])))
(println "PASS: mapv / filterv")

;; === sort ===
(assert (= '(1 1 3 4 5) (sort [3 1 4 1 5])))
(assert (= '(5 4 3 2 1) (sort > [3 1 4 2 5])))
(assert (= () (sort [])))
(println "PASS: sort")

;; === sort-by ===
(assert (= ["a" "bb" "ccc"] (sort-by count ["ccc" "a" "bb"])))
(assert (= [{:a 1} {:a 2} {:a 3}] (sort-by :a [{:a 3} {:a 1} {:a 2}])))
(println "PASS: sort-by")

;; === shuffle ===
(let [v [1 2 3 4 5]
      s (shuffle v)]
  (assert (= (set v) (set s)))
  (assert (= (count v) (count s)))
  (assert (vector? s)))
(println "PASS: shuffle")

;; === rand / rand-int / rand-nth ===
(let [r (rand)]
  (assert (>= r 0.0))
  (assert (< r 1.0)))
(let [r (rand 10)]
  (assert (>= r 0.0))
  (assert (< r 10.0)))
(let [r (rand-int 10)]
  (assert (>= r 0))
  (assert (< r 10))
  (assert (integer? r)))
(let [v [1 2 3 4 5]
      r (rand-nth v)]
  (assert (some #(= r %) v)))
(println "PASS: rand / rand-int / rand-nth")

;; === partition ===
(assert (= '((1 2) (3 4)) (partition 2 [1 2 3 4 5])))
(assert (= '((1 2 3) (4 5 6)) (partition 3 [1 2 3 4 5 6 7])))
(assert (= '((1 2) (3 4) (5 6)) (partition 2 2 [1 2 3 4 5 6])))
(assert (= '((1 2 3) (3 4 5)) (partition 3 2 [1 2 3 4 5])))
(assert (= '((1 2 3) (3 4 5) (5 6 0)) (partition 3 2 [0] [1 2 3 4 5 6])))
(println "PASS: partition")

;; === partition-all ===
(assert (= '((1 2) (3 4) (5)) (partition-all 2 [1 2 3 4 5])))
(assert (= '((1 2 3) (4 5 6) (7)) (partition-all 3 [1 2 3 4 5 6 7])))
(assert (= '((1 2 3) (3 4 5) (5 6)) (partition-all 3 2 [1 2 3 4 5 6])))
(println "PASS: partition-all")

;; === split-at ===
(assert (= [(take 2 [1 2 3 4 5]) (drop 2 [1 2 3 4 5])] (split-at 2 [1 2 3 4 5])))
(assert (= [(take 0 [1 2 3]) (drop 0 [1 2 3])] (split-at 0 [1 2 3])))
(println "PASS: split-at")

;; === take-last / drop-last ===
(assert (= '(4 5) (take-last 2 [1 2 3 4 5])))
(assert (= '(5) (take-last 1 [1 2 3 4 5])))
(assert (= '(1 2 3) (drop-last [1 2 3 4])))
(assert (= '(1 2) (drop-last 2 [1 2 3 4])))
(assert (= () (drop-last 5 [1 2 3])))
(println "PASS: take-last / drop-last")

;; === nthrest / nthnext ===
(assert (= '(3 4 5) (nthrest [1 2 3 4 5] 2)))
(assert (= () (nthrest [1 2 3] 5)))
(assert (= '(3 4 5) (nthnext [1 2 3 4 5] 2)))
(assert (nil? (nthnext [1 2 3] 5)))
(println "PASS: nthrest / nthnext")

;; === ffirst / fnext / nnext ===
(assert (= 1 (ffirst [[1 2] [3 4]])))
(assert (= [3 4] (fnext [[1 2] [3 4]])))
(assert (= '([5 6]) (nnext [[1 2] [3 4] [5 6]])))
(assert (nil? (nnext [1 2])))
(assert (= '(2) (nfirst [[1 2] [3 4]])))
(println "PASS: ffirst / fnext / nnext")

;; === bounded-count ===
(assert (= 3 (bounded-count 10 [1 2 3])))
(assert (= 5 (bounded-count 5 (range 100))))
(assert (= 3 (bounded-count 5 [1 2 3])))
(println "PASS: bounded-count")

(println "\n=== Conformance: Transducers & Sequence Ops PASSED ===")
