(ns sente-lite.transit-multiplexer
  "Transit-native multiplexing for different encoded message types
   Uses Transit's tagged value system for elegant message type multiplexing"
  (:require [cognitect.transit :as transit]
            [telemere-lite.core :as tel]
            #?(:bb [cheshire.core :as json]
               :clj [clojure.data.json :as json])
            [clojure.edn :as edn])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util Base64]
           [java.lang System Exception]))

;; ============================================================================
;; Transit Tagged Value Multiplexing
;; ============================================================================

;; Transform maps for encoding different payload types
(def multiplex-write-transforms
  {"j" (fn [json-payload]
         ;; JSON-encoded payload
         (if (string? json-payload)
           json-payload
           #?(:bb (json/generate-string json-payload)
              :clj (json/write-str json-payload))))

   "e" (fn [edn-payload]
         ;; EDN-encoded payload
         (if (string? edn-payload)
           edn-payload
           (pr-str edn-payload)))

   "b" (fn [bencode-payload]
         ;; Base64-encoded bencode payload
         (if (string? bencode-payload)
           bencode-payload
           ;; Assume byte array input for bencode
           (.encodeToString (Base64/getEncoder) bencode-payload)))

   "r" (fn [raw-payload]
         ;; Raw Transit-encoded payload (passthrough)
         raw-payload)

   "x" (fn [binary-payload]
         ;; Arbitrary binary data
         (if (string? binary-payload)
           binary-payload
           (.encodeToString (Base64/getEncoder) binary-payload)))})

(def multiplex-read-transforms
  {"j" (fn [json-string]
         ;; Parse JSON payload
         (try
           #?(:bb (json/parse-string json-string true)
              :clj (json/read-str json-string :key-fn keyword))
           (catch Exception e
             (tel/error! {:msg "Failed to parse JSON payload"
                          :error e
                          :data {:payload json-string}})
             {:error "Invalid JSON" :raw json-string})))

   "e" (fn [edn-string]
         ;; Parse EDN payload
         (try
           (edn/read-string edn-string)
           (catch Exception e
             (tel/error! {:msg "Failed to parse EDN payload"
                          :error e
                          :data {:payload edn-string}})
             {:error "Invalid EDN" :raw edn-string})))

   "b" (fn [base64-string]
         ;; Decode base64 bencode payload
         (try
           (.decode (Base64/getDecoder) base64-string)
           (catch Exception e
             (tel/error! {:msg "Failed to decode base64 payload"
                          :error e
                          :data {:payload base64-string}})
             {:error "Invalid base64" :raw base64-string})))

   "r" (fn [raw-payload]
         ;; Raw payload passthrough
         raw-payload)

   "x" (fn [base64-binary]
         ;; Arbitrary binary data
         (try
           (.decode (Base64/getDecoder) base64-binary)
           (catch Exception e
             (tel/error! {:msg "Failed to decode binary payload"
                          :error e
                          :data {:payload base64-binary}})
             {:error "Invalid binary" :raw base64-binary})))})

;; ============================================================================
;; Transit Multiplexer Factory
;; ============================================================================

(defn create-multiplexing-writer
  "Create a Transit writer with multiplexing transforms"
  [output-stream & {:keys [additional-transforms]}]
  (let [transforms (merge multiplex-write-transforms additional-transforms)]
    (transit/writer output-stream :json {:write-transforms transforms})))

(defn create-multiplexing-reader
  "Create a Transit reader with multiplexing transforms"
  [input-stream & {:keys [additional-transforms]}]
  (let [transforms (merge multiplex-read-transforms additional-transforms)]
    (transit/reader input-stream :json {:read-transforms transforms})))

;; ============================================================================
;; Message Encoding Functions
;; ============================================================================

(defn encode-json-message
  "Encode a message as JSON within Transit"
  [message]
  (transit/tagged-value "j" message))

(defn encode-edn-message
  "Encode a message as EDN within Transit"
  [message]
  (transit/tagged-value "e" message))

(defn encode-bencode-message
  "Encode a bencode byte array within Transit"
  [bencode-bytes]
  (transit/tagged-value "b" bencode-bytes))

(defn encode-raw-message
  "Encode a raw Transit message (no additional encoding)"
  [message]
  (transit/tagged-value "r" message))

(defn encode-binary-message
  "Encode arbitrary binary data within Transit"
  [binary-data]
  (transit/tagged-value "x" binary-data))

;; ============================================================================
;; High-Level Multiplexing API
;; ============================================================================

(defn multiplex-serialize
  "Serialize multiple encoded messages in a single Transit payload"
  [messages]
  (try
    (let [out (ByteArrayOutputStream.)
          writer (create-multiplexing-writer out)]

      (tel/event! ::multiplex-serialize-start
                  {:message-count (count messages)})

      (transit/write writer messages)
      (let [result (.toString out "UTF-8")]

        (tel/event! ::multiplex-serialize-complete
                    {:message-count (count messages)
                     :total-size (count result)})

        result))
    (catch Exception e
      (tel/error! {:msg "Failed to multiplex serialize"
                   :error e
                   :data {:message-count (count messages)}})
      nil)))

(defn multiplex-deserialize
  "Deserialize a Transit payload containing multiple encoded messages"
  [wire-data]
  (try
    (let [in (ByteArrayInputStream. (.getBytes wire-data "UTF-8"))
          reader (create-multiplexing-reader in)]

      (tel/event! ::multiplex-deserialize-start
                  {:wire-size (count wire-data)})

      (let [result (transit/read reader)]

        (tel/event! ::multiplex-deserialize-complete
                    {:wire-size (count wire-data)
                     :message-count (if (coll? result) (count result) 1)})

        result))
    (catch Exception e
      (tel/error! {:msg "Failed to multiplex deserialize"
                   :error e
                   :data {:wire-data-preview (subs wire-data 0 (min 100 (count wire-data)))}})
      nil)))

