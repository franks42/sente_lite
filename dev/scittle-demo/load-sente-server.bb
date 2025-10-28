#!/usr/bin/env bb
;; Load sente-lite server into running BB instance via nREPL

(require '[babashka.nrepl-client :as nrepl])

(def nrepl-port 1338)  ;; BB nREPL server

(defn eval-in-bb [expr]
  (println "\n→ Evaluating in BB server:")
  (println expr)
  (let [result (nrepl/eval-expr {:port nrepl-port :expr expr})]
    (println "← Result:" result)
    result))

(println "Loading sente-lite server into BB...")

;; 1. Add source path
(eval-in-bb "(require '[babashka.classpath :as cp])")
(eval-in-bb "(cp/add-classpath \"../../src\")")

;; 2. Test that we can require a sente-lite namespace
(eval-in-bb "(println \"Checking if sente-lite source is accessible...\")")
(eval-in-bb "(try
               (require '[clojure.string :as str])
               (println \"Basic require works\")
               (catch Exception e (println \"Error:\" (.getMessage e))))")

;; 3. Start simple http-kit WebSocket server on port 1342
(eval-in-bb "
(do
  (require '[org.httpkit.server :as http])

  (defn ws-handler [request]
    (http/with-channel request channel
      (println \"WebSocket client connected!\")
      (http/on-receive channel (fn [data]
                                  (println \"Received:\" data)
                                  (http/send! channel (str \"Echo: \" data))))
      (http/on-close channel (fn [status]
                               (println \"WebSocket client disconnected\" status)))))

  (defonce server (atom nil))

  (when @server
    (println \"Stopping existing server...\")
    (@server))

  (reset! server (http/run-server ws-handler {:port 1342}))
  (println \"✓ Sente-lite WebSocket server started on port 1342\")
  :ready)
")

(println "\n✅ Sente-lite server loaded and running on port 1342!")
(println "   Test with: websocat ws://localhost:1342")
