(ns taoensso.trove
  "Trove logging facade for Scittle.
   
   Function-based implementation that mimics the log! macro signature.
   Can be replaced with the real macro once available in SCI.")

(defn- format-timestamp []
  (.toISOString (js/Date.)))

(defn- default-log-fn
  "Default console backend - mimics console/get-log-fn"
  [ns coords level id lazy-form]
  (let [data (if lazy-form (force lazy-form) {})
        timestamp (format-timestamp)
        msg (:msg data)
        error (:error data)
        output (str timestamp " " (name level) " " ns " " coords " " id)]
    (js/console.log output (clj->js data))))

(def ^:dynamic *log-fn*
  "The logging backend function.
   Signature: (fn [ns coords level id lazy-form])"
  default-log-fn)

(defn log!
  "Log using the configured backend.
   
   Signature matches Trove's log! macro for future compatibility.
   
   Usage:
     (log! {:level :info :id :event :data {...}})
     (log! {:level :error :id :error :msg \"Failed\" :error err})"
  [opts]
  (when-not (map? opts)
    (throw (ex-info "Trove opts must be a map" {:opts opts})))
  
  (let [{:keys [ns coords level id msg data error log-fn]
         :or {ns (str *ns*)
              level :info
              coords nil
              log-fn *log-fn*}} opts
        
        lazy-form (when (or msg data error)
                    (delay {:msg msg :data data :error error}))]
    
    (when log-fn
      (log-fn ns coords level id lazy-form))
    nil))
