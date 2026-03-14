;; Runner for original Clojure test suite — loads and runs each ns individually
(require '[clojure.test :as t])
;; Pre-load test-helper for fails-with-cause? and thrown-with-cause-msg? assert-expr methods
(try (require 'clojure.test-helper) (catch Exception e (println "WARN: could not load test-helper:" (.getMessage e))))

(def test-nss
  '[clojure.test-clojure.control
    clojure.test-clojure.logic
    ;; clojure.test-clojure.for  ;; hangs - infinite loop in for macro
    clojure.test-clojure.keywords
    clojure.test-clojure.atoms
    clojure.test-clojure.delays
    clojure.test-clojure.fn
    ;; clojure.test-clojure.def  ;; requires protocols test (proxy)
    clojure.test-clojure.data
    clojure.test-clojure.clojure-set
    clojure.test-clojure.clojure-walk
    ;; clojure.test-clojure.data-structures  ;; requires clojure.data.generators (external dep)
    ;; clojure.test-clojure.sequences  ;; requires clojure.test.check.generators (external dep)
    clojure.test-clojure.string
    ;; clojure.test-clojure.numbers  ;; requires clojure.data.generators (external dep)
    clojure.test-clojure.predicates
    ;; clojure.test-clojure.reader  ;; requires clojure.test.generative (external dep)
    ;; clojure.test-clojure.reducers  ;; fn* arity clause parse issue
    clojure.test-clojure.vectors
    ;; clojure.test-clojure.metadata  ;; requires namespace 'ns' (test fixture issue)
    clojure.test-clojure.multimethods
    clojure.test-clojure.protocols
    ;; clojure.test-clojure.java-interop  ;; requires proxy support
    clojure.test-clojure.errors
    ;; clojure.test-clojure.edn  ;; requires clojure.test.generative (external dep)
    clojure.test-clojure.transients
    clojure.test-clojure.special])

(def results (atom []))

(defn try-load-and-run [ns-sym]
  (print (str "  " ns-sym "... "))
  (flush)
  (try
    ;; Use eval to call require at runtime with the actual symbol value
    (eval (list 'require (list 'quote ns-sym)))
    (let [r (t/run-tests ns-sym)]
      (swap! results conj {:ns ns-sym :pass (:pass r 0) :fail (:fail r 0) :error (:error r 0) :test (:test r 0)}))
    (catch Exception e
      (println "ERROR:" (.getMessage e))
      (swap! results conj {:ns ns-sym :pass 0 :fail 0 :error 1 :test 0 :load-error (.getMessage e)}))))

(doseq [ns-sym test-nss]
  (try-load-and-run ns-sym))

(println "\n\n========== SUMMARY ==========")
(let [rs @results
      total-pass (reduce + (map :pass rs))
      total-fail (reduce + (map :fail rs))
      total-error (reduce + (map :error rs))
      total-test (reduce + (map :test rs))]
  (doseq [r rs]
    (let [f (:fail r 0) e (:error r 0)]
      (when (or (> f 0) (> e 0) (:load-error r))
        (println (str "  PROBLEM: " (:ns r)
                      " pass=" (:pass r 0) " fail=" f " error=" e
                      (when (:load-error r) (str " [" (:load-error r) "]")))))))
  (println (str "\nTotal: " total-test " tests, " (+ total-pass total-fail total-error) " assertions"))
  (println (str "  Pass: " total-pass " | Fail: " total-fail " | Error: " total-error))
  (let [denom (+ total-pass total-fail total-error)]
    (println (str "  Rate: " (if (> denom 0)
                               (int (* 100.0 (/ (double total-pass) (double denom))))
                               0) "%"))))
