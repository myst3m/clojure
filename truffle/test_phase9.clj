;; Phase 9 Tests

(println "=== Volatile ===")
(def v (volatile! 0))
(println "volatile initial:" @v)
(vreset! v 42)
(println "after vreset!:" @v)
(vswap! v + 8)
(println "after vswap! + 8:" @v)
(println "volatile?:" (volatile? v))

(println "\n=== Promise/Deliver ===")
(def p (promise))
(println "realized? before:" (realized? p))
(deliver p 42)
(println "realized? after:" (realized? p))
(println "deref promise:" @p)

(println "\n=== Atom Watchers ===")
(def a (atom 0))
(def watch-log (atom []))
(add-watch a :logger (fn [key ref old-val new-val]
  (swap! watch-log conj {:old old-val :new new-val})))
(swap! a inc)
(swap! a inc)
(println "watch-log:" @watch-log)
(remove-watch a :logger)
(swap! a inc)
(println "watch-log after remove (should be same):" @watch-log)

(println "\n=== Atom Validator ===")
(def va (atom 0))
(set-validator! va pos?)
(try
  (reset! va -1)
  (println "ERROR: should have thrown")
  (catch Exception e
    (println "validator caught:" (ex-message e))))
(println "atom still valid:" @va)

(println "\n=== Comp & Partial ===")
(println "((comp str inc) 41):" ((comp str inc) 41))
(println "((comp inc inc inc) 0):" ((comp inc inc inc) 0))
(def add5 (partial + 5))
(println "(add5 10):" (add5 10))
(println "(add5 1 2 3):" (add5 1 2 3))

(println "\n=== Transducers ===")
;; map transducer
(println "(transduce (map inc) + 0 [1 2 3]):" (transduce (map inc) + 0 [1 2 3]))  ; 9
;; filter transducer
(println "(transduce (filter even?) + 0 [1 2 3 4]):" (transduce (filter even?) + 0 [1 2 3 4])) ; 6
;; comp transducers
(println "(transduce (comp (map inc) (filter even?)) + 0 [1 2 3 4]):"
  (transduce (comp (map inc) (filter even?)) + 0 [1 2 3 4])) ; 2+4 = 6
;; sequence with transducer
(println "(sequence (map inc) [1 2 3]):" (sequence (map inc) [1 2 3]))
;; reduced
(println "(reduced? (reduced 1)):" (reduced? (reduced 1)))

(println "\n=== Hierarchy ===")
(derive ::rect ::shape)
(derive ::square ::rect)
(println "(isa? ::rect ::shape):" (isa? ::rect ::shape))       ; true
(println "(isa? ::square ::shape):" (isa? ::square ::shape))   ; true
(println "(isa? ::shape ::rect):" (isa? ::shape ::rect))       ; false
(println "(parents ::square):" (parents ::square))
(println "(ancestors ::square):" (ancestors ::square))
(println "(descendants ::shape):" (descendants ::shape))

(println "\n=== Utility Functions ===")
;; tree-seq
(println "(tree-seq seq? seq '((1 2) (3 (4)))):"
  (tree-seq seq? seq '((1 2) (3 (4)))))

;; iterate
(println "(take 5 (iterate inc 0)):" (take 5 (iterate inc 0)))

;; cycle
(println "(take 7 (cycle [1 2 3])):" (take 7 (cycle [1 2 3])))

;; not=
(println "(not= 1 2):" (not= 1 2))
(println "(not= 1 1):" (not= 1 1))

;; empty / empty? / not-empty
(println "(empty [1 2 3]):" (empty [1 2 3]))
(println "(empty? []):" (empty? []))
(println "(empty? [1]):" (empty? [1]))
(println "(not-empty [1]):" (not-empty [1]))
(println "(not-empty []):" (not-empty []))

(println "\n=== Phase 9 Complete ===")
