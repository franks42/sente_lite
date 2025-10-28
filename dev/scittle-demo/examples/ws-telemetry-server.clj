;; WebSocket server with telemere-lite telemetry on port 1342
(require '[org.httpkit.server :as http])

;; Simple telemetry stub for BB (replace with real telemere-lite when integrated)
(defn log! [level msg data]
  (println (str "[" level "] " msg " " (pr-str data))))

(log! :info "Starting WebSocket server" {:port 1342})

(defonce connections (atom #{}))
(log! :debug "Connections atom initialized" {:type (type connections)})

(defn ws-handler [req]
  (http/with-channel req channel
    (log! :info "New WebSocket connection" {:channel-id (hash channel)
                                            :remote-addr (:remote-addr req)})
    (swap! connections conj channel)

    ;; Send welcome message
    (http/send! channel "Welcome from server!")
    (log! :debug "Sent welcome message" {:channel-id (hash channel)})

    ;; Handle incoming messages
    (http/on-receive channel
      (fn [msg]
        (log! :info "Received message" {:channel-id (hash channel)
                                        :message msg
                                        :length (count msg)})
        (http/send! channel (str "Server echo: " msg))
        (log! :debug "Sent echo reply" {:channel-id (hash channel)})))

    ;; Handle disconnect
    (http/on-close channel
      (fn [status]
        (log! :info "Connection closed" {:channel-id (hash channel)
                                         :status status
                                         :active-connections (count @connections)})
        (swap! connections disj channel)))))

(log! :debug "ws-handler function defined" {:handler ws-handler})

;; Start server on port 1342
(log! :debug "About to call http/run-server" {:port 1342})
(def server
  (http/run-server ws-handler {:port 1342}))
(log! :debug "http/run-server returned" {:server server :type (type server)})

(log! :info "WebSocket server started successfully"
      {:port 1342
       :status :listening
       :handler "ws-telemetry-server"})
