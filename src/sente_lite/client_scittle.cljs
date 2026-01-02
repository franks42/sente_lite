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
            [sente-lite.packer :as packer]
            [sente-lite.queue-scittle :as q]))

;; Event IDs (Sente-compatible)
(def ^:const event-handshake :chsk/handshake)
(def ^:const event-ws-ping :chsk/ws-ping)
(def ^:const event-ws-pong :chsk/ws-pong)
(def ^:const event-subscribe :sente-lite/subscribe)
(def ^:const event-unsubscribe :sente-lite/unsubscribe)
(def ^:const event-publish :sente-lite/publish)

(defn- system-event-id?
  [event-id]
  (and (keyword? event-id)
       (= "chsk" (namespace event-id))))

(defn- normalize-recv
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
           (not (system-event-id? event-id)))
      [:chsk/recv [event-id data]]

      :else
      [event-id data])))

;;; State Management

(defonce ^:private clients (atom {}))  ; client-id -> client-state

(defn- generate-client-id []
  (str "client-" (.now js/Date) "-" (rand-int 10000)))

(defn- generate-handler-id []
  (str "h-" (.now js/Date) "-" (rand-int 10000)))

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
   :send-queue nil
   :handlers (atom {})})

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

(defn- send-event!
  "Send an event vector directly (packs to EDN)"
  [ws event]
  (.send ws (packer/pack event)))

(defn- send-raw!
  "Internal: Send serialized message directly over WebSocket (bypassing queue).
   Used by queue flush. Takes [serialized message] tuple.
   Throws on failure so queue can track as error (not silent loss)."
  [client-id serialized message]
  (if-let [client-state (get @clients client-id)]
    (let [ws (get client-state :ws)
          ready-state (if ws (.-readyState ws) -1)]
      (if (= ready-state 1) ; WebSocket.OPEN = 1
        (do
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
                        :status (get client-state :status)
                        :ready-state ready-state}})
          (throw (js/Error. (str "WebSocket not open (state=" ready-state ")"))))))
    (throw (js/Error. "Client not found"))))

;;; Handler Registry Dispatch

(defn- handler-matches?
  "Check if a handler matches a message."
  [handler msg]
  (let [pred (get handler :pred)
        handler-event-id (get handler :event-id)
        msg-event-id (get msg :event-id)]
    (cond
      pred (pred msg)
      (= handler-event-id :*) true
      handler-event-id (= handler-event-id msg-event-id)
      :else false)))

(defn- dispatch-to-handlers!
  "Dispatch message to all matching handlers. Removes :once? handlers after match."
  [client-id msg]
  (when-let [client-state (get @clients client-id)]
    (let [handlers-atom (get client-state :handlers)
          handlers-map @handlers-atom]
      (doseq [entry handlers-map]
        (let [handler-id (first entry)
              handler (second entry)]
          (when (handler-matches? handler msg)
            (try
              ((get handler :callback) msg)
              ;; Remove once? handlers after match
              (when (get handler :once?)
                ;; Cancel timeout if present
                (when-let [timeout-id (get handler :timeout-id)]
                  (js/clearTimeout timeout-id))
                (swap! handlers-atom dissoc handler-id))
              (catch :default e
                (log! {:level :error
                       :id :sente-lite.client/handler-error
                       :data {:client-id client-id
                              :handler-id handler-id
                              :event-id (get msg :event-id)
                              :error (.-message e)}})))))))))

