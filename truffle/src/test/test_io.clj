(require '[clojure.java.io :as io])

;; file
(let [f (io/file "/tmp/test_io_clj.txt")]
  (assert (instance? java.io.File f) "io/file returns File"))
(println "PASS: io/file")

;; writer and reader
(let [f (io/file "/tmp/test_io_clj.txt")]
  (with-open [w (io/writer f)]
    (.write w "hello world"))
  (with-open [r (io/reader f)]
    (let [line (.readLine r)]
      (assert (= "hello world" line) (str "reader got: " line)))))
(println "PASS: io/writer and io/reader")

;; input-stream and output-stream
(with-open [os (io/output-stream "/tmp/test_io_clj2.txt")]
  (.write os (.getBytes "binary data")))
(with-open [is (io/input-stream "/tmp/test_io_clj2.txt")]
  (let [data (String. (.readAllBytes is))]
    (assert (= "binary data" data) (str "input-stream got: " data))))
(println "PASS: io/input-stream and io/output-stream")

;; delete-file
(io/delete-file "/tmp/test_io_clj.txt")
(io/delete-file "/tmp/test_io_clj2.txt")
(assert (not (.exists (io/file "/tmp/test_io_clj.txt"))) "file deleted")
(println "PASS: io/delete-file")

;; make-parents
(io/make-parents "/tmp/test_io_parents/a/b/c.txt")
(assert (.isDirectory (io/file "/tmp/test_io_parents/a/b")) "parents created")
(io/delete-file "/tmp/test_io_parents/a/b" true)
(io/delete-file "/tmp/test_io_parents/a" true)
(io/delete-file "/tmp/test_io_parents" true)
(println "PASS: io/make-parents")

;; IOFactory protocol
(assert (some? io/IOFactory) "IOFactory exists")
(assert (some? io/default-streams-impl) "default-streams-impl exists")
(println "PASS: IOFactory protocol and default-streams-impl")

;; make-input-stream
(let [data (.getBytes "test data")]
  (with-open [is (io/make-input-stream data nil)]
    (assert (instance? java.io.InputStream is) "make-input-stream returns InputStream")))
(println "PASS: io/make-input-stream")

;; copy
(with-open [os (java.io.ByteArrayOutputStream.)]
  (io/copy (.getBytes "copy test") os)
  (assert (= "copy test" (.toString os)) "copy works"))
(println "PASS: io/copy")

;; as-file
(assert (= (io/file "foo") (io/as-file "foo")) "as-file string")
(assert (nil? (io/as-file nil)) "as-file nil")
(println "PASS: io/as-file")

(println "\n=== All clojure.java.io tests passed! ===")
