(ns sente-lite.wire-multiplexer
  "Wire format multiplexing support for sente-lite
   Allows different wire formats to be used over a single WebSocket connection"
  (:require [sente-lite.wire-format :as wire]
            [telemere-lite.core :as tel]
            #?(:bb [cheshire.core :as json]
               :clj [clojure.data.json :as json]))
  (:import [java.lang System Exception]))

;; ============================================================================
;; Multiplexing Protocol Design
;; ============================================================================

;; Envelope format (always JSON for maximum compatibility):
;; {:format "edn" :payload "serialized-data"}
;; or compact: {:f "edn" :p "data"}

(def default-envelope-format :json)

;; JSON serialization helpers
(defn- to-json [data]
  #?(:bb (json/generate-string data)
     :clj (json/write-str data)))

(defn- from-json [data]
  #?(:bb (json/parse-string data true)
     :clj (json/read-str data :key-fn keyword)))

;; ============================================================================
;; Message Envelope Functions
;; ============================================================================

(defn wrap-message
  "Wrap a message with format metadata in a JSON envelope"
  [message format-spec]
  (let [wire-format (wire/get-format format-spec)
        serialized-payload (wire/serialize wire-format message)
        envelope {:format (wire/format-name wire-format)
                  :payload serialized-payload}]

    ;; Return serialized envelope
    (to-json envelope)))

(defn unwrap-message
  "Unwrap a message from its JSON envelope and deserialize payload"
  [raw-envelope-data]
  (try
    ;; Parse JSON envelope
    (let [envelope (from-json raw-envelope-data)
          format-name (:format envelope)
          payload-data (:payload envelope)]

      (when-not (and format-name payload-data)
        (throw (ex-info "Invalid envelope format"
                        {:envelope envelope})))

      ;; Find wire format by name
      (let [format-spec (case format-name
                          "JSON" :json
                          "EDN" :edn
                          "Transit+JSON" :transit-json
                          "Transit+JSON+Bencode" :transit-json-bencode
                          (throw (ex-info "Unknown wire format"
                                          {:format-name format-name})))

            wire-format (wire/get-format format-spec)
            parsed-message (wire/deserialize wire-format payload-data)]

        {:format format-spec
         :message parsed-message}))

    (catch Exception e
      (tel/error! {:id :sente-lite.mux/unwrap-failed
                   :error e
                   :data {:raw-data (subs raw-envelope-data 0
                                          (min 100 (count raw-envelope-data)))}})
      nil)))

;; ============================================================================
;; Format Detection & Auto-negotiation
;; ============================================================================

