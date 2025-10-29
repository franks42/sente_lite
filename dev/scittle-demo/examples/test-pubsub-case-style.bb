#!/usr/bin/env bb
;; Test pubsub using browser-style `case` matching to verify it works

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn])

(println "Testing pubsub with browser-style case matching...")

(def subscriptions (atom #{}))
(def messages-received (atom []))
(def connection-established (promise))

(defn handle-message-browser-style
  "Handle message using browser's case approach"
  [parsed]
  (let [msg-type (:type parsed)]
    (case msg-type
      :ping
      (println "💓 Ping (ignored for this test)")

      :subscription-result
      (do
        (println "✓ Subscription result:")
        (println "   Channel:" (:channel-id parsed))
        (println "   Success:" (:success parsed))
        (when (:success parsed)
          (swap! subscriptions conj (:channel-id parsed))))

      :unsubscription-result
      (do
        (println "✓ Unsubscription result:")
        (println "   Channel:" (:channel-id parsed))
        (when (:success parsed)
          (swap! subscriptions disj (:channel-id parsed))))

      :publish-result
      (println "✓ Publish result:" (:delivered-to parsed) "subscribers")

      :channel-message
      (do
        (swap! messages-received conj parsed)
        (println "📨 Channel message:" (:data parsed)))

      :welcome
      (println "✓ Welcome:" (:conn-id parsed))

      :pong-ack
      nil  ; ignore

      ;; default case
      (println "📨 Other message:" msg-type (pr-str parsed)))))

(def client
  (ws/websocket
   {:uri "ws://localhost:1345/"
    :on-message (fn [ws msg _last?]
                  (try
                    (let [msg-str (str msg)
                          parsed (edn/read-string msg-str)]
                      (handle-message-browser-style parsed))
                    (catch Exception e
                      (println "⚠ Error:" (ex-message e)))))
    :on-open (fn [ws]
               (println "✓ Connected")
               (deliver connection-established true))
    :on-close (fn [ws status-code reason]
                (println "✗ Disconnected:" status-code reason))
    :on-error (fn [ws error]
                (println "⚠ Error:" error))}))

(println "Waiting for connection...")
(deref connection-established 5000 :timeout)

(if (= @connection-established true)
  (do
    (println "\n=== Test: Subscribe to 'test-channel' ===")
    (ws/send! client (pr-str {:type :subscribe :channel-id "test-channel"}))
    (Thread/sleep 2000)

    (println "\n=== Results ===")
    (println "Subscriptions:" @subscriptions)
    (println "Expected: #{\"test-channel\"}")

    (if (contains? @subscriptions "test-channel")
      (println "✅ SUCCESS: case matching works!")
      (println "❌ FAILED: case matching did not work"))

    (ws/close! client))
  (do
    (println "❌ Connection timeout!")
    (System/exit 1)))
