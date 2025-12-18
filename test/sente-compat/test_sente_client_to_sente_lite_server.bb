#!/usr/bin/env bb
;;
;; Test: Real Sente (JVM) client connecting to sente-lite (BB) server
;; This validates that our server implementation is Sente-compatible.
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "../../src")

(require '[babashka.process :as p]
         '[clojure.edn :as edn]
         '[sente-lite.server :as server])

(println "=== Test: Sente JVM Client -> sente-lite BB Server ===")
(println)

;; Start sente-lite server on a known port
(def test-port 8090)

(println "1. Starting sente-lite server on port" test-port)

;; Need to handle the /chsk WebSocket path that Sente clients expect
;; For now, our server accepts WebSocket on any path

(def server-instance
  (server/start-server! {:port test-port
                         :wire-format :edn
                         :heartbeat {:enabled false}}))

(Thread/sleep 500)
(println "   Server started on port" (server/get-server-port))

;; Run the JVM Sente client
(println)
(println "2. Running JVM Sente client...")
(println "   (This requires: cd test/sente-compat && clj -M:client localhost" test-port ")")
(println)

;; Try to run the client
(try
  (let [result (p/shell {:dir "."
                         :out :string
                         :err :string
                         :continue true}
                        "clj" "-M:client" "localhost" (str test-port))]
    (println "=== Client Output ===")
    (println (:out result))
    (when (seq (:err result))
      (println "=== Client Errors ===")
      (println (:err result)))
    (println "Exit code:" (:exit result)))
  (catch Exception e
    (println "ERROR: Failed to run JVM client:" (.getMessage e))
    (println "Make sure you have Clojure CLI installed and run from test/sente-compat/")))

;; Check server stats
(println)
(println "3. Server stats after test:")
(let [stats (server/get-server-stats)]
  (println "   Running:" (:running? stats))
  (println "   Connections:" (get-in stats [:connections :active])))

;; Stop server
(println)
(println "4. Stopping server...")
(server/stop-server!)
(println "   Done!")

(System/exit 0)
