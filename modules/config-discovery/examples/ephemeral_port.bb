#!/usr/bin/env bb
;; Ephemeral Port Discovery Example
;;
;; Demonstrates the Configuration Discovery pattern:
;; 1. Server starts on port 0 (OS assigns ephemeral port)
;; 2. HTML served with port in JSON config
;; 3. Client uses discovery handlers from config-discovery module
;;
;; Usage:
;;   bb modules/config-discovery/examples/ephemeral_port.bb
;;
;; Then test with:
;;   node test/scripts/registry/test_ephemeral_port_playwright.mjs

(ns ephemeral-port-example
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
;; HTML Template
;; ============================================================================

(def html-template
  "<!DOCTYPE html>
<html>
<head>
  <title>Ephemeral Port Example</title>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js\"></script>
  <style>
    body { font-family: monospace; padding: 20px; max-width: 800px; margin: 0 auto; }
    .success { color: green; }
    .error { color: red; }
    pre { background: #f5f5f5; padding: 15px; }
    #log { margin-top: 20px; }
  </style>
</head>
<body>
  <h1>Ephemeral Port Discovery Example</h1>

  <h2>Server-Rendered Config</h2>
  <pre id=\"config\">Loading...</pre>

  <h2>Connection Log</h2>
  <div id=\"log\"></div>

  <!-- Server-rendered config (the key!) -->
  <script type=\"application/json\" id=\"sente-config\">
    {{CONFIG_JSON}}
  </script>

  <!-- Load Trove -->
  <script type=\"application/x-scittle\" src=\"taoensso/trove.cljs\"></script>

  <!-- Load sente-lite client -->
  <script type=\"application/x-scittle\" src=\"../../../src/sente_lite/packer.cljc\"></script>
  <script type=\"application/x-scittle\" src=\"../../../src/sente_lite/wire_format.cljc\"></script>
  <script type=\"application/x-scittle\" src=\"../../../src/sente_lite/queue_scittle.cljs\"></script>
  <script type=\"application/x-scittle\" src=\"../../../src/sente_lite/client_scittle.cljs\"></script>

  <!-- Load registry -->
  <script type=\"application/x-scittle\" src=\"../../../src/sente_lite/registry.cljc\"></script>

  <!-- Load discovery handlers -->
  <script type=\"application/x-scittle\" src=\"../src/config_discovery/handlers.cljc\"></script>

  <!-- App code -->
  <script type=\"application/x-scittle\">
(ns ephemeral-port-app
  (:require [sente-lite.registry :as reg]
            [sente-lite.client-scittle :as client]
            [config-discovery.handlers :as discovery]))

;; UI helpers
(defn log! [msg & [class]]
  (let [el (.createElement js/document \"div\")]
    (when class (set! (.-className el) class))
    (set! (.-textContent el) msg)
    (.appendChild (.getElementById js/document \"log\") el)))

(defn show-config! []
  (let [el (.getElementById js/document \"sente-config\")
        config (when el (js/JSON.parse (.-textContent el)))]
    (set! (.-textContent (.getElementById js/document \"config\"))
          (js/JSON.stringify config nil 2))))

;; Main
(defn init! []
  (show-config!)

  (log! \"Step 1: Running discovery handlers...\")

  ;; Register defaults first
  (discovery/discover-from-hardcoded!
    {:ws-host \"localhost\"
     :ws-port 9999  ; Will be overridden
     :ws-path \"/\"})
  (log! \"  - Hardcoded defaults registered\")

  ;; Override from JSON script (server-rendered ephemeral port)
  (discovery/discover-from-json-script! {:register? false})
  (log! (str \"  - JSON config applied: port = \" (reg/get-value \"config.server/ws-port\")))

  (log! \"Step 2: Building WebSocket URL from registry...\")
  (let [url (discovery/build-ws-url)]
    (log! (str \"  - URL: \" url))

    (log! \"Step 3: Connecting...\")

    (let [cid-atom (atom nil)
          cid (client/make-client!
               {:url url
                :on-open (fn [uid]
                           (log! (str \"Connected! UID: \" uid) \"success\")
                           (client/send! @cid-atom [:test/ping {:from \"example\"}]))
                :on-message (fn [event-id data]
                              (when (= event-id :sente-lite/echo)
                                (log! (str \"Echo received: \" (pr-str data)) \"success\")
                                ;; Close after successful echo
                                (js/setTimeout
                                 #(do (client/close! @cid-atom)
                                      (log! \"Disconnected cleanly.\" \"success\")
                                      (aset js/window \"testsPassed\" true)
                                      (aset js/window \"testsComplete\" true))
                                 500)))
                :on-close (fn [_] nil)
                :auto-reconnect? false})]
      (reset! cid-atom cid))))

;; Start after page load
(js/setTimeout init! 300)
  </script>
</body>
</html>")

;; ============================================================================
;; Server
;; ============================================================================

(defonce ws-port (atom nil))

(defn on-message [client-uid event-id data]
  (println "  Received:" event-id "from" client-uid)
  (server/send-event-to-connection! client-uid
    [:sente-lite/echo {:original-event-id event-id
                       :original-data data
                       :conn-id client-uid}]))

(defn start-ws-server! []
  (server/start-server!
   {:port 0  ; Ephemeral!
    :on-message on-message
    :on-open (fn [uid] (println "  Client connected:" uid))
    :on-close (fn [uid] (println "  Client disconnected:" uid))})
  (let [port (server/get-server-port)]
    (reset! ws-port port)
    (println "WebSocket server on ephemeral port:" port)
    port))

(defn render-html [port]
  (str/replace html-template "{{CONFIG_JSON}}"
    (str "{\"wsHost\": \"localhost\", \"wsPort\": " port ", \"wsPath\": \"/\"}")))

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
          (str/ends-with? uri ".cljc")
          (str/ends-with? uri ".cljs"))
      (let [;; Normalize relative paths like ../../../src/foo.cljc
            cleaned (-> uri
                       (str/replace #"^/+" "")
                       (str/replace #"\.\./" ""))
            ;; Try multiple base paths
            paths [;; Direct from project root
                   cleaned
                   ;; From dev/scittle-demo (for trove.cljs)
                   (str "dev/scittle-demo/" cleaned)
                   ;; From modules directory
                   (str "modules/config-discovery/examples/" cleaned)
                   (str "modules/config-discovery/" cleaned)
                   ;; Original path stripped
                   (str/replace uri #"^/+" "")]]
        (if-let [file (->> paths
                          (map io/file)
                          (filter #(.exists %))
                          first)]
          {:status 200
           :headers {"Content-Type" "application/x-scittle"}
           :body (slurp file)}
          {:status 404 :body (str "Not found: " uri " (tried: " (str/join ", " paths) ")")}))

      :else
      {:status 404 :body "Not found"})))

;; ============================================================================
;; Main
;; ============================================================================

(defn -main []
  (println "\n=== Ephemeral Port Discovery Example ===\n")

  (let [ws-p (start-ws-server!)]
    (httpkit/run-server handler {:port 1350})
    (println "HTTP server on port: 1350")
    (println "")
    (println "Open: http://localhost:1350/")
    (println "")
    (println "The client will discover port" ws-p "from JSON config.")
    (println "Press Ctrl+C to stop.\n")

    @(promise)))

(-main)
