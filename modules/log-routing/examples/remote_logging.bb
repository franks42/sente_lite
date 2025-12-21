#!/usr/bin/env bb
;; Remote Logging Example - Module Reuse Demo
;;
;; Demonstrates composing modules:
;; 1. config-discovery: Ephemeral port discovery via JSON script tag
;; 2. log-routing: Configurable log handlers via registry indirection
;;
;; Usage:
;;   bb modules/log-routing/examples/remote_logging.bb
;;
;; Then test with:
;;   node modules/log-routing/examples/test_remote_logging.mjs

(ns remote-logging-example
  (:require [org.httpkit.server :as httpkit]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; Load dependencies
(load-file "src/taoensso/trove/utils.cljc")
(load-file "src/taoensso/trove/console.cljc")
(load-file "src/taoensso/trove/timbre.cljc")
(load-file "src/taoensso/trove.cljc")
(load-file "src/sente_lite/packer.cljc")
(load-file "src/sente_lite/serialization.cljc")
(load-file "src/sente_lite/wire_format.cljc")
(load-file "src/sente_lite/channels.cljc")
(load-file "src/sente_lite/server.cljc")

(require '[sente-lite.server :as server])

;; ============================================================================
;; HTML Template - Browser reuses both modules
;; ============================================================================

(def html-template
  "<!DOCTYPE html>
<html>
<head>
  <title>Remote Logging Demo</title>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.6.19/dist/scittle.js\"></script>
  <style>
    body { font-family: monospace; padding: 20px; max-width: 900px; margin: 0 auto; }
    .success { color: green; }
    .error { color: red; }
    .info { color: blue; }
    pre { background: #f5f5f5; padding: 15px; border-radius: 4px; }
    h2 { margin-top: 30px; border-bottom: 1px solid #ccc; padding-bottom: 5px; }
    button { margin: 5px; padding: 8px 16px; }
    #log div { margin: 3px 0; }
    .handler-badge {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 4px;
      font-size: 12px;
      margin-left: 10px;
    }
    .console-active { background: #d4edda; }
    .sente-active { background: #cce5ff; }
    .silent-active { background: #f8d7da; }
  </style>
</head>
<body>
  <h1>Remote Logging Demo</h1>
  <p>Demonstrates module reuse: <code>config-discovery</code> + <code>log-routing</code></p>

  <h2>1. Config Discovery (from config-discovery module)</h2>
  <pre id=\"config\">Loading...</pre>

  <h2>2. Handler Switching (from log-routing module)</h2>
  <p>Current handler: <span id=\"current-handler\">loading...</span></p>
  <button onclick=\"switchHandler('console')\">Use Console</button>
  <button onclick=\"switchHandler('sente')\">Use Sente (Remote)</button>
  <button onclick=\"switchHandler('silent')\">Use Silent</button>

  <h2>3. Log Messages</h2>
  <button onclick=\"sendLog('info', 'Hello from browser!')\">Log Info</button>
  <button onclick=\"sendLog('warn', 'This is a warning')\">Log Warn</button>
  <button onclick=\"sendLog('error', 'Something went wrong')\">Log Error</button>

  <h2>4. Activity Log</h2>
  <div id=\"log\"></div>

  <!-- Server-rendered config (ephemeral port!) -->
  <script type=\"application/json\" id=\"sente-config\">
    {{CONFIG_JSON}}
  </script>

  <!-- Load Trove for structured logging -->
  <script type=\"application/x-scittle\" src=\"/taoensso/trove.cljs\"></script>

  <!-- Load sente-lite client -->
  <script type=\"application/x-scittle\" src=\"/src/sente_lite/packer.cljc\"></script>
  <script type=\"application/x-scittle\" src=\"/src/sente_lite/wire_format.cljc\"></script>
  <script type=\"application/x-scittle\" src=\"/src/sente_lite/queue_scittle.cljs\"></script>
  <script type=\"application/x-scittle\" src=\"/src/sente_lite/client_scittle.cljs\"></script>

  <!-- Load registry (shared infrastructure) -->
  <script type=\"application/x-scittle\" src=\"/src/sente_lite/registry.cljc\"></script>

  <!-- MODULE REUSE: Load config-discovery handlers -->
  <script type=\"application/x-scittle\" src=\"/modules/config-discovery/src/config_discovery/handlers.cljc\"></script>

  <!-- MODULE REUSE: Load log-routing registry handlers -->
  <script type=\"application/x-scittle\" src=\"/modules/log-routing/src/log_routing/registry_handlers.cljc\"></script>

  <!-- App code -->
  <script type=\"application/x-scittle\">
(ns remote-logging-app
  (:require [sente-lite.registry :as reg]
            [sente-lite.client-scittle :as client]
            [config-discovery.handlers :as discovery]
            [log-routing.registry-handlers :as rh]))

;; State
(def client-id (atom nil))
(def test-results (atom {:sent 0 :received 0}))

;; Export results to JS for testing
(defn export-results! []
  (let [r @test-results]
    (aset js/window \"testResultsSent\" (:sent r))
    (aset js/window \"testResultsReceived\" (:received r))))

;; UI helpers
(defn log! [msg & [class]]
  (let [el (.createElement js/document \"div\")]
    (when class (set! (.-className el) class))
    (set! (.-textContent el) (str \"[\" (.toLocaleTimeString (js/Date.)) \"] \" msg))
    (.appendChild (.getElementById js/document \"log\") el)))

(defn update-handler-display! []
  (let [current (reg/get-value \"telemetry/log-handler\")
        short-name (last (clojure.string/split current #\"/\"))
        badge-class (case short-name
                      \"console\" \"console-active\"
                      \"sente\" \"sente-active\"
                      \"silent\" \"silent-active\"
                      \"\")]
    (set! (.-innerHTML (.getElementById js/document \"current-handler\"))
          (str \"<span class='handler-badge \" badge-class \"'>\" short-name \"</span>\"))))

(defn show-config! []
  (set! (.-textContent (.getElementById js/document \"config\"))
        (str \"wsPort: \" (reg/get-value \"config.server/ws-port\") \"\\n\"
             \"wsHost: \" (reg/get-value \"config.server/ws-host\") \"\\n\"
             \"wsPath: \" (reg/get-value \"config.server/ws-path\"))))

;; Global functions for buttons
(aset js/window \"switchHandler\"
  (fn [handler-name]
    (rh/use-handler! (str \"telemetry.impl/\" handler-name))
    (update-handler-display!)
    (log! (str \"Switched to: \" handler-name) \"info\")))

(aset js/window \"sendLog\"
  (fn [level msg]
    (let [handler (rh/get-handler)
          entry {:level (keyword level)
                 :ns \"remote-logging-app\"
                 :data {:msg msg}
                 :timestamp (js/Date.now)}]
      (handler entry)
      (swap! test-results update :sent inc)
      (export-results!)
      (log! (str \"Sent log (\" level \"): \" msg)))))

;; Set up log handlers after connection (MUST be defined before init!)
(defn setup-handlers! [cid]
  (log! \"\")
  (log! \"Step 3: Initializing Log Handlers (log-routing module)\" \"info\")

  ;; Initialize with sente support (reusing log-routing module!)
  ;; Note: For this demo, we send direct events instead of publish!
  ;; because the server echoes unknown events, giving us an acknowledgment
  (rh/init-with-sente!
    (fn [channel data]
      ;; Use send! with custom event - server will echo it back
      (client/send! cid [:log/entry {:channel channel :log data}]))
    {:source-id \"browser-demo\"})

  (log! \"  - Console handler: logs to browser console\")
  (log! \"  - Sente handler: routes logs to server\")
  (log! \"  - Silent handler: discards logs\")

  ;; Watch for handler changes
  (rh/on-handler-change! :demo-watch
    (fn [old-h new-h]
      (log! \"  Handler changed!\" \"info\")))

  (update-handler-display!)
  (log! \"\")
  (log! \"Ready! Try the buttons above.\" \"success\")

  ;; Mark test globals
  (aset js/window \"testReady\" true)
  (aset js/window \"testResults\" test-results))

;; Init
(defn init! []
  (log! \"=== Remote Logging Demo ===\")
  (log! \"\")

  ;; Step 1: Config discovery (reusing config-discovery module!)
  (log! \"Step 1: Config Discovery\" \"info\")
  (discovery/discover-from-hardcoded!
    {:ws-host \"localhost\" :ws-port 9999 :ws-path \"/\"})
  (log! \"  - Hardcoded defaults set\")

  (discovery/discover-from-json-script! {:register? false})
  (log! (str \"  - Ephemeral port discovered: \" (reg/get-value \"config.server/ws-port\")))
  (show-config!)

  ;; Step 2: Connect to server
  (log! \"\")
  (log! \"Step 2: Connecting to WebSocket\" \"info\")
  (let [url (discovery/build-ws-url)]
    (log! (str \"  - URL: \" url))
    (let [cid-atom (atom nil)
          cid (client/make-client!
               {:url url
                :on-open (fn [uid]
                           (log! (str \"  - Connected as: \" uid) \"success\")
                           (setup-handlers! @cid-atom))
                :on-message (fn [event-id data]
                              ;; Server echoes unknown events back as :sente-lite/echo
                              (when (= event-id :sente-lite/echo)
                                (when (= (:original-event-id data) :log/entry)
                                  (swap! test-results update :received inc)
                                  (export-results!)
                                  (log! \"Server ACK: log received\" \"success\"))))
                :on-close (fn [_] (log! \"Disconnected\" \"error\"))
                :auto-reconnect? false})]
      (reset! cid-atom cid)
      (reset! client-id cid))))

;; Start
(js/setTimeout init! 300)
  </script>
</body>
</html>")

;; ============================================================================
;; Server - Receives routed logs
;; ============================================================================

(defonce ws-port (atom nil))

;; Note: sente-lite server echoes unknown events back as :sente-lite/echo.
;; The server doesn't support user message callbacks yet.
;; For this demo, the browser uses the echo as acknowledgment.

(defn start-ws-server! []
  (server/start-server!
   {:port 0})
  (let [port (server/get-server-port)]
    (reset! ws-port port)
    (println "WebSocket server on ephemeral port:" port)
    port))

;; ============================================================================
;; HTTP Server - Serves HTML and module files
;; ============================================================================

(defn render-html [port]
  (str/replace html-template "{{CONFIG_JSON}}"
               (str "{\"wsHost\": \"localhost\", \"wsPort\": " port ", \"wsPath\": \"/\"}")))

(defn serve-file [uri]
  (let [;; Clean up path
        cleaned (-> uri
                    (str/replace #"^/+" "")
                    (str/replace #"\.\./" ""))
        ;; Try multiple base paths
        paths [cleaned
               (str "dev/scittle-demo/" cleaned)
               (str/replace uri #"^/+" "")]]
    (if-let [file (->> paths
                       (map io/file)
                       (filter #(.exists %))
                       first)]
      {:status 200
       :headers {"Content-Type" "application/x-scittle"}
       :body (slurp file)}
      {:status 404 :body (str "Not found: " uri)})))

(defn handler [req]
  (let [uri (:uri req)]
    (cond
      (= uri "/")
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (render-html @ws-port)}

      ;; Serve source files
      (or (str/starts-with? uri "/taoensso/")
          (str/starts-with? uri "/src/")
          (str/starts-with? uri "/modules/"))
      (serve-file uri)

      ;; API endpoint (placeholder)
      (= uri "/api/status")
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body "{\"status\": \"ok\"}"}

      :else
      {:status 404 :body "Not found"})))

;; ============================================================================
;; Main
;; ============================================================================

(defn -main []
  (println "\n=== Remote Logging Demo (Module Reuse) ===\n")
  (println "Modules used:")
  (println "  - config-discovery: Ephemeral port via JSON script tag")
  (println "  - log-routing: Configurable handlers via registry indirection")
  (println "")

  (let [ws-p (start-ws-server!)]
    (httpkit/run-server handler {:port 1351})
    (println "HTTP server on port: 1351")
    (println "")
    (println "Open: http://localhost:1351/")
    (println "")
    (println "The client discovers port" ws-p "and routes logs to this server.")
    (println "Try switching handlers and sending logs!")
    (println "Press Ctrl+C to stop.\n")

    @(promise)))

(-main)
