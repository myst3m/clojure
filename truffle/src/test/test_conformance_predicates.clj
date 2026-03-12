; Conformance Tests: Predicates, Coercions & Utilities

(println "=== Conformance: Predicates & Coercions ===")

;; === Type predicates: coll? ===
(assert (coll? [1 2 3]))
(assert (coll? '(1 2 3)))
(assert (coll? #{1 2}))
(assert (coll? {:a 1}))
(assert (not (coll? "hello")))
(assert (not (coll? 42)))
(assert (not (coll? nil)))
(println "PASS: coll?")

;; === sequential? ===
(assert (sequential? [1 2]))
(assert (sequential? '(1 2)))
(assert (not (sequential? #{1})))
(assert (not (sequential? {:a 1})))
(assert (not (sequential? nil)))
(println "PASS: sequential?")

;; === associative? ===
(assert (associative? {:a 1}))
(assert (associative? [1 2 3]))
(assert (not (associative? '(1 2))))
(assert (not (associative? #{1})))
(assert (not (associative? nil)))
(println "PASS: associative?")

;; === counted? ===
(assert (counted? [1 2]))
(assert (counted? {:a 1}))
(assert (counted? #{1}))
(assert (not (counted? (iterate inc 0))))
(assert (not (counted? nil)))
(println "PASS: counted?")

;; === seqable? ===
(assert (seqable? [1 2]))
(assert (seqable? '(1 2)))
(assert (seqable? {:a 1}))
(assert (seqable? #{1}))
(assert (seqable? "hello"))
(assert (seqable? nil))
(assert (not (seqable? 42)))
(println "PASS: seqable?")

;; === reversible? ===
(assert (reversible? [1 2 3]))
(assert (reversible? (sorted-set 1 2 3)))
(assert (not (reversible? '(1 2))))
(assert (not (reversible? #{1})))
(assert (not (reversible? nil)))
(println "PASS: reversible?")

;; === sorted? ===
(assert (sorted? (sorted-map :a 1)))
(assert (sorted? (sorted-set 1 2)))
(assert (not (sorted? {:a 1})))
(assert (not (sorted? #{1})))
(assert (not (sorted? [1 2])))
(println "PASS: sorted?")

;; === indexed? ===
(assert (indexed? [1 2 3]))
(assert (not (indexed? '(1 2))))
(assert (not (indexed? {:a 1})))
(assert (not (indexed? nil)))
(println "PASS: indexed?")

;; === nat-int? ===
(assert (nat-int? 0))
(assert (nat-int? 42))
(assert (not (nat-int? -1)))
(assert (not (nat-int? 1.0)))
(assert (not (nat-int? nil)))
(println "PASS: nat-int?")

;; === pos-int? ===
(assert (pos-int? 1))
(assert (pos-int? 100))
(assert (not (pos-int? 0)))
(assert (not (pos-int? -1)))
(assert (not (pos-int? 1.0)))
(println "PASS: pos-int?")

;; === neg-int? ===
(assert (neg-int? -1))
(assert (neg-int? -100))
(assert (not (neg-int? 0)))
(assert (not (neg-int? 1)))
(assert (not (neg-int? -1.0)))
(println "PASS: neg-int?")

;; === int? ===
(assert (int? 42))
(assert (int? 0))
(assert (int? -1))
(assert (not (int? 1.5)))
(assert (not (int? "1")))
(println "PASS: int?")

;; === double? ===
(assert (double? 1.0))
(assert (double? 0.0))
(assert (not (double? 1)))
(assert (not (double? "1.0")))
(println "PASS: double?")

;; === decimal? ===
(assert (decimal? 1.0M))
(assert (not (decimal? 1.0)))
(assert (not (decimal? 1)))
(println "PASS: decimal?")

;; === rational? ===
(assert (rational? 1))
(assert (rational? 1/2))
(assert (rational? 1.0M))
(assert (not (rational? 1.0)))
(println "PASS: rational?")

;; === NaN? ===
(assert (NaN? Double/NaN))
(assert (not (NaN? 1.0)))
(assert (not (NaN? 0)))
(println "PASS: NaN?")

;; === infinite? ===
(assert (infinite? Double/POSITIVE_INFINITY))
(assert (infinite? Double/NEGATIVE_INFINITY))
(assert (not (infinite? 1.0)))
(assert (not (infinite? 0)))
(println "PASS: infinite?")

;; === ident? ===
(assert (ident? :foo))
(assert (ident? 'foo))
(assert (ident? :ns/foo))
(assert (ident? 'ns/foo))
(assert (not (ident? "foo")))
(assert (not (ident? 42)))
(println "PASS: ident?")

;; === simple-ident? ===
(assert (simple-ident? :foo))
(assert (simple-ident? 'foo))
(assert (not (simple-ident? :ns/foo)))
(assert (not (simple-ident? 'ns/foo)))
(assert (not (simple-ident? "foo")))
(println "PASS: simple-ident?")

;; === qualified-ident? ===
(assert (qualified-ident? :ns/foo))
(assert (qualified-ident? 'ns/foo))
(assert (not (qualified-ident? :foo)))
(assert (not (qualified-ident? 'foo)))
(assert (not (qualified-ident? "foo")))
(println "PASS: qualified-ident?")

;; === simple-keyword? ===
(assert (simple-keyword? :foo))
(assert (not (simple-keyword? :ns/foo)))
(assert (not (simple-keyword? 'foo)))
(println "PASS: simple-keyword?")

;; === qualified-keyword? ===
(assert (qualified-keyword? :ns/foo))
(assert (not (qualified-keyword? :foo)))
(assert (not (qualified-keyword? 'ns/foo)))
(println "PASS: qualified-keyword?")

;; === simple-symbol? ===
(assert (simple-symbol? 'foo))
(assert (not (simple-symbol? 'ns/foo)))
(assert (not (simple-symbol? :foo)))
(println "PASS: simple-symbol?")

;; === qualified-symbol? ===
(assert (qualified-symbol? 'ns/foo))
(assert (not (qualified-symbol? 'foo)))
(assert (not (qualified-symbol? :ns/foo)))
(println "PASS: qualified-symbol?")

;; === some? ===
(assert (some? 0))
(assert (some? false))
(assert (some? ""))
(assert (not (some? nil)))
(println "PASS: some?")

;; === any? ===
(assert (any? nil))
(assert (any? false))
(assert (any? 42))
(assert (any? "hello"))
(println "PASS: any?")

;; === ifn? ===
(assert (ifn? inc))
(assert (ifn? :keyword))
(assert (ifn? {:a 1}))
(assert (ifn? #{1 2}))
(assert (not (ifn? 42)))
(assert (not (ifn? "hello")))
(println "PASS: ifn?")

;; === fn? ===
(assert (fn? inc))
(assert (fn? (fn [x] x)))
(assert (not (fn? :keyword)))
(assert (not (fn? {:a 1})))
(println "PASS: fn?")

;; === var? ===
(assert (var? #'inc))
(assert (not (var? inc)))
(assert (not (var? 42)))
(println "PASS: var?")

;; === volatile? (requires volatile!) ===
(assert (volatile? (volatile! 0)))
(assert (not (volatile? (atom 0))))
(assert (not (volatile? 42)))
(println "PASS: volatile?")

;; === atom? (Clojure doesn't have atom? in core, skip) ===
;; NOTE: atom? is not a standard Clojure predicate

;; === map-entry? ===
(assert (map-entry? (first {:a 1})))
(assert (not (map-entry? [:a 1])))
(assert (not (map-entry? nil)))
(println "PASS: map-entry?")

;; === Coercions: byte short int long float double ===
(assert (= 42 (byte 42)))
(assert (= 42 (short 42)))
(assert (= 42 (int 42)))
(assert (= 42 (long 42)))
(assert (= 42.0 (float 42)))
(assert (= 42.0 (double 42)))
(assert (instance? java.lang.Long (long 42)))
(assert (instance? java.lang.Double (double 42)))
(println "PASS: byte short int long float double coercions")

;; === char ===
(assert (= \A (char 65)))
(assert (= \a (char 97)))
(assert (= \space (char 32)))
(println "PASS: char coercion")

;; === boolean ===
(assert (= true (boolean 1)))
(assert (= true (boolean "x")))
(assert (= false (boolean nil)))
(assert (= false (boolean false)))
(println "PASS: boolean coercion")

;; === num ===
(assert (= 42 (num 42)))
(assert (= 1.5 (num 1.5)))
(println "PASS: num coercion")

;; === bigint ===
(assert (= 42N (bigint 42)))
(assert (= 42N (bigint 42.0)))
(println "PASS: bigint coercion")

;; === bigdec ===
(assert (= 42.0M (bigdec 42)))
(assert (= 42.5M (bigdec 42.5)))
(assert (instance? java.math.BigDecimal (bigdec 42)))
(println "PASS: bigdec coercion")

;; === parse-long ===
(assert (= 42 (parse-long "42")))
(assert (= -1 (parse-long "-1")))
(assert (= 0 (parse-long "0")))
(assert (nil? (parse-long "abc")))
(assert (nil? (parse-long "")))
(assert (nil? (parse-long "1.5")))
(println "PASS: parse-long")

;; === parse-double ===
(assert (= 1.5 (parse-double "1.5")))
(assert (= 42.0 (parse-double "42")))
(assert (= -1.0 (parse-double "-1")))
(assert (nil? (parse-double "abc")))
(assert (nil? (parse-double "")))
(println "PASS: parse-double")

;; === parse-boolean ===
(assert (= true (parse-boolean "true")))
(assert (= false (parse-boolean "false")))
(assert (nil? (parse-boolean "yes")))
(assert (nil? (parse-boolean "")))
(assert (nil? (parse-boolean nil)))
(println "PASS: parse-boolean")

;; === parse-uuid ===
(let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
      u (parse-uuid uuid-str)]
  (assert (instance? java.util.UUID u))
  (assert (= uuid-str (str u))))
(assert (nil? (parse-uuid "not-a-uuid")))
(println "PASS: parse-uuid")

;; === random-uuid ===
(let [u (random-uuid)]
  (assert (instance? java.util.UUID u))
  (assert (not= u (random-uuid))))
(println "PASS: random-uuid")

;; === keyword constructor ===
(assert (= :foo (keyword "foo")))
(assert (= :ns/foo (keyword "ns" "foo")))
(assert (= :foo (keyword 'foo)))
(println "PASS: keyword constructor")

;; === symbol constructor ===
(assert (= 'foo (symbol "foo")))
(assert (= 'ns/foo (symbol "ns" "foo")))
(println "PASS: symbol constructor")

;; === name ===
(assert (= "foo" (name :foo)))
(assert (= "foo" (name :ns/foo)))
(assert (= "foo" (name 'foo)))
(assert (= "foo" (name 'ns/foo)))
(assert (= "foo" (name "foo")))
(println "PASS: name")

;; === namespace ===
(assert (= "ns" (namespace :ns/foo)))
(assert (= "ns" (namespace 'ns/foo)))
(assert (nil? (namespace :foo)))
(assert (nil? (namespace 'foo)))
(println "PASS: namespace")

;; === hash ===
(assert (integer? (hash :foo)))
(assert (integer? (hash "hello")))
(assert (integer? (hash 42)))
(assert (= (hash :foo) (hash :foo)))
(assert (= (hash [1 2 3]) (hash [1 2 3])))
(println "PASS: hash")

;; === identical? ===
(let [x [1 2 3]]
  (assert (identical? x x))
  (assert (not (identical? x [1 2 3]))))
(assert (identical? nil nil))
(assert (identical? true true))
(println "PASS: identical?")

;; === compare ===
(assert (= 0 (compare 1 1)))
(assert (neg? (compare 1 2)))
(assert (pos? (compare 2 1)))
(assert (= 0 (compare "a" "a")))
(assert (neg? (compare "a" "b")))
(assert (pos? (compare "b" "a")))
(assert (= 0 (compare :a :a)))
(assert (neg? (compare :a :b)))
(println "PASS: compare")

;; === not ===
(assert (= true (not false)))
(assert (= true (not nil)))
(assert (= false (not true)))
(assert (= false (not 42)))
(assert (= false (not "hello")))
(println "PASS: not")

;; === not= ===
(assert (not= 1 2))
(assert (not= :a :b))
(assert (not (not= 1 1)))
(assert (not (not= :a :a)))
(assert (not= 1 2 3))
(println "PASS: not=")

;; === unchecked-add ===
(assert (= 3 (unchecked-add 1 2)))
(assert (= 0 (unchecked-add -1 1)))
(println "PASS: unchecked-add")

;; === unchecked-inc ===
(assert (= 2 (unchecked-inc 1)))
(assert (= 0 (unchecked-inc -1)))
(println "PASS: unchecked-inc")

;; === unchecked-dec ===
(assert (= 0 (unchecked-dec 1)))
(assert (= -2 (unchecked-dec -1)))
(println "PASS: unchecked-dec")

;; === unchecked-multiply ===
(assert (= 6 (unchecked-multiply 2 3)))
(assert (= -6 (unchecked-multiply -2 3)))
(println "PASS: unchecked-multiply")

;; === unchecked-negate ===
(assert (= -1 (unchecked-negate 1)))
(assert (= 1 (unchecked-negate -1)))
(assert (= 0 (unchecked-negate 0)))
(println "PASS: unchecked-negate")

;; === unchecked-subtract ===
(assert (= 1 (unchecked-subtract 3 2)))
(assert (= -3 (unchecked-subtract -1 2)))
(println "PASS: unchecked-subtract")

;; === uri? ===
(assert (uri? (java.net.URI. "http://example.com")))
(assert (not (uri? "http://example.com")))
(assert (not (uri? nil)))
(println "PASS: uri?")

;; === uuid? ===
(assert (uuid? (random-uuid)))
(assert (uuid? (java.util.UUID/randomUUID)))
(assert (not (uuid? "550e8400-e29b-41d4-a716-446655440000")))
(assert (not (uuid? nil)))
(println "PASS: uuid?")

;; === inst? ===
(assert (inst? (java.util.Date.)))
(assert (not (inst? "2024-01-01")))
(assert (not (inst? nil)))
(println "PASS: inst?")

;; === array? (using Java arrays) ===
(assert (let [arr (into-array [1 2 3])] (-> arr class .isArray)))
(assert (not (.isArray (class [1 2 3]))))
(println "PASS: array check via .isArray")

(println "\n=== Conformance: Predicates & Coercions PASSED ===")
