(ns telemere-lite.core
  "Lightweight telemetry wrapper around BB's built-in logging.
   Provides structured logging with JSON output for AI visibility."
  #?(:bb  (:require [clojure.tools.logging :as log]
                    [taoensso.timbre :as timbre]
                    [cheshire.core :as json]
                    [clojure.java.io :as io])
     :scittle (:require [clojure.string :as str])))

(def ^:dynamic *telemetry-enabled* true)
(def ^:dynamic *log-level* :info)
(def ^:dynamic *output-file* "logs/telemetry.jsonl")

#?(:bb
   (defn configure-timbre!
     "Configure Timbre for structured JSON logging in BB"
     []
     (timbre/merge-config!
      {:level :debug
       :appenders {:file {:enabled? true
                          :fn (fn [data]
                                (let [output-str (json/generate-string
                                                  {:timestamp (:instant data)
                                                   :level (:level data)
                                                   :ns (:?ns-str data)
                                                   :msg (:vargs data)
                                                   :context (:context data)})]
                                  (spit *output-file* (str output-str "\n") :append true)))}}})))

#?(:bb
   (defn ensure-log-dir! []
     "Ensure logs directory exists"
     (let [log-dir (io/file "logs")]
       (when-not (.exists log-dir)
         (.mkdirs log-dir)))))

(defn now
  "Current timestamp in ISO format"
  []
  #?(:bb (.toString (java.time.Instant/now))
     :scittle (.toISOString (js/Date.))))

(defn- serialize-for-json
  "Convert objects to JSON-serializable form"
  [obj]
  (cond
    #?(:bb (instance? Throwable obj)
       :scittle false)
    #?(:bb {:type (str (type obj))
            :message (str obj)
            :class (str (class obj))}
       :scittle {})

    (map? obj) (into {} (map (fn [[k v]] [k (serialize-for-json v)]) obj))
    (sequential? obj) (mapv serialize-for-json obj)
    :else obj))

;; Store our own ns-filter since BB Timbre doesn't have built-in ns filtering
#?(:bb (def ^:dynamic *ns-filter* {:allow #{"*"} :deny #{}}))

;; Store event-id filter for event correlation
#?(:bb (def ^:dynamic *event-id-filter* {:allow #{"*"} :deny #{}}))

;; Store handlers for routing
#?(:bb (def ^:dynamic *handlers* (atom {})))

#?(:bb
   (defn- ns-allowed?
     "Check if namespace is allowed by current filter"
     [ns-str]
     (let [{:keys [allow deny]} *ns-filter*
           denied? (some #(re-matches (re-pattern (clojure.string/replace % "*" ".*")) ns-str) deny)
           allowed? (some #(re-matches (re-pattern (clojure.string/replace % "*" ".*")) ns-str) allow)]
       (and (not denied?) allowed?))))

#?(:bb
   (defn- event-id-allowed?
     "Check if event-id is allowed by current filter"
     [event-id]
     (if (nil? event-id)
       true  ; Allow signals without event-id
       (let [{:keys [allow deny]} *event-id-filter*
             event-id-str (str event-id)
             denied? (some #(re-matches (re-pattern (clojure.string/replace (str %) "*" ".*")) event-id-str) deny)
             allowed? (some #(re-matches (re-pattern (clojure.string/replace (str %) "*" ".*")) event-id-str) allow)]
         (and (not denied?) allowed?)))))

#_{:clj-kondo/ignore [:unused-binding]}
(defn- log-with-location!
  "Internal function that does the actual logging with source location"
  [level msg context file line ns-str]
  (when (and *telemetry-enabled*
             #?(:bb (and (ns-allowed? ns-str)
                         (event-id-allowed? (:event-id context)))
                :scittle true))
    (let [location {:file file :line line :ns ns-str}
          safe-context (serialize-for-json (or context {}))
          enhanced-context (assoc safe-context :location location)
          signal {:timestamp (now)
                  :level level
                  :ns ns-str
                  :msg [msg enhanced-context]
                  :context nil}]
      #?(:bb (do
               ;; Send to configured handlers
               (doseq [[handler-id {:keys [handler-fn enabled?]}] @*handlers*]
                 (when enabled?
                   (try
                     (handler-fn signal)
                     (catch Exception e
                       (binding [*out* *err*]
                         (println "Handler" handler-id "failed:" (.getMessage e)))))))
               ;; Also send to Timbre (for backwards compatibility)
               (case level
                 :debug (timbre/debug msg enhanced-context)
                 :info  (timbre/info msg enhanced-context)
                 :warn  (timbre/warn msg enhanced-context)
                 :error (timbre/error msg enhanced-context)
                 (timbre/info msg enhanced-context)))
         :scittle (js/console.log (str "[" level "] " msg " " (->json signal)))))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defmacro signal!
  "Core signal macro - the foundation of all telemetry (like official Telemere)"
  [opts]
  `(let [opts# ~opts
         level# (get opts# :level :info)
         msg# (get opts# :msg "")
         data# (dissoc opts# :level :msg)]
     (log-with-location! level# msg# data#
                         ~(str *file*) ~(:line (meta &form)) ~(str *ns*))))

(defmacro log!
  "Logging macro built on signal! (matching official Telemere API)"
  ([opts-or-level]
   `(if (map? ~opts-or-level)
      ;; Full options map: (log! {:level :info :msg "Message"})
      (signal! ~opts-or-level)
      ;; Level only: (log! :info)
      (signal! {:level ~opts-or-level})))
  ([level msg]
   ;; Level + message: (log! :info "Message")
   `(signal! {:level ~level :msg ~msg}))
  ([level msg data]
   ;; Level + message + data: (log! :info "Message" {:key "value"})
   `(signal! {:level ~level :msg ~msg :data ~data})))

