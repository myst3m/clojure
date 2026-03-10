;; Phase 8 Tests

(println "=== Regex ===")
(println "(re-find #\"\\d+\" \"abc123def\"):" (re-find #"\d+" "abc123def"))    ; "123"
(println "(re-matches #\"\\d+\" \"123\"):" (re-matches #"\d+" "123"))           ; "123"
(println "(re-matches #\"\\d+\" \"abc\"):" (re-matches #"\d+" "abc"))           ; nil
(println "(re-seq #\"\\d+\" \"a1b2c3\"):" (re-seq #"\d+" "a1b2c3"))            ; ("1" "2" "3")
(println "(re-find #\"(\\w+)@(\\w+)\" \"user@host\"):" (re-find #"(\w+)@(\w+)" "user@host"))

(println "\n=== Seq Operations ===")
(println "(group-by odd? [1 2 3 4 5]):" (group-by odd? [1 2 3 4 5]))
(println "(frequencies [:a :b :a :c :b :a]):" (frequencies [:a :b :a :c :b :a]))
(println "(take-while pos? [3 2 1 0 -1]):" (take-while pos? [3 2 1 0 -1]))
(println "(drop-while pos? [3 2 1 0 -1]):" (drop-while pos? [3 2 1 0 -1]))
(println "(every? even? [2 4 6]):" (every? even? [2 4 6]))       ; true
(println "(every? even? [2 3 6]):" (every? even? [2 3 6]))       ; false
(println "(some even? [1 2 3]):" (some even? [1 2 3]))           ; true
(println "(some even? [1 3 5]):" (some even? [1 3 5]))           ; nil
(println "(not-any? neg? [1 2 3]):" (not-any? neg? [1 2 3]))     ; true
(println "(not-every? odd? [1 2 3]):" (not-every? odd? [1 2 3])) ; true

;; into
(println "(into [] '(1 2 3)):" (into [] '(1 2 3)))               ; [1 2 3]
(println "(into {} [[:a 1] [:b 2]]):" (into {} [[:a 1] [:b 2]])) ; {:a 1 :b 2}
(println "(into #{} [1 2 2 3]):" (into #{} [1 2 2 3]))

;; reduce-kv
(println "(reduce-kv #(assoc %1 %3 %2) {} {:a 1 :b 2}):" (reduce-kv (fn [m k v] (assoc m v k)) {} {:a 1 :b 2}))

;; split-at, split-with
(println "(split-at 3 [1 2 3 4 5]):" (split-at 3 [1 2 3 4 5]))
(println "(split-with pos? [1 2 -1 3]):" (split-with pos? [1 2 -1 3]))

;; partition-by
(println "(partition-by odd? [1 1 2 2 3]):" (partition-by odd? [1 1 2 2 3]))

;; take-last, drop-last
(println "(take-last 2 [1 2 3 4 5]):" (take-last 2 [1 2 3 4 5]))
(println "(drop-last [1 2 3 4 5]):" (drop-last [1 2 3 4 5]))

;; mapv, filterv
(println "(mapv inc [1 2 3]):" (mapv inc [1 2 3]))
(println "(filterv even? [1 2 3 4 5]):" (filterv even? [1 2 3 4 5]))

(println "\n=== Metadata ===")
(def v (with-meta [1 2 3] {:doc "test vector"}))
(println "(meta v):" (meta v))
(println "(meta (vary-meta v assoc :version 2)):" (meta (vary-meta v assoc :version 2)))

(println "\n=== ex-info / ex-data ===")
(try
  (throw (ex-info "test error" {:code 404}))
  (catch Exception e
    (println "ex-message:" (ex-message e))
    (println "ex-data:" (ex-data e))))

(println "\n=== String Operations ===")
(println "(str/split \"a,b,c\" #\",\"):" (str/split "a,b,c" #","))
(println "(str/join \"-\" [1 2 3]):" (str/join "-" [1 2 3]))
(println "(str/trim \"  hi  \"):" (str/trim "  hi  "))
(println "(str/upper-case \"hello\"):" (str/upper-case "hello"))
(println "(str/lower-case \"HELLO\"):" (str/lower-case "HELLO"))
(println "(str/replace \"hello world\" \"world\" \"clojure\"):" (str/replace "hello world" "world" "clojure"))
(println "(str/starts-with? \"hello\" \"hel\"):" (str/starts-with? "hello" "hel"))
(println "(str/ends-with? \"hello\" \"llo\"):" (str/ends-with? "hello" "llo"))
(println "(str/includes? \"hello\" \"ell\"):" (str/includes? "hello" "ell"))
(println "(str/blank? \"  \"):" (str/blank? "  "))
(println "(str/reverse \"hello\"):" (str/reverse "hello"))
(println "(subs \"hello\" 1 3):" (subs "hello" 1 3))

(println "\n=== Sets ===")
(println "(set [1 2 2 3 3 3]):" (set [1 2 2 3 3 3]))
(println "(set? #{1 2}):" (set? #{1 2}))
(println "(contains? #{1 2 3} 2):" (contains? #{1 2 3} 2))
(println "(contains? {:a 1} :a):" (contains? {:a 1} :a))
(println "(disj #{1 2 3} 2):" (disj #{1 2 3} 2))

(println "\n=== Misc ===")
(println "(name :foo):" (name :foo))
(println "(name 'bar):" (name 'bar))
(println "(keyword \"test\"):" (keyword "test"))
(println "(symbol \"x\"):" (symbol "x"))
(println "(compare 1 2):" (compare 1 2))
(println "(compare 2 1):" (compare 2 1))
(println "(type 42):" (type 42))
(println "(instance? String \"hi\"):" (instance? String "hi"))
(println "(instance? java.lang.Long 42):" (instance? java.lang.Long 42))
(println "(min-key count \"ab\" \"a\" \"abc\"):" (min-key count "ab" "a" "abc"))
(println "(max-key count \"ab\" \"a\" \"abc\"):" (max-key count "ab" "a" "abc"))
(println "(repeatedly 3 #(rand-int 100)) is a list of length:" (count (repeatedly 3 (fn [] (rand-int 100)))))

;; Dynamic binding
(def ^:dynamic *x* 10)
(println "\n=== Dynamic Binding ===")
(println "*x* default:" *x*)
(binding [*x* 42]
  (println "*x* inside binding:" *x*))
(println "*x* after binding:" *x*)

(println "\n=== Phase 8 Complete ===")
