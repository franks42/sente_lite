#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[babashka.process :as p])

;;
;; Multi-Process Test Runner
;;
;; Runs all multi-process tests in sequence and reports results.
;;

(println "[info] " "=== Multi-Process Test Suite ===")

(def script-dir (-> *file* babashka.fs/parent str))

(def tests
  [{:id "01"
    :name "Basic Multi-Process"
    :script (str script-dir "/01_basic_multiprocess.bb")
    :description "1 server + 2 clients, basic pub/sub"}
   {:id "02"
    :name "Ephemeral Port Reconnection"
    :script (str script-dir "/02_ephemeral_reconnection.bb")
    :description "Server restarts with different ephemeral port"}
   {:id "03"
    :name "Reconnection"
    :script (str script-dir "/03_reconnection.bb")
    :description "Server restart, clients auto-reconnect"}
   {:id "04"
    :name "Concurrent Startup"
    :script (str script-dir "/04_concurrent_startup.bb")
    :description "10 clients start simultaneously"}
   {:id "05"
    :name "Process Failure"
    :script (str script-dir "/05_process_failure.bb")
    :description "Kill client, server cleanup"}
   {:id "06"
    :name "Stress Test"
    :script (str script-dir "/06_stress_test.bb")
    :description "20 clients, high message throughput"}])

(def results (atom []))

(doseq [test tests]
  (println "[info] " "Running test" {:id (:id test) :name (:name test)})
  (println "[info] " "Description" {:desc (:description test)})

  (def start-time (System/currentTimeMillis))

  (try
    (def result @(p/process ["bb" (:script test)]
                            {:out :inherit
                             :err :inherit}))

    (def duration-ms (- (System/currentTimeMillis) start-time))
    (def duration-sec (/ duration-ms 1000.0))

    (if (zero? (:exit result))
      (do
        (println "[info] " "✅ PASSED" {:test (:name test)
                                      :duration-sec duration-sec})
        (swap! results conj {:test test
                             :status :passed
                             :duration-sec duration-sec}))
      (do
        (println "ERROR:" "❌ FAILED" {:test (:name test)
                                  :exit-code (:exit result)
                                  :duration-sec duration-sec})
        (swap! results conj {:test test
                             :status :failed
                             :exit-code (:exit result)
                             :duration-sec duration-sec})))

    (catch Exception e
      (def duration-ms (- (System/currentTimeMillis) start-time))
      (def duration-sec (/ duration-ms 1000.0))
      (println "ERROR:" "❌ ERROR" {:test (:name test)
                               :error (str e)
                               :duration-sec duration-sec})
      (swap! results conj {:test test
                           :status :error
                           :error (str e)
                           :duration-sec duration-sec})))

  (println "[info] " "---"))

;; Report summary
(println "[info] " "")
(println "[info] " "=== Test Suite Summary ===")

(def passed-count (count (filter #(= :passed (:status %)) @results)))
(def failed-count (count (filter #(= :failed (:status %)) @results)))
(def error-count (count (filter #(= :error (:status %)) @results)))
(def total-count (count @results))

(println "[info] " "Results"
          {:total total-count
           :passed passed-count
           :failed failed-count
           :errors error-count})

(doseq [result @results]
  (def status-str (case (:status result)
                    :passed "✅ PASSED"
                    :failed "❌ FAILED"
                    :error "❌ ERROR"))
  (println "[info] " status-str
            {:test (get-in result [:test :name])
             :duration-sec (:duration-sec result)}))

(def total-duration (reduce + (map :duration-sec @results)))
(println "[info] " "Total duration" {:seconds total-duration})

;; Exit with appropriate code
(if (and (zero? failed-count) (zero? error-count))
  (do
    (println "[info] " "✅ All tests passed!")
    (System/exit 0))
  (do
    (println "ERROR:" "❌ Some tests failed")
    (System/exit 1)))
