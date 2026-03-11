(defprotocol Stringable
  (to-str [this]))

(extend-protocol Stringable
  String
  (to-str [this] (str "string:" this))
  Long
  (to-str [this] (str "long:" this)))

(assert (= "string:hello" (to-str "hello")) "extend-protocol String")
(assert (= "long:42" (to-str 42)) "extend-protocol Long")
(println "PASS: extend-protocol basic")

(extend-protocol Stringable
  nil
  (to-str [_] "nil-value"))
(assert (= "nil-value" (to-str nil)) "extend-protocol nil")
(println "PASS: extend-protocol nil")

(defprotocol Countable (count-of [this]))
(extend String Countable {:count-of (fn [this] (.length this))})
(assert (= 5 (count-of "hello")) "extend function String")
(println "PASS: extend function")

(defprotocol Upperable (upper [this]))
(extend String Upperable {:upper (fn [this] (.toUpperCase this))})
(assert (= "HELLO" (upper "hello")) "extend multiple protocols")
(println "PASS: extend multiple protocols")

(defprotocol ByteHandler (handle-bytes [this]))
(extend-protocol ByteHandler
  (Class/forName "[B")
  (handle-bytes [this] (str "bytes:" (count this)))
  String
  (handle-bytes [this] (str "str:" this)))
(assert (= "str:hello" (handle-bytes "hello")) "extend-protocol Class/forName String")
(println "PASS: extend-protocol with Class/forName")

;; Test extend with assoc'd map (muuntaja pattern)
(defprotocol MySerializable
  (my-serialize [this])
  (my-deserialize [this]))

(def default-impl {:my-serialize (fn [this] (str this))
                    :my-deserialize (fn [this] this)})
(extend String MySerializable
  (assoc default-impl :my-serialize (fn [this] (str "custom:" this))))
(assert (= "custom:hello" (my-serialize "hello")) "extend with assoc'd map")
(println "PASS: extend with assoc'd default map")

(println "\n=== All extend tests passed! ===")
