#!/usr/bin/env bb
;;
;; sente-lite Pub/Sub Demo Server
;;
;; Demonstrates channel-based pub/sub messaging.
;; - Clients subscribe to channels by channel-id
;; - Clients publish messages to channels
;; - Server broadcasts messages to all channel subscribers
;; - Supports multiple channels and multiple subscribers per channel
;;
;; Usage:
;;   bb dev/scittle-demo/examples/sente-pubsub-demo-server.clj
;;
;; Then open browser client and load:
;;   dev/scittle-demo/examples/sente-pubsub-demo-client.cljs

(require '[sente-lite.server :as server])

(println "Starting sente-lite pub/sub demo server...")

;; Start server with auto-create channels enabled
(def server-instance
  (server/start-server!
   {:port 1345
    :host "localhost"
    :wire-format :edn  ; Default EDN for Clojure-to-Clojure
    :telemetry {:enabled true}
    :heartbeat {:enabled true
                :ping-interval-ms 10000}  ; Send ping every 10 seconds
    :channels {:auto-create true          ; Auto-create channels on subscribe
               :default-config {:retain-messages 10}}}))  ; Retain last 10 messages

(def actual-port (server/get-server-port))

(println (str "✓ Server listening on ws://localhost:" actual-port))
(println "")
(println "Pub/Sub features:")
(println "  - Auto-create channels on first subscribe")
(println "  - Multiple subscribers per channel")
(println "  - Broadcast messages to all subscribers")
(println "  - Retain last 10 messages per channel")
(println "")
(println "Available operations:")
(println "  - Subscribe:   {:type \"subscribe\" :channel-id \"channel-name\"}")
(println "  - Publish:     {:type \"publish\" :channel-id \"channel-name\" :data {...}}")
(println "  - Unsubscribe: {:type \"unsubscribe\" :channel-id \"channel-name\"}")
(println "")
(println "Press Ctrl+C to stop")

;; Keep server running
@(promise)
