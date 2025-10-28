(ns examples.sente-pubsub-demo-client
  "sente-lite Pub/Sub Demo Client

  Demonstrates channel-based pub/sub messaging:
  - Subscribe to channels by channel-id
  - Publish messages to channels
  - Receive messages from subscribed channels
  - Unsubscribe from channels
  - Test multiple channels and message filtering

  Usage:
    1. Start server: bb dev/scittle-demo/examples/sente-pubsub-demo-server.clj
    2. Load this file in Scittle browser via nREPL (port 1339)
    3. Call (connect!) to establish WebSocket connection
    4. Call (subscribe! \"channel-name\") to subscribe to channels
    5. Call (publish! \"channel-name\" {:data \"...\"}) to publish messages
    6. Open multiple browser tabs to test multi-subscriber broadcast"
  (:require [sente-lite.client-scittle :as sente]))

;; Client state
(defonce client-atom (atom nil))
(defonce subscriptions-atom (atom #{}))
(defonce received-messages-atom (atom []))

(defn- handle-message
  "Handle all incoming messages"
  [msg]
  (let [msg-type (:type msg)]
    (case msg-type
      "ping"
      (do
        (js/console.log "💓 Ping received, sending pong...")
        (when-let [client-id @client-atom]
          (sente/send! client-id {:type "pong"
                                  :timestamp (js/Date.now)})))

      "subscription-result"
      (do
        (js/console.log "✓ Subscription result:")
        (js/console.log "  Channel:" (:channel-id msg))
        (js/console.log "  Success:" (:success msg))
        (js/console.log "  Subscribers:" (:subscriber-count msg))
        (when (:success msg)
          (swap! subscriptions-atom conj (:channel-id msg)))
        (when-let [retained (:retained-messages msg)]
          (when (seq retained)
            (js/console.log "  Retained messages:" (count retained)))))

      "unsubscription-result"
      (do
        (js/console.log "✓ Unsubscription result:")
        (js/console.log "  Channel:" (:channel-id msg))
        (js/console.log "  Success:" (:success msg))
        (when (:success msg)
          (swap! subscriptions-atom disj (:channel-id msg))))

      "publish-result"
      (js/console.log "✓ Publish result:"
                      " Channel:" (:channel-id msg)
                      " Delivered to:" (:delivered-to msg))

      "channel-message"
      (do
        (swap! received-messages-atom conj msg)
        (js/console.log "📨 Channel message:")
        (js/console.log "  Channel:" (:channel-id msg))
        (js/console.log "  Data:" (pr-str (:data msg))))

      "pong-ack"
      nil  ; Ignore pong-ack for cleaner console

      ;; Default
      (js/console.log "📨 Received:" (pr-str msg)))))

(defn connect!
  "Establish WebSocket connection"
  []
  (reset! subscriptions-atom #{})
  (reset! received-messages-atom [])
  (let [client-id (sente/make-client!
                   {:url "ws://localhost:1345"
                    :on-open (fn [ws]
                               (js/console.log "✓ Connected to pub/sub server"))
                    :on-message handle-message
                    :on-close (fn [event]
                                (js/console.log "✗ Disconnected from server"))
                    :on-error (fn [error]
                                (js/console.error "⚠ Error:" error))})]
    (reset! client-atom client-id)
    (js/console.log "Client created with ID:" client-id)
    client-id))

(defn disconnect!
  "Close WebSocket connection"
  []
  (when-let [client-id @client-atom]
    (sente/close! client-id)
    (reset! client-atom nil)
    (js/console.log "✓ Disconnected")))

(defn subscribe!
  "Subscribe to a channel"
  [channel-id]
  (if-let [client-id @client-atom]
    (do
      (js/console.log "📥 Subscribing to channel:" channel-id)
      (sente/send! client-id {:type "subscribe"
                              :channel-id channel-id}))
    (js/console.error "⚠ Not connected! Call (connect!) first")))

(defn unsubscribe!
  "Unsubscribe from a channel"
  [channel-id]
  (if-let [client-id @client-atom]
    (do
      (js/console.log "📤 Unsubscribing from channel:" channel-id)
      (sente/send! client-id {:type "unsubscribe"
                              :channel-id channel-id}))
    (js/console.error "⚠ Not connected! Call (connect!) first")))

(defn publish!
  "Publish message to a channel"
  [channel-id data]
  (if-let [client-id @client-atom]
    (do
      (js/console.log "📢 Publishing to channel:" channel-id)
      (sente/send! client-id {:type "publish"
                              :channel-id channel-id
                              :data data
                              :exclude-sender? false}))
    (js/console.error "⚠ Not connected! Call (connect!) first")))

(defn get-subscriptions
  "Get current channel subscriptions"
  []
  @subscriptions-atom)

(defn get-received-messages
  "Get all received channel messages"
  []
  @received-messages-atom)

(defn clear-messages!
  "Clear received messages history"
  []
  (reset! received-messages-atom [])
  (js/console.log "✓ Message history cleared"))

;; Instructions
(js/console.log "")
(js/console.log "sente-lite Pub/Sub Demo Client")
(js/console.log "===============================")
(js/console.log "")
(js/console.log "1. Start server first:")
(js/console.log "   bb dev/scittle-demo/examples/sente-pubsub-demo-server.clj")
(js/console.log "")
(js/console.log "2. Connect to server:")
(js/console.log "   (examples.sente-pubsub-demo-client/connect!)")
(js/console.log "")
(js/console.log "3. Subscribe to channels:")
(js/console.log "   (examples.sente-pubsub-demo-client/subscribe! \"chat\")")
(js/console.log "   (examples.sente-pubsub-demo-client/subscribe! \"notifications\")")
(js/console.log "")
(js/console.log "4. Publish messages:")
(js/console.log "   (examples.sente-pubsub-demo-client/publish! \"chat\" {:user \"Alice\" :msg \"Hello!\"})")
(js/console.log "   (examples.sente-pubsub-demo-client/publish! \"notifications\" {:type \"alert\" :text \"New update\"})")
(js/console.log "")
(js/console.log "5. Check subscriptions:")
(js/console.log "   (examples.sente-pubsub-demo-client/get-subscriptions)")
(js/console.log "")
(js/console.log "6. View received messages:")
(js/console.log "   (examples.sente-pubsub-demo-client/get-received-messages)")
(js/console.log "")
(js/console.log "7. Unsubscribe from channel:")
(js/console.log "   (examples.sente-pubsub-demo-client/unsubscribe! \"chat\")")
(js/console.log "")
(js/console.log "8. Disconnect:")
(js/console.log "   (examples.sente-pubsub-demo-client/disconnect!)")
(js/console.log "")
(js/console.log "TIP: Open multiple browser tabs and load this file in each to test")
(js/console.log "     multi-subscriber broadcast!")
(js/console.log "")
