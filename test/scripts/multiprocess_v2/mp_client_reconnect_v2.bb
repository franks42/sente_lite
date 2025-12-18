#!/usr/bin/env bb
;;
;; Multi-Process Test Client (v2 Reconnection Test using client_bb.clj)
;;
;; Usage: bb mp_client_reconnect_v2.bb <test-id> <client-id> <channel-id> <port> <initial-msg-count> <post-reconnect-msg-count>
;;
;; Connects to fixed port, sends initial messages, detects disconnection,
;; auto-reconnects, sends post-reconnect messages, validates behavior.
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[sente-lite.client-bb :as client]
         '[mp-utils :as mp])

(defn parse-args [args]
  (when (< (count args) 6)
    (println "Usage: bb mp_client_reconnect_v2.bb <test-id> <client-id> <channel-id> <port> <initial-msg-count> <post-reconnect-msg-count>")
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

    (println "[client" client-id "] Starting v2 reconnection client")
    (println "[client" client-id "] Port:" port "Channel:" channel-id)

    ;; Track test state
    (def initial-messages-sent (atom 0))
    (def post-reconnect-messages-sent (atom 0))
    (def messages-received (atom []))
    (def connection-count (atom 0))
    (def disconnection-detected (atom false))
    (def reconnection-detected (atom false))
    (def subscribe-confirmed (atom false))

    ;; Create client using client_bb.clj with auto-reconnect enabled
    (def client-handle
      (client/make-client!
        {:url (str "ws://localhost:" port "/")
         :auto-reconnect? true
         :reconnect-delay 1000
         :max-reconnect-delay 5000
         :on-open (fn [uid]
                    (swap! connection-count inc)
                    (println "[client" client-id "] Connected (count:" @connection-count ") uid:" uid))
         :on-reconnect (fn []
                         (reset! reconnection-detected true)
                         (swap! connection-count inc)
                         (println "[client" client-id "] Reconnected (count:" @connection-count ")"))
         :on-message (fn [event-id data]
                       (swap! messages-received conj {:event-id event-id :data data})
                       (when (= event-id :sente-lite/subscribed)
                         (reset! subscribe-confirmed true)))
         :on-close (fn [code reason]
                     (when (> @connection-count 0)
                       (reset! disconnection-detected true)
                       (println "[client" client-id "] Disconnected (detected:" @disconnection-detected ")")))}))

    ;; Wait for initial connection
    (Thread/sleep 1000)

    ;; Verify connected
    (when (not= :connected (client/get-status client-handle))
      (println "[client" client-id "] ERROR: Failed to connect")
      (mp/write-result! test-id process-id {:status :failed :error "initial-connection-failed"})
      (mp/signal-ready! test-id process-id)
      (System/exit 1))

    ;; Subscribe to channel
    (println "[client" client-id "] Subscribing to" channel-id)
    (client/subscribe! client-handle channel-id)
    (Thread/sleep 500)

    ;; Send initial messages
    (println "[client" client-id "] Sending" initial-message-count "initial messages")
    (dotimes [i initial-message-count]
      (when (client/send! client-handle [:test/initial-message {:client-id client-id
                                                                 :sequence i
                                                                 :channel channel-id}])
        (swap! initial-messages-sent inc))
      (Thread/sleep 100))

    (println "[client" client-id "] Initial messages sent:" @initial-messages-sent)
    (println "[client" client-id "] Waiting for server restart...")

    ;; Wait for test-complete signal (poll loop)
    ;; Also check for reconnection during the wait
    (loop [attempts 0]
      (if (>= attempts 60)  ; 60 seconds max
        (println "[client" client-id "] WARNING: test-complete signal timeout")
        (let [signal-found (try
                             (mp/wait-for-ready test-id "test-complete" 1000)
                             true
                             (catch Exception e false))]
          (if signal-found
            (println "[client" client-id "] Test-complete signal received")
            (do
              (when @reconnection-detected
                (println "[client" client-id "] Reconnection detected during wait"))
              (recur (inc attempts)))))))

    ;; Wait a bit more for any in-flight reconnection
    (when (and @disconnection-detected (not @reconnection-detected))
      (println "[client" client-id "] Waiting for reconnection to complete...")
      (Thread/sleep 3000))

    ;; If we reconnected, send post-reconnect messages
    (when @reconnection-detected
      (println "[client" client-id "] Sending" post-reconnect-message-count "post-reconnect messages")
      
      ;; Re-subscribe after reconnection
      (client/subscribe! client-handle channel-id)
      (Thread/sleep 500)
      
      (dotimes [i post-reconnect-message-count]
        (when (client/send! client-handle [:test/post-reconnect {:client-id client-id
                                                                   :sequence i
                                                                   :channel channel-id}])
          (swap! post-reconnect-messages-sent inc))
        (Thread/sleep 100)))

    ;; Wait for final responses
    (Thread/sleep 1000)

    ;; Get final stats
    (def stats (client/get-stats client-handle))
    (println "[client" client-id "] Stats:" stats)

    ;; Write result
    (def result {:status (if (and @disconnection-detected @reconnection-detected) :passed :failed)
                 :client-id client-id
                 :channel-id channel-id
                 :uid (client/get-uid client-handle)
                 :initial-messages-sent @initial-messages-sent
                 :post-reconnect-messages-sent @post-reconnect-messages-sent
                 :messages-received (count @messages-received)
                 :connection-count @connection-count
                 :detected-disconnection @disconnection-detected
                 :detected-reconnection @reconnection-detected
                 :stats stats})

    (mp/write-result! test-id process-id result)
    (mp/signal-ready! test-id process-id)

    ;; Close
    (client/set-reconnect! client-handle false)
    (client/close! client-handle)
    (println "[client" client-id "] Done")
    (System/exit 0)))

(apply -main *command-line-args*)
