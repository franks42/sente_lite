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

;; Forward declarations
(declare reconnect-with-backoff!)
(declare restore-subscriptions!)

(defn- restore-subscriptions!
  "Restore all subscriptions after reconnection"
  [state ws]
  (let [subscriptions (:subscriptions @state)]
    (when (seq subscriptions)
      (tel/event! ::restoring-subscriptions {:count (count subscriptions)
                                             :channels subscriptions})
      (doseq [channel-id subscriptions]
        (tel/event! ::restoring-subscription {:channel channel-id})
        (ws/send! ws (json/generate-string
                      {:type "subscribe"
                       :channel-id channel-id}))
        ;; Small delay to ensure each subscription is processed
        (Thread/sleep 100)))))

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
                                   ;; Restore subscriptions after reconnection (Phase 6d)
                                   (restore-subscriptions! state ws)
                                   ;; Call user on-open callback
                                   (when-let [on-open (get-in @state [:config :on-open])]
                                     (on-open ws)))
                        :on-message (fn [ws data last]
                                      (handle-message state data))
                        :on-close (fn [ws status reason]
                                    (tel/event! ::connection-closed
                                                {:status status :reason reason})
                                    (let [was-closing? (= :closing (:status @state))
                                          reconnect-enabled? (get-in @state [:config :reconnect :enabled] false)]
                                      (update-state! state {:status :closed
                                                            :ws nil})
                                      ;; Call user on-close callback
                                      (when-let [on-close (get-in @state [:config :on-close])]
                                        (on-close ws status reason))
                                      ;; Trigger reconnection if not intentional close
                                      (when (and (not was-closing?) reconnect-enabled?)
                                        (tel/event! ::connection-lost {:will-reconnect true})
                                        (reconnect-with-backoff! state))))
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
        (let [reconnect-enabled? (get-in @state [:config :reconnect :enabled] false)]
          (if reconnect-enabled?
            (reconnect-with-backoff! state)
            (update-state! state {:status :failed})))
        nil))))

(defn- update-uri-port!
  "Update URI with new port from port-file (for ephemeral port servers)"
  [state]
  (when-let [port-file-fn (get-in @state [:config :reconnect :port-file-fn])]
    (try
      (let [new-port (port-file-fn)
            current-uri (get-in @state [:config :uri])
            uri-parts (re-matches #"ws://([^:]+):(\d+)(.*)" current-uri)]
        (when uri-parts
          (let [[_ host old-port path] uri-parts
                old-port-int (Integer/parseInt old-port)]
            (when (not= new-port old-port-int)
              (let [new-uri (str "ws://" host ":" new-port path)]
                (tel/event! ::port-changed {:old-port old-port-int
                                            :new-port new-port
                                            :old-uri current-uri
                                            :new-uri new-uri})
                ;; Update URI in state
                (swap! state assoc-in [:config :uri] new-uri)
                true)))))
      (catch Exception e
        (tel/error! "Failed to read port file for reconnection" {:error e})
        false))))

(defn- reconnect-with-backoff!
  "Attempt reconnection with exponential backoff and jitter"
  [state]
  (let [config (:config @state)
        current-attempt (:reconnect-attempt @state)
        max-attempts (get-in config [:reconnect :max-attempts] 5)
        initial-delay (get-in config [:reconnect :initial-delay-ms] 1000)
        max-delay (get-in config [:reconnect :max-delay-ms] 30000)
        multiplier (get-in config [:reconnect :backoff-multiplier] 2)]

    (if (>= current-attempt max-attempts)
      ;; Exceeded max attempts - give up
      (do
        (tel/error! "Max reconnect attempts exceeded" {:attempts current-attempt})
        (update-state! state {:status :failed}))

      ;; Calculate delay with exponential backoff and jitter
      (let [base-delay (* initial-delay (Math/pow multiplier current-attempt))
            capped-delay (min base-delay max-delay)
            jitter (* capped-delay 0.25 (- (rand) 0.5))  ; ±25% randomness
            actual-delay (long (+ capped-delay jitter))]

        (tel/event! ::reconnect-scheduled {:attempt (inc current-attempt)
                                           :delay-ms actual-delay})
        (update-state! state {:status :reconnecting
                              :reconnect-attempt (inc current-attempt)})

        ;; Schedule reconnection attempt
        (future
          (try
            (Thread/sleep actual-delay)
            (tel/event! ::reconnect-attempt {:attempt (inc current-attempt)})
            ;; Check if port has changed (for ephemeral port servers)
            (update-uri-port! state)
            (connect-internal! state)
            (catch Exception e
              (tel/error! "Reconnection attempt failed" {:error e :attempt (inc current-attempt)}))))))))

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
                :backoff-multiplier 2
                :port-file-fn nil}  ; (fn [] port) - Read port file on reconnect
                                     ; For ephemeral port servers that may restart
                                     ; with different port. If provided, will be
                                     ; called before each reconnect attempt to
                                     ; get the current port and update URI if changed.

  Returns client handle with methods:
    :connect! - Initiate connection
    :disconnect! - Graceful close
    :send! - Send message (validates state)
    :subscribe! - Subscribe to channel (Phase 6d)
    :unsubscribe! - Unsubscribe from channel (Phase 6d)
    :get-state - Get current state keyword
    :get-full-state - Get full state atom value
    :get-subscriptions - Get set of subscribed channel IDs"
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
     :get-full-state (fn [] @state)
     :subscribe! (fn [channel-id]
                   (tel/event! ::client-subscribe-requested {:channel channel-id})
                   (if-let [ws (:ws @state)]
                     (if (= :open (:status @state))
                       (do
                         ;; Add to subscriptions set
                         (swap! state update :subscriptions conj channel-id)
                         ;; Send subscribe message
                         (ws/send! ws (json/generate-string
                                       {:type "subscribe"
                                        :channel-id channel-id}))
                         true)
                       (do
                         (tel/error! "Cannot subscribe - connection not open"
                                     {:status (:status @state) :channel channel-id})
                         false))
                     (do
                       (tel/error! "Cannot subscribe - no active connection"
                                   {:channel channel-id})
                       false)))
     :unsubscribe! (fn [channel-id]
                     (tel/event! ::client-unsubscribe-requested {:channel channel-id})
                     (if-let [ws (:ws @state)]
                       (if (= :open (:status @state))
                         (do
                           ;; Remove from subscriptions set
                           (swap! state update :subscriptions disj channel-id)
                           ;; Send unsubscribe message
                           (ws/send! ws (json/generate-string
                                         {:type "unsubscribe"
                                          :channel-id channel-id}))
                           true)
                         (do
                           (tel/error! "Cannot unsubscribe - connection not open"
                                       {:status (:status @state) :channel channel-id})
                           false))
                       (do
                         (tel/error! "Cannot unsubscribe - no active connection"
                                     {:channel channel-id})
                         false)))
     :get-subscriptions (fn [] (:subscriptions @state))}))
