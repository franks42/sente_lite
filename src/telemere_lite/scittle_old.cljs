(ns telemere-lite.scittle
  "Lightweight telemetry for Scittle/browser - logs to console with structured JSON")

(def ^:dynamic *telemetry-enabled* true)

(defn now
  "Current timestamp in ISO format"
  []
  (.toISOString (js/Date.)))

(defn ->json
  "Convert data to JSON string for browser output"
  [data]
  (js/JSON.stringify (clj->js data)))

(defn- log-with-location!
  "Internal logging function"
  [level msg context file line ns-str]
  (when *telemetry-enabled*
    (let [location {:file file :line line :ns ns-str}
          signal {:timestamp (now)
                  :level level
                  :ns ns-str
                  :msg msg
                  :context context
                  :location location}]
      (js/console.log (str "[" level "] " msg " " (->json signal))))))

(defmacro log!
  "Logging macro - outputs structured JSON to browser console

  Usage:
    (log! :info \"message\")
    (log! :info \"message\" {:key \"value\"})"
  ([level]
   `(log-with-location! ~level "" {}
                        ~(str #_{:clj-kondo/ignore [:unresolved-symbol]} *file*)
                        ~(:line (meta &form))
                        ~(str *ns*)))
  ([level msg]
   `(log-with-location! ~level ~msg {}
                        ~(str #_{:clj-kondo/ignore [:unresolved-symbol]} *file*)
                        ~(:line (meta &form))
                        ~(str *ns*)))
  ([level msg data]
   `(log-with-location! ~level ~msg ~data
                        ~(str #_{:clj-kondo/ignore [:unresolved-symbol]} *file*)
                        ~(:line (meta &form))
                        ~(str *ns*))))

(defn startup!
  "Log browser startup with system info"
  []
  (log-with-location! :info "Scittle telemetry initialized"
                      {:user-agent (.-userAgent js/navigator)
                       :location (.-href js/location)}
                      "telemere-lite/scittle.cljs" 0 "telemere-lite.scittle"))

(defn debug! [msg & [data]] (log-with-location! :debug msg (or data {}) "inline" 0 "user"))
(defn info! [msg & [data]] (log-with-location! :info msg (or data {}) "inline" 0 "user"))
(defn warn! [msg & [data]] (log-with-location! :warn msg (or data {}) "inline" 0 "user"))
(defn error! [msg & [data]] (log-with-location! :error msg (or data {}) "inline" 0 "user"))
