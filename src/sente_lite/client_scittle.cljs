(ns sente-lite.client-scittle
  "Lightweight WebSocket client for Scittle/browser with Sente-compatible wire format.

  Provides sente-like API for browser environments:
  - Native WebSocket (no dependencies)
  - Sente-compatible wire format: [event-id data]
  - Simple callback-based API (no core.async)
  - Automatic reconnection with backoff

  Usage:
    (require '[sente-lite.client-scittle :as sente])

    (def client (sente/make-client!
                  {:url \"ws://localhost:3000/ws\"
                   :on-message (fn [event-id data] (println \"Received:\" event-id data))
                   :on-open (fn [uid] (println \"Connected as\" uid))
                   :on-close (fn [event] (println \"Disconnected\"))}))

    (sente/send! client :my/event {:data \"value\"})
    (sente/subscribe! client \"my-channel\")
    (sente/close! client)
  
  NOTE: SCI/Scittle requires macros to be referred directly, not namespace-qualified."
  (:require [taoensso.trove :as trove :refer [log!]]
            [sente-lite.packer :as packer]))

;; Event IDs (Sente-compatible)
(def ^:const event-handshake :chsk/handshake)
(def ^:const event-ws-ping :chsk/ws-ping)
(def ^:const event-ws-pong :chsk/ws-pong)
(def ^:const event-subscribe :sente-lite/subscribe)
(def ^:const event-unsubscribe :sente-lite/unsubscribe)
(def ^:const event-publish :sente-lite/publish)

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
   :uid nil                    ; Server-assigned user ID from handshake
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
        config (:config client-state)]
    (log! {:level :debug
           :id :sente-lite.client/ws-connected
           :data {:client-id client-id
                  :url (:url config)
                  :ready-state (.. event -target -readyState)}})
    (swap! clients assoc-in [client-id :status] :connected)
    ;; Note: on-open/on-reconnect callbacks are called after handshake in handle-message,
    ;; not here, so the user gets the uid from the server.
    ))

(defn- parse-message
  "Parse message - expects EDN event vector format [event-id data]"
  [raw-data]
  (try
    (let [parsed (packer/unpack raw-data)]
      (if (vector? parsed)
        {:event-id (first parsed)
         :data (second parsed)}
        {:error :invalid-format :raw raw-data}))
    (catch js/Error e
      (log! {:level :warn
             :id :sente-lite.client/parse-failed
             :data {:raw-data raw-data
                    :error (.-message e)}})
      {:error :parse-failed :raw raw-data})))

(defn- send-raw!
  "Send an event vector directly"
  [ws event]
  (.send ws (packer/pack event)))

(defn- handle-handshake
  "Handle :chsk/handshake event - extract uid and store it"
  [client-id data ws]
  (let [uid (first data)
        csrf-token (second data)]
    (swap! clients assoc-in [client-id :uid] uid)
    (log! {:level :info
           :id :sente-lite.client/handshake-received
           :data {:client-id client-id
                  :uid uid
                  :has-csrf (some? csrf-token)}})
    uid))

(defn- handle-message [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        ws (:ws client-state)
        raw-data (.-data event)
        parsed (parse-message raw-data)]

    (swap! clients update-in [client-id :message-count-received] inc)

    (if (:error parsed)
      (log! {:level :warn
             :id :sente-lite.client/msg-error
             :data {:client-id client-id
                    :error (:error parsed)}})
      (let [event-id (:event-id parsed)
            data (:data parsed)]
        (log! {:level :trace
               :id :sente-lite.client/msg-recv
               :data {:client-id client-id
                      :event-id event-id
                      :message-size (.-length raw-data)}})

        (cond
          ;; Handle handshake
          (= event-id event-handshake)
          (let [uid (handle-handshake client-id data ws)
                is-reconnect? (> (:reconnect-count client-state) 0)]
            (if is-reconnect?
              (when-let [on-reconnect (:on-reconnect config)]
                (log! {:level :trace
                       :id :sente-lite.client/callback-on-reconnect
                       :data {:client-id client-id :uid uid}})
                (on-reconnect))
              (when-let [on-open (:on-open config)]
                (log! {:level :trace
                       :id :sente-lite.client/callback-on-open
                       :data {:client-id client-id :uid uid}})
                (on-open uid))))

          ;; Handle server ping -> respond with pong
          (= event-id event-ws-ping)
          (do
            (log! {:level :trace
                   :id :sente-lite.client/auto-pong
                   :data {:client-id client-id}})
            (send-raw! ws [event-ws-pong]))

          ;; Pass all other events to user handler
          :else
          (when-let [on-message (:on-message config)]
            (on-message event-id data)))))))

(defn- handle-error [client-state event]
  (let [client-id (:id client-state)
        ws (.-target event)]
    (log! {:level :error
           :id :sente-lite.client/ws-error
           :data {:client-id client-id
                  :ready-state (.-readyState ws)}})))

(declare attempt-reconnect!)  ; forward declaration

(defn- handle-close [client-state event]
  (let [client-id (:id client-state)]
    ;; Only process if client still exists (not removed by close!)
    (when (get @clients client-id)
      (let [config (:config client-state)
            code (.-code event)
            reason (.-reason event)
            was-clean (.-wasClean event)
            reconnect-enabled? (:reconnect-enabled? client-state)]
        (swap! clients assoc-in [client-id :status] :disconnected)
        (log! {:level :debug
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
            (log! {:level :debug
                   :id :sente-lite.client/reconnect-scheduled
                   :data {:client-id client-id
                          :delay-ms delay-ms
                          :reconnect-count reconnect-count}})
            (js/setTimeout #(attempt-reconnect! client-id) delay-ms)))))))

;;; Reconnection Logic

(defn- attempt-reconnect! [client-id]
  (when-let [client-state (get @clients client-id)]
    (when (:reconnect-enabled? client-state)
      (let [config (:config client-state)
            url (:url config)
            reconnect-count (:reconnect-count client-state)
            new-reconnect-count (inc reconnect-count)]

        (log! {:level :debug
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

            (log! {:level :trace
                   :id :sente-lite.client/reconnect-initiated
                   :data {:client-id client-id}}))

          (catch js/Error e
            (log! {:level :error
                   :id :sente-lite.client/reconnect-failed
                   :data {:client-id client-id
                          :error (.-message e)
                          :reconnect-count new-reconnect-count}})
            ;; Failed reconnect - try again after delay if still enabled
            (when (get-in @clients [client-id :reconnect-enabled?])
              (let [retry-delay (get-in @clients [client-id :reconnect-delay])]
                (log! {:level :debug
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

    (log! {:level :debug
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

    (log! {:level :trace
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
        (let [serialized (packer/pack message)]
          (.send ws serialized)
          (swap! clients update-in [client-id :message-count-sent] inc)
          (log! {:level :trace
                 :id :sente-lite.client/msg-sent
                 :data {:client-id client-id
                        :message-type (first message)
                        :size (count serialized)}})
          true)
        (do
          (log! {:level :warn
                 :id :sente-lite.client/send-failed
                 :data {:client-id client-id
                        :ready-state ready-state
                        :status (:status client-state)}})
          false)))
    (do
      (log! {:level :error
             :id :sente-lite.client/invalid-client-id
             :data {:client-id client-id}})
      false)))

(defn close!
  "Close WebSocket connection gracefully."
  [client-id]
  (if-let [client-state (get @clients client-id)]
    (let [ws (:ws client-state)]
      ;; Remove client from registry FIRST to prevent on-close from re-adding
      (swap! clients dissoc client-id)
      (when ws
        (log! {:level :debug
               :id :sente-lite.client/closing
               :data {:client-id client-id}})
        (.close ws))
      true)
    (do
      (log! {:level :warn
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
      (log! {:level :debug
             :id :sente-lite.client/reconnect-setting-updated
             :data {:client-id client-id
                    :enabled? enabled?}})
      true)
    (do
      (log! {:level :warn
             :id :sente-lite.client/reconnect-setting-failed
             :data {:client-id client-id}})
      false)))

;;; Channel/Pub-Sub API (event vector format)

(defn subscribe!
  "Subscribe to a channel. Returns true if message was sent.
  
  Example:
    (subscribe! client \"my-channel\")"
  [client-id channel-id]
  (send! client-id [event-subscribe {:channel-id channel-id}]))

(defn unsubscribe!
  "Unsubscribe from a channel. Returns true if message was sent.
  
  Example:
    (unsubscribe! client \"my-channel\")"
  [client-id channel-id]
  (send! client-id [event-unsubscribe {:channel-id channel-id}]))

(defn publish!
  "Publish a message to a channel. Returns true if message was sent.
  
  Example:
    (publish! client \"my-channel\" {:msg \"Hello!\"})
    (publish! client \"my-channel\" {:msg \"Hello!\"} :exclude-sender? true)"
  [client-id channel-id data & {:keys [exclude-sender?] :or {exclude-sender? false}}]
  (send! client-id [event-publish {:channel-id channel-id
                                   :data data
                                   :exclude-sender? exclude-sender?}]))

(defn get-uid
  "Get the server-assigned user ID for this client.
  Returns nil if not yet connected or handshake not received."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (:uid client-state)))
