;; Phase 7 Tests

(println "=== Threading Macros ===")
(println "(-> 1 (+ 2) (* 3)):" (-> 1 (+ 2) (* 3)))          ; 9
(println "(->> 1 (+ 2) (* 3)):" (->> 1 (+ 2) (* 3)))        ; 9
(println "(as-> 0 v (+ v 1) (* v 3)):" (as-> 0 v (+ v 1) (* v 3))) ; 3

;; some-> with nil short-circuit
(println "(some-> 1 (+ 2)):" (some-> 1 (+ 2)))               ; 3
(println "(some-> nil (+ 2)):" (some-> nil (+ 2)))            ; nil

;; cond->
(println "(cond-> 1 true (+ 2) false (* 10)):" (cond-> 1 true (+ 2) false (* 10))) ; 3

;; doto
(def al (doto (java.util.ArrayList.) (.add 1) (.add 2) (.add 3)))
(println "doto ArrayList:" al)

;; .. (method chaining)
(println "(.. \"hello\" (toUpperCase) (substring 0 3)):" (.. "hello" (toUpperCase) (substring 0 3))) ; HEL

(println "\n=== Control Flow ===")
;; if-let
(println "(if-let [x 42] x :none):" (if-let [x 42] x :none))       ; 42
(println "(if-let [x nil] x :none):" (if-let [x nil] x :none))     ; :none

;; when-let
(println "(when-let [x 10] (+ x 5)):" (when-let [x 10] (+ x 5)))  ; 15
(println "(when-let [x nil] (+ x 5)):" (when-let [x nil] (+ x 5))); nil

;; if-some
(println "(if-some [x 0] x :none):" (if-some [x 0] x :none))       ; 0  (not nil)
(println "(if-some [x nil] x :none):" (if-some [x nil] x :none))   ; :none

;; when-some
(println "(when-some [x false] x):" (when-some [x false] x))       ; false

;; case
(println "(case 2 1 :one 2 :two 3 :three :default):" (case 2 1 :one 2 :two 3 :three :default)) ; :two
(println "(case :x 1 :one :miss):" (case :x 1 :one :miss))         ; :miss

(println "\n=== Comprehensions ===")
;; for
(println "(for [x [1 2 3]] (* x x)):" (for [x [1 2 3]] (* x x)))  ; (1 4 9)

;; nested for
(println "(for [x [1 2] y [3 4]] [x y]):" (for [x [1 2] y [3 4]] [x y]))

;; doseq (side effects)
(print "doseq: ")
(doseq [x [1 2 3]] (print x " "))
(println)

;; dotimes
(print "dotimes: ")
(dotimes [i 5] (print i " "))
(println)

(println "\n=== Collection Operations ===")
(println "(get-in {:a {:b 42}} [:a :b]):" (get-in {:a {:b 42}} [:a :b]))  ; 42
(println "(assoc-in {} [:a :b] 1):" (assoc-in {} [:a :b] 1))              ; {:a {:b 1}}
(println "(update-in {:a {:b 1}} [:a :b] inc):" (update-in {:a {:b 1}} [:a :b] inc)) ; {:a {:b 2}}
(println "(select-keys {:a 1 :b 2 :c 3} [:a :c]):" (select-keys {:a 1 :b 2 :c 3} [:a :c])) ; {:a 1, :c 3}
(println "(merge {:a 1} {:b 2} {:c 3}):" (merge {:a 1} {:b 2} {:c 3}))   ; {:a 1 :b 2 :c 3}
(println "(update {:a 1} :a inc):" (update {:a 1} :a inc))                ; {:a 2}

;; zipmap
(println "(zipmap [:a :b :c] [1 2 3]):" (zipmap [:a :b :c] [1 2 3]))

;; interleave
(println "(interleave [1 2 3] [:a :b :c]):" (interleave [1 2 3] [:a :b :c]))

;; interpose
(println "(interpose \", \" [\"a\" \"b\" \"c\"]):" (interpose ", " ["a" "b" "c"]))

;; distinct
(println "(distinct [1 2 1 3 2 4]):" (distinct [1 2 1 3 2 4]))

;; partition
(println "(partition 2 [1 2 3 4 5 6]):" (partition 2 [1 2 3 4 5 6]))

;; map-indexed
(println "(map-indexed vector [:a :b :c]):" (map-indexed vector [:a :b :c]))

;; keep
(println "(keep #(if (odd? %) %) [1 2 3 4 5]):" (keep (fn [x] (if (odd? x) x)) [1 2 3 4 5]))

(println "\n=== Type Predicates ===")
(println "(map? {:a 1}):" (map? {:a 1}))           ; true
(println "(vector? [1 2]):" (vector? [1 2]))       ; true
(println "(list? '(1 2)):" (list? '(1 2)))         ; true
(println "(string? \"hi\"):" (string? "hi"))       ; true
(println "(number? 42):" (number? 42))             ; true
(println "(keyword? :k):" (keyword? :k))           ; true
(println "(nil? nil):" (nil? nil))                 ; true
(println "(nil? 0):" (nil? 0))                     ; false
(println "(fn? +):" (fn? +))                       ; true
(println "(boolean? true):" (boolean? true))       ; true
(println "(coll? [1]):" (coll? [1]))               ; true
(println "(sequential? [1]):" (sequential? [1]))   ; true
(println "(sequential? {:a 1}):" (sequential? {:a 1})) ; false

(println "\n=== Higher-order Functions ===")
;; juxt
(println "((juxt + *) 2 3):" ((juxt + *) 2 3))    ; [5 6]

;; identity, constantly, complement
(println "(identity 42):" (identity 42))
(println "((constantly 5) :anything):" ((constantly 5) :anything))
(println "((complement nil?) 42):" ((complement nil?) 42))  ; true

;; sort-by
(println "(sort-by count [\"aa\" \"a\" \"aaa\"]):" (sort-by count ["aa" "a" "aaa"]))

;; flatten
(println "(flatten [1 [2 [3 4] 5]]):" (flatten [1 [2 [3 4] 5]]))

;; letfn
(letfn [(double [x] (* x 2))
        (triple [x] (* x 3))]
  (println "\nletfn (double 5):" (double 5))
  (println "letfn (triple 5):" (triple 5)))

(println "\n=== Phase 7 Complete ===")
