;; Phase 13 Tests

(println "=== Named fn self-reference ===")
(def factorial
  (fn fact [n]
    (if (<= n 1) 1 (* n (fact (dec n))))))
(println "factorial 5:" (factorial 5))
(println "factorial 10:" (factorial 10))

;; Named fn with lazy-seq (the original Phase 12 failing test)
(def fibs-named
  (fn fib-seq [a b]
    (lazy-seq (cons a (fib-seq b (+ a b))))))
(println "First 10 fibs (named fn):" (vec (take 10 (fibs-named 0 1))))

(println "\n=== into with transducer ===")
(println "(into [] (map inc) [1 2 3]):" (into [] (map inc) [1 2 3]))
(println "(into [] (filter odd?) (range 10)):" (into [] (filter odd?) (range 10)))
(println "(into [] (comp (filter even?) (map #(* % %))) (range 10)):"
  (into [] (comp (filter even?) (map #(* % %))) (range 10)))
(println "(into #{} (map keyword) [\"a\" \"b\" \"a\"]):"
  (into #{} (map keyword) ["a" "b" "a"]))

(println "\n=== compare-and-set! ===")
(def a (atom 42))
(println "compare-and-set! correct:" (compare-and-set! a 42 99))
(println "atom after:" @a)
(println "compare-and-set! wrong:" (compare-and-set! a 42 100))
(println "atom after:" @a)

(println "\n=== every-pred / some-fn ===")
(def pos-even? (every-pred pos? even?))
(println "pos-even? 4:" (pos-even? 4))
(println "pos-even? -2:" (pos-even? -2))
(println "pos-even? 3:" (pos-even? 3))

(def str-or-num? (some-fn string? number?))
(println "str-or-num? \"hello\":" (str-or-num? "hello"))
(println "str-or-num? 42:" (str-or-num? 42))
(println "str-or-num? :kw:" (str-or-num? :kw))

(println "\n=== dedupe ===")
(println "dedupe [1 1 2 2 3 3 1]:" (dedupe [1 1 2 2 3 3 1]))

(println "\n=== sorted-set ===")
(def ss (sorted-set 3 1 4 1 5 9))
(println "sorted-set:" ss)
(println "contains? 4:" (contains? ss 4))

(println "\n=== prn-str ===")
(println "prn-str:" (pr-str (prn-str "hello" 42)))

(println "\n=== rename-keys ===")
(println "rename-keys:" (rename-keys {:a 1 :b 2} {:a :alpha :b :beta}))

(println "\n=== with-out-str ===")
(def captured (with-out-str (println "captured output")))
(println "with-out-str result:" (pr-str captured))

(println "\n=== not-any? / not-every? ===")
(println "not-any? neg? [1 2 3]:" (not-any? neg? [1 2 3]))
(println "not-any? neg? [1 -2 3]:" (not-any? neg? [1 -2 3]))
(println "not-every? pos? [1 2 3]:" (not-every? pos? [1 2 3]))
(println "not-every? pos? [1 -2 3]:" (not-every? pos? [1 -2 3]))

(println "\n=== shuffle / rand-nth ===")
(def v [1 2 3 4 5])
(println "shuffle (count):" (count (shuffle v)))
(println "rand-nth:" (contains? (set v) (rand-nth v)))

(println "\n=== Comprehensive: Named fn recursion ===")
;; Mutual-style: accumulator pattern with named fn
(def sum-to
  (fn sum [n acc]
    (if (zero? n) acc (sum (dec n) (+ acc n)))))
(println "sum 1-100:" (sum-to 100 0))

;; Tree flattening with named fn
(def my-flatten
  (fn flat [coll]
    (lazy-seq
      (when (seq coll)
        (let [x (first coll)]
          (if (sequential? x)
            (concat (flat x) (flat (rest coll)))
            (cons x (flat (rest coll)))))))))
(println "my-flatten:" (vec (my-flatten [1 [2 [3 4] 5] [6]])))

(println "\n=== Phase 13 Complete ===")
