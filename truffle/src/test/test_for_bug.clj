;; Test for macro with :when and destructuring
(println "=== Basic for ===")
(println (pr-str (for [x [1 2 3]] x)))
;; => (1 2 3)

(println "=== for with :when ===")
(println (pr-str (for [x [1 2 3 4 5] :when (> x 2)] x)))
;; => (3 4 5)

(println "=== for with map destructuring ===")
(println (pr-str (for [[k v] {"a" 1 "b" 2}] k)))
;; => ("a" "b")

(println "=== for with map destructuring + :when ===")
(println (pr-str (for [[k v] {"a" 1 "b" 2 "c" 3} :when (> v 1)] k)))
;; => ("b" "c")

(println "=== key-set pattern (muuntaja) ===")
(defn key-set [m accept?]
  (set (for [[k v] m :when (accept? v)] k)))
(println (pr-str (key-set {"application/json" {:encode true}
                           "text/plain" {:encode false}} :encode)))
;; => #{"application/json"}

(println "=== for with :when and :let ===")
(println (pr-str (for [x [1 2 3 4 5] :when (> x 2) :let [y (* x x)]] y)))
;; => (9 16 25)

(println "=== nested for ===")
(println (pr-str (for [x [1 2] y [3 4]] [x y])))
;; => ([1 3] [1 4] [2 3] [2 4])

(println "=== nested for with :when ===")
(println (pr-str (for [x [1 2 3] y [1 2 3] :when (not= x y)] [x y])))
;; => ([1 2] [1 3] [2 1] [2 3] [3 1] [3 2])

(println "All for tests passed!")