(defn detect-message-format
  "Attempt to detect the wire format of a raw message"
  [raw-message]
  (cond
    ;; Check for JSON envelope structure
    (and (string? raw-message)
         (or (.startsWith raw-message "{\"format\":")
             (.startsWith raw-message "{\"f\":")))
    :multiplexed

    ;; Check for plain JSON
    (and (string? raw-message)
         (.startsWith raw-message "{"))
    :json

    ;; Check for EDN (starts with { or [ or keyword)
    (and (string? raw-message)
         (or (.startsWith raw-message "{:")
             (.startsWith raw-message "#{")
             (.startsWith raw-message ":")))
    :edn

    ;; Check for Transit (starts with [)
    (and (string? raw-message)
         (.startsWith raw-message "["))
    :transit-json

    ;; Default fallback
    :else
    :unknown))

(defn parse-any-format
  "Parse a message, auto-detecting format or using multiplexed envelope"
  [raw-message conn-id]
  (let [detected-format (detect-message-format raw-message)]

    (tel/log! {:level :trace
               :id :sente-lite.mux/format-detect
               :data {:conn-id conn-id
                      :detected-format detected-format}})

    (case detected-format
      :multiplexed
      (let [unwrapped (unwrap-message raw-message)]
        (when unwrapped
          (assoc (:message unwrapped)
                 :_wire-format (:format unwrapped))))

      (:json :edn :transit-json)
      (let [wire-format (wire/get-format detected-format)]
        (when-let [parsed (wire/deserialize wire-format raw-message)]
          (assoc parsed :_wire-format detected-format)))

      :unknown
      (do
        (tel/error! {:id :sente-lite.mux/parse-failed
                     :data {:conn-id conn-id
                            :message-preview (subs raw-message 0 (min 100 (count raw-message)))}})
        nil))))

;; ============================================================================
;; Channel-Specific Format Negotiation
;; ============================================================================

(defonce ^:private channel-formats (atom {}))

(defn set-channel-format!
  "Set the preferred wire format for a specific channel"
  [channel-id format-spec]
  (let [wire-format (wire/get-format format-spec)]
    (swap! channel-formats assoc channel-id format-spec)
    (tel/log! {:level :debug
               :id :sente-lite.mux/chan-fmt-set
               :data {:channel-id channel-id
                      :format (wire/format-name wire-format)}})
    format-spec))

(defn get-channel-format
  "Get the preferred wire format for a channel, or default"
  [channel-id default-format]
  (get @channel-formats channel-id default-format))

(defn negotiate-channel-format
  "Handle format negotiation for channel operations"
  [message default-format]
  (let [requested-format (:wire-format message)
        channel-id (:channel-id message)

        negotiated-format (cond
                           ;; Explicit format request
                            requested-format
                            (do
                              (when channel-id
                                (set-channel-format! channel-id requested-format))
                              requested-format)

                           ;; Channel has preferred format
                            channel-id
                            (get-channel-format channel-id default-format)

                           ;; Use detected format from message
                            (:_wire-format message)
                            (:_wire-format message)

                           ;; Default
                            :else
                            default-format)]

    (tel/log! {:level :trace
               :id :sente-lite.mux/fmt-negotiated
               :data {:channel-id channel-id
                      :requested-format requested-format
                      :negotiated-format negotiated-format}})

    negotiated-format))

;; ============================================================================
;; Response Format Selection
;; ============================================================================

(defn select-response-format
  "Select appropriate format for response based on request"
  [request-message default-format]
  (or
   ;; Use format from original request
   (:_wire-format request-message)

   ;; Use channel-specific format
   (when-let [channel-id (:channel-id request-message)]
     (get-channel-format channel-id nil))

   ;; Use requested format
   (:wire-format request-message)

   ;; Default format
   default-format))

;; ============================================================================
;; Statistics & Monitoring
;; ============================================================================

(defn get-multiplexing-stats
  "Get statistics about wire format usage"
  []
  (let [channel-format-counts (frequencies (vals @channel-formats))]
    {:total-channels (count @channel-formats)
     :format-distribution channel-format-counts
     :channels-by-format (group-by (fn [[_ format]] format) @channel-formats)}))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn supports-multiplexing?
  "Check if a format supports being multiplexed"
  [format-spec]
  ;; All our current formats support multiplexing
  (contains? #{:json :edn :transit-json :transit-json-bencode} format-spec))

(defn get-optimal-format
  "Suggest optimal format based on data characteristics"
  [data]
  (cond
    ;; Contains byte arrays - use Transit
    (some #(= (type %) (type (.getBytes "test"))) (tree-seq coll? seq data))
    :transit-json

    ;; Simple data types only - use JSON for compatibility
    (every? #(or (string? %) (number? %) (boolean? %) (nil? %)) (tree-seq coll? seq data))
    :json

    ;; Complex Clojure data - use EDN
    :else
    :edn))

;; ============================================================================
;; Format Migration Support
;; ============================================================================

(defn create-format-migration
  "Create a migration function to gradually transition formats"
  [from-format to-format]
  (fn [message]
    ;; Could implement gradual migration logic here
    ;; For now, just log the intent
    (tel/log! {:level :debug
               :id :sente-lite.mux/migration-candidate
               :data {:from-format from-format
                      :to-format to-format
                      :message-type (:type message)}})
    to-format))

;; ============================================================================
;; Testing Support
;; ============================================================================

(defn test-multiplexing
  "Test multiplexing with sample data"
  [test-data]
  (let [test-message {:type "test" :data test-data :timestamp (System/currentTimeMillis)}]

    ;; Test each format
    (doseq [format-spec [:json :edn :transit-json]]
      (let [wrapped (wrap-message test-message format-spec)
            unwrapped (unwrap-message wrapped)]

        (tel/log! {:level :info
                   :id :sente-lite.mux/test-result
                   :data {:format format-spec
                          :wrapped-size (count wrapped)
                          :success (some? (:message unwrapped))}})

        ;; Verify round-trip
        (when-not (= test-message (:message unwrapped))
          (tel/log! {:level :warn
                     :id :sente-lite.mux/test-failed
                     :data {:format format-spec}}))))

    ;; Test auto-detection
    (let [json-msg (to-json test-message)
          detected (detect-message-format json-msg)]
      (tel/log! {:level :info
                 :id :sente-lite.mux/test-detect
                 :data {:sample (subs json-msg 0 20)
                        :detected-format detected}}))))