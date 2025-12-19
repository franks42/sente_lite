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
;; Part 2: Queue Tests
;;

(println "\n\n=== Part 2: Queue Tests ===")

(def queue-bb-passed? (atom false))
(def queue-nbb-passed? (atom true))  ; Default true if skipped
(def queue-integration-passed? (atom false))

;; BB Queue Unit Tests
(println "Running BB queue unit tests...")
(try
  (def queue-bb-result @(p/process ["bb" "test/scripts/queue/test_queue_bb.bb"]
                                   {:out :inherit
                                    :err :inherit}))
  (reset! queue-bb-passed? (zero? (:exit queue-bb-result)))
  (catch Exception e
    (println "❌ BB queue tests error:" (str e))
    (reset! queue-bb-passed? false)))

;; nbb Queue Unit Tests (if nbb available)
(when (.exists (java.io.File. "test/nbb/node_modules/ws"))
  (println "\nRunning nbb queue unit tests...")
  (try
    (def queue-nbb-result @(p/process ["nbb" "--classpath" "src" "test/scripts/queue/test_queue_nbb.cljs"]
                                      {:out :inherit
                                       :err :inherit}))
    (reset! queue-nbb-passed? (zero? (:exit queue-nbb-result)))
    (catch Exception e
      (println "❌ nbb queue tests error:" (str e))
      (reset! queue-nbb-passed? false))))

;; BB-to-BB Queue Integration Test
(println "\nRunning BB-to-BB queue integration test...")
(try
  (def queue-int-result @(p/process ["bb" "test/scripts/queue/test_queue_integration_bb.bb"]
                                    {:out :inherit
                                     :err :inherit}))
  (reset! queue-integration-passed? (zero? (:exit queue-int-result)))
  (catch Exception e
    (println "❌ Queue integration tests error:" (str e))
    (reset! queue-integration-passed? false)))

;;
;; Part 3: Multi-Process Integration Tests
;;

(println "\n\n=== Part 3: Multi-Process Integration Tests ===")

(def mp-test-script "test/scripts/multiprocess/01_basic.bb")
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
;; Part 4: nbb Platform Tests (if nbb is available)
;;

(println "\n\n=== Part 4: nbb Platform Tests ===")

(def nbb-passed? (atom true))  ; Default to true if skipped

(if (.exists (java.io.File. "test/nbb/node_modules/ws"))
  (do
    (println "nbb environment detected, running nbb tests...")
    (try
      (def nbb-result @(p/process ["nbb" "--classpath" "../../src" "test_server_nbb_module.cljs"]
                                  {:dir "test/nbb"
                                   :out :inherit
                                   :err :inherit}))
      (reset! nbb-passed? (zero? (:exit nbb-result)))
      (catch Exception e
        (println "❌ nbb tests error:" (str e))
        (reset! nbb-passed? false))))
  (println "Skipping nbb tests (run 'cd test/nbb && npm install' to enable)"))

;;
;; Final Summary
;;

(println "\n\n=== Final Test Summary ===")
(let [ur unit-results
      unit-passed? (and (zero? (:fail ur)) (zero? (:error ur)))
      queue-passed? (and @queue-bb-passed? @queue-nbb-passed? @queue-integration-passed?)
      all-passed? (and unit-passed? queue-passed? @mp-passed? @nbb-passed?)]

  (println "\nUnit Tests (Trove):")
  (println (format "  Tests: %d | Assertions: %d | Failures: %d | Errors: %d"
                   (:test ur)
                   (+ (:pass ur) (:fail ur) (:error ur))
                   (:fail ur)
                   (:error ur)))
  (println (if unit-passed? "  ✅ PASSED" "  ❌ FAILED"))

  (println "\nQueue Tests:")
  (println (str "  BB Unit: " (if @queue-bb-passed? "✅" "❌")))
  (if (.exists (java.io.File. "test/nbb/node_modules/ws"))
    (println (str "  nbb Unit: " (if @queue-nbb-passed? "✅" "❌")))
    (println "  nbb Unit: ⏭ SKIPPED"))
  (println (str "  BB Integration: " (if @queue-integration-passed? "✅" "❌")))
  (println (if queue-passed? "  ✅ PASSED" "  ❌ FAILED"))

  (println "\nMulti-Process Integration Tests:")
  (println (if @mp-passed? "  ✅ PASSED" "  ❌ FAILED"))

  (println "\nnbb Platform Tests:")
  (if (.exists (java.io.File. "test/nbb/node_modules/ws"))
    (println (if @nbb-passed? "  ✅ PASSED" "  ❌ FAILED"))
    (println "  ⏭ SKIPPED (nbb not configured)"))

  (println "\n" (if all-passed?
                  "✅ ALL TESTS PASSED!"
                  "❌ SOME TESTS FAILED!"))

  (System/exit (if all-passed? 0 1)))