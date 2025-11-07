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
  (:require [telemere-lite.core :as tel]))

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

;;; Telemetry - uses Trove event ID pattern (:sente-lite.client/*)

;;; WebSocket Lifecycle

(defn- handle-open [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        is-reconnect? (> (:reconnect-count client-state) 0)]
    (tel/log! {:level :debug
               :id (if is-reconnect? :sente-lite.client/reconnected :sente-lite.client/connected)
               :data {:client-id client-id
                      :url (:url config)
                      :ready-state (.. event -target -readyState)
                      :reconnect-count (:reconnect-count client-state)}})
    (swap! clients assoc-in [client-id :status] :connected)

    ;; Call appropriate callback
    (if is-reconnect?
      (when-let [on-reconnect (:on-reconnect config)]
        (tel/log! {:level :trace
                   :id :sente-lite.client/callback-on-reconnect
                   :data {:client-id client-id}})
        (on-reconnect))
      (when-let [on-open (:on-open config)]
        (tel/log! {:level :trace
                   :id :sente-lite.client/callback-on-open
                   :data {:client-id client-id}})
        (on-open)))))

(defn- parse-message
  "Parse message - expects EDN format for now"
  [raw-data]
  (try
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (read-string raw-data)  ; read-string available in Scittle via SCI
    (catch js/Error e
      (tel/log! {:level :warn
                 :id :sente-lite.client/parse-failed
                 :data {:raw-data raw-data
                        :error (.-message e)}})
      {:error :parse-failed :raw raw-data})))

(defn- handle-message [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        raw-data (.-data event)
        parsed-msg (parse-message raw-data)]
    (swap! clients update-in [client-id :message-count-received] inc)
    (tel/log! {:level :trace
               :id :sente-lite.client/msg-recv
               :data {:client-id client-id
                      :message-size (.-length raw-data)
                      :message-type (first parsed-msg)}})
    (when-let [on-message (:on-message config)]
      (on-message parsed-msg))))

(defn- handle-error [client-state event]
  (let [client-id (:id client-state)
        ws (.-target event)]
    (tel/log! {:level :error
               :id :sente-lite.client/ws-error
               :data {:client-id client-id
                      :ready-state (.-readyState ws)}})))

(declare attempt-reconnect!)  ; forward declaration

(defn- handle-close [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        code (.-code event)
        reason (.-reason event)
        was-clean (.-wasClean event)
        reconnect-enabled? (:reconnect-enabled? client-state)]
    (swap! clients assoc-in [client-id :status] :disconnected)
    (tel/log! {:level :debug
               :id :sente-lite.client/disconnected
               :data {:client-id client-id
                      :code code
                      :reason reason
                      :was-clean was-clean
                      :will-reconnect? reconnect-enabled?}})
    (when-let [on-close (:on-close config)]
      (on-close event))

    ;; Auto-reconnect if enabled
    (when reconnect-enabled?
      (let [current-client-state (get @clients client-id)
            delay-ms (:reconnect-delay current-client-state)
            reconnect-count (:reconnect-count current-client-state)]
        (tel/log! {:level :debug
                   :id :sente-lite.client/reconnect-scheduled
                   :data {:client-id client-id
                          :delay-ms delay-ms
                          :reconnect-count reconnect-count}})
        (js/setTimeout #(attempt-reconnect! client-id) delay-ms)))))

;;; Reconnection Logic

(defn- attempt-reconnect! [client-id]
  (when-let [client-state (get @clients client-id)]
    (when (:reconnect-enabled? client-state)
      (let [config (:config client-state)
            url (:url config)
            reconnect-count (:reconnect-count client-state)
            new-reconnect-count (inc reconnect-count)]

        (tel/log! {:level :debug
                   :id :sente-lite.client/reconnect-attempt
                   :data {:client-id client-id
                          :reconnect-count new-reconnect-count
                          :url url}})

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

            (tel/log! {:level :trace
                       :id :sente-lite.client/reconnect-initiated
                       :data {:client-id client-id}}))

          (catch js/Error e
            (tel/log! {:level :error
                       :id :sente-lite.client/reconnect-failed
                       :data {:client-id client-id
                              :error (.-message e)
                              :reconnect-count new-reconnect-count}})
            ;; Failed reconnect - try again after delay if still enabled
            (when (get-in @clients [client-id :reconnect-enabled?])
              (let [retry-delay (get-in @clients [client-id :reconnect-delay])]
                (tel/log! {:level :debug
                           :id :sente-lite.client/reconnect-retry
                           :data {:client-id client-id
                                  :retry-delay retry-delay}})
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

    (tel/log! {:level :debug
               :id :sente-lite.client/creating
               :data {:client-id client-id
                      :url url
                      :initial-state (.-readyState ws)}})

    ;; Store client state
    (swap! clients assoc client-id (assoc client-state :ws ws))

    ;; Setup handlers
    (set! (.-onopen ws) (partial handle-open (get @clients client-id)))
    (set! (.-onmessage ws) (partial handle-message (get @clients client-id)))
    (set! (.-onerror ws) (partial handle-error (get @clients client-id)))
    (set! (.-onclose ws) (partial handle-close (get @clients client-id)))

    (tel/log! {:level :trace
               :id :sente-lite.client/handlers-attached
               :data {:client-id client-id}})

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
          (tel/log! {:level :trace
                     :id :sente-lite.client/msg-sent
                     :data {:client-id client-id
                            :message-type (first message)
                            :size (count serialized)}})
          true)
        (do
          (tel/log! {:level :warn
                     :id :sente-lite.client/send-failed
                     :data {:client-id client-id
                            :ready-state ready-state
                            :status (:status client-state)}})
          false)))
    (do
      (tel/log! {:level :error
                 :id :sente-lite.client/invalid-client-id
                 :data {:client-id client-id}})
      false)))

(defn close!
  "Close WebSocket connection gracefully."
  [client-id]
  (if-let [client-state (get @clients client-id)]
    (let [ws (:ws client-state)]
      (tel/log! {:level :debug
                 :id :sente-lite.client/closing
                 :data {:client-id client-id}})
      (.close ws)
      (swap! clients dissoc client-id)
      true)
    (do
      (tel/log! {:level :warn
                 :id :sente-lite.client/close-failed
                 :data {:client-id client-id}})
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
      (tel/log! {:level :debug
                 :id :sente-lite.client/reconnect-setting-updated
                 :data {:client-id client-id
                        :enabled? enabled?}})
      true)
    (do
      (tel/log! {:level :warn
                 :id :sente-lite.client/reconnect-setting-failed
                 :data {:client-id client-id}})
      false)))
