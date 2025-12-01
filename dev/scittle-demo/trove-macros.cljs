;; trove-macros.cljs
;; Wrapper to expose Trove logging for use in Scittle

(ns trove.macros
  (:require [taoensso.trove.console :as console]))

;; Instead of trying to wrap the macro, provide direct API access
;; This is more reliable in Scittle's SCI context

(def ^:private log-fn (console/get-log-fn))

(defn log!
  "Direct logging function that works in Scittle
   Usage: (log! :info :event-id {:data value})"
  ([level id]
   (log! level id nil))
  ([level id data]
   (log-fn (str *ns*) nil level id (delay {:data data}))))

(println "✅ trove.macros namespace loaded with log! function!")
