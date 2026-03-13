;; Test require-native builtin

;; Test 1: libc functions
(require-native 'c "libc.so.6"
  strlen  "(STRING):UINT64"
  getpid  "():SINT32"
  getenv  "(STRING):STRING")

(assert (= 5 (c/strlen "hello")) "strlen should return 5")
(assert (> (c/getpid) 0) "getpid should return positive")
(assert (string? (c/getenv "HOME")) "getenv HOME should return string")
(println "PASS: libc strlen/getpid/getenv")

;; Test 2: libm functions
(require-native 'm "libm.so.6"
  sqrt "(DOUBLE):DOUBLE"
  pow  "(DOUBLE, DOUBLE):DOUBLE")

(assert (= 3.0 (m/sqrt 9.0)) "sqrt(9) should be 3.0")
(assert (= 8.0 (m/pow 2.0 3.0)) "pow(2,3) should be 8.0")
(println "PASS: libm sqrt/pow")

(println "ALL TESTS PASSED")
