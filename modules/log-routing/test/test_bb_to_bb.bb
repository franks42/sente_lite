#!/usr/bin/env bb

;; =============================================================================
;; log-routing BB-to-BB Test
;; =============================================================================
;;
;; Tests remote log routing between two BB processes via sente-lite.
;;
;; Architecture:
;;   Sender Client --[publish]--> Server --[channel-msg]--> Receiver Client
;;
;; Run from project root:
;;   bb modules/log-routing/test/test_bb_to_bb.bb
;;
;; =============================================================================

;; Add our source paths to classpath
;; Note: Trove is vendored in src/taoensso/
(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")
(add-classpath "modules/log-routing/src")

(require '[sente-lite.server :as server]
         '[sente-lite.client-bb :as client]
         '[log-routing.sender :as sender]
         '[log-routing.receiver :as receiver]
         '[taoensso.trove :as trove])

(println "=== log-routing BB-to-BB Test ===\n")

;; =============================================================================
;; Setup
;; =============================================================================

(def server-port 19999)
(def received-logs (atom []))

;; 1. Start server
(println "1. Starting server on port" server-port "...")
(server/start-server! {:port server-port})
(Thread/sleep 500)
(println "   Server started.\n")

;; 2. Connect sender client
(println "2. Connecting sender client...")
(def sender-client
  (client/make-client!
    {:url (str "ws://localhost:" server-port "/ws")
     :client-id "log-sender"}))
(Thread/sleep 500)
(println "   Sender connected.\n")

;; 3. Connect receiver client
(println "3. Connecting receiver client...")
(def receiver-client
  (client/make-client!
    {:url (str "ws://localhost:" server-port "/ws")
     :client-id "log-receiver"}))
(Thread/sleep 500)
(println "   Receiver connected.\n")

;; 4. Set up receiver with custom handler to collect logs
(println "4. Starting log receiver...")
(def handler-id
  (receiver/start! receiver-client
    {:handler (fn [log-entry]
                (swap! received-logs conj log-entry)
                (println "   [RECEIVED]" (:level log-entry) (:ns log-entry)
                         ":" (pr-str (:data log-entry))))}))
(Thread/sleep 200)
(println "   Receiver started, handler-id:" handler-id "\n")

;; 5. Sender must also subscribe to publish (pub/sub requires subscription)
(println "5. Subscribing sender to log-routing channel...")
(client/subscribe! sender-client "log-routing")
(Thread/sleep 200)
(println "   Sender subscribed.\n")

;; 6. Set up sender's remote log-fn
(println "6. Wrapping Trove log-fn on sender...")
(def original-log-fn trove/*log-fn*)
(trove/set-log-fn!
  (sender/make-remote-log-fn original-log-fn sender-client
    {:source-id "test-sender"}))
(println "   Log-fn wrapped.\n")

;; =============================================================================
;; Test
;; =============================================================================

(println "7. Generating test logs...")
(Thread/sleep 100)

;; Generate 3 logs at different levels
(trove/log! {:level :info
             :id ::test-info
             :data {:msg "Test info message" :count 1}})
(Thread/sleep 100)

(trove/log! {:level :warn
             :id ::test-warn
             :data {:msg "Test warning message" :count 2}})
(Thread/sleep 100)

(trove/log! {:level :error
             :id ::test-error
             :data {:msg "Test error message" :count 3 :details "Something went wrong"}})
(Thread/sleep 100)

(println "   3 logs generated.\n")

;; Wait for delivery
(println "8. Waiting for logs to be delivered...")
(Thread/sleep 1000)

;; =============================================================================
;; Verify
;; =============================================================================

(println "\n=== Results ===")
(println "Received" (count @received-logs) "log entries")

(when (> (count @received-logs) 0)
  (println "\nReceived log entries:")
  (doseq [log @received-logs]
    (println "  -" (:level log) (:ns log) (when (:id log) (str "(" (:id log) ")"))
             "->" (pr-str (:data log)))))

(def success? (= 3 (count @received-logs)))

(println)
(if success?
  (println "SUCCESS: All 3 logs received!")
  (println "FAILED: Expected 3 logs, got" (count @received-logs)))

;; =============================================================================
;; Cleanup
;; =============================================================================

(println "\n9. Cleaning up...")
(trove/set-log-fn! original-log-fn)
(client/close! sender-client)
(client/close! receiver-client)
(server/stop-server!)
(println "   Done.\n")

;; Exit with appropriate code
(System/exit (if success? 0 1))
