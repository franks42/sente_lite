#!/usr/bin/env bb

(require '[babashka.http-client.websocket :as ws])

(println "=== Minimal WebSocket Client (Native Babashka) ===")
(println)

(def messages-received (atom []))

(println "Connecting to ws://localhost:9090...")
(def ws-client
  (ws/websocket
   {:uri "ws://localhost:9090"
    :on-open (fn [ws]
               (println "✅ WebSocket OPENED"))
    :on-message (fn [ws data last]
                  (println "📨 RECEIVED:" data)
                  (swap! messages-received conj data))
    :on-close (fn [ws status reason]
                (println "❌ WebSocket CLOSED:" status reason))
    :on-error (fn [ws error]
                (println "⚠️  ERROR:" (.getMessage error)))}))

(println "Connected!")
(println)

(Thread/sleep 1000)
(println "Messages received so far:" @messages-received)
(println)

(println "Sending message: 'Hello Server'")
(ws/send! ws-client "Hello Server")
(println "Send completed!")
(println)

(Thread/sleep 2000)
(println)
(println "=== Final Results ===")
(println "Messages received:" @messages-received)
(println "Count:" (count @messages-received))

(ws/close! ws-client 1000 "Normal closure")
(Thread/sleep 500)
(System/exit 0)
