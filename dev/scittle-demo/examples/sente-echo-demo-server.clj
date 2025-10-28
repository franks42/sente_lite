#!/usr/bin/env bb
;;
;; sente-lite Echo Demo Server
;;
;; Demonstrates basic sente-lite server with default echo behavior.
;; Any message sent by client gets echoed back with metadata.
;;
;; Usage:
;;   bb dev/scittle-demo/examples/sente-echo-demo-server.clj
;;
;; Then open browser client at:
;;   http://localhost:1340/examples/sente-echo-demo-client.html

(require '[sente-lite.server :as server])

(println "Starting sente-lite echo demo server...")

;; Start server with minimal config
;; Default behavior: echo any unrecognized message type
(def server-instance
  (server/start-server!
   {:port 1343
    :host "localhost"
    :wire-format :edn  ; Default EDN for Clojure-to-Clojure
    :telemetry {:enabled true}
    :channels {:auto-create true}}))

(def actual-port (server/get-server-port))

(println (str "✓ Server listening on ws://localhost:" actual-port))
(println "")
(println "Echo behavior:")
(println "  - Client sends: {:type \"test\" :data \"hello\"}")
(println "  - Server echoes: {:type \"echo\" :original {...} :conn-id ... :timestamp ...}")
(println "")
(println "Press Ctrl+C to stop")

;; Keep server running
@(promise)
