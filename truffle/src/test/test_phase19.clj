; Phase 19: Metadata, Parsing Functions, Type Predicates

;; === ^:dynamic metadata on def ===
(def ^:dynamic *test-var* 42)
(assert (= 42 *test-var*))
(binding [*test-var* 99]
  (assert (= 99 *test-var*)))
(assert (= 42 *test-var*))
(println "PASS: ^:dynamic metadata on def")

;; === Earmuff convention auto-dynamic ===
(def *auto-dynamic* 10)
(binding [*auto-dynamic* 20]
  (assert (= 20 *auto-dynamic*)))
(println "PASS: earmuff auto-dynamic")

;; === def with docstring ===
(def my-documented-var "This is documented" 123)
(assert (= 123 my-documented-var))
(println "PASS: def with docstring")

;; === defn- (private defn) ===
(defn- private-fn [x] (* x 2))
(assert (= 10 (private-fn 5)))
(println "PASS: defn-")

;; === parse-long ===
(assert (= 42 (parse-long "42")))
(assert (= -7 (parse-long "-7")))
(assert (nil? (parse-long "abc")))
(assert (nil? (parse-long nil)))
(println "PASS: parse-long")

;; === parse-double ===
(assert (= 3.14 (parse-double "3.14")))
(assert (nil? (parse-double "xyz")))
(println "PASS: parse-double")

;; === parse-boolean ===
(assert (= true (parse-boolean "true")))
(assert (= false (parse-boolean "false")))
(assert (nil? (parse-boolean "yes")))
(println "PASS: parse-boolean")

;; === parse-uuid ===
(let [u (parse-uuid "550e8400-e29b-41d4-a716-446655440000")]
  (assert (uuid? u)))
(assert (nil? (parse-uuid "not-a-uuid")))
(println "PASS: parse-uuid")

;; === random-uuid ===
(let [u (random-uuid)]
  (assert (uuid? u)))
(println "PASS: random-uuid")

;; === print-str ===
(assert (= "hello world" (print-str "hello" "world")))
(assert (= "1 2 3" (print-str 1 2 3)))
(println "PASS: print-str")

;; === println-str ===
(assert (= "hello world\n" (println-str "hello" "world")))
(println "PASS: println-str")

;; === rational? ===
(assert (rational? 42))
(assert (not (rational? 3.14)))
(assert (not (rational? "hello")))
(println "PASS: rational?")

;; === decimal? ===
(assert (not (decimal? 42)))
(assert (not (decimal? 3.14)))
(assert (decimal? 3.14M))
(println "PASS: decimal?")

;; === ident? ===
(assert (ident? :foo))
(assert (ident? 'bar))
(assert (not (ident? "baz")))
(println "PASS: ident?")

;; === simple-ident? / qualified-ident? ===
(assert (simple-ident? :foo))
(assert (not (simple-ident? :ns/foo)))
(assert (qualified-ident? :ns/foo))
(assert (not (qualified-ident? :foo)))
(println "PASS: simple-ident? / qualified-ident?")

;; === simple-keyword? / qualified-keyword? ===
(assert (simple-keyword? :foo))
(assert (not (simple-keyword? :ns/foo)))
(assert (qualified-keyword? :ns/foo))
(assert (not (qualified-keyword? :foo)))
(assert (not (simple-keyword? 'foo)))
(println "PASS: simple-keyword? / qualified-keyword?")

;; === simple-symbol? / qualified-symbol? ===
(assert (simple-symbol? 'foo))
(assert (not (simple-symbol? 'ns/foo)))
(assert (qualified-symbol? 'ns/foo))
(assert (not (qualified-symbol? 'foo)))
(assert (not (simple-symbol? :foo)))
(println "PASS: simple-symbol? / qualified-symbol?")

;; === inst? ===
(assert (inst? (java.util.Date.)))
(assert (not (inst? 42)))
(println "PASS: inst?")

;; === uuid? ===
(assert (uuid? (random-uuid)))
(assert (not (uuid? "string")))
(println "PASS: uuid?")

;; === any? ===
(assert (any? 42))
(assert (any? nil))
(assert (any? "hello"))
(println "PASS: any?")

;; === NaN? ===
(assert (NaN? ##NaN))
(assert (not (NaN? 1.0)))
(assert (not (NaN? 42)))
(println "PASS: NaN?")

;; === infinite? ===
(assert (infinite? ##Inf))
(assert (infinite? ##-Inf))
(assert (not (infinite? 1.0)))
(println "PASS: infinite?")

;; === pos-int? / neg-int? / nat-int? ===
(assert (pos-int? 1))
(assert (not (pos-int? 0)))
(assert (not (pos-int? -1)))
(assert (neg-int? -1))
(assert (not (neg-int? 0)))
(assert (nat-int? 0))
(assert (nat-int? 1))
(assert (not (nat-int? -1)))
(println "PASS: pos-int? / neg-int? / nat-int?")

;; === bytes? ===
(assert (not (bytes? "hello")))
(println "PASS: bytes?")

;; === indexed? ===
(assert (indexed? [1 2 3]))
(assert (not (indexed? '(1 2 3))))
(println "PASS: indexed?")

;; === seqable? ===
(assert (seqable? [1 2 3]))
(assert (seqable? '(1 2 3)))
(assert (seqable? {:a 1}))
(assert (seqable? "hello"))
(assert (seqable? nil))
(assert (not (seqable? 42)))
(println "PASS: seqable?")

(println "\n=== Phase 19: All tests passed! ===")
