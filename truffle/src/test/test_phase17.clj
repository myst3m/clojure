; Phase 17: Namespace System Tests

;; === ns-publics / ns-interns ===
(def test-ns-val 42)
(let [publics (ns-publics 'user)]
  (assert (map? publics))
  (assert (contains? publics 'test-ns-val)))
(println "PASS: ns-publics")

;; === ns-name ===
(assert (= 'user (ns-name (the-ns 'user))))
(println "PASS: ns-name")

;; === the-ns ===
(assert (= "#namespace[user]" (str (the-ns 'user))))
(println "PASS: the-ns")

;; === create-ns / find-ns / remove-ns ===
(create-ns 'temp.test.ns)
(assert (find-ns 'temp.test.ns))
(remove-ns 'temp.test.ns)
(assert (nil? (find-ns 'temp.test.ns)))
(println "PASS: create-ns / find-ns / remove-ns")

;; === all-ns ===
(let [all (all-ns)]
  (assert (seq all))
  (assert (some #(= "user" (.getName %)) all)))
(println "PASS: all-ns")

;; === ns-aliases ===
(require '[clojure.string :as str])
(let [aliases (ns-aliases 'user)]
  (assert (map? aliases))
  (assert (contains? aliases 'str)))
(println "PASS: ns-aliases")

;; === ns-unalias ===
(ns-unalias 'user 'str)
(assert (not (contains? (ns-aliases 'user) 'str)))
;; Re-add it for subsequent tests
(require '[clojure.string :as str])
(println "PASS: ns-unalias")

;; === intern ===
(intern 'user 'interned-val 99)
(assert (= 99 interned-val))
(println "PASS: intern")

;; === namespace function ===
(assert (= "clojure.core" (namespace :clojure.core/foo)))
(assert (nil? (namespace :foo)))
(assert (= "my.ns" (namespace 'my.ns/bar)))
(println "PASS: namespace function")

;; === ns with :require :as ===
(ns test.require.ns
  (:require [clojure.string :as s]))
(assert (= "HELLO" (s/upper-case "hello")))
(in-ns 'user)
(println "PASS: ns :require :as")

;; === ns with :require :refer ===
(ns test.refer.ns
  (:require [clojure.string :refer [upper-case lower-case]]))
(assert (= "HELLO" (upper-case "hello")))
(assert (= "hello" (lower-case "HELLO")))
(in-ns 'user)
(println "PASS: ns :require :refer")

;; === ns with :import ===
(ns test.import.ns
  (:import [java.util ArrayList]))
(assert (instance? ArrayList (ArrayList.)))
(in-ns 'user)
(println "PASS: ns :import")

;; === use with :only ===
(ns test.use.ns)
(def use-test-val 123)
(def use-test-other 456)
(in-ns 'user)
(use '[test.use.ns :only [use-test-val]])
(assert (= 123 use-test-val))
(println "PASS: use :only")

;; === refer ===
(ns test.refer.source)
(def refer-val-a 10)
(def refer-val-b 20)
(in-ns 'user)
(refer 'test.refer.source)
(assert (= 10 refer-val-a))
(assert (= 20 refer-val-b))
(println "PASS: refer")

;; === ns-map ===
(let [m (ns-map 'user)]
  (assert (map? m))
  (assert (contains? m '+)))
(println "PASS: ns-map")

;; === require with :rename ===
(ns test.rename.ns
  (:require [clojure.string :refer [upper-case] :rename {upper-case upcase}]))
(assert (= "HELLO" (upcase "hello")))
(in-ns 'user)
(println "PASS: require :rename")

;; === ns with :refer-clojure :exclude ===
(ns test.exclude.ns
  (:refer-clojure :exclude [get]))
(def get "my-get")
(assert (= "my-get" get))
(in-ns 'user)
(println "PASS: ns :refer-clojure :exclude")

(println "\n=== Phase 17: All tests passed! ===")