;; ============================================================================
;; Channel-Specific Encoding Strategies
;; ============================================================================

(defn encode-channel-message
  "Encode a message for a specific channel with appropriate encoding"
  [channel-id message & {:keys [encoding-hint]}]
  (let [encoding (or encoding-hint
                     (cond
                       ;; nREPL channels prefer bencode
                       (.contains (str channel-id) "nrepl") :bencode

                       ;; API channels prefer EDN
                       (.contains (str channel-id) "api") :edn

                       ;; Chat/web channels prefer JSON
                       (or (.contains (str channel-id) "chat")
                           (.contains (str channel-id) "web")) :json

                       ;; Default to raw Transit
                       :else :raw))

        encoded-payload (case encoding
                          :json (encode-json-message message)
                          :edn (encode-edn-message message)
                          :bencode (encode-bencode-message message)
                          :binary (encode-binary-message message)
                          :raw (encode-raw-message message))]

    (tel/event! ::channel-message-encoded
                {:channel-id channel-id
                 :encoding encoding
                 :original-type (type message)})

    ;; Return envelope with metadata
    {:channel channel-id
     :encoding encoding
     :payload encoded-payload
     :timestamp (System/currentTimeMillis)}))

;; ============================================================================
;; Batch Message Processing
;; ============================================================================

(defn create-message-batch
  "Create a batch of differently encoded messages"
  [& messages]
  (->> messages
       (map-indexed (fn [idx msg]
                      (assoc msg :batch-index idx)))
       vec))

(defn serialize-message-batch
  "Serialize a batch of mixed-encoding messages"
  [message-batch]
  (multiplex-serialize message-batch))

(defn process-message-batch
  "Process a deserialized batch of messages"
  [deserialized-batch]
  (when (and (coll? deserialized-batch)
             (every? map? deserialized-batch))
    (->> deserialized-batch
         (sort-by :batch-index)
         (map #(dissoc % :batch-index))
         vec)))

;; ============================================================================
;; nREPL Integration Helpers
;; ============================================================================

(defn encode-nrepl-message
  "Encode an nREPL message as bencode within Transit"
  [nrepl-msg]
  ;; For now, assume bencode is pre-encoded as bytes
  ;; In practice, you'd use a bencode library here
  (let [mock-bencode (.getBytes (str nrepl-msg) "UTF-8")]
    (encode-bencode-message mock-bencode)))

(defn decode-nrepl-message
  "Decode a bencode nREPL message from Transit"
  [transit-tagged-value]
  (when (and (transit/tagged-value? transit-tagged-value)
             (= "b" (transit/tag transit-tagged-value)))
    (let [bencode-bytes (transit/rep transit-tagged-value)]
      ;; In practice, you'd use a bencode library to decode
      (#?(:bb String. :clj String.) bencode-bytes "UTF-8"))))

;; ============================================================================
;; Performance & Statistics
;; ============================================================================

(defonce ^:private encoding-stats (atom {}))

(defn track-encoding-usage
  "Track usage statistics for different encodings"
  [encoding message-size]
  (swap! encoding-stats update encoding
         (fn [stats]
           (-> (or stats {:count 0 :total-size 0})
               (update :count inc)
               (update :total-size + message-size)))))

(defn get-encoding-stats
  "Get statistics about encoding usage"
  []
  (let [stats @encoding-stats]
    (->> stats
         (map (fn [[encoding data]]
                [encoding (assoc data
                                 :avg-size (if (pos? (:count data))
                                             (/ (:total-size data) (:count data))
                                             0))]))
         (into {}))))

;; ============================================================================
;; Testing & Validation
;; ============================================================================

(defn test-transit-multiplexing
  "Test Transit multiplexing with various data types"
  []
  (let [test-messages [(encode-json-message {:type "chat" :msg "hello"})
                       (encode-edn-message {:type :status :value :online})
                       (encode-raw-message {:type "ping" :ts (System/currentTimeMillis)})
                       (encode-bencode-message (.getBytes "mock-bencode" "UTF-8"))]

        serialized (multiplex-serialize test-messages)
        deserialized (multiplex-deserialize serialized)]

    (println "Transit multiplexing test:")
    (println "  Original messages:" (count test-messages))
    (println "  Serialized size:" (count serialized) "chars")
    (println "  Deserialized messages:" (if (coll? deserialized) (count deserialized) 1))
    (println "  Round-trip success:" (= (count test-messages)
                                        (if (coll? deserialized) (count deserialized) 1)))

    {:original test-messages
     :serialized serialized
     :deserialized deserialized
     :success (some? deserialized)}))

;; ============================================================================
;; Format Detection & Auto-routing
;; ============================================================================

(defn detect-payload-encoding
  "Detect the encoding type of a payload"
  [payload]
  (cond
    (transit/tagged-value? payload)
    (case (transit/tag payload)
      "j" :json
      "e" :edn
      "b" :bencode
      "r" :raw
      "x" :binary
      :unknown)

    (string? payload) :string
    (map? payload) :map
    (vector? payload) :vector
    :else :unknown))

(defn route-by-encoding
  "Route a message based on its encoding type"
  [payload routing-map]
  (let [encoding (detect-payload-encoding payload)
        handler (get routing-map encoding)]

    (tel/event! ::message-routed
                {:encoding encoding
                 :handler-found (some? handler)})

    (when handler
      (handler payload))))