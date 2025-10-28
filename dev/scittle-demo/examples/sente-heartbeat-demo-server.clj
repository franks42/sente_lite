#!/usr/bin/env bb
;;
;; sente-lite Heartbeat Demo Server
;;
;; Demonstrates server-side heartbeat (ping/pong) mechanism.
;; Server sends ping messages at regular intervals.
;; Client responds with pong to maintain connection health.
;;
;; Usage:
;;   bb dev/scittle-demo/examples/sente-heartbeat-demo-server.clj
;;
;; Then open browser client and load:
;;   dev/scittle-demo/examples/sente-heartbeat-demo-client.cljs

(require '[sente-lite.server :as server])

(println "Starting sente-lite heartbeat demo server...")

;; Start server with heartbeat enabled
(def server-instance
  (server/start-server!
   {:port 1344
    :host "localhost"
    :wire-format :edn  ; Default EDN for Clojure-to-Clojure
    :telemetry {:enabled true}
    :heartbeat {:enabled true
                :interval-ms 5000}  ; Send ping every 5 seconds
    :channels {:auto-create true}}))

(def actual-port (server/get-server-port))

(println (str "✓ Server listening on ws://localhost:" actual-port))
(println "")
(println "Heartbeat enabled:")
(println "  - Server sends ping every 5 seconds")
(println "  - Client responds with pong (auto-pong enabled)")
(println "  - Connection health monitored via ping/pong")
(println "")
(println "Press Ctrl+C to stop")

;; Keep server running
@(promise)
