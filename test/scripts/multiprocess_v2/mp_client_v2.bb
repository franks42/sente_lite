#!/usr/bin/env bb
;;
;; Multi-Process Test Client (v2 using client_bb.clj)
;;
;; Usage: bb mp_client_v2.bb <test-id> <client-id> <channel-id> <message-count>
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[sente-lite.client-bb :as client]
         '[mp-utils :as mp])

(defn parse-args [args]
  (when (< (count args) 4)
    (println "Usage: bb mp_client_v2.bb <test-id> <client-id> <channel-id> <message-count>")
    (System/exit 1))
  {:test-id (first args)
   :client-id (second args)
   :channel-id (nth args 2)
   :message-count (Integer/parseInt (nth args 3))})

(defn -main [& args]
  (let [{:keys [test-id client-id channel-id message-count]} (parse-args args)
        process-id (str "client-" client-id)]

    (println "[client" client-id "] Starting v2 client")

    ;; State tracking
    (def handshake-received (promise))
    (def messages-received (atom []))
    (def subscribe-confirmed (promise))

    ;; Get server port
    (def port (mp/read-port test-id 5000))
    (println "[client" client-id "] Server port:" port)

    ;; Create client using client_bb.clj
    (def client-handle
      (client/make-client!
        {:url (str "ws://localhost:" port "/")
         :auto-reconnect? false
         :on-open (fn [uid]
                    (println "[client" client-id "] Connected, uid:" uid)
                    (deliver handshake-received uid))
         :on-message (fn [event-id data]
                       (swap! messages-received conj {:event-id event-id :data data})
                       (when (= event-id :sente-lite/subscribed)
                         (deliver subscribe-confirmed true)))
         :on-close (fn [code reason]
                     (println "[client" client-id "] Disconnected:" code))}))

    ;; Wait for handshake
    (def uid (deref handshake-received 5000 nil))
    (when-not uid
      (println "[client" client-id "] ERROR: No handshake received!")
      (mp/write-result! test-id process-id {:status :failed :error "no-handshake"})
      (mp/signal-ready! test-id process-id)
      (System/exit 1))

    ;; Subscribe to channel
    (println "[client" client-id "] Subscribing to" channel-id)
    (client/subscribe! client-handle channel-id)
    (deref subscribe-confirmed 2000 nil)

    ;; Send messages
    (println "[client" client-id "] Sending" message-count "messages")
    (def messages-sent (atom 0))
    (dotimes [i message-count]
      (when (client/send! client-handle [:test/message {:client-id client-id
                                                         :sequence i
                                                         :channel channel-id}])
        (swap! messages-sent inc))
      (Thread/sleep 100))

    ;; Wait for responses
    (Thread/sleep 1000)

    ;; Get stats
    (def stats (client/get-stats client-handle))
    (println "[client" client-id "] Stats:" stats)

    ;; Write result
    (def result {:status :passed
                 :client-id client-id
                 :uid uid
                 :channel-id channel-id
                 :messages-sent @messages-sent
                 :messages-received (count @messages-received)
                 :stats stats})

    (mp/write-result! test-id process-id result)
    (mp/signal-ready! test-id process-id)

    ;; Close
    (client/close! client-handle)
    (println "[client" client-id "] Done")
    (System/exit 0)))

(apply -main *command-line-args*)
