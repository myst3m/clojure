;; Test reader conditionals
(def x #?(:clj "CLJ" :cljs "CLJS"))
(println "x:" x)
