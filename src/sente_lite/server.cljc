(ns sente-lite.server
  "Enhanced WebSocket server with channel system for sente-lite"
  (:require [telemere-lite.core :as tel]
            #?(:bb [cheshire.core :as json]
               :clj [clojure.data.json :as json])
            #?(:bb [org.httpkit.server :as http])
            [sente-lite.channels :as channels]
            [sente-lite.wire-format :as wire])
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
    (tel/event! ::connection-added {:conn-id conn-id
                                    :total-connections (count @connections)})
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

      (tel/event! ::connection-removed {:conn-id conn-id
                                        :duration-ms duration
                                        :message-count (:message-count conn-data)
                                        :total-connections (count @connections)})
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

;; Message handling with pluggable wire format
(defn- get-wire-format
  "Get the configured wire format from config"
  [config]
  (wire/get-format (:wire-format config :json)))

(defn- parse-message
  "Parse a message using the configured wire format"
  [raw-message conn-id wire-format]
  (try
    (let [parsed (wire/deserialize wire-format raw-message)]
      (tel/event! ::message-parsed {:conn-id conn-id
                                    :type (:type parsed)
                                    :size (count raw-message)
                                    :format (wire/format-name wire-format)})
      parsed)
    (catch Exception e
      (tel/error! {:msg "Failed to parse WebSocket message"
                   :error e
                   :data {:conn-id conn-id
                          :raw-message raw-message
                          :format (wire/format-name wire-format)
                          :error-type (type e)}})
      nil)))

(defn- send-message! [channel message wire-format]
  ;; Send a message using the configured wire format
  (try
    (let [wire-data (wire/serialize wire-format message)]
      (when wire-data
        #?(:bb (http/send! channel wire-data))
        (tel/event! ::message-sent {:channel-id (str channel)
                                    :type (:type message)
                                    :size (count wire-data)
                                    :format (wire/format-name wire-format)})
        true))
    (catch Exception e
      (tel/error! {:msg "Failed to send WebSocket message"
                   :error e
                   :data {:channel-id (str channel)
                          :message-type (:type message)
                          :format (wire/format-name wire-format)}})
      false)))

;; Forward declarations
(declare broadcast-to-channel!)

;; Message routing and handling
(defn- route-message
  "Route parsed message to appropriate handler"
  [conn-id parsed-message config]
  (let [msg-type (:type parsed-message)]
    (tel/event! ::message-routing {:conn-id conn-id :type msg-type})

    (case (keyword msg-type)
      ;; Ping/pong for connection health
      :ping
      {:type :pong
       :timestamp (System/currentTimeMillis)
       :original-timestamp (:timestamp parsed-message)}

      :pong
      (do
        ;; Update last-pong timestamp when client responds
        (when-let [channel (get @connection-index conn-id)]
          (update-connection-pong! channel))
        {:type :pong-ack
         :timestamp (System/currentTimeMillis)})

      ;; Channel operations
      :subscribe
      (let [channel-id (:channel-id parsed-message)
            auto-create? (get-in config [:channels :auto-create])
            result (do
                     ;; Auto-create channel if enabled and doesn't exist
                     (when (and auto-create? (not (channels/get-channel-info channel-id)))
                       (channels/create-channel! channel-id
                                                 (get-in config [:channels :default-config])))

                     (channels/subscribe! conn-id channel-id))]
        {:type :subscription-result
         :channel-id channel-id
         :success (:success result)
         :reason (:reason result)
         :subscriber-count (:subscriber-count result)
         :retained-messages (:retained-messages result)})

      :unsubscribe
      (let [channel-id (:channel-id parsed-message)
            result (channels/unsubscribe! conn-id channel-id)]
        {:type :unsubscription-result
         :channel-id channel-id
         :success result})

      :publish
      (let [channel-id (:channel-id parsed-message)
            message-data (:data parsed-message)
            exclude-sender? (:exclude-sender? parsed-message false)
            result (channels/publish! channel-id message-data
                                      :sender-conn-id conn-id
                                      :exclude-sender? exclude-sender?)]
        ;; Actually broadcast the message to subscribers
        (when (:success result)
          (broadcast-to-channel! channel-id message-data))
        {:type :publish-result
         :channel-id channel-id
         :success (:success result)
         :message-id (:message-id result)
         :delivered-to (:delivered-to result)})

      :rpc-request
      (let [target-channel-id (:channel-id parsed-message)
            request-data (:data parsed-message)
            timeout-ms (:timeout-ms parsed-message 30000)
            result (channels/send-rpc-request! conn-id target-channel-id request-data
                                               :timeout-ms timeout-ms)]
        {:type :rpc-request-result
         :request-id (:request-id result)
         :channel-id target-channel-id
         :delivery (:delivery result)})

      :rpc-response
      (let [request-id (:request-id parsed-message)
            response-data (:data parsed-message)
            error? (:error? parsed-message false)
            result (channels/send-rpc-response! request-id response-data :error? error?)]
        {:type :rpc-response-result
         :request-id request-id
         :success (:success result)})

      :list-channels
      {:type :channel-list
       :channels (channels/list-channels)}

      :get-subscriptions
      {:type :subscription-list
       :subscriptions (vec (channels/get-subscriptions conn-id))}

      ;; Default echo for testing
      {:type :echo
       :original parsed-message
       :conn-id conn-id
       :timestamp (System/currentTimeMillis)})))

