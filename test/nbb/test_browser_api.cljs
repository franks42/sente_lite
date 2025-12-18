(ns test-browser-api
  "Test if nbb's ws module supports browser-style WebSocket API"
  (:require ["ws" :as ws-mod]))

(println "=== Test: Browser-style WebSocket API in nbb ===")
(println)

(def WebSocket (.-WebSocket ws-mod))
(def client (WebSocket. "ws://localhost:9090/"))

;; Try browser-style API
(println "Testing browser-style properties...")
(println "  client.onopen type:" (type (.-onopen client)))
(println "  client.onmessage type:" (type (.-onmessage client)))

;; Set handlers browser-style
(set! (.-onopen client)
  (fn [event]
    (println "  onopen called!")
    (println "  event type:" (type event))))

(set! (.-onmessage client)
  (fn [event]
    (println "  onmessage called!")
    (println "  event.data:" (.-data event))
    (.close client)))

(set! (.-onclose client)
  (fn [event]
    (println "  onclose called!")
    (println "Done!")))

(set! (.-onerror client)
  (fn [event]
    (println "  onerror called!")))

;; Keep alive
(js/setTimeout #() 5000)
