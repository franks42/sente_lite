(ns nrepl-sente.proxy
  "Bencode proxy for nREPL-over-sente.

   Bridges bencode nREPL clients (editors, nREPL-MCP) to sente-lite peers.

   Architecture:
     [Editor/MCP] ─bencode→ [Proxy] ─EDN/sente→ [Peer]

   Usage:
     (require '[nrepl-sente.proxy :as proxy])

     ;; Start proxy (uses latest connection by default)
     (proxy/start! {:port 1347})

     ;; Or with explicit target
     (proxy/start! {:port 1347 :target-conn-id \"conn-123\"})

     ;; Discovery:
     ;; - In-process: (proxy/get-proxy-url) or (registry/get-value \"nrepl.proxy/port\")
     ;; - Cross-process: reads .nrepl-port file (standard nREPL convention)

     ;; Stop
     (proxy/stop!)"
  (:require [bencode.core :as bencode]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nrepl-sente.client :as client]
            [sente-lite.registry :as registry])
  (:import [java.io PushbackInputStream EOFException BufferedOutputStream]
           [java.net ServerSocket InetAddress]))

(set! *warn-on-reflection* true)

;;; ============================================================
;;; State
;;; ============================================================

(defonce ^:private proxy-state (atom nil))  ; {:socket ServerSocket :config map}
(defonce ^:private active-sessions (atom {}))  ; session-id -> {:conn-id ... :out ...}

;;; ============================================================
;;; Port File (cross-process discovery)
;;; ============================================================

(defn- write-port-file!
  "Write port to .nrepl-port file for cross-process discovery"
  [port port-file]
  (spit port-file (str port)))

(defn- delete-port-file!
  "Delete .nrepl-port file"
  [port-file]
  (when (.exists (io/file port-file))
    (.delete (io/file port-file))))

;;; ============================================================
;;; Bencode Helpers
;;; ============================================================

(defn- coerce-bencode
  "Convert bencode bytes to strings"
  [x]
  (if (bytes? x)
    (String. ^bytes x)
    x))

(defn- read-bencode-msg
  "Read and decode bencode message from input stream"
  [in]
  (let [msg (bencode/read-bencode in)]
    (zipmap (map keyword (keys msg))
            (map coerce-bencode (vals msg)))))

(defn- write-bencode-msg
  "Write bencode message to output stream"
  [^BufferedOutputStream out msg]
  (bencode/write-bencode out msg)
  (.flush out))

(defn- edn-status->bencode
  "Convert EDN status keywords to bencode strings"
  [status]
  (mapv name status))

;;; ============================================================
;;; Response Conversion
;;; ============================================================

(defn- edn-response->bencode
  "Convert EDN nREPL response to bencode format"
  [{:keys [value err out ns status id session ex] :as response}]
  (cond-> {}
    value (assoc "value" value)
    err (assoc "err" err)
    out (assoc "out" out)
    ns (assoc "ns" ns)
    ex (assoc "ex" ex)
    status (assoc "status" (edn-status->bencode status))
    id (assoc "id" id)
    session (assoc "session" session)))

;;; ============================================================
;;; Operation Handlers
;;; ============================================================

(defn- get-target-conn
  "Get target connection ID, either from config or latest"
  []
  (or (when (registry/registered? "nrepl.proxy/target-conn")
        (registry/get-value "nrepl.proxy/target-conn"))
      (client/get-nrepl-connection! {:skip-probe? true})))

(defn- handle-clone
  "Handle :clone - create new session"
  [out {:keys [id]}]
  (let [new-session (str (java.util.UUID/randomUUID))]
    (write-bencode-msg out {"id" id
                            "new-session" new-session
                            "status" ["done"]})))

(defn- handle-describe
  "Handle :describe - report capabilities"
  [out {:keys [id session]}]
  (let [response {"versions" {"nrepl-sente-proxy" {"major" "0"
                                                   "minor" "1"
                                                   "incremental" "0"}}
                  "ops" {"eval" {}
                         "load-file" {}
                         "clone" {}
                         "describe" {}}
                  "status" ["done"]}
        response (cond-> response
                   id (assoc "id" id)
                   session (assoc "session" session))]
    (write-bencode-msg out response)))

(defn- should-filter-code?
  "Check if code should be filtered (REPL setup noise)"
  [code]
  (or (str/includes? code "clojure.main/repl-requires")
      (str/includes? code "System/getProperty")
      (str/includes? code "*cider-*")))

(defn- handle-eval
  "Handle :eval - forward to peer via client API"
  [out {:keys [id session code]}]
  (if (should-filter-code? code)
    ;; Filter REPL setup noise
    (do
      (write-bencode-msg out {"id" id "session" session "value" "nil"})
      (write-bencode-msg out {"id" id "session" session "status" ["done"]}))
    ;; Forward to peer
    (try
      (let [conn-id (get-target-conn)
            response (client/eval! conn-id code {:timeout-ms 30000})]
        (if response
          (write-bencode-msg out (edn-response->bencode
                                  (assoc response :id id :session session)))
          ;; Timeout
          (do
            (write-bencode-msg out {"id" id "session" session
                                    "err" "Timeout waiting for response"})
            (write-bencode-msg out {"id" id "session" session
                                    "status" ["done" "error"]}))))
      (catch Exception e
        (write-bencode-msg out {"id" id "session" session
                                "err" (str "Proxy error: " (.getMessage e))})
        (write-bencode-msg out {"id" id "session" session
                                "status" ["done" "error"]})))))

(defn- handle-load-file
  "Handle :load-file - forward to peer via client API"
  [out {:keys [id session file file-path file-name]}]
  (try
    (let [conn-id (get-target-conn)
          response (client/load-file! conn-id file file-path file-name
                                      {:timeout-ms 30000})]
      (if response
        (write-bencode-msg out (edn-response->bencode
                                (assoc response :id id :session session)))
        ;; Timeout
        (do
          (write-bencode-msg out {"id" id "session" session
                                  "err" "Timeout waiting for response"})
          (write-bencode-msg out {"id" id "session" session
                                  "status" ["done" "error"]}))))
    (catch Exception e
      (write-bencode-msg out {"id" id "session" session
                              "err" (str "Proxy error: " (.getMessage e))})
      (write-bencode-msg out {"id" id "session" session
                              "status" ["done" "error"]}))))

;;; ============================================================
;;; Session Loop
;;; ============================================================

(defn- session-loop
  "Main session loop - reads bencode, dispatches operations"
  [in out]
  (loop []
    (when-let [msg (try
                     (read-bencode-msg in)
                     (catch EOFException _
                       nil)
                     (catch Exception e
                       (println "Proxy: bencode read error:" (.getMessage e))
                       nil))]
      (let [op (keyword (:op msg))
            id (:id msg)
            session (:session msg)]
        (case op
          :clone (handle-clone out msg)
          :describe (handle-describe out msg)
          :eval (handle-eval out msg)
          :load-file (handle-load-file out msg)
          ;; Unknown op
          (do
            (println "Proxy: unknown op" op)
            (write-bencode-msg out {"id" id "session" session
                                    "err" (str "Unknown operation: " (name op))
                                    "status" ["done" "error"]})))
        (recur)))))

(defn- accept-connections
  "Accept loop for incoming connections"
  [^ServerSocket socket]
  (println "Proxy: listening on port" (.getLocalPort socket))
  (loop []
    (when-not (.isClosed socket)
      (try
        (let [client (.accept socket)
              in (PushbackInputStream. (.getInputStream client))
              out (BufferedOutputStream. (.getOutputStream client))]
          (println "Proxy: client connected")
          (future
            (try
              (session-loop in out)
              (catch Exception e
                (println "Proxy: session error:" (.getMessage e)))
              (finally
                (.close client)
                (println "Proxy: client disconnected")))))
        (catch Exception e
          (when-not (.isClosed socket)
            (println "Proxy: accept error:" (.getMessage e)))))
      (recur))))

;;; ============================================================
;;; Public API
;;; ============================================================

(defn start!
  "Start the bencode proxy.

   Options:
   - :port - TCP port to listen on (default: 0 = ephemeral)
   - :target-conn-id - specific connection to use (default: latest)
   - :host - bind address (default: localhost)
   - :port-file - path to port file (default: .nrepl-proxy-port in cwd)
   - :write-port-file? - write port file for discovery (default: true)

   Returns: {:port actual-port :port-file path-or-nil}"
  [{:keys [port target-conn-id host port-file write-port-file?]
    :or {port 0 host "localhost" port-file ".nrepl-proxy-port" write-port-file? true}}]
  (when @proxy-state
    (throw (ex-info "Proxy already running" {:port (:port (:config @proxy-state))})))

  ;; Store target in registry if specified
  (when target-conn-id
    (registry/register! "nrepl.proxy/target-conn" target-conn-id))

  (let [inet-addr (InetAddress/getByName host)
        socket (ServerSocket. port 0 inet-addr)
        actual-port (.getLocalPort socket)]

    (reset! proxy-state {:socket socket
                         :config {:port actual-port :host host :port-file port-file}})

    ;; Register in registry for in-process discovery
    (registry/register! "nrepl.proxy/port" actual-port)
    (registry/register! "nrepl.proxy/host" host)

    ;; Write port file for cross-process discovery
    (when write-port-file?
      (write-port-file! actual-port port-file)
      (registry/register! "nrepl.proxy/port-file" port-file))

    ;; Start accept loop in background
    (future (accept-connections socket))

    (println "nREPL proxy started on port" actual-port)
    {:port actual-port
     :port-file (when write-port-file? port-file)}))

(defn stop!
  "Stop the bencode proxy."
  []
  (when-let [{:keys [socket config]} @proxy-state]
    (.close ^ServerSocket socket)
    ;; Delete port file if it was written
    (when-let [port-file (:port-file config)]
      (delete-port-file! port-file))
    (reset! proxy-state nil)
    (reset! active-sessions {})
    ;; Clean up registry
    (when (registry/registered? "nrepl.proxy/port")
      (registry/unregister! "nrepl.proxy/port"))
    (when (registry/registered? "nrepl.proxy/host")
      (registry/unregister! "nrepl.proxy/host"))
    (when (registry/registered? "nrepl.proxy/port-file")
      (registry/unregister! "nrepl.proxy/port-file"))
    (when (registry/registered? "nrepl.proxy/target-conn")
      (registry/unregister! "nrepl.proxy/target-conn"))
    (println "nREPL proxy stopped")
    :ok))

(defn status
  "Get proxy status"
  []
  (if (registry/registered? "nrepl.proxy/port")
    {:running? true
     :port (registry/get-value "nrepl.proxy/port")
     :host (registry/get-value "nrepl.proxy/host")
     :target (if (registry/registered? "nrepl.proxy/target-conn")
               (registry/get-value "nrepl.proxy/target-conn")
               :latest)}
    {:running? false}))

(defn get-proxy-url
  "Get the nREPL proxy URL from registry.
   Returns nil if proxy is not running.

   Example: \"nrepl://localhost:1347\""
  []
  (when (registry/registered? "nrepl.proxy/port")
    (let [host (registry/get-value "nrepl.proxy/host")
          port (registry/get-value "nrepl.proxy/port")]
      (str "nrepl://" host ":" port))))
