#!/usr/bin/env bb
;;
;; Multi-Process Test Server (v2 wire format)
;;
;; Usage: bb mp_server_v2.bb <test-id> <duration-sec>
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[sente-lite.server :as server]
         '[mp-utils :as mp])

(defn parse-args [args]
  (when (< (count args) 2)
    (println "Usage: bb mp_server_v2.bb <test-id> <duration-sec>")
    (System/exit 1))
  {:test-id (first args)
   :duration-sec (Integer/parseInt (second args))})

(defn -main [& args]
  (let [{:keys [test-id duration-sec]} (parse-args args)
        process-id "server"]

    (println "[server] Starting v2 server for test:" test-id)

    ;; Start server with v2 (EDN) wire format
    (server/start-server!
      {:port 0
       :host "localhost"
       :wire-format :edn  ; v2 format!
       :heartbeat {:enabled true
                   :interval-ms 10000
                   :timeout-ms 30000}
       :channels {:auto-create true}})

    (def actual-port (server/get-server-port))
    (println "[server] Started on port" actual-port)
    
    ;; Publish port and signal ready
    (mp/write-port! test-id actual-port)
    (mp/signal-ready! test-id process-id)
    (println "[server] Ready signal sent")

    ;; Run for duration
    (println "[server] Running for" duration-sec "seconds...")
    (Thread/sleep (* duration-sec 1000))

    ;; Get stats and write result
    (def stats (server/get-server-stats))
    (def result {:status :passed
                 :port actual-port
                 :connections (get-in stats [:connections :active])
                 :stats stats})

    (mp/write-result! test-id process-id result)
    (println "[server] Stats:" (select-keys stats [:connections :channels]))

    ;; Stop
    (server/stop-server!)
    (println "[server] Stopped")
    (System/exit 0)))

(apply -main *command-line-args*)
