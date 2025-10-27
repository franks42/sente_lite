#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[babashka.process :as p]
         '[telemere-lite.core :as tel]
         '[mp-utils :as mp])

;;
;; Test 3: Reconnection (MEDIUM Priority)
;;
;; Tests: Server restart, clients auto-reconnect
;; Validates: Clients detect disconnection and reconnect with backoff
;;

(tel/log! :info "=== Test 3: Reconnection ===")

(def test-id "reconnection-03")
(def test-port 9876)  ; Fixed port so we can restart on same port
(def client-count 2)
(def initial-message-count 3)
(def post-reconnect-message-count 3)

;; Cleanup from any previous run
(tel/log! :info "Cleaning up previous test files")
(mp/cleanup-test-files! test-id ["server" "client-1" "client-2" "test-complete"])

;; Start server process in background (fixed port)
(tel/log! :info "Starting server process (first time)" {:port test-port})
(def script-dir (-> *file* babashka.fs/parent str))
(def server-process
  (p/process ["bb" (str script-dir "/mp_server_reconnect.bb")
              test-id (str test-port) "30"]
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

;; Start client processes
(tel/log! :info "Starting client processes")
(def client1-process
  (p/process ["bb" (str script-dir "/mp_client_reconnect.bb")
              test-id "1" "test-channel" (str test-port)
              (str initial-message-count) (str post-reconnect-message-count)]
             {:out :inherit
              :err :inherit}))

(def client2-process
  (p/process ["bb" (str script-dir "/mp_client_reconnect.bb")
              test-id "2" "test-channel" (str test-port)
              (str initial-message-count) (str post-reconnect-message-count)]
             {:out :inherit
              :err :inherit}))

;; Wait for clients to connect
(tel/log! :info "Waiting for clients to connect")
(Thread/sleep 2000)

;; Let clients send initial messages
(tel/log! :info "Clients sending initial messages")
(Thread/sleep 2000)

;; Kill server
(tel/log! :info "Stopping server (simulating failure)")
(try
  (babashka.process/destroy server-process)
  (tel/log! :info "Server stopped")
  (catch Exception e
    (tel/log! :warn "Could not stop server" {:error (str e)})))

;; Clear server ready signal
(mp/cleanup-ready-file! test-id "server")

;; Wait a bit to ensure clients detect disconnection
(tel/log! :info "Waiting for clients to detect disconnection")
(Thread/sleep 3000)

;; Restart server on same port
(tel/log! :info "Restarting server on same port" {:port test-port})
(def server-process-2
  (p/process ["bb" (str script-dir "/mp_server_reconnect.bb")
              test-id (str test-port) "30"]
             {:out :inherit
              :err :inherit}))

;; Wait for server ready
(tel/log! :info "Waiting for restarted server ready signal")
(try
  (mp/wait-for-ready test-id "server" 5000)
  (tel/log! :info "Restarted server is ready")
  (catch Exception e
    (tel/error! "Restarted server failed to become ready" {:error (str e)})
    (try (babashka.process/destroy server-process-2) (catch Exception _))
    (try (babashka.process/destroy client1-process) (catch Exception _))
    (try (babashka.process/destroy client2-process) (catch Exception _))
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Wait for clients to reconnect and send post-reconnect messages
(tel/log! :info "Waiting for clients to reconnect and send messages")
(Thread/sleep 5000)

;; Signal clients to finish
(tel/log! :info "Signaling clients to complete")
(mp/signal-ready! test-id "test-complete")

;; Wait for clients to complete
(tel/log! :info "Waiting for clients to complete")
(try
  (mp/wait-for-all-ready test-id ["client-1" "client-2"] 15000)
  (tel/log! :info "All clients completed")
  (catch Exception e
    (tel/error! "Clients failed to complete" {:error (str e)})
    (try (babashka.process/destroy server-process-2) (catch Exception _))
    (try (babashka.process/destroy client1-process) (catch Exception _))
    (try (babashka.process/destroy client2-process) (catch Exception _))
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Wait for processes to exit
(tel/log! :info "Waiting for client processes to exit")
@client1-process
@client2-process
(Thread/sleep 1000)

;; Kill server
(tel/log! :info "Stopping server")
(try
  (babashka.process/destroy server-process-2)
  (catch Exception e
    (tel/log! :warn "Could not stop server" {:error (str e)})))

;; Read client results only (server was killed before it could write results)
(tel/log! :info "Reading test results")
(def results (mp/read-all-results test-id ["client-1" "client-2"] 5000))
(tel/log! :info "Results collected" {:count (count results)})

;; Analyze results
(def aggregated (mp/aggregate-results results))
(tel/log! :info "Test results" {:summary aggregated})

;; Cleanup
(tel/log! :info "Cleaning up test files")
(mp/cleanup-test-files! test-id ["server" "client-1" "client-2" "test-complete"])

;; Validate
(def client1-result (first (filter #(= "client-1" (:process-id %)) results)))
(def client2-result (first (filter #(= "client-2" (:process-id %)) results)))

(def validation-failures (atom []))

;; Validate clients reconnected
(when-not (>= (:connection-count client1-result 0) 2)
  (swap! validation-failures conj (str "Client 1 did not reconnect (connections: "
                                       (:connection-count client1-result) ")")))

(when-not (>= (:connection-count client2-result 0) 2)
  (swap! validation-failures conj (str "Client 2 did not reconnect (connections: "
                                       (:connection-count client2-result) ")")))

;; Validate initial messages sent
(def expected-initial-msgs initial-message-count)
(when-not (= expected-initial-msgs (:initial-messages-sent client1-result 0))
  (swap! validation-failures conj (str "Client 1 initial messages: "
                                       (:initial-messages-sent client1-result)
                                       " expected: " expected-initial-msgs)))

(when-not (= expected-initial-msgs (:initial-messages-sent client2-result 0))
  (swap! validation-failures conj (str "Client 2 initial messages: "
                                       (:initial-messages-sent client2-result)
                                       " expected: " expected-initial-msgs)))

;; Validate post-reconnect messages sent
(def expected-post-msgs post-reconnect-message-count)
(when-not (= expected-post-msgs (:post-reconnect-messages-sent client1-result 0))
  (swap! validation-failures conj (str "Client 1 post-reconnect messages: "
                                       (:post-reconnect-messages-sent client1-result)
                                       " expected: " expected-post-msgs)))

(when-not (= expected-post-msgs (:post-reconnect-messages-sent client2-result 0))
  (swap! validation-failures conj (str "Client 2 post-reconnect messages: "
                                       (:post-reconnect-messages-sent client2-result)
                                       " expected: " expected-post-msgs)))

;; Validate clients detected disconnection
(when-not (:detected-disconnection client1-result false)
  (swap! validation-failures conj "Client 1 did not detect disconnection"))

(when-not (:detected-disconnection client2-result false)
  (swap! validation-failures conj "Client 2 did not detect disconnection"))

;; Report results
(if (empty? @validation-failures)
  (do
    (tel/log! :info "✅ Test 3 PASSED: Reconnection working"
              {:client1 client1-result
               :client2 client2-result})
    (System/exit 0))
  (do
    (tel/error! "❌ Test 3 FAILED: Validation failures"
                {:failures @validation-failures
                 :results results})
    (System/exit 1)))
