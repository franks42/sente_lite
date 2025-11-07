(ns sente-lite.server-simple
  "Simple WebSocket server foundation for sente-lite with embedded telemetry"
  (:require [telemere-lite.core :as tel]
            [cheshire.core :as json]
            [org.httpkit.server :as http])
  (:import [java.lang System Exception]))

;; Connection state management
(defonce ^:private connections (atom {}))
(defonce ^:private server-state (atom nil))

;; Configuration defaults
(def default-config
  {:port 3000
   :host "localhost"
   :telemetry {:enabled true
               :handler-id :sente-lite-server}})

;; Connection lifecycle management
(defn- generate-connection-id []
  (str "conn-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defn- add-connection! [channel conn-id]
  (let [conn-data {:id conn-id
                   :channel channel
                   :opened-at (System/currentTimeMillis)
                   :last-activity (System/currentTimeMillis)
                   :message-count 0}]
    (swap! connections assoc channel conn-data)
    (tel/log! {:level :debug
               :id :sente-lite.server/conn-added
               :data {:conn-id conn-id
                      :total-connections (count @connections)}})
    conn-data))

(defn- remove-connection! [channel]
  (when-let [conn-data (get @connections channel)]
    (let [duration (- (System/currentTimeMillis) (:opened-at conn-data))]
      (swap! connections dissoc channel)
      (tel/log! {:level :debug
                 :id :sente-lite.server/conn-removed
                 :data {:conn-id (:id conn-data)
                        :duration-ms duration
                        :message-count (:message-count conn-data)
                        :total-connections (count @connections)}})
      conn-data)))

;; WebSocket handlers
(defn- on-websocket-open [channel _config]
  (let [conn-id (generate-connection-id)
        _conn-data (add-connection! channel conn-id)]
    (http/send! channel (json/generate-string {:type :welcome
                                               :conn-id conn-id
                                               :server-time (System/currentTimeMillis)}))
    (tel/log! {:level :debug
               :id :sente-lite.server/ws-open
               :data {:conn-id conn-id}})))

(defn- on-websocket-message [channel raw-message _config]
  (when-let [conn-data (get @connections channel)]
    (let [conn-id (:id conn-data)]
      (tel/log! {:level :trace
                 :id :sente-lite.server/ws-msg-recv
                 :data {:conn-id conn-id
                        :size (count raw-message)}})
      (try
        (let [parsed (json/parse-string raw-message true)]
          ;; Echo back
          (http/send! channel (json/generate-string {:type :echo
                                                     :original parsed
                                                     :conn-id conn-id
                                                     :timestamp (System/currentTimeMillis)})))
        (catch Exception e
          (tel/error! {:id :sente-lite.server/parse-failed
                       :error e
                       :data {:conn-id conn-id}}))))))

(defn- on-websocket-close [channel status _config]
  (when-let [conn-data (remove-connection! channel)]
    (tel/log! {:level :debug
               :id :sente-lite.server/ws-close
               :data {:conn-id (:id conn-data) :status status}})))

(defn- on-websocket-error [channel throwable _config]
  (when-let [conn-data (get @connections channel)]
    (tel/error! {:id :sente-lite.server/ws-error
                 :error throwable
                 :data {:conn-id (:id conn-data)}})
    (remove-connection! channel)))

;; WebSocket request handler
(defn- websocket-handler [request config]
  (tel/log! {:level :trace
             :id :sente-lite.server/ws-req
             :data {:uri (:uri request) :websocket? (:websocket? request)}})
  (if-not (:websocket? request)
    {:status 426 :headers {"Upgrade" "websocket"} :body "WebSocket upgrade required"}
    (http/as-channel request
                     {:on-open    #(on-websocket-open % config)
                      :on-message #(on-websocket-message %1 %2 config)
                      :on-close   #(on-websocket-close %1 %2 config)
                      :on-error   #(on-websocket-error %1 %2 config)})))

;; HTTP request handler
(defn- http-handler [config]
  (fn [request]
    (tel/log! {:level :trace
               :id :sente-lite.server/http-req
               :data {:method (:request-method request) :uri (:uri request)}})
    (cond
      (:websocket? request)
      (websocket-handler request config)

      (= (:uri request) "/health")
      {:status 200
       :headers {"content-type" "application/json"}
       :body (json/generate-string {:status "healthy"
                                    :connections (count @connections)})}

      (= (:uri request) "/stats")
      {:status 200
       :headers {"content-type" "application/json"}
       :body (json/generate-string {:active-connections (count @connections)
                                    :server-config (select-keys config [:port :host])})}

      :else
      {:status 404 :headers {"content-type" "text/plain"} :body "Not found"})))

;; Public API
(defn start-server!
  "Start WebSocket server with configuration"
  ([config]
   (let [merged-config (merge default-config config)]
     (when (get-in merged-config [:telemetry :enabled])
       (tel/startup!)
       (tel/add-file-handler! (get-in merged-config [:telemetry :handler-id])
                              "sente-lite-server.log"))

     (tel/log! {:level :info
                :id :sente-lite.server/starting
                :data merged-config})

     (let [server (http/run-server (http-handler merged-config)
                                   {:port (:port merged-config)
                                    :host (:host merged-config)})]
       (reset! server-state {:server server
                             :config merged-config
                             :start-time (System/currentTimeMillis)})
       (tel/log! {:level :info
                  :id :sente-lite.server/started
                  :data {:port (:port merged-config)
                         :host (:host merged-config)}})
       server)))
  ([]
   (start-server! {})))

(defn stop-server!
  "Stop the WebSocket server"
  []
  (when-let [state @server-state]
    (tel/log! {:level :info
               :id :sente-lite.server/stopping
               :data {:active-connections (count @connections)}})
    (doseq [[channel _conn-data] @connections]
      (http/close channel))
    ((:server state))
    (reset! server-state nil)
    (reset! connections {})
    (tel/log! {:level :info
               :id :sente-lite.server/stopped
               :data {}})
    (tel/shutdown-telemetry!)))

(defn get-server-stats
  "Get current server statistics"
  []
  (let [state @server-state
        active-conns @connections]
    {:running? (boolean state)
     :config (:config state)
     :uptime-ms (when (:start-time state)
                  (- (System/currentTimeMillis) (:start-time state)))
     :connections {:active (count active-conns)
                   :details (map #(select-keys % [:id :opened-at :message-count])
                                 (vals active-conns))}
     :telemetry (tel/get-handler-stats)}))