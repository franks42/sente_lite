;; sente-lite client demo with telemetry
;; Load this into browser nREPL (port 1339)

(ns sente-demo-client
  (:require [sente-lite.client-scittle :as sente]
            [telemere-lite.scittle :as tel]))

;; Initialize telemetry
(tel/startup!)
(tel/info! "Starting sente-lite client demo" {:server-url "ws://localhost:1343/ws"})

;; Create client with handlers
(def client
  (sente/make-client!
    {:url "ws://localhost:1343/ws"
     :on-open (fn []
                (tel/info! "Connected to sente-lite server" {})
                (println "✅ Connected to server!"))
     :on-message (fn [msg]
                   (tel/info! "Received from server"
                             {:message-type (first msg)
                              :data (second msg)})
                   (println "📨 Server says:" msg))
     :on-close (fn [event]
                 (tel/info! "Disconnected from server"
                           {:code (.-code event)
                            :reason (.-reason event)})
                 (println "❌ Disconnected from server"))
     :on-error (fn [event]
                 (tel/error! "Connection error" {:event event})
                 (println "⚠️ Connection error"))}))

(tel/info! "sente-lite client created" {:client-id client})
(println "🎯 Client ID:" client)

;; Helper function to send test messages
(defn send-test! [n]
  (tel/info! "Sending test message" {:test-number n})
  (sente/send! client [:client/test {:number n
                                     :message (str "Test message #" n)
                                     :timestamp (.now js/Date)}]))

(println "")
(println "=== sente-lite Client Demo ===")
(println "Try: (send-test! 1)")
(println "     (send-test! 2)")
(println "     (sente/get-stats client)")
(println "     (sente/close! client)")
