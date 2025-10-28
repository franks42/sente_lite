#!/usr/bin/env bb
;; Quick test client for echo demo server

(require '[babashka.http-client.websocket :as ws]
         '[cheshire.core :as json]
         '[clojure.edn :as edn])

(println "Connecting to echo server on ws://localhost:1343...")

(def received-messages (atom []))
(def connection-established (promise))

(def client
  (ws/websocket
   {:uri "ws://localhost:1343/"  ; Added trailing slash
    :on-message (fn [ws msg _last?]
                  (try
                    (let [msg-str (str msg)  ; Convert HeapCharBuffer to String
                          parsed (edn/read-string msg-str)]
                      (swap! received-messages conj parsed)
                      (println "📨 Received:" parsed))
                    (catch Exception e
                      (println "⚠ Parse error:" (ex-message e) "from:" msg))))
    :on-open (fn [ws]
               (println "✓ Connected to server")
               (deliver connection-established true))
    :on-close (fn [ws status-code reason]
                (println "✗ Disconnected:" status-code reason))
    :on-error (fn [ws error]
                (println "⚠ Error:" error))}))

(println "Waiting for connection...")
(deref connection-established 5000 :timeout)

(if (= @connection-established true)
  (println "✓ Connection established")
  (do
    (println "❌ Connection timeout!")
    (System/exit 1)))

(Thread/sleep 500)

;; Send test message
(println "📤 Sending test message...")
(ws/send! client (pr-str {:type "test" :data "hello from bb client" :timestamp (System/currentTimeMillis)}))

(Thread/sleep 1000)

;; Send another message
(println "📤 Sending second message...")
(ws/send! client (pr-str {:type "custom" :foo "bar" :timestamp (System/currentTimeMillis)}))

(Thread/sleep 1000)

(println)
(println "Total messages received:" (count @received-messages))
(doseq [[i msg] (map-indexed vector @received-messages)]
  (println (str "  " (inc i) ".") msg))

(ws/close! client)
(println "✓ Test complete")
