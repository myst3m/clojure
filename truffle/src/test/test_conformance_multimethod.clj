; Conformance Tests: Multimethods, Protocols, Records, Types

(println "=== Conformance: Multimethods & Protocols ===")

;; === defmulti / defmethod basic dispatch ===
(defmulti shape-area :type)
(defmethod shape-area :circle [{:keys [radius]}]
  (* 3.14159 radius radius))
(defmethod shape-area :rect [{:keys [width height]}]
  (* width height))
(defmethod shape-area :triangle [{:keys [base height]}]
  (/ (* base height) 2))
(assert (< (Math/abs (- 78.53975 (shape-area {:type :circle :radius 5}))) 0.001))
(assert (= 20 (shape-area {:type :rect :width 4 :height 5})))
(assert (= 15 (shape-area {:type :triangle :base 6 :height 5})))
(println "PASS: defmulti/defmethod basic")

;; === defmethod :default ===
(defmulti greet-lang :lang)
(defmethod greet-lang :en [_] "Hello")
(defmethod greet-lang :ja [_] "こんにちは")
(defmethod greet-lang :default [_] "???")
(assert (= "Hello" (greet-lang {:lang :en})))
(assert (= "こんにちは" (greet-lang {:lang :ja})))
(assert (= "???" (greet-lang {:lang :fr})))
(println "PASS: defmethod :default")

;; === remove-method ===
(remove-method greet-lang :ja)
(assert (= "???" (greet-lang {:lang :ja})))
(println "PASS: remove-method")

;; === methods ===
(assert (map? (methods greet-lang)))
(assert (contains? (methods greet-lang) :en))
(println "PASS: methods")

;; === defprotocol / defrecord ===
(defprotocol Drawable
  (draw [this])
  (area [this]))

(defrecord Circle [radius]
  Drawable
  (draw [this] (str "Drawing circle r=" radius))
  (area [this] (* 3.14159 radius radius)))

(defrecord Rect [w h]
  Drawable
  (draw [this] (str "Drawing rect " w "x" h))
  (area [this] (* w h)))

(let [c (->Circle 5)
      r (->Rect 3 4)]
  (assert (= "Drawing circle r=5" (draw c)))
  (assert (< (Math/abs (- 78.53975 (area c))) 0.001))
  (assert (= "Drawing rect 3x4" (draw r)))
  (assert (= 12 (area r))))
(println "PASS: defprotocol / defrecord")

;; === defrecord field access ===
(let [c (->Circle 10)]
  (assert (= 10 (:radius c))))
;; NOTE: .field access not supported for defrecord in tclj (uses keyword access)
(println "PASS: defrecord field access")

;; === defrecord as map ===
(let [c (->Circle 5)]
  (assert (= 5 (get c :radius)))
  (let [c2 (assoc c :color "red")]
    (assert (= "red" (:color c2)))
    (assert (= 5 (:radius c2)))))
(println "PASS: defrecord as map")

;; === map->Record ===
(let [c (map->Circle {:radius 7})]
  (assert (= 7 (:radius c)))
  (assert (= "Drawing circle r=7" (draw c))))
(println "PASS: map->Record")

;; === satisfies? ===
(assert (satisfies? Drawable (->Circle 1)))
(assert (satisfies? Drawable (->Rect 1 1)))
(assert (not (satisfies? Drawable "string")))
(println "PASS: satisfies?")

;; === deftype ===
(deftype Counter [^:volatile-mutable cnt]
  clojure.lang.IDeref
  (deref [this] cnt))

(let [c (Counter. 42)]
  (assert (= 42 @c)))
(println "PASS: deftype")

;; === extend-type ===
(defprotocol Describable
  (describe [this]))

(extend-type String
  Describable
  (describe [this] (str "String: " this)))

(extend-type Long
  Describable
  (describe [this] (str "Number: " this)))

(assert (= "String: hello" (describe "hello")))
(assert (= "Number: 42" (describe 42)))
(println "PASS: extend-type")

;; === extend-protocol ===
(defprotocol Printable
  (to-str [this]))

(extend-protocol Printable
  String
  (to-str [this] (str "\"" this "\""))
  Long
  (to-str [this] (str this))
  nil
  (to-str [this] "nil"))

(assert (= "\"hello\"" (to-str "hello")))
(assert (= "42" (to-str 42)))
(assert (= "nil" (to-str nil)))
(println "PASS: extend-protocol")

;; === reify ===
(let [obj (reify
            Describable
            (describe [this] "I am reified"))]
  (assert (= "I am reified" (describe obj))))
(println "PASS: reify")

(println "\n=== Conformance: Multimethods & Protocols PASSED ===")
