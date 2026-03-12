; Conformance Tests: I/O & Misc

(println "=== Conformance: I/O & Misc ===")

;; === spit / slurp ===
(let [f (java.io.File/createTempFile "clj-test" ".txt")]
  (.deleteOnExit f)
  (spit f "hello world")
  (assert (= "hello world" (slurp f)))
  ;; spit with :append
  (spit f " again" :append true)
  (assert (= "hello world again" (slurp f))))
(println "PASS: spit / slurp")

;; === slurp with string path ===
(let [f (java.io.File/createTempFile "clj-test2" ".txt")
      path (.getAbsolutePath f)]
  (.deleteOnExit f)
  (spit path "path test")
  (assert (= "path test" (slurp path))))
(println "PASS: slurp with string path")

;; === line-seq ===
(let [f (java.io.File/createTempFile "clj-lines" ".txt")]
  (.deleteOnExit f)
  (spit f "line1\nline2\nline3")
  (let [rdr (java.io.BufferedReader. (java.io.FileReader. f))
        lines (vec (line-seq rdr))]
    (.close rdr)
    (assert (= ["line1" "line2" "line3"] lines))))
(println "PASS: line-seq")

;; === with-out-str ===
(assert (= "hello" (with-out-str (print "hello"))))
(assert (= "hello\n" (with-out-str (println "hello"))))
(println "PASS: with-out-str")

(assert (= "hello" (with-in-str "hello" (read-line))))
(assert (= "line1" (with-in-str "line1\nline2" (read-line))))
(println "PASS: with-in-str / read-line")

;; === print / println / pr / prn ===
(assert (= "42" (with-out-str (print 42))))
(assert (= "42\n" (with-out-str (println 42))))
(assert (= "\"hello\"" (with-out-str (pr "hello"))))
(assert (= "\"hello\"\n" (with-out-str (prn "hello"))))
(assert (= "42" (with-out-str (pr 42))))
(assert (= ":foo" (with-out-str (pr :foo))))
(println "PASS: print / println / pr / prn")

;; === printf ===
(assert (= "Hello World" (with-out-str (printf "Hello %s" "World"))))
(assert (= "Val: 42" (with-out-str (printf "Val: %d" 42))))
(println "PASS: printf")

;; === newline ===
(assert (= "\n" (with-out-str (newline))))
(println "PASS: newline")

