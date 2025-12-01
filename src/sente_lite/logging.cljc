(ns sente-lite.logging
  "Unified logging interface using Trove facade.

  Provides a consistent logging API across all platforms (Babashka, JVM, browser).
  Uses Trove as the logging facade (0 dependencies) with pluggable backends.

  For Babashka: Uses println for now (can be enhanced with handlers)
  For JVM/Browser: Uses Trove logging facade

  Usage:
    (require '[sente-lite.logging :as log])
    (log/log! {:level :info :id :app/started :data {:version \"1.0.0\"}})
    (log/log! {:level :error :id :app/error :data {:message \"Something failed\"}})"
  #?(:clj-jvm (:require [taoensso.trove :as trove])
     :cljs (:require [taoensso.trove :as trove])))

;;; ============================================================================
;;; Core Logging Function
;;; ============================================================================

(defn log!
  "Log a message using Trove facade (JVM/browser) or println (Babashka).

  Args:
    opts - Map with keys:
      :level - Log level keyword (:trace, :debug, :info, :warn, :error, :fatal)
      :id    - Event ID as keyword (e.g., :app/started, :ws/connected)
      :data  - Optional data map
      :msg   - Optional message string
      :error - Optional error/exception

  Returns:
    nil

  Examples:
    (log! {:level :info :id :app/started :data {:version \"1.0.0\"}})
    (log! {:level :error :id :app/error :data {:message \"Connection failed\"}})
    (log! {:level :debug :id :app/state :data {:user-id 123}})"
  [opts]
  (let [{:keys [level id data msg error]} opts]
    #?(:bb
       ;; Babashka: Simple println logging
       (println (str "[" (name (or level :info)) "] " id " "
                     (when msg (str msg " "))
                     (when data (pr-str data))
                     (when error (str " ERROR: " error))))
       :clj-jvm
       ;; JVM: Use Trove logging facade
       (trove/log! opts)
       :cljs
       ;; Browser: Use Trove logging facade
       (trove/log! opts))))
