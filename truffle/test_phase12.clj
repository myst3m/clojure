;; Phase 12 Tests

(println "=== delay / force ===")
(def d (delay (println "computing...") 42))
(println "realized? before:" (realized? d))
(println "force result:" (force d))
(println "force again (should not recompute):" (force d))

(println "\n=== Java Array Interop ===")
(def arr (object-array 5))
(aset arr 0 "hello")
(aset arr 1 "world")
(println "aget 0:" (aget arr 0))
(println "aget 1:" (aget arr 1))
(println "alength:" (alength arr))
(println "array?:" (array? arr))
(println "array? on vec:" (array? [1 2]))

(def arr2 (to-array [10 20 30]))
(println "to-array aget 2:" (aget arr2 2))

(def arr3 (into-array String ["a" "b" "c"]))
(println "into-array aget 1:" (aget arr3 1))

(println "\n=== dorun / doall ===")
(def side-effects (atom []))
(dorun (map (fn [x] (swap! side-effects conj x)) [1 2 3]))
(println "dorun side effects:" @side-effects)

(def realized (doall (map inc [1 2 3])))
(println "doall result:" realized)

(println "\n=== for :when / :let ===")
(println "(for [x (range 10) :when (even? x)] x):"
  (for [x (range 10) :when (even? x)] x))
(println "(for [x [1 2 3] :let [y (* x x)]] y):"
  (for [x [1 2 3] :let [y (* x x)]] y))
(println "(for [x [1 2 3] :when (odd? x) :let [y (* x 10)]] y):"
  (for [x [1 2 3] :when (odd? x) :let [y (* x 10)]] y))

(println "\n=== Arithmetic ===")
(println "(mod 10 3):" (mod 10 3))
(println "(rem -7 3):" (rem -7 3))
(println "(quot 17 5):" (quot 17 5))

(println "\n=== satisfies? ===")
(defprotocol Greetable
  (greet [this]))
(deftype Person [name])
(extend-type Person
  Greetable
  (greet [this] (str "Hello, " (.-name this))))
(def p (Person. "Alice"))
(println "greet Person:" (greet p))
(println "satisfies? Greetable Person:" (satisfies? Greetable p))

(println "\n=== prefer-method / methods ===")
(defmulti area :shape)
(defmethod area :circle [s] (* 3.14159 (:radius s) (:radius s)))
(defmethod area :rect [s] (* (:width s) (:height s)))
(println "area circle:" (area {:shape :circle :radius 5}))
(println "area rect:" (area {:shape :rect :width 3 :height 4}))
(println "methods keys:" (keys (methods area)))

(println "\n=== Comprehensive Test ===")
;; FizzBuzz
(def fizzbuzz
  (fn [n]
    (cond
      (zero? (mod n 15)) "FizzBuzz"
      (zero? (mod n 3)) "Fizz"
      (zero? (mod n 5)) "Buzz"
      :else (str n))))

(println "FizzBuzz 1-20:" (mapv fizzbuzz (range 1 21)))

;; Fibonacci with lazy seq
(defn fibs [a b]
  (lazy-seq (cons a (fibs b (+ a b)))))
(println "First 10 fibs:" (vec (take 10 (fibs 0 1))))

;; Nested data transformation
(def data [{:name "Alice" :scores [90 85 92]}
           {:name "Bob" :scores [78 88 95]}])
(println "Average scores:"
  (mapv (fn [d] {:name (:name d)
                 :avg (/ (reduce + (:scores d)) (count (:scores d)))})
        data))

(println "\n=== Phase 12 Complete ===")
