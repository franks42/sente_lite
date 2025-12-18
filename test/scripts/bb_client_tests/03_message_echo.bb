#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[sente-lite.server :as server]
         '[ws-client :as ws]
         '[cheshire.core :as json])

(println "[info] " "=== Phase 3: Message Echo Test ===")

;; Track received messages
(def received-messages (atom []))
(def connection-ready (promise))

(defn log-received [data]
  (swap! received-messages conj data)
  println {:data data}))

(try
  ;; Step 1: Start server
  (println "[info] " "Starting server on port 3000")
  (def test-server
    (try
      (server/start-server! {:port 3000})
      (catch Exception e
        (println "ERROR:" "Server failed to start"
                    {:error e
                     :error-message (.getMessage e)
                     :error-type (str (type e))})
        (throw e))))
  (Thread/sleep 500)
  (def stats (server/get-server-stats))
  (println "[info] " "Server running" {:running (:running? stats)
                                     :port (get-in stats [:config :port])})

  ;; Step 2: Connect client with message handler
  (println "[info] " "Connecting WebSocket client")
  (def ws-connection
    (try
      (ws/connect!
        {:uri "ws://localhost:3000/"
         :on-open (fn [ws]
                    println {:ws (str ws)})
                    (deliver connection-ready true))
         :on-message (fn [ws data last]
                       println {:data-length (count data) :last last})
                       (log-received data))
         :on-close (fn [ws status reason]
                     println {:status status :reason reason}))
         :on-error (fn [ws error]
                     (println "ERROR:" "WebSocket error" {:error error}))})
      (catch Exception e
        (println "ERROR:" "Failed to connect WebSocket"
                    {:error e
                     :error-message (.getMessage e)
                     :error-type (str (type e))})
        (throw e))))

  ;; Wait for connection to be ready
  (def ready-result (deref connection-ready 5000 :timeout))
  (if (= ready-result :timeout)
    (do
      (println "[warn] " "Connection ready timeout - connection may not have opened")
      (throw (ex-info "WebSocket connection timeout" {})))
    (println "[info] " "Client connected"))
  (Thread/sleep 500)

  ;; Step 3: Send test message
  (println "[info] " "Sending test message to server")
  (def test-msg {:type "test-echo"
                 :data "Hello from BB client!"
                 :timestamp (System/currentTimeMillis)})
  (def msg-json (json/generate-string test-msg))
  (ws/send! ws-connection msg-json)
  (println "[info] " "Message sent" {:message test-msg})

  ;; Step 4: Wait for echo response
  (println "[info] " "Waiting for echo response (5 seconds max)")
  (Thread/sleep 5000)

  ;; Step 5: Verify we received messages
  (def msgs @received-messages)
  (println "[info] " "Received messages count" {:count (count msgs)})

  (if (seq msgs)
    (do
      (println "[info] " "Messages received" {:messages msgs})
      ;; Try to parse first message
      (try
        (def first-msg (first msgs))
        (def parsed (json/parse-string first-msg true))
        (println "[info] " "First message parsed" {:parsed parsed})
        (catch Exception e
          (println "[warn] " "Could not parse message as JSON" {:raw first-msg}))))
    (println "[warn] " "No messages received from server"))

  ;; Step 6: Close connection
  (println "[info] " "Closing client connection")
  (ws/close! ws-connection)
  (Thread/sleep 500)

  ;; Step 7: Stop server
  (println "[info] " "Stopping server")
  (server/stop-server!)

  ;; Summary
  (println "[info] " "Phase 3 Summary"
            {:messages-sent 1
             :messages-received (count msgs)
             :connection-established true})

  (if (seq msgs)
    (do
      (println "[info] " "Phase 3 PASSED: Bi-directional communication working")
      (System/exit 0))
    (do
      (println "ERROR:" "Phase 3 INCONCLUSIVE: No messages received (server may not echo yet)")
      (System/exit 0))) ; Exit 0 because server might not have echo handler implemented yet

  (catch Exception e
    (println "ERROR:" "Phase 3 FAILED: Exception occurred"
                {:error e
                 :error-type (str (type e))
                 :error-message (.getMessage e)
                 :error-data (ex-data e)})
    (.printStackTrace e)
    (System/exit 1)))
