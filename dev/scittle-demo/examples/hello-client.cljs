;; Minimal WebSocket client connecting to port 1342
(defonce ws-conn (atom nil))

(defn connect! []
  (let [ws (js/WebSocket. "ws://localhost:1342")]

    (set! (.-onopen ws)
          (fn [evt]
            (println "Client: Connected to server!")
            (.send ws "Hello from browser!")))

    (set! (.-onmessage ws)
          (fn [evt]
            (println "Client received:" (.-data evt))))

    (set! (.-onerror ws)
          (fn [evt]
            (println "Client: WebSocket error!" evt)))

    (set! (.-onclose ws)
          (fn [evt]
            (println "Client: Connection closed")))

    (reset! ws-conn ws)
    (println "Client: Connecting to ws://localhost:1342...")))

(defn send-msg! [msg]
  (when-let [ws @ws-conn]
    (if (= (.-readyState ws) 1) ; OPEN = 1
      (do
        (.send ws msg)
        (println "Client sent:" msg))
      (println "Client: WebSocket not open, readyState:" (.-readyState ws)))))

(defn disconnect! []
  (when-let [ws @ws-conn]
    (.close ws)
    (reset! ws-conn nil)
    (println "Client: Disconnected")))

;; Auto-connect on load
(connect!)

(println "✅ WebSocket client loaded")