(defn- send-to-connection! [conn-id message wire-format]
  ;; Send message to a specific connection by conn-id
  (when-let [channel (get @connection-index conn-id)]
    (send-message! channel message wire-format)))

;; Heartbeat management
(defn- send-heartbeat-pings!
  "Send pings to all connections and close dead ones"
  [config]
  (let [timeout-ms (get-in config [:heartbeat :timeout-ms] 60000)
        now (System/currentTimeMillis)
        wire-format (get-wire-format config)
        dead-conns (atom [])]

    (tel/event! ::heartbeat-check {:total-connections (count @connections)
                                   :timeout-ms timeout-ms})

    ;; Check each connection
    (doseq [[channel conn-data] @connections]
      (let [time-since-pong (- now (:last-pong conn-data))
            conn-id (:id conn-data)]
        (if (> time-since-pong timeout-ms)
          ;; Connection is dead - mark for removal
          (do
            (tel/event! ::heartbeat-timeout {:conn-id conn-id
                                             :time-since-pong-ms time-since-pong
                                             :timeout-ms timeout-ms})
            (swap! dead-conns conj [channel conn-id]))
          ;; Connection alive - send ping
          (when (send-message! channel {:type :ping
                                        :timestamp now} wire-format)
            (tel/event! ::heartbeat-ping-sent {:conn-id conn-id})))))

    ;; Close dead connections
    (doseq [[channel conn-id] @dead-conns]
      (tel/event! ::closing-dead-connection {:conn-id conn-id})
      (remove-connection! channel)
      #?(:bb (http/close channel)))

    (when (seq @dead-conns)
      (tel/event! ::heartbeat-cleanup-complete {:closed-count (count @dead-conns)}))))

(defn- start-heartbeat-task!
  "Start background heartbeat task"
  [config]
  (let [interval-ms (get-in config [:heartbeat :interval-ms] 30000)
        enabled? (get-in config [:heartbeat :enabled] true)]

    (tel/event! ::heartbeat-starting {:enabled enabled?
                                      :interval-ms interval-ms})

    (when enabled?
      #?(:bb
         (future
           (try
             (while @server-state
               (Thread/sleep interval-ms)
               (when @server-state  ; Check again after sleep
                 (send-heartbeat-pings! config)))
             (tel/event! ::heartbeat-stopped {})
             (catch Exception e
               (tel/error! "Heartbeat task error" {:error e}))))
         :clj
         (future
           (try
             (while @server-state
               (Thread/sleep interval-ms)
               (when @server-state
                 (send-heartbeat-pings! config)))
             (tel/event! ::heartbeat-stopped {})
             (catch Exception e
               (tel/error! "Heartbeat task error" {:error e}))))
         :cljs
         (tel/error! "Heartbeat not supported in ClojureScript" {})))))

;; WebSocket handlers
(defn- on-websocket-open [channel config]
  (let [conn-id (generate-connection-id)
        _conn-data (add-connection! channel conn-id)
        wire-format (get-wire-format config)]

    ;; Send welcome message
    (send-message! channel {:type :welcome
                            :conn-id conn-id
                            :server-time (System/currentTimeMillis)}
                   wire-format)

    (tel/event! ::websocket-opened {:conn-id conn-id
                                    :config (select-keys config [:port :host])
                                    :wire-format (wire/format-name wire-format)})))