(defn- handle-handshake
  "Handle :chsk/handshake event - extract uid and csrf-token, store both"
  [client-id data ws]
  (let [uid (first data)
        csrf-token (second data)]
    (swap! clients assoc-in [client-id :uid] uid)
    (swap! clients assoc-in [client-id :csrf-token] csrf-token)
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
                    :error (:error parsed)
                    :raw-data (let [r (:raw parsed)]
                                (if (string? r)
                                  (subs r 0 (min 200 (.-length r)))
                                  (pr-str r)))}})
      (let [[event-id data] (normalize-recv (:event-id parsed) (:data parsed) config)]
        (log! {:level :trace
               :id :sente-lite.client/msg-recv
               :data {:client-id client-id
                      :event-id event-id
                      :message-size (.-length raw-data)}})

        (cond
          ;; Handle handshake
          (= event-id event-handshake)
          (let [uid (handle-handshake client-id data ws)
                ;; Get fresh state to check reconnect count
                current-state (get @clients client-id)
                is-reconnect? (> (get current-state :reconnect-count 0) 0)]
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
                (on-open uid)))
            ;; Call :on-channel-ready on EVERY connection (initial + reconnect)
            (when-let [on-channel-ready (:on-channel-ready config)]
              (log! {:level :trace
                     :id :sente-lite.client/callback-on-channel-ready
                     :data {:client-id client-id :uid uid :is-reconnect? is-reconnect?}})
              (on-channel-ready client-id)))

          ;; Handle server ping -> respond with pong
          (= event-id event-ws-ping)
          (do
            (log! {:level :trace
                   :id :sente-lite.client/auto-pong
                   :data {:client-id client-id}})
            (send-event! ws [event-ws-pong]))

          ;; User messages: dispatch to unified handler registry
          :else
          (let [msg {:event-id event-id :data data}]
            (dispatch-to-handlers! client-id msg)))))))

(defn- handle-error [client-state event]
  (let [client-id (:id client-state)
        ws (.-target event)]
    (log! {:level :error
           :id :sente-lite.client/ws-error
           :data {:client-id client-id
                  :ready-state (.-readyState ws)}})))

(declare attempt-reconnect!)  ; forward declaration
(declare on!)  ; forward declaration for take!

