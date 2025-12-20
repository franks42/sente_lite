(ns log-routing.receiver
  "Receive remote logs via sente-lite channel.

   Subscribes to the log-routing channel and processes incoming log entries.

   Usage:
     (require '[log-routing.receiver :as receiver])

     ;; Start receiving with custom handler
     (def handler-id
       (receiver/start! client-id
         {:handler (fn [log-entry]
                     (println \"Remote:\" log-entry))}))

     ;; Or use default (prints to stdout)
     (def handler-id (receiver/start! client-id {}))

     ;; Stop receiving
     (receiver/stop! client-id handler-id)"
  #?(:bb (:require [sente-lite.client-bb :as client])))

;; Channel name for log routing (must match sender.cljc)
(def log-channel "log-routing")

;; Event ID for channel messages
(def channel-msg-event :sente-lite/channel-msg)

(defn- default-handler
  "Default log handler - prints to stdout"
  [log-entry]
  (let [level (get log-entry :level)
        ns-str (get log-entry :ns)
        source (get log-entry :source)
        data (get log-entry :data)
        id (get log-entry :id)]
    (println (str "[REMOTE " source "] "
                  level " " ns-str
                  (when id (str " " id))
                  ": " (pr-str data)))))

(defn start!
  "Start receiving logs on client-id.

   Subscribes to the log-routing channel and registers a handler.

   Args:
     client-id - sente-lite client-id
     opts      - {:handler (fn [log-entry] ...)}

   Returns handler-id for later removal with stop!

   Example:
     (def h (receiver/start! client-id
              {:handler (fn [entry]
                          (swap! log-store conj entry))}))
     ;; Later...
     (receiver/stop! client-id h)"
  [client-id opts]
  #?(:bb
     (let [custom-handler (get opts :handler)
           ;; Handler that processes channel messages
           handler-fn (fn [msg]
                        (let [data (get msg :data)
                              channel-id (get data :channel-id)]
                          ;; Only process messages from log-routing channel
                          (when (= channel-id log-channel)
                            (let [log-entry (get data :data)]
                              (if custom-handler
                                (custom-handler log-entry)
                                (default-handler log-entry))))))]

       ;; Subscribe to the log-routing channel
       (client/subscribe! client-id log-channel)

       ;; Register handler for channel messages
       (client/on! client-id
                   {:event-id channel-msg-event
                    :callback handler-fn}))
     :cljs
     nil)) ;; Phase 3: Add ClojureScript support

(defn stop!
  "Stop receiving logs.

   Args:
     client-id  - sente-lite client-id
     handler-id - ID returned by start!

   Example:
     (receiver/stop! client-id handler-id)"
  [client-id handler-id]
  #?(:bb
     (do
       ;; Unsubscribe from channel
       (client/unsubscribe! client-id log-channel)
       ;; Remove handler
       (client/off! client-id handler-id))
     :cljs
     nil)) ;; Phase 3: Add ClojureScript support
