;; sente-lite server demo with telemetry
;; Load this into BB nREPL (port 1338)

(require '[sente-lite.server :as sente]
         '[telemere-lite.core :as tel])

;; Initialize telemetry
(tel/startup!)
(tel/info! "Starting sente-lite server demo" {:port 1343})

;; Message handler with telemetry
(defn handle-client-message [conn-id event-data]
  (tel/info! "Received client message"
            {:conn-id conn-id
             :event-type (first event-data)
             :data (second event-data)})

  ;; Echo back to client
  (sente/send! conn-id [:server/echo {:original event-data
                                      :timestamp (System/currentTimeMillis)}]))

;; Start server
(def server
  (sente/start-server!
    {:port 1343
     :ws-path "/ws"
     :on-message handle-client-message
     :telemetry {:enabled true}}))

(tel/info! "sente-lite server started"
          {:port 1343
           :ws-path "/ws"
           :status :listening})

(println "✅ sente-lite server ready on port 1343")
