;; Load truss and check what vars were defined
(require 'taoensso.truss)
(println "ns publics:" (count (ns-publics 'taoensso.truss)))
(println "ns interns:" (count (ns-interns 'taoensso.truss)))
(println "all vars:")
(doseq [[k v] (ns-interns 'taoensso.truss)]
  (println "  " k "=" (type v)))
