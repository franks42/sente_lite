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
;; Multi-process stress test client
;;
;; Sends messages at a controlled rate to test server under load
;;

(defn parse-args [args]
  (when (< (count args) 5)
    (println "ERROR:" "Usage: mp_client_stress.bb <test-id> <client-id> <channel-id> <message-count> <interval-ms>")
    (System/exit 1))
  {:test-id (nth args 0)
   :client-id (nth args 1)
   :channel-id (nth args 2)
   :message-count (Integer/parseInt (nth args 3))
   :interval-ms (Integer/parseInt (nth args 4))})

(defn -main [& args]
  (let [{:keys [test-id client-id channel-id message-count interval-ms]} (parse-args args)
        process-id (str "client-" client-id)]

    (println "[info] " "Stress test client starting"
              {:process-id process-id
               :test-id test-id
               :channel-id channel-id
               :message-count message-count
               :interval-ms interval-ms})

    ;; State tracking
    (def connection-count (atom 0))
    (def messages-sent (atom 0))
    (def messages-received (atom []))
    (def failures (atom 0))
    (def send-times (atom []))
    (def receive-times (atom []))

    ;; Get server port
    (def port (mp/read-port test-id 5000))
    (println "[info] " "Discovered server port" {:client-id client-id :port port})

    ;; Create managed client
    (def client
      (wsm/create-managed-client
       {:uri (str "ws://localhost:" port "/")
        :on-state-change (fn [old-state new-state]
                           (println "Event: " {:old old-state
                                        :new new-state
                                        :client-id client-id}))
        :on-message (fn [msg]
                      (swap! receive-times conj (System/currentTimeMillis))
                      (swap! messages-received conj msg))
        :on-open (fn [ws]
                   (println "Event: " {:client-id client-id})
                   (swap! connection-count inc))
        :on-error (fn [ws error]
                    (println "ERROR:" "Client error"
                                {:client-id client-id :error (str error)})
                    (swap! failures inc))
        :heartbeat {:auto-pong true}
        :reconnect {:enabled false}}))

    ;; Connect
    (println "[info] " "Connecting to server" {:client-id client-id :port port})
    ((:connect! client))

    ;; Wait for connection
    (Thread/sleep 1000)

    ;; Subscribe to channel
    (println "[info] " "Subscribing to channel" {:client-id client-id :channel channel-id})
    ((:subscribe! client) channel-id)
    (Thread/sleep 500)

    ;; Send messages at controlled rate
    (println "[info] " "Sending messages"
              {:client-id client-id :count message-count :interval-ms interval-ms})

    (def start-time (System/currentTimeMillis))

    (dotimes [i message-count]
      (let [msg {:type :stress-test-message
                 :client-id client-id
                 :sequence i
                 :timestamp (System/currentTimeMillis)}
            msg-json (json/generate-string msg)
            send-time (System/currentTimeMillis)]
        (if ((:send! client) msg-json)
          (do
            (swap! messages-sent inc)
            (swap! send-times conj send-time))
          (do
            (println "ERROR:" "Failed to send message"
                        {:client-id client-id :sequence i})
            (swap! failures inc))))
      ;; Rate limiting
      (when (< i (dec message-count))
        (Thread/sleep interval-ms)))

    (def end-time (System/currentTimeMillis))
    (def duration-ms (- end-time start-time))

    ;; Wait a bit for final messages
    (Thread/sleep 1000)

    ;; Disconnect
    (println "[info] " "Disconnecting" {:client-id client-id})
    ((:disconnect! client))
    (Thread/sleep 500)

    ;; Calculate performance metrics
    (def actual-rate (if (> duration-ms 0)
                       (/ (* @messages-sent 1000.0) duration-ms)
                       0))

    ;; Collect results
    (def result {:status "passed"
                 :client-id client-id
                 :connections @connection-count
                 :messages-sent @messages-sent
                 :messages-received (count @messages-received)
                 :failures @failures
                 :duration-ms duration-ms
                 :actual-rate-msg-sec (format "%.2f" actual-rate)})

    (println "[info] " "Stress test client completed"
              {:process-id process-id :result result})

    ;; Write result and signal ready
    (mp/write-result! test-id process-id result)
    (mp/signal-ready! test-id process-id)

    (System/exit 0)))

(apply -main *command-line-args*)
