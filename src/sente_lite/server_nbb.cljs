(ns sente-lite.server-nbb
  "WebSocket server for nbb (Node Babashka) with Sente-compatible v2 wire format.

  Provides sente-like API for nbb/Node.js environments:
  - Uses 'ws' npm package (WebSocketServer)
  - Sente-compatible v2 wire format: [event-id data]
  - Channel/pub-sub support
  - Heartbeat with ping/pong

  Usage:
    (require '[sente-lite.server-nbb :as server])

    (server/start-server! {:port 3000})
    ; ... later ...
    (server/stop-server!)"
  (:require ["ws" :as ws-mod]
            [clojure.edn :as edn]
            [taoensso.trove :as trove]))

;; ============================================================================
;; v2 Event IDs (Sente-compatible)
;; ============================================================================

(def ^:const event-handshake :chsk/handshake)
(def ^:const event-ws-ping :chsk/ws-ping)
(def ^:const event-ws-pong :chsk/ws-pong)
(def ^:const event-subscribe :sente-lite/subscribe)
(def ^:const event-unsubscribe :sente-lite/unsubscribe)
(def ^:const event-subscribed :sente-lite/subscribed)
(def ^:const event-publish :sente-lite/publish)
(def ^:const event-channel-msg :sente-lite/channel-msg)

;; ============================================================================
;; State Management
;; ============================================================================

(defonce ^:private connections (atom {}))      ; ws -> conn-data
(defonce ^:private connection-index (atom {})) ; conn-id -> ws
(defonce ^:private channels (atom {}))         ; channel-id -> #{conn-ids}
(defonce ^:private server-state (atom nil))
(defonce ^:private conn-counter (atom 0))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-config
  {:port 3000
   :host "0.0.0.0"
   :heartbeat {:enabled true
               :interval-ms 30000
               :timeout-ms 60000}
   :channels {:auto-create true}})

;; ============================================================================
;; Connection Management
;; ============================================================================

(defn- generate-conn-id []
  (str "conn-" (.now js/Date) "-" (swap! conn-counter inc)))

(defn- add-connection! [ws]
  (let [conn-id (generate-conn-id)
        conn-data {:id conn-id
                   :ws ws
                   :opened-at (.now js/Date)
                   :last-activity (.now js/Date)
                   :last-pong (.now js/Date)
                   :message-count 0}]
    (swap! connections assoc ws conn-data)
    (swap! connection-index assoc conn-id ws)
    (trove/log! {:level :debug
                 :id :sente-lite.server/conn-added
                 :data {:conn-id conn-id
                        :total-connections (count @connections)}})
    conn-data))

(defn- remove-connection! [ws]
  (when-let [conn-data (get @connections ws)]
    (let [conn-id (:id conn-data)]
      ;; Unsubscribe from all channels
      (doseq [[channel-id subscribers] @channels]
        (when (contains? subscribers conn-id)
          (swap! channels update channel-id disj conn-id)))
      ;; Remove connection
      (swap! connections dissoc ws)
      (swap! connection-index dissoc conn-id)
      (trove/log! {:level :debug
                   :id :sente-lite.server/conn-removed
                   :data {:conn-id conn-id
                          :total-connections (count @connections)}})
      conn-data)))

;; ============================================================================
;; Message Handling
;; ============================================================================

(defn- send-event! [ws event]
  (try
    (.send ws (pr-str event))
    (trove/log! {:level :trace
                 :id :sente-lite.server/msg-sent
                 :data {:event-id (first event)}})
    true
    (catch :default e
      (trove/log! {:level :error
                   :id :sente-lite.server/send-failed
                   :data {:error (.-message e)}})
      false)))

(defn- parse-message [raw-data]
  (try
    (let [parsed (edn/read-string (str raw-data))]
      (if (vector? parsed)
        {:event-id (first parsed)
         :data (second parsed)}
        {:error :invalid-format}))
    (catch :default e
      {:error :parse-failed :message (.-message e)})))

