;; Minimal WebSocket server on port 1342
(require '[org.httpkit.server :as http])

(defonce connections (atom #{}))

(defn ws-handler [req]
  (http/with-channel req channel
    (println "Server: New connection!")
    (swap! connections conj channel)

    ;; Send welcome message
    (http/send! channel "Welcome from server!")

    ;; Handle incoming messages
    (http/on-receive channel
      (fn [msg]
        (println "Server received:" msg)
        (http/send! channel (str "Server echo: " msg))))

    ;; Handle disconnect
    (http/on-close channel
      (fn [status]
        (println "Server: Connection closed, status:" status)
        (swap! connections disj channel)))))

;; Start server on port 1342
(defonce server
  (http/run-server ws-handler {:port 1342}))

(println "✅ WebSocket server started on port 1342")
