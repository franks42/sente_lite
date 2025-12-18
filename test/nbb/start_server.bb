#!/usr/bin/env bb
;; Start sente-lite server for nbb testing

(require '[babashka.classpath :as cp])
(cp/add-classpath "../../src")

(require '[sente-lite.server :as server])

(println "Starting sente-lite server on port 9090...")
(server/start-server! {:port 9090 :wire-format :edn :heartbeat {:enabled false}})
(println "Server running. Press Ctrl+C to stop.")

;; Keep alive
@(promise)
