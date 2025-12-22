#!/usr/bin/env bb

;;; Test for sente-lite nREPL Browser Adapter
;;;
;;; This test:
;;; 1. Starts an HTTP server to serve files
;;; 2. Starts a sente-lite server
;;; 3. Waits for browser to connect (via Playwright)
;;; 4. Sends nREPL eval requests
;;; 5. Verifies responses
;;;
;;; Run: bb modules/nrepl/test/test_browser_adapter.bb

(require '[babashka.process :as process]
         '[org.httpkit.server :as http]
         '[clojure.java.io :as io])

;; Load sente-lite server
(load-file "src/sente_lite/server.cljc")
(require '[sente-lite.server :as server])

(def ws-port 18765)
(def http-port 18766)
(def test-results (atom {:sent [] :received []}))
(def !browser-connected (atom false))

(defn log [& args]
  (apply println "[test]" args))

;;; --- Simple HTTP server for serving files ---

(defn content-type [path]
  (cond
    (clojure.string/ends-with? path ".html") "text/html; charset=utf-8"
    (clojure.string/ends-with? path ".js") "application/javascript; charset=utf-8"
    (clojure.string/ends-with? path ".cljs") "text/plain; charset=utf-8"
    (clojure.string/ends-with? path ".cljc") "text/plain; charset=utf-8"
    :else "text/plain; charset=utf-8"))

(defn file-handler [req]
  (let [uri (:uri req)
        ;; Map URIs to file paths
        file-path (cond
                    (= uri "/") "modules/nrepl/test/test_browser_adapter.html"
                    (clojure.string/starts-with? uri "/src/") (subs uri 1)
                    (clojure.string/starts-with? uri "/modules/") (subs uri 1)
                    :else nil)]
    (if (and file-path (.exists (io/file file-path)))
      {:status 200
       :headers {"Content-Type" (content-type file-path)
                 "Access-Control-Allow-Origin" "*"}
       :body (slurp file-path)}
      {:status 404 :body "Not found"})))

(defonce !http-server (atom nil))

(defn start-http-server! []
  (log "Starting HTTP server on port" http-port)
  (reset! !http-server (http/run-server file-handler {:port http-port})))

(defn stop-http-server! []
  (when-let [stop-fn @!http-server]
    (stop-fn)
    (reset! !http-server nil)))

;;; --- nREPL message handling ---

(defn make-nrepl-request [op code id]
  (str "{:op " op " :code " (pr-str code) " :id " (pr-str id) " :session \"test-session\"}"))

(defn handle-nrepl-response [conn-id data]
  (log "nREPL response from" conn-id ":" data)
  (swap! test-results update :received conj data))

(defn send-nrepl-eval! [code id]
  (let [msg (make-nrepl-request :eval code id)]
    (log "Sending eval request:" msg)
    (swap! test-results update :sent conj {:code code :id id})
    ;; Send to all connected clients - get-connections returns maps with :conn-id
    (doseq [conn (server/get-connections)]
      (let [conn-id (:conn-id conn)]
        (log "Sending to" conn-id)
        (server/send-event-to-connection! conn-id [:nrepl/request {:edn msg}])))))

;;; --- Server setup ---

(defn on-message [conn-id event-id data]
  (log "Message from" conn-id ":" event-id data)
  (case event-id
    :nrepl/response
    (handle-nrepl-response conn-id (:edn data))

    :sente-lite/connected
    (do
      (log "Browser connected:" conn-id)
      (reset! !browser-connected true))

    :sente-lite/disconnected
    (log "Browser disconnected:" conn-id)

    ;; Default
    (log "Unknown event:" event-id)))

(defn start-ws-server! []
  (log "Starting sente-lite server on port" ws-port)
  (server/start-server!
   {:port ws-port
    :on-message on-message}))

;;; --- Test execution ---

(defn wait-for-browser [timeout-ms]
  (log "Waiting for browser to connect...")
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [connections (server/get-connections)]
        (cond
          (seq connections)
          (do (log "Browser connected! Connections:" connections)
              (reset! !browser-connected true)
              true)

          (> (- (System/currentTimeMillis) start) timeout-ms)
          (do (log "Timeout waiting for browser") false)

          :else
          (do (Thread/sleep 100) (recur)))))))

