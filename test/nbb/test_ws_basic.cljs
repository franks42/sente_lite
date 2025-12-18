(ns test-ws-basic
  "Basic WebSocket test with nbb to understand the API"
  (:require ["ws" :as ws]))

(println "=== nbb WebSocket Basic Test ===")
(println)

;; Check what we get from the ws module
(println "ws module type:" (type ws))
(println "ws keys:" (js/Object.keys ws))

;; Try creating a WebSocket client
(println)
(println "Creating WebSocket client...")

(def WebSocket (.-WebSocket ws))
(println "WebSocket constructor:" (type WebSocket))

(def client (WebSocket. "ws://echo.websocket.org"))

(println "Client type:" (type client))
(println "Client readyState:" (.-readyState client))

(.on client "open"
     (fn []
       (println "Connected!")
       (.send client "Hello from nbb!")
       (println "Message sent")))

(.on client "message"
     (fn [data]
       (println "Received:" (str data))))

(.on client "close"
     (fn [code reason]
       (println "Closed:" code reason)))

(.on client "error"
     (fn [err]
       (println "Error:" (.-message err))))

;; Keep alive for a bit
(js/setTimeout
 (fn []
   (println "Closing...")
   (.close client)
   (println "Done!"))
 3000)
