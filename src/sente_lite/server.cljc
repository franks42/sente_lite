(ns sente-lite.server
  "Enhanced WebSocket server with channel system for sente-lite.
   Uses Sente-compatible wire format: [event-id data]"
  (:require [taoensso.trove :as trove]
            #?(:bb [cheshire.core :as json]
               :clj [clojure.data.json :as json])
            #?(:bb [org.httpkit.server :as http])
            [sente-lite.channels :as channels]
            [sente-lite.wire-format :as wf])
  (:import [java.lang System Exception]))

;; Connection state management
(defonce ^:private connections (atom {}))           ; channel -> conn-data
(defonce ^:private connection-index (atom {}))      ; conn-id -> channel
(defonce ^:private server-state (atom nil))

;; Configuration defaults
(def default-config
  {:port 3000
   :host "localhost"
   :wire-format :edn  ; Default to EDN for Clojure-to-Clojure communication
   :telemetry {:enabled true
               :handler-id :sente-lite-server}
   :websocket {:max-connections 1000
               :message-timeout-ms 5000}
   :heartbeat {:enabled true
               :interval-ms 30000    ; Send ping every 30s
               :timeout-ms 60000}    ; Close if no pong for 60s
   :channels {:auto-create true
              :default-config {:max-subscribers 1000
                               :message-retention 0
                               :rpc-timeout-ms 30000}}})

;; Connection lifecycle management
(defn- generate-connection-id []
  (str "conn-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defn- add-connection! [channel conn-id]
  (let [conn-data {:id conn-id
                   :channel channel
                   :opened-at (System/currentTimeMillis)
                   :last-activity (System/currentTimeMillis)
                   :last-pong (System/currentTimeMillis)
                   :message-count 0}]
    (swap! connections assoc channel conn-data)
    (swap! connection-index assoc conn-id channel)
    (trove/log! {:level :debug
                 :id :sente-lite.server/conn-added
                 :data {:conn-id conn-id
                        :total-connections (count @connections)}})
    conn-data))

(defn- remove-connection! [channel]
  (when-let [conn-data (get @connections channel)]
    (let [conn-id (:id conn-data)
          duration (- (System/currentTimeMillis) (:opened-at conn-data))]
      ;; Unsubscribe from all channels
      (channels/unsubscribe-all! conn-id)

      ;; Remove connection tracking
      (swap! connections dissoc channel)
      (swap! connection-index dissoc conn-id)

      (trove/log! {:level :debug
                   :id :sente-lite.server/conn-removed
                   :data {:conn-id conn-id
                          :duration-ms duration
                          :message-count (:message-count conn-data)
                          :total-connections (count @connections)}})
      conn-data)))

(defn- update-connection-activity! [channel]
  (when (get @connections channel)
    (swap! connections update channel
           #(-> %
                (assoc :last-activity (System/currentTimeMillis))
                (update :message-count inc)))))

