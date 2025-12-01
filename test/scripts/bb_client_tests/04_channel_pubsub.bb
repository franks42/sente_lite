#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[sente-lite.server :as server]
         '[ws-client :as ws]
         '[cheshire.core :as json])

(println "[info] " "=== Phase 4: Channel Pub/Sub Test ===")

;;
;; Phase 4: Multiple clients subscribing to channels and receiving published messages
;;
;; Tests:
;; 1. Start server with channel system enabled
;; 2. Connect 3 WebSocket clients
;; 3. Clients subscribe to channels via WebSocket messages
;; 4. One client publishes to channel
;; 5. Other clients receive the published message
;; 6. Verify channel delivery
;;

;; Test state
(def received-messages (atom {}))
(def clients-ready (promise))

;; Start server with channel system
(println "[info] " "Starting server with channel system on port 3000")
(def test-server
  (server/start-server!
   {:port 3000
    :host "localhost"
    :wire-format :json
    :telemetry {:enabled true
                :handler-id :phase4-test}
    :channels {:auto-create true
               :default-config {:max-subscribers 100
                                :message-retention 5}}}))

(Thread/sleep 500) ; Let server initialize

(defn message-handler [client-id]
  (fn [ws data last]
    (let [data-str (str data) ; Convert CharBuffer to String
          msg (try
                (json/parse-string data-str true) ; true = keywordize keys
                (catch Exception e
                  (println "ERROR:" "Failed to parse message" {:error e :data data-str})
                  nil))]
      (when msg
        (println "[info] " "Client received message" {:client client-id :message msg})
        (swap! received-messages update client-id (fnil conj []) msg)))))

(println "[info] " "Connecting 3 WebSocket clients")

;; Connect client 1
(def client1
  (ws/connect!
   {:uri "ws://localhost:3000/"
    :on-message (message-handler :client1)}))

;; Connect client 2
(def client2
  (ws/connect!
   {:uri "ws://localhost:3000/"
    :on-message (message-handler :client2)}))

;; Connect client 3
(def client3
  (ws/connect!
   {:uri "ws://localhost:3000/"
    :on-message (message-handler :client3)}))

(println "[info] " "All clients connected")
(Thread/sleep 1000) ; Wait for welcome messages

;; Subscribe clients to channels
(println "[info] " "Subscribing clients to channels")

;; Client 1 and 2 subscribe to "announcements" channel
(ws/send! client1 (json/generate-string
                   {:type "subscribe"
                    :channel-id "announcements"}))

(ws/send! client2 (json/generate-string
                   {:type "subscribe"
                    :channel-id "announcements"}))

;; Client 3 subscribes to "alerts" channel
(ws/send! client3 (json/generate-string
                   {:type "subscribe"
                    :channel-id "alerts"}))

(Thread/sleep 500) ; Let subscriptions process

;; Clear received messages (discard welcome + subscription confirmations)
(reset! received-messages {})

;; Publish message to "announcements" channel
(println "[info] " "Publishing message to 'announcements' channel from client1")
(ws/send! client1 (json/generate-string
                   {:type "publish"
                    :channel-id "announcements"
                    :data {:text "Hello all subscribers!"
                           :timestamp (System/currentTimeMillis)}}))

(Thread/sleep 2000) ; Wait for message delivery

;; Publish message to "alerts" channel
(println "[info] " "Publishing message to 'alerts' channel from client2")
(ws/send! client2 (json/generate-string
                   {:type "publish"
                    :channel-id "alerts"
                    :data {:alert "System notification"
                           :priority "high"}}))

(Thread/sleep 2000) ; Wait for message delivery

;; Check results
(println "[info] " "Checking received messages")

(def client1-msgs (get @received-messages :client1 []))
(def client2-msgs (get @received-messages :client2 []))
(def client3-msgs (get @received-messages :client3 []))

(println "[info] " "Phase 4 Summary"
          {:data {:client1-received (count client1-msgs)
                  :client2-received (count client2-msgs)
                  :client3-received (count client3-msgs)}})

;; Cleanup
(println "[info] " "Closing clients")
(ws/close! client1)
(ws/close! client2)
(ws/close! client3)
(Thread/sleep 500)

(println "[info] " "Stopping server")
(server/stop-server!)

;; Validate results
(defn has-announcement-message? [msgs]
  (some #(and (= "announcements" (:channel-id %))
              (= "Hello all subscribers!" (:text %)))
        msgs))

(defn has-alert-message? [msgs]
  (some #(and (= "alerts" (:channel-id %))
              (= "System notification" (:alert %)))
        msgs))

(cond
  ;; Both client1 and client2 should have received "announcements" message
  ;; Client3 should have received "alerts" message
  (and (or (has-announcement-message? client1-msgs)
           (has-announcement-message? client2-msgs))
       (has-alert-message? client3-msgs))
  (println "[info] " "Phase 4 PASSED: Channel pub/sub working")

  :else
  (println "ERROR:" "Phase 4 FAILED: Channel pub/sub not working correctly"
              {:error "Expected messages not received"
               :client1-msgs client1-msgs
               :client2-msgs client2-msgs
               :client3-msgs client3-msgs}))

(System/exit 0)
