; Conformance Tests: Concurrency Primitives

(println "=== Conformance: Concurrency ===")

;; === atom basics ===
(let [a (atom 0)]
  (swap! a inc)
  (swap! a + 10)
  (assert (= 11 @a))
  (reset! a 42)
  (assert (= 42 @a)))
(println "PASS: atom basics")

;; === atom with swap! multi-arg ===
(let [a (atom [1 2])]
  (swap! a conj 3)
  (assert (= [1 2 3] @a))
  (swap! a into [4 5])
  (assert (= [1 2 3 4 5] @a)))
(println "PASS: atom swap! multi-arg")

;; === atom compare-and-set! ===
(let [a (atom 1)]
  (assert (compare-and-set! a 1 2))
  (assert (= 2 @a))
  (assert (not (compare-and-set! a 1 3)))
  (assert (= 2 @a)))
(println "PASS: compare-and-set!")

;; === atom with validator ===
(let [a (atom 0 :validator pos?)]
  (swap! a inc)
  (assert (= 1 @a))
  (try
    (swap! a - 10)
    (assert false "should have thrown")
    (catch Exception e
      (assert true))))
(println "PASS: atom validator")

;; === atom with watcher ===
(let [log (atom [])
      a (atom 0)]
  (add-watch a :logger (fn [k ref old new]
                          (swap! log conj [old new])))
  (swap! a inc)
  (swap! a inc)
  (remove-watch a :logger)
  (swap! a inc) ;; not logged
  (assert (= [[0 1] [1 2]] @log)))
(println "PASS: atom watcher")

;; === ref / dosync / alter ===
(let [r (ref 0)]
  (dosync (alter r inc))
  (assert (= 1 @r))
  (dosync (alter r + 10))
  (assert (= 11 @r)))
(println "PASS: ref / alter")

;; === ref / commute ===
(let [r (ref 0)]
  (dosync (commute r + 5))
  (assert (= 5 @r)))
(println "PASS: ref / commute")

;; === ref / ref-set ===
(let [r (ref 42)]
  (dosync (ref-set r 99))
  (assert (= 99 @r)))
(println "PASS: ref / ref-set")

;; === multiple refs in dosync ===
(let [a (ref 100)
      b (ref 0)]
  (dosync
    (alter a - 30)
    (alter b + 30))
  (assert (= 70 @a))
  (assert (= 30 @b))
  (assert (= 100 (+ @a @b))))
(println "PASS: multiple refs in dosync")

;; === agent ===
(let [a (agent 0)]
  (send a inc)
  (send a + 10)
  (await a)
  (assert (= 11 @a)))
(println "PASS: agent")

;; === future ===
(let [f (future (+ 1 2 3))]
  (assert (= 6 @f))
  (assert (= 6 @f)) ;; deref again
  (assert (future-done? f)))
(println "PASS: future")

;; === promise ===
(let [p (promise)]
  (future (deliver p 42))
  (assert (= 42 @p))
  (assert (realized? p)))
(println "PASS: promise")

;; === delay ===
(let [side-effect (atom 0)
      d (delay (swap! side-effect inc) 42)]
  (assert (not (realized? d)))
  (assert (= 42 @d))
  (assert (realized? d))
  (assert (= 42 @d)) ;; cached
  (assert (= 1 @side-effect))) ;; computed once
(println "PASS: delay")

;; === volatile ===
(let [v (volatile! 0)]
  (vswap! v inc)
  (vswap! v + 10)
  (assert (= 11 @v))
  (vreset! v 42)
  (assert (= 42 @v)))
(println "PASS: volatile")

;; === locking ===
(let [a (atom 0)
      lock (Object.)]
  (locking lock
    (swap! a inc))
  (assert (= 1 @a)))
(println "PASS: locking")

(println "\n=== Conformance: Concurrency PASSED ===")