;; === flush (just ensure it doesn't error) ===
(with-out-str (print "x") (flush))
(println "PASS: flush")

;; === pr-str / prn-str ===
(assert (= "\"hello\"" (pr-str "hello")))
(assert (= "42" (pr-str 42)))
(assert (= ":foo" (pr-str :foo)))
(assert (= "1 2 3" (pr-str 1 2 3)))
(assert (= "\"hello\"\n" (prn-str "hello")))
(println "PASS: pr-str / prn-str")

;; === print-str / println-str ===
(assert (= "hello" (print-str "hello")))
(assert (= "42" (print-str 42)))
(assert (= "1 2 3" (print-str 1 2 3)))
(assert (= "hello\n" (println-str "hello")))
(println "PASS: print-str / println-str")

;; === read-string ===
(assert (= 42 (read-string "42")))
(assert (= 3.14 (read-string "3.14")))
(assert (= "hello" (read-string "\"hello\"")))
(assert (= :foo (read-string ":foo")))
(assert (= 'bar (read-string "bar")))
(assert (= [1 2 3] (read-string "[1 2 3]")))
(assert (= {:a 1 :b 2} (read-string "{:a 1 :b 2}")))
(assert (= #{1 2 3} (read-string "#{1 2 3}")))
(assert (= true (read-string "true")))
(assert (= false (read-string "false")))
(assert (nil? (read-string "nil")))
(println "PASS: read-string")

;; === str/index-of / str/last-index-of ===
(require '[clojure.string :as str])
(assert (= 2 (str/index-of "hello" "l")))
(assert (= 0 (str/index-of "hello" "h")))
(assert (nil? (str/index-of "hello" "z")))
(assert (= 3 (str/last-index-of "hello" "l")))
(assert (= 0 (str/last-index-of "hello" "h")))
(assert (nil? (str/last-index-of "hello" "z")))
(println "PASS: str/index-of / str/last-index-of")

;; === str/replace-first ===
(assert (= "heLLo" (str/replace-first "hello" "ll" "LL")))
(assert (= "xbc" (str/replace-first "abc" "a" "x")))
(assert (= "abc" (str/replace-first "abc" "z" "x")))
(println "PASS: str/replace-first")

;; === str/triml / str/trimr ===
(assert (= "hello  " (str/triml "  hello  ")))
(assert (= "  hello" (str/trimr "  hello  ")))
(println "PASS: str/triml / str/trimr")

;; === clojure.string qualified names ===
(assert (= "ABC" (clojure.string/upper-case "abc")))
(assert (= "abc" (clojure.string/lower-case "ABC")))
(assert (= "hello" (clojure.string/trim "  hello  ")))
(println "PASS: clojure.string qualified names")

(let [m (re-matcher #"\d+" "abc123def456")]
  (assert (= "123" (re-find m)))
  (assert (= "456" (re-find m)))
  (assert (nil? (re-find m))))
(println "PASS: re-matcher / re-find 1-arg")

;; === format ===
(assert (= "Hello World" (format "Hello %s" "World")))
(assert (= "Value: 42" (format "Value: %d" 42)))
(assert (= "Pi: 3.14" (format "Pi: %.2f" 3.14159)))
(assert (= "Hex: ff" (format "Hex: %x" 255)))
(assert (= "Pad: 007" (format "Pad: %03d" 7)))
(assert (= "Multi: a 1" (format "Multi: %s %d" "a" 1)))
(println "PASS: format")

;; === keyword / symbol constructors ===
(assert (= :foo (keyword "foo")))
(assert (= :bar/baz (keyword "bar" "baz")))
(assert (= 'foo (symbol "foo")))
(assert (= 'bar/baz (symbol "bar" "baz")))
(println "PASS: keyword / symbol constructors")

;; === name / namespace ===
(assert (= "foo" (name :foo)))
(assert (= "baz" (name :bar/baz)))
(assert (= "bar" (namespace :bar/baz)))
(assert (nil? (namespace :foo)))
(assert (= "foo" (name 'foo)))
(assert (= "baz" (name 'bar/baz)))
(assert (= "bar" (namespace 'bar/baz)))
(assert (nil? (namespace 'foo)))
(println "PASS: name / namespace")

;; === munge / demunge ===
(assert (= "foo_bar" (munge "foo-bar")))
(assert (= "foo_QMARK_" (munge "foo?")))
(assert (= "foo-bar" (demunge "foo_bar")))
(assert (= "foo?" (demunge "foo_QMARK_")))
(println "PASS: munge / demunge")

;; === hash ===
(assert (integer? (hash "hello")))
(assert (integer? (hash 42)))
(assert (integer? (hash :foo)))
(assert (= (hash "hello") (hash "hello")))
(assert (not= (hash "hello") (hash "world")))
(println "PASS: hash")

;; === hash-map ===
(assert (= {:a 1 :b 2} (hash-map :a 1 :b 2)))
(assert (= {} (hash-map)))
(assert (map? (hash-map :a 1)))
(println "PASS: hash-map")

;; === hash-set ===
(assert (= #{1 2 3} (hash-set 1 2 3)))
(assert (= #{} (hash-set)))
(assert (set? (hash-set :a :b)))
(println "PASS: hash-set")

;; === sorted-map ===
(let [m (sorted-map :b 2 :a 1 :c 3)]
  (assert (= [:a :b :c] (keys m)))
  (assert (= [1 2 3] (vals m))))
(println "PASS: sorted-map")

;; === sorted-map-by ===
(let [m (sorted-map-by (fn [a b] (compare b a)) 1 "a" 3 "c" 2 "b")]
  (assert (= [3 2 1] (keys m)))
  (assert (= ["c" "b" "a"] (vals m))))
(println "PASS: sorted-map-by")

;; === sorted-set ===
(let [s (sorted-set 3 1 2)]
  (assert (= [1 2 3] (vec s))))
(println "PASS: sorted-set")

;; === sorted-set-by ===
(let [s (sorted-set-by (fn [a b] (compare b a)) 3 1 2)]
  (assert (= [3 2 1] (vec s))))
(println "PASS: sorted-set-by")

;; === array-map ===
(let [m (array-map :a 1 :b 2 :c 3)]
  (assert (= {:a 1 :b 2 :c 3} m))
  (assert (= [:a :b :c] (keys m))))
(println "PASS: array-map")

;; === update-keys ===
(assert (= {"a" 1 "b" 2} (update-keys {:a 1 :b 2} name)))
(println "PASS: update-keys")

;; === update-vals ===
(assert (= {:a 2 :b 3} (update-vals {:a 1 :b 2} inc)))
(println "PASS: update-vals")

;; === select-keys ===
(assert (= {:a 1 :c 3} (select-keys {:a 1 :b 2 :c 3} [:a :c])))
(assert (= {} (select-keys {:a 1} [:b])))
(println "PASS: select-keys")

;; === rename-keys (clojure.set) ===
(require '[clojure.set :as cset])
(assert (= {:x 1 :y 2} (cset/rename-keys {:a 1 :b 2} {:a :x :b :y})))
(assert (= {:a 1 :x 2} (cset/rename-keys {:a 1 :b 2} {:b :x})))
(println "PASS: rename-keys")

;; === find ===
(assert (= [:a 1] (vec (find {:a 1 :b 2} :a))))
(assert (nil? (find {:a 1 :b 2} :c)))
(println "PASS: find")

;; === key / val on map entries ===
(let [e (first {:a 1})]
  (assert (= :a (key e)))
  (assert (= 1 (val e))))
(println "PASS: key / val")

;; === keys / vals ===
(let [m {:a 1 :b 2}]
  (assert (= #{:a :b} (set (keys m))))
  (assert (= #{1 2} (set (vals m)))))
(println "PASS: keys / vals on map")

(println "\n=== Conformance: I/O & Misc PASSED ===")
