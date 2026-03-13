;; JIT optimization benchmark

(defn sum-loop [n]
  (loop [i 0 acc 0]
    (if (>= i n)
      acc
      (recur (inc i) (+ acc i)))))

;; Warmup
(sum-loop 10000000)
(sum-loop 10000000)
(sum-loop 10000000)

;; Timed runs
(let [start (System/nanoTime)
      r1 (sum-loop 10000000)
      t1 (/ (- (System/nanoTime) start) 1000000.0)
      start2 (System/nanoTime)
      r2 (sum-loop 10000000)
      t2 (/ (- (System/nanoTime) start2) 1000000.0)
      start3 (System/nanoTime)
      r3 (sum-loop 10000000)
      t3 (/ (- (System/nanoTime) start3) 1000000.0)]
  (println (str "Run 1: " t1 "ms"))
  (println (str "Run 2: " t2 "ms"))
  (println (str "Run 3: " t3 "ms")))
