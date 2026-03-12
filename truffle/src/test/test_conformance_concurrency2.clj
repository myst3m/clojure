; Conformance Tests: Concurrency Extras

(println "=== Conformance: Concurrency Extras ===")

;; === agent-error / restart-agent ===
(let [a (agent 0)]
  (send a (fn [_] (throw (Exception. "agent err"))))
  (Thread/sleep 500)
  (let [err (agent-error a)]
    (assert (instance? Exception err))
    (assert (= "agent err" (.getMessage err))))
  (restart-agent a 99)
  (assert (= 99 @a))
  (assert (nil? (agent-error a))))
(println "PASS: agent-error / restart-agent")

;; === send-off ===
(let [a (agent [])]
  (send-off a conj 1)
  (send-off a conj 2)
  (await a)
  (assert (= [1 2] @a)))
(println "PASS: send-off")

;; === future? / future-done? ===
(let [f (future 42)]
  (assert (future? f))
  (assert (= 42 @f))
  (assert (future-done? f)))
(assert (not (future? 42)))
(assert (not (future? (atom 0))))
(println "PASS: future? / future-done?")

;; === future-call ===
(let [f (future-call (fn [] (+ 1 2 3)))]
  (assert (= 6 @f))
  (assert (future-done? f)))
(println "PASS: future-call")

;; === future-cancel ===
(let [f (future (Thread/sleep 10000) :done)]
  (future-cancel f)
  (assert (future-done? f)))
(println "PASS: future-cancel")

;; === deref with timeout ===
(let [p (promise)]
  ;; deref with timeout — 100ms, should timeout and return :timeout
  (let [result (deref p 100 :timeout)]
    (assert (= :timeout result))))
(println "PASS: deref with timeout")

;; === realized? on various types ===
(let [p (promise)]
  (assert (not (realized? p)))
  (deliver p 42)
  (assert (realized? p)))
(let [d (delay 42)]
  (assert (not (realized? d)))
  (force d)
  (assert (realized? d)))
(let [f (future 42)]
  @f ;; ensure done
  (assert (realized? f)))
(println "PASS: realized?")

;; === atom with metadata ===
(let [a (atom 42 :meta {:doc "test"})]
  (assert (= 42 @a)))
(println "PASS: atom with metadata")

;; === swap-vals! ===
;; swap-vals! returns [old new]
(let [a (atom 1)]
  (let [[old new] (swap-vals! a inc)]
    (assert (= 1 old))
    (assert (= 2 new))))
(println "PASS: swap-vals!")

;; === reset-vals! ===
;; reset-vals! returns [old new]
(let [a (atom 1)]
  (let [[old new] (reset-vals! a 42)]
    (assert (= 1 old))
    (assert (= 42 new))))
(println "PASS: reset-vals!")

;; === set-validator! / get-validator ===
(let [a (atom 0)]
  (set-validator! a pos?)
  (swap! a inc)
  (assert (= 1 @a))
  (try
    (swap! a - 10)
    (assert false "should have thrown")
    (catch Exception e
      (assert true)))
  (assert (= pos? (get-validator a))))
(println "PASS: set-validator! / get-validator")

;; === alter-meta! ===
(let [a (atom 0)]
  (alter-meta! a assoc :doc "test atom")
  (assert (= "test atom" (:doc (meta a)))))
(println "PASS: alter-meta!")

;; === ref basics ===
(let [r (ref 0)]
  (dosync (alter r inc))
  (assert (= 1 @r)))
(println "PASS: ref basics")

;; === multiple futures coordination ===
(let [results (atom [])
      futures (doall (for [i (range 5)]
                       (future (swap! results conj i))))]
  (doseq [f futures] @f)
  (assert (= 5 (count @results)))
  (assert (= #{0 1 2 3 4} (set @results))))
(println "PASS: multiple futures")

;; === promise double deliver ===
(let [p (promise)]
  (deliver p 42)
  (deliver p 99)  ;; second deliver is ignored
  (assert (= 42 @p)))
(println "PASS: promise double deliver")

;; === delay with side effect ===
(let [calls (atom 0)
      d (delay (swap! calls inc) :result)]
  (assert (= :result @d))
  (assert (= :result @d))
  (assert (= :result @d))
  (assert (= 1 @calls)))
(println "PASS: delay idempotent")

;; === locking nested ===
(let [lock1 (Object.)
      lock2 (Object.)
      a (atom 0)]
  (locking lock1
    (locking lock2
      (swap! a + 10)))
  (assert (= 10 @a)))
(println "PASS: locking nested")

(println "\n=== Conformance: Concurrency Extras PASSED ===")
