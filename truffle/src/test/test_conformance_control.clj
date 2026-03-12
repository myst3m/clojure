; Conformance Tests: Control Flow & Error Handling

(println "=== Conformance: Control Flow & Error Handling ===")

;; === case ===
(assert (= "one" (case 1 1 "one" 2 "two" "default")))
(assert (= "two" (case 2 1 "one" 2 "two" "default")))
(assert (= "default" (case 99 1 "one" 2 "two" "default")))
(assert (= "a or b" (case :a (:a :b) "a or b" :c "c")))
(assert (= "str" (case "hello" "hello" "str" "other")))
(println "PASS: case")

;; === condp ===
(assert (= "pos" (condp = 1 1 "pos" 2 "neg")))
(assert (= "small" (condp < 5 3 "small" 10 "big" "default")))
(assert (= "default" (condp = :x :a "a" :b "b" "default")))
(println "PASS: condp")

;; === cond-> ===
(assert (= 3 (cond-> 1 true inc true inc)))
(assert (= 2 (cond-> 1 true inc false inc)))
(assert (= "HELLO!" (cond-> "hello" true clojure.string/upper-case true (str "!"))))
(println "PASS: cond->")

;; === cond->> ===
(assert (= 10 (cond->> 1 true (+ 2) true (* 2) true (+ 4))))
(assert (= '(1 2 3) (cond->> (range 1 4) false reverse true seq)))
(println "PASS: cond->>")

;; === if-some ===
(assert (= 2 (if-some [x 1] (inc x) :none)))
(assert (= :none (if-some [x nil] (inc x) :none)))
(assert (= 1 (if-some [x false] 1 2))) ;; false is not nil
(println "PASS: if-some")

;; === when-some ===
(assert (= 2 (when-some [x 1] (inc x))))
(assert (nil? (when-some [x nil] (inc x))))
(println "PASS: when-some")

;; === while (via loop) ===
(let [a (atom 0)]
  (while (< @a 5) (swap! a inc))
  (assert (= 5 @a)))
(println "PASS: while")

;; === dotimes ===
(let [a (atom 0)]
  (dotimes [i 5] (swap! a + i))
  (assert (= 10 @a))) ;; 0+1+2+3+4=10
(println "PASS: dotimes")

;; === doseq ===
(let [a (atom [])]
  (doseq [x [1 2 3]] (swap! a conj (* x x)))
  (assert (= [1 4 9] @a)))
(println "PASS: doseq")

;; === doseq with multiple bindings ===
(let [a (atom [])]
  (doseq [x [1 2] y [:a :b]] (swap! a conj [x y]))
  (assert (= [[1 :a] [1 :b] [2 :a] [2 :b]] @a)))
(println "PASS: doseq multi-binding")

;; === for ===
(assert (= '(1 4 9) (for [x [1 2 3]] (* x x))))
(assert (= '([1 :a] [1 :b] [2 :a] [2 :b])
           (for [x [1 2] y [:a :b]] [x y])))
(println "PASS: for")

;; === for with :when ===
(assert (= '(2 4 6) (for [x (range 1 8) :when (even? x)] x)))
(println "PASS: for :when")

;; === for with :let ===
(assert (= '(1 4 9) (for [x [1 2 3] :let [y (* x x)]] y)))
(println "PASS: for :let")

;; === trampoline ===
(defn tramp-even? [n]
  (if (zero? n) true #(tramp-odd? (dec n))))
(defn tramp-odd? [n]
  (if (zero? n) false #(tramp-even? (dec n))))
(assert (= true (trampoline tramp-even? 10)))
(assert (= false (trampoline tramp-odd? 10)))
(println "PASS: trampoline")

;; === try/catch/finally ===
(assert (= "caught" (try (throw (Exception. "boom")) (catch Exception e "caught"))))
(let [a (atom nil)]
  (try (throw (Exception. "x")) (catch Exception e nil) (finally (reset! a :done)))
  (assert (= :done @a)))
(println "PASS: try/catch/finally")

;; === ex-info / ex-data / ex-message ===
(let [e (ex-info "test error" {:code 42})]
  (assert (= "test error" (ex-message e)))
  (assert (= {:code 42} (ex-data e))))
(assert (= "wrapped"
           (try (throw (ex-info "wrapped" {:type :test}))
                (catch Exception e (ex-message e)))))
(assert (= {:type :test}
           (try (throw (ex-info "wrapped" {:type :test}))
                (catch Exception e (ex-data e)))))
(println "PASS: ex-info / ex-data / ex-message")

;; === catch by type ===
(assert (= :npe (try (throw (NullPointerException.))
                     (catch NullPointerException e :npe)
                     (catch Exception e :other))))
(assert (= :other (try (throw (RuntimeException. "x"))
                       (catch NullPointerException e :npe)
                       (catch Exception e :other))))
(println "PASS: catch by type")

;; === some-> ===
(assert (= 3 (some-> 1 inc inc)))
(assert (nil? (some-> nil inc inc)))
(assert (= "HELLO" (some-> "hello" clojure.string/upper-case)))
(println "PASS: some->")

;; === some->> ===
(assert (= 6 (some->> 1 inc (* 3))))
(assert (nil? (some->> nil inc)))
(println "PASS: some->>")

;; === -> (threading) ===
(assert (= 3 (-> 1 inc inc)))
(assert (= [1 2 3] (-> [] (conj 1) (conj 2) (conj 3))))
(assert (= "HELLO" (-> "hello" clojure.string/upper-case)))
(println "PASS: ->")

;; === ->> (threading last) ===
(assert (= 25 (->> 5 (+ 10) (* 2) (- 55))))
(assert (= '(2 4 6) (->> (range 1 7) (filter even?))))
(println "PASS: ->>")

;; === as-> ===
(assert (= 3 (as-> 1 x (inc x) (inc x))))
(assert (= [1 2] (as-> [1] v (conj v 2))))
(println "PASS: as->")

(println "\n=== Conformance: Control Flow & Error Handling PASSED ===")