(defn startup!
  "Log BB startup with system info"
  []
  #?(:bb
     (do
       (ensure-log-dir!)
       (configure-timbre!)
       (log! :info "BB startup initiated"
             {:bb-version (System/getProperty "babashka.version")
              :java-version (System/getProperty "java.version")
              :os-name (System/getProperty "os.name")
              :user-dir (System/getProperty "user.dir")}))
     :scittle
     (log! :info "Scittle startup initiated"
           {:user-agent (.-userAgent js/navigator)
            :location (.-href js/location)})))

(defn module-load!
  "Log module loading with timing"
  [module-name]
  (log! :info "Module loading" {:module module-name :timestamp (now)}))

(defn module-loaded!
  "Log module loaded successfully"
  [module-name duration-ms]
  (log! :info "Module loaded" {:module module-name :duration-ms duration-ms}))

(defmacro event!
  "Event logging macro with ID-based correlation (matching official Telemere API)"
  ([event-id]
   ;; Event ID only: (event! ::user-login)
   `(signal! {:level :info
              :msg "Event"
              :event-id ~event-id}))
  ([event-id data]
   ;; Event ID + data: (event! ::user-login {:user-id 123})
   `(signal! {:level :info
              :msg "Event"
              :event-id ~event-id
              :data ~data}))
  ([level event-id data]
   ;; Level + Event ID + data: (event! :debug ::ws-ping {:conn-id "abc"})
   `(signal! {:level ~level
              :msg "Event"
              :event-id ~event-id
              :data ~data})))

(defmacro error!
  "Error logging macro built on signal! (matching official Telemere API)"
  ([error-or-opts]
   `(if (map? ~error-or-opts)
      ;; Full options map: (error! {:msg "Error" :error ex})
      (signal! (assoc ~error-or-opts :level :error))
      ;; Exception/Throwable: (error! (Exception. "Something went wrong"))
      (signal! {:level :error
                :msg (str ~error-or-opts)
                :error ~error-or-opts})))
  ([msg error-or-data]
   ;; Message + error/data: (error! "Failed to connect" ex)
   `(signal! {:level :error
              :msg ~msg
              :error ~error-or-data})))

(defmacro performance!
  "Performance logging macro built on signal!"
  ([operation duration-ms]
   `(signal! {:level :info
              :msg "Performance metric"
              :operation ~operation
              :duration-ms ~duration-ms}))
  ([operation duration-ms context]
   `(signal! (merge {:level :info
                     :msg "Performance metric"
                     :operation ~operation
                     :duration-ms ~duration-ms}
                    ~context))))

(defmacro with-timing
  "Execute body and log timing built on signal!"
  [operation & body]
  `(let [start# #?(:bb (System/currentTimeMillis)
                   :scittle (.getTime (js/Date.)))
         result# (do ~@body)
         duration# (- #?(:bb (System/currentTimeMillis)
                         :scittle (.getTime (js/Date.)))
                      start#)]
     (performance! ~operation duration#)
     result#))

#_{:clj-kondo/ignore [:unused-binding]}
(defn ->json
  "Convert data to JSON string for browser output"
  [data]
  #?(:bb (json/generate-string data)
     :scittle (js/JSON.stringify (clj->js data))))

;; Telemere-compatible filtering API wrapping Timbre

#?(:bb
   (defn set-min-level!
     "Set minimum signal level (matching official Telemere API)"
     ([level]
      (timbre/set-min-level! level))
     ([signal-type ns-pattern level]
      ;; For namespace-specific levels, use BB Timbre's set-ns-min-level
      ;; Note: signal-type is ignored for now in our lite implementation
      (timbre/set-ns-min-level level ns-pattern))))

#?(:bb
   (defn get-min-level
     "Get current minimum signal level (matching official Telemere API)"
     ([]
      ;; BB Timbre doesn't have get-min-level, so we read from config
      (get timbre/*config* :min-level :debug))
     ([ns-str]
      ;; For namespace-specific levels, check config
      (get-in timbre/*config* [:ns-min-level ns-str]
              (get timbre/*config* :min-level :debug)))))

#?(:bb
   (defn set-ns-filter!
     "Set namespace-based signal filtering (matching official Telemere API)"
     [{:keys [allow disallow]}]
     (let [allow-patterns (if (coll? allow) (set allow) #{allow})
           disallow-patterns (if (coll? disallow) (set disallow) #{disallow})]
       (alter-var-root #'*ns-filter*
                       (constantly {:allow allow-patterns
                                    :deny disallow-patterns})))))

#?(:bb
   (defn set-id-filter!
     "Set event-id-based signal filtering (matching official Telemere API)"
     [{:keys [allow disallow]}]
     (let [allow-patterns (if (coll? allow) (set allow) #{allow})
           disallow-patterns (if (coll? disallow) (set disallow) #{disallow})]
       (alter-var-root #'*event-id-filter*
                       (constantly {:allow allow-patterns
                                    :deny disallow-patterns})))))

#?(:bb
   (defn get-filters
     "Get current filter configuration (matching official Telemere API style)"
     []
     {:min-level (get timbre/*config* :min-level :debug)
      :ns-filter *ns-filter*
      :event-id-filter *event-id-filter*
      :ns-min-levels (get timbre/*config* :ns-min-level {})
      :enabled? *telemetry-enabled*}))

#?(:bb
   (defn clear-filters!
     "Clear all filters, reverting to defaults (utility function)"
     []
     (timbre/set-min-level! :debug)
     (alter-var-root #'*ns-filter* (constantly {:allow #{"*"} :deny #{}}))
     (alter-var-root #'*event-id-filter* (constantly {:allow #{"*"} :deny #{}}))
     (alter-var-root #'*telemetry-enabled* (constantly true))))

(defn set-enabled!
  "Enable/disable all telemetry (cross-platform)"
  [enabled?]
  #?(:bb (alter-var-root #'*telemetry-enabled* (constantly enabled?))
     :scittle (set! *telemetry-enabled* enabled?)))

(defn get-enabled?
  "Check if telemetry is enabled (cross-platform)"
  []
  *telemetry-enabled*)

;; Telemere-compatible handler/routing API

#?(:bb
   (defn- file-handler
     "Handler that writes to file in JSON Lines format"
     [file-path]
     (fn [signal]
       (try
         (let [output-str (json/generate-string signal)]
           (spit file-path (str output-str "\n") :append true))
         (catch Exception e
           (binding [*out* *err*]
             (println "Error writing to log file:" (.getMessage e))))))))

#?(:bb
   (defn- stdout-handler
     "Handler that writes to stdout"
     []
     (fn [signal]
       (try
         (println (json/generate-string signal))
         (catch Exception e
           (binding [*out* *err*]
             (println "Error writing to stdout:" (.getMessage e))))))))

#?(:bb
   (defn- stderr-handler
     "Handler that writes to stderr"
     []
     (fn [signal]
       (try
         (binding [*out* *err*]
           (println (json/generate-string signal)))
         (catch Exception e
           (binding [*out* *err*]
             (println "Error writing to stderr:" (.getMessage e))))))))

#?(:bb
   (defn add-handler!
     "Add a telemetry handler (matching official Telemere API)"
     ([handler-id handler-fn]
      (add-handler! handler-id handler-fn {}))
     ([handler-id handler-fn opts]
      (swap! *handlers* assoc handler-id
             {:handler-fn handler-fn
              :opts opts
              :enabled? true}))))

#?(:bb
   (defn remove-handler!
     "Remove a telemetry handler (matching official Telemere API)"
     [handler-id]
     (swap! *handlers* dissoc handler-id)))

#?(:bb
   (defn get-handlers
     "Get current handlers (matching official Telemere API style)"
     []
     @*handlers*))

#?(:bb
   (defn clear-handlers!
     "Clear all handlers (utility function)"
     []
     (reset! *handlers* {})))

;; Convenience functions for common handlers
#?(:bb
   (defn add-file-handler!
     "Add file output handler"
     ([file-path]
      (add-file-handler! :file file-path))
     ([handler-id file-path]
      (add-handler! handler-id (file-handler file-path)))))

#?(:bb
   (defn add-stdout-handler!
     "Add stdout output handler"
     ([]
      (add-stdout-handler! :stdout))
     ([handler-id]
      (add-handler! handler-id (stdout-handler)))))

#?(:bb
   (defn add-stderr-handler!
     "Add stderr output handler"
     ([]
      (add-stderr-handler! :stderr))
     ([handler-id]
      (add-handler! handler-id (stderr-handler)))))