#!/usr/bin/env bb
;; COMPLETE pub/sub test: subscribe, publish, receive broadcast

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn])

(println "Starting COMPLETE BB-to-BB pub/sub test...")
(println "Two clients will subscribe, one will publish, both should receive")

;; Client state
(def client-a-subscriptions (atom #{}))
(def client-a-messages (atom []))
(def client-a-ready (promise))

(def client-b-subscriptions (atom #{}))
(def client-b-messages (atom []))
(def client-b-ready (promise))

(defn make-handler [name subscriptions-atom messages-atom ready-promise]
  (fn handle-message [parsed]
    (let [msg-type (:type parsed)]
      (case msg-type
        :ping
        nil  ; ignore pings

        :welcome
        (println (str "  " name " - Welcome: " (:conn-id parsed)))

        :subscription-result
        (do
          (println (str "  " name " - Subscribed to: " (:channel-id parsed)))
          (when (:success parsed)
            (swap! subscriptions-atom conj (:channel-id parsed))
            (deliver ready-promise true)))

        :publish-result
        (println (str "  " name " - Publish result: delivered to " (:delivered-to parsed)))

        :channel-message
        (do
          (swap! messages-atom conj parsed)
          (println (str "  " name " - 📨 RECEIVED BROADCAST!"))
          (println (str "       Channel: " (:channel-id parsed)))
          (println (str "       Data: " (pr-str (:data parsed)))))

        :pong-ack
        nil  ; ignore

        ;; default
        (println (str "  " name " - Other: " msg-type " " (pr-str parsed)))))))

;; Create client A
(def client-a
  (ws/websocket
   {:uri "ws://localhost:1345/"
    :on-message (fn [ws msg _last?]
                  (try
                    (let [parsed (edn/read-string (str msg))]
                      ((make-handler "Client-A" client-a-subscriptions
                                     client-a-messages client-a-ready) parsed))
                    (catch Exception e
                      (println "  Client-A Error:" (ex-message e)))))
    :on-open (fn [ws] (println "✓ Client-A connected"))
    :on-close (fn [ws status reason] (println "✗ Client-A disconnected"))
    :on-error (fn [ws err] (println "⚠ Client-A error:" err))}))

;; Create client B
(def client-b
  (ws/websocket
   {:uri "ws://localhost:1345/"
    :on-message (fn [ws msg _last?]
                  (try
                    (let [parsed (edn/read-string (str msg))]
                      ((make-handler "Client-B" client-b-subscriptions
                                     client-b-messages client-b-ready) parsed))
                    (catch Exception e
                      (println "  Client-B Error:" (ex-message e)))))
    :on-open (fn [ws] (println "✓ Client-B connected"))
    :on-close (fn [ws status reason] (println "✗ Client-B disconnected"))
    :on-error (fn [ws err] (println "⚠ Client-B error:" err))}))

(Thread/sleep 1000)

;; Both subscribe
(println "\n=== Step 1: Both clients subscribe to 'test-channel' ===")
(ws/send! client-a (pr-str {:type :subscribe :channel-id "test-channel"}))
(ws/send! client-b (pr-str {:type :subscribe :channel-id "test-channel"}))

(deref client-a-ready 5000 :timeout)
(deref client-b-ready 5000 :timeout)

(if (and (= @client-a-ready true) (= @client-b-ready true))
  (do
    (println "\n✅ Both clients subscribed")
    (println "   Client-A subscriptions:" @client-a-subscriptions)
    (println "   Client-B subscriptions:" @client-b-subscriptions)

    ;; Client A publishes
    (println "\n=== Step 2: Client-A publishes message ===")
    (ws/send! client-a (pr-str {:type :publish
                                :channel-id "test-channel"
                                :data {:user "Alice" :msg "Hello from Client-A!" :timestamp (System/currentTimeMillis)}
                                :exclude-sender? false}))

    (Thread/sleep 2000)

    ;; Check results
    (println "\n=== RESULTS ===")
    (println "Client-A received:" (count @client-a-messages) "messages")
    (println "Client-B received:" (count @client-b-messages) "messages")

    (when (seq @client-a-messages)
      (println "\nClient-A messages:")
      (doseq [msg @client-a-messages]
        (println "  -" (pr-str msg))))

    (when (seq @client-b-messages)
      (println "\nClient-B messages:")
      (doseq [msg @client-b-messages]
        (println "  -" (pr-str msg))))

    (let [success? (and (= 1 (count @client-a-messages))
                        (= 1 (count @client-b-messages)))]
      (if success?
        (println "\n✅ SUCCESS: Both clients received the broadcast!")
        (println "\n❌ FAILED: Expected both clients to receive 1 message each")))

    (ws/close! client-a)
    (ws/close! client-b))

  (do
    (println "\n❌ FAILED: Clients did not subscribe successfully")
    (ws/close! client-a)
    (ws/close! client-b)
    (System/exit 1)))
