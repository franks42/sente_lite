(ns sente-lite.channels
  "Channel system for pub/sub messaging and RPC patterns"
  (:require [sente-lite.logging :as log])
  (:import [java.lang System]))

;; Channel state management
(defonce ^:private channels (atom {}))           ; channel-id -> {:subscribers #{conn-id} :config {...}}
(defonce ^:private subscriptions (atom {}))      ; conn-id -> #{channel-ids}
(defonce ^:private rpc-requests (atom {}))       ; request-id -> {:conn-id ... :timeout ...}

;; Configuration
(def default-channel-config
  {:max-subscribers 1000
   :message-retention 0     ; 0 = no retention, N = keep last N messages
   :rpc-timeout-ms 30000
   :telemetry-enabled true})

;; Channel Management
(defn create-channel!
  "Create a new channel for pub/sub messaging"
  ([channel-id]
   (create-channel! channel-id {}))
  ([channel-id config]
   (let [merged-config (merge default-channel-config config)]
     (when-not (get @channels channel-id)
       (swap! channels assoc channel-id
              {:subscribers #{}
               :config merged-config
               :created-at (System/currentTimeMillis)
               :message-count 0
               :retained-messages []})

       (log/debug :sente-lite.channels/created {:channel-id channel-id
                                                :config merged-config})
       true))))

(defn delete-channel!
  "Delete a channel and unsubscribe all clients"
  [channel-id]
  (when-let [channel (get @channels channel-id)]
    (let [subscriber-count (count (:subscribers channel))]
      ;; Remove subscriptions from all connected clients
      (doseq [conn-id (:subscribers channel)]
        (swap! subscriptions update conn-id disj channel-id))

      ;; Remove the channel
      (swap! channels dissoc channel-id)

      (log/debug :sente-lite.channels/deleted {:channel-id channel-id
                                               :subscriber-count subscriber-count})
      true)))

(defn get-channel-info
  "Get information about a channel"
  [channel-id]
  (get @channels channel-id))

(defn list-channels
  "List all available channels"
  []
  (into {} (map (fn [[id channel]]
                  [id (select-keys channel [:config :created-at :message-count
                                            :subscriber-count])])
                @channels)))

;; Subscription Management
(defn subscribe!
  "Subscribe a connection to a channel"
  [conn-id channel-id]
  (log/trace :sente-lite.pubsub/sub-req {:conn-id conn-id :channel-id channel-id})

  (if-let [channel (get @channels channel-id)]
    (let [current-subs (count (:subscribers channel))
          max-subs (get-in channel [:config :max-subscribers])]

      (if (>= current-subs max-subs)
        (do
          (log/warn :sente-lite.pubsub/sub-rejected {:conn-id conn-id
                                                     :channel-id channel-id
                                                     :reason :max-subscribers-reached
                                                     :current current-subs
                                                     :max max-subs})
          {:success false :reason :max-subscribers-reached})

        (do
          ;; Add subscription
          (swap! channels update-in [channel-id :subscribers] conj conn-id)
          (swap! subscriptions update conn-id (fnil conj #{}) channel-id)

          (log/debug :sente-lite.pubsub/sub-added {:conn-id conn-id
                                                   :channel-id channel-id
                                                   :total-subscribers (inc current-subs)})

          ;; Send retained messages if any
          (let [retained (:retained-messages channel)]
            (when (seq retained)
              (log/info :sente-lite.pubsub/retained-sent {:conn-id conn-id
                                                          :channel-id channel-id
                                                          :message-count (count retained)})))

          {:success true
           :subscriber-count (inc current-subs)
           :retained-messages (:retained-messages channel)})))

    (do
      (log/warn :sente-lite.pubsub/sub-rejected {:conn-id conn-id
                                                 :channel-id channel-id
                                                 :reason :channel-not-found})
      {:success false :reason :channel-not-found})))

(defn unsubscribe!
  "Unsubscribe a connection from a channel"
  [conn-id channel-id]
  (when (get @channels channel-id)
    (swap! channels update-in [channel-id :subscribers] disj conn-id)
    (swap! subscriptions update conn-id disj channel-id)

    (log/debug :sente-lite.pubsub/sub-removed {:conn-id conn-id
                                               :channel-id channel-id
                                               :remaining-subscribers
                                               (count (get-in @channels [channel-id :subscribers]))})
    true))

(defn unsubscribe-all!
  "Unsubscribe a connection from all channels (typically on disconnect)"
  [conn-id]
  (when-let [channel-ids (get @subscriptions conn-id)]
    (let [unsubscribed-count (count channel-ids)]
      (doseq [channel-id channel-ids]
        (swap! channels update-in [channel-id :subscribers] disj conn-id))

      (swap! subscriptions dissoc conn-id)

      (log/debug :sente-lite.pubsub/all-subs-removed {:conn-id conn-id
                                                      :channel-count unsubscribed-count})
      unsubscribed-count)))

(defn get-subscriptions
  "Get all channel subscriptions for a connection"
  [conn-id]
  (get @subscriptions conn-id #{}))

;; Message Publishing
(defn publish!
  "Publish a message to a channel"
  [channel-id message & {:keys [sender-conn-id exclude-sender?]
                         :or {exclude-sender? false}}]
  (if-let [channel (get @channels channel-id)]
    (let [subscribers (:subscribers channel)
          target-subscribers (if (and exclude-sender? sender-conn-id)
                               (disj subscribers sender-conn-id)
                               subscribers)
          message-with-meta (assoc message
                                   :channel-id channel-id
                                   :published-at (System/currentTimeMillis)
                                   :message-id (str (gensym "msg-")))]

      ;; Update channel statistics
      (swap! channels update-in [channel-id :message-count] inc)

      ;; Handle message retention
      (let [retention (get-in channel [:config :message-retention])]
        (when (pos? retention)
          (swap! channels update-in [channel-id :retained-messages]
                 (fn [msgs]
                   (let [new-msgs (conj (vec msgs) message-with-meta)]
                     (if (> (count new-msgs) retention)
                       (subvec new-msgs (- (count new-msgs) retention))
                       new-msgs))))))

      (log/trace :sente-lite.pubsub/msg-published {:channel-id channel-id
                                                   :message-id (:message-id message-with-meta)
                                                   :target-subscriber-count (count target-subscribers)
                                                   :total-subscriber-count (count subscribers)})

      {:success true
       :message-id (:message-id message-with-meta)
       :delivered-to (count target-subscribers)
       :subscribers target-subscribers})

    (do
      (log/error :sente-lite.pubsub/msg-publish-failed {:channel-id channel-id
                                                        :reason :channel-not-found})
      {:success false :reason :channel-not-found})))

;; RPC Patterns
(defn generate-request-id []
  (str "req-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defn send-rpc-request!
  "Send an RPC request and track it for response correlation"
  [conn-id target-channel-id request-data & {:keys [timeout-ms]
                                             :or {timeout-ms 30000}}]
  (let [request-id (generate-request-id)
        request-message {:type :rpc-request
                         :request-id request-id
                         :data request-data
                         :sender-conn-id conn-id
                         :created-at (System/currentTimeMillis)}]

    ;; Track the request for response correlation
    (swap! rpc-requests assoc request-id
           {:conn-id conn-id
            :target-channel-id target-channel-id
            :created-at (System/currentTimeMillis)
            :timeout-ms timeout-ms})

    ;; Publish the request to the target channel
    (let [result (publish! target-channel-id request-message
                           :sender-conn-id conn-id
                           :exclude-sender? true)]

      (log/trace :sente-lite.rpc/req-sent {:request-id request-id
                                           :conn-id conn-id
                                           :target-channel-id target-channel-id
                                           :timeout-ms timeout-ms
                                           :delivery-result result})

      {:request-id request-id
       :delivery result})))

(defn send-rpc-response!
  "Send an RPC response back to the original requester"
  [request-id response-data & {:keys [error?]
                               :or {error? false}}]
  (if-let [request-info (get @rpc-requests request-id)]
    (let [response-message {:type :rpc-response
                            :request-id request-id
                            :data response-data
                            :error? error?
                            :created-at (System/currentTimeMillis)}]

      ;; Remove the tracked request
      (swap! rpc-requests dissoc request-id)

      (log/trace :sente-lite.rpc/resp-sent {:request-id request-id
                                            :target-conn-id (:conn-id request-info)
                                            :error? error?})

      {:success true
       :target-conn-id (:conn-id request-info)
       :response response-message})

    (do
      (log/error :sente-lite.rpc/resp-failed {:request-id request-id
                                              :reason :request-not-found})
      {:success false :reason :request-not-found})))

(defn cleanup-expired-rpc-requests!
  "Clean up RPC requests that have exceeded their timeout"
  []
  (let [now (System/currentTimeMillis)
        expired-requests (filter (fn [[_request-id request-info]]
                                   (> (- now (:created-at request-info))
                                      (:timeout-ms request-info)))
                                 @rpc-requests)]

    (when (seq expired-requests)
      (doseq [[request-id request-info] expired-requests]
        (swap! rpc-requests dissoc request-id)
        (log/warn :sente-lite.rpc/req-expired {:request-id request-id
                                               :conn-id (:conn-id request-info)
                                               :age-ms (- now (:created-at request-info))}))

      (count expired-requests))

    (count expired-requests)))

;; Channel Statistics
(defn get-channel-stats
  "Get comprehensive statistics about all channels"
  []
  (let [channel-data @channels
        subscription-data @subscriptions
        rpc-data @rpc-requests]

    {:channels (into {} (map (fn [[id channel]]
                               [id {:subscriber-count (count (:subscribers channel))
                                    :message-count (:message-count channel)
                                    :created-at (:created-at channel)
                                    :retention-count (count (:retained-messages channel))}])
                             channel-data))
     :total-channels (count channel-data)
     :total-subscriptions (reduce + (map (comp count val) subscription-data))
     :active-connections (count subscription-data)
     :pending-rpc-requests (count rpc-data)
     :rpc-requests (into {} (map (fn [[id req]]
                                   [id (select-keys req [:conn-id :created-at :timeout-ms])])
                                 rpc-data))}))

(defn get-system-health
  "Get overall channel system health"
  []
  (let [stats (get-channel-stats)
        now (System/currentTimeMillis)
        old-rpc-requests (filter (fn [[_ req]]
                                   (> (- now (:created-at req)) 60000)) ; > 1 minute old
                                 @rpc-requests)]

    {:healthy? (< (count old-rpc-requests) 10) ; Healthy if < 10 old RPC requests
     :total-channels (:total-channels stats)
     :total-subscriptions (:total-subscriptions stats)
     :active-connections (:active-connections stats)
     :pending-rpc-requests (:pending-rpc-requests stats)
     :old-rpc-requests (count old-rpc-requests)
     :timestamp now}))