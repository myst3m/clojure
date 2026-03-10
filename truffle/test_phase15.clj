;; Phase 15 Tests

(println "=== defrecord ===")
(defrecord Point [x y])
(def p (->Point 3 4))
(println "Point:" p)
(println "(:x p):" (:x p))
(println "(:y p):" (:y p))
(println "(get p :x):" (get p :x))

;; map->Record constructor
(def p2 (map->Point {:x 10 :y 20}))
(println "map->Point:" p2)
(println "(:x p2):" (:x p2))

;; assoc on record
(def p3 (assoc p :x 99))
(println "assoc :x 99:" p3)
(println "(:x p3):" (:x p3))

;; assoc with extra key
(def p4 (assoc p :z 5))
(println "assoc :z 5:" p4)
(println "(:z p4):" (:z p4))
(println "count p4:" (count p4))

;; seq on record
(println "seq p:" (seq p))

(println "\n=== Agents ===")
(def a (agent 0))
(println "agent?:" (agent? a))
(println "initial:" @a)
(send a inc)
(send a + 10)
(await a)
(println "after send inc + 10:" @a)

(println "\n=== Refs ===")
(def r (ref 100))
(println "ref?:" (ref? r))
(println "initial:" @r)
(dosync (alter r + 50))
(println "after alter + 50:" @r)
(dosync (ref-set r 42))
(println "after ref-set 42:" @r)

(println "\n=== clojure.java.io ===")
(require '[clojure.java.io :as io])
(def f (io/file "/tmp/truffle-test.txt"))
(spit "/tmp/truffle-test.txt" "hello from truffle")
(println "file exists:" (.exists f))
(println "slurp:" (slurp "/tmp/truffle-test.txt"))
(io/delete-file f)
(println "deleted:" (not (.exists f)))

(println "\n=== dosync (simplified) ===")
(def account-a (ref 1000))
(def account-b (ref 500))
(dosync
  (alter account-a - 200)
  (alter account-b + 200))
(println "account-a:" @account-a)
(println "account-b:" @account-b)

(println "\n=== Comprehensive: Record with protocol ===")
(defprotocol Shape
  (area [this])
  (perimeter [this]))

(defrecord Circle [radius]
  Shape
  (area [this] (* 3.14159 (:radius this) (:radius this)))
  (perimeter [this] (* 2 3.14159 (:radius this))))

(defrecord Rectangle [width height]
  Shape
  (area [this] (* (:width this) (:height this)))
  (perimeter [this] (* 2 (+ (:width this) (:height this)))))

(def c (->Circle 5))
(def r2 (->Rectangle 3 4))
(println "Circle area:" (area c))
(println "Circle perimeter:" (perimeter c))
(println "Rectangle area:" (area r2))
(println "Rectangle perimeter:" (perimeter r2))

;; Agent-based counter
(def counter (agent 0))
(dotimes [_ 10] (send counter inc))
(await counter)
(println "Agent counter:" @counter)

(println "\n=== Phase 15 Complete ===")
