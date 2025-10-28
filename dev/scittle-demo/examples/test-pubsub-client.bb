#!/usr/bin/env bb
;; Test client for pub/sub demo

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn])

(println "Connecting to pub/sub server on ws://localhost:1345...")

(def channel-messages (atom []))
(def subscriptions (atom #{}))
(def connection-established (promise))

(def client
  (ws/websocket
   {:uri "ws://localhost:1345/"
    :on-message (fn [ws msg _last?]
                  (try
                    (let [msg-str (str msg)
                          parsed (edn/read-string msg-str)
                          msg-type (:type parsed)
                          has-channel? (contains? parsed :channel-id)
                          has-broadcast-time? (contains? parsed :broadcast-time)]
                      (cond
                        ;; Channel messages have :broadcast-time (server adds this)
                        has-broadcast-time?
                        (do
                          (swap! channel-messages conj parsed)
                          (println "📨 Channel message:")
                          (println "   Channel:" (:channel-id parsed))
                          (println "   Data:" (dissoc parsed :channel-id :broadcast-time)))

                        (= msg-type :welcome)
                        (println "✓ Welcome:" (:conn-id parsed))

                        (= msg-type :subscription-result)
                        (do
                          (println "✓ Subscription result:")
                          (println "   Channel:" (:channel-id parsed))
                          (println "   Success:" (:success parsed))
                          (println "   Subscribers:" (:subscriber-count parsed))
                          (when (:success parsed)
                            (swap! subscriptions conj (:channel-id parsed))))

                        (= msg-type :unsubscription-result)
                        (do
                          (println "✓ Unsubscription result:")
                          (println "   Channel:" (:channel-id parsed))
                          (println "   Success:" (:success parsed))
                          (when (:success parsed)
                            (swap! subscriptions disj (:channel-id parsed))))

                        (= msg-type :publish-result)
                        (println "✓ Publish result: Delivered to" (:delivered-to parsed))

                        (= msg-type :ping)
                        (ws/send! ws (pr-str {:type "pong" :timestamp (System/currentTimeMillis)}))

                        :else
                        (println "📨 Other:" msg-type parsed)))
                    (catch Exception e
                      (println "⚠ Error:" (ex-message e)))))
    :on-open (fn [ws]
               (println "✓ Connected to pub/sub server")
               (deliver connection-established true))
    :on-close (fn [ws status-code reason]
                (println "✗ Disconnected:" status-code reason))
    :on-error (fn [ws error]
                (println "⚠ Error:" error))}))

(println "Waiting for connection...")
(deref connection-established 5000 :timeout)

(when-not (= @connection-established true)
  (println "❌ Connection timeout!")
  (System/exit 1))

(println "✓ Connection established")
(println)

;; Test 1: Subscribe to chat channel
(println "=== TEST 1: Subscribe to 'chat' channel ===")
(ws/send! client (pr-str {:type "subscribe" :channel-id "chat"}))
(Thread/sleep 500)

;; Test 2: Publish message to chat
(println)
(println "=== TEST 2: Publish message to 'chat' ===")
(ws/send! client (pr-str {:type "publish"
                          :channel-id "chat"
                          :data {:user "Alice" :message "Hello everyone!"}
                          :exclude-sender? false}))
(Thread/sleep 500)

;; Test 3: Subscribe to notifications
(println)
(println "=== TEST 3: Subscribe to 'notifications' channel ===")
(ws/send! client (pr-str {:type "subscribe" :channel-id "notifications"}))
(Thread/sleep 500)

;; Test 4: Publish to notifications
(println)
(println "=== TEST 4: Publish to 'notifications' ===")
(ws/send! client (pr-str {:type "publish"
                          :channel-id "notifications"
                          :data {:type "alert" :text "New update available"}
                          :exclude-sender? false}))
(Thread/sleep 500)

;; Test 5: Unsubscribe from chat
(println)
(println "=== TEST 5: Unsubscribe from 'chat' ===")
(ws/send! client (pr-str {:type "unsubscribe" :channel-id "chat"}))
(Thread/sleep 500)

;; Test 6: Publish to chat again (should not receive)
(println)
(println "=== TEST 6: Publish to 'chat' after unsubscribe (should not receive) ===")
(ws/send! client (pr-str {:type "publish"
                          :channel-id "chat"
                          :data {:user "Bob" :message "Anyone here?"}
                          :exclude-sender? false}))
(Thread/sleep 500)

;; Final stats
(println)
(println "=== FINAL STATS ===")
(println "Active subscriptions:" @subscriptions)
(println "Channel messages received:" (count @channel-messages))
(println)
(println "Message details:")
(doseq [[i msg] (map-indexed vector @channel-messages)]
  (println (str "  " (inc i) ". Channel:" (:channel-id msg) " Data:" (:data msg))))

(ws/close! client)
(println)
(println "✓ Test complete")
