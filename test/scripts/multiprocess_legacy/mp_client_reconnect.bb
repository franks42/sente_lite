#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[mp-utils :as mp]
         '[ws-client-managed :as wsm]
         '[cheshire.core :as json])

;;
;; Multi-Process Test Client (Reconnection Test)
;;
;; Usage: bb mp_client_reconnect.bb <test-id> <client-id> <channel-id> <port> <initial-msg-count> <post-reconnect-msg-count>
;;
;; Connects to fixed port, sends initial messages, detects disconnection,
;; auto-reconnects, sends post-reconnect messages, validates behavior.
;;

(defn parse-args [args]
  (when (< (count args) 6)
    (println "Usage: bb mp_client_reconnect.bb <test-id> <client-id> <channel-id> <port> <initial-msg-count> <post-reconnect-msg-count>")
    (System/exit 1))
  {:test-id (first args)
   :client-id (second args)
   :channel-id (nth args 2)
   :port (Integer/parseInt (nth args 3))
   :initial-message-count (Integer/parseInt (nth args 4))
   :post-reconnect-message-count (Integer/parseInt (nth args 5))})

(defn -main [& args]
  (let [{:keys [test-id client-id channel-id port
                initial-message-count post-reconnect-message-count]} (parse-args args)
        process-id (str "client-" client-id)]

    (println "[info] " "=== Multi-Process Test Client (Reconnection) ==="
              {:test-id test-id
               :client-id client-id
               :channel channel-id
               :port port
               :initial-messages initial-message-count
               :post-reconnect-messages post-reconnect-message-count})

    ;; Track test state
    (def messages-received (atom []))
    (def initial-messages-sent (atom 0))
    (def post-reconnect-messages-sent (atom 0))
    (def connection-count (atom 0))
    (def disconnection-detected (atom false))
    (def reconnection-detected (atom false))
    (def failures (atom []))
    (def state-log (atom []))

    ;; Create managed client
    (def client
      (wsm/create-managed-client
       {:uri (str "ws://localhost:" port "/")
        :on-state-change (fn [old-state new-state]
                           (println "[info] " "State changed"
                                     {:old old-state :new new-state})
                           (swap! state-log conj {:old old-state :new new-state})

                            ;; Detect disconnection
                           (when (and (= :open old-state)
                                      (not= :open new-state))
                             (println "[info] " "Disconnection detected")
                             (reset! disconnection-detected true))

                            ;; Detect reconnection
                           (when (and @disconnection-detected
                                      (= :open new-state))
                             (println "[info] " "Reconnection detected")
                             (reset! reconnection-detected true)))
        :on-message (fn [msg]
                      (println "[info] " "Message received" {:msg msg})
                      (swap! messages-received conj msg))
        :on-open (fn [ws]
                   (println "[info] " "Connection opened")
                   (swap! connection-count inc))
        :heartbeat {:auto-pong true}
        :reconnect {:enabled true
                    :max-attempts 5
                    :initial-delay-ms 1000
                    :max-delay-ms 5000}}))

    ;; Connect
    (println "[info] " "Connecting to server")
    ((:connect! client))
    (Thread/sleep 1000)

    ;; Verify connected
    (def state ((:get-state client)))
    (when (not= :open state)
      (swap! failures conj {:type :connection :expected :open :actual state})
      (println "ERROR:" "Failed to connect" {:state state}))

    ;; Subscribe to channel
    (println "[info] " "Subscribing to channel" {:channel channel-id})
    ((:subscribe! client) channel-id)
    (Thread/sleep 500)

    ;; Verify subscription
    (def subs ((:get-subscriptions client)))
    (when-not (contains? subs channel-id)
      (swap! failures conj {:type :subscription
                            :expected channel-id
                            :actual subs})
      (println "ERROR:" "Failed to subscribe" {:subs subs}))

    ;; Send initial messages
    (println "[info] " "Sending initial messages" {:count initial-message-count})
    (dotimes [i initial-message-count]
      (let [msg {:type :initial-message
                 :client-id client-id
                 :sequence i
                 :channel channel-id}
            msg-json (json/generate-string msg)]
        (try
          ((:send! client) msg-json)
          (swap! initial-messages-sent inc)
          (Thread/sleep 100)
          (catch Exception e
            (swap! failures conj {:type :send-initial :error (str e)})
            (println "ERROR:" "Failed to send initial" {:error e})))))

    (println "[info] " "Initial messages sent, waiting for server restart")

    ;; Wait for disconnection detection and reconnection
    ;; Poll for test-complete signal
    (loop [attempts 0]
      (if (>= attempts 60)  ; 60 seconds max
        (do
          (swap! failures conj {:type :timeout :reason "test-complete signal not received"})
          (println "ERROR:" "Timeout waiting for test-complete signal"))
        (let [signal-found (try
                             (mp/wait-for-ready test-id "test-complete" 1000)
                             true
                             (catch Exception e
                               false))]
          (if signal-found
            (do
              (println "[info] " "Test-complete signal received")
              ;; Continue to post-reconnect phase
              nil)
            (do
              (Thread/sleep 1000)
              (recur (inc attempts)))))))

    ;; After reconnection, send post-reconnect messages
    (when @reconnection-detected
      (println "[info] " "Sending post-reconnect messages" {:count post-reconnect-message-count})
      (dotimes [i post-reconnect-message-count]
        (let [msg {:type :post-reconnect-message
                   :client-id client-id
                   :sequence i
                   :channel channel-id}
              msg-json (json/generate-string msg)]
          (try
            ((:send! client) msg-json)
            (swap! post-reconnect-messages-sent inc)
            (Thread/sleep 100)
            (catch Exception e
              (swap! failures conj {:type :send-post-reconnect :error (str e)})
              (println "ERROR:" "Failed to send post-reconnect" {:error e}))))))

    ;; Wait for final responses
    (println "[info] " "Waiting for final responses")
    (Thread/sleep 2000)

    ;; Signal client ready/done
    (mp/signal-ready! test-id process-id)
    (println "[info] " "Client done signal sent")

    ;; Disconnect
    ((:disconnect! client))
    (Thread/sleep 500)

    ;; Write result
    (def result {:status (if (empty? @failures) :passed :failed)
                 :client-id client-id
                 :channel-id channel-id
                 :initial-messages-sent @initial-messages-sent
                 :post-reconnect-messages-sent @post-reconnect-messages-sent
                 :messages-received (count @messages-received)
                 :connection-count @connection-count
                 :detected-disconnection @disconnection-detected
                 :detected-reconnection @reconnection-detected
                 :expected-initial-messages initial-message-count
                 :expected-post-reconnect-messages post-reconnect-message-count
                 :state-log @state-log
                 :failures (count @failures)
                 :failure-details @failures})

    (mp/write-result! test-id process-id result)
    (println "[info] " "Client result written" {:result result})

    (System/exit (if (empty? @failures) 0 1))))

(apply -main *command-line-args*)
