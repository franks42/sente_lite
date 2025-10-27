#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[babashka.process :as p]
         '[telemere-lite.core :as tel]
         '[mp-utils :as mp])

;;
;; Test 4: Concurrent Startup (MEDIUM Priority)
;;
;; Tests: 10 clients start simultaneously
;; Validates: Server handles concurrent connections without race conditions
;;

(tel/log! :info "=== Test 4: Concurrent Startup ===")

(def test-id "concurrent-04")
(def test-duration-sec 15)
(def client-count 10)
(def message-count 3)

;; Cleanup from any previous run
(tel/log! :info "Cleaning up previous test files")
(def process-ids (concat ["server"]
                         (map #(str "client-" %) (range 1 (inc client-count)))))
(mp/cleanup-test-files! test-id process-ids)

;; Start server process in background
(tel/log! :info "Starting server process")
(def script-dir (-> *file* babashka.fs/parent str))
(def server-process
  (p/process ["bb" (str script-dir "/mp_server.bb") test-id (str test-duration-sec)]
             {:out :inherit
              :err :inherit}))

;; Wait for server ready
(tel/log! :info "Waiting for server ready signal")
(try
  (mp/wait-for-ready test-id "server" 5000)
  (tel/log! :info "Server is ready")
  (catch Exception e
    (tel/error! "Server failed to become ready" {:error (str e)})
    (try (babashka.process/destroy server-process) (catch Exception _))
    (mp/cleanup-test-files! test-id process-ids)
    (System/exit 1)))

;; Get server port
(def server-port (mp/read-port test-id 1000))
(tel/log! :info "Server port discovered" {:port server-port})

;; Start all clients SIMULTANEOUSLY
(tel/log! :info "Starting all clients simultaneously" {:count client-count})
(def client-processes
  (doall
   (for [i (range 1 (inc client-count))]
     (p/process ["bb" (str script-dir "/mp_client.bb")
                 test-id (str i) "shared-channel" (str message-count)]
                {:out :inherit
                 :err :inherit}))))

(tel/log! :info "All client processes started" {:count (count client-processes)})

;; Wait for all clients to complete
(tel/log! :info "Waiting for all clients to complete")
(def client-process-ids (map #(str "client-" %) (range 1 (inc client-count))))
(try
  (mp/wait-for-all-ready test-id client-process-ids 20000)
  (tel/log! :info "All clients completed")
  (catch Exception e
    (tel/error! "Clients failed to complete" {:error (str e)})
    (try (babashka.process/destroy server-process) (catch Exception _))
    (doseq [proc client-processes]
      (try (babashka.process/destroy proc) (catch Exception _)))
    (mp/cleanup-test-files! test-id process-ids)
    (System/exit 1)))

;; Wait for processes to exit
(tel/log! :info "Waiting for processes to exit")
(doseq [proc client-processes]
  @proc)
(Thread/sleep 1000)

;; Kill server
(tel/log! :info "Stopping server")
(try
  (babashka.process/destroy server-process)
  (catch Exception e
    (tel/log! :warn "Could not stop server" {:error (str e)})))

;; Read client results only (server was killed before it could write results)
(tel/log! :info "Reading test results")
(def results (mp/read-all-results test-id client-process-ids 5000))
(tel/log! :info "Results collected" {:count (count results)})

;; Analyze results
(def aggregated (mp/aggregate-results results))
(tel/log! :info "Test results" {:summary aggregated})

;; Cleanup
(tel/log! :info "Cleaning up test files")
(mp/cleanup-test-files! test-id process-ids)

;; Validate
(def client-results results)

(def validation-failures (atom []))

;; Validate all clients connected
(doseq [client-result client-results]
  (when-not (>= (:connections client-result 0) 1)
    (swap! validation-failures conj
           (str "Client " (:client-id client-result) " did not connect"))))

;; Validate all clients sent expected messages
(doseq [client-result client-results]
  (when-not (= message-count (:messages-sent client-result 0))
    (swap! validation-failures conj
           (str "Client " (:client-id client-result)
                " sent " (:messages-sent client-result)
                " messages, expected " message-count))))

;; Validate all clients received messages (at least some)
(doseq [client-result client-results]
  (when-not (>= (:messages-received client-result 0) 2)
    (swap! validation-failures conj
           (str "Client " (:client-id client-result)
                " received " (:messages-received client-result)
                " messages, expected at least 2"))))

;; Validate no client failed
(doseq [client-result client-results]
  (when-not (zero? (:failures client-result 0))
    (swap! validation-failures conj
           (str "Client " (:client-id client-result)
                " had " (:failures client-result) " failures"))))

;; Validate concurrent connection handling
(def total-connections (reduce + (map #(:connections % 0) client-results)))
(when-not (= client-count total-connections)
  (swap! validation-failures conj
         (str "Expected " client-count " total connections, got " total-connections)))

;; Report results
(if (empty? @validation-failures)
  (do
    (tel/log! :info "✅ Test 4 PASSED: Concurrent startup working"
              {:client-count client-count
               :total-connections total-connections
               :sample-client (first client-results)})
    (System/exit 0))
  (do
    (tel/error! "❌ Test 4 FAILED: Validation failures"
                {:failures @validation-failures
                 :results results})
    (System/exit 1)))
