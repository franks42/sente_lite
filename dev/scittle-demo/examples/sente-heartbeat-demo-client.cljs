(ns examples.sente-heartbeat-demo-client
  "sente-lite Heartbeat Demo Client

  Demonstrates client-side heartbeat (ping/pong) mechanism.
  Client automatically responds to server pings with pongs.
  Monitors connection health via heartbeat messages.

  Usage:
    1. Start server: bb dev/scittle-demo/examples/sente-heartbeat-demo-server.clj
    2. Load this file in Scittle browser via nREPL (port 1339)
    3. Call (connect!) to establish WebSocket connection
    4. Watch console for ping/pong messages every 5 seconds"
  (:require [sente-lite.client-scittle :as sente]))

;; Client state
(defonce client-atom (atom nil))
(defonce ping-count-atom (atom 0))
(defonce pong-count-atom (atom 0))

(defn- handle-heartbeat-message
  "Handle ping/pong messages and auto-respond"
  [msg]
  (let [msg-type (:type msg)]
    (case msg-type
      :ping
      (do
        (swap! ping-count-atom inc)
        (js/console.log "💓 Received ping #" @ping-count-atom)
        ;; Auto-pong: send pong response
        (when-let [client-id @client-atom]
          (sente/send! client-id {:type "pong"
                                  :timestamp (js/Date.now)})
          (swap! pong-count-atom inc)
          (js/console.log "💚 Sent pong #" @pong-count-atom)))

      :pong-ack
      (js/console.log "✓ Server acknowledged pong")

      :welcome
      (js/console.log "✓ Welcome message:" msg)

      ;; Default case for other messages
      (js/console.log "📨 Other message:" (pr-str msg)))))

(defn connect!
  "Establish WebSocket connection with heartbeat monitoring"
  []
  (reset! ping-count-atom 0)
  (reset! pong-count-atom 0)
  (let [client-id (sente/make-client!
                   {:url "ws://localhost:1344"
                    :on-open (fn [ws]
                               (js/console.log "✓ Connected to heartbeat server")
                               (js/console.log "Waiting for pings... (every 5 seconds)"))
                    :on-message handle-heartbeat-message
                    :on-close (fn [event]
                                (js/console.log "✗ Disconnected from server")
                                (js/console.log "Stats: pings=" @ping-count-atom " pongs=" @pong-count-atom))
                    :on-error (fn [error]
                                (js/console.error "⚠ Error:" error))})]
    (reset! client-atom client-id)
    (js/console.log "Client created with ID:" client-id)
    client-id))

(defn disconnect!
  "Close WebSocket connection"
  []
  (when-let [client-id @client-atom]
    (js/console.log "Final stats: pings=" @ping-count-atom " pongs=" @pong-count-atom)
    (sente/close! client-id)
    (reset! client-atom nil)
    (js/console.log "✓ Disconnected")))

(defn get-stats
  "Get current heartbeat statistics"
  []
  (let [client-id @client-atom
        actual-status (when client-id
                        (sente/get-status client-id))]
    {:pings-received @ping-count-atom
     :pongs-sent @pong-count-atom
     :connected? (= actual-status :connected)
     :status actual-status}))

(defn send-test-message!
  "Send test message (unrelated to heartbeat)"
  []
  (if-let [client-id @client-atom]
    (do
      (js/console.log "📤 Sending test message...")
      (sente/send! client-id {:type "test"
                              :data "test message while heartbeat running"
                              :timestamp (js/Date.now)}))
    (js/console.error "⚠ Not connected! Call (connect!) first")))

;; Instructions
(js/console.log "")
(js/console.log "sente-lite Heartbeat Demo Client")
(js/console.log "=================================")
(js/console.log "")
(js/console.log "1. Start server first:")
(js/console.log "   bb dev/scittle-demo/examples/sente-heartbeat-demo-server.clj")
(js/console.log "")
(js/console.log "2. Connect to server:")
(js/console.log "   (examples.sente-heartbeat-demo-client/connect!)")
(js/console.log "")
(js/console.log "3. Watch console for ping/pong messages (every 5 seconds)")
(js/console.log "")
(js/console.log "4. Check stats:")
(js/console.log "   (examples.sente-heartbeat-demo-client/get-stats)")
(js/console.log "")
(js/console.log "5. Send test message (while heartbeat running):")
(js/console.log "   (examples.sente-heartbeat-demo-client/send-test-message!)")
(js/console.log "")
(js/console.log "6. Disconnect:")
(js/console.log "   (examples.sente-heartbeat-demo-client/disconnect!)")
(js/console.log "")
