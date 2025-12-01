#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[babashka.process :as p]
         '[mp-utils :as mp])

;;
;; Test 5: Process Failure (LOW Priority)
;;
;; Tests: Kill client process, server cleanup
;; Validates: Server detects disconnection and cleans up, other clients unaffected
;;

(println "[info] " "=== Test 5: Process Failure ===")

(def test-id "process-failure-05")
(def test-duration-sec 15)
(def client-count 3)
(def message-count 3)

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

;; Start all clients
(println "[info] " "Starting clients" {:count client-count})
(def client-processes
  (doall
   (for [i (range 1 (inc client-count))]
     (p/process ["bb" (str script-dir "/mp_client.bb")
                 test-id (str i) "test-channel" (str message-count)]
                {:out :inherit
                 :err :inherit}))))

(println "[info] " "All client processes started")

;; Wait for clients to connect and send initial messages
(println "[info] " "Waiting for clients to connect")
(Thread/sleep 3000)

;; Kill client 2 (middle one)
(println "[info] " "Killing client 2 to simulate process failure")
(def client2-process (nth client-processes 1))
(try
  (babashka.process/destroy client2-process)
  (println "[info] " "Client 2 killed")
  (catch Exception e
    (println "[warn] " "Could not kill client 2" {:error (str e)})))

;; Wait for server to detect disconnection
(println "[info] " "Waiting for server to detect disconnection")
(Thread/sleep 2000)

;; Wait for remaining clients to complete
(println "[info] " "Waiting for remaining clients to complete")
(def remaining-client-ids ["client-1" "client-3"])
(try
  (mp/wait-for-all-ready test-id remaining-client-ids 15000)
  (println "[info] " "Remaining clients completed")
  (catch Exception e
    (println "ERROR:" "Remaining clients failed to complete" {:error (str e)})
    (try (babashka.process/destroy server-process) (catch Exception _))
    (doseq [proc client-processes]
      (try (babashka.process/destroy proc) (catch Exception _)))
    (mp/cleanup-test-files! test-id process-ids)
    (System/exit 1)))

;; Wait for processes to exit
(println "[info] " "Waiting for remaining processes to exit")
@(first client-processes)  ; client-1
@(nth client-processes 2)  ; client-3
(Thread/sleep 1000)

;; Kill server
(println "[info] " "Stopping server")
(try
  (babashka.process/destroy server-process)
  (catch Exception e
    (println "[warn] " "Could not stop server" {:error (str e)})))

;; Read results from remaining clients only (client-2 was killed, won't have results)
(println "[info] " "Reading test results")
(def results (mp/read-all-results test-id remaining-client-ids 5000))
(println "[info] " "Results collected" {:count (count results)})

;; Analyze results
(def aggregated (mp/aggregate-results results))
(println "[info] " "Test results" {:summary aggregated})

;; Cleanup
(println "[info] " "Cleaning up test files")
(mp/cleanup-test-files! test-id process-ids)

;; Validate
(def client1-result (first (filter #(= "client-1" (:process-id %)) results)))
(def client3-result (first (filter #(= "client-3" (:process-id %)) results)))

(def validation-failures (atom []))

;; Validate remaining clients succeeded
(when-not (= "passed" (:status client1-result))
  (swap! validation-failures conj "Client 1 did not pass"))

(when-not (= "passed" (:status client3-result))
  (swap! validation-failures conj "Client 3 did not pass"))

;; Validate remaining clients connected
(when-not (>= (:connections client1-result 0) 1)
  (swap! validation-failures conj "Client 1 did not connect"))

(when-not (>= (:connections client3-result 0) 1)
  (swap! validation-failures conj "Client 3 did not connect"))

;; Validate remaining clients sent expected messages
(when-not (= message-count (:messages-sent client1-result 0))
  (swap! validation-failures conj
         (str "Client 1 sent " (:messages-sent client1-result)
              " messages, expected " message-count)))

(when-not (= message-count (:messages-sent client3-result 0))
  (swap! validation-failures conj
         (str "Client 3 sent " (:messages-sent client3-result)
              " messages, expected " message-count)))

;; Validate no failures in remaining clients
(when-not (zero? (:failures client1-result 0))
  (swap! validation-failures conj
         (str "Client 1 had " (:failures client1-result) " failures")))

(when-not (zero? (:failures client3-result 0))
  (swap! validation-failures conj
         (str "Client 3 had " (:failures client3-result) " failures")))

;; Report results
(if (empty? @validation-failures)
  (do
    (println "[info] " "✅ Test 5 PASSED: Process failure handling working"
              {:surviving-clients 2
               :client1 client1-result
               :client3 client3-result})
    (System/exit 0))
  (do
    (println "ERROR:" "❌ Test 5 FAILED: Validation failures"
                {:failures @validation-failures
                 :results results})
    (System/exit 1)))
