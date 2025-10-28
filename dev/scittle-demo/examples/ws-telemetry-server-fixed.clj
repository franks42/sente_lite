;; WebSocket server with telemere-lite telemetry - FIXED VERSION
;; Uses http/as-channel (new API) instead of deprecated with-channel + on-receive

(require '[org.httpkit.server :as http])

(defn log! [level msg data]
  (println (str "[" level "] " msg " " (pr-str data))))

(log! :info "Starting WebSocket server" {:port 1342})

(def connections (atom #{}))

(defn ws-handler [req]
  (http/as-channel req
    {:on-open (fn [ch]
                (log! :info "New WebSocket connection" {:channel-id (hash ch)
                                                         :remote-addr (:remote-addr req)})
                (swap! connections conj ch)
                (http/send! ch "Welcome from server with telemetry!")
                (log! :debug "Sent welcome message" {:channel-id (hash ch)}))

     :on-receive (fn [ch msg]
                   (log! :info "Received message" {:channel-id (hash ch)
                                                   :message msg
                                                   :length (count msg)})
                   (http/send! ch (str "Server echo: " msg))
                   (log! :debug "Sent echo reply" {:channel-id (hash ch)}))

     :on-close (fn [ch status]
                 (log! :info "Connection closed" {:channel-id (hash ch)
                                                  :status status
                                                  :active-connections (count @connections)})
                 (swap! connections disj ch))}))

(log! :debug "WebSocket handler defined" {:api "as-channel"})

;; Start server
(log! :debug "About to start server" {:port 1342})
(def server (http/run-server ws-handler {:port 1342}))
(log! :debug "Server started" {:server server})

(log! :info "WebSocket server ready"
      {:port 1342
       :status :listening
       :api "as-channel (http-kit v2.4.0+)"})
