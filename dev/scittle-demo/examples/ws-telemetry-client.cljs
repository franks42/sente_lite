;; WebSocket client with telemere-lite.scittle telemetry
(ns ws-telemetry-client
  (:require [telemere-lite.scittle :as tel]))

(tel/startup!)
(tel/info! "Initializing WebSocket client" {:target-port 1342})

(defonce ws-conn (atom nil))

(defn connect! []
  (let [ws (js/WebSocket. "ws://localhost:1342")]
    (tel/info! "Creating WebSocket connection" {:url "ws://localhost:1342"
                                                 :ready-state (.-readyState ws)})

    (set! (.-onopen ws)
          (fn [evt]
            (tel/info! "WebSocket connection established"
                      {:ready-state (.-readyState ws)
                       :event-type (.-type evt)})
            (.send ws "Hello from browser with telemetry!")
            (tel/debug! "Sent initial message" {:message "Hello from browser with telemetry!"})))

    (set! (.-onmessage ws)
          (fn [evt]
            (let [data (.-data evt)]
              (tel/info! "Received message from server"
                        {:message data
                         :length (.-length data)
                         :timestamp (.now js/Date)}))))

    (set! (.-onerror ws)
          (fn [evt]
            (tel/error! "WebSocket error occurred"
                       {:event evt
                        :ready-state (.-readyState ws)})))

    (set! (.-onclose ws)
          (fn [evt]
            (tel/info! "WebSocket connection closed"
                      {:code (.-code evt)
                       :reason (.-reason evt)
                       :was-clean (.-wasClean evt)})))

    (reset! ws-conn ws)
    (tel/debug! "Connection initiated" {:state "connecting"})))

(defn send-msg! [msg]
  (when-let [ws @ws-conn]
    (if (= (.-readyState ws) 1) ; OPEN = 1
      (do
        (.send ws msg)
        (tel/info! "Sent message to server" {:message msg :length (count msg)}))
      (tel/warn! "WebSocket not open, cannot send"
                {:ready-state (.-readyState ws)
                 :message msg}))))

(defn disconnect! []
  (when-let [ws @ws-conn]
    (tel/info! "Disconnecting WebSocket" {:ready-state (.-readyState ws)})
    (.close ws)
    (reset! ws-conn nil)))

;; Auto-connect on load
(connect!)

(tel/info! "WebSocket client loaded successfully"
          {:auto-connect true
           :functions ["connect!" "send-msg!" "disconnect!"]})
