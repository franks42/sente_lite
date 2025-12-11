#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test")

(require '[clojure.test :as t]
         '[babashka.process :as p]
         '[taoensso.trove-tests])

(println "=== sente-lite Test Suite ===\n")

;;
;; Part 1: Unit Tests (telemere-lite)
;;

(println "=== Part 1: Unit Tests (Trove) ===")

;; Run unit tests and capture results
(def unit-results (t/run-tests 'taoensso.trove-tests))

(println "\n\n=== Unit Tests Summary ===")
(let [r unit-results]
  (println (format "Ran %d tests containing %d assertions."
                   (:test r) (+ (:pass r) (:fail r) (:error r))))
  (println (format "%d failures, %d errors." (:fail r) (:error r)))
  (if (and (zero? (:fail r)) (zero? (:error r)))
    (println "✅ Unit tests passed!")
    (println "❌ Unit tests failed!")))

;;
;; Part 2: Multi-Process Integration Tests
;;

(println "\n\n=== Part 2: Multi-Process Integration Tests ===")

(def mp-test-script "test/scripts/multiprocess/run_multiprocess_tests.bb")
(def mp-passed? (atom false))

(try
  (def mp-result @(p/process ["bb" mp-test-script]
                             {:out :inherit
                              :err :inherit}))
  (reset! mp-passed? (zero? (:exit mp-result)))
  (catch Exception e
    (println "❌ Multi-process tests error:" (str e))
    (reset! mp-passed? false)))

;;
;; Final Summary
;;

(println "\n\n=== Final Test Summary ===")
(let [ur unit-results
      unit-passed? (and (zero? (:fail ur)) (zero? (:error ur)))
      all-passed? (and unit-passed? @mp-passed?)]

  (println "\nUnit Tests:")
  (println (format "  Tests: %d | Assertions: %d | Failures: %d | Errors: %d"
                   (:test ur)
                   (+ (:pass ur) (:fail ur) (:error ur))
                   (:fail ur)
                   (:error ur)))
  (println (if unit-passed? "  ✅ PASSED" "  ❌ FAILED"))

  (println "\nMulti-Process Integration Tests:")
  (println (if @mp-passed? "  ✅ PASSED" "  ❌ FAILED"))

  (println "\n" (if all-passed?
                  "✅ ALL TESTS PASSED!"
                  "❌ SOME TESTS FAILED!"))

  (System/exit (if all-passed? 0 1)))