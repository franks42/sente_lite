(ns sente-lite.serialization
  "Pluggable wire format protocol for sente-lite serialization
   Supports JSON, EDN, Transit+JSON, and custom formats"
  (:require #?(:bb [cheshire.core :as json]
               :clj [clojure.data.json :as json])
            [cognitect.transit :as transit]
            [sente-lite.packer :as packer]
            [taoensso.trove :as trove])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util Arrays]
           [java.lang Exception]))

;; ============================================================================
;; Wire Format Protocol
;; ============================================================================

(defprotocol IWireFormat
  "Protocol for pluggable serialization formats"
  (serialize [this data]
    "Serialize Clojure data to wire format (string or bytes)")
  (deserialize [this wire-data]
    "Deserialize wire data (string or bytes) to Clojure data")
  (content-type [this]
    "Returns MIME content-type for HTTP headers")
  (format-name [this]
    "Returns human-readable format name for logging/debugging")
  (binary? [this]
    "Returns true if format produces binary output, false for text"))

;; ============================================================================
;; JSON Format (Current Implementation)
;; ============================================================================

(defrecord JsonWireFormat []
  IWireFormat
  (serialize [_ data]
    (try
      #?(:bb (json/generate-string data)
         :clj (json/write-str data))
      (catch Exception e
        (trove/log! {:level :error :id :sente-lite.format/json-serial-failed
                     :data {:error e :input-type (type data)}})
        nil)))

  (deserialize [_ wire-data]
    (try
      #?(:bb (json/parse-string wire-data true)
         :clj (json/read-str wire-data :key-fn keyword))
      (catch Exception e
        (trove/log! {:level :error :id :sente-lite.format/json-deserial-failed
                     :data {:error e
                            :wire-data-preview (subs (str wire-data) 0
                                                     (min 100 (count wire-data)))}})
        nil)))

  (content-type [_] "application/json")
  (format-name [_] "JSON")
  (binary? [_] false))

;; ============================================================================
;; EDN Format
;; ============================================================================

(defrecord EdnWireFormat []
  IWireFormat
  (serialize [_ data]
    (try
      (packer/pack data)
      (catch Exception e
        (trove/log! {:level :error :id :sente-lite.format/edn-serial-failed
                     :data {:error e :input-type (type data)}})
        nil)))

  (deserialize [_ wire-data]
    (try
      (packer/unpack wire-data)
      (catch Exception e
        (trove/log! {:level :error :id :sente-lite.format/edn-deserial-failed
                     :data {:error e
                            :wire-data-preview (subs (str wire-data) 0
                                                     (min 100 (count wire-data)))}})
        nil)))

  (content-type [_] "application/edn")
  (format-name [_] "EDN")
  (binary? [_] false))

;; ============================================================================
;; Transit+JSON Format (Lossless Clojure data in JSON transport)
;; ============================================================================

(defrecord TransitJsonWireFormat [read-handlers write-handlers]
  IWireFormat
  (serialize [_ data]
    (try
      (let [out (ByteArrayOutputStream.)
            writer (transit/writer out :json {:write-transforms write-handlers})]
        (transit/write writer data)
        (.toString out "UTF-8"))
      (catch Exception e
        (trove/log! {:level :error :id :sente-lite.format/transit-serial-failed
                     :data {:error e :input-type (type data)}})
        nil)))

  (deserialize [_ wire-data]
    (try
      (let [in (ByteArrayInputStream. (.getBytes wire-data "UTF-8"))
            reader (transit/reader in :json {:read-transforms read-handlers})]
        (transit/read reader))
      (catch Exception e
        (trove/log! {:level :error :id :sente-lite.format/transit-deserial-failed
                     :data {:error e
                            :wire-data-preview (subs (str wire-data) 0
                                                     (min 100 (count wire-data)))}})
        nil)))

  (content-type [_] "application/transit+json")
  (format-name [_] "Transit+JSON")
  (binary? [_] false))

;; ============================================================================
;; Transit+JSON+Bencode Format (For nREPL tunneling)
;; ============================================================================

(defrecord TransitJsonBencodeWireFormat [read-handlers write-handlers]
  IWireFormat
  (serialize [_ data]
    (try
      ;; Transit+JSON natively handles byte arrays - no manual encoding needed
      (let [out (ByteArrayOutputStream.)
            writer (transit/writer out :json {:write-transforms write-handlers})]
        (transit/write writer data)
        (.toString out "UTF-8"))
      (catch Exception e
        (trove/log! {:level :error :id :sente-lite.format/bencode-serial-failed
                     :data {:error e :input-type (type data)}})
        nil)))

  (deserialize [_ wire-data]
    (try
      (let [in (ByteArrayInputStream. (.getBytes wire-data "UTF-8"))
            reader (transit/reader in :json {:read-transforms read-handlers})]
        (transit/read reader))
      (catch Exception e
        (trove/log! {:level :error :id :sente-lite.format/bencode-deserial-failed
                     :data {:error e
                            :wire-data-preview (subs (str wire-data) 0
                                                     (min 100 (count wire-data)))}})
        nil)))

  (content-type [_] "application/transit+json+bencode")
  (format-name [_] "Transit+JSON+Bencode")
  (binary? [_] false))

