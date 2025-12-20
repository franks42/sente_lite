(ns atom-sync.subscriber
  "Receive atom updates over sente-lite channel.

   Subscribes to a sente-lite pub/sub channel and updates a local atom
   when changes are received from a publisher.

   Usage:
     (require '[atom-sync.subscriber :as sub])

     (def app-state (atom {}))

     ;; Start receiving updates
     (def handler-id
       (sub/start! client-id app-state
         {:atom-id :app-state}))

     ;; Atom is automatically updated
     @app-state  ; => current synced value

     ;; Stop receiving
     (sub/stop! client-id handler-id)"
  #?(:bb (:require [sente-lite.client-bb :as client])))

;; Default channel for atom sync (must match publisher)
(def default-channel "atom-sync")

;; Event ID for channel messages
(def channel-msg-event :sente-lite/channel-msg)

(defn start!
  "Start receiving atom updates from sente-lite channel.

   Args:
     client-id - sente-lite client-id
     the-atom  - Local atom to update
     opts      - {:atom-id :my-atom           ; Required: atom to sync
                  :channel \"atom-sync\"       ; Optional: channel name
                  :on-update (fn [old new] ...)} ; Optional: callback

   Returns handler-id for later removal with stop!

   Example:
     (def handler-id
       (sub/start! client-id my-atom
         {:atom-id :app-state
          :on-update (fn [old new]
                       (println \"Synced:\" new))}))"
  [client-id the-atom opts]
  #?(:bb
     (let [atom-id (get opts :atom-id)
           channel (get opts :channel default-channel)
           on-update (get opts :on-update)

           ;; Handler that processes channel messages
           handler-fn (fn [msg]
                        (let [data (get msg :data)
                              channel-id (get data :channel-id)]
                          ;; Only process messages from our channel
                          (when (= channel-id channel)
                            (let [sync-msg (get data :data)
                                  msg-atom-id (get sync-msg :atom-id)]
                              ;; Only process messages for our atom
                              (when (= msg-atom-id atom-id)
                                (let [new-value (get sync-msg :value)
                                      old-value @the-atom]
                                  ;; Update atom
                                  (reset! the-atom new-value)
                                  ;; Call optional callback
                                  (when on-update
                                    (on-update old-value new-value))))))))]

       ;; Subscribe to the channel
       (client/subscribe! client-id channel)

       ;; Register handler for channel messages
       (client/on! client-id
                   {:event-id channel-msg-event
                    :callback handler-fn}))
     :cljs
     nil)) ;; Phase 3: Add ClojureScript support

(defn stop!
  "Stop receiving atom updates.

   Args:
     client-id  - sente-lite client-id
     handler-id - ID returned by start!

   Example:
     (sub/stop! client-id handler-id)"
  [client-id handler-id]
  #?(:bb
     (do
       ;; Remove handler
       (client/off! client-id handler-id))
     :cljs
     nil)) ;; Phase 3: Add ClojureScript support

(defn request-current!
  "Request current atom value from publisher.

   Sends a request on a dedicated request channel. Publisher should
   respond with publish-current!.

   Note: This is a convenience for initial sync. Not implemented in Phase 1."
  [_client-id _opts]
  ;; Phase 2: Implement request/response for initial sync
  nil)