(defn- notify-once-handlers-closed!
  "Notify all :once? handlers that connection closed, and remove them."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (let [handlers-atom (get client-state :handlers)
          handlers-map @handlers-atom
          once-handlers (filter (fn [entry]
                                  (let [handler (second entry)]
                                    (get handler :once?)))
                                handlers-map)]
      (doseq [entry once-handlers]
        (let [handler-id (first entry)
              handler (second entry)]
          ;; Cancel timeout if present
          (when-let [timeout-id (get handler :timeout-id)]
            (js/clearTimeout timeout-id))
          ;; Notify callback
          (try
            ((get handler :callback) {:error :closed :reason :disconnected})
            (catch :default e
              (log! {:level :error
                     :id :sente-lite.client/notify-close-error
                     :data {:client-id client-id
                            :handler-id handler-id
                            :error (.-message e)}})))
          ;; Remove handler
          (swap! handlers-atom dissoc handler-id)))
      (when (pos? (count once-handlers))
        (log! {:level :debug
               :id :sente-lite.client/once-handlers-notified
               :data {:client-id client-id
                      :count (count once-handlers)}})))))

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

        ;; Notify all :once? handlers that connection closed
        (notify-once-handlers-closed! client-id)

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
    :on-open              - Called on initial connection (fn [uid])
    :on-reconnect         - Called after reconnection (fn [])
    :on-channel-ready     - Called on EVERY connection (initial + reconnect) after handshake.
                            Use this to register waiters fresh each time. (fn [client-id])
    :on-message           - Called with parsed message (fn [event-id data])
                            Registered internally as catch-all handler via on!
    :on-close             - Called when connection closes (fn [event])
    :on-error             - Called on error (fn [event])
    :auto-reconnect?      - Enable auto-reconnect (default: true)
    :reconnect-delay      - Initial reconnect delay in ms (default: 1000)
    :max-reconnect-delay  - Maximum reconnect delay in ms (default: 30000)
    :send-queue           - Send queue config map (optional):
                            {:max-depth 1000          ; max queued messages
                             :flush-interval-ms 10}   ; flush interval

  Returns client-id handle for send!/close!/set-reconnect!/take!/on!/off! operations."
  [config]
  (let [client-state (make-client-state config)
        client-id (get client-state :id)
        url (get config :url)
        send-queue-config (get config :send-queue)
        on-message-fn (get config :on-message)
        ws (js/WebSocket. url)]

    (log! {:level :debug
           :id :sente-lite.client/creating
           :data {:client-id client-id
                  :url url
                  :send-queue (some? send-queue-config)
                  :on-message (some? on-message-fn)
                  :initial-state (.-readyState ws)}})

    ;; Store client state
    (swap! clients assoc client-id (assoc client-state :ws ws))

    ;; Setup handlers
    (set! (.-onopen ws) (partial handle-open (get @clients client-id)))
    (set! (.-onmessage ws) (partial handle-message (get @clients client-id)))
    (set! (.-onerror ws) (partial handle-error (get @clients client-id)))
    (set! (.-onclose ws) (partial handle-close (get @clients client-id)))

    ;; Register :on-message as catch-all handler if provided
    (when on-message-fn
      (let [handlers-atom (get (get @clients client-id) :handlers)
            handler-id (str "on-message-" (generate-handler-id))]
        (swap! handlers-atom assoc handler-id
               {:id handler-id
                :event-id :*
                :callback (fn [msg]
                            (on-message-fn (get msg :event-id) (get msg :data)))
                :once? false})
        (log! {:level :debug
               :id :sente-lite.client/on-message-registered
               :data {:client-id client-id
                      :handler-id handler-id}})))

    ;; Create and start send queue if configured
    (when send-queue-config
      (let [queue (q/make-send-queue
                   (merge send-queue-config
                          {:on-send (fn [msg]
                                      ;; msg is [serialized message] tuple
                                      (let [serialized (first msg)
                                            message (second msg)]
                                        (send-raw! client-id serialized message)))
                           :on-error (fn [e msg]
                                       (let [message (second msg)]
                                         (log! {:level :error
                                                :id :sente-lite.client/queue-send-error
                                                :data {:client-id client-id
                                                       :message-type (first message)
                                                       :error (str e)}})))}))]
        (swap! clients assoc-in [client-id :send-queue] queue)
        (q/start! queue)
        (log! {:level :debug
               :id :sente-lite.client/queue-started
               :data {:client-id client-id
                      :max-depth (get send-queue-config :max-depth 1000)
                      :flush-interval-ms (get send-queue-config :flush-interval-ms 10)}})))

    (log! {:level :trace
           :id :sente-lite.client/handlers-attached
           :data {:client-id client-id}})

    ;; Return client-id as handle
    client-id))

(defn send!
  "Send message through client. Message should be an event vector [event-id data].

  If send-queue is configured:
    Returns :ok if message was queued, :rejected if queue is full.
    Message will be sent asynchronously by the background flush timer.

  If no send-queue (direct send):
    Returns true if sent immediately, false if failed.

  Example:
    (send! client [:my/event {:data \"value\"}])"
  [client-id message]
  (if-let [client-state (get @clients client-id)]
    (let [send-queue (get client-state :send-queue)]
      (if send-queue
        ;; Queue-based sending
        (let [serialized (packer/pack message)
              result (q/enqueue! send-queue [serialized message])]
          (when (= result :rejected)
            (log! {:level :warn
                   :id :sente-lite.client/queue-full
                   :data {:client-id client-id
                          :message-type (first message)}}))
          result)
        ;; Direct sending (no queue)
        (let [ws (get client-state :ws)
              ready-state (if ws (.-readyState ws) -1)]
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
                            :status (get client-state :status)}})
              false)))))
    (do
      (log! {:level :error
             :id :sente-lite.client/invalid-client-id
             :data {:client-id client-id}})
      false)))

