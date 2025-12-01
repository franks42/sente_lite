(ns sente-lite.logging
  "Unified logging interface using Trove facade.

  Provides a consistent logging API across all platforms (Babashka, JVM, browser).
  Uses Trove as the logging facade (0 dependencies) with pluggable backends.

  For Babashka: Uses println for now (can be enhanced with handlers)
  For JVM/Browser: Uses Trove logging facade

  Usage:
    (require '[sente-lite.logging :as log])
    (log/info :app/started {:version \"1.0.0\"})
    (log/error :app/error {:message \"Something failed\"})"
  #?(:clj-jvm (:require [taoensso.trove :as trove])
     :cljs (:require [taoensso.trove :as trove])))

;;; ============================================================================
;;; Core Logging Function
;;; ============================================================================

(defn log!
  "Log a message using Trove facade (JVM/browser) or println (Babashka).

  Args:
    level - Log level keyword (:trace, :debug, :info, :warn, :error, :fatal)
    id    - Event ID as keyword (e.g., :app/started, :ws/connected)
    data  - Optional data map or string message

  Returns:
    nil

  Examples:
    (log! :info :app/started {:version \"1.0.0\"})
    (log! :error :app/error \"Connection failed\")
    (log! :debug :app/state {:user-id 123})"
  [level id data]
  #?(:bb
     ;; Babashka: Simple println logging
     (println (str "[" (name level) "] " id " " (pr-str data)))
     :clj-jvm
     ;; JVM: Use Trove logging facade
     (trove/log!
      (merge {:level level :id id}
             (when (map? data) data)
             (when (string? data) {:msg data})))
     :cljs
     ;; Browser: Use Trove logging facade
     (trove/log!
      (merge {:level level :id id}
             (when (map? data) data)
             (when (string? data) {:msg data})))))

