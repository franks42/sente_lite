#!/usr/bin/env bb
;; Test auto-reconnect with application-controlled subscription restoration

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn])

(println "Testing auto-reconnect with app-controlled restoration...")

;; Application state (NOT infrastructure)
(def app-subscriptions (atom #{}))
(def messages-received (atom []))
(def reconnect-count (atom 0))

;; Infrastructure state
(def ws-client (atom nil))
(def status (atom :disconnected))
(def reconnect-enabled (atom true))

(defn subscribe-to-channel! [channel-id]
  (println "  [APP] Subscribing to" channel-id)
  (swap! app-subscriptions conj channel-id)
  (when @ws-client
    (ws/send! @ws-client (pr-str {:type :subscribe :channel-id channel-id}))))

(defn handle-message [parsed]
  (case (:type parsed)
    :welcome
    (println "✓ Connected:" (:conn-id parsed))

    :subscription-result
    (println "✓ Subscribed to:" (:channel-id parsed))

    :channel-message
    (do
      (swap! messages-received conj parsed)
      (println "📨 Received message on" (:channel-id parsed)))

    :ping nil
    :pong-ack nil
    (println "Other:" (:type parsed))))

(defn attempt-reconnect! []
  (println "\n🔄 Attempting reconnect #" (inc @reconnect-count))
  (swap! reconnect-count inc)

  (try
    (let [client (ws/websocket
                  {:uri "ws://localhost:1345/"
                   :on-message (fn [ws msg _last?]
                                 (handle-message (edn/read-string (str msg))))
                   :on-open (fn [ws]
                              (reset! status :connected)
                              (println "✓ Reconnected!")

                              ;; APPLICATION DECIDES what to restore
                              (println "  [APP] on-reconnect: restoring subscriptions...")
                              (doseq [channel-id @app-subscriptions]
                                (println "    [APP] Re-subscribing to" channel-id)
                                (ws/send! ws (pr-str {:type :subscribe :channel-id channel-id})))
                              (println "  [APP] Restoration complete"))

                   :on-close (fn [ws status reason]
                               (reset! @ws-client nil)
                               (reset! status :disconnected)
                               (println "✗ Disconnected")

                               ;; Auto-reconnect if enabled
                               (when @reconnect-enabled
                                 (Thread/sleep 1000)
                                 (when @reconnect-enabled
                                   (attempt-reconnect!))))

                   :on-error (fn [ws err]
                               (println "⚠ Error:" err))})]
      (reset! ws-client client))
    (catch Exception e
      (println "❌ Reconnect failed:" (.getMessage e))
      (Thread/sleep 2000)
      (when @reconnect-enabled
        (attempt-reconnect!)))))

(println "\n=== Phase 1: Initial connection ===")
(attempt-reconnect!)
(Thread/sleep 1000)

(println "\n=== Phase 2: Subscribe to channel ===")
(subscribe-to-channel! "test-channel")
(Thread/sleep 1000)

(println "\n=== Phase 3: Publish message (should receive) ===")
(ws/send! @ws-client (pr-str {:type :publish
                              :channel-id "test-channel"
                              :data {:test 1}
                              :exclude-sender? false}))
(Thread/sleep 1000)

(println "\n=== Phase 4: Kill server now! ===")
(println "Run in another terminal: pkill -f sente-pubsub-demo-server")
(println "Waiting 15 seconds for disconnect and auto-reconnect...")
(Thread/sleep 15000)

(println "\n=== Phase 5: Test if reconnected and subscriptions restored ===")
(if (= @status :connected)
  (do
    (println "✓ Client reconnected!")
    (println "Sending test message after reconnect...")
    (ws/send! @ws-client (pr-str {:type :publish
                                  :channel-id "test-channel"
                                  :data {:test-after-reconnect true}
                                  :exclude-sender? false}))
    (Thread/sleep 2000)

    (println "\n=== RESULTS ===")
    (println "Messages received:" (count @messages-received))
    (println "Reconnect count:" @reconnect-count)
    (println "Expected: 2 messages (1 before, 1 after reconnect)")

    (if (>= (count @messages-received) 2)
      (println "✅ SUCCESS: Auto-reconnect with app-controlled restoration works!")
      (println "❌ FAILED: Expected 2+ messages, got" (count @messages-received))))
  (println "❌ FAILED: Client did not reconnect"))

(reset! reconnect-enabled false)
(when @ws-client (ws/close! @ws-client))
