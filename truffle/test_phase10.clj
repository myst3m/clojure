;; Phase 10 Tests

(println "=== Control Flow ===")
(println "(when-not false :yes):" (when-not false :yes))       ; :yes
(println "(when-not true :yes):" (when-not true :yes))         ; nil
(println "(if-not false :a :b):" (if-not false :a :b))         ; :a
(println "(if-not true :a :b):" (if-not true :a :b))           ; :b

;; condp
(println "(condp = 2 1 :one 2 :two 3 :three):" (condp = 2 1 :one 2 :two 3 :three))
(println "(condp = :x 1 :one :default):" (condp = :x 1 :one :default))

;; while
(def counter (atom 0))
(while (< @counter 5) (swap! counter inc))
(println "while result:" @counter)

;; comment
(comment (throw (Exception. "should not execute")))
(println "comment passed")

;; declare / defonce
(declare my-fn)
(defonce my-val 42)
(defonce my-val 99)  ;; should not change
(println "defonce my-val:" my-val)

(println "\n=== Core Functions ===")
(println "(vec '(1 2 3)):" (vec '(1 2 3)))
(println "(second [10 20 30]):" (second [10 20 30]))
(println "(last [1 2 3 4]):" (last [1 2 3 4]))
(println "(butlast [1 2 3 4]):" (butlast [1 2 3 4]))
(println "(peek [1 2 3]):" (peek [1 2 3]))
(println "(pop [1 2 3]):" (pop [1 2 3]))
(println "(subvec [0 1 2 3 4] 1 4):" (subvec [0 1 2 3 4] 1 4))
(println "(fnext [1 2 3]):" (fnext [1 2 3]))
(println "(ffirst [[1 2] [3 4]]):" (ffirst [[1 2] [3 4]]))
(println "(next [1 2 3]):" (next [1 2 3]))
(println "(next [1]):" (next [1]))
(println "(nth '(a b c) 1):" (nth '(a b c) 1))

;; apply with spread
(println "(apply + 1 2 [3 4]):" (apply + 1 2 [3 4]))          ; 10
(println "(apply str [\"a\" \"b\" \"c\"]):" (apply str ["a" "b" "c"])) ; "abc"

;; sort, reverse
(println "(sort [3 1 2]):" (sort [3 1 2]))
(println "(sort > [3 1 2]):" (sort > [3 1 2]))
(println "(reverse [1 2 3]):" (reverse [1 2 3]))

;; max, min, abs
(println "(max 1 5 3):" (max 1 5 3))
(println "(min 1 5 3):" (min 1 5 3))
(println "(abs -42):" (abs -42))

;; mapcat
(println "(mapcat #(repeat 2 %) [1 2 3]):" (mapcat (fn [x] (repeat 2 x)) [1 2 3]))

;; range with no args (take to limit)
(println "(take 5 (range)):" (take 5 (range)))

(println "\n=== Type Predicates ===")
(println "(some? 42):" (some? 42))
(println "(some? nil):" (some? nil))
(println "(true? true):" (true? true))
(println "(false? false):" (false? false))
(println "(zero? 0):" (zero? 0))
(println "(int? 42):" (int? 42))
(println "(int? 3.14):" (int? 3.14))
(println "(double? 3.14):" (double? 3.14))
(println "(pos-int? 5):" (pos-int? 5))
(println "(nat-int? 0):" (nat-int? 0))
(println "(associative? {:a 1}):" (associative? {:a 1}))
(println "(associative? [1 2]):" (associative? [1 2]))
(println "(atom? (atom 0)):" (atom? (atom 0)))

(println "\n=== str improvements ===")
(println "(str nil):" (str nil))              ; ""
(println "(str nil \"hello\" nil):" (str nil "hello" nil))  ; "hello"
(println "(pr-str \"hello\"):" (pr-str "hello"))

;; assoc on vectors
(println "(assoc [0 1 2] 1 :x):" (assoc [0 1 2] 1 :x))
;; assoc on nil creates map
(println "(assoc nil :a 1):" (assoc nil :a 1))

(println "\n=== Phase 10 Complete ===")
