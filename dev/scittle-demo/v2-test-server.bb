#!/usr/bin/env bb
;; Simple v2 WebSocket server for browser client testing
;; Starts on port 1345

(require '[babashka.classpath :as cp])
(cp/add-classpath "../../src")

(require '[sente-lite.server :as server]
         '[sente-lite.wire-format-v2 :as wf2])

(println "")
(println "========================================")
(println "  sente-lite v2 Test Server")
(println "========================================")
(println "")
(println "Starting server on port 1345...")
(println "")

(def srv (server/start-server! {:port 1345
                                 :host "0.0.0.0"
                                 :wire-format :edn}))

(println "Server started on port" (server/get-server-port))
(println "")
(println "WebSocket endpoint: ws://localhost:1345/")
(println "Health check: http://localhost:1345/health")
(println "")
(println "Press Ctrl+C to stop...")
(println "")

;; Keep alive
@(promise)
