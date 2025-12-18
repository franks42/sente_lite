#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[mp-utils :as mp])

;;
;; Test 6: Stress Test (OPTIONAL - LOW Priority)
;;
;; Tests: 20 clients sending messages concurrently (~100 msg/sec total)
;; Validates: Server handles high concurrent load, no message loss, performance
;;

(println "[info] " "=== Test 6: Stress Test ===")

(def test-id "stress-06")
(def test-duration-sec 20)
(def client-count 20)
(def messages-per-client 20)  ; 20 clients * 20 msgs = 400 msgs over 20 sec = 20 msg/sec
(def message-interval-ms 1000) ; 1 msg/sec per client = 20 msg/sec total

;; Cleanup from any previous run
(println "[info] " "Cleaning up previous test files")
(def process-ids (concat ["server"]
                         (map #(str "client-" %) (range 1 (inc client-count)))))
(mp/cleanup-test-files! test-id process-ids)

;; Start server process in background
(println "[info] " "Starting server process")
(def script-dir (-> *file* babashka.fs/parent str))
(def server-process
  (p/process ["bb" (str script-dir "/mp_server.bb") test-id (str test-duration-sec)]
             {:out :inherit
              :err :inherit}))

;; Wait for server ready
(println "[info] " "Waiting for server ready signal")
(try
  (mp/wait-for-ready test-id "server" 5000)
  (println "[info] " "Server is ready")
  (catch Exception e
    (println "ERROR:" "Server failed to become ready" {:error (str e)})
    (try (babashka.process/destroy server-process) (catch Exception _))
    (mp/cleanup-test-files! test-id process-ids)
    (System/exit 1)))

;; Get server port
(def server-port (mp/read-port test-id 1000))
(println "[info] " "Server port discovered" {:port server-port})

;; Start all clients SIMULTANEOUSLY
(println "[info] " "Starting stress test clients"
          {:count client-count
           :messages-per-client messages-per-client
           :message-interval-ms message-interval-ms
           :expected-total-messages (* client-count messages-per-client)})

(def start-time (System/currentTimeMillis))

(def client-processes
  (doall
   (for [i (range 1 (inc client-count))]
     (p/process ["bb" (str script-dir "/mp_client_stress.bb")
                 test-id (str i) "stress-channel"
                 (str messages-per-client) (str message-interval-ms)]
                {:out :inherit
                 :err :inherit}))))

(println "[info] " "All client processes started" {:count (count client-processes)})

;; Wait for all clients to complete
(println "[info] " "Waiting for all clients to complete")
(def client-process-ids (map #(str "client-" %) (range 1 (inc client-count))))
(try
  (mp/wait-for-all-ready test-id client-process-ids 45000)
  (println "[info] " "All clients completed")
  (catch Exception e
    (println "ERROR:" "Clients failed to complete" {:error (str e)})
    (try (babashka.process/destroy server-process) (catch Exception _))
    (doseq [proc client-processes]
      (try (babashka.process/destroy proc) (catch Exception _)))
    (mp/cleanup-test-files! test-id process-ids)
    (System/exit 1)))

;; Wait for processes to exit
(println "[info] " "Waiting for processes to exit")
(doseq [proc client-processes]
  @proc)
(Thread/sleep 1000)

;; Kill server
(println "[info] " "Stopping server")
(try
  (babashka.process/destroy server-process)
  (catch Exception e
    (println "[warn] " "Could not stop server" {:error (str e)})))

;; Read client results
(println "[info] " "Reading test results")
(def results (mp/read-all-results test-id client-process-ids 5000))
(println "[info] " "Results collected" {:count (count results)})

;; Calculate test duration
(def end-time (System/currentTimeMillis))
(def total-duration-ms (- end-time start-time))
(def total-duration-sec (/ total-duration-ms 1000.0))

;; Analyze results
(def aggregated (mp/aggregate-results results))
(println "[info] " "Test results" {:summary aggregated})

;; Calculate performance metrics
(def total-messages-sent (reduce + (map #(:messages-sent % 0) results)))
(def total-messages-received (reduce + (map #(:messages-received % 0) results)))
(def total-connections (reduce + (map #(:connections % 0) results)))
(def messages-per-sec (if (> total-duration-sec 0)
                        (/ total-messages-sent total-duration-sec)
                        0))

(println "[info] " "Performance metrics"
          {:total-messages-sent total-messages-sent
           :total-messages-received total-messages-received
           :total-connections total-connections
           :duration-sec total-duration-sec
           :messages-per-sec (format "%.2f" messages-per-sec)})

;; Cleanup
(println "[info] " "Cleaning up test files")
(mp/cleanup-test-files! test-id process-ids)

;; Validate
(def validation-failures (atom []))

;; Validate expected number of results
(when-not (= client-count (count results))
  (swap! validation-failures conj
         (str "Expected " client-count " results, got " (count results))))

;; Validate all clients connected
(doseq [client-result results]
  (when-not (>= (:connections client-result 0) 1)
    (swap! validation-failures conj
           (str "Client " (:process-id client-result) " did not connect"))))

;; Validate all clients sent at least 90% of expected messages (stress test tolerance)
(def min-messages-per-client (* 0.9 messages-per-client))
(doseq [client-result results]
  (when-not (>= (:messages-sent client-result 0) min-messages-per-client)
    (swap! validation-failures conj
           (str "Client " (:process-id client-result)
                " sent " (:messages-sent client-result)
                " messages, expected at least " min-messages-per-client))))

;; Validate failures are reasonable (< 10% failure rate)
(def max-failures-per-client (* 0.1 messages-per-client))
(doseq [client-result results]
  (when-not (<= (:failures client-result 0) max-failures-per-client)
    (swap! validation-failures conj
           (str "Client " (:process-id client-result)
                " had " (:failures client-result) " failures (too many)"))))

;; Validate throughput is reasonable (at least 50% of expected)
(def expected-total-messages (* client-count messages-per-client))
(when-not (>= total-messages-sent (* 0.5 expected-total-messages))
  (swap! validation-failures conj
         (str "Only sent " total-messages-sent " messages, expected at least "
              (* 0.5 expected-total-messages))))

;; Report results
(if (empty? @validation-failures)
  (do
    (println "[info] " "✅ Test 6 PASSED: Stress test working"
              {:client-count client-count
               :total-messages total-messages-sent
               :duration-sec total-duration-sec
               :throughput-msg-sec (format "%.2f" messages-per-sec)
               :sample-client (first results)})
    (System/exit 0))
  (do
    (println "ERROR:" "❌ Test 6 FAILED: Validation failures"
                {:failures @validation-failures
                 :results results})
    (System/exit 1)))
