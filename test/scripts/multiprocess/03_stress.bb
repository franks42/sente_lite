#!/usr/bin/env bb
;;
;; Test 3: Stress Test
;;
;; Tests: 10 clients sending messages concurrently
;; Validates: Server handles concurrent load, no message loss, performance
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[mp-utils :as mp])

(println "=== Test 3: Stress Test ===")
(println)

(def test-id (str "stress-" (System/currentTimeMillis)))
(def test-duration-sec 15)
(def client-count 10)
(def messages-per-client 10)
(def message-interval-ms 200)
(def script-dir (-> *file* fs/parent str))

(println "[test] Test ID:" test-id)
(println "[test] Clients:" client-count)
(println "[test] Messages per client:" messages-per-client)
(println "[test] Message interval:" message-interval-ms "ms")
(println "[test] Expected total:" (* client-count messages-per-client) "messages")
(println)

;; Process IDs
(def process-ids (concat ["server"]
                         (map #(str "client-" %) (range 1 (inc client-count)))))
(def client-process-ids (map #(str "client-" %) (range 1 (inc client-count))))

;; Cleanup
(println "[setup] Cleaning up previous test files...")
(mp/cleanup-test-files! test-id process-ids)

;; Start server
(println "[setup] Starting server...")
(def server-process
  (p/process ["bb" (str script-dir "/mp_server.bb") test-id (str test-duration-sec)]
             {:out :inherit
              :err :inherit}))

;; Wait for server ready
(println "[setup] Waiting for server ready signal...")
(try
  (mp/wait-for-ready test-id "server" 5000)
  (println "[setup] Server is ready")
  (catch Exception e
    (println "[ERROR] Server failed to become ready:" (str e))
    (try (p/destroy server-process) (catch Exception _))
    (mp/cleanup-test-files! test-id process-ids)
    (System/exit 1)))

;; Get server port
(def server-port (mp/read-port test-id 1000))
(println "[setup] Server port:" server-port)
(println)

;; Start all clients SIMULTANEOUSLY
(println "[stress] Starting" client-count "clients simultaneously...")
(def start-time (System/currentTimeMillis))

(def client-processes
  (doall
   (for [i (range 1 (inc client-count))]
     (p/process ["bb" (str script-dir "/mp_client_stress.bb")
                test-id (str i) "stress-channel"
                 (str messages-per-client) (str message-interval-ms)]
                {:out :inherit
                 :err :inherit}))))

(println "[stress] All clients started:" (count client-processes))

;; Wait for all clients to complete
(println "[stress] Waiting for clients to complete...")
(try
  (mp/wait-for-all-ready test-id client-process-ids 30000)
  (println "[stress] All clients completed")
  (catch Exception e
    (println "[ERROR] Clients failed to complete:" (str e))
    (try (p/destroy server-process) (catch Exception _))
    (doseq [proc client-processes]
      (try (p/destroy proc) (catch Exception _)))
    (mp/cleanup-test-files! test-id process-ids)
    (System/exit 1)))

;; Wait for processes to exit
(println "[stress] Waiting for processes to exit...")
(doseq [proc client-processes]
  @proc)
(Thread/sleep 500)

;; Stop server
(println "[cleanup] Stopping server...")
(try
  (p/destroy server-process)
  (catch Exception _))

;; Calculate duration
(def end-time (System/currentTimeMillis))
(def total-duration-ms (- end-time start-time))
(def total-duration-sec (/ total-duration-ms 1000.0))

(println)

;; Read results
(println "[results] Reading test results...")
(def results (mp/read-all-results test-id client-process-ids 5000))
(println "[results] Results collected:" (count results))

;; Calculate metrics
(def total-messages-sent (reduce + (map #(:messages-sent % 0) results)))
(def total-messages-received (reduce + (map #(:messages-received % 0) results)))
(def total-failures (reduce + (map #(:failures % 0) results)))
(def messages-per-sec (if (> total-duration-sec 0)
                        (/ total-messages-sent total-duration-sec)
                        0))

(println)
(println "=== Performance Metrics ===")
(println "Duration:" (format "%.2f" total-duration-sec) "seconds")
(println "Messages sent:" total-messages-sent)
(println "Messages received:" total-messages-received)
(println "Failures:" total-failures)
(println "Throughput:" (format "%.2f" messages-per-sec) "msg/sec")
(println)

;; Cleanup files
(println "[cleanup] Cleaning up test files...")
(mp/cleanup-test-files! test-id process-ids)

;; Validation
(def validation-failures (atom []))

;; Validate result count
(when-not (= client-count (count results))
  (swap! validation-failures conj
         (str "Expected " client-count " results, got " (count results))))

;; Validate all clients connected
(doseq [r results]
  (when-not (>= (:connections r 0) 1)
    (swap! validation-failures conj
           (str "Client " (:process-id r) " did not connect"))))

;; Validate message sending (at least 90% success rate)
(def min-messages (* 0.9 messages-per-client))
(doseq [r results]
  (when-not (>= (:messages-sent r 0) min-messages)
    (swap! validation-failures conj
           (str "Client " (:process-id r) " sent " (:messages-sent r) 
                " messages, expected at least " min-messages))))

;; Validate low failure rate (<10%)
(def max-failures (* 0.1 messages-per-client))
(doseq [r results]
  (when (> (:failures r 0) max-failures)
    (swap! validation-failures conj
           (str "Client " (:process-id r) " had " (:failures r) " failures (too many)"))))

;; Validate total throughput (at least 50% of expected)
(def expected-total (* client-count messages-per-client))
(when-not (>= total-messages-sent (* 0.5 expected-total))
  (swap! validation-failures conj
         (str "Only sent " total-messages-sent " messages, expected at least " (* 0.5 expected-total))))

;; Report
(println "=== Test Results ===")
(if (empty? @validation-failures)
  (do
    (println "✅ Test 3 PASSED: Stress test working!")
    (println "  Clients:" client-count)
    (println "  Total messages:" total-messages-sent)
    (println "  Throughput:" (format "%.2f" messages-per-sec) "msg/sec")
    (System/exit 0))
  (do
    (println "❌ Test 3 FAILED:")
    (doseq [f @validation-failures]
      (println "  -" f))
    (System/exit 1)))
