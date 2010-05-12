;;; test_pretty.clj -- part of the pretty printer for Clojure

;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

;; Author: Tom Faulhaber
;; April 3, 2009


(in-ns 'clojure.test-clojure.pprint)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;; Unit tests for the pretty printer
;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(simple-tests xp-fill-test
  (binding [*print-pprint-dispatch* simple-dispatch
            *print-right-margin* 38
            *print-miser-width* nil]
    (cl-format nil "(let ~:<~@{~:<~w ~_~w~:>~^ ~:_~}~:>~_ ...)~%"
               '((x 4) (*print-length* nil) (z 2) (list nil))))
  "(let ((x 4) (*print-length* nil)\n      (z 2) (list nil))\n ...)\n"

  (binding [*print-pprint-dispatch* simple-dispatch
            *print-right-margin* 22]
    (cl-format nil "(let ~:<~@{~:<~w ~_~w~:>~^ ~:_~}~:>~_ ...)~%"
               '((x 4) (*print-length* nil) (z 2) (list nil))))
  "(let ((x 4)\n      (*print-length*\n       nil)\n      (z 2)\n      (list nil))\n ...)\n")

(simple-tests xp-miser-test
  (binding [*print-pprint-dispatch* simple-dispatch
            *print-right-margin* 10, *print-miser-width* 9]
    (cl-format nil "~:<LIST ~@_~W ~@_~W ~@_~W~:>" '(first second third)))
  "(LIST\n first\n second\n third)"

  (binding [*print-pprint-dispatch* simple-dispatch
            *print-right-margin* 10, *print-miser-width* 8]
    (cl-format nil "~:<LIST ~@_~W ~@_~W ~@_~W~:>" '(first second third)))
  "(LIST first second third)")

(simple-tests mandatory-fill-test
  (cl-format nil
             "<pre>~%~<Usage: ~:I~@{*~a*~^~:@_~}~:>~%</pre>~%"
             [ "hello" "gooodbye" ])
  "<pre>
Usage: *hello*
       *gooodbye*
</pre>
")

(simple-tests prefix-suffix-test
  (binding [*print-pprint-dispatch* simple-dispatch
            *print-right-margin* 10, *print-miser-width* 10]
    (cl-format nil "~<{~;LIST ~@_~W ~@_~W ~@_~W~;}~:>" '(first second third)))
  "{LIST\n first\n second\n third}")

(simple-tests pprint-test
  (binding [*print-pprint-dispatch* simple-dispatch]
    (write '(defn foo [x y] 
              (let [result (* x y)] 
                (if (> result 400) 
                  (cl-format true "That number is too big")
                  (cl-format true "The  result of ~d x ~d is ~d" x y result))))
           :stream nil))
  "(defn
 foo
 [x y]
 (let
  [result (* x y)]
  (if
   (> result 400)
   (cl-format true \"That number is too big\")
   (cl-format true \"The  result of ~d x ~d is ~d\" x y result))))"

  (with-pprint-dispatch code-dispatch
    (write '(defn foo [x y] 
              (let [result (* x y)] 
                (if (> result 400) 
                  (cl-format true "That number is too big")
                  (cl-format true "The  result of ~d x ~d is ~d" x y result))))
           :stream nil))
  "(defn foo [x y]
  (let [result (* x y)]
    (if (> result 400)
      (cl-format true \"That number is too big\")
      (cl-format true \"The  result of ~d x ~d is ~d\" x y result))))"

  (binding [*print-pprint-dispatch* simple-dispatch
            *print-right-margin* 15] 
    (write '(fn (cons (car x) (cdr y))) :stream nil))
  "(fn\n (cons\n  (car x)\n  (cdr y)))"

  (with-pprint-dispatch code-dispatch
    (binding [*print-right-margin* 52] 
      (write 
       '(add-to-buffer this (make-buffer-blob (str (char c)) nil))
       :stream nil)))
  "(add-to-buffer\n  this\n  (make-buffer-blob (str (char c)) nil))"
  )



(simple-tests pprint-reader-macro-test
  (with-pprint-dispatch code-dispatch
    (write (read-string "(map #(first %) [[1 2 3] [4 5 6] [7]])")
	   :stream nil))
  "(map #(first %) [[1 2 3] [4 5 6] [7]])"

  (with-pprint-dispatch code-dispatch
    (write (read-string "@@(ref (ref 1))")
	   :stream nil))
  "@@(ref (ref 1))"

  (with-pprint-dispatch code-dispatch
    (write (read-string "'foo")
	   :stream nil))
  "'foo"
)

(simple-tests code-block-tests 
 (with-out-str
   (with-pprint-dispatch code-dispatch 
     (pprint 
      '(defn cl-format 
         "An implementation of a Common Lisp compatible format function"
         [stream format-in & args]
         (let [compiled-format (if (string? format-in) (compile-format format-in) format-in)
               navigator (init-navigator args)]
           (execute-format stream compiled-format navigator))))))
 "(defn cl-format
  \"An implementation of a Common Lisp compatible format function\"
  [stream format-in & args]
  (let [compiled-format (if (string? format-in)
                          (compile-format format-in)
                          format-in)
        navigator (init-navigator args)]
    (execute-format stream compiled-format navigator)))
"

 (with-out-str
   (with-pprint-dispatch code-dispatch 
     (pprint 
      '(defn pprint-defn [writer alis]
         (if (next alis) 
           (let [[defn-sym defn-name & stuff] alis
                 [doc-str stuff] (if (string? (first stuff))
                                   [(first stuff) (next stuff)]
                                   [nil stuff])
                 [attr-map stuff] (if (map? (first stuff))
                                    [(first stuff) (next stuff)]
                                    [nil stuff])]
             (pprint-logical-block writer :prefix "(" :suffix ")"
                                   (cl-format true "~w ~1I~@_~w" defn-sym defn-name)
                                   (if doc-str
                                     (cl-format true " ~_~w" doc-str))
                                   (if attr-map
                                     (cl-format true " ~_~w" attr-map))
                                   ;; Note: the multi-defn case will work OK for malformed defns too
                                   (cond
                                    (vector? (first stuff)) (single-defn stuff (or doc-str attr-map))
                                    :else (multi-defn stuff (or doc-str attr-map)))))
           (pprint-simple-code-list writer alis))))))
 "(defn pprint-defn [writer alis]
  (if (next alis)
    (let [[defn-sym defn-name & stuff] alis
          [doc-str stuff] (if (string? (first stuff))
                            [(first stuff) (next stuff)]
                            [nil stuff])
          [attr-map stuff] (if (map? (first stuff))
                             [(first stuff) (next stuff)]
                             [nil stuff])]
      (pprint-logical-block
        writer
        :prefix
        \"(\"
        :suffix
        \")\"
        (cl-format true \"~w ~1I~@_~w\" defn-sym defn-name)
        (if doc-str (cl-format true \" ~_~w\" doc-str))
        (if attr-map (cl-format true \" ~_~w\" attr-map))
        (cond
          (vector? (first stuff)) (single-defn
                                    stuff
                                    (or doc-str attr-map))
          :else (multi-defn stuff (or doc-str attr-map)))))
    (pprint-simple-code-list writer alis)))
")


(defn tst-pprint
  "A helper function to pprint to a string with a restricted right margin"
  [right-margin obj]
  (binding [*print-right-margin* right-margin
            *print-pretty* true]
    (write obj :stream nil)))

;;; A bunch of predefined data to print
(def future-filled (future-call (fn [] 100)))
@future-filled
(def future-unfilled (future-call (fn [] (.acquire (java.util.concurrent.Semaphore. 0)))))
(def promise-filled (promise))
(deliver promise-filled '(first second third))
(def promise-unfilled (promise))
(def basic-agent (agent '(first second third)))
(def basic-atom (atom '(first second third)))
(def basic-ref (ref '(first second third)))
(def delay-forced (delay '(first second third)))
(force delay-forced)
(def delay-unforced (delay '(first second third)))
(defrecord pprint-test-rec [a b c])


(simple-tests pprint-datastructures-tests
 (tst-pprint 20 future-filled) #"#<Future@[0-9a-f]+: \n  100>"
 (tst-pprint 20 future-unfilled) #"#<Future@[0-9a-f]+: \n  :pending>"
 (tst-pprint 20 promise-filled) #"#<Promise@[0-9a-f]+: \n  \(first\n   second\n   third\)>"
 ;; This hangs currently, cause we can't figure out whether a promise is filled
 ;;(tst-pprint 20 promise-unfilled) #"#<Promise@[0-9a-f]+: \n  :pending>"
 (tst-pprint 20 basic-agent) #"#<Agent@[0-9a-f]+: \n  \(first\n   second\n   third\)>"
 (tst-pprint 20 basic-atom) #"#<Atom@[0-9a-f]+: \n  \(first\n   second\n   third\)>"
 (tst-pprint 20 basic-ref) #"#<Ref@[0-9a-f]+: \n  \(first\n   second\n   third\)>"
 (tst-pprint 20 delay-forced) #"#<Delay@[0-9a-f]+: \n  \(first\n   second\n   third\)>"
 ;; Currently no way not to force the delay
 ;;(tst-pprint 20 delay-unforced) #"#<Delay@[0-9a-f]+: \n  :pending>"
 (tst-pprint 20 (pprint-test-rec. 'first 'second 'third)) "{:a first,\n :b second,\n :c third}"

 ;; basic java arrays: fails owing to assembla ticket #346
 ;;(tst-pprint 10 (int-array (range 7))) "[0,\n 1,\n 2,\n 3,\n 4,\n 5,\n 6]"
 (tst-pprint 15 (reduce conj clojure.lang.PersistentQueue/EMPTY (range 10)))
 "<-(0\n   1\n   2\n   3\n   4\n   5\n   6\n   7\n   8\n   9)-<"
 )




