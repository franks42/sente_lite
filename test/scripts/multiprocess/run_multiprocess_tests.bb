#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[telemere-lite.core :as tel]
         '[babashka.process :as p])

;;
;; Multi-Process Test Runner
;;
;; Runs all multi-process tests in sequence and reports results.
;;

(tel/log! :info "=== Multi-Process Test Suite ===")

(def script-dir (-> *file* babashka.fs/parent str))

(def tests
  [{:id "01"
    :name "Basic Multi-Process"
    :script (str script-dir "/01_basic_multiprocess.bb")
    :description "1 server + 2 clients, basic pub/sub"}
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
    :description "Kill client, server cleanup"}])

(def results (atom []))

(doseq [test tests]
  (tel/log! :info "Running test" {:id (:id test) :name (:name test)})
  (tel/log! :info "Description" {:desc (:description test)})

  (def start-time (System/currentTimeMillis))

  (try
    (def result @(p/process ["bb" (:script test)]
                            {:out :inherit
                             :err :inherit}))

    (def duration-ms (- (System/currentTimeMillis) start-time))
    (def duration-sec (/ duration-ms 1000.0))

    (if (zero? (:exit result))
      (do
        (tel/log! :info "✅ PASSED" {:test (:name test)
                                      :duration-sec duration-sec})
        (swap! results conj {:test test
                             :status :passed
                             :duration-sec duration-sec}))
      (do
        (tel/error! "❌ FAILED" {:test (:name test)
                                  :exit-code (:exit result)
                                  :duration-sec duration-sec})
        (swap! results conj {:test test
                             :status :failed
                             :exit-code (:exit result)
                             :duration-sec duration-sec})))

    (catch Exception e
      (def duration-ms (- (System/currentTimeMillis) start-time))
      (def duration-sec (/ duration-ms 1000.0))
      (tel/error! "❌ ERROR" {:test (:name test)
                               :error (str e)
                               :duration-sec duration-sec})
      (swap! results conj {:test test
                           :status :error
                           :error (str e)
                           :duration-sec duration-sec})))

  (tel/log! :info "---"))

;; Report summary
(tel/log! :info "")
(tel/log! :info "=== Test Suite Summary ===")

(def passed-count (count (filter #(= :passed (:status %)) @results)))
(def failed-count (count (filter #(= :failed (:status %)) @results)))
(def error-count (count (filter #(= :error (:status %)) @results)))
(def total-count (count @results))

(tel/log! :info "Results"
          {:total total-count
           :passed passed-count
           :failed failed-count
           :errors error-count})

(doseq [result @results]
  (def status-str (case (:status result)
                    :passed "✅ PASSED"
                    :failed "❌ FAILED"
                    :error "❌ ERROR"))
  (tel/log! :info status-str
            {:test (get-in result [:test :name])
             :duration-sec (:duration-sec result)}))

(def total-duration (reduce + (map :duration-sec @results)))
(tel/log! :info "Total duration" {:seconds total-duration})

;; Exit with appropriate code
(if (and (zero? failed-count) (zero? error-count))
  (do
    (tel/log! :info "✅ All tests passed!")
    (System/exit 0))
  (do
    (tel/error! "❌ Some tests failed")
    (System/exit 1)))
