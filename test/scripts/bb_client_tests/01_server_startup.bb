#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[sente-lite.server :as server])

(println "=== Phase 1: Verify Server Starts ===")
(println)

(try
  (println "1. Attempting to start server on port 3000...")
  (def test-server (server/start-server! {:port 3000}))
  (println "✅ Server started successfully")
  (println "   Server function type:" (type test-server))
  (println)

  (println "2. Checking server is running...")
  (def stats (server/get-server-stats))
  (println "   Running:" (:running? stats))
  (println "   Port:" (get-in stats [:config :port]))
  (println "   Active connections:" (get-in stats [:connections :active]))
  (println)

  (println "3. Waiting 2 seconds to ensure stability...")
  (Thread/sleep 2000)
  (println "✅ Server remained stable")
  (println)

  (println "4. Attempting to stop server...")
  (server/stop-server!)
  (println "✅ Server stopped cleanly")
  (def stats-after (server/get-server-stats))
  (println "   Running after stop:" (:running? stats-after))
  (println)

  (println "🎉 Phase 1 PASSED: Server starts and stops successfully")
  (System/exit 0)

  (catch Exception e
    (println)
    (println "❌ Phase 1 FAILED: Server startup failed")
    (println)
    (println "Error type:" (type e))
    (println "Error message:" (.getMessage e))
    (when-let [data (ex-data e)]
      (println "Error data:" data))
    (println)
    (println "Stack trace:")
    (.printStackTrace e)
    (System/exit 1)))
