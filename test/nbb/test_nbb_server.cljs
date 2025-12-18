(ns test-nbb-server
  "Test a simple WebSocket server in nbb using ws package"
  (:require ["ws" :as ws-mod]
            [clojure.edn :as edn]))

(println "=== Test: nbb WebSocket Server ===")
(println)

(def WebSocketServer (.-WebSocketServer ws-mod))

;; Event IDs
(def event-handshake :chsk/handshake)
(def event-ws-ping :chsk/ws-ping)
(def event-ws-pong :chsk/ws-pong)

;; Connection tracking
(def connections (atom {}))
(def conn-counter (atom 0))

(defn generate-conn-id []
  (str "conn-" (.now js/Date) "-" (swap! conn-counter inc)))

(defn send-event! [ws event]
  (.send ws (pr-str event)))

(defn parse-message [raw-data]
  (try
    (let [parsed (edn/read-string (str raw-data))]
      (if (vector? parsed)
        {:event-id (first parsed)
         :data (second parsed)}
        {:error :invalid-format}))
    (catch :default e
      {:error :parse-failed})))

(defn handle-connection [ws]
  (let [conn-id (generate-conn-id)]
    (println "  Client connected:" conn-id)
    (swap! connections assoc ws {:id conn-id :opened-at (.now js/Date)})

    ;; Send handshake
    (send-event! ws [event-handshake [conn-id nil {:sente-lite-version "2.0.0-nbb"} true]])

    (.on ws "message"
         (fn [raw-data]
           (let [parsed (parse-message raw-data)
                 event-id (:event-id parsed)
                 data (:data parsed)]
             (println "  Received:" event-id "from" conn-id)

             (cond
            ;; Ping -> Pong
               (= event-id event-ws-ping)
               (send-event! ws [event-ws-pong])

            ;; Echo anything else
               :else
               (send-event! ws [:sente-lite/echo {:original-event-id event-id
                                                  :original-data data
                                                  :conn-id conn-id
                                                  :timestamp (.now js/Date)}])))))

    (.on ws "close"
         (fn []
           (println "  Client disconnected:" conn-id)
           (swap! connections dissoc ws)))

    (.on ws "error"
         (fn [err]
           (println "  Error for" conn-id ":" (.-message err))))))

;; Create server
(def port 9091)
(println "Starting nbb WebSocket server on port" port)

(def server (WebSocketServer. #js {:port port}))

(.on server "connection" handle-connection)

(.on server "listening"
     (fn []
       (println "Server listening on ws://localhost:" port)))

;; Keep alive
(println "Press Ctrl+C to stop")
(js/setInterval #() 1000)
