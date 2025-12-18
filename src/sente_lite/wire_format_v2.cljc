(ns sente-lite.wire-format-v2
  "Sente-compatible wire format for sente-lite v2.
   Implements the Sente event format: [event-id data]
   with callback support: [[event-id data] cb-uuid]
   
   Cross-platform: works on Babashka, JVM Clojure, and Scittle/ClojureScript.
   
   NOTE: SCI/Scittle requires macros to be referred directly, not namespace-qualified.
   NOTE: For Scittle, we use read-string directly (available in SCI) instead of cljs.reader."
  (:require [taoensso.trove :as trove :refer [log!]]
            [clojure.string :as str]
            #?(:clj [sente-lite.wire-format :as wire]))
  #?(:clj (:import [java.util UUID])))

;; ============================================================================
;; Constants
;; ============================================================================

(def ^:const version "2.0.0")

;; System event IDs (Sente-compatible)
(def ^:const event-handshake :chsk/handshake)
(def ^:const event-state :chsk/state)
(def ^:const event-recv :chsk/recv)
(def ^:const event-ws-ping :chsk/ws-ping)
(def ^:const event-ws-pong :chsk/ws-pong)
(def ^:const event-reply :chsk/reply)
(def ^:const event-uidport-open :chsk/uidport-open)
(def ^:const event-uidport-close :chsk/uidport-close)
(def ^:const event-bad-event :chsk/bad-event)

;; sente-lite extension events
(def ^:const event-subscribe :sente-lite/subscribe)
(def ^:const event-unsubscribe :sente-lite/unsubscribe)
(def ^:const event-subscribed :sente-lite/subscribed)
(def ^:const event-publish :sente-lite/publish)
(def ^:const event-channel-msg :sente-lite/channel-msg)
(def ^:const event-message :sente-lite/message)
(def ^:const event-rpc :sente-lite/rpc)

;; ============================================================================
;; UUID Generation
;; ============================================================================

(defn generate-cb-uuid
  "Generate a callback UUID"
  []
  #?(:clj (str (UUID/randomUUID))
     :cljs (str (random-uuid))))

;; ============================================================================
;; Event Encoding
;; ============================================================================

(defn encode-event
  "Encode an event to Sente wire format.
   Returns: [event-id data]"
  ([event-id]
   (encode-event event-id nil))
  ([event-id data]
   (log! {:level :trace :id :sente-lite.v2/encode-event
          :data {:event-id event-id :has-data (some? data)}})
   [event-id data]))

(defn encode-event-with-callback
  "Encode an event with callback UUID.
   Returns: [[event-id data] cb-uuid]"
  ([event-id data]
   (encode-event-with-callback event-id data (generate-cb-uuid)))
  ([event-id data cb-uuid]
   (log! {:level :debug :id :sente-lite.v2/encode-with-cb
          :data {:event-id event-id :cb-uuid cb-uuid}})
   [[event-id data] cb-uuid]))

;; ============================================================================
;; Event Decoding
;; ============================================================================

(defn valid-event-id?
  "Check if event-id is a valid namespaced keyword"
  [event-id]
  (and (keyword? event-id)
       (namespace event-id)))

(defn decode-event
  "Decode Sente wire format to event map.
   Input formats:
     [event-id]                    -> {:event-id X :data nil}
     [event-id data]               -> {:event-id X :data Y}
     [[event-id data] cb-uuid]     -> {:event-id X :data Y :cb-uuid Z}
   Returns map with :event-id, :data, and optionally :cb-uuid"
  [wire-data]
  (let [result
        (cond
          ;; Not a vector - invalid
          (not (vector? wire-data))
          {:error :not-vector :raw wire-data}

          ;; Empty vector - invalid
          (empty? wire-data)
          {:error :empty-vector}

          ;; Event with callback: [[event-id data] cb-uuid]
          ;; NOTE: Using first/second instead of destructuring for SCI/Scittle compatibility
          (and (= 2 (count wire-data))
               (vector? (first wire-data))
               (string? (second wire-data)))
          (let [inner-event (first wire-data)
                cb-uuid (second wire-data)
                event-id (first inner-event)
                data (second inner-event)]
            (if (valid-event-id? event-id)
              {:event-id event-id
               :data data
               :cb-uuid cb-uuid}
              {:error :invalid-event-id :raw wire-data}))

          ;; Simple event: [event-id] or [event-id data]
          (keyword? (first wire-data))
          (let [event-id (first wire-data)
                data (second wire-data)]
            (if (valid-event-id? event-id)
              {:event-id event-id
               :data data}
              {:error :invalid-event-id :raw wire-data}))

          :else
          {:error :invalid-format :raw wire-data})]
    (if (:error result)
      (log! {:level :warn :id :sente-lite.v2/decode-error
             :data {:error (:error result)}})
      (log! {:level :trace :id :sente-lite.v2/decode-event
             :data {:event-id (:event-id result)
                    :has-cb (some? (:cb-uuid result))}}))
    result))

;; ============================================================================
;; System Events
;; ============================================================================

(defn make-handshake
  "Create a handshake event.
   Returns: [:chsk/handshake [uid csrf-token handshake-data first?]]"
  [uid csrf-token handshake-data first?]
  (log! {:level :info :id :sente-lite.v2/handshake-created
         :data {:uid uid :first? first?}})
  [event-handshake [uid csrf-token handshake-data first?]])

(defn parse-handshake
  "Parse handshake data from event.
   Handles both Sente wire format (2 elements) and full format (4 elements).
   Wire format: [uid csrf-token]
   Full format: [uid csrf-token handshake-data first?]
   Returns: {:uid X :csrf-token Y :handshake-data Z :first? W}"
  [data]
  (when (and (vector? data) (>= (count data) 2))
    (let [cnt (count data)]
      (cond
        ;; Full format: [uid csrf-token handshake-data first?]
        ;; NOTE: Using nth instead of destructuring for SCI/Scittle compatibility
        (>= cnt 4)
        {:uid (nth data 0)
         :csrf-token (nth data 1)
         :handshake-data (nth data 2)
         :first? (nth data 3)}

        ;; Wire format: [uid csrf-token] - Sente sends this on wire
        (= cnt 2)
        {:uid (first data)
         :csrf-token (second data)
         :handshake-data nil
         :first? true}

        ;; 3 elements: [uid csrf-token handshake-data]
        (= cnt 3)
        {:uid (nth data 0)
         :csrf-token (nth data 1)
         :handshake-data (nth data 2)
         :first? true}

        :else nil))))

(defn make-reply
  "Create a reply event.
   For sente-lite internal use: [:chsk/reply {:cb-uuid X :data Y}]
   Note: Sente wire format is [data cb-uuid] without :chsk/reply wrapper"
  [cb-uuid data]
  [event-reply {:cb-uuid cb-uuid :data data}])

(defn make-wire-reply
  "Create a reply in Sente wire format.
   Returns: [data cb-uuid]
   This is what Sente actually sends on the wire."
  [cb-uuid data]
  [data cb-uuid])

(defn parse-wire-reply
  "Parse a Sente wire reply [data cb-uuid].
   Returns: {:cb-uuid X :data Y}
   NOTE: Using first/second instead of destructuring for SCI/Scittle compatibility"
  [wire-data]
  (when (and (vector? wire-data)
             (= 2 (count wire-data))
             (string? (second wire-data)))
    {:cb-uuid (second wire-data)
     :data (first wire-data)}))

(defn parse-reply
  "Parse reply data from event.
   Returns: {:cb-uuid X :data Y}"
  [data]
  (when (map? data)
    {:cb-uuid (:cb-uuid data)
     :data (:data data)}))

(defn make-ws-ping
  "Create a WebSocket ping event"
  []
  [event-ws-ping])

(defn make-ws-pong
  "Create a WebSocket pong event"
  []
  [event-ws-pong])

(defn make-state-change
  "Create a state change event.
   Returns: [:chsk/state [old-state new-state]]"
  [old-state new-state]
  [event-state [old-state new-state]])

(defn make-recv
  "Create a recv event (server push wrapper).
   Returns: [:chsk/recv [event-id data]]"
  [event-id data]
  [event-recv [event-id data]])

;; ============================================================================
;; sente-lite Extension Events
;; ============================================================================

(defn make-subscribe
  "Create a subscribe event.
   Returns: [:sente-lite/subscribe {:channel-id X}]"
  [channel-id & {:keys [data]}]
  [event-subscribe (cond-> {:channel-id channel-id}
                     data (assoc :data data))])

(defn make-unsubscribe
  "Create an unsubscribe event.
   Returns: [:sente-lite/unsubscribe {:channel-id X}]"
  [channel-id]
  [event-unsubscribe {:channel-id channel-id}])

(defn make-subscribed
  "Create a subscribed confirmation event.
   Returns: [:sente-lite/subscribed {:channel-id X :success Y}]"
  [channel-id success & {:keys [error]}]
  [event-subscribed (cond-> {:channel-id channel-id :success success}
                      error (assoc :error error))])

(defn make-publish
  "Create a publish event.
   Returns: [:sente-lite/publish {:channel-id X :data Y}]"
  [channel-id data]
  [event-publish {:channel-id channel-id :data data}])

(defn make-channel-msg
  "Create a channel message event.
   Returns: [:sente-lite/channel-msg {:channel-id X :data Y :from Z}]"
  [channel-id data from]
  [event-channel-msg {:channel-id channel-id :data data :from from}])

;; ============================================================================
;; Wire Format Detection
;; ============================================================================

(defn detect-wire-version
  "Detect if raw message is v1 (map) or v2 (vector)"
  [raw-message]
  (cond
    (nil? raw-message)
    :unknown

    (not (string? raw-message))
    :unknown

    (str/blank? raw-message)
    :unknown

    ;; v2: starts with [ (vector/event)
    (str/starts-with? raw-message "[")
    :v2

    ;; v1: starts with { (map)
    (str/starts-with? raw-message "{")
    :v1

    :else
    :unknown))

;; ============================================================================
;; Sente Buffered Events
;; ============================================================================

(defn buffered-events?
  "Check if wire data is Sente's buffered events format.
   Sente wraps events in outer vector: [[event1] [event2] ...]"
  [wire-data]
  (and (vector? wire-data)
       (seq wire-data)
       (every? vector? wire-data)
       ;; First element should be an event (vector starting with keyword)
       (let [first-elem (first wire-data)]
         (and (vector? first-elem)
              (keyword? (first first-elem))))))

(defn unwrap-buffered-events
  "Unwrap Sente's buffered events format.
   Input: [[event1] [event2] ...]
   Output: sequence of events"
  [wire-data]
  (if (buffered-events? wire-data)
    (do
      (log! {:level :trace :id :sente-lite.v2/unwrap-buffered
             :data {:count (count wire-data)}})
      wire-data)
    [wire-data]))

(defn wrap-buffered-events
  "Wrap events in Sente's buffered format.
   Input: [event] or [[event1] [event2]]
   Output: [[event1] [event2] ...]"
  [events]
  (if (and (vector? events)
           (seq events)
           (every? vector? events)
           (keyword? (ffirst events)))
    ;; Already buffered format
    events
    ;; Single event - wrap it
    [events]))

;; ============================================================================
;; Serialization (Cross-platform)
;; ============================================================================

(defn serialize
  "Serialize event to wire format string.
   On JVM/BB: supports :edn, :json, :transit via wire-format library.
   On ClojureScript: EDN only (uses pr-str)."
  [event format-spec]
  (let [result #?(:clj (let [wire-format (wire/get-format format-spec)]
                         (wire/serialize wire-format event))
                  :cljs (pr-str event))]
    (log! {:level :trace :id :sente-lite.v2/serialize
           :data {:format format-spec :size (count (str result))}})
    result))

(defn deserialize
  "Deserialize wire format string to event.
   On JVM/BB: supports :edn, :json, :transit via wire-format library.
   On ClojureScript/Scittle: EDN only (uses read-string directly)."
  [raw-message format-spec]
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (let [result #?(:clj (let [wire-format (wire/get-format format-spec)]
                         (wire/deserialize wire-format raw-message))
                  :cljs (read-string raw-message))]
    (log! {:level :trace :id :sente-lite.v2/deserialize
           :data {:format format-spec :input-size (count raw-message)}})
    result))

