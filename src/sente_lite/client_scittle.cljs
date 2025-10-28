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
  (:require [telemere-lite.scittle :as tel]
            [cljs.reader]))

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
        config (:config client-state)]
    (log-client-event! client-id "connected"
                       {:url (:url config)
                        :ready-state (.. event -target -readyState)})
    (swap! clients assoc-in [client-id :status] :connected)
    (when-let [on-open (:on-open config)]
      (on-open))))

(defn- parse-message
  "Parse message - expects EDN format for now"
  [raw-data]
  (try
    (cljs.reader/read-string raw-data)
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

(defn- handle-close [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        code (.-code event)
        reason (.-reason event)
        was-clean (.-wasClean event)]
    (swap! clients assoc-in [client-id :status] :disconnected)
    (log-client-event! client-id "disconnected"
                       {:code code
                        :reason reason
                        :was-clean was-clean})
    (when-let [on-close (:on-close config)]
      (on-close event))))

;;; Public API

(defn make-client!
  "Create and connect a WebSocket client with telemetry.

  Config options:
    :url          - WebSocket URL (required, e.g. \"ws://localhost:3000/ws\")
    :on-open      - Called when connection established
    :on-message   - Called with parsed message (fn [msg])
    :on-close     - Called when connection closes (fn [event])
    :on-error     - Called on error (fn [event])

  Returns client handle for send!/close! operations."
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
