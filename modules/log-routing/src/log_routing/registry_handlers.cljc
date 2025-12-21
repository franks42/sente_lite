(ns log-routing.registry-handlers
  "Registry-based log handler configuration.

   Uses the registry indirection pattern to make log handlers configurable:
   - Register concrete implementations (console, sente, silent)
   - Configure which implementation to use via reference
   - Switch at runtime without knowing function names

   Registry Layout:
     telemetry.impl/console  -> (fn [log-entry] ...)  ; concrete impl
     telemetry.impl/sente    -> (fn [log-entry] ...)  ; concrete impl
     telemetry.impl/silent   -> (fn [log-entry] nil)  ; concrete impl
     telemetry/log-handler   -> \"telemetry.impl/console\"  ; config pointer

   Usage:
     (require '[log-routing.registry-handlers :as rh])
     (require '[sente-lite.registry :as reg])

     ;; Initialize with default handler
     (rh/init!)

     ;; Get current handler
     (let [handler (rh/get-handler)]
       (handler log-entry))

     ;; Switch to sente routing (by name, not function!)
     (rh/use-handler! \"telemetry.impl/sente\")

     ;; Watch for handler changes
     (rh/on-handler-change! :my-key
       (fn [old-handler new-handler]
         (println \"Switched handlers!\")))"
  (:require [sente-lite.registry :as reg]
            [clojure.string :as str]))

;; ============================================================================
;; Standard Registry Names
;; ============================================================================

(def config-name
  "Registry name for the current log handler reference"
  "telemetry/log-handler")

(def impl-prefix
  "Prefix for log handler implementations"
  "telemetry.impl/")

;; ============================================================================
;; Built-in Implementations
;; ============================================================================

(defn console-handler
  "Log handler that prints to console/stdout"
  [log-entry]
  (let [level (get log-entry :level)
        ns-str (get log-entry :ns)
        source (get log-entry :source)
        data (get log-entry :data)
        id (get log-entry :id)]
    (println (str "[" (or source "local") "] "
                  (name level) " " ns-str
                  (when id (str " " id))
                  ": " (pr-str data)))))

(defn silent-handler
  "Log handler that discards logs"
  [_log-entry]
  nil)

;; Note: sente-handler must be created with make-sente-handler
;; because it needs client-id

(defn make-sente-handler
  "Create a log handler that routes logs via sente-lite pub/sub.

   Args:
     client-id - sente-lite client-id
     opts - {:channel \"log-routing\"
             :source-id \"my-client\"}"
  [send-fn opts]
  (let [channel (get opts :channel "log-routing")
        source-id (get opts :source-id "unknown")]
    (fn [log-entry]
      (let [entry (assoc log-entry
                         :source source-id
                         :timestamp #?(:bb (System/currentTimeMillis)
                                       :cljs (js/Date.now)))]
        (send-fn channel entry)))))

;; ============================================================================
;; Registry Setup
;; ============================================================================

(defn register-impl!
  "Register a log handler implementation.

   Example:
     (register-impl! \"console\" console-handler)
     ;; Creates: telemetry.impl/console"
  [name handler-fn]
  (reg/register! (str impl-prefix name) handler-fn))

(defn init!
  "Initialize registry with built-in implementations.
   Sets console as the default handler."
  []
  ;; Register built-in implementations
  (register-impl! "console" console-handler)
  (register-impl! "silent" silent-handler)

  ;; Set default: point to console
  (reg/register! config-name "telemetry.impl/console"))

(defn init-with-sente!
  "Initialize registry with sente handler available.

   Args:
     send-fn - Function to send to channel (fn [channel data])
     opts    - {:source-id \"my-client\"}"
  [send-fn opts]
  (init!)
  (register-impl! "sente" (make-sente-handler send-fn opts)))

;; ============================================================================
;; Handler Access
;; ============================================================================

(defn get-handler
  "Get the currently configured log handler function.
   Uses resolve-ref to follow the indirection."
  []
  (reg/resolve-ref config-name))

(defn use-handler!
  "Switch to a different handler by implementation name.

   Example:
     (use-handler! \"telemetry.impl/sente\")
     (use-handler! \"telemetry.impl/console\")"
  [impl-name]
  (reg/set-value! config-name impl-name))

(defn list-implementations
  "List all registered handler implementations."
  []
  (reg/list-registered-prefix impl-prefix))

;; ============================================================================
;; Reactive Updates
;; ============================================================================

(defn on-handler-change!
  "Watch for handler changes. Callback receives resolved handlers.

   callback: (fn [old-handler new-handler] ...)"
  [key callback]
  (reg/watch-resolved! config-name key
    (fn [_k _name old-handler new-handler]
      (callback old-handler new-handler))))

(defn remove-handler-watch!
  "Remove a handler change watch."
  [key]
  (reg/unwatch! config-name key))

;; ============================================================================
;; Integration with Trove
;; ============================================================================

(defn make-trove-log-fn
  "Create a Trove-compatible log-fn that uses the registry handler.

   This wraps the registry lookup so Trove always uses the current handler.

   Usage:
     (require '[taoensso.trove :as trove])
     (trove/set-log-fn! (rh/make-trove-log-fn))"
  []
  (fn [ns-str coords level id lazy_]
    (when-let [handler (get-handler)]
      (let [data (force lazy_)
            entry {:ns ns-str
                   :coords coords
                   :level level
                   :id id
                   :data data}]
        (handler entry)))))
