; Conformance Tests: Strings, Regex, I/O, Bit Ops, Numerics

(println "=== Conformance: Strings, Regex & More ===")

;; === str/split ===
(assert (= ["a" "b" "c"] (str/split "a,b,c" #",")))
(assert (= ["a" "b,c"] (str/split "a,b,c" #"," 2)))
(println "PASS: str/split")

;; === str/replace ===
(assert (= "hello world" (str/replace "hello-world" #"-" " ")))
(assert (= "heLLo" (str/replace "hello" #"ll" "LL")))
(println "PASS: str/replace")

;; === str/trim ===
(assert (= "hello" (str/trim "  hello  ")))
(println "PASS: str/trim")

;; === str/join ===
(assert (= "a,b,c" (str/join "," ["a" "b" "c"])))
(assert (= "abc" (str/join ["a" "b" "c"])))
(println "PASS: str/join")

;; === str/blank? ===
(assert (str/blank? ""))
(assert (str/blank? "  "))
(assert (str/blank? nil))
(assert (not (str/blank? "x")))
(println "PASS: str/blank?")

;; === str/starts-with? / ends-with? / includes? ===
(assert (str/starts-with? "hello" "hel"))
(assert (not (str/starts-with? "hello" "llo")))
(assert (str/ends-with? "hello" "llo"))
(assert (str/includes? "hello world" "lo wo"))
(println "PASS: str/starts-with? ends-with? includes?")

;; === str/upper-case / lower-case ===
(assert (= "HELLO" (str/upper-case "hello")))
(assert (= "hello" (str/lower-case "HELLO")))
(println "PASS: str/upper-case lower-case")

;; === str/capitalize ===
(assert (= "Hello" (str/capitalize "hello")))
(assert (= "Hello" (str/capitalize "HELLO")))
(println "PASS: str/capitalize")

;; === str/reverse ===
(assert (= "olleh" (str/reverse "hello")))
(println "PASS: str/reverse")

;; === str/escape ===
(assert (= "a&amp;b" (str/escape "a&b" {\& "&amp;"})))
(println "PASS: str/escape")

;; === subs ===
(assert (= "llo" (subs "hello" 2)))
(assert (= "ll" (subs "hello" 2 4)))
(println "PASS: subs")

;; === str ===
(assert (= "hello world" (str "hello" " " "world")))
(assert (= "42" (str 42)))
(assert (= "" (str nil)))
(assert (= "truefalse" (str true false)))
(println "PASS: str")

;; === format ===
(assert (= "Hello World" (format "Hello %s" "World")))
(assert (= "Value: 42" (format "Value: %d" 42)))
(assert (= "Pi: 3.14" (format "Pi: %.2f" 3.14159)))
(println "PASS: format")

;; === re-find ===
(assert (= "123" (re-find #"\d+" "abc123def")))
(assert (nil? (re-find #"\d+" "abcdef")))
(println "PASS: re-find")

;; === re-matches ===
(assert (= "123" (re-matches #"\d+" "123")))
(assert (nil? (re-matches #"\d+" "abc123")))
(println "PASS: re-matches")

;; === re-seq ===
(assert (= '("1" "2" "3") (re-seq #"\d" "a1b2c3")))
(println "PASS: re-seq")

;; === re-find with groups ===
(let [[full y m d] (re-find #"(\d{4})-(\d{2})-(\d{2})" "date: 2024-01-15")]
  (assert (= "2024-01-15" full))
  (assert (= "2024" y))
  (assert (= "01" m))
  (assert (= "15" d)))
(println "PASS: re-find with groups")

;; === re-pattern ===
(let [p (re-pattern "\\d+")]
  (assert (= "42" (re-find p "abc42def"))))
(println "PASS: re-pattern")

;; === read-string ===
(assert (= '(+ 1 2) (read-string "(+ 1 2)")))
(assert (= 42 (read-string "42")))
(assert (= :hello (read-string ":hello")))
(println "PASS: read-string")

;; === char functions ===
(assert (= \A (char 65)))
(assert (= 65 (int \A)))
(println "PASS: char/int conversion")

;; === bit operations ===
(assert (= 0 (bit-and 5 2)))    ;; 101 & 010 = 000
(assert (= 7 (bit-or 5 3)))     ;; 101 | 011 = 111
(assert (= 6 (bit-xor 5 3)))    ;; 101 ^ 011 = 110
(assert (= -6 (bit-not 5)))     ;; ~101
(assert (= 20 (bit-shift-left 5 2)))   ;; 5 << 2 = 20
(assert (= 2 (bit-shift-right 10 2)))  ;; 10 >> 2 = 2
(assert (= 1 (unsigned-bit-shift-right 4 2))) ;; 4 >>> 2 = 1
(println "PASS: bit operations")

;; === bit-test / bit-set / bit-clear / bit-flip ===
(assert (bit-test 5 0))         ;; bit 0 of 101 is set
(assert (not (bit-test 5 1)))   ;; bit 1 of 101 is not set
(assert (= 7 (bit-set 5 1)))    ;; set bit 1: 101 -> 111
(assert (= 4 (bit-clear 5 0)))  ;; clear bit 0: 101 -> 100
(assert (= 7 (bit-flip 5 1)))   ;; flip bit 1: 101 -> 111
(println "PASS: bit-test/set/clear/flip")

;; === quot / rem / mod ===
(assert (= 3 (quot 10 3)))
(assert (= 1 (rem 10 3)))
(assert (= 1 (mod 10 3)))
(assert (= -1 (rem -10 3)))     ;; rem has sign of dividend
(assert (= 2 (mod -10 3)))      ;; mod always positive when divisor positive
(println "PASS: quot/rem/mod")

;; === ratio ===
(assert (= 1/2 (/ 1 2)))
(assert (= 3/4 (/ 3 4)))
(assert (ratio? 1/3))
(assert (not (ratio? 1)))
(println "PASS: ratio")

;; === max / min ===
(assert (= 5 (max 1 5 3)))
(assert (= 1 (min 1 5 3)))
(assert (= 5 (apply max [1 5 3])))
(println "PASS: max/min")

;; === abs ===
(assert (= 5 (abs -5)))
(assert (= 5 (abs 5)))
(assert (= 0 (abs 0)))
(println "PASS: abs")

;; === number predicates ===
(assert (number? 42))
(assert (number? 3.14))
(assert (integer? 42))
(assert (not (integer? 3.14)))
(assert (float? 3.14))
(assert (not (float? 42)))
(assert (zero? 0))
(assert (pos? 1))
(assert (neg? -1))
(assert (even? 4))
(assert (odd? 3))
(println "PASS: number predicates")

;; === type predicates ===
(assert (string? "hello"))
(assert (keyword? :hello))
(assert (symbol? 'hello))
(assert (map? {:a 1}))
(assert (vector? [1 2]))
(assert (list? '(1 2)))
(assert (set? #{1 2}))
(assert (seq? (seq [1 2])))
(assert (fn? inc))
(assert (nil? nil))
(assert (true? true))
(assert (false? false))
(println "PASS: type predicates")

(println "\n=== Conformance: Strings, Regex & More PASSED ===")
