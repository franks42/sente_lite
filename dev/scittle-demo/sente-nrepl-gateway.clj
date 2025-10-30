#!/usr/bin/env bb
;;
;; sente-nrepl Gateway
;;
;; Replaces sci.nrepl.browser-server with sente-lite based gateway.
;; Proxies nREPL messages between bencode (editor) and EDN (browser via sente).
;;
;; Architecture:
;;   nREPL Client (editor) ←bencode→ BB Gateway ←EDN/sente→ Browser
;;
;; Usage:
;;   (require '[sente-nrepl-gateway :as gateway])
;;   (gateway/start! {:nrepl-port 1339 :sente-port 1342})
;;

(ns sente-nrepl-gateway
  "nREPL gateway that proxies messages over sente-websocket using EDN"
  (:require [bencode.core :as bencode]
            [clojure.string :as str]
            [sente-lite.server :as sente])
  (:import [java.io PushbackInputStream EOFException BufferedOutputStream]
           [java.net ServerSocket]))

(set! *warn-on-reflection* true)

;;; State Management

(defonce ^:private browser-conn-id (atom nil))
(defonce ^:private pending-requests (atom {}))
(defonce ^:private last-nrepl-context (volatile! nil))

;;; Bencode Helpers (from sci.nrepl.browser-server)

(defn- coerce-bencode
  "Convert bencode bytes to strings"
  [x]
  (if (bytes? x)
    (String. ^bytes x)
    x))

(defn- read-bencode
  "Read and decode bencode message from input stream"
  [in]
  (try
    (let [msg (bencode/read-bencode in)
          msg (zipmap (map keyword (keys msg))
                      (map coerce-bencode (vals msg)))]
      msg)
    (catch Exception e
      (println "ERROR:" "Failed to read bencode" {:error (.getMessage e)})
      (throw e))))

(defn- send-bencode-response
  "Send bencode response to nREPL client"
  [{:keys [out id session response]
    :or {out (:out @last-nrepl-context)}}]
  (let [response (cond-> response
                   id (assoc :id id)
                   session (assoc :session session))]
    (bencode/write-bencode out response)
    (.flush ^java.io.OutputStream out)))

;;; Browser Message Handling

(defn- handle-browser-message
  "Handle messages from browser via sente-websocket"
  [conn-id [event-type event-data]]
  (println "INFO:" "Browser message received"
           {:conn-id conn-id
            :event-type event-type})

  (case event-type
    :nrepl/register
    ;; Browser registers as nREPL client
    (do
      (println "INFO:" "Browser registered as nREPL client" {:conn-id conn-id})
      (reset! browser-conn-id conn-id))

    :nrepl/response
    ;; Browser sent nREPL eval response
    (let [{:keys [id session] :as response} event-data]
      (println "INFO:" "nREPL response from browser"
               {:id id :session session})

      ;; Send back to nREPL client as bencode
      (send-bencode-response {:id id
                              :session session
                              :response (dissoc response :id :session)}))

    ;; Other messages
    (println "DEBUG:" "Unhandled browser message"
             {:type event-type :data event-data})))

;;; nREPL Request Handling

(defn- send-to-browser!
  "Send EDN message to browser via sente"
  [msg]
  (if-let [conn @browser-conn-id]
    (do
      (sente/send-message-to-connection! conn [:nrepl/eval msg])
      (println "INFO:" "Sent to browser" {:msg-keys (keys msg)}))
    (println "ERROR:" "No browser connected" {:msg msg})))

(defn- handle-clone
  "Handle :clone operation"
  [ctx]
  (let [id (str (java.util.UUID/randomUUID))]
    (send-bencode-response (assoc ctx
                                  :response {"new-session" id
                                             "status" ["done"]}))))

(defn- handle-eval
  "Handle :eval operation - send to browser"
  [{:as ctx :keys [msg session id]}]
  (vreset! last-nrepl-context ctx)
  (let [code (get msg :code)]
    ;; Filter out REPL setup noise
    (if (or (str/includes? code "clojure.main/repl-requires")
            (str/includes? code "System/getProperty"))
      (do
        (send-bencode-response (assoc ctx :response {"value" "nil"}))
        (send-bencode-response (assoc ctx :response {"status" ["done"]})))
      ;; Send to browser as EDN
      (send-to-browser! {:op :eval
                         :code code
                         :id id
                         :session session}))))

(defn- handle-load-file
  "Handle :load-file operation"
  [ctx]
  (let [msg (get ctx :msg)
        code (get msg :file)
        msg (assoc msg :code code)]
    (handle-eval (assoc ctx :msg msg))))

(defn- handle-describe
  "Handle :describe operation"
  [ctx]
  (vreset! last-nrepl-context ctx)
  (let [response {"versions" {"sente-nrepl" {"major" "0"
                                             "minor" "1"
                                             "incremental" "0"}}
                  "ops" (zipmap
                         (map name [:eval :load-file :clone :describe])
                         (repeat {}))
                  "aux" {"cwd" (System/getProperty "user.dir")}
                  :status ["done"]}]
    (send-bencode-response (assoc ctx :response response))))

;;; nREPL Session Loop

(defn- session-loop
  "Main nREPL session loop - reads bencode, dispatches operations"
  [in out {:keys [opts]}]
  (loop []
    (when-let [msg (try
                     (read-bencode in)
                     (catch EOFException _
                       (when-not (:quiet opts)
                         (println "INFO:" "nREPL client closed connection" {}))))]
      (let [ctx {:out out :msg msg}
            id (get msg :id)
            session (get msg :session)
            ctx (assoc ctx :id id :session session)
            op (keyword (get msg :op))]

        (println "INFO:" "nREPL operation" {:op op :id id :session session})

        (case op
          :clone (handle-clone ctx)
          :eval (handle-eval ctx)
          :describe (handle-describe ctx)
          :load-file (handle-load-file ctx)
          ;; Unknown ops - ignore
          (println "WARN:" "Unknown nREPL op" {:op op})))
      (recur))))

(defn- listen
  "Listen for nREPL client connections"
  [^ServerSocket listener {:as opts}]
  (println "INFO:" "nREPL server started" {:port (:port opts)})
  (println (str "nREPL server started on port " (:port opts) "..."))
  (let [client-socket (.accept listener)
        in (.getInputStream client-socket)
        in (PushbackInputStream. in)
        out (.getOutputStream client-socket)
        out (BufferedOutputStream. out)]
    (future
      (session-loop in out {:opts opts}))
    (recur listener opts)))

;;; Server Lifecycle

(defonce ^:private nrepl-socket (atom nil))
(defonce ^:private sente-server (atom nil))

(defn start-nrepl-server!
  "Start bencode nREPL server (for editors)"
  [{:keys [port] :as opts}]
  (let [port (or port 1339)
        inet-address (java.net.InetAddress/getByName "localhost")
        socket (ServerSocket. port 0 inet-address)]
    (reset! nrepl-socket socket)
    (future (listen socket opts))
    (println "INFO:" "nREPL server initialized" {:port port})))

(defn start-sente-server!
  "Start sente-websocket server (for browser)"
  [{:keys [port]}]
  (let [port (or port 1342)
        server (sente/start-server!
                {:port port
                 :ws-path "/nrepl"
                 :on-message handle-browser-message
                 :wire-format :edn
                 :telemetry {:enabled true}})]
    (reset! sente-server server)
    (println "INFO:" "Sente server started" {:port port})
    (println (str "Sente WebSocket server started on port " port))))

(defn stop!
  "Stop both servers"
  []
  (when-let [socket @nrepl-socket]
    (.close ^ServerSocket socket)
    (reset! nrepl-socket nil)
    (println "INFO:" "nREPL server stopped" {}))

  (when @sente-server
    (sente/stop-server!)
    (reset! sente-server nil)
    (println "INFO:" "Sente server stopped" {}))

  (reset! browser-conn-id nil)
  (reset! pending-requests {}))

(defn start!
  "Start the sente-nrepl gateway"
  [{:keys [nrepl-port sente-port]
    :or {nrepl-port 1347  ; Different from old gateway (1339)
         sente-port 1346}}]  ; Different port for new sente
  ; telemere startup removed
  (println "INFO:" "Starting sente-nrepl gateway"
           {:nrepl-port nrepl-port
            :sente-port sente-port})

  (start-sente-server! {:port sente-port})
  (start-nrepl-server! {:port nrepl-port})

  (println "INFO:" "sente-nrepl gateway ready"
           {:nrepl-port nrepl-port
            :sente-port sente-port})

  {:nrepl-port nrepl-port
   :sente-port sente-port})

;; For REPL testing
(comment
  (start! {})
  (stop!)
  @browser-conn-id)

;; Start gateway when running as script
(start! {})

(println "")
(println "Press Ctrl+C to stop")

;; Block to keep server running
@(promise)