(defn close!
  "Close WebSocket connection gracefully. Stops send queue and drains remaining messages."
  [client-id]
  (if-let [client-state (get @clients client-id)]
    (let [ws (get client-state :ws)
          send-queue (get client-state :send-queue)]
      ;; Stop queue first (drains remaining messages)
      (when send-queue
        (let [final-stats (q/stop! send-queue)]
          (log! {:level :debug
                 :id :sente-lite.client/queue-stopped
                 :data {:client-id client-id
                        :final-stats final-stats}})))
      ;; Remove client from registry to prevent on-close from re-adding
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
    (get client-state :uid)))

(defn get-csrf-token
  "Get the CSRF token received from the server during handshake.
  Returns nil if not yet connected or handshake not received.

  Use this token in HTTP requests (e.g., file uploads) by including
  it in the X-CSRF-Token header."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (get client-state :csrf-token)))

(defn queue-stats
  "Get send queue statistics. Returns nil if no queue configured.
  Stats include: :depth :enqueued :sent :dropped :errors"
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (when-let [send-queue (get client-state :send-queue)]
      (q/queue-stats send-queue))))

;;; Receive Queue / RPC API

(defn take!
  "Register a one-shot handler for a message matching the predicate.
  This is a convenience wrapper around on! with :once? true.

  IMPORTANT: Register the handler BEFORE sending the request to ensure
  you don't miss the response.

  Options:
    :pred        - Predicate function (fn [msg] -> bool). msg is {:event-id :data}
    :event-id    - Event ID to match (alternative to :pred)
    :callback    - Called when message matches or timeout/close occurs
    :timeout-ms  - Optional timeout in milliseconds

  Returns handler-id (can be used with off! to cancel).

  The callback receives either:
  - The matching message: {:event-id :some/event :data {...}}
  - On timeout: {:error :timeout}
  - On disconnect: {:error :closed :reason :disconnected}

  Example:
    (take! client {:pred #(= (:event-id %) :my/response)
                   :timeout-ms 5000
                   :callback (fn [msg]
                               (if (:error msg)
                                 (println \"Error:\" msg)
                                 (println \"Got:\" msg)))})"
  [client-id opts]
  ;; Delegate to on! with :once? true
  (on! client-id (assoc opts :once? true)))

(defn rpc-waiter
  "Create waiter options for RPC-style request/response matching by request-id.

  Usage:
    (take! client (rpc-waiter \"req-123\" 5000
                              (fn [response]
                                (if (:error response)
                                  (handle-error response)
                                  (handle-success response)))))"
  [request-id timeout-ms callback]
  {:pred (fn [msg]
           (= (get-in msg [:data :request-id]) request-id))
   :timeout-ms timeout-ms
   :callback callback})

;;; Unified Handler API (on!/off!)

(defn on!
  "Register a message handler.

  Options:
    :event-id   - Event ID to match (keyword), or :* for all events
    :pred       - Predicate function (fn [msg] -> bool), alternative to :event-id
    :callback   - Handler function (fn [msg] ...), receives {:event-id :data}
    :once?      - If true, handler removed after first match (default: false)
    :timeout-ms - For :once? handlers, timeout in ms. Callback receives {:error :timeout}

  Returns handler-id (for removal with off!)

  Examples:
    ;; Persistent handler
    (on! client {:event-id :server/push :callback handle-push})

    ;; One-shot with timeout (RPC)
    (on! client {:event-id :my/response :once? true :timeout-ms 5000 :callback ...})

    ;; Predicate matching
    (on! client {:pred #(= (:id (:data %)) req-id) :once? true :callback ...})

    ;; Catch-all
    (on! client {:event-id :* :callback log-all-events})"
  [client-id opts]
  (if-let [client-state (get @clients client-id)]
    (let [handlers-atom (get client-state :handlers)
          handler-id (generate-handler-id)
          callback (get opts :callback)
          once? (get opts :once? false)
          timeout-ms (get opts :timeout-ms)
          handler (cond-> {:id handler-id
                           :callback callback
                           :once? once?}
                    (contains? opts :event-id) (assoc :event-id (get opts :event-id))
                    (contains? opts :pred) (assoc :pred (get opts :pred)))]

      (assert callback ":callback is required for on!")

      ;; Setup timeout for once? handlers if specified
      (let [handler-with-timeout
            (if (and once? timeout-ms)
              (let [timeout-id
                    (js/setTimeout
                     (fn []
                       ;; Check if handler still exists (not already matched)
                       (when (contains? @handlers-atom handler-id)
                         (swap! handlers-atom dissoc handler-id)
                         (callback {:error :timeout})))
                     timeout-ms)]
                (assoc handler :timeout-id timeout-id))
              handler)]

        (swap! handlers-atom assoc handler-id handler-with-timeout)

        (log! {:level :trace
               :id :sente-lite.client/handler-registered
               :data {:client-id client-id
                      :handler-id handler-id
                      :event-id (get opts :event-id)
                      :once? once?
                      :timeout-ms timeout-ms}})

        handler-id))
    (do
      (log! {:level :error
             :id :sente-lite.client/on-invalid-client
             :data {:client-id client-id}})
      nil)))

(defn off!
  "Remove message handler(s).

  Forms:
    (off! client handler-id)           ; Remove specific handler
    (off! client {:event-id :foo})     ; Remove all handlers for event-id
    (off! client :all)                 ; Remove all handlers

  Returns true if any handlers were removed, false otherwise."
  [client-id id-or-opts]
  (if-let [client-state (get @clients client-id)]
    (let [handlers-atom (get client-state :handlers)]
      (cond
        ;; Remove all handlers
        (= id-or-opts :all)
        (let [had-handlers? (pos? (count @handlers-atom))]
          ;; Cancel all timeouts
          (doseq [entry @handlers-atom]
            (let [handler (second entry)]
              (when-let [timeout-id (get handler :timeout-id)]
                (js/clearTimeout timeout-id))))
          (reset! handlers-atom {})
          (log! {:level :debug
                 :id :sente-lite.client/handlers-cleared
                 :data {:client-id client-id}})
          had-handlers?)

        ;; Remove by event-id
        (map? id-or-opts)
        (let [target-event-id (get id-or-opts :event-id)
              matching-ids (for [entry @handlers-atom
                                 :let [id (first entry)
                                       handler (second entry)]
                                 :when (= (get handler :event-id) target-event-id)]
                             id)
              count-before (count @handlers-atom)]
          (doseq [id matching-ids]
            (when-let [handler (get @handlers-atom id)]
              (when-let [timeout-id (get handler :timeout-id)]
                (js/clearTimeout timeout-id)))
            (swap! handlers-atom dissoc id))
          (let [removed-count (- count-before (count @handlers-atom))]
            (log! {:level :debug
                   :id :sente-lite.client/handlers-removed
                   :data {:client-id client-id
                          :event-id target-event-id
                          :removed-count removed-count}})
            (pos? removed-count)))

        ;; Remove by handler-id
        (string? id-or-opts)
        (let [handler-id id-or-opts
              handler (get @handlers-atom handler-id)]
          (if handler
            (do
              (when-let [timeout-id (get handler :timeout-id)]
                (js/clearTimeout timeout-id))
              (swap! handlers-atom dissoc handler-id)
              (log! {:level :trace
                     :id :sente-lite.client/handler-removed
                     :data {:client-id client-id
                            :handler-id handler-id}})
              true)
            false))

        :else
        (do
          (log! {:level :warn
                 :id :sente-lite.client/off-invalid-arg
                 :data {:client-id client-id
                        :arg id-or-opts}})
          false)))
    (do
      (log! {:level :error
             :id :sente-lite.client/off-invalid-client
             :data {:client-id client-id}})
      false)))

(defn handler-count
  "Get count of registered handlers for a client."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (count @(get client-state :handlers))))
