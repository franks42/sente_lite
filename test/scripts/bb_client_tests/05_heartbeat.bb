#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[sente-lite.server :as server]
         '[ws-client :as ws]
         '[cheshire.core :as json])

(println "[info] " "=== Phase 6a: Server Heartbeat Test ===")

;;
;; Phase 6a: Server heartbeat detection and dead connection cleanup
;;
;; Tests:
;; 1. Start server with short heartbeat interval (2s) and timeout (5s)
;; 2. Connect responsive client (auto-responds to pings)
;; 3. Connect unresponsive client (ignores pings)
;; 4. Verify responsive client stays connected
;; 5. Verify unresponsive client gets closed after timeout
;;

;; Test state
(def pings-received (atom 0))
(def client1-closed (promise))
(def client2-closed (promise))

;; Start server with aggressive heartbeat settings for testing
(println "[info] " "Starting server with heartbeat: interval=2s, timeout=5s")
(def test-server
  (server/start-server!
   {:port 3000
    :host "localhost"
    :wire-format :json
    :telemetry {:enabled true
                :handler-id :heartbeat-test}
    :heartbeat {:enabled true
                :interval-ms 2000    ; Send ping every 2s
                :timeout-ms 5000}    ; Close if no pong for 5s
    :channels {:auto-create false}}))

(Thread/sleep 500) ; Let server initialize

;; Client 1: Responsive (auto-responds to pings)
(println "[info] " "Connecting responsive client1")
(def client1
  (ws/connect!
   {:uri "ws://localhost:3000/"
    :on-message (fn [ws data last]
                  (let [data-str (str data)
                        msg (json/parse-string data-str true)]
                    (println "[info] " "Client1 received message" {:type (:type msg)})
                    ;; Auto-respond to pings
                    (when (= "ping" (:type msg))
                      (swap! pings-received inc)
                      (println "[info] " "Client1 auto-responding to ping")
                      (ws/send! ws (json/generate-string
                                    {:type "pong"
                                     :timestamp (System/currentTimeMillis)
                                     :original-timestamp (:timestamp msg)})))))
    :on-close (fn [ws status reason]
                (println "[info] " "Client1 closed" {:status status :reason reason})
                (deliver client1-closed true))}))

;; Client 2: Unresponsive (ignores pings)
(println "[info] " "Connecting unresponsive client2")
(def client2
  (ws/connect!
   {:uri "ws://localhost:3000/"
    :on-message (fn [ws data last]
                  (let [data-str (str data)
                        msg (json/parse-string data-str true)]
                    (println "[info] " "Client2 received message but ignoring pings" {:type (:type msg)})
                    ;; Deliberately NOT responding to pings
                    ))
    :on-close (fn [ws status reason]
                (println "[info] " "Client2 closed by server" {:status status :reason reason})
                (deliver client2-closed true))}))

(println "[info] " "Both clients connected")
(Thread/sleep 1000) ; Wait for welcome messages

;; Wait 10 seconds to observe heartbeat behavior
;; Expected:
;; - t=0: clients connect
;; - t=2s: first ping sent
;; - t=4s: second ping sent
;; - t=6s: third ping sent (client2 exceeds 5s timeout)
;; - t=6s: client2 should be closed
;; - t=10s: test ends, client1 still connected

(println "[info] " "Waiting 10 seconds to observe heartbeat...")
(Thread/sleep 10000)

;; Check results
(def client1-still-connected (not (realized? client1-closed)))
(def client2-was-closed (realized? client2-closed))

(println "[info] " "Test results"
          {:pings-received @pings-received
           :client1-still-connected client1-still-connected
           :client2-was-closed client2-was-closed})

;; Cleanup
(println "[info] " "Cleaning up")
(when client1-still-connected
  (ws/close! client1))
(Thread/sleep 500)

(println "[info] " "Stopping server")
(server/stop-server!)

;; Validate results
(cond
  ;; Client1 should stay connected (responsive)
  ;; Client2 should be closed (unresponsive for >5s)
  (and client1-still-connected
       client2-was-closed
       (>= @pings-received 3))  ; Should have received at least 3 pings in 10s
  (println "[info] " "Phase 6a PASSED: Server heartbeat working correctly")

  :else
  (println "ERROR:" "Phase 6a FAILED: Heartbeat not working correctly"
              {:error "Expected responsive client to stay connected and unresponsive to be closed"
               :client1-still-connected client1-still-connected
               :client2-was-closed client2-was-closed
               :pings-received @pings-received}))

(System/exit 0)
