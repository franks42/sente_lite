(ns ws-client-managed
  "Managed WebSocket client with state tracking, auto-reconnection, and subscription restoration"
  (:require [telemere-lite.core :as tel]
            [babashka.http-client.websocket :as ws]
            [cheshire.core :as json]))

;;
;; State Machine:
;;   :connecting  → :open       (successful connection)
;;   :connecting  → :failed     (connection failed)
;;   :open        → :closing    (graceful close initiated)
;;   :open        → :closed     (connection lost)
;;   :closing     → :closed     (close completed)
;;   :closed      → :reconnecting (auto-reconnect enabled)
;;   :reconnecting → :open      (reconnect successful)
;;   :reconnecting → :failed    (max attempts exceeded)
;;

(defn- notify-state-change!
  "Notify state change callback if provided"
  [state old-status new-status]
  (tel/event! ::state-change {:old old-status :new new-status})
  (when-let [callback (get-in @state [:config :on-state-change])]
    (try
      (callback old-status new-status)
      (catch Exception e
        (tel/error! "State change callback error" {:error e})))))

(defn- update-state!
  "Update state atom and notify callbacks"
  [state updates]
  (let [old-status (:status @state)
        new-status (:status updates old-status)]
    (swap! state merge updates)
    (when (not= old-status new-status)
      (notify-state-change! state old-status new-status))))

(defn- handle-message
  "Process incoming message, handle pings automatically"
  [state data]
  (let [msg (json/parse-string (str data) true)
        auto-pong? (get-in @state [:config :heartbeat :auto-pong] true)]

    (tel/event! ::message-received {:type (:type msg)
                                    :auto-pong auto-pong?})

    (cond
      ;; Auto-respond to pings
      (and (= "ping" (:type msg)) auto-pong?)
      (do
        (tel/event! ::auto-pong-response {:timestamp (:timestamp msg)})
        (when-let [ws (:ws @state)]
          (ws/send! ws (json/generate-string
                        {:type "pong"
                         :timestamp (System/currentTimeMillis)
                         :original-timestamp (:timestamp msg)}))))

      ;; Pass to user handler
      :else
      (when-let [on-message (get-in @state [:config :on-message])]
        (try
          (on-message msg)
          (catch Exception e
            (tel/error! "Message handler error" {:error e :msg msg})))))))

(defn- connect-internal!
  "Internal connection function"
  [state]
  (let [uri (get-in @state [:config :uri])]
    (tel/event! ::connecting {:uri uri})
    (update-state! state {:status :connecting})

    (try
      (let [ws-client (ws/websocket
                       {:uri uri
                        :on-open (fn [ws]
                                   (tel/event! ::connected {:uri uri})
                                   (update-state! state {:status :open
                                                         :ws ws
                                                         :reconnect-attempt 0})
                                   ;; Call user on-open callback
                                   (when-let [on-open (get-in @state [:config :on-open])]
                                     (on-open ws)))
                        :on-message (fn [ws data last]
                                      (handle-message state data))
                        :on-close (fn [ws status reason]
                                    (tel/event! ::connection-closed
                                                {:status status :reason reason})
                                    (update-state! state {:status :closed
                                                          :ws nil})
                                    ;; Call user on-close callback
                                    (when-let [on-close (get-in @state [:config :on-close])]
                                      (on-close ws status reason)))
                        :on-error (fn [ws error]
                                    (tel/error! "WebSocket error" {:error error :uri uri})
                                    ;; Call user on-error callback
                                    (when-let [on-error (get-in @state [:config :on-error])]
                                      (on-error ws error)))})]
        ;; Give connection time to establish
        (Thread/sleep 100)
        ws-client)
      (catch Exception e
        (tel/error! "Failed to connect WebSocket" {:error e :uri uri})
        (update-state! state {:status :failed})
        (throw e)))))

(defn create-managed-client
  "Create a managed WebSocket client with state tracking

  Config options:
    :uri - WebSocket URI (ws://...)
    :on-state-change - Called when state changes (fn [old-state new-state])
    :on-message - Called when message received (fn [msg])
    :on-open - Called when connection opens (fn [ws])
    :on-close - Called when connection closes (fn [ws status reason])
    :on-error - Called on error (fn [ws error])
    :heartbeat {:auto-pong true} - Automatically respond to server pings
    :reconnect {:enabled false      ; Auto-reconnection (Phase 6c)
                :max-attempts 5
                :initial-delay-ms 1000
                :max-delay-ms 30000
                :backoff-multiplier 2}"
  [config]
  (let [state (atom {:status :closed
                     :ws nil
                     :reconnect-attempt 0
                     :subscriptions #{}
                     :config config})]

    ;; Return client handle with methods
    {:state state
     :connect! (fn []
                 (tel/event! ::client-connect-requested {})
                 (connect-internal! state))
     :disconnect! (fn []
                    (tel/event! ::client-disconnect-requested {})
                    (when-let [ws (:ws @state)]
                      (update-state! state {:status :closing})
                      (ws/close! ws 1000 "Normal closure")
                      (update-state! state {:status :closed :ws nil})))
     :send! (fn [message]
              (if-let [ws (:ws @state)]
                (if (= :open (:status @state))
                  (do
                    (tel/event! ::client-send {:message-length (count message)})
                    (ws/send! ws message))
                  (do
                    (tel/error! "Cannot send - connection not open"
                                {:status (:status @state)})
                    false))
                (do
                  (tel/error! "Cannot send - no active connection" {})
                  false)))
     :get-state (fn [] (:status @state))
     :get-full-state (fn [] @state)}))
