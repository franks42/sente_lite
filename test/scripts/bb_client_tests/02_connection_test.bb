#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[sente-lite.server :as server]
         '[ws-client :as ws])

(println "=== Phase 2: Connection Test ===")
(println)

;; Track connection events
(def events (atom []))

(defn log-event [event-type data]
  (swap! events conj {:type event-type
                      :data data
                      :timestamp (System/currentTimeMillis)})
  (println (format "[%s] %s" event-type (pr-str data))))

(try
  ;; Step 1: Start server
  (println "1. Starting server on port 3000...")
  (def test-server (server/start-server! {:port 3000}))
  (Thread/sleep 500) ; Give server time to bind
  (def stats (server/get-server-stats))
  (println "   ✅ Server started, running:" (:running? stats))
  (println)

  ;; Step 2: Connect client
  (println "2. Connecting WebSocket client...")
  (def ws-connection
    (ws/connect!
      {:uri "ws://localhost:3000/"
       :on-open (fn [ws]
                  (log-event :open {:ws (str ws)}))
       :on-message (fn [ws data last]
                     (log-event :message {:data data :last last}))
       :on-close (fn [ws status reason]
                   (log-event :close {:status status :reason reason}))
       :on-error (fn [ws error]
                   (log-event :error {:error (str error)}))}))

  (println "   ✅ Client connected:" (str ws-connection))
  (println)

  ;; Step 3: Verify server sees connection
  (Thread/sleep 500)
  (def stats-after-connect (server/get-server-stats))
  (println "3. Checking server connection state...")
  (println "   Active connections:" (get-in stats-after-connect [:connections :active]))
  (if (pos? (get-in stats-after-connect [:connections :active]))
    (println "   ✅ Server registered the connection")
    (println "   ⚠️  Server shows 0 connections"))
  (println)

  ;; Step 4: Close connection
  (println "4. Closing client connection...")
  (ws/close! ws-connection)
  (Thread/sleep 500)
  (println "   ✅ Client closed")
  (println)

  ;; Step 5: Verify server sees disconnection
  (def stats-after-close (server/get-server-stats))
  (println "5. Checking server after disconnect...")
  (println "   Active connections:" (get-in stats-after-close [:connections :active]))
  (if (zero? (get-in stats-after-close [:connections :active]))
    (println "   ✅ Server registered the disconnection")
    (println "   ⚠️  Server still shows active connections"))
  (println)

  ;; Step 6: Stop server
  (println "6. Stopping server...")
  (server/stop-server!)
  (println "   ✅ Server stopped")
  (println)

  ;; Summary
  (println "=== Event Summary ===")
  (doseq [event @events]
    (println (format "  [%s] at %d: %s"
                     (:type event)
                     (:timestamp event)
                     (pr-str (:data event)))))
  (println)

  (if (and (some #(= :open (:type %)) @events)
           (pos? (get-in stats-after-connect [:connections :active])))
    (do
      (println "🎉 Phase 2 PASSED: Client connects to server successfully")
      (System/exit 0))
    (do
      (println "❌ Phase 2 FAILED: Connection not established")
      (System/exit 1)))

  (catch Exception e
    (println)
    (println "❌ Phase 2 FAILED: Exception occurred")
    (println)
    (println "Error type:" (type e))
    (println "Error message:" (.getMessage e))
    (when-let [data (ex-data e)]
      (println "Error data:" data))
    (println)
    (println "Stack trace:")
    (.printStackTrace e)
    (System/exit 1)))
