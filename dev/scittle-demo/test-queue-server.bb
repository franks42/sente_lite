#!/usr/bin/env bb
;;
;; Simple WebSocket test server for queue browser tests
;; Uses port 1346 to avoid conflicts with other test servers
;;

(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs])

(def project-root (-> *file* fs/parent fs/parent fs/parent str))
(cp/add-classpath (str project-root "/src"))

(require '[sente-lite.server :as server])

(def server-port 1346)

(println "Starting queue test server on port" server-port "...")

(server/start-server! {:port server-port
                        :host "localhost"})

(println "Server started on port" server-port)
(println "Press Ctrl+C to stop")

;; Keep server running
(while true
  (Thread/sleep 10000))
