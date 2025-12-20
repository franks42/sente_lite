(ns log-routing.sender
  "Route Trove logs over sente-lite channel.

   Wraps Trove's *log-fn* to also publish log entries to a sente-lite
   channel for remote collection.

   Usage:
     (require '[log-routing.sender :as sender])
     (require '[taoensso.trove :as trove])

     ;; Wrap existing log-fn
     (def original-log-fn trove/*log-fn*)
     (trove/set-log-fn!
       (sender/make-remote-log-fn original-log-fn client-id
         {:source-id \"my-client\"}))

     ;; Logs now go to both local and remote
     (trove/log! :info \"Hello\" {:count 1})"
  (:require [clojure.string :as str]
            #?(:bb [sente-lite.client-bb :as client])))

;; Channel name for log routing
(def log-channel "log-routing")

;; Guard against re-entrant logging (prevents infinite recursion)
(def ^:dynamic *sending?* false)

;; Default namespaces to exclude (internal sente-lite logging)
(def default-exclude-ns-prefixes
  #{"sente-lite." "log-routing."})

(defn- should-send?
  "Check if this log should be sent remotely.
   Excludes internal sente-lite and log-routing namespaces."
  [ns-str exclude-prefixes]
  (not (some (fn [prefix]
               (str/starts-with? (str ns-str) prefix))
             exclude-prefixes)))

(defn make-remote-log-fn
  "Wrap a local log-fn to also publish logs via sente-lite pub/sub.

   Returns a new log-fn that:
   1. Calls local-log-fn (for local output)
   2. Publishes log entry to log-routing channel (if not excluded)

   Args:
     local-log-fn - Original Trove log-fn to chain
     client-id    - sente-lite client-id
     opts         - {:source-id \"client-123\"
                    :exclude-ns-prefixes #{\"sente-lite.\"}} ; namespaces to not send

   The receiver must be subscribed to the log-routing channel.

   Note: By default, excludes sente-lite.* and log-routing.* namespaces
   to prevent feedback loops and reduce noise."
  [local-log-fn client-id opts]
  (let [source-id (get opts :source-id "unknown")
        exclude-prefixes (get opts :exclude-ns-prefixes default-exclude-ns-prefixes)]
    (fn [ns-str coords level id lazy_]
      ;; Always call local first
      (when local-log-fn
        (local-log-fn ns-str coords level id lazy_))
      ;; Publish over sente if not excluded and not already sending
      #?(:bb
         (when (and (not *sending?*)
                    (should-send? ns-str exclude-prefixes))
           (binding [*sending?* true]
             (try
               (let [data (force lazy_)
                     entry {:ns ns-str
                            :coords coords
                            :level level
                            :id id
                            :data data
                            :timestamp (System/currentTimeMillis)
                            :source source-id}]
                 (client/publish! client-id log-channel entry))
               (catch Exception _
                 nil))))
         :cljs
         nil)))) ;; Phase 3: Add ClojureScript support