(defn wait-for-responses [expected-count timeout-ms]
  (log "Waiting for" expected-count "responses...")
  (let [start (System/currentTimeMillis)]
    (loop []
      (let [received-count (count (:received @test-results))]
        (cond
          (>= received-count expected-count)
          (do (log "Received" received-count "responses") true)

          (> (- (System/currentTimeMillis) start) timeout-ms)
          (do (log "Timeout after" received-count "responses") false)

          :else
          (do (Thread/sleep 100) (recur)))))))

(defn run-eval-tests! []
  (log "=== Running eval tests ===")

  ;; Test 1: Simple arithmetic
  (send-nrepl-eval! "(+ 1 2 3)" "test-1")
  (Thread/sleep 300)

  ;; Test 2: Define and use
  (send-nrepl-eval! "(def x 42)" "test-2")
  (Thread/sleep 300)

  (send-nrepl-eval! "(* x 2)" "test-3")
  (Thread/sleep 300)

  ;; Test 4: Multi-form
  (send-nrepl-eval! "(do (def y 10) (+ x y))" "test-4")
  (Thread/sleep 300))

(defn verify-results []
  (log "=== Verifying results ===")
  (let [{:keys [sent received]} @test-results
        ;; Each eval should produce 2 responses: value + done
        expected-responses (* 2 (count sent))
        actual-responses (count received)]

    (log "Sent" (count sent) "requests")
    (log "Expected" expected-responses "responses (2 per request)")
    (log "Received" actual-responses "responses")

    ;; Check for expected values
    (let [values (filter #(re-find #":value" %) received)]
      (log "Value responses:" (count values))
      (doseq [v values]
        (log "  " v)))

    ;; Check for errors
    (let [errors (filter #(re-find #":err" %) received)]
      (when (seq errors)
        (log "ERRORS found:")
        (doseq [e errors]
          (log "  " e))))

    ;; Final verdict
    (if (>= actual-responses expected-responses)
      (do (log "✅ PASS: All responses received")
          true)
      (do (log "❌ FAIL: Missing responses")
          false))))

(defn -main []
  (log "=== sente-lite nREPL Browser Adapter Test ===")

  (start-http-server!)
  (start-ws-server!)

  (try
    ;; Start Playwright in background
    (log "Starting Playwright browser...")
    (let [url (str "http://localhost:" http-port "/?port=" ws-port)
          playwright-code (str "
const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  page.on('console', msg => console.log('BROWSER:', msg.text()));
  page.on('pageerror', err => console.log('PAGE ERROR:', err.message));
  console.log('Navigating to " url "');
  await page.goto('" url "');
  // Wait for adapter to be ready
  await page.waitForFunction(() => window.ADAPTER_READY === true, { timeout: 15000 });
  console.log('Browser ready!');
  // Keep alive for test
  await page.waitForTimeout(8000);
  await browser.close();
})();
")
          browser-proc (process/process ["node" "-e" playwright-code]
                                        {:out :inherit
                                         :err :inherit})]

      ;; Wait for browser to connect
      (if (wait-for-browser 15000)
        (do
          ;; Run tests
          (Thread/sleep 1000) ; Extra settle time
          (run-eval-tests!)

          ;; Wait for responses
          (if (wait-for-responses 8 5000) ; 4 evals × 2 responses each
            (verify-results)
            (do (log "❌ FAIL: Timeout waiting for responses")
                false)))
        (do (log "❌ FAIL: Browser did not connect")
            false)))

    (finally
      (log "Stopping servers...")
      (server/stop-server!)
      (stop-http-server!))))

;; Run the test
(let [result (-main)]
  (System/exit (if result 0 1)))
