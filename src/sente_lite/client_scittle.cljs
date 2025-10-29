(ns sente-lite.client-scittle
  "Lightweight WebSocket client for Scittle/browser with telemere-lite integration

  Provides sente-like API for browser environments:
  - Native WebSocket (no dependencies)
  - Structured telemetry via telemere-lite.scittle
  - Simple callback-based API (no core.async)
  - Automatic reconnection with backoff

  Usage:
    (require '[sente-lite.client-scittle :as sente]
             '[telemere-lite.scittle :as tel])

    (tel/startup!)

    (def client (sente/make-client!
                  {:url \"ws://localhost:3000/ws\"
                   :on-message (fn [msg] (println \"Received:\" msg))
                   :on-open (fn [] (println \"Connected!\"))
                   :on-close (fn [event] (println \"Disconnected\"))}))

    (sente/send! client [:my/event {:data \"value\"}])
    (sente/close! client)"
  (:require [telemere-lite.scittle :as tel]))

;;; State Management

(defonce ^:private clients (atom {}))  ; client-id -> client-state

(defn- generate-client-id []
  (str "client-" (.now js/Date) "-" (rand-int 10000)))

;;; Client State

(defn- make-client-state [config]
  {:id (generate-client-id)
   :config config
   :ws nil
   :status :disconnected
   :reconnect-count 0
   :reconnect-enabled? (get config :auto-reconnect? true)  ; default true
   :reconnect-delay (get config :reconnect-delay 1000)     ; default 1s
   :max-reconnect-delay (get config :max-reconnect-delay 30000)  ; default 30s
   :last-connect-attempt nil
   :message-count-sent 0
   :message-count-received 0})

;;; Telemetry Helpers

(defn- log-client-event! [client-id event-type data]
  (tel/info! (str "Client " event-type)
             (assoc data
                    :client-id client-id
                    :timestamp (.now js/Date))))

(defn- log-client-error! [client-id error-type data]
  (tel/error! (str "Client " error-type)
              (assoc data
                     :client-id client-id
                     :timestamp (.now js/Date))))

;;; WebSocket Lifecycle

(defn- handle-open [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        is-reconnect? (> (:reconnect-count client-state) 0)]
    (log-client-event! client-id (if is-reconnect? "reconnected" "connected")
                       {:url (:url config)
                        :ready-state (.. event -target -readyState)
                        :reconnect-count (:reconnect-count client-state)})
    (swap! clients assoc-in [client-id :status] :connected)

    ;; Call appropriate callback
    (if is-reconnect?
      (when-let [on-reconnect (:on-reconnect config)]
        (tel/info! "Calling :on-reconnect callback" {:client-id client-id})
        (on-reconnect))
      (when-let [on-open (:on-open config)]
        (tel/info! "Calling :on-open callback" {:client-id client-id})
        (on-open)))))

(defn- parse-message
  "Parse message - expects EDN format for now"
  [raw-data]
  (try
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (read-string raw-data)  ; read-string available in Scittle via SCI
    (catch js/Error e
      (tel/warn! "Failed to parse message" {:raw-data raw-data :error (.-message e)})
      {:error :parse-failed :raw raw-data})))

(defn- handle-message [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        raw-data (.-data event)
        parsed-msg (parse-message raw-data)]
    (swap! clients update-in [client-id :message-count-received] inc)
    (log-client-event! client-id "message-received"
                       {:message-size (.-length raw-data)
                        :message-type (first parsed-msg)})
    (when-let [on-message (:on-message config)]
      (on-message parsed-msg))))

(defn- handle-error [client-state event]
  (let [client-id (:id client-state)
        ws (.-target event)]
    (log-client-error! client-id "error"
                       {:ready-state (.-readyState ws)
                        :event event})))

(declare attempt-reconnect!)  ; forward declaration

(defn- handle-close [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        code (.-code event)
        reason (.-reason event)
        was-clean (.-wasClean event)
        reconnect-enabled? (:reconnect-enabled? client-state)]
    (swap! clients assoc-in [client-id :status] :disconnected)
    (log-client-event! client-id "disconnected"
                       {:code code
                        :reason reason
                        :was-clean was-clean
                        :will-reconnect? reconnect-enabled?})
    (when-let [on-close (:on-close config)]
      (on-close event))

    ;; Auto-reconnect if enabled
    (when reconnect-enabled?
      (let [current-client-state (get @clients client-id)
            delay-ms (:reconnect-delay current-client-state)
            reconnect-count (:reconnect-count current-client-state)]
        (tel/info! "Scheduling reconnect"
                   {:client-id client-id
                    :delay-ms delay-ms
                    :reconnect-count reconnect-count})
        (js/setTimeout #(attempt-reconnect! client-id) delay-ms)))))

;;; Reconnection Logic

(defn- attempt-reconnect! [client-id]
  (when-let [client-state (get @clients client-id)]
    (when (:reconnect-enabled? client-state)
      (let [config (:config client-state)
            url (:url config)
            reconnect-count (:reconnect-count client-state)
            new-reconnect-count (inc reconnect-count)]

        (tel/info! "Attempting reconnection"
                   {:client-id client-id
                    :reconnect-count new-reconnect-count
                    :url url})

        (try
          ;; Increment reconnect count BEFORE creating WebSocket
          (swap! clients update-in [client-id :reconnect-count] inc)

          ;; Calculate exponential backoff for NEXT reconnection
          (let [base-delay (:reconnect-delay client-state)
                max-delay (:max-reconnect-delay client-state)
                next-delay (min (* base-delay (Math/pow 2 new-reconnect-count)) max-delay)]
            (swap! clients assoc-in [client-id :reconnect-delay] next-delay))

          ;; Create new WebSocket
          (let [ws (js/WebSocket. url)
                updated-client-state (get @clients client-id)]

            ;; Store new ws in client state
            (swap! clients assoc-in [client-id :ws] ws)

            ;; Setup handlers with updated client state
            (set! (.-onopen ws) (partial handle-open updated-client-state))
            (set! (.-onmessage ws) (partial handle-message updated-client-state))
            (set! (.-onerror ws) (partial handle-error updated-client-state))
            (set! (.-onclose ws) (partial handle-close updated-client-state))

            (tel/debug! "Reconnection attempt initiated" {:client-id client-id}))

          (catch js/Error e
            (log-client-error! client-id "reconnection-failed"
                               {:error (.-message e)
                                :reconnect-count new-reconnect-count})
            ;; Failed reconnect - try again after delay if still enabled
            (when (get-in @clients [client-id :reconnect-enabled?])
              (let [retry-delay (get-in @clients [client-id :reconnect-delay])]
                (tel/info! "Scheduling retry after error"
                           {:client-id client-id
                            :retry-delay retry-delay})
                (js/setTimeout #(attempt-reconnect! client-id) retry-delay)))))))))

;;; Public API

(defn make-client!
  "Create and connect a WebSocket client with auto-reconnect and telemetry.

  Config options:
    :url                  - WebSocket URL (required, e.g. \"ws://localhost:3000/ws\")
    :on-open              - Called on initial connection (fn [])
    :on-reconnect         - Called after reconnection (fn [])
    :on-message           - Called with parsed message (fn [msg])
    :on-close             - Called when connection closes (fn [event])
    :on-error             - Called on error (fn [event])
    :auto-reconnect?      - Enable auto-reconnect (default: true)
    :reconnect-delay      - Initial reconnect delay in ms (default: 1000)
    :max-reconnect-delay  - Maximum reconnect delay in ms (default: 30000)

  Returns client-id handle for send!/close!/set-reconnect! operations."
  [config]
  (let [client-state (make-client-state config)
        client-id (:id client-state)
        url (:url config)
        ws (js/WebSocket. url)]

    (tel/info! "Creating WebSocket client"
               {:client-id client-id
                :url url
                :initial-state (.-readyState ws)})

    ;; Store client state
    (swap! clients assoc client-id (assoc client-state :ws ws))

    ;; Setup handlers
    (set! (.-onopen ws) (partial handle-open (get @clients client-id)))
    (set! (.-onmessage ws) (partial handle-message (get @clients client-id)))
    (set! (.-onerror ws) (partial handle-error (get @clients client-id)))
    (set! (.-onclose ws) (partial handle-close (get @clients client-id)))

    (tel/debug! "Client handlers attached" {:client-id client-id})

    ;; Return client-id as handle
    client-id))

(defn send!
  "Send message through client. Message should be EDN-serializable.

  Example:
    (send! client [:my/event {:data \"value\"}])"
  [client-id message]
  (if-let [client-state (get @clients client-id)]
    (let [ws (:ws client-state)
          ready-state (.-readyState ws)]
      (if (= ready-state 1) ; WebSocket.OPEN = 1
        (let [serialized (pr-str message)]
          (.send ws serialized)
          (swap! clients update-in [client-id :message-count-sent] inc)
          (tel/info! "Message sent"
                     {:client-id client-id
                      :message-type (first message)
                      :size (count serialized)})
          true)
        (do
          (tel/warn! "Cannot send - connection not open"
                     {:client-id client-id
                      :ready-state ready-state
                      :status (:status client-state)})
          false)))
    (do
      (tel/error! "Invalid client-id" {:client-id client-id})
      false)))

(defn close!
  "Close WebSocket connection gracefully."
  [client-id]
  (if-let [client-state (get @clients client-id)]
    (let [ws (:ws client-state)]
      (tel/info! "Closing client" {:client-id client-id})
      (.close ws)
      (swap! clients dissoc client-id)
      true)
    (do
      (tel/warn! "Cannot close - invalid client-id" {:client-id client-id})
      false)))

(defn get-status
  "Get current client status. Returns :connected, :disconnected, or nil if invalid client-id."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (:status client-state)))

(defn get-stats
  "Get client statistics including message counts."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    {:client-id client-id
     :status (:status client-state)
     :messages-sent (:message-count-sent client-state)
     :messages-received (:message-count-received client-state)
     :reconnect-count (:reconnect-count client-state)}))

(defn list-clients
  "List all active client IDs."
  []
  (keys @clients))

(defn set-reconnect!
  "Enable or disable auto-reconnect for a client.
  Useful for stopping reconnection attempts when shutting down."
  [client-id enabled?]
  (if-let [client-state (get @clients client-id)]
    (do
      (swap! clients assoc-in [client-id :reconnect-enabled?] enabled?)
      (tel/info! "Reconnect setting updated"
                 {:client-id client-id
                  :enabled? enabled?})
      true)
    (do
      (tel/warn! "Cannot set reconnect - invalid client-id" {:client-id client-id})
      false)))
