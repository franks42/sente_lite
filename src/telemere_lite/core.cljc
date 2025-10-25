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

#_{:clj-kondo/ignore [:unused-binding]}
(defn- log-with-location!
  "Internal function that does the actual logging with source location"
  [level msg context file line ns-str]
  #_{:clj-kondo/ignore [:missing-body-in-when]}
  (when *telemetry-enabled*
    (let [location {:file file :line line :ns ns-str}
          enhanced-context (assoc (or context {}) :location location)]
      #?(:bb (case level
               :debug (timbre/debug msg enhanced-context)
               :info  (timbre/info msg enhanced-context)
               :warn  (timbre/warn msg enhanced-context)
               :error (timbre/error msg enhanced-context)
               (timbre/info msg enhanced-context))
         :scittle (let [log-data {:timestamp (now)
                                 :level level
                                 :msg msg
                                 :context enhanced-context}]
                    (js/console.log (str "[" level "] " msg " " (->json log-data))))))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defmacro log!
  "Primary telemetry logging macro with source location capture"
  ([msg] `(log! :info ~msg nil))
  ([level msg] `(log! ~level ~msg nil))
  ([level msg context]
   `(log-with-location! ~level ~msg ~context
                        ~(str *file*) ~(:line (meta &form)) ~(str *ns*))))

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

(defmacro error!
  "Log error with context and source location"
  ([msg] `(error! ~msg {}))
  ([msg error-data]
   `(log-with-location! :error ~msg ~error-data
                        ~(str *file*) ~(:line (meta &form)) ~(str *ns*))))

(defmacro performance!
  "Log performance metrics with source location"
  ([operation duration-ms] `(performance! ~operation ~duration-ms {}))
  ([operation duration-ms context]
   `(log-with-location! :info "Performance metric"
                        (merge {:operation ~operation :duration-ms ~duration-ms} ~context)
                        ~(str *file*) ~(:line (meta &form)) ~(str *ns*))))

(defmacro with-timing
  "Execute body and log timing with source location"
  [operation & body]
  `(let [start# #?(:bb (System/currentTimeMillis)
                   :scittle (.getTime (js/Date.)))
         result# (do ~@body)
         duration# (- #?(:bb (System/currentTimeMillis)
                         :scittle (.getTime (js/Date.)))
                      start#)]
     (log-with-location! :info "Performance metric"
                         {:operation ~operation :duration-ms duration#}
                         ~(str *file*) ~(:line (meta &form)) ~(str *ns*))
     result#))

#_{:clj-kondo/ignore [:unused-binding]}
(defn ->json
  "Convert data to JSON string for browser output"
  [data]
  #?(:bb (json/generate-string data)
     :scittle (js/JSON.stringify (clj->js data))))