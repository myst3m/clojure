;; Phase 11 Tests

(println "=== Collections as Functions ===")
(println "({:a 1 :b 2} :a):" ({:a 1 :b 2} :a))           ; 1
(println "({:a 1} :b :default):" ({:a 1} :b :default))    ; :default
(println "([10 20 30] 1):" ([10 20 30] 1))                 ; 20
(println "(#{1 2 3} 2):" (#{1 2 3} 2))                     ; 2
(println "(#{1 2 3} 5):" (#{1 2 3} 5))                     ; nil

(println "\n=== read-string ===")
(println "(read-string \"42\"):" (read-string "42"))
(println "(read-string \"[1 2 3]\"):" (read-string "[1 2 3]"))
(println "(read-string \"{:a 1}\"):" (read-string "{:a 1}"))
(println "(read-string \":keyword\"):" (read-string ":keyword"))

(println "\n=== walk / postwalk / prewalk ===")
(println "(postwalk #(if (number? %) (inc %) %) [1 [2 3]]):"
  (postwalk (fn [x] (if (number? x) (inc x) x)) [1 [2 3]]))
(println "(prewalk #(if (number? %) (inc %) %) [1 [2 3]]):"
  (prewalk (fn [x] (if (number? x) (inc x) x)) [1 [2 3]]))
(println "(postwalk-replace {:a :x :b :y} [:a :b :c]):"
  (postwalk-replace {:a :x :b :y} [:a :b :c]))

(println "\n=== update-keys / update-vals ===")
(println "(update-keys {:a 1 :b 2} name):" (update-keys {:a 1 :b 2} name))
(println "(update-vals {:a 1 :b 2} inc):" (update-vals {:a 1 :b 2} inc))

(println "\n=== Arithmetic ===")
(println "(mod 10 3):" (mod 10 3))
(println "(mod -1 3):" (mod -1 3))
(println "(rem 10 3):" (rem 10 3))
(println "(quot 10 3):" (quot 10 3))

(println "\n=== Bit Operations ===")
(println "(bit-and 0xFF 0x0F):" (bit-and 0xFF 0x0F))       ; 15
(println "(bit-or 0xF0 0x0F):" (bit-or 0xF0 0x0F))         ; 255
(println "(bit-xor 0xFF 0x0F):" (bit-xor 0xFF 0x0F))       ; 240
(println "(bit-shift-left 1 4):" (bit-shift-left 1 4))     ; 16
(println "(bit-shift-right 16 2):" (bit-shift-right 16 2)) ; 4

(println "\n=== Type Conversion ===")
(println "(long 3.14):" (long 3.14))
(println "(double 42):" (double 42))
(println "(boolean nil):" (boolean nil))
(println "(boolean 0):" (boolean 0))
(println "(not true):" (not true))
(println "(not nil):" (not nil))

(println "\n=== Map functions ===")
(println "(find {:a 1 :b 2} :a):" (find {:a 1 :b 2} :a))
(println "(key (find {:a 1} :a)):" (key (find {:a 1} :a)))
(println "(val (find {:a 1} :a)):" (val (find {:a 1} :a)))

(println "\n=== with-open ===")
;; Test with-open using a StringWriter
(def sw (java.io.StringWriter.))
(with-open [w sw]
  (.write w "hello from with-open"))
(println "with-open result:" (.toString sw))

(println "\n=== reify ===")
(def obj (reify
  Object
  (greet [this name] (str "Hello, " name "!"))
  (add [this a b] (+ a b))))
(println "reify type:" (type obj))

(println "\n=== require :as ===")
(require '[clojure.string :as s])
(println "(s/upper-case \"hello\"):" (s/upper-case "hello"))
(println "(s/join \", \" [1 2 3]):" (s/join ", " [1 2 3]))

(require '[clojure.set :as cset])
(println "(cset/union #{1 2} #{2 3}):" (cset/union #{1 2} #{2 3}))

(println "\n=== Phase 11 Complete ===")
