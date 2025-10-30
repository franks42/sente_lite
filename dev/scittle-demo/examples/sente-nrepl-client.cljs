(ns examples.sente-nrepl-client
  "Browser-side nREPL handler for sente-websocket

  Replaces Scittle's built-in WebSocket nREPL with sente-based implementation.
  Uses scittle.core.eval_string for code evaluation.

  Architecture:
    Editor → BB Gateway (port 1339, bencode) → Sente (port 1342, EDN) → Browser

  Usage:
    This file is loaded automatically when using sente-nrepl gateway.
    No manual setup required - auto-connects on load."
  (:require [sente-lite.client-scittle :as sente]))

;;; State

(defonce client-atom (atom nil))
(defonce eval-counter (atom 0))

;;; Code Evaluation

(defn eval-code
  "Evaluate ClojureScript code using Scittle's eval_string"
  [code-string]
  (try
    (let [result (.eval_string (.-core js/window.scittle) code-string)
          ;; Convert result to string
          result-str (pr-str result)]
      (js/console.log "✓ Eval success:" result-str)
      {:success true
       :value result-str
       :ns (str *ns*)})
    (catch js/Error e
      (js/console.error "✗ Eval error:" (.-message e))
      {:success false
       :error (.-message e)
       :ex (str (type e))
       :ns (str *ns*)})))

;;; Message Handling

(defn handle-message
  "Handle messages from sente-websocket"
  [msg]
  (let [event-type (first msg)
        event-data (second msg)]
    (swap! eval-counter inc)
    (js/console.log (str "📨 [" @eval-counter "] nREPL message:")
                    (pr-str event-type))

    (case event-type
      :nrepl/eval
      ;; Gateway sent eval request
      (let [{:keys [op code id session]} event-data]
        (js/console.log "  Op:" op)
        (js/console.log "  Code:" (subs code 0 (min 50 (count code))))
        (js/console.log "  ID:" id)

        ;; Evaluate code
        (let [result (eval-code code)]
          ;; Send response back
          (when-let [client-id @client-atom]
            (if (:success result)
              (do
                ;; Send value response
                (sente/send! client-id [:nrepl/response
                                        {:value (:value result)
                                         :id id
                                         :session session
                                         :ns (:ns result)}])
                ;; Send done status
                (sente/send! client-id [:nrepl/response
                                        {:status ["done"]
                                         :id id
                                         :session session
                                         :ns (:ns result)}]))
              ;; Send error response
              (do
                (sente/send! client-id [:nrepl/response
                                        {:ex (:ex result)
                                         :root-ex (:error result)
                                         :id id
                                         :session session
                                         :ns (:ns result)}])
                (sente/send! client-id [:nrepl/response
                                        {:status ["eval-error" "done"]
                                         :id id
                                         :session session
                                         :ns (:ns result)}]))))))

      :welcome
      ;; Connected to gateway, register as nREPL client
      (do
        (js/console.log "✓ Connected to sente-nrepl gateway")
        (js/console.log "  Registering as nREPL client...")
        (when-let [client-id @client-atom]
          (sente/send! client-id [:nrepl/register {}])))

      :ping
      ;; Heartbeat from server
      (when-let [client-id @client-atom]
        (sente/send! client-id {:type :pong
                                :timestamp (js/Date.now)}))

      ;; Other messages
      (js/console.log "📨 Other message:" (pr-str event-type) (pr-str event-data)))))

;;; Connection Management

(defn connect!
  "Connect to sente-nrepl gateway"
  []
  (js/console.log "")
  (js/console.log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
  (js/console.log "🔌 Connecting to sente-nrepl gateway...")
  (js/console.log "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

  (let [client-id (sente/make-client!
                   {:url "ws://localhost:1346/nrepl"
                    :on-open (fn []
                               (js/console.log "✓ WebSocket open"))
                    :on-message handle-message
                    :on-reconnect (fn []
                                    (js/console.log "✓ Reconnected, re-registering...")
                                    ;; Re-register after reconnection
                                    (when-let [cid @client-atom]
                                      (sente/send! cid [:nrepl/register {}])))
                    :on-close (fn [event]
                                (js/console.log "✗ Disconnected from gateway"))
                    :on-error (fn [error]
                                (js/console.error "⚠ Error:" error))
                    :auto-reconnect? true
                    :reconnect-delay 1000
                    :max-reconnect-delay 30000})]
    (reset! client-atom client-id)
    (js/console.log "✓ sente-nrepl client initialized")
    (js/console.log "  Client ID:" client-id)
    (js/console.log "")
    (js/console.log "Ready for nREPL connections!")
    (js/console.log "Connect your editor to: localhost:1339")
    (js/console.log "")
    client-id))

(defn disconnect!
  "Disconnect from gateway"
  []
  (when-let [client-id @client-atom]
    (sente/close! client-id)
    (reset! client-atom nil)
    (js/console.log "✓ Disconnected from sente-nrepl gateway")))

(defn get-status
  "Get current connection status"
  []
  (if-let [client-id @client-atom]
    {:connected true
     :client-id client-id
     :eval-count @eval-counter
     :sente-status (sente/get-status client-id)}
    {:connected false}))

;;; Auto-connect on load

(js/console.log "")
(js/console.log "╔════════════════════════════════════════╗")
(js/console.log "║   sente-nrepl Client Loading...       ║")
(js/console.log "╚════════════════════════════════════════╝")
(js/console.log "")

;; Auto-connect when this file loads
(connect!)
