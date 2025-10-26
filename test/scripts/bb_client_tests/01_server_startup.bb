#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[sente-lite.server :as server]
         '[telemere-lite.core :as tel])

(println "=== Phase 1: Verify Server Starts ===")
(println)

(try
  (tel/log! :info "Attempting to start server on port 3000")
  (def test-server (server/start-server! {:port 3000}))
  (tel/log! :info "Server started successfully" {:server-type (type test-server)})

  (tel/log! :info "Checking server is running")
  (def stats (server/get-server-stats))
  (tel/log! :info "Server status" {:running (:running? stats)
                                   :port (get-in stats [:config :port])
                                   :active-connections (get-in stats [:connections :active])})

  (tel/log! :info "Waiting 2 seconds to ensure stability")
  (Thread/sleep 2000)
  (tel/log! :info "Server remained stable")

  (tel/log! :info "Attempting to stop server")
  (server/stop-server!)
  (def stats-after (server/get-server-stats))
  (tel/log! :info "Server stopped cleanly" {:running-after-stop (:running? stats-after)})

  (tel/log! :info "Phase 1 PASSED: Server starts and stops successfully")
  (System/exit 0)

  (catch Exception e
    (tel/error! "Phase 1 FAILED: Server startup failed"
                {:error e
                 :error-type (type e)
                 :error-message (.getMessage e)
                 :error-data (ex-data e)})
    (.printStackTrace e)
    (System/exit 1)))
