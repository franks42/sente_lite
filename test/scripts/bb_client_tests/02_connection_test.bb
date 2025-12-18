#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[sente-lite.server :as server]
         '[ws-client :as ws]


(println "=== Phase 2: Connection Test ===")
(println)

;; Track connection events
(def events (atom []))

(defn log-event [event-type data]
  (swap! events conj {:type event-type
                      :data data
                      :timestamp (System/currentTimeMillis)})
  println {:event-type event-type :data data}))

(try
  ;; Step 1: Start server
  (println "[info] " "Starting server on port 3000")
  (def test-server (server/start-server! {:port 3000}))
  (Thread/sleep 500) ; Give server time to bind
  (def stats (server/get-server-stats))
  (println "[info] " "Server started" {:running (:running? stats)})

  ;; Step 2: Connect client
  (println "[info] " "Connecting WebSocket client")
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

  (println "[info] " "Client connected" {:ws (str ws-connection)})

  ;; Step 3: Verify server sees connection
  (Thread/sleep 500)
  (def stats-after-connect (server/get-server-stats))
  (def active-after-connect (get-in stats-after-connect [:connections :active]))
  (println "[info] " "Checking server connection state" {:active-connections active-after-connect})
  (if (pos? active-after-connect)
    (println "[info] " "Server registered the connection")
    (println "[warn] " "Server shows 0 connections"))

  ;; Step 4: Close connection
  (println "[info] " "Closing client connection")
  (ws/close! ws-connection)
  (Thread/sleep 500)
  (println "[info] " "Client closed")

  ;; Step 5: Verify server sees disconnection
  (def stats-after-close (server/get-server-stats))
  (def active-after-close (get-in stats-after-close [:connections :active]))
  (println "[info] " "Checking server after disconnect" {:active-connections active-after-close})
  (if (zero? active-after-close)
    (println "[info] " "Server registered the disconnection")
    (println "[warn] " "Server still shows active connections"))

  ;; Step 6: Stop server
  (println "[info] " "Stopping server")
  (server/stop-server!)
  (println "[info] " "Server stopped")

  ;; Summary
  (println "[info] " "Event summary" {:total-events (count @events)
                                   :events (mapv :type @events)})

  (if (and (some #(= :open (:type %)) @events)
           (pos? active-after-connect))
    (do
      (println "[info] " "Phase 2 PASSED: Client connects to server successfully")
      (System/exit 0))
    (do
      (println "ERROR:" "Phase 2 FAILED: Connection not established")
      (System/exit 1)))

  (catch Exception e
    (println "ERROR:" "Phase 2 FAILED: Exception occurred"
                {:error e
                 :error-type (type e)
                 :error-message (.getMessage e)
                 :error-data (ex-data e)})
    (.printStackTrace e)
    (System/exit 1)))
