#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts")
(cp/add-classpath "test/scripts/bb_client_tests")
(cp/add-classpath "test/scripts/multiprocess")

(require '[cheshire.core :as json]
         '[ws-client-managed :as wsm]
         '[mp-utils :as mp])

;;
;; Multi-process test client with reconnection support and port-file fallback
;;

(defn parse-args [args]
  (when (< (count args) 6)
    (println "ERROR:" "Usage: mp_client_reconnect_ephemeral.bb <test-id> <client-id> <channel-id> <initial-port> <initial-message-count> <post-reconnect-message-count>")
    (System/exit 1))
  {:test-id (nth args 0)
   :client-id (nth args 1)
   :channel-id (nth args 2)
   :initial-port (Integer/parseInt (nth args 3))
   :initial-message-count (Integer/parseInt (nth args 4))
   :post-reconnect-message-count (Integer/parseInt (nth args 5))})

(defn -main [& args]
  (let [{:keys [test-id client-id channel-id initial-port
                initial-message-count post-reconnect-message-count]} (parse-args args)
        process-id (str "client-" client-id)]

    (println "[info] " "Client starting" {:process-id process-id
                                       :test-id test-id
                                       :channel-id channel-id
                                       :initial-port initial-port})

    ;; State tracking
    (def connection-count (atom 0))
    (def messages-received (atom []))
    (def initial-messages-sent (atom 0))
    (def post-reconnect-messages-sent (atom 0))
    (def disconnection-detected (atom false))
    (def reconnection-detected (atom false))
    (def port-changed (atom false))
    (def state-log (atom []))
    (def is-reconnecting (atom false))

    ;; Create managed client with port-file-fn
    (def client
      (wsm/create-managed-client
       {:uri (str "ws://localhost:" initial-port "/")
        :on-state-change (fn [old-state new-state]
                           (println "Event: " {:old old-state
                                                       :new new-state
                                                       :client-id client-id})
                           (swap! state-log conj {:old old-state :new new-state
                                                  :timestamp (System/currentTimeMillis)})

                           ;; Detect disconnection
                           (when (and (= :open old-state)
                                      (not= :open new-state))
                             (println "Event: " {:client-id client-id})
                             (reset! disconnection-detected true)
                             (reset! is-reconnecting true))

                           ;; Detect reconnection
                           (when (and @is-reconnecting
                                      (= :open new-state))
                             (println "Event: " {:client-id client-id})
                             (reset! reconnection-detected true)
                             (reset! is-reconnecting false)))
        :on-message (fn [msg]
                      (println "Event: " {:msg msg :client-id client-id})
                      (swap! messages-received conj msg))
        :on-open (fn [ws]
                   (println "Event: " {:client-id client-id})
                   (swap! connection-count inc))
        :heartbeat {:auto-pong true}
        :reconnect {:enabled true
                    :max-attempts 5
                    :initial-delay-ms 1000
                    :max-delay-ms 5000
                    :backoff-multiplier 2
                    ;; Port-file fallback function
                    :port-file-fn (fn []
                                    (println "Event: " {:test-id test-id
                                                 :client-id client-id})
                                    (let [new-port (mp/read-port test-id 5000)]
                                      (println "Event: " {:new-port new-port
                                                   :client-id client-id})
                                      (when (not= new-port initial-port)
                                        (println "Event: " {:old-port initial-port
                                                     :new-port new-port
                                                     :client-id client-id})
                                        (reset! port-changed true))
                                      new-port))}}))

    ;; Connect
    (println "[info] " "Connecting to server" {:client-id client-id :port initial-port})
    ((:connect! client))

    ;; Wait for connection
    (Thread/sleep 1000)

    ;; Subscribe to channel
    (println "[info] " "Subscribing to channel" {:client-id client-id :channel channel-id})
    ((:subscribe! client) channel-id)

    ;; Send initial messages
    (println "[info] " "Sending initial messages" {:client-id client-id :count initial-message-count})
    (dotimes [i initial-message-count]
      (let [msg {:type :test-message
                 :client-id client-id
                 :phase :initial
                 :sequence i
                 :timestamp (System/currentTimeMillis)}
            msg-json (json/generate-string msg)]
        (when ((:send! client) msg-json)
          (swap! initial-messages-sent inc)
          (println "Event: " {:client-id client-id :sequence i :phase :initial})))
      (Thread/sleep 500))

    ;; Wait for test-complete signal
    (println "[info] " "Waiting for test-complete signal" {:client-id client-id})
    (try
      (mp/wait-for-ready test-id "test-complete" 30000)
      (println "[info] " "Test-complete signal received" {:client-id client-id})
      (catch Exception e
        (println "ERROR:" "Timeout waiting for test-complete" {:error (str e) :client-id client-id})))

    ;; If reconnected, send post-reconnect messages
    (when @reconnection-detected
      (println "[info] " "Sending post-reconnect messages"
                {:client-id client-id :count post-reconnect-message-count})
      (dotimes [i post-reconnect-message-count]
        (let [msg {:type :test-message
                   :client-id client-id
                   :phase :post-reconnect
                   :sequence i
                   :timestamp (System/currentTimeMillis)}
              msg-json (json/generate-string msg)]
          (when ((:send! client) msg-json)
            (swap! post-reconnect-messages-sent inc)
            (println "Event: " {:client-id client-id :sequence i :phase :post-reconnect})))
        (Thread/sleep 500)))

    ;; Disconnect
    (println "[info] " "Disconnecting" {:client-id client-id})
    ((:disconnect! client))
    (Thread/sleep 1000)

    ;; Collect results
    (def result {:status "passed"
                 :client-id client-id
                 :connection-count @connection-count
                 :initial-messages-sent @initial-messages-sent
                 :post-reconnect-messages-sent @post-reconnect-messages-sent
                 :messages-received (count @messages-received)
                 :detected-disconnection @disconnection-detected
                 :detected-reconnection @reconnection-detected
                 :port-changed @port-changed
                 :state-transitions (count @state-log)})

    (println "[info] " "Client completed" {:process-id process-id :result result})

    ;; Write result and signal ready
    (mp/write-result! test-id process-id result)
    (mp/signal-ready! test-id process-id)

    (System/exit 0)))

(apply -main *command-line-args*)
