#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[babashka.process :as p]
         '[telemere-lite.core :as tel]
         '[mp-utils :as mp])

;;
;; Test 1: Basic Multi-Process (HIGH Priority)
;;
;; Tests: 1 server + 2 clients, basic pub/sub
;; Validates: Separate processes can communicate via WebSocket
;;

(tel/log! :info "=== Test 1: Basic Multi-Process ===")

(def test-id "basic-mp-01")
(def test-duration-sec 10)
(def client-message-count 5)

;; Cleanup from any previous run
(tel/log! :info "Cleaning up previous test files")
(mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])

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
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Get server port
(def server-port (mp/read-port test-id 1000))
(tel/log! :info "Server port discovered" {:port server-port})

;; Start client 1
(tel/log! :info "Starting client 1")
(def client1-process
  (p/process ["bb" (str script-dir "/mp_client.bb")
              test-id "1" "test-channel" (str client-message-count)]
             {:out :inherit
              :err :inherit}))

;; Start client 2
(tel/log! :info "Starting client 2")
(def client2-process
  (p/process ["bb" (str script-dir "/mp_client.bb")
              test-id "2" "test-channel" (str client-message-count)]
             {:out :inherit
              :err :inherit}))

;; Wait for clients to complete
(tel/log! :info "Waiting for clients to complete")
(try
  (mp/wait-for-all-ready test-id ["client-1" "client-2"] 15000)
  (tel/log! :info "All clients completed")
  (catch Exception e
    (tel/error! "Clients failed to complete" {:error (str e)})
    (try (babashka.process/destroy server-process) (catch Exception _))
    (try (babashka.process/destroy client1-process) (catch Exception _))
    (try (babashka.process/destroy client2-process) (catch Exception _))
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Wait for processes to exit
(tel/log! :info "Waiting for client processes to exit")
@client1-process
@client2-process
(tel/log! :info "Clients exited")

;; Wait for server to complete its duration and write result
;; Server runs for test-duration-sec and should exit naturally
(tel/log! :info "Waiting for server to complete" {:duration-sec test-duration-sec})
(try
  ;; Wait up to duration + 5 seconds for server to finish and write result
  (def server-wait-start (System/currentTimeMillis))
  (def server-timeout-ms (* (+ test-duration-sec 5) 1000))

  ;; Use deref with timeout to wait for server process
  (deref server-process server-timeout-ms :timeout)

  (def server-wait-duration (- (System/currentTimeMillis) server-wait-start))
  (tel/log! :info "Server completed" {:duration-ms server-wait-duration})

  (catch Exception e
    (tel/log! :warn "Server did not complete cleanly" {:error (str e)})
    ;; Try to stop it if still running
    (try
      (babashka.process/destroy server-process)
      (catch Exception _))))

;; Read all results
(tel/log! :info "Reading test results")
(def results (mp/read-all-results test-id ["server" "client-1" "client-2"] 5000))
(tel/log! :info "Results collected" {:count (count results)})

;; Analyze results
(def aggregated (mp/aggregate-results results))
(tel/log! :info "Test results" {:summary aggregated})

;; Cleanup
(tel/log! :info "Cleaning up test files")
(mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])

;; Validate
(def server-result (first (filter #(= "server" (:process-id %)) results)))
(def client1-result (first (filter #(= "client-1" (:process-id %)) results)))
(def client2-result (first (filter #(= "client-2" (:process-id %)) results)))

(def validation-failures (atom []))

;; Validate server ran
(when-not (= "passed" (:status server-result))
  (swap! validation-failures conj "Server did not pass"))

;; Validate clients connected
(when-not (>= (:connections client1-result 0) 1)
  (swap! validation-failures conj "Client 1 did not connect"))

(when-not (>= (:connections client2-result 0) 1)
  (swap! validation-failures conj "Client 2 did not connect"))

;; Validate messages sent
(when-not (= client-message-count (:messages-sent client1-result 0))
  (swap! validation-failures conj (str "Client 1 sent " (:messages-sent client1-result)
                                       " messages, expected " client-message-count)))

(when-not (= client-message-count (:messages-sent client2-result 0))
  (swap! validation-failures conj (str "Client 2 sent " (:messages-sent client2-result)
                                       " messages, expected " client-message-count)))

;; Report results
(if (empty? @validation-failures)
  (do
    (tel/log! :info "✅ Test 1 PASSED: Basic multi-process working"
              {:server server-result
               :client1 client1-result
               :client2 client2-result})
    (System/exit 0))
  (do
    (tel/error! "❌ Test 1 FAILED: Validation failures"
                {:failures @validation-failures
                 :results results})
    (System/exit 1)))