(defn parse-message
  "Parse message, auto-detecting version and format.
   Returns decoded event map."
  [raw-message format-spec]
  (let [version (detect-wire-version raw-message)]
    (log! {:level :trace :id :sente-lite.v2/parse
           :data {:version version :preview (subs raw-message 0 (min 50 (count raw-message)))}})
    (case version
      :v2
      (let [parsed (deserialize raw-message format-spec)]
        (if (vector? parsed)
          (decode-event parsed)
          {:error :deserialize-failed :raw raw-message}))

      :v1
      (do
        (log! {:level :error :id :sente-lite.v2/v1-format-rejected
               :data {:message "v1 wire format is not supported - use v2 format"}})
        {:error :v1-format-not-supported
         :message "v1 wire format (map-based) is deprecated. Use v2 format (vector-based)."
         :raw raw-message})

      {:error :unknown-version :raw raw-message})))

;; ============================================================================
;; Event Predicates
;; ============================================================================

(defn system-event?
  "Check if event is a Sente system event (:chsk/* namespace)"
  [event-id]
  (and (keyword? event-id)
       (= "chsk" (namespace event-id))))

(defn sente-lite-event?
  "Check if event is a sente-lite extension event"
  [event-id]
  (and (keyword? event-id)
       (= "sente-lite" (namespace event-id))))

