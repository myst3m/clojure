;; Phase 14 Tests

(println "=== future ===")
(def f (future (Thread/sleep 100) (+ 1 2)))
(println "future?:" (future? f))
(println "deref future:" @f)
(println "future-done?:" (future-done? f))

(println "\n=== locking ===")
(def counter (atom 0))
(def lock (Object.))
(dotimes [_ 5]
  (locking lock
    (swap! counter inc)))
(println "counter after locking:" @counter)

(println "\n=== transient collections ===")
(def tv (transient [1 2 3]))
(def tv2 (conj! tv 4))
(def pv (persistent! tv2))
(println "transient vector:" pv)

(def tm (transient {:a 1 :b 2}))
(def tm2 (assoc! tm :c 3))
(def pm (persistent! tm2))
(println "transient map:" pm)

;; Build vector with transient for performance
(def big-vec
  (persistent!
    (reduce (fn [tv x] (conj! tv x))
            (transient [])
            (range 10))))
(println "built with transient:" big-vec)

(println "\n=== identical? ===")
(def x "hello")
(println "identical? same:" (identical? x x))
(println "identical? diff:" (identical? "a" "b"))

(println "\n=== remove-method ===")
(defmulti greeting :lang)
(defmethod greeting :en [_] "Hello")
(defmethod greeting :ja [_] "こんにちは")
(defmethod greeting :fr [_] "Bonjour")
(println "en:" (greeting {:lang :en}))
(println "ja:" (greeting {:lang :ja}))
(remove-method greeting :fr)
(println "methods after remove:" (keys (methods greeting)))

(println "\n=== pmap ===")
(def results (pmap (fn [x] (* x x)) [1 2 3 4 5]))
(println "pmap squares:" results)

(println "\n=== bean ===")
(def props (java.util.Properties.))
(.setProperty props "name" "test")
(def b (bean props))
(println "bean has :class:" (contains? b :class))
(println "bean :class:" (:class b))

(println "\n=== bases ===")
(println "bases of String:" (bases String))

(println "\n=== Comprehensive: Transient reduce ===")
;; Build a map using transients
(def freq
  (persistent!
    (reduce (fn [m x] (assoc! m (keyword (str "k" x)) (* x x)))
            (transient {})
            (range 5))))
(println "transient map build:" freq)

;; Future-based parallel computation
(def futs (mapv (fn [x] (future (* x x x))) [1 2 3 4 5]))
(println "future cubes:" (mapv deref futs))

(println "\n=== Phase 14 Complete ===")