(defn- update-connection-pong!
  "Update last-pong timestamp for connection"
  [channel]
  (when (get @connections channel)
    (swap! connections update channel
           #(assoc % :last-pong (System/currentTimeMillis)))))

;; JSON serialization helper
(defn- to-json [data]
  #?(:bb (json/generate-string data)
     :clj (json/write-str data)))

;; Message handling with v2 wire format
(defn- get-format-spec
  "Get the wire format spec keyword from config (:edn, :json, :transit-json)"
  [config]
  (:wire-format config :edn))

(defn- parse-message
  "Parse a raw wire message into an event map {:event-id ... :data ... :cb-uuid ...}"
  [raw-message conn-id format-spec]
  (try
    (let [event (wf/parse-message raw-message format-spec)]
      (if (:error event)
        (do
          (trove/log! {:level :warn :id :sente-lite.server/parse-error
                       :data {:conn-id conn-id
                              :error (:error event)
                              :message (:message event)}})
          nil)
        event))
    (catch Exception e
      (trove/log! {:level :error :id :sente-lite.server/parse-failed
                   :error e
                   :data {:conn-id conn-id
                          :format-spec format-spec
                          :error-type (type e)}})
      nil)))

(defn- send-event!
  "Send a v2 event vector to a channel"
  [channel event format-spec]
  (try
    (let [wire-data (wf/serialize event format-spec)]
      (when wire-data
        #?(:bb (http/send! channel wire-data))
        (trove/log! {:level :trace
                     :id :sente-lite.server/msg-sent
                     :data {:channel-id (str channel)
                            :event-id (when (vector? event) (first event))
                            :size (count (str wire-data))
                            :format-spec format-spec}})
        true))
    (catch Exception e
      (trove/log! {:level :error :id :sente-lite.server/send-failed
                   :error e
                   :data {:channel-id (str channel)
                          :event-id (when (vector? event) (first event))
                          :format-spec format-spec}})
      false)))

;; Forward declarations
(declare broadcast-to-channel!)

;; Message routing and handling (v2 event-based)
(defn- route-message
  "Route an event map {:event-id ... :data ... :cb-uuid ...} to handler.
   Returns either a v2 event vector or nil (no response needed)."
  [conn-id {:keys [event-id data cb-uuid]} config]
  (trove/log! {:level :trace
               :id :sente-lite.server/msg-routing
               :data {:conn-id conn-id :event-id event-id :has-cb (some? cb-uuid)}})

  (cond
    ;; Ping -> respond with pong
    (wf/ping-event? event-id)
    (wf/make-ws-pong)

    ;; Pong -> update last-pong timestamp, no response
    (wf/pong-event? event-id)
    (do
      (when-let [channel (get @connection-index conn-id)]
        (update-connection-pong! channel))
      nil)

    ;; Handshake from client -> ignore (server-initiated only)
    (wf/handshake-event? event-id)
    nil

    ;; sente-lite extension events
    (= event-id wf/event-subscribe)
    (let [channel-id (:channel-id data)
          auto-create? (get-in config [:channels :auto-create])
          _ (when (and auto-create? (not (channels/get-channel-info channel-id)))
              (channels/create-channel! channel-id
                                        (get-in config [:channels :default-config])))
          result (channels/subscribe! conn-id channel-id)]
      (wf/make-subscribed channel-id (:success result)
                          :error (:reason result)))

    (= event-id wf/event-unsubscribe)
    (let [channel-id (:channel-id data)
          success (channels/unsubscribe! conn-id channel-id)]
      (wf/make-subscribed channel-id success
                          :error (when-not success :not-subscribed)))

    (= event-id wf/event-publish)
    (let [channel-id (:channel-id data)
          message-data (:data data)
          exclude-sender? (:exclude-sender? data false)
          result (channels/publish! channel-id message-data
                                    :sender-conn-id conn-id
                                    :exclude-sender? exclude-sender?)]
      (when (:success result)
        (broadcast-to-channel! channel-id message-data conn-id))
      nil)

    ;; Echo for testing - respond with same data wrapped in :sente-lite/echo
    :else
    [:sente-lite/echo {:original-event-id event-id
                       :original-data data
                       :conn-id conn-id
                       :timestamp (System/currentTimeMillis)}]))

(defn- send-to-connection!
  "Send a v2 event to a specific connection by conn-id"
  [conn-id event format-spec]
  (when-let [channel (get @connection-index conn-id)]
    (send-event! channel event format-spec)))

;; Heartbeat management
(defn- send-heartbeat-pings!
  "Send pings to all connections and close dead ones"
  [config]
  (let [timeout-ms (get-in config [:heartbeat :timeout-ms] 60000)
        now (System/currentTimeMillis)
        format-spec (get-format-spec config)
        dead-conns (atom [])]

    ;; Check each connection
    (doseq [[channel conn-data] @connections]
      (let [time-since-pong (- now (:last-pong conn-data))
            conn-id (:id conn-data)]
        (if (> time-since-pong timeout-ms)
          ;; Connection is dead - mark for removal
          (do
            (trove/log! {:level :warn
                         :id :sente-lite.heartbeat/timeout
                         :data {:conn-id conn-id
                                :time-since-pong-ms time-since-pong
                                :timeout-ms timeout-ms}})
            (swap! dead-conns conj [channel conn-id]))
          ;; Connection alive - send ping (v2 format)
          (send-event! channel (wf/make-ws-ping) format-spec))))

    ;; Close dead connections
    (doseq [[channel conn-id] @dead-conns]
      (remove-connection! channel)
      #?(:bb (http/close channel)))))

(defn- start-heartbeat-task!
  "Start background heartbeat task"
  [config]
  (let [interval-ms (get-in config [:heartbeat :interval-ms] 30000)
        enabled? (get-in config [:heartbeat :enabled] true)]

    (trove/log! {:level :info
                 :id :sente-lite.heartbeat/starting
                 :data {:enabled enabled?
                        :interval-ms interval-ms}})

    (when enabled?
      #?(:bb
         (future
           (try
             (while @server-state
               (Thread/sleep interval-ms)
               (when @server-state  ; Check again after sleep
                 (send-heartbeat-pings! config)))
             (trove/log! {:level :info
                          :id :sente-lite.heartbeat/stopped
                          :data {}})
             (catch Exception e
               (trove/log! {:level :error :id :sente-lite.heartbeat/task-error
                            :error e}))))
         :clj
         (future
           (try
             (while @server-state
               (Thread/sleep interval-ms)
               (when @server-state
                 (send-heartbeat-pings! config)))
             (trove/log! {:level :info
                          :id :sente-lite.heartbeat/stopped
                          :data {}})
             (catch Exception e
               (trove/log! {:level :error :id :sente-lite.heartbeat/task-error
                            :error e}))))
         :cljs
         (trove/log! {:level :error :id :sente-lite.heartbeat/cljs-not-supported
                      :data {}})))))

;; WebSocket handlers
(defn- on-websocket-open [channel config]
  (let [conn-id (generate-connection-id)
        _conn-data (add-connection! channel conn-id)
        format-spec (get-format-spec config)
        ;; Use conn-id as uid for Sente compatibility
        uid conn-id
        csrf-token nil
        handshake-data {:sente-lite-version wf/version}
        first? true
        handshake-event (wf/make-handshake uid csrf-token handshake-data first?)]

    ;; Send handshake (Sente-compatible)
    (send-event! channel handshake-event format-spec)

    (trove/log! {:level :debug
                 :id :sente-lite.server/ws-open
                 :data {:conn-id conn-id
                        :uid uid
                        :config (select-keys config [:port :host])
                        :format-spec format-spec}})))

(defn- on-websocket-message [channel raw-message config]
  (when-let [conn-data (get @connections channel)]
    (let [conn-id (:id conn-data)
          format-spec (get-format-spec config)]
      (update-connection-activity! channel)

      (trove/log! {:level :trace
                   :id :sente-lite.server/ws-msg-recv
                   :data {:conn-id conn-id
                          :size (count raw-message)}})

      (when-let [event (parse-message raw-message conn-id format-spec)]
        (when-let [response-event (route-message conn-id event config)]
          (trove/log! {:level :trace
                       :id :sente-lite.server/msg-processed
                       :data {:conn-id conn-id
                              :event-id (:event-id event)
                              :response-event-id (when (vector? response-event)
                                                   (first response-event))}})
          ;; Send response back to client
          (send-event! channel response-event format-spec))))))

(defn- on-websocket-close [channel status _config]
  (when-let [conn-data (remove-connection! channel)]
    (trove/log! {:level :debug
                 :id :sente-lite.server/ws-close
                 :data {:conn-id (:id conn-data)
                        :status status
                        :final-message-count (:message-count conn-data)}})))

(defn- on-websocket-error [channel throwable _config]
  (when-let [conn-data (get @connections channel)]
    (trove/log! {:level :error :id :sente-lite.server/ws-error
                 :error throwable
                 :data {:conn-id (:id conn-data)
                        :error-type (type throwable)}})
    (remove-connection! channel)))

;; WebSocket request handler
(defn- websocket-handler [request config]
  (trove/log! {:level :trace
               :id :sente-lite.server/ws-req
               :data {:method (:request-method request)
                      :uri (:uri request)
                      :websocket? (:websocket? request)}})

  (if-not (:websocket? request)
    {:status 426
     :headers {"Upgrade" "websocket"}
     :body "WebSocket upgrade required"}

    #?(:bb
       (http/as-channel request
                        {:on-open    #(on-websocket-open % config)
                         :on-receive #(on-websocket-message %1 %2 config)
                         :on-close   #(on-websocket-close %1 %2 config)
                         :on-error   #(on-websocket-error %1 %2 config)})
       :clj
       {:status 501 :body "WebSocket not supported in Clojure mode"})))

;; HTTP request handler
(defn- http-handler [config]
  (fn [request]
    (trove/log! {:level :trace
                 :id :sente-lite.server/http-req
                 :data {:method (:request-method request)
                        :uri (:uri request)
                        :user-agent (get-in request [:headers "user-agent"])}})

    (cond
      ;; WebSocket upgrade requests
      (:websocket? request)
      (websocket-handler request config)

      ;; Health check endpoint
      (= (:uri request) "/health")
      {:status 200
       :headers {"content-type" "application/json"}
       :body (to-json {:status "healthy"
                       :connections (count @connections)
                       :uptime-ms (when-let [start-time (:start-time @server-state)]
                                    (- (System/currentTimeMillis) start-time))})}

      ;; Server stats endpoint
      (= (:uri request) "/stats")
      {:status 200
       :headers {"content-type" "application/json"}
       :body (to-json {:active-connections (count @connections)
                       :total-messages (reduce + (map :message-count (vals @connections)))
                       :server-config (select-keys config [:port :host])
                       :channel-stats (channels/get-channel-stats)
                       :telemetry-stats {}})}

      ;; Channels endpoint
      (= (:uri request) "/channels")
      {:status 200
       :headers {"content-type" "application/json"}
       :body (to-json {:channels (channels/list-channels)})}

      ;; Default: not found
      :else
      {:status 404
       :headers {"content-type" "text/plain"}
       :body "Not found"})))

;; Public API
(defn start-server!
  "Start WebSocket server with configuration"
  ([config]
   (let [merged-config (merge default-config config)]

     (trove/log! {:level :info
                  :id :sente-lite.server/starting
                  :data merged-config})

     ;; Start HTTP-Kit server
     #?(:bb
        (let [server (http/run-server (http-handler merged-config)
                                      {:port (:port merged-config)
                                       :host (:host merged-config)})
              ;; Get actual bound port (supports ephemeral port 0)
              actual-port (:local-port (meta server))]

          (reset! server-state {:server server
                                :config merged-config
                                :actual-port actual-port
                                :start-time (System/currentTimeMillis)})

          (trove/log! {:level :info
                       :id :sente-lite.server/started
                       :data {:requested-port (:port merged-config)
                              :actual-port actual-port
                              :host (:host merged-config)
                              :ephemeral? (zero? (:port merged-config))}})

          ;; Start heartbeat task
          (start-heartbeat-task! merged-config)

          server))))
  ([]
   (start-server! {})))

(defn stop-server!
  "Stop the WebSocket server"
  []
  (when-let [state @server-state]
    (trove/log! {:level :info
                 :id :sente-lite.server/stopping
                 :data {:active-connections (count @connections)}})

    ;; Close all active connections
    (doseq [[channel _conn-data] @connections]
      #?(:bb (http/close channel)))

    ;; Clean up expired RPC requests
    (channels/cleanup-expired-rpc-requests!)

    ;; Stop the server
    #?(:bb ((:server state)))

    ;; Reset state
    (reset! server-state nil)
    (reset! connections {})
    (reset! connection-index {})

    (trove/log! {:level :info
                 :id :sente-lite.server/stopped
                 :data {}})))

(defn get-server-port
  "Get the actual bound port of the running server.
   Returns nil if server is not running.

   Useful when using ephemeral ports (port 0) to discover
   the OS-assigned port number.

   Example:
     (start-server! {:port 0})  ; Use ephemeral port
     (get-server-port)          ; => 55123 (actual assigned port)"
  []
  (when-let [state @server-state]
    (:actual-port state)))

(defn get-server-stats
  "Get comprehensive server statistics including channel information"
  []
  (let [state @server-state
        active-conns @connections
        channel-stats (channels/get-channel-stats)]
    {:running? (boolean state)
     :config (:config state)
     :actual-port (:actual-port state)
     :requested-port (get-in state [:config :port])
     :ephemeral? (when state (zero? (get-in state [:config :port])))
     :uptime-ms (when (:start-time state)
                  (- (System/currentTimeMillis) (:start-time state)))
     :connections {:active (count active-conns)
                   :details (map #(select-keys % [:id :opened-at :message-count :last-activity])
                                 (vals active-conns))}
     :channels channel-stats
     :system-health (channels/get-system-health)
     :telemetry {}}))

(defn broadcast-message!
  "Send a v2 event to all connected clients"
  [event]
  (trove/log! {:level :debug
               :id :sente-lite.server/broadcast-start
               :data {:event-id (when (vector? event) (first event))
                      :target-connections (count @connections)}})

  (let [sent-count (atom 0)
        format-spec (get-format-spec (:config @server-state))]
    (doseq [[channel _conn-data] @connections]
      (when (send-event! channel event format-spec)
        (swap! sent-count inc)))

    (trove/log! {:level :debug
                 :id :sente-lite.server/broadcast-complete
                 :data {:sent-count @sent-count
                        :total-connections (count @connections)}})
    @sent-count))

;; Channel integration functions
(defn broadcast-to-channel!
  "Broadcast a message to all subscribers of a channel using v2 format"
  [channel-id message-data from-conn-id]
  (let [channel-info (channels/get-channel-info channel-id)]
    (if channel-info
      (let [subscribers (:subscribers channel-info)
            event (wf/make-channel-msg channel-id message-data from-conn-id)
            delivered (atom 0)
            format-spec (get-format-spec (:config @server-state))]

        (trove/log! {:level :debug
                     :id :sente-lite.server/chan-broadcast-start
                     :data {:channel-id channel-id
                            :subscriber-count (count subscribers)}})

        (doseq [conn-id subscribers]
          (when (send-to-connection! conn-id event format-spec)
            (swap! delivered inc)))

        (trove/log! {:level :debug
                     :id :sente-lite.server/chan-broadcast-complete
                     :data {:channel-id channel-id
                            :delivered @delivered
                            :target-count (count subscribers)}})
        @delivered)
      (do
        (trove/log! {:level :warn
                     :id :sente-lite.server/broadcast-failed
                     :data {:channel-id channel-id :reason :channel-not-found}})
        0))))

(defn send-event-to-connection!
  "Send a v2 event directly to a connection (exposed for external use)"
  [conn-id event]
  (let [format-spec (get-format-spec (:config @server-state))]
    (send-to-connection! conn-id event format-spec)))