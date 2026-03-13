(require '[clojure.core.async :as async])
(println "chan type:" (type (async/chan 10)))
(let [ch (async/chan 10)]
  (println "putting...")
  (async/>!! ch "hello")
  (println "put done")
  (println "taking...")
  (println "got:" (async/<!! ch)))
