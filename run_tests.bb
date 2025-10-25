#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test")

(require '[telemere-lite.core-test :as test]
         '[clojure.test :as t])

(println "=== Running Telemere-lite Tests ===")

;; Configure test environment
(require '[telemere-lite.core :as tel])
(tel/configure-timbre!)

(def results (atom {:test 0 :pass 0 :fail 0 :error 0}))

;; Custom test reporter to capture results
(defmethod t/report :begin-test-ns [m]
  (println "\nTesting" (ns-name (:ns m))))

(defmethod t/report :end-test-ns [m]
  (when-let [summary (:clojure.test/summary m)]
    (swap! results
           (fn [r]
             (-> r
                 (update :test + (:test summary))
                 (update :pass + (:pass summary))
                 (update :fail + (:fail summary))
                 (update :error + (:error summary)))))))

(defmethod t/report :pass [m]
  (print "."))

(defmethod t/report :fail [m]
  (print "F")
  (println "\nFAIL in" (t/testing-vars-str m))
  (when (seq t/*testing-contexts*)
    (println (t/testing-contexts-str)))
  (when-let [message (:message m)]
    (println message))
  (println "expected:" (pr-str (:expected m)))
  (println "  actual:" (pr-str (:actual m))))

(defmethod t/report :error [m]
  (print "E")
  (println "\nERROR in" (t/testing-vars-str m))
  (when (seq t/*testing-contexts*)
    (println (t/testing-contexts-str)))
  (when-let [message (:message m)]
    (println message))
  (println "expected:" (pr-str (:expected m)))
  (println "  actual:" (pr-str (:actual m))))

;; Run the tests
(test/run-tests)

(println "\n\n=== Test Summary ===")
(let [r @results]
  (println (format "Ran %d tests containing %d assertions."
                   (:test r) (+ (:pass r) (:fail r) (:error r))))
  (println (format "%d failures, %d errors." (:fail r) (:error r)))

  (if (and (zero? (:fail r)) (zero? (:error r)))
    (do
      (println "✅ ALL TESTS PASSED!")
      (System/exit 0))
    (do
      (println "❌ SOME TESTS FAILED!")
      (System/exit 1))))