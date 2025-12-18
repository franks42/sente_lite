#!/usr/bin/env bb
;;
;; Test 2: Reconnection
;;
;; Tests: Server restart, clients auto-reconnect
;; Validates: Clients detect disconnection and reconnect with backoff
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[mp-utils :as mp])

(println "=== Test 2: Reconnection ===")
(println)

(def test-id (str "reconnect-" (System/currentTimeMillis)))
(def test-port 9877)  ; Fixed port for reconnection test
(def initial-message-count 3)
(def post-reconnect-message-count 3)
(def script-dir (-> *file* fs/parent str))

(println "[test] Test ID:" test-id)
(println "[test] Fixed port:" test-port)
(println "[test] Initial messages:" initial-message-count)
(println "[test] Post-reconnect messages:" post-reconnect-message-count)
(println)

;; Cleanup from any previous run
(println "[setup] Cleaning up previous test files...")
(mp/cleanup-test-files! test-id ["server" "client-1" "client-2" "test-complete"])

;; Start server process (fixed port)
(println "[phase 1] Starting server (first instance)...")
(def server-process
  (p/process ["bb" (str script-dir "/mp_server_reconnect.bb")
              test-id (str test-port) "30"]
             {:out :inherit
              :err :inherit}))

;; Wait for server ready
(println "[phase 1] Waiting for server ready signal...")
(try
  (mp/wait-for-ready test-id "server" 5000)
  (println "[phase 1] Server is ready on port" test-port)
  (catch Exception e
    (println "[ERROR] Server failed to become ready:" (str e))
    (try (p/destroy server-process) (catch Exception _))
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Start client processes (with auto-reconnect enabled)
(println "[phase 1] Starting client 1...")
(def client1-process
  (p/process ["bb" (str script-dir "/mp_client_reconnect.bb")
              test-id "1" "reconnect-channel" (str test-port)
              (str initial-message-count) (str post-reconnect-message-count)]
             {:out :inherit
              :err :inherit}))

(println "[phase 1] Starting client 2...")
(def client2-process
  (p/process ["bb" (str script-dir "/mp_client_reconnect.bb")
              test-id "2" "reconnect-channel" (str test-port)
              (str initial-message-count) (str post-reconnect-message-count)]
             {:out :inherit
              :err :inherit}))

;; Wait for clients to connect and send initial messages
(println "[phase 1] Waiting for clients to connect and send initial messages...")
(Thread/sleep 3000)

;; Kill server (simulate failure)
(println)
(println "[phase 2] STOPPING SERVER (simulating failure)...")
(try
  (p/destroy server-process)
  (println "[phase 2] Server stopped")
  (catch Exception e
    (println "[WARN] Could not stop server:" (str e))))

;; Clear server ready signal for restart detection
(mp/cleanup-ready-file! test-id "server")

;; Wait for clients to detect disconnection
(println "[phase 2] Waiting for clients to detect disconnection...")
(Thread/sleep 3000)

;; Restart server on same port
(println)
(println "[phase 3] RESTARTING SERVER on same port...")
(def server-process-2
  (p/process ["bb" (str script-dir "/mp_server_reconnect.bb")
              test-id (str test-port) "30"]
             {:out :inherit
              :err :inherit}))

;; Wait for restarted server to be ready
(println "[phase 3] Waiting for restarted server ready signal...")
(try
  (mp/wait-for-ready test-id "server" 5000)
  (println "[phase 3] Restarted server is ready")
  (catch Exception e
    (println "[ERROR] Restarted server failed:" (str e))
    (try (p/destroy server-process-2) (catch Exception _))
    (try (p/destroy client1-process) (catch Exception _))
    (try (p/destroy client2-process) (catch Exception _))
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Wait for clients to reconnect - need sufficient time for reconnect attempts
(println "[phase 3] Waiting for clients to reconnect and stabilize (8 sec)...")
(Thread/sleep 8000)

;; Signal clients to finish their work
(println)
(println "[phase 4] Signaling clients to complete...")
(mp/signal-ready! test-id "test-complete")

;; Wait for clients to complete
(println "[phase 4] Waiting for clients to complete...")
(try
  (mp/wait-for-all-ready test-id ["client-1" "client-2"] 15000)
  (println "[phase 4] All clients completed")
  (catch Exception e
    (println "[ERROR] Clients failed to complete:" (str e))
    (try (p/destroy server-process-2) (catch Exception _))
    (try (p/destroy client1-process) (catch Exception _))
    (try (p/destroy client2-process) (catch Exception _))
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2" "test-complete"])
    (System/exit 1)))

;; Wait for client processes to exit
(println "[phase 4] Waiting for client processes to exit...")
@client1-process
@client2-process
(Thread/sleep 1000)

;; Stop server
(println "[cleanup] Stopping server...")
(try
  (p/destroy server-process-2)
  (catch Exception _))

(println)

;; Read client results (server was killed, so no server result)
(println "[results] Reading test results...")
(def results (mp/read-all-results test-id ["client-1" "client-2"] 5000))
(println "[results] Results collected:" (count results) "results")

;; Extract individual results
(def client1-result (first (filter #(= "client-1" (:process-id %)) results)))
(def client2-result (first (filter #(= "client-2" (:process-id %)) results)))

;; Validation
(def validation-failures (atom []))

;; Validate clients reconnected (at least 2 connections: initial + reconnect)
(when-not (>= (:connection-count client1-result 0) 2)
  (swap! validation-failures conj 
         (str "Client 1 did not reconnect (connections: " (:connection-count client1-result) ")")))

(when-not (>= (:connection-count client2-result 0) 2)
  (swap! validation-failures conj 
         (str "Client 2 did not reconnect (connections: " (:connection-count client2-result) ")")))

;; Validate initial messages sent
(when-not (= initial-message-count (:initial-messages-sent client1-result 0))
  (swap! validation-failures conj 
         (str "Client 1 initial messages: " (:initial-messages-sent client1-result) 
              " expected: " initial-message-count)))

(when-not (= initial-message-count (:initial-messages-sent client2-result 0))
  (swap! validation-failures conj 
         (str "Client 2 initial messages: " (:initial-messages-sent client2-result) 
              " expected: " initial-message-count)))

;; Validate post-reconnect messages sent
(when-not (= post-reconnect-message-count (:post-reconnect-messages-sent client1-result 0))
  (swap! validation-failures conj 
         (str "Client 1 post-reconnect messages: " (:post-reconnect-messages-sent client1-result) 
              " expected: " post-reconnect-message-count)))

(when-not (= post-reconnect-message-count (:post-reconnect-messages-sent client2-result 0))
  (swap! validation-failures conj 
         (str "Client 2 post-reconnect messages: " (:post-reconnect-messages-sent client2-result) 
              " expected: " post-reconnect-message-count)))

;; Validate disconnection detection
(when-not (:detected-disconnection client1-result false)
  (swap! validation-failures conj "Client 1 did not detect disconnection"))

(when-not (:detected-disconnection client2-result false)
  (swap! validation-failures conj "Client 2 did not detect disconnection"))

;; Validate reconnection detection  
(when-not (:detected-reconnection client1-result false)
  (swap! validation-failures conj "Client 1 did not detect reconnection"))

(when-not (:detected-reconnection client2-result false)
  (swap! validation-failures conj "Client 2 did not detect reconnection"))

;; Cleanup
(println "[cleanup] Cleaning up test files...")
(mp/cleanup-test-files! test-id ["server" "client-1" "client-2" "test-complete"])

;; Report results
(println)
(println "=== Test Results ===")
(println "Client 1:" (select-keys client1-result 
                                   [:status :connection-count :initial-messages-sent 
                                    :post-reconnect-messages-sent :detected-disconnection 
                                    :detected-reconnection]))
(println "Client 2:" (select-keys client2-result 
                                   [:status :connection-count :initial-messages-sent 
                                    :post-reconnect-messages-sent :detected-disconnection 
                                    :detected-reconnection]))
(println)

(if (empty? @validation-failures)
  (do
    (println "✅ Test 2 PASSED: Reconnection working!")
    (System/exit 0))
  (do
    (println "❌ Test 2 FAILED:")
    (doseq [f @validation-failures]
      (println "  -" f))
    (System/exit 1)))
