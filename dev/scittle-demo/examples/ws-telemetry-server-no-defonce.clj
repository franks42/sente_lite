;; WebSocket server with telemetry - NO defonce version
(require '[org.httpkit.server :as http])

(defn log! [level msg data]
  (println (str "[" level "] " msg " " (pr-str data))))

(log! :info "Starting WebSocket server" {:port 1342})

(def connections (atom #{}))
(log! :debug "Connections atom created" {:type (type connections)})

(defn ws-handler [req]
  (http/with-channel req channel
    (log! :info "New WebSocket connection" {:channel-id (hash channel)})
    (swap! connections conj channel)
    (http/send! channel "Welcome from server!")
    (log! :debug "Sent welcome message" {:channel-id (hash channel)})

    (http/on-receive channel
      (fn [msg]
        (log! :info "Received message" {:channel-id (hash channel) :message msg})
        (http/send! channel (str "Server echo: " msg))
        (log! :debug "Sent echo reply" {:channel-id (hash channel)})))

    (http/on-close channel
      (fn [status]
        (log! :info "Connection closed" {:channel-id (hash channel) :status status})
        (swap! connections disj channel)))))

(log! :debug "ws-handler defined" {:handler ws-handler})

(log! :debug "About to start server" {:port 1342})
(def server (http/run-server ws-handler {:port 1342}))
(log! :debug "Server started" {:server server :type (type server)})

(log! :info "WebSocket server ready" {:port 1342 :status :listening})
