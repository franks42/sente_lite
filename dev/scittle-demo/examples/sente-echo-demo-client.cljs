(ns examples.sente-echo-demo-client
  "sente-lite Echo Demo Client

  Demonstrates basic sente-lite client with echo behavior.
  Sends messages to server, receives echo responses.

  Usage:
    1. Start server: bb dev/scittle-demo/examples/sente-echo-demo-server.clj
    2. Load this file in Scittle browser via nREPL (port 1339)
    3. Call (connect!) to establish WebSocket connection
    4. Call (send-test-message!) to send test messages"
  (:require [sente-lite.client-scittle :as sente]))

;; Client state
(defonce client-atom (atom nil))

(defn connect!
  "Establish WebSocket connection to server"
  []
  (let [client-id (sente/make-client!
                   {:url "ws://localhost:1343"
                    :on-open (fn [ws]
                               (js/console.log "✓ Connected to echo server"))
                    :on-message (fn [msg]
                                  (js/console.log "📨 Received echo:" (pr-str msg)))
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

(defn send-test-message!
  "Send test message to server - will be echoed back"
  []
  (if-let [client-id @client-atom]
    (do
      (js/console.log "📤 Sending test message...")
      (sente/send! client-id {:type "test"
                              :data "hello from browser"
                              :timestamp (js/Date.now)}))
    (js/console.error "⚠ Not connected! Call (connect!) first")))

(defn send-custom-message!
  "Send custom message to server"
  [message-map]
  (if-let [client-id @client-atom]
    (do
      (js/console.log "📤 Sending:" (pr-str message-map))
      (sente/send! client-id message-map))
    (js/console.error "⚠ Not connected! Call (connect!) first")))

;; Instructions
(js/console.log "")
(js/console.log "sente-lite Echo Demo Client")
(js/console.log "============================")
(js/console.log "")
(js/console.log "1. Start server first:")
(js/console.log "   bb dev/scittle-demo/examples/sente-echo-demo-server.clj")
(js/console.log "")
(js/console.log "2. Connect to server:")
(js/console.log "   (examples.sente-echo-demo-client/connect!)")
(js/console.log "")
(js/console.log "3. Send test message:")
(js/console.log "   (examples.sente-echo-demo-client/send-test-message!)")
(js/console.log "")
(js/console.log "4. Send custom message:")
(js/console.log "   (examples.sente-echo-demo-client/send-custom-message! {:type \"my-type\" :data {:foo \"bar\"}})")
(js/console.log "")
(js/console.log "5. Disconnect:")
(js/console.log "   (examples.sente-echo-demo-client/disconnect!)")
(js/console.log "")
