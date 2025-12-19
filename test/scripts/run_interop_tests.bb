#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(require '[babashka.process :as p])

;; Add source paths
(cp/add-classpath "src")
(cp/add-classpath "test")

(require '[clojure.test :as t])

(println "=== Running Sente Interop Tests (Milestone 3B) ===")
(println)

;; Load and run the tests
(require 'sente-lite.sente-interop-test)

(let [test-results (t/run-tests 'sente-lite.sente-interop-test)]
  (println)
  (println "=== Test Summary ===")
  (println "Tests run:" (:test test-results))
  (println "Assertions:" (:pass test-results))
  (println "Failures:" (:fail test-results))
  (println "Errors:" (:error test-results))
  
  (if (and (zero? (:fail test-results))
           (zero? (:error test-results)))
    (do
      (println)
      (println "✅ All Sente interop tests passed!")
      (System/exit 0))
    (do
      (println)
      (println "❌ Some tests failed!")
      (System/exit 1))))
