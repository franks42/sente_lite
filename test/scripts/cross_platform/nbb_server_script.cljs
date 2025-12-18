(ns nbb-server-script
  "nbb server for cross-platform testing"
  (:require [sente-lite.server-nbb :as server]))

;; Read port from args or use default
(def port (if (seq *command-line-args*)
            (js/parseInt (first *command-line-args*) 10)
            9878))

(println "[nbb-server] Starting on port" port "...")
(server/start-server! {:port port :heartbeat {:enabled false}})
(println "[nbb-server] Server running on port" port)

;; Write port to file for BB client to discover
(def fs (js/require "fs"))
(.writeFileSync fs "/tmp/sente-lite-nbb-server-port.txt" (str port))
(println "[nbb-server] Port file written")

;; Keep server running for 30 seconds
(println "[nbb-server] Running for 30 seconds...")
(js/setTimeout
 (fn []
   (println "[nbb-server] Stopping...")
   (server/stop-server!)
   (println "[nbb-server] Stopped"))
 30000)
