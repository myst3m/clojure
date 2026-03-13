(require 'taoensso.encore)
(println "encore loaded")
;; Try calling merge-with* directly
(try
  (println (taoensso.encore/nested-merge {:a 1} {:b 2}))
  (catch Exception e (println "ERROR:" (.getMessage e))))
