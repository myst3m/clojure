;; Phase: clojure.spec.alpha support tests

(def pass-count (atom 0))
(def fail-count (atom 0))

(defn assert-eq [label expected actual]
  (if (= expected actual)
    (do (println "PASS:" label)
        (swap! pass-count inc))
    (do (println "FAIL:" label "- expected:" expected "got:" actual)
        (swap! fail-count inc))))

(defn assert-true [label val]
  (assert-eq label true (boolean val)))

(defn assert-false [label val]
  (assert-eq label false (boolean val)))

;; === Load clojure.spec.alpha ===
(require (quote [clojure.spec.alpha :as s]))
(println "spec.alpha loaded successfully")

;; === Basic spec operations ===
(println "\n--- Basic s/def and s/valid? ---")
(s/def :user/name string?)
(s/def :user/age int?)

(assert-true "valid? string" (s/valid? :user/name "hello"))
(assert-false "valid? int for string spec" (s/valid? :user/name 42))
(assert-true "valid? int" (s/valid? :user/age 25))
(assert-false "valid? string for int spec" (s/valid? :user/age "25"))

;; === s/conform ===
(println "\n--- s/conform ---")
(assert-eq "conform valid string" "hello" (s/conform :user/name "hello"))
(assert-eq "conform invalid" :clojure.spec.alpha/invalid (s/conform :user/name 42))

;; === s/and ===
(println "\n--- s/and ---")
(s/def :user/positive-int (s/and int? pos?))
(assert-true "and valid" (s/valid? :user/positive-int 5))
(assert-false "and invalid (neg)" (s/valid? :user/positive-int -1))
(assert-false "and invalid (string)" (s/valid? :user/positive-int "5"))

;; === s/or ===
(println "\n--- s/or ---")
(s/def :user/name-or-id (s/or :name string? :id int?))
(assert-true "or valid string" (s/valid? :user/name-or-id "foo"))
(assert-true "or valid int" (s/valid? :user/name-or-id 42))
(assert-false "or invalid keyword" (s/valid? :user/name-or-id :bar))
(assert-eq "or conform string" [:name "foo"] (s/conform :user/name-or-id "foo"))
(assert-eq "or conform int" [:id 42] (s/conform :user/name-or-id 42))

;; === spec? and instance? with protocols ===
(println "\n--- spec? ---")
(def my-spec (get (deref clojure.spec.alpha/registry-ref) :user/name))
(assert-true "spec? on registered spec" (s/spec? my-spec))

;; === s/keys ===
(println "\n--- s/keys ---")
(s/def :user/person (s/keys :req-un [:user/name :user/age]))
(assert-true "keys valid person" (s/valid? :user/person {:name "Alice" :age 30}))
(assert-eq "keys conform" {:name "Alice" :age 30} (s/conform :user/person {:name "Alice" :age 30}))
(assert-false "keys missing key" (s/valid? :user/person {:name "Alice"}))

;; === let variable shadowing ===
(println "\n--- let shadowing ---")
(assert-eq "let shadow outer" "outer"
  (let [x "outer"]
    (if false
      (let [x "inner"] x)
      x)))

(assert-eq "let shadow nested" "outer-value"
  (let [ret "outer-value"]
    (if false
      (let [ret "inner-value"] ret)
      ret)))

;; === Results ===
(println "\n=== Spec Tests: " @pass-count "passed," @fail-count "failed ===")
(when (> @fail-count 0)
  (System/exit 1))
