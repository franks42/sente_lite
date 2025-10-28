;; Minimal WebSocket client with telemetry connecting to port 1342

;; Stub telemetry for testing (replace with real telemere-lite when integrated)
(defn log! [level msg data]
  (println (str "[" level "] " msg " " (pr-str data))))

(log! :info "Initializing WebSocket client" {:target-port 1342})

(defonce ws-conn (atom nil))

(defn connect! []
  (let [ws (js/WebSocket. "ws://localhost:1342")]
    (log! :info "Creating WebSocket connection" {:url "ws://localhost:1342"})

    (set! (.-onopen ws)
          (fn [evt]
            (log! :info "WebSocket connection established" {:ready-state (.-readyState ws)})
            (.send ws "Hello from browser!")
            (log! :debug "Sent initial message" {:message "Hello from browser!"})))

    (set! (.-onmessage ws)
          (fn [evt]
            (let [data (.-data evt)]
              (log! :info "Received message from server" {:message data}))))

    (set! (.-onerror ws)
          (fn [evt]
            (log! :error "WebSocket error occurred" {:event evt})))

    (set! (.-onclose ws)
          (fn [evt]
            (log! :info "WebSocket connection closed" {:code (.-code evt) :reason (.-reason evt)})))

    (reset! ws-conn ws)
    (log! :debug "Connection initiated" {:state "connecting"})))

(defn send-msg! [msg]
  (when-let [ws @ws-conn]
    (if (= (.-readyState ws) 1) ; OPEN = 1
      (do
        (.send ws msg)
        (log! :info "Sent message to server" {:message msg}))
      (log! :warn "WebSocket not open, cannot send" {:ready-state (.-readyState ws) :message msg}))))

(defn disconnect! []
  (when-let [ws @ws-conn]
    (log! :info "Disconnecting WebSocket" {})
    (.close ws)
    (reset! ws-conn nil)))

;; Auto-connect on load
(connect!)

(log! :info "WebSocket client loaded successfully" {:auto-connect true})
