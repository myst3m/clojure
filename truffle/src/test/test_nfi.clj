;;; NFI (Native Function Interface) tests
;;; Run: tclj test_nfi.clj

(println "=== NFI Tests ===")

;; Load libc
(def libc (native-load "libc.so.6"))
(assert (some? libc) "libc loaded")
(println "  libc loaded")

;; getpid
(def getpid (native-fn libc "getpid" "():SINT32"))
(let [pid (getpid)]
  (assert (> pid 0) "getpid returns positive")
  (println "  getpid:" pid))

;; strlen
(def my-strlen (native-fn libc "strlen" "(STRING):UINT64"))
(assert (= 5 (my-strlen "hello")) "strlen hello = 5")
(assert (= 0 (my-strlen "")) "strlen empty = 0")
(assert (= 13 (my-strlen "clojure rocks")) "strlen clojure rocks = 13")
(println "  strlen: OK")

;; getenv
(def my-getenv (native-fn libc "getenv" "(STRING):STRING"))
(assert (string? (my-getenv "HOME")) "getenv HOME is string")
(assert (string? (my-getenv "USER")) "getenv USER is string")
(println "  getenv HOME:" (my-getenv "HOME"))
(println "  getenv USER:" (my-getenv "USER"))

;; libm sqrt
(def libm (native-load "libm.so.6"))
(def my-sqrt (native-fn libm "sqrt" "(DOUBLE):DOUBLE"))
(assert (= 12.0 (my-sqrt 144.0)) "sqrt 144 = 12")
(assert (= 3.0 (my-sqrt 9.0)) "sqrt 9 = 3")
(println "  sqrt: OK")

;; libm pow
(def my-pow (native-fn libm "pow" "(DOUBLE, DOUBLE):DOUBLE"))
(assert (= 8.0 (my-pow 2.0 3.0)) "pow 2 3 = 8")
(assert (= 1000.0 (my-pow 10.0 3.0)) "pow 10 3 = 1000")
(println "  pow: OK")

;; libm floor/ceil
(def my-floor (native-fn libm "floor" "(DOUBLE):DOUBLE"))
(def my-ceil (native-fn libm "ceil" "(DOUBLE):DOUBLE"))
(assert (= 3.0 (my-floor 3.7)) "floor 3.7 = 3")
(assert (= 4.0 (my-ceil 3.2)) "ceil 3.2 = 4")
(println "  floor/ceil: OK")

;; time
(def my-time (native-fn libc "time" "(POINTER):SINT64"))
(let [t (my-time nil)]
  (assert (> t 1700000000) "time returns reasonable epoch")
  (println "  time:" t))

;; native-default (process symbol table)
(def defaults (native-default))
(def puts-fn (native-fn defaults "puts" "(STRING):SINT32"))
(puts-fn "  puts via native-default: OK")

;; strerror
(def my-strerror (native-fn libc "strerror" "(SINT32):STRING"))
(println "  strerror(2):" (my-strerror 2))  ; ENOENT
(println "  strerror(13):" (my-strerror 13)) ; EACCES

;; abs
(def my-abs (native-fn libc "abs" "(SINT32):SINT32"))
(assert (= 42 (my-abs -42)) "abs -42 = 42")
(assert (= 0 (my-abs 0)) "abs 0 = 0")
(println "  abs: OK")

(println "=== All NFI tests passed ===")