(defn ping-event?
  "Check if event is a ping"
  [event-id]
  (= event-id event-ws-ping))

(defn pong-event?
  "Check if event is a pong"
  [event-id]
  (= event-id event-ws-pong))

(defn reply-event?
  "Check if event is a reply"
  [event-id]
  (= event-id event-reply))

(defn handshake-event?
  "Check if event is a handshake"
  [event-id]
  (= event-id event-handshake))

;; ============================================================================
;; Callback Registry
;; ============================================================================

(defonce ^:private callback-registry (atom {}))

(defn register-callback!
  "Register a callback function for a cb-uuid.
   Returns the cb-uuid."
  [cb-uuid callback-fn timeout-ms]
  (swap! callback-registry assoc cb-uuid
         {:callback callback-fn
          :registered-at #?(:clj (System/currentTimeMillis)
                            :cljs (.now js/Date))
          :timeout-ms timeout-ms})
  (log! {:level :debug :id :sente-lite.v2/cb-registered
         :data {:cb-uuid cb-uuid :timeout-ms timeout-ms}})
  cb-uuid)

(defn invoke-callback!
  "Invoke and remove a registered callback.
   Returns true if callback was found and invoked."
  [cb-uuid data]
  (when-let [{:keys [callback]} (get @callback-registry cb-uuid)]
    (swap! callback-registry dissoc cb-uuid)
    (log! {:level :debug :id :sente-lite.v2/cb-invoked
           :data {:cb-uuid cb-uuid}})
    (try
      (callback data)
      true
      (catch #?(:clj Exception :cljs :default) e
        (log! {:level :error :id :sente-lite.v2/cb-error
               :data {:cb-uuid cb-uuid :error e}})
        false))))

(defn cleanup-expired-callbacks!
  "Remove callbacks that have exceeded their timeout
   NOTE: Using first/key instead of destructuring for SCI/Scittle compatibility"
  []
  (let [now #?(:clj (System/currentTimeMillis)
               :cljs (.now js/Date))
        expired (filter (fn [entry]
                          (let [cb-data (val entry)
                                registered-at (:registered-at cb-data)
                                timeout-ms (:timeout-ms cb-data)]
                            (> (- now registered-at) timeout-ms)))
                        @callback-registry)]
    (doseq [entry expired]
      (let [cb-uuid (key entry)]
        (swap! callback-registry dissoc cb-uuid)
        (log! {:level :warn :id :sente-lite.v2/cb-expired
               :data {:cb-uuid cb-uuid}})))
    (count expired)))

(defn pending-callbacks
  "Get count of pending callbacks"
  []
  (count @callback-registry))
