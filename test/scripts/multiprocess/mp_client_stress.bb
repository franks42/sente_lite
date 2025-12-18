#!/usr/bin/env bb
;;
;; Multi-Process Stress Test Client (using client_bb.clj)
;;
;; Usage: bb mp_client_stress.bb <test-id> <client-id> <channel-id> <message-count> <interval-ms>
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/multiprocess")

(require '[sente-lite.client-bb :as client]
         '[mp-utils :as mp])

(defn parse-args [args]
  (when (< (count args) 5)
    (println "Usage: bb mp_client_stress.bb <test-id> <client-id> <channel-id> <message-count> <interval-ms>")
    (System/exit 1))
  {:test-id (first args)
   :client-id (second args)
   :channel-id (nth args 2)
   :message-count (Integer/parseInt (nth args 3))
   :interval-ms (Integer/parseInt (nth args 4))})

(defn -main [& args]
  (let [{:keys [test-id client-id channel-id message-count interval-ms]} (parse-args args)
        process-id (str "client-" client-id)]

    ;; State tracking
    (def handshake-received (promise))
    (def messages-sent (atom 0))
    (def messages-received (atom 0))
    (def failures (atom 0))

    ;; Get server port
    (def port (mp/read-port test-id 5000))

    ;; Create client using client_bb.clj
    (def client-handle
      (client/make-client!
        {:url (str "ws://localhost:" port "/")
         :auto-reconnect? false
         :on-open (fn [uid]
                    (deliver handshake-received uid))
         :on-message (fn [event-id data]
                       (swap! messages-received inc))
         :on-close (fn [code reason] nil)}))

    ;; Wait for handshake
    (def uid (deref handshake-received 5000 nil))
    (when-not uid
      (mp/write-result! test-id process-id {:status :failed :error "no-handshake"})
      (mp/signal-ready! test-id process-id)
      (System/exit 1))

    ;; Subscribe to channel
    (client/subscribe! client-handle channel-id)
    (Thread/sleep 200)

    ;; Send messages with interval
    (dotimes [i message-count]
      (if (client/send! client-handle [:stress/message {:client-id client-id
                                                         :sequence i
                                                         :timestamp (System/currentTimeMillis)}])
        (swap! messages-sent inc)
        (swap! failures inc))
      (when (< i (dec message-count))
        (Thread/sleep interval-ms)))

    ;; Wait briefly for any responses
    (Thread/sleep 500)

    ;; Get stats
    (def stats (client/get-stats client-handle))

    ;; Write result
    (def result {:status (if (zero? @failures) :passed :failed)
                 :client-id client-id
                 :uid uid
                 :channel-id channel-id
                 :connections 1
                 :messages-sent @messages-sent
                 :messages-received @messages-received
                 :failures @failures
                 :stats stats})

    (mp/write-result! test-id process-id result)
    (mp/signal-ready! test-id process-id)

    ;; Close
    (client/close! client-handle)
    (System/exit 0)))

(apply -main *command-line-args*)
