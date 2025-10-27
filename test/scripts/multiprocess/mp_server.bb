#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[sente-lite.server :as server]
         '[telemere-lite.core :as tel]
         '[mp-utils :as mp])

;;
;; Multi-Process Test Server
;;
;; Usage: bb mp_server.bb <test-id> <duration-sec>
;;
;; Starts a WebSocket server on ephemeral port, publishes port to discovery
;; file, signals ready, then runs for specified duration.
;;

(defn parse-args [args]
  (when (< (count args) 2)
    (println "Usage: bb mp_server.bb <test-id> <duration-sec>")
    (System/exit 1))
  {:test-id (first args)
   :duration-sec (Integer/parseInt (second args))})

(defn -main [& args]
  (let [{:keys [test-id duration-sec]} (parse-args args)
        process-id "server"]

    (tel/log! :info "=== Multi-Process Test Server ==="
              {:test-id test-id :duration duration-sec})

    ;; Start server on ephemeral port
    (tel/log! :info "Starting server on ephemeral port")
    (def server-instance
      (server/start-server!
        {:port 0  ; Ephemeral port
         :host "localhost"
         :wire-format :json
         :telemetry {:enabled true
                     :handler-id (keyword (str "mp-test-" test-id))}
         :heartbeat {:enabled true
                     :ping-interval-ms 5000}
         :channels {:auto-create true}}))

    ;; Get actual port and publish
    (def actual-port (server/get-server-port))
    (tel/log! :info "Server started" {:port actual-port})
    (mp/write-port! test-id actual-port)

    ;; Signal server ready
    (mp/signal-ready! test-id process-id)
    (tel/log! :info "Server ready signal sent")

    ;; Track connection events
    (def connections (atom 0))
    (def messages-received (atom 0))
    (def messages-sent (atom 0))

    ;; Add connection tracking (simplified - relies on server internals)
    ;; For now, we'll just track via telemetry

    ;; Run for specified duration
    (tel/log! :info "Running test" {:duration-sec duration-sec})
    (Thread/sleep (* duration-sec 1000))

    ;; Get server stats
    (def stats (server/get-server-stats))
    (tel/log! :info "Server stats" {:stats stats})

    ;; Write result
    (def result {:status :passed
                 :port actual-port
                 :connections @connections
                 :messages-received @messages-received
                 :messages-sent @messages-sent
                 :stats stats
                 :failures 0})

    (mp/write-result! test-id process-id result)
    (tel/log! :info "Server result written" {:result result})

    ;; Stop server
    (server/stop-server!)
    (tel/log! :info "Server stopped")

    (System/exit 0)))

(apply -main *command-line-args*)