;; ============================================================================
;; Factory Functions
;; ============================================================================

(defn create-json-format
  "Create a JSON wire format instance (lossy for Clojure types)"
  []
  (->JsonWireFormat))

(defn create-edn-format
  "Create an EDN wire format instance (lossless for Clojure types)"
  []
  (->EdnWireFormat))

(defn create-transit-json-format
  "Create a Transit+JSON wire format instance (lossless with JSON transport)
   Options:
   - :read-handlers - map of type tags to read handler functions
   - :write-handlers - map of types to write handler functions"
  [& {:keys [read-handlers write-handlers]
      :or {read-handlers {}
           write-handlers {}}}]
  (->TransitJsonWireFormat read-handlers write-handlers))

(defn create-transit-json-bencode-format
  "Create a Transit+JSON+Bencode wire format instance for nREPL tunneling
   Combines Transit+JSON lossless Clojure serialization with base64-encoded bencode payload support.
   Options:
   - :read-handlers - map of type tags to read handler functions
   - :write-handlers - map of types to write handler functions"
  [& {:keys [read-handlers write-handlers]
      :or {read-handlers {}
           write-handlers {}}}]
  (->TransitJsonBencodeWireFormat read-handlers write-handlers))

;; ============================================================================
;; Format Registry & Selection
;; ============================================================================

(def ^:private format-registry
  "Built-in format registry"
  (atom {:json (create-json-format)
         :edn (create-edn-format)
         :transit-json (create-transit-json-format)
         :transit-json-bencode (create-transit-json-bencode-format)}))

(defn register-format!
  "Register a custom wire format implementation"
  [format-key wire-format]
  (swap! format-registry assoc format-key wire-format)
  (trove/log! {:level :debug :id :sente-lite.format/registered
               :data {:format-key format-key :format-name (format-name wire-format)}})
  wire-format)

(defn get-format
  "Get a wire format by key or return the format if already an IWireFormat"
  [format-spec]
  (cond
    ;; Already a wire format instance
    (satisfies? IWireFormat format-spec)
    format-spec

    ;; Registered format key
    (keyword? format-spec)
    (or (get @format-registry format-spec)
        (throw (ex-info "Unknown wire format"
                        {:format format-spec
                         :available (keys @format-registry)})))

    ;; Default to JSON for backward compatibility
    :else
    (get @format-registry :json)))

;; ============================================================================
;; Utility Functions
;; ============================================================================

(defn format-info
  "Get information about a wire format"
  [format-spec]
  (let [fmt (get-format format-spec)]
    {:name (format-name fmt)
     :content-type (content-type fmt)
     :binary? (binary? fmt)}))

(defn available-formats
  "List all available wire formats"
  []
  (into {}
        (map (fn [[k v]]
               [k (format-info v)])
             @format-registry)))

;; ============================================================================
;; Testing & Validation
;; ============================================================================

(defn- deep-equal?
  "Deep equality comparison that handles byte arrays properly"
  [a b]
  (cond
    ;; Both are byte arrays
    (and (= (type a) (type (.getBytes "test")))
         (= (type b) (type (.getBytes "test"))))
    (Arrays/equals a b)

    ;; Both are maps - recursively compare
    (and (map? a) (map? b))
    (and (= (set (keys a)) (set (keys b)))
         (every? (fn [k] (deep-equal? (get a k) (get b k))) (keys a)))

    ;; Both are collections - convert to seqs and compare recursively
    (and (coll? a) (coll? b))
    (and (= (count a) (count b))
         (every? (fn [[x y]] (deep-equal? x y)) (map vector a b)))

    ;; Default equality
    :else
    (= a b)))

(defn round-trip-test
  "Test if data survives serialization/deserialization"
  [format-spec test-data]
  (let [fmt (get-format format-spec)]
    (try
      (let [serialized (serialize fmt test-data)
            deserialized (deserialize fmt serialized)]
        {:success true
         :format (format-name fmt)
         :original test-data
         :serialized serialized
         :deserialized deserialized
         :equal? (deep-equal? test-data deserialized)})
      (catch Exception e
        {:success false
         :format (format-name fmt)
         :error (str e)}))))

;; ============================================================================
;; Migration Helpers
;; ============================================================================

(defn compare-formats
  "Compare serialization results between different formats"
  [data & format-specs]
  (into {}
        (map (fn [format-spec]
               (let [fmt (get-format format-spec)]
                 [format-spec
                  (try
                    {:serialized (serialize fmt data)
                     :size (count (str (serialize fmt data)))
                     :format-name (format-name fmt)}
                    (catch Exception e
                      {:error (str e)}))]))
             format-specs)))