(defn- on-websocket-message [channel raw-message config]
  (when-let [conn-data (get @connections channel)]
    (let [conn-id (:id conn-data)
          wire-format (get-wire-format config)]
      (update-connection-activity! channel)

      (tel/event! ::websocket-message-received {:conn-id conn-id
                                                :size (count raw-message)})

      (when-let [parsed-message (parse-message raw-message conn-id wire-format)]
        (let [response (route-message conn-id parsed-message config)]
          (tel/event! ::message-processed {:conn-id conn-id
                                           :type (:type parsed-message)
                                           :response-type (:type response)})
          ;; Send response back to client
          (send-message! channel response wire-format))))))

(defn- on-websocket-close [channel status _config]
  (when-let [conn-data (remove-connection! channel)]
    (tel/event! ::websocket-closed {:conn-id (:id conn-data)
                                    :status status
                                    :final-message-count (:message-count conn-data)})))

(defn- on-websocket-error [channel throwable _config]
  (when-let [conn-data (get @connections channel)]
    (tel/error! {:msg "WebSocket error occurred"
                 :error throwable
                 :data {:conn-id (:id conn-data)
                        :error-type (type throwable)}})
    (remove-connection! channel)))

;; WebSocket request handler
(defn- websocket-handler [request config]
  (tel/event! ::websocket-request {:method (:request-method request)
                                   :uri (:uri request)
                                   :websocket? (:websocket? request)})

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
    (tel/event! ::http-request {:method (:request-method request)
                                :uri (:uri request)
                                :user-agent (get-in request [:headers "user-agent"])})

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
                       :telemetry-stats (tel/get-handler-stats)})}

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

     ;; Initialize telemetry if enabled
     (when (get-in merged-config [:telemetry :enabled])
       (tel/startup!)
       (tel/add-file-handler! (get-in merged-config [:telemetry :handler-id])
                              "sente-lite-server.log"))

     (tel/event! ::server-starting merged-config)

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

          (tel/event! ::server-started {:requested-port (:port merged-config)
                                        :actual-port actual-port
                                        :host (:host merged-config)
                                        :ephemeral? (zero? (:port merged-config))})

          ;; Start heartbeat task
          (start-heartbeat-task! merged-config)

          server))))
  ([]
   (start-server! {})))

(defn stop-server!
  "Stop the WebSocket server"
  []
  (when-let [state @server-state]
    (tel/event! ::server-stopping {:active-connections (count @connections)})

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

    (tel/event! ::server-stopped {})
    (tel/shutdown-telemetry!)))

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
     :telemetry (tel/get-handler-stats)}))

(defn broadcast-message!
  "Send message to all connected clients"
  [message]
  (tel/event! ::broadcast-start {:message-type (:type message)
                                 :target-connections (count @connections)})

  (let [sent-count (atom 0)
        wire-format (get-wire-format (:config @server-state))]
    (doseq [[channel _conn-data] @connections]
      (when (send-message! channel (assoc message :broadcast true) wire-format)
        (swap! sent-count inc)))

    (tel/event! ::broadcast-complete {:sent-count @sent-count
                                      :total-connections (count @connections)})
    @sent-count))

;; Channel integration functions
(defn broadcast-to-channel!
  "Broadcast a message to all subscribers of a channel"
  [channel-id message]
  (let [channel-info (channels/get-channel-info channel-id)]
    (if channel-info
      (let [subscribers (:subscribers channel-info)
            message-with-meta {:type :channel-message
                               :channel-id channel-id
                               :data message
                               :broadcast-time (System/currentTimeMillis)}
            delivered (atom 0)
            wire-format (get-wire-format (:config @server-state))]

        (tel/event! ::channel-broadcast-start {:channel-id channel-id
                                               :subscriber-count (count subscribers)})

        (doseq [conn-id subscribers]
          (when (send-to-connection! conn-id message-with-meta wire-format)
            (swap! delivered inc)))

        (tel/event! ::channel-broadcast-complete {:channel-id channel-id
                                                  :delivered @delivered
                                                  :target-count (count subscribers)})
        @delivered)
      (do
        (tel/event! ::broadcast-failed {:channel-id channel-id :reason :channel-not-found})
        0))))

(defn send-message-to-connection!
  "Send a message directly to a connection (exposed for external use)"
  [conn-id message]
  (let [wire-format (get-wire-format (:config @server-state))]
    (send-to-connection! conn-id message wire-format)))