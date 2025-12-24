#!/usr/bin/env bb
;; Ephemeral Port Server Demo
;;
;; Demonstrates the Configuration Discovery pattern with server-rendered config:
;; 1. Server starts on port 0 (OS assigns ephemeral port)
;; 2. Server serves HTML with the actual port embedded in JSON config
;; 3. Client discovers config from JSON, connects via registry
;;
;; Usage: bb dev/scittle-demo/ephemeral_server.bb

(ns ephemeral-server
  (:require [org.httpkit.server :as httpkit]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; Load trove dependencies in order
(load-file "src/taoensso/trove/utils.cljc")
(load-file "src/taoensso/trove/console.cljc")
(load-file "src/taoensso/trove/timbre.cljc")
(load-file "src/taoensso/trove.cljc")

;; Load sente-lite dependencies in order
(load-file "src/sente_lite/packer.cljc")
(load-file "src/sente_lite/serialization.cljc")
(load-file "src/sente_lite/wire_format.cljc")
(load-file "src/sente_lite/channels.cljc")
(load-file "src/sente_lite/server.cljc")

(require '[sente-lite.server :as server])

;; ============================================================================
;; HTML Template with JSON Config Placeholder
;; ============================================================================

(def html-template
  "<!DOCTYPE html>
<html>
<head>
  <title>Ephemeral Port Discovery Test</title>
  <script src=\"https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js\"></script>
  <style>
    body { font-family: monospace; padding: 20px; max-width: 800px; margin: 0 auto; }
    .pass { color: green; font-weight: bold; }
    .fail { color: red; font-weight: bold; }
    .info { color: blue; }
    pre { background: #f5f5f5; padding: 10px; }
    #results { margin-top: 10px; }
    .test-item { margin: 5px 0; padding: 8px; border-left: 3px solid #ccc; }
    .test-item.pass { border-left-color: green; }
    .test-item.fail { border-left-color: red; }
    .test-item.info { border-left-color: blue; background: #f0f8ff; }
    .summary { margin-top: 20px; padding: 15px; background: #eee; }
  </style>
</head>
<body>
  <h1>Ephemeral Port Discovery Test</h1>
  <p>Server started on ephemeral port. Client discovers it from embedded JSON config.</p>

  <!-- SERVER-RENDERED CONFIG - This is the key! -->
  <script type=\"application/json\" id=\"sente-config\">
    {{CONFIG_JSON}}
  </script>

  <h2>Config Source</h2>
  <pre id=\"config-source\">Loading...</pre>

  <h2>Test Results</h2>
  <div id=\"status\">Initializing...</div>
  <div id=\"results\"></div>

  <!-- Load Trove for logging -->
  <script type=\"application/x-scittle\" src=\"taoensso/trove.cljs\"></script>

  <!-- Load sente-lite client dependencies -->
  <script type=\"application/x-scittle\" src=\"../../src/sente_lite/packer.cljc\"></script>
  <script type=\"application/x-scittle\" src=\"../../src/sente_lite/wire_format.cljc\"></script>
  <script type=\"application/x-scittle\" src=\"../../src/sente_lite/queue_scittle.cljs\"></script>
  <script type=\"application/x-scittle\" src=\"../../src/sente_lite/client_scittle.cljs\"></script>

  <!-- Inline Registry -->
  <script type=\"application/x-scittle\">
(ns sente-lite.registry)

(defonce ^:private reg-root (atom \"sente-lite.registry\"))
(def ^:private valid-name-pattern #\"[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)*/[a-z][a-z0-9-]*\")
(defn- valid-name? [name] (and (string? name) (re-matches valid-name-pattern name)))
(defn- validate-name! [name]
  (when-not (valid-name? name)
    (throw (ex-info \"Invalid registry name format\" {:name name}))))

(defn- absolute-fqn [name]
  (let [slash-idx (.indexOf name \"/\")
        category (subs name 0 slash-idx)
        var-name (subs name (inc slash-idx))]
    (str @reg-root \".\" category \"/\" var-name)))

(defn- name->symbols [name]
  (let [fqn (absolute-fqn name)
        sym (symbol fqn)]
    [(symbol (namespace sym)) (symbol (clojure.core/name sym))]))

(defonce ^:private registered-names (atom #{}))

(defn register! [name initial-value]
  (validate-name! name)
  (let [syms (name->symbols name)
        ns-sym (first syms)
        name-sym (second syms)]
    (create-ns ns-sym)
    (let [a (atom initial-value)]
      (intern ns-sym name-sym a)
      (swap! registered-names conj name)
      a)))

(defn get-ref [name]
  (validate-name! name)
  (when-let [v (find-var (symbol (absolute-fqn name)))]
    @v))

(defn get-value [name]
  (when-let [ref (get-ref name)] @ref))

(defn set-value! [name new-value]
  (if-let [ref (get-ref name)]
    (reset! ref new-value)
    (throw (ex-info \"Registry name not found\" {:name name}))))

(defn registered? [name]
  (validate-name! name)
  (contains? @registered-names name))

(defn list-registered-prefix [prefix]
  (into #{} (filter #(.startsWith % prefix) @registered-names)))
  </script>

  <!-- Test Script -->
  <script type=\"application/x-scittle\">
(ns test-ephemeral-port
  (:require [sente-lite.registry :as reg]
            [sente-lite.client-scittle :as client]))

;; ============================================================================
;; UI Helpers
;; ============================================================================

(def test-results (atom []))

(defn set-status! [msg]
  (set! (.-innerHTML (.getElementById js/document \"status\"))
        (str \"<strong>Status:</strong> \" msg)))

(defn log-result! [name pass? detail & [info?]]
  (swap! test-results conj {:name name :pass pass? :detail detail})
  (let [el (.createElement js/document \"div\")
        class (cond info? \"info\" pass? \"pass\" :else \"fail\")
        icon (cond info? \"→\" pass? \"✓\" :else \"✗\")]
    (set! (.-className el) (str \"test-item \" class))
    (set! (.-innerHTML el) (str \"<span class='\" class \"'>\" icon \"</span> \" name \": \" detail))
    (.appendChild (.getElementById js/document \"results\") el)))

(defn show-summary! []
  (let [results @test-results
        passed (count (filter :pass results))
        failed (count (filter #(not (:pass %)) results))
        el (.createElement js/document \"div\")]
    (set! (.-className el) \"summary\")
    (set! (.-innerHTML el)
          (str \"<h3>Summary</h3>\"
               \"<p>Total: \" (count results) \" | \"
               \"<span class='pass'>Passed: \" passed \"</span> | \"
               \"<span class='fail'>Failed: \" failed \"</span></p>\"
               (if (zero? failed)
                 \"<p class='pass'>✓ EPHEMERAL PORT DISCOVERY WORKS!</p>\"
                 \"<p class='fail'>✗ SOME TESTS FAILED</p>\")))
    (.appendChild (.getElementById js/document \"results\") el)
    (aset js/window \"testsPassed\" (zero? failed))
    (aset js/window \"testsComplete\" true)))

;; ============================================================================
;; NEW DISCOVERY HANDLER: JSON Script Tag
;; ============================================================================

(defn discover-from-json-script!
  \"Discovery handler: Read config from <script type='application/json' id='sente-config'>\"
  []
  (log-result! \"Discovery: JSON Script\" true
               \"Reading from #sente-config script tag...\" true)
  (when-let [script-el (.getElementById js/document \"sente-config\")]
    (let [json-text (.-textContent script-el)
          config (js->clj (js/JSON.parse json-text) :keywordize-keys true)]
      ;; Show what we found
      (set! (.-textContent (.getElementById js/document \"config-source\"))
            (str \"Found: \" (pr-str config)))

      ;; Register each config value
      (when-let [host (:wsHost config)]
        (reg/register! \"config.server/ws-host\" host)
        (log-result! \"Discovery: ws-host\" true (str \"Registered: \\\"\" host \"\\\"\")))

      (when-let [port (:wsPort config)]
        (reg/register! \"config.server/ws-port\" port)
        (log-result! \"Discovery: ws-port\" true (str \"Registered: \" port \" (ephemeral!)\")))

      (when-let [path (:wsPath config)]
        (reg/register! \"config.server/ws-path\" path)
        (log-result! \"Discovery: ws-path\" true (str \"Registered: \\\"\" path \"\\\"\"))))))

;; ============================================================================
;; APP CODE (unchanged from other demos - only reads from registry)
;; ============================================================================

(def client-atom (atom nil))

(defn build-ws-url []
  (let [host (reg/get-value \"config.server/ws-host\")
        port (reg/get-value \"config.server/ws-port\")
        path (or (reg/get-value \"config.server/ws-path\") \"/\")]
    (str \"ws://\" host \":\" port path)))

(defn connect! []
  (let [url (build-ws-url)]
    (log-result! \"App: Building URL\" true (str \"From registry: \" url))

    ;; Use atom for client-id so callbacks can reference it
    (let [cid-atom (atom nil)
          cid (client/make-client!
               {:url url
                :on-open (fn [uid]
                           (log-result! \"Connection Established\" true
                                        (str \"Connected with UID: \" uid))
                           ;; Send test message (use atom for cid)
                           (client/send! @cid-atom [:test/ping {:from \"ephemeral-port-test\"}])
                           ;; Close after brief test
                           (js/setTimeout
                            (fn []
                              (client/close! @cid-atom)
                              (log-result! \"Connection Closed\" true \"Clean disconnect\")
                              (set-status! \"Test complete!\")
                              (show-summary!))
                            1000))
                :on-message (fn [event-id data]
                              (when (= event-id :sente-lite/echo)
                                (log-result! \"Echo Received\" true
                                             (str \"Server echoed: \" (pr-str data)))))
                :on-close (fn [_] nil)
                :auto-reconnect? false})]
      (reset! cid-atom cid)
      (reset! client-atom cid))))

;; ============================================================================
;; MAIN
;; ============================================================================

(defn run-test! []
  (set-status! \"Running Ephemeral Port Discovery...\")

  (log-result! \"=== PHASE 1: Discovery ===\" true
               \"Reading server-rendered JSON config\" true)

  ;; Discover from JSON script tag (the new handler!)
  (discover-from-json-script!)

  (log-result! \"=== PHASE 2: Verify ===\" true
               \"Check registry has ephemeral port\" true)

  (let [port (reg/get-value \"config.server/ws-port\")]
    (log-result! \"Registry: ws-port\" (number? port)
                 (str \"Got: \" port \" (assigned by OS)\"))
    (log-result! \"Registry: not hardcoded\" (not= port 8080)
                 (str port \" ≠ 8080 (proves it's ephemeral)\")))

  (log-result! \"=== PHASE 3: Connect ===\" true
               \"App connects using discovered port\" true)

  (connect!))

;; Start after page load
(js/setTimeout run-test! 300)
  </script>
</body>
</html>")

;; ============================================================================
;; Server
;; ============================================================================

(defonce ws-port (atom nil))
(defonce http-port (atom nil))

(defn on-message [client-uid event-id data]
  (println "  Received from" client-uid ":" event-id data)
  ;; Echo back
  (server/send-event-to-connection! client-uid [:sente-lite/echo {:event event-id :data data}]))

(defn start-ws-server! []
  ;; Start on ephemeral port (port 0)
  (server/start-server!
   {:port 0
    :on-message on-message
    :on-open (fn [uid] (println "  Client connected:" uid))
    :on-close (fn [uid] (println "  Client disconnected:" uid))})
  ;; Get the actual port assigned by OS
  (let [actual-port (server/get-server-port)]
    (reset! ws-port actual-port)
    (println "WebSocket server started on ephemeral port:" actual-port)
    actual-port))

(defn render-html [ws-port-num]
  (let [config {:wsHost "localhost"
                :wsPort ws-port-num
                :wsPath "/"}
        json (pr-str config)]  ;; Use EDN-style for readability, JSON.parse handles it
    (str/replace html-template "{{CONFIG_JSON}}"
                 (str "{\"wsHost\": \"localhost\", \"wsPort\": " ws-port-num ", \"wsPath\": \"/\"}"))))

(defn handler [req]
  (let [uri (:uri req)]
    (cond
      ;; Serve the main HTML with config injected
      (= uri "/")
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (render-html @ws-port)}

      ;; Serve static files from dev/scittle-demo and src
      (str/starts-with? uri "/taoensso/")
      (let [file (io/file (str "dev/scittle-demo" uri))]
        (if (.exists file)
          {:status 200
           :headers {"Content-Type" "application/x-scittle"}
           :body (slurp file)}
          {:status 404 :body "Not found"}))

      (str/starts-with? uri "/src/")
      (let [file (io/file (subs uri 1))]
        (if (.exists file)
          {:status 200
           :headers {"Content-Type" "application/x-scittle"}
           :body (slurp file)}
          {:status 404 :body "Not found"}))

      ;; Redirect /src paths that come from ../../src
      (str/ends-with? uri ".cljc")
      (let [;; Handle ../../src/sente_lite/foo.cljc -> src/sente_lite/foo.cljc
            normalized (str/replace uri #"^/+\.\./+" "")
            file (io/file normalized)]
        (if (.exists file)
          {:status 200
           :headers {"Content-Type" "application/x-scittle"}
           :body (slurp file)}
          {:status 404 :body (str "Not found: " normalized)}))

      :else
      {:status 404 :body "Not found"})))

(defn start-http-server! [port]
  (reset! http-port port)
  (httpkit/run-server handler {:port port})
  (println "HTTP server started on port:" port))

;; ============================================================================
;; Main
;; ============================================================================

(defn -main []
  (println "\n=== Ephemeral Port Discovery Demo ===\n")

  ;; Start WebSocket on ephemeral port
  (let [ws-p (start-ws-server!)]
    (println "")

    ;; Start HTTP server on fixed port to serve HTML
    (start-http-server! 1350)

    (println "")
    (println "Open in browser: http://localhost:1350/")
    (println "")
    (println "The HTML page will discover the WebSocket port (" ws-p ") from embedded JSON.")
    (println "Press Ctrl+C to stop.\n")

    ;; Keep running
    @(promise)))

(-main)
