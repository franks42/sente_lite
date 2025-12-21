(ns config-discovery.handlers
  "Reusable discovery handlers for the Configuration Discovery pattern.

   Each handler populates the registry from a specific source.
   Chain handlers in order of priority (later overrides earlier):

     (discover-from-hardcoded! defaults)
     (discover-from-html!)           ; Browser only
     (discover-from-json-script!)    ; Browser only - for ephemeral ports

   All handlers use the naming convention:
     config.server/ws-host
     config.server/ws-port
     config.server/ws-path
     config.server/api-base
     config.env/mode

   Requires: sente-lite.registry as reg"
  #?(:cljs (:require [sente-lite.registry :as reg])))

;; ============================================================================
;; Hardcoded Defaults (All Runtimes)
;; ============================================================================

(defn discover-from-hardcoded!
  "Register hardcoded default values. Use as fallback.

   Example:
     (discover-from-hardcoded! {:ws-host \"localhost\"
                                :ws-port 8080
                                :ws-path \"/\"
                                :api-base \"/api/v1\"})"
  [defaults]
  #?(:cljs
     (do
       (when-let [v (:ws-host defaults)]
         (reg/register! "config.server/ws-host" v))
       (when-let [v (:ws-port defaults)]
         (reg/register! "config.server/ws-port" v))
       (when-let [v (:ws-path defaults)]
         (reg/register! "config.server/ws-path" v))
       (when-let [v (:api-base defaults)]
         (reg/register! "config.server/api-base" v))
       (when-let [v (:env-mode defaults)]
         (reg/register! "config.env/mode" v)))))

;; ============================================================================
;; HTML Data Attributes (Browser Only)
;; ============================================================================

#?(:cljs
   (defn discover-from-html!
     "Discover config from HTML <body> data attributes.
      Overrides existing registry values if present.

      HTML example:
        <body data-ws-host=\"localhost\"
              data-ws-port=\"8080\"
              data-ws-path=\"/ws\"
              data-api-base=\"/api/v1\">"
     []
     (let [body js/document.body
           ds (.-dataset body)]
       (when-let [v (.-wsHost ds)]
         (reg/set-value! "config.server/ws-host" v))
       (when-let [v (.-wsPort ds)]
         (reg/set-value! "config.server/ws-port" (js/parseInt v)))
       (when-let [v (.-wsPath ds)]
         (reg/set-value! "config.server/ws-path" v))
       (when-let [v (.-apiBase ds)]
         (reg/set-value! "config.server/api-base" v)))))

;; ============================================================================
;; JSON Script Tag (Browser Only - Recommended for Ephemeral Ports)
;; ============================================================================

#?(:cljs
   (defn discover-from-json-script!
     "Discover config from a JSON script tag (server-rendered).
      Perfect for ephemeral ports where server knows the actual port.

      HTML example (server renders the actual port):
        <script type=\"application/json\" id=\"sente-config\">
          {\"wsHost\": \"localhost\", \"wsPort\": 51234, \"wsPath\": \"/\"}
        </script>

      Options:
        :element-id - Script tag ID (default: \"sente-config\")
        :register?  - If true, registers new values; if false, updates existing
                      (default: true)"
     ([] (discover-from-json-script! {}))
     ([opts]
      (let [element-id (or (:element-id opts) "sente-config")
            register? (if (contains? opts :register?) (:register? opts) true)
            set-fn (if register? reg/register! reg/set-value!)]
        (when-let [el (.getElementById js/document element-id)]
          (let [config (js->clj (js/JSON.parse (.-textContent el))
                                :keywordize-keys true)]
            (when-let [v (:wsHost config)]
              (set-fn "config.server/ws-host" v))
            (when-let [v (:wsPort config)]
              (set-fn "config.server/ws-port" v))
            (when-let [v (:wsPath config)]
              (set-fn "config.server/ws-path" v))
            (when-let [v (:apiBase config)]
              (set-fn "config.server/api-base" v))))))))

;; ============================================================================
;; URL Builder (All Runtimes)
;; ============================================================================

(defn build-ws-url
  "Build WebSocket URL from registry values.
   Returns nil if ws-port is not set.

   Example:
     (build-ws-url) => \"ws://localhost:8080/\""
  []
  #?(:cljs
     (let [host (or (reg/get-value "config.server/ws-host") "localhost")
           port (reg/get-value "config.server/ws-port")
           path (or (reg/get-value "config.server/ws-path") "/")]
       (when port
         (str "ws://" host ":" port path)))))

(defn build-api-url
  "Build API URL from registry values.

   Example:
     (build-api-url \"/users\") => \"/api/v1/users\""
  [endpoint]
  #?(:cljs
     (let [base (or (reg/get-value "config.server/api-base") "")]
       (str base endpoint))))
