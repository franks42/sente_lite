#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[sente-lite.server :as server]
         '[mp-utils :as mp])

;;
;; Multi-Process Test Server (Reconnection Test)
;;
;; Usage: bb mp_server_reconnect.bb <test-id> <port> <duration-sec>
;;
;; Starts a WebSocket server on FIXED port (for reconnection testing),
;; signals ready, then runs for specified duration.
;;

(defn parse-args [args]
  (when (< (count args) 3)
    (println "Usage: bb mp_server_reconnect.bb <test-id> <port> <duration-sec>")
    (System/exit 1))
  {:test-id (first args)
   :port (Integer/parseInt (second args))
   :duration-sec (Integer/parseInt (nth args 2))})

(defn -main [& args]
  (let [{:keys [test-id port duration-sec]} (parse-args args)
        process-id "server"]

    (println "[info] " "=== Multi-Process Test Server (Reconnection) ==="
              {:test-id test-id :port port :duration duration-sec})

    ;; Start server on fixed port
    (println "[info] " "Starting server on fixed port" {:port port})
    (def server-instance
      (server/start-server!
       {:port port
        :host "localhost"
        :wire-format :json
        :telemetry {:enabled true
                    :handler-id (keyword (str "mp-reconnect-" test-id))}
        :heartbeat {:enabled true
                    :ping-interval-ms 5000}
        :channels {:auto-create true}}))

    ;; Get actual port and verify (unless ephemeral port was requested)
    (def actual-port (server/get-server-port))
    (when (and (not= port 0) (not= port actual-port))
      (println "ERROR:" "Port mismatch" {:expected port :actual actual-port})
      (server/stop-server!)
      (System/exit 1))

    (println "[info] " "Server started" {:port actual-port})
    (mp/write-port! test-id actual-port)

    ;; Signal server ready
    (mp/signal-ready! test-id process-id)
    (println "[info] " "Server ready signal sent")

    ;; Track connection events
    (def connections (atom 0))
    (def messages-received (atom 0))
    (def messages-sent (atom 0))

    ;; Run for specified duration
    (println "[info] " "Running test" {:duration-sec duration-sec})
    (Thread/sleep (* duration-sec 1000))

    ;; Get server stats
    (def stats (server/get-server-stats))
    (println "[info] " "Server stats" {:stats stats})

    ;; Write result
    (def result {:status :passed
                 :port actual-port
                 :connections @connections
                 :messages-received @messages-received
                 :messages-sent @messages-sent
                 :stats stats
                 :failures 0})

    (mp/write-result! test-id process-id result)
    (println "[info] " "Server result written" {:result result})

    ;; Stop server
    (server/stop-server!)
    (println "[info] " "Server stopped")

    (System/exit 0)))

(apply -main *command-line-args*)