(defn- broadcast-to-channel! [channel-id data from-conn-id]
  (let [subscribers (get @channels channel-id #{})]
    (doseq [conn-id subscribers]
      (when-let [ws (get @connection-index conn-id)]
        (send-event! ws [event-channel-msg {:channel-id channel-id
                                            :data data
                                            :from from-conn-id}])))))

(defn- handle-message [ws raw-data]
  (let [conn-data (get @connections ws)
        conn-id (:id conn-data)
        {:keys [event-id data error]} (parse-message raw-data)]

    ;; Update activity
    (swap! connections update ws assoc
           :last-activity (.now js/Date)
           :message-count (inc (:message-count conn-data)))

    (when error
      (trove/log! {:level :warn
                   :id :sente-lite.server/parse-error
                   :data {:conn-id conn-id :error error}}))

    (when event-id
      (trove/log! {:level :trace
                   :id :sente-lite.server/msg-recv
                   :data {:conn-id conn-id :event-id event-id}})

      (cond
        ;; Ping -> Pong
        (= event-id event-ws-ping)
        (send-event! ws [event-ws-pong])

        ;; Pong -> update timestamp
        (= event-id event-ws-pong)
        (swap! connections update ws assoc :last-pong (.now js/Date))

        ;; Subscribe
        (= event-id event-subscribe)
        (let [channel-id (:channel-id data)]
          (swap! channels update channel-id (fnil conj #{}) conn-id)
          (trove/log! {:level :debug
                       :id :sente-lite.server/subscribed
                       :data {:conn-id conn-id :channel-id channel-id}})
          (send-event! ws [event-subscribed {:channel-id channel-id :success true}]))

        ;; Unsubscribe
        (= event-id event-unsubscribe)
        (let [channel-id (:channel-id data)]
          (swap! channels update channel-id disj conn-id)
          (send-event! ws [event-subscribed {:channel-id channel-id :success true}]))

        ;; Publish
        (= event-id event-publish)
        (let [channel-id (:channel-id data)
              msg-data (:data data)]
          (broadcast-to-channel! channel-id msg-data conn-id))

        ;; Echo anything else
        :else
        (send-event! ws [:sente-lite/echo {:original-event-id event-id
                                           :original-data data
                                           :conn-id conn-id
                                           :timestamp (.now js/Date)}])))))

;; ============================================================================
;; Connection Handler
;; ============================================================================

(defn- handle-connection [ws]
  (let [conn-data (add-connection! ws)
        conn-id (:id conn-data)]

    ;; Send handshake
    (send-event! ws [event-handshake [conn-id nil {:sente-lite-version "2.0.0"} true]])

    ;; Message handler
    (.on ws "message" #(handle-message ws %))

    ;; Close handler
    (.on ws "close"
         (fn [code reason]
           (trove/log! {:level :debug
                        :id :sente-lite.server/ws-close
                        :data {:conn-id conn-id :code code}})
           (remove-connection! ws)))

    ;; Error handler
    (.on ws "error"
         (fn [err]
           (trove/log! {:level :error
                        :id :sente-lite.server/ws-error
                        :data {:conn-id conn-id :error (.-message err)}})
           (remove-connection! ws)))))

;; ============================================================================
;; Heartbeat
;; ============================================================================

(defn- start-heartbeat! [config]
  (let [interval-ms (get-in config [:heartbeat :interval-ms] 30000)
        timeout-ms (get-in config [:heartbeat :timeout-ms] 60000)]
    (js/setInterval
     (fn []
       (let [now (.now js/Date)]
         (doseq [[ws conn-data] @connections]
           (let [time-since-pong (- now (:last-pong conn-data))]
             (if (> time-since-pong timeout-ms)
               (do
                 (trove/log! {:level :warn
                              :id :sente-lite.server/heartbeat-timeout
                              :data {:conn-id (:id conn-data)}})
                 (.close ws))
               (send-event! ws [event-ws-ping]))))))
     interval-ms)))

;; ============================================================================
;; Public API
;; ============================================================================

(defn start-server!
  "Start WebSocket server.

  Config options:
    :port        - Port to listen on (default: 3000)
    :host        - Host to bind to (default: \"0.0.0.0\")
    :heartbeat   - {:enabled true :interval-ms 30000 :timeout-ms 60000}

  Returns the server instance."
  ([] (start-server! {}))
  ([config]
   (let [merged-config (merge default-config config)
         port (:port merged-config)
         WebSocketServer (.-WebSocketServer ws-mod)
         server (WebSocketServer. #js {:port port})]

     (trove/log! {:level :info
                  :id :sente-lite.server/starting
                  :data {:port port}})

     (.on server "connection" handle-connection)

     (.on server "listening"
          (fn []
            (trove/log! {:level :info
                         :id :sente-lite.server/started
                         :data {:port port}})))

     ;; Start heartbeat if enabled
     (when (get-in merged-config [:heartbeat :enabled])
       (let [heartbeat-interval (start-heartbeat! merged-config)]
         (reset! server-state {:server server
                               :config merged-config
                               :heartbeat-interval heartbeat-interval
                               :start-time (.now js/Date)})))

     (when-not (get-in merged-config [:heartbeat :enabled])
       (reset! server-state {:server server
                             :config merged-config
                             :start-time (.now js/Date)}))

     server)))

(defn stop-server!
  "Stop the WebSocket server."
  []
  (when-let [state @server-state]
    (trove/log! {:level :info
                 :id :sente-lite.server/stopping
                 :data {:active-connections (count @connections)}})

    ;; Stop heartbeat
    (when-let [interval (:heartbeat-interval state)]
      (js/clearInterval interval))

    ;; Close all connections
    (doseq [[ws _] @connections]
      (.close ws))

    ;; Close server
    (.close (:server state))

    ;; Reset state
    (reset! server-state nil)
    (reset! connections {})
    (reset! connection-index {})
    (reset! channels {})

    (trove/log! {:level :info
                 :id :sente-lite.server/stopped
                 :data {}})))

(defn get-server-port
  "Get the port the server is listening on."
  []
  (when-let [state @server-state]
    (get-in state [:config :port])))

(defn get-server-stats
  "Get server statistics."
  []
  (let [state @server-state]
    {:running? (boolean state)
     :port (get-in state [:config :port])
     :connections {:active (count @connections)
                   :details (map #(select-keys % [:id :opened-at :message-count])
                                 (vals @connections))}
     :channels {:count (count @channels)
                :details (into {} (map (fn [[k v]] [k (count v)]) @channels))}
     :uptime-ms (when (:start-time state)
                  (- (.now js/Date) (:start-time state)))}))

(defn broadcast-message!
  "Send a v2 event to all connected clients."
  [event]
  (let [sent (atom 0)]
    (doseq [[ws _] @connections]
      (when (send-event! ws event)
        (swap! sent inc)))
    @sent))

(defn send-to-connection!
  "Send a v2 event to a specific connection by conn-id."
  [conn-id event]
  (when-let [ws (get @connection-index conn-id)]
    (send-event! ws event)))
