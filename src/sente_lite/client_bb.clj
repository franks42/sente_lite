(ns sente-lite.client-bb
  "Lightweight WebSocket client for Babashka with Sente-compatible wire format.

  Provides sente-like API for BB environments:
  - Uses babashka.http-client.websocket
  - Sente-compatible wire format: [event-id data]
  - Simple callback-based API (no core.async)
  - Automatic reconnection with backoff

  Usage:
    (require '[sente-lite.client-bb :as sente])

    (def client (sente/make-client!
                  {:url \"ws://localhost:3000/ws\"
                   :on-message (fn [event-id data] (println \"Received:\" event-id data))
                   :on-open (fn [uid] (println \"Connected as\" uid))
                   :on-close (fn [code reason] (println \"Disconnected\"))}))

    (sente/send! client [:my/event {:data \"value\"}])
    (sente/subscribe! client \"my-channel\")
    (sente/close! client)"
  (:require [babashka.http-client.websocket :as ws]
            [sente-lite.packer :as packer]
            [sente-lite.queue :as q]
            [sente-lite.queue-bb :as qbb]
            [taoensso.trove :as trove]))

;; Event IDs (Sente-compatible)
(def ^:const event-handshake :chsk/handshake)
(def ^:const event-ws-ping :chsk/ws-ping)
(def ^:const event-ws-pong :chsk/ws-pong)
(def ^:const event-subscribe :sente-lite/subscribe)
(def ^:const event-unsubscribe :sente-lite/unsubscribe)
(def ^:const event-publish :sente-lite/publish)

(defn- maybe-unwrap-recv
  [event-id data config]
  (let [wrap? (get config :wrap-recv-evs? false)]
    (cond
      (and (not wrap?)
           (= event-id :chsk/recv)
           (vector? data)
           (keyword? (first data)))
      [(first data) (second data)]

      (and wrap?
           (not= event-id :chsk/recv)
           (not (.startsWith (str event-id) ":chsk/")))
      [:chsk/recv [event-id data]]

      :else
      [event-id data])))

;;; State Management

(defonce ^:private clients (atom {}))  ; client-id -> client-state

(defn- generate-client-id []
  (str "client-" (System/currentTimeMillis) "-" (rand-int 10000)))

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
   :message-count-received 0
   :send-queue nil})

;;; Message Parsing
;; IMPORTANT: Babashka's babashka.http-client.websocket passes a java.nio.HeapCharBuffer
;; to on-message, NOT a String like JVM's org.java-websocket/Java-WebSocket does.
;; Must convert with (str raw-data) before parsing.

(defn- parse-message
  "Parse message - expects EDN event vector format [event-id data]"
  [raw-data]
  (try
    (let [data-str (str raw-data)  ; CharBuffer → String (required for BB websocket)
          parsed (packer/unpack data-str)]
      (if (vector? parsed)
        {:event-id (first parsed)
         :data (second parsed)}
        {:error :invalid-format :raw data-str}))
    (catch Exception e
      (trove/log! {:level :warn
                   :id :sente-lite.client/parse-failed
                   :data {:raw-data (str raw-data)
                          :error (.getMessage e)}})
      {:error :parse-failed :raw (str raw-data)})))

;;; Forward declarations
(declare attempt-reconnect!)

;;; WebSocket Lifecycle Handlers

(defn- handle-handshake
  "Handle :chsk/handshake event - extract uid and store it"
  [client-id data]
  (let [uid (first data)
        csrf-token (second data)]
    (swap! clients assoc-in [client-id :uid] uid)
    (trove/log! {:level :info
                 :id :sente-lite.client/handshake-received
                 :data {:client-id client-id
                        :uid uid
                        :has-csrf (some? csrf-token)}})
    uid))

(defn- make-on-open [client-id]
  (fn [ws]
    (let [client-state (get @clients client-id)
          config (:config client-state)]
      (trove/log! {:level :debug
                   :id :sente-lite.client/ws-connected
                   :data {:client-id client-id
                          :url (:url config)}})
      (swap! clients assoc-in [client-id :status] :connected)
      (swap! clients assoc-in [client-id :ws] ws)
      ;; Note: on-open callback is called after handshake in make-on-message,
      ;; not here, so the user gets the uid from the server.
      )))

(defn- make-on-message [client-id]
  (fn [ws raw-data _last?]
    (let [client-state (get @clients client-id)
          config (:config client-state)
          ws (:ws client-state)
          parsed (parse-message raw-data)]

      (swap! clients update-in [client-id :message-count-received] inc)

      (if (:error parsed)
        (trove/log! {:level :warn
                     :id :sente-lite.client/msg-error
                     :data {:client-id client-id
                            :error (:error parsed)}})
        (let [[event-id data] (maybe-unwrap-recv (:event-id parsed) (:data parsed) config)]
          (trove/log! {:level :trace
                       :id :sente-lite.client/msg-recv
                       :data {:client-id client-id
                              :event-id event-id}})

          (cond
            ;; Handle handshake
            (= event-id event-handshake)
            (let [uid (handle-handshake client-id data)
                  ;; Get current reconnect-count from atom, not captured state
                  current-reconnect-count (get-in @clients [client-id :reconnect-count] 0)
                  is-reconnect? (> current-reconnect-count 0)]
              (if is-reconnect?
                (when-let [on-reconnect (:on-reconnect config)]
                  (trove/log! {:level :trace
                               :id :sente-lite.client/callback-on-reconnect
                               :data {:client-id client-id :uid uid :reconnect-count current-reconnect-count}})
                  (on-reconnect))
                (when-let [on-open (:on-open config)]
                  (trove/log! {:level :trace
                               :id :sente-lite.client/callback-on-open
                               :data {:client-id client-id :uid uid}})
                  (on-open uid))))

            ;; Handle server ping -> respond with pong
            (= event-id event-ws-ping)
            (do
              (trove/log! {:level :trace
                           :id :sente-lite.client/auto-pong
                           :data {:client-id client-id}})
              (ws/send! ws (packer/pack [event-ws-pong])))

            ;; Pass all other events to user handler
            :else
            (when-let [on-message (:on-message config)]
              (on-message event-id data))))))))

(defn- make-on-close [client-id]
  (fn [ws code reason]
    ;; Only process if client still exists (not removed by close!)
    (when-let [client-state (get @clients client-id)]
      (let [config (:config client-state)
            reconnect-enabled? (:reconnect-enabled? client-state)]
        (swap! clients assoc-in [client-id :status] :disconnected)
        (swap! clients assoc-in [client-id :ws] nil)
        (trove/log! {:level :debug
                     :id :sente-lite.client/disconnected
                     :data {:client-id client-id
                            :code code
                            :reason reason
                            :will-reconnect? reconnect-enabled?}})
        (when-let [on-close (:on-close config)]
          (on-close code reason))

        ;; Auto-reconnect if enabled
        (when reconnect-enabled?
          (let [current-client-state (get @clients client-id)
                delay-ms (:reconnect-delay current-client-state)
                reconnect-count (:reconnect-count current-client-state)]
            (trove/log! {:level :debug
                         :id :sente-lite.client/reconnect-scheduled
                         :data {:client-id client-id
                                :delay-ms delay-ms
                                :reconnect-count reconnect-count}})
            (future
              (Thread/sleep (long delay-ms))
              (attempt-reconnect! client-id))))))))

(defn- make-on-error [client-id]
  (fn [ws error]
    (trove/log! {:level :error
                 :id :sente-lite.client/ws-error
                 :data {:client-id client-id
                        :error (.getMessage error)}})))

;;; Connection Functions

(defn- connect-internal! [client-id]
  (let [client-state (get @clients client-id)
        config (:config client-state)
        url (:url config)]
    (try
      (ws/websocket {:uri url
                     :on-open (make-on-open client-id)
                     :on-message (make-on-message client-id)
                     :on-close (make-on-close client-id)
                     :on-error (make-on-error client-id)})
      (catch Exception e
        (trove/log! {:level :error
                     :id :sente-lite.client/connect-failed
                     :data {:client-id client-id
                            :url url
                            :error (.getMessage e)}})
        nil))))

(defn- attempt-reconnect! [client-id]
  (when-let [client-state (get @clients client-id)]
    (when (:reconnect-enabled? client-state)
      (let [config (:config client-state)
            url (:url config)
            reconnect-count (:reconnect-count client-state)
            new-reconnect-count (inc reconnect-count)]

        (trove/log! {:level :debug
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

          ;; Create new WebSocket - if it fails (returns nil), schedule retry
          (let [result (connect-internal! client-id)]
            (when (nil? result)
              ;; Connection failed (e.g., server not available) - schedule retry
              (when (get-in @clients [client-id :reconnect-enabled?])
                (let [retry-delay (get-in @clients [client-id :reconnect-delay])]
                  (trove/log! {:level :debug
                               :id :sente-lite.client/reconnect-retry-scheduled
                               :data {:client-id client-id
                                      :retry-delay retry-delay
                                      :reason :connection-failed}})
                  (future
                    (Thread/sleep (long retry-delay))
                    (attempt-reconnect! client-id))))))

          (catch Exception e
            (trove/log! {:level :error
                         :id :sente-lite.client/reconnect-failed
                         :data {:client-id client-id
                                :error (.getMessage e)
                                :reconnect-count new-reconnect-count}})
            ;; Failed reconnect - try again after delay if still enabled
            (when (get-in @clients [client-id :reconnect-enabled?])
              (let [retry-delay (get-in @clients [client-id :reconnect-delay])]
                (trove/log! {:level :debug
                             :id :sente-lite.client/reconnect-retry
                             :data {:client-id client-id
                                    :retry-delay retry-delay}})
                (future
                  (Thread/sleep (long retry-delay))
                  (attempt-reconnect! client-id))))))))))

;;; Public API

(defn- send-raw!
  "Internal: Send serialized message directly over WebSocket (bypassing queue)."
  [client-id serialized message]
  (if-let [client-state (get @clients client-id)]
    (let [ws (:ws client-state)]
      (if (and ws (= :connected (:status client-state)))
        (do
          (ws/send! ws serialized)
          (swap! clients update-in [client-id :message-count-sent] inc)
          (trove/log! {:level :trace
                       :id :sente-lite.client/msg-sent
                       :data {:client-id client-id
                              :message-type (first message)
                              :size (count serialized)}})
          true)
        (do
          (trove/log! {:level :warn
                       :id :sente-lite.client/send-failed
                       :data {:client-id client-id
                              :status (:status client-state)
                              :has-ws (some? ws)}})
          false)))
    false))

(defn make-client!
  "Create and connect a WebSocket client with auto-reconnect and telemetry.

  Config options:
    :url                  - WebSocket URL (required, e.g. \"ws://localhost:3000/ws\")
    :on-open              - Called on initial connection with uid (fn [uid])
    :on-reconnect         - Called after reconnection (fn [])
    :on-message           - Called with parsed message (fn [event-id data])
    :on-close             - Called when connection closes (fn [code reason])
    :on-error             - Called on error (fn [error])
    :auto-reconnect?      - Enable auto-reconnect (default: true)
    :reconnect-delay      - Initial reconnect delay in ms (default: 1000)
    :max-reconnect-delay  - Maximum reconnect delay in ms (default: 30000)
    :send-queue           - Send queue config map (optional):
                            {:max-depth 1000          ; max queued messages
                             :flush-interval-ms 10}   ; flush interval

  Returns client-id handle for send!/close!/set-reconnect! operations."
  [config]
  (let [client-state (make-client-state config)
        client-id (:id client-state)
        url (:url config)
        queue-config (:send-queue config)]

    (trove/log! {:level :debug
                 :id :sente-lite.client/creating
                 :data {:client-id client-id
                        :url url
                        :send-queue (some? queue-config)}})

    ;; Store client state
    (swap! clients assoc client-id client-state)

    ;; Create and start send queue if configured
    (when queue-config
      (let [queue (qbb/make-send-queue
                   (merge queue-config
                          {:on-send (fn [[serialized message]]
                                      (send-raw! client-id serialized message))
                           :on-error (fn [e [_ message]]
                                       (trove/log! {:level :error
                                                    :id :sente-lite.client/queue-send-error
                                                    :data {:client-id client-id
                                                           :message-type (first message)
                                                           :error (str e)}}))}))]
        (swap! clients assoc-in [client-id :send-queue] queue)
        (q/start! queue)
        (trove/log! {:level :debug
                     :id :sente-lite.client/queue-started
                     :data {:client-id client-id
                            :max-depth (:max-depth queue-config 1000)
                            :flush-interval-ms (:flush-interval-ms queue-config 10)}})))

    ;; Connect - if it fails and auto-reconnect is enabled, schedule retry
    (let [result (connect-internal! client-id)]
      (when (and (nil? result)
                 (:reconnect-enabled? client-state))
        (let [delay-ms (:reconnect-delay client-state)]
          (trove/log! {:level :debug
                       :id :sente-lite.client/initial-connect-failed-scheduling-retry
                       :data {:client-id client-id
                              :retry-delay delay-ms}})
          (future
            (Thread/sleep delay-ms)
            (attempt-reconnect! client-id)))))

    (trove/log! {:level :trace
                 :id :sente-lite.client/created
                 :data {:client-id client-id}})

    ;; Return client-id as handle
    client-id))

(defn send!
  "Send message through client. Message should be an event vector [event-id data].

  If send-queue is configured:
    Returns :ok if message was queued, :rejected if queue is full.
    Message will be sent asynchronously by the background flush thread.

  If no send-queue (direct send):
    Returns true if sent immediately, false if failed.

  Example:
    (send! client [:my/event {:data \"value\"}])"
  [client-id message]
  (if-let [client-state (get @clients client-id)]
    (let [send-queue (:send-queue client-state)]
      (if send-queue
        ;; Queue-based sending
        (let [serialized (packer/pack message)
              result (q/enqueue! send-queue [serialized message])]
          (when (= result :rejected)
            (trove/log! {:level :warn
                         :id :sente-lite.client/queue-full
                         :data {:client-id client-id
                                :message-type (first message)}}))
          result)
        ;; Direct sending (no queue)
        (let [ws (:ws client-state)]
          (if (and ws (= :connected (:status client-state)))
            (let [serialized (packer/pack message)]
              (ws/send! ws serialized)
              (swap! clients update-in [client-id :message-count-sent] inc)
              (trove/log! {:level :trace
                           :id :sente-lite.client/msg-sent
                           :data {:client-id client-id
                                  :message-type (first message)
                                  :size (count serialized)}})
              true)
            (do
              (trove/log! {:level :warn
                           :id :sente-lite.client/send-failed
                           :data {:client-id client-id
                                  :status (:status client-state)
                                  :has-ws (some? ws)}})
              false)))))
    (do
      (trove/log! {:level :error
                   :id :sente-lite.client/invalid-client-id
                   :data {:client-id client-id}})
      false)))

(defn close!
  "Close WebSocket connection gracefully. Stops send queue and drains remaining messages."
  [client-id]
  (if-let [client-state (get @clients client-id)]
    (let [ws (:ws client-state)
          send-queue (:send-queue client-state)]
      ;; Stop queue first (drains remaining messages)
      (when send-queue
        (let [final-stats (q/stop! send-queue)]
          (trove/log! {:level :debug
                       :id :sente-lite.client/queue-stopped
                       :data {:client-id client-id
                              :final-stats final-stats}})))
      ;; Remove client from registry to prevent on-close from re-adding
      (swap! clients dissoc client-id)
      (when ws
        (trove/log! {:level :debug
                     :id :sente-lite.client/closing
                     :data {:client-id client-id}})
        (ws/close! ws))
      true)
    (do
      (trove/log! {:level :warn
                   :id :sente-lite.client/close-failed
                   :data {:client-id client-id}})
      false)))

(defn get-status
  "Get current client status. Returns :connected, :disconnected, or nil if invalid client-id."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (:status client-state)))

(defn get-uid
  "Get the server-assigned user ID for this client.
  Returns nil if not yet connected or handshake not received."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (:uid client-state)))

(defn get-stats
  "Get client statistics including message counts."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    {:client-id client-id
     :status (:status client-state)
     :uid (:uid client-state)
     :messages-sent (:message-count-sent client-state)
     :messages-received (:message-count-received client-state)
     :reconnect-count (:reconnect-count client-state)}))

(defn queue-stats
  "Get send queue statistics. Returns nil if no queue configured.
  Stats include: :depth :enqueued :sent :dropped :errors"
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (when-let [send-queue (:send-queue client-state)]
      (q/queue-stats send-queue))))

(defn list-clients
  "List all active client IDs."
  []
  (keys @clients))

(defn set-reconnect!
  "Enable or disable auto-reconnect for a client.
  Useful for stopping reconnection attempts when shutting down."
  [client-id enabled?]
  (if-let [_client-state (get @clients client-id)]
    (do
      (swap! clients assoc-in [client-id :reconnect-enabled?] enabled?)
      (trove/log! {:level :debug
                   :id :sente-lite.client/reconnect-setting-updated
                   :data {:client-id client-id
                          :enabled? enabled?}})
      true)
    (do
      (trove/log! {:level :warn
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
