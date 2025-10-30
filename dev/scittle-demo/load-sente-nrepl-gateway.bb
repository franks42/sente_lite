#!/usr/bin/env bb
;; Load sente-nrepl gateway into running BB instance
;;
;; This adds nREPL-over-sente capability to the existing bb dev server.
;; The gateway runs IN the same BB process, sharing the same runtime.

(require '[babashka.nrepl-client :as nrepl])

(def nrepl-port 1338)  ;; BB nREPL server from bb dev

(defn eval-in-bb [expr]
  (println "\n→ Evaluating in BB server:")
  (println expr)
  (let [result (nrepl/eval-expr {:port nrepl-port :expr expr :timeout-ms 30000})]
    (println "← Result:" result)
    result))

(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "Loading sente-nrepl gateway into BB server...")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

;; 1. Add source path
(eval-in-bb "(require '[babashka.classpath :as cp])")
(eval-in-bb "(cp/add-classpath \"../../src\")")

;; 2. Require dependencies
(eval-in-bb "(require '[sente-lite.server :as sente])")
(eval-in-bb "(require '[bencode.core :as bencode])")
(eval-in-bb "(require '[clojure.string :as str])")

;; 3. Load the nREPL gateway code
(eval-in-bb "
(do
  (require '[java.io PushbackInputStream EOFException BufferedOutputStream]
           '[java.net ServerSocket])

  ;; State
  (defonce browser-conn-id (atom nil))
  (defonce last-nrepl-context (volatile! nil))
  (defonce sente-server (atom nil))
  (defonce nrepl-socket (atom nil))

  ;; Bencode helpers
  (defn coerce-bencode [x]
    (if (bytes? x) (String. x) x))

  (defn read-bencode [in]
    (let [msg (bencode/read-bencode in)
          msg (zipmap (map keyword (keys msg))
                      (map coerce-bencode (vals msg)))]
      msg))

  (defn send-bencode-response
    [{:keys [out id session response]
      :or {out (:out @last-nrepl-context)}}]
    (let [response (cond-> response
                     id (assoc :id id)
                     session (assoc :session session))]
      (bencode/write-bencode out response)
      (.flush out)))

  (println \"✓ nREPL bencode helpers loaded\")
  :loaded-bencode)
")

;; 4. Load the sente message handler
(eval-in-bb "
(do
  (defn handle-browser-message
    [conn-id [event-type event-data]]
    (println \"INFO: Browser message\" {:type event-type :conn-id conn-id})

    (case event-type
      :nrepl/register
      (do
        (println \"INFO: Browser registered as nREPL client\" {:conn-id conn-id})
        (reset! browser-conn-id conn-id))

      :nrepl/response
      (let [{:keys [id session] :as response} event-data]
        (println \"INFO: nREPL response from browser\" {:id id})
        (send-bencode-response {:id id
                                :session session
                                :response (dissoc response :id :session)}))

      (println \"DEBUG: Unhandled message\" {:type event-type})))

  (println \"✓ Browser message handler loaded\")
  :loaded-handler)
")

;; 5. Load nREPL request handling
(eval-in-bb "
(do
  (defn send-to-browser! [msg]
    (if-let [conn @browser-conn-id]
      (do
        (sente/send-message-to-connection! conn [:nrepl/eval msg])
        (println \"INFO: Sent to browser\" {:msg-keys (keys msg)}))
      (println \"ERROR: No browser connected\")))

  (defn handle-eval [{:as ctx :keys [msg session id]}]
    (vreset! last-nrepl-context ctx)
    (let [code (get msg :code)]
      (if (or (str/includes? code \"clojure.main/repl-requires\")
              (str/includes? code \"System/getProperty\"))
        (do
          (send-bencode-response (assoc ctx :response {\"value\" \"nil\"}))
          (send-bencode-response (assoc ctx :response {\"status\" [\"done\"]})))
        (send-to-browser! {:op :eval :code code :id id :session session}))))

  (defn handle-clone [ctx]
    (let [id (str (java.util.UUID/randomUUID))]
      (send-bencode-response (assoc ctx
                                    :response {\"new-session\" id
                                               \"status\" [\"done\"]}))))

  (defn handle-describe [ctx]
    (vreset! last-nrepl-context ctx)
    (let [response {\"versions\" {\"sente-nrepl\" {\"major\" \"0\" \"minor\" \"1\" \"incremental\" \"0\"}}
                    \"ops\" (zipmap (map name [:eval :load-file :clone :describe])
                                    (repeat {}))
                    \"aux\" {\"cwd\" (System/getProperty \"user.dir\")}
                    :status [\"done\"]}]
      (send-bencode-response (assoc ctx :response response))))

  (defn handle-load-file [ctx]
    (let [msg (get ctx :msg)
          code (get msg :file)
          msg (assoc msg :code code)]
      (handle-eval (assoc ctx :msg msg))))

  (println \"✓ nREPL handlers loaded\")
  :loaded-nrepl)
")

;; 6. Start sente server
(eval-in-bb "
(do
  (when @sente-server
    (println \"Stopping existing sente server...\")
    (sente/stop-server!))

  (reset! sente-server
          (sente/start-server!
           {:port 1342
            :ws-path \"/nrepl\"
            :on-message handle-browser-message
            :wire-format :edn
            :telemetry {:enabled true}}))

  (println \"INFO: Sente server started\" {:port 1342})
  :sente-ready)
")

;; 7. Start nREPL bencode server
(eval-in-bb "
(do
  (defn session-loop [in out]
    (loop []
      (when-let [msg (try (read-bencode in)
                          (catch java.io.EOFException _ nil))]
        (let [ctx {:out out :msg msg}
              id (get msg :id)
              session (get msg :session)
              ctx (assoc ctx :id id :session session)
              op (keyword (get msg :op))]

          (println \"INFO: nREPL operation\" {:op op :id id})

          (case op
            :clone (handle-clone ctx)
            :eval (handle-eval ctx)
            :describe (handle-describe ctx)
            :load-file (handle-load-file ctx)
            (println \"WARN: Unknown nREPL op\" {:op op})))
        (recur))))

  (defn listen [listener port]
    (println \"INFO: nREPL server started\" {:port port})
    (println (str \"nREPL server started on port \" port \"...\"))
    (let [client-socket (.accept listener)
          in (.getInputStream client-socket)
          in (java.io.PushbackInputStream. in)
          out (.getOutputStream client-socket)
          out (java.io.BufferedOutputStream. out)]
      (future (session-loop in out))
      (recur listener port)))

  (when @nrepl-socket
    (println \"Closing existing nREPL socket...\")
    (.close @nrepl-socket))

  (let [port 1347
        inet-address (java.net.InetAddress/getByName \"localhost\")
        socket (java.net.ServerSocket. port 0 inet-address)]
    (reset! nrepl-socket socket)
    (future (listen socket port)))

  (println \"INFO: nREPL bencode server started\" {:port 1347})
  :nrepl-ready)
")

(println "")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "✅ sente-nrepl gateway loaded and running!")
(println "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
(println "")
(println "Services:")
(println "  Port 1342: Sente WebSocket (for browser)")
(println "  Port 1347: nREPL bencode server (for editor)")
(println "")
(println "Next steps:")
(println "  1. Load browser client: bb load-browser dev/scittle-demo/examples/sente-nrepl-client.cljs")
(println "  2. Test eval: Connect editor to port 1347 and eval (+ 1 2 3)")
(println "")
