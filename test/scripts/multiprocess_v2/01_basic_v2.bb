#!/usr/bin/env bb
;;
;; Test 1: Basic Multi-Process v2
;;
;; Tests: 1 server + 2 clients using v2 wire format and client_bb.clj module
;; Validates: Separate processes can communicate via WebSocket using EDN/v2
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[mp-utils :as mp])

(println "=== Test 1: Basic Multi-Process v2 ===")
(println)

(def test-id (str "basic-v2-" (System/currentTimeMillis)))
(def test-duration-sec 10)
(def client-message-count 5)
(def script-dir (-> *file* fs/parent str))

(println "[test] Test ID:" test-id)
(println "[test] Duration:" test-duration-sec "seconds")
(println "[test] Messages per client:" client-message-count)
(println)

;; Cleanup from any previous run
(println "[setup] Cleaning up previous test files...")
(mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])

;; Start server process in background
(println "[setup] Starting v2 server process...")
(def server-process
  (p/process ["bb" (str script-dir "/mp_server_v2.bb") test-id (str test-duration-sec)]
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
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Get server port
(def server-port (mp/read-port test-id 1000))
(println "[setup] Server port discovered:" server-port)
(println)

;; Start clients
(println "[clients] Starting client 1...")
(def client1-process
  (p/process ["bb" (str script-dir "/mp_client_v2.bb")
              test-id "1" "test-channel-v2" (str client-message-count)]
             {:out :inherit
              :err :inherit}))

(println "[clients] Starting client 2...")
(def client2-process
  (p/process ["bb" (str script-dir "/mp_client_v2.bb")
              test-id "2" "test-channel-v2" (str client-message-count)]
             {:out :inherit
              :err :inherit}))

;; Wait for clients to complete
(println "[clients] Waiting for clients to complete...")
(try
  (mp/wait-for-all-ready test-id ["client-1" "client-2"] 15000)
  (println "[clients] All clients completed")
  (catch Exception e
    (println "[ERROR] Clients failed to complete:" (str e))
    (try (p/destroy server-process) (catch Exception _))
    (try (p/destroy client1-process) (catch Exception _))
    (try (p/destroy client2-process) (catch Exception _))
    (mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])
    (System/exit 1)))

;; Wait for client processes to exit
(println "[clients] Waiting for client processes to exit...")
@client1-process
@client2-process
(println "[clients] Clients exited")
(println)

;; Wait for server to complete
(println "[server] Waiting for server to complete (" test-duration-sec "sec)...")
(try
  (def server-wait-start (System/currentTimeMillis))
  (def server-timeout-ms (* (+ test-duration-sec 5) 1000))
  (deref server-process server-timeout-ms :timeout)
  (def server-wait-duration (- (System/currentTimeMillis) server-wait-start))
  (println "[server] Server completed in" server-wait-duration "ms")
  (catch Exception e
    (println "[WARN] Server did not complete cleanly:" (str e))
    (try (p/destroy server-process) (catch Exception _))))

(println)

;; Read all results
(println "[results] Reading test results...")
(def results (mp/read-all-results test-id ["server" "client-1" "client-2"] 5000))
(println "[results] Results collected:" (count results) "results")

;; Analyze results
(def aggregated (mp/aggregate-results results))

;; Extract individual results
(def server-result (first (filter #(= "server" (:process-id %)) results)))
(def client1-result (first (filter #(= "client-1" (:process-id %)) results)))
(def client2-result (first (filter #(= "client-2" (:process-id %)) results)))

;; Validation
(def validation-failures (atom []))

;; Server validation
(when-not (= "passed" (name (:status server-result)))
  (swap! validation-failures conj "Server did not pass"))

;; Client connection validation  
(when-not (:uid client1-result)
  (swap! validation-failures conj "Client 1 did not receive uid"))

(when-not (:uid client2-result)
  (swap! validation-failures conj "Client 2 did not receive uid"))

;; Messages sent validation
(when-not (= client-message-count (:messages-sent client1-result 0))
  (swap! validation-failures conj 
         (str "Client 1 sent " (:messages-sent client1-result) 
              " messages, expected " client-message-count)))

(when-not (= client-message-count (:messages-sent client2-result 0))
  (swap! validation-failures conj 
         (str "Client 2 sent " (:messages-sent client2-result) 
              " messages, expected " client-message-count)))

;; Cleanup
(println "[cleanup] Cleaning up test files...")
(mp/cleanup-test-files! test-id ["server" "client-1" "client-2"])

;; Report results
(println)
(println "=== Test Results ===")
(println "Server:" (select-keys server-result [:status :port :connections]))
(println "Client 1:" (select-keys client1-result [:status :uid :messages-sent :messages-received]))
(println "Client 2:" (select-keys client2-result [:status :uid :messages-sent :messages-received]))
(println)

(if (empty? @validation-failures)
  (do
    (println "✅ Test 1 PASSED: Basic multi-process v2 working!")
    (System/exit 0))
  (do
    (println "❌ Test 1 FAILED:")
    (doseq [f @validation-failures]
      (println "  -" f))
    (System/exit 1)))
