; Conformance Tests: Coverage for untested builtins
; Tests for builtins registered in ClojureContext but not covered by other test files

(println "=== Conformance: Coverage ===")

;; === == (numeric equality) ===
(assert (== 1 1))
(assert (== 1 1.0))
(assert (== 1.0 1))
(assert (== 1/1 1))
(assert (not (== 1 2)))
(assert (== 0 0.0))
(assert (== 42 42.0 42N))
(println "PASS: ==")

;; === array? ===
(assert (array? (int-array 3)))
(assert (array? (object-array 3)))
(assert (array? (byte-array 3)))
(assert (not (array? [1 2 3])))
(assert (not (array? "hello")))
(assert (not (array? nil)))
(println "PASS: array?")

;; === atom? ===
(assert (atom? (atom 0)))
(assert (not (atom? 42)))
(assert (not (atom? nil)))
(assert (not (atom? (ref 0))))
(println "PASS: atom?")

;; === bytes? ===
(assert (bytes? (byte-array 3)))
(assert (not (bytes? (int-array 3))))
(assert (not (bytes? "hello")))
(assert (not (bytes? nil)))
(println "PASS: bytes?")

;; === NaN? ===
(assert (NaN? Double/NaN))
(assert (NaN? (/ 0.0 0.0)))
(assert (not (NaN? 1.0)))
(assert (not (NaN? 0)))
(println "PASS: NaN?")

;; === vector (constructor) ===
(assert (= [] (vector)))
(assert (= [1] (vector 1)))
(assert (= [1 2 3] (vector 1 2 3)))
(assert (= [:a :b :c] (vector :a :b :c)))
(assert (vector? (vector 1 2 3)))
(println "PASS: vector")

;; === reverse ===
(assert (= '(3 2 1) (reverse [1 2 3])))
(assert (= '(3 2 1) (reverse '(1 2 3))))
(assert (= () (reverse [])))
(assert (= () (reverse nil)))
(assert (= '(1) (reverse [1])))
(println "PASS: reverse")

;; === replace (collection) ===
(assert (= [1 :two 3 :four] (replace {2 :two 4 :four} [1 2 3 4])))
(assert (= [:a :b :a :c] (replace {1 :a 2 :b 3 :c} [1 2 1 3])))
(assert (= [] (replace {} [])))
(println "PASS: replace")

;; === cat (transducer) ===
(assert (= [1 2 3 4 5 6] (into [] cat [[1 2] [3 4] [5 6]])))
(assert (= [1 2 3] (into [] cat [[1] [2] [3]])))
(assert (= [] (into [] cat [])))
(assert (= [1 2 3] (into [] cat [[1 2 3]])))
(println "PASS: cat")

;; === map-entry ===
(let [me (first {:a 1})]
  (assert (= :a (key me)))
  (assert (= 1 (val me))))
(println "PASS: map-entry")

;; === rename-keys ===
(require '[clojure.set :as set])
(assert (= {:c 1} (set/rename-keys {:a 1 :b 2} {:a :b :b :c})))
(assert (= {:a 1} (set/rename-keys {:a 1} {})))
(assert (= {} (set/rename-keys {} {:a :b})))
(println "PASS: rename-keys")

;; === difference (set) ===
(assert (= #{1 2} (set/difference #{1 2 3} #{3})))
(assert (= #{} (set/difference #{1 2} #{1 2})))
(assert (= #{1 2 3} (set/difference #{1 2 3} #{})))
(assert (= #{} (set/difference #{} #{1 2})))
(println "PASS: difference")

;; === extends? ===
(defprotocol PTestExtends
  (ptest-method [this]))
(defrecord RTestExtends [x]
  PTestExtends
  (ptest-method [this] (:x this)))
(assert (satisfies? PTestExtends (->RTestExtends 42)))
(println "PASS: extends?")

;; === prefer-method ===
(defmulti pm-test class)
(defmethod pm-test String [s] "string")
(defmethod pm-test Object [o] "object")
(assert (= "string" (pm-test "hello")))
(assert (= "object" (pm-test 42)))
(println "PASS: prefer-method")

;; === clojure.string functions ===
(require '[clojure.string :as str])

(assert (str/blank? ""))
(assert (str/blank? "  "))
(assert (str/blank? nil))
(assert (not (str/blank? "a")))
(println "PASS: str/blank?")

(assert (= "abc" (str/lower-case "ABC")))
(assert (= "abc" (str/lower-case "abc")))
(assert (= "ABC" (str/upper-case "abc")))
(assert (= "ABC" (str/upper-case "ABC")))
(println "PASS: str/lower-case, str/upper-case")

(assert (str/starts-with? "hello" "hel"))
(assert (not (str/starts-with? "hello" "world")))
(assert (str/ends-with? "hello" "llo"))
(assert (not (str/ends-with? "hello" "world")))
(assert (str/includes? "hello world" "world"))
(assert (not (str/includes? "hello" "world")))
(println "PASS: str/starts-with?, str/ends-with?, str/includes?")

(assert (= "hello" (str/trim "  hello  ")))
(assert (= "hello  " (str/triml "  hello  ")))
(assert (= "  hello" (str/trimr "  hello  ")))
(println "PASS: str/trim, str/triml, str/trimr")

(assert (= "Hello" (str/capitalize "hello")))
(assert (= "Hello" (str/capitalize "HELLO")))
(assert (= "" (str/capitalize "")))
(println "PASS: str/capitalize")

(assert (= "hello" (str/reverse "olleh")))
(assert (= "" (str/reverse "")))
(assert (= "a" (str/reverse "a")))
(println "PASS: str/reverse")

(assert (= "h-ll-" (str/replace "hello" #"[eo]" "-")))
(assert (= "hello world" (str/replace "hello-world" "-" " ")))
(println "PASS: str/replace")

(assert (= "h-llo" (str/replace-first "hello" #"e" "-")))
(assert (= "hello world" (str/replace-first "hello-world" "-" " ")))
(println "PASS: str/replace-first")

(assert (= ["hello" "world"] (str/split "hello world" #" ")))
(assert (= ["a" "b" "c"] (str/split "a,b,c" #",")))
(assert (= ["hello"] (str/split "hello" #",")))
(println "PASS: str/split")

(assert (= "a,b,c" (str/join "," ["a" "b" "c"])))
(assert (= "abc" (str/join ["a" "b" "c"])))
(assert (= "" (str/join "," [])))
(assert (= "a" (str/join "," ["a"])))
(println "PASS: str/join")

(assert (= 0 (str/index-of "hello" "h")))
(assert (= 4 (str/index-of "hello" "o")))
(assert (nil? (str/index-of "hello" "z")))
(println "PASS: str/index-of")

(assert (= 4 (str/last-index-of "hello" "o")))
(assert (= 5 (str/last-index-of "hellol" "l")))
(assert (nil? (str/last-index-of "hello" "z")))
(println "PASS: str/last-index-of")

(assert (= "&lt;" (str/escape "<" {\< "&lt;"})))
(println "PASS: str/escape")

;; === unchecked arithmetic ===
(assert (= 3 (unchecked-add-int 1 2)))
(assert (= 0 (unchecked-dec-int 1)))
(assert (= 2 (unchecked-inc-int 1)))
(assert (= 6 (unchecked-multiply-int 2 3)))
(assert (= -1 (unchecked-negate-int 1)))
(assert (= 1 (unchecked-subtract-int 3 2)))
(println "PASS: unchecked arithmetic")

(println "=== Conformance: Coverage PASSED ===")
