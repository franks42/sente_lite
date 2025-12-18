#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[mp-utils :as mp])

;;
;; Test 2: Ephemeral Port Reconnection (HIGH Priority)
;;
;; Tests: Server restarts with DIFFERENT ephemeral port, clients use port-file fallback
;; Validates: Clients detect port change and reconnect using port-file-fn
;;

(println "[info] " "=== Test 2: Ephemeral Port Reconnection ===")

(def test-id "ephemeral-reconnect-02")
(def client-count 2)
(def initial-message-count 3)
(def post-reconnect-message-count 3)

;; Cleanup from any previous run
(println "[info] " "Cleaning up previous test files")
(mp/cleanup-test-files! test-id ["server" "client-1" "client-2" "test-complete"])

;; Start server process in background (ephemeral port)
(println "[info] " "Starting server process (first time, ephemeral port)")
(def script-dir (-> *file* babashka.fs/parent str))
(def server-process
  (p/process ["bb" (str script-dir "/mp_server_reconnect.bb")
              test-id "0" "30"]  ; Port 0 = ephemeral
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
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Get first server port
(def first-port (mp/read-port test-id 1000))
(println "[info] " "First server port" {:port first-port})

;; Start client processes with port-file-fn
(println "[info] " "Starting client processes with port-file-fn")
(def client1-process
  (p/process ["bb" (str script-dir "/mp_client_reconnect_ephemeral.bb")
              test-id "1" "test-channel" (str first-port)
              (str initial-message-count) (str post-reconnect-message-count)]
             {:out :inherit
              :err :inherit}))

(def client2-process
  (p/process ["bb" (str script-dir "/mp_client_reconnect_ephemeral.bb")
              test-id "2" "test-channel" (str first-port)
              (str initial-message-count) (str post-reconnect-message-count)]
             {:out :inherit
              :err :inherit}))

;; Wait for clients to connect
(println "[info] " "Waiting for clients to connect")
(Thread/sleep 2000)

;; Let clients send initial messages
(println "[info] " "Clients sending initial messages")
(Thread/sleep 2000)

;; Kill server
(println "[info] " "Stopping server (simulating failure)")
(try
  (babashka.process/destroy server-process)
  (println "[info] " "Server stopped")
  (catch Exception e
    (println "[warn] " "Could not stop server" {:error (str e)})))

;; Clear server ready signal
(mp/cleanup-ready-file! test-id "server")

;; Wait a bit to ensure clients detect disconnection
(println "[info] " "Waiting for clients to detect disconnection")
(Thread/sleep 3000)

;; Restart server on DIFFERENT ephemeral port (OS will assign new port)
(println "[info] " "Restarting server on new ephemeral port")
(def server-process-2
  (p/process ["bb" (str script-dir "/mp_server_reconnect.bb")
              test-id "0" "30"]  ; Port 0 = ephemeral - will get DIFFERENT port
             {:out :inherit
              :err :inherit}))

;; Wait for server ready
(println "[info] " "Waiting for restarted server ready signal")
(try
  (mp/wait-for-ready test-id "server" 5000)
  (println "[info] " "Restarted server is ready")
  (catch Exception e
    (println "ERROR:" "Restarted server failed to become ready" {:error (str e)})
    (try (babashka.process/destroy server-process-2) (catch Exception _))
    (try (babashka.process/destroy client1-process) (catch Exception _))
    (try (babashka.process/destroy client2-process) (catch Exception _))
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Get second server port (should be different)
(def second-port (mp/read-port test-id 1000))
(println "[info] " "Second server port" {:port second-port})

;; Verify ports are different
(when (= first-port second-port)
  (println "ERROR:" "Server restarted on SAME port - test invalid"
              {:first-port first-port :second-port second-port})
  (try (babashka.process/destroy server-process-2) (catch Exception _))
  (try (babashka.process/destroy client1-process) (catch Exception _))
  (try (babashka.process/destroy client2-process) (catch Exception _))
  (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
  (System/exit 1))

(println "[info] " "Port changed as expected" {:old-port first-port :new-port second-port})

;; Wait for clients to reconnect and send post-reconnect messages
(println "[info] " "Waiting for clients to reconnect and send messages")
(Thread/sleep 5000)

;; Signal clients to finish
(println "[info] " "Signaling clients to complete")
(mp/signal-ready! test-id "test-complete")

;; Wait for clients to complete
(println "[info] " "Waiting for clients to complete")
(try
  (mp/wait-for-all-ready test-id ["client-1" "client-2"] 15000)
  (println "[info] " "All clients completed")
  (catch Exception e
    (println "ERROR:" "Clients failed to complete" {:error (str e)})
    (try (babashka.process/destroy server-process-2) (catch Exception _))
    (try (babashka.process/destroy client1-process) (catch Exception _))
    (try (babashka.process/destroy client2-process) (catch Exception _))
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Wait for processes to exit
(println "[info] " "Waiting for client processes to exit")
@client1-process
@client2-process
(Thread/sleep 1000)

;; Kill server
(println "[info] " "Stopping server")
(try
  (babashka.process/destroy server-process-2)
  (catch Exception e
    (println "[warn] " "Could not stop server" {:error (str e)})))

;; Read client results only (server was killed before it could write results)
(println "[info] " "Reading test results")
(def results (mp/read-all-results test-id ["client-1" "client-2"] 5000))
(println "[info] " "Results collected" {:count (count results)})

;; Analyze results
(def aggregated (mp/aggregate-results results))
(println "[info] " "Test results" {:summary aggregated})

;; Cleanup
(println "[info] " "Cleaning up test files")
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

;; Validate port change detected
(when-not (:port-changed client1-result false)
  (swap! validation-failures conj "Client 1 did not detect port change"))

(when-not (:port-changed client2-result false)
  (swap! validation-failures conj "Client 2 did not detect port change"))

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
    (println "[info] " "✅ Test 2 PASSED: Ephemeral port reconnection working"
              {:old-port first-port
               :new-port second-port
               :client1 client1-result
               :client2 client2-result})
    (System/exit 0))
  (do
    (println "ERROR:" "❌ Test 2 FAILED: Validation failures"
                {:failures @validation-failures
                 :results results})
    (System/exit 1)))
