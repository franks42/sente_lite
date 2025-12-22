#!/usr/bin/env nbb
;;; Quick test to verify nbb evaluation works
;;; Run: nbb modules/nrepl/test/test_nbb_eval.cljs

(ns test-nbb-eval
  (:require [nbb.core :as nbb]
            [promesa.core :as p]))

(println "=== Testing nbb evaluation ===")

;; Test 1: Check if we can detect nbb runtime
(println "\n1. Runtime detection:")
(println "   js/process exists:" (exists? js/process))
(println "   js/window exists:" (exists? js/window))

;; Test 2: Check nbb.core/load-string
(println "\n2. nbb.core namespace:")
(println "   load-string available:" (some? nbb/load-string))

;; Test 3: Use load-string for simple eval
(println "\n3. Evaluating (+ 1 2 3):")
(p/let [result (nbb/load-string "(+ 1 2 3)")]
  (println "   Result:" result)

  ;; Test 4: Test state persistence
  (println "\n4. Testing state persistence:")
  (p/let [_ (nbb/load-string "(def test-var 42)")
          result (nbb/load-string "(* test-var 2)")]
    (println "   (def test-var 42), (* test-var 2) =" result)

    ;; Test 5: Test namespace change
    (println "\n5. Testing namespace:")
    (p/let [ns-result (nbb/load-string "(str *ns*)")]
      (println "   Current namespace:" ns-result)

      ;; Test 6: Multi-expression
      (println "\n6. Multi-expression eval:")
      (p/let [result (nbb/load-string "(do (def y 10) (+ test-var y))")]
        (println "   (do (def y 10) (+ test-var y)) =" result)

        (println "\n=== All tests PASSED ===")
        (js/process.exit 0)))))
