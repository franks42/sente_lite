#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[telemere-lite.core :as tel]
         '[mp-utils :as mp]
         '[ws-client-managed :as wsm]
         '[cheshire.core :as json])

;;
;; Multi-Process Test Client
;;
;; Usage: bb mp_client.bb <test-id> <client-id> <channel-id> <message-count>
;;
;; Discovers server port, connects, subscribes to channel, sends/receives
;; messages, validates behavior, writes result.
;;

(defn parse-args [args]
  (when (< (count args) 4)
    (println "Usage: bb mp_client.bb <test-id> <client-id> <channel-id> <message-count>")
    (System/exit 1))
  {:test-id (first args)
   :client-id (second args)
   :channel-id (nth args 2)
   :message-count (Integer/parseInt (nth args 3))})

(defn -main [& args]
  (let [{:keys [test-id client-id channel-id message-count]} (parse-args args)
        process-id (str "client-" client-id)]

    (tel/log! :info "=== Multi-Process Test Client ==="
              {:test-id test-id
               :client-id client-id
               :channel channel-id
               :message-count message-count})

    ;; Discover server port
    (tel/log! :info "Discovering server port")
    (def port (mp/read-port test-id 5000))
    (tel/log! :info "Server port discovered" {:port port})

    ;; Track test state
    (def messages-received (atom []))
    (def messages-sent (atom 0))
    (def connection-count (atom 0))
    (def failures (atom []))
    (def state-log (atom []))

    ;; Create managed client
    (def client
      (wsm/create-managed-client
        {:uri (str "ws://localhost:" port "/")
         :on-state-change (fn [old-state new-state]
                            (tel/log! :info "State changed"
                                      {:old old-state :new new-state})
                            (swap! state-log conj {:old old-state :new new-state}))
         :on-message (fn [msg]
                       (tel/log! :info "Message received" {:msg msg})
                       (swap! messages-received conj msg))
         :on-open (fn [ws]
                    (tel/log! :info "Connection opened")
                    (swap! connection-count inc))
         :heartbeat {:auto-pong true}
         :reconnect {:enabled true
                     :max-attempts 3
                     :initial-delay-ms 1000}}))

    ;; Connect
    (tel/log! :info "Connecting to server")
    ((:connect! client))
    (Thread/sleep 1000)

    ;; Verify connected
    (def state ((:get-state client)))
    (when (not= :open state)
      (swap! failures conj {:type :connection :expected :open :actual state})
      (tel/error! "Failed to connect" {:state state}))

    ;; Subscribe to channel
    (tel/log! :info "Subscribing to channel" {:channel channel-id})
    ((:subscribe! client) channel-id)
    (Thread/sleep 500)

    ;; Verify subscription
    (def subs ((:get-subscriptions client)))
    (when-not (contains? subs channel-id)
      (swap! failures conj {:type :subscription
                            :expected channel-id
                            :actual subs})
      (tel/error! "Failed to subscribe" {:subs subs}))

    ;; Send messages
    (tel/log! :info "Sending messages" {:count message-count})
    (dotimes [i message-count]
      (let [msg {:type :test-message
                 :client-id client-id
                 :sequence i
                 :channel channel-id}
            msg-json (json/generate-string msg)]
        (try
          ((:send! client) msg-json)
          (swap! messages-sent inc)
          (Thread/sleep 100)
          (catch Exception e
            (swap! failures conj {:type :send :error (str e)})
            (tel/error! "Failed to send" {:error e})))))

    ;; Wait for responses
    (tel/log! :info "Waiting for responses")
    (Thread/sleep 2000)

    ;; Signal client ready/done
    (mp/signal-ready! test-id process-id)
    (tel/log! :info "Client done signal sent")

    ;; Disconnect
    ((:disconnect! client))
    (Thread/sleep 500)

    ;; Write result
    (def result {:status (if (empty? @failures) :passed :failed)
                 :client-id client-id
                 :channel-id channel-id
                 :messages-sent @messages-sent
                 :messages-received (count @messages-received)
                 :expected-messages message-count
                 :connections @connection-count
                 :state-log @state-log
                 :failures (count @failures)
                 :failure-details @failures})

    (mp/write-result! test-id process-id result)
    (tel/log! :info "Client result written" {:result result})

    (System/exit (if (empty? @failures) 0 1))))

(apply -main *command-line-args*)
