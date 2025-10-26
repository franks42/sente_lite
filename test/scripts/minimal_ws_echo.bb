#!/usr/bin/env bb

(require '[org.httpkit.server :as http])

(println "=== Minimal WebSocket Echo Server ===")
(println)

(defn ws-handler [request]
  (println "Received request:" (:request-method request) (:uri request))
  (println "Is WebSocket?" (:websocket? request))

  (if (:websocket? request)
    (http/as-channel request
                     {:on-open (fn [ch]
                                 (println "WebSocket opened:" ch)
                                 (let [result (http/send! ch "WELCOME FROM SERVER")]
                                   (println "Welcome send result:" result)))

                      :on-receive (fn [ch msg]  ; ← FIX: was :on-message, should be :on-receive!
                                    (println "Received message:" msg)
                                    (println "Echoing back...")
                                    (let [result (http/send! ch (str "ECHO: " msg))]
                                      (println "Echo send result:" result)))

                      :on-close (fn [ch status]
                                  (println "WebSocket closed:" status))

                      :on-error (fn [ch error]
                                  (println "WebSocket error:" error))})

    {:status 200
     :body "HTTP endpoint - use WebSocket to connect"}))

(println "Starting server on port 9090...")
(def server (http/run-server ws-handler {:port 9090}))
(println "Server started!")
(println)
(println "Test with: websocat ws://localhost:9090")
(println "Or:        wscat -c ws://localhost:9090")
(println)
(println "Press Ctrl+C to stop")

;; Keep server running
@(promise)
