#!/usr/bin/env bb
;; Test client for heartbeat demo - auto-pong enabled

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn])

(println "Connecting to heartbeat server on ws://localhost:1344...")

(def ping-count (atom 0))
(def pong-sent-count (atom 0))
(def connection-established (promise))

(def client
  (ws/websocket
   {:uri "ws://localhost:1344/"
    :on-message (fn [ws msg _last?]
                  (try
                    (let [msg-str (str msg)
                          parsed (edn/read-string msg-str)
                          msg-type (:type parsed)]
                      (case msg-type
                        :ping
                        (do
                          (swap! ping-count inc)
                          (println "💓 Received ping #" @ping-count)
                          ;; Auto-pong
                          (ws/send! ws (pr-str {:type "pong"
                                                :timestamp (System/currentTimeMillis)}))
                          (swap! pong-sent-count inc)
                          (println "💚 Sent pong #" @pong-sent-count))

                        :pong-ack
                        (println "✓ Server acknowledged pong")

                        :welcome
                        (println "✓ Welcome message:" parsed)

                        (println "📨 Other message:" parsed)))
                    (catch Exception e
                      (println "⚠ Error:" (ex-message e)))))
    :on-open (fn [ws]
               (println "✓ Connected to heartbeat server")
               (deliver connection-established true))
    :on-close (fn [ws status-code reason]
                (println "✗ Disconnected:" status-code reason)
                (println "Stats: pings=" @ping-count " pongs=" @pong-sent-count))
    :on-error (fn [ws error]
                (println "⚠ Error:" error))}))

(println "Waiting for connection...")
(deref connection-established 5000 :timeout)

(if (= @connection-established true)
  (do
    (println "✓ Connection established")
    (println "Waiting for heartbeat pings... (server sends every 5 seconds)")
    (println "Will run for 15 seconds")

    ;; Run for 15 seconds to see multiple heartbeats
    (Thread/sleep 15000)

    (println)
    (println "=== Final Stats ===")
    (println "Pings received:" @ping-count)
    (println "Pongs sent:" @pong-sent-count)

    (ws/close! client)
    (println "✓ Test complete"))
  (do
    (println "❌ Connection timeout!")
    (System/exit 1)))
