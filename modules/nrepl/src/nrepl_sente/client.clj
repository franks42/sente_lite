(ns nrepl-sente.client
  "nREPL-over-sente client API.

   Provides simple eval!/load-file! API for server-side code to call
   browser or other connected peers.

   Connection discovery uses registry entries populated by sente-lite server.

   Usage:
     ;; Get latest connection (simplest)
     (def conn (get-nrepl-connection!))

     ;; Evaluate code
     (eval! conn \"(+ 1 2 3)\")
     => {:value \"6\" :status [:done] ...}

     ;; Load a file
     (load-file! conn \"(ns my.ns) (def x 42)\" \"my/ns.clj\" \"ns.clj\")
     => {:value \"#'my.ns/x\" :status [:done] ...}"
  (:require [nrepl-sente.protocol :as proto]
            [sente-lite.server :as server]))

;;; ============================================================
;;; Pending Request Tracking
;;; ============================================================

(defonce ^:private pending-requests (atom {}))  ; id -> {:promise p :sent-at ms}

(defn- register-pending! [id promise-fn]
  (swap! pending-requests assoc id
         {:promise promise-fn
          :sent-at (System/currentTimeMillis)}))

(defn- resolve-pending! [id response]
  (when-let [{:keys [promise]} (get @pending-requests id)]
    (swap! pending-requests dissoc id)
    (deliver promise response)
    true))

(defn- cleanup-stale-requests!
  "Remove requests older than timeout-ms."
  [timeout-ms]
  (let [now (System/currentTimeMillis)
        cutoff (- now timeout-ms)]
    (swap! pending-requests
           (fn [m]
             (into {} (filter (fn [[_id {:keys [sent-at]}]]
                                (> sent-at cutoff))
                              m))))))

;;; ============================================================
;;; Protocol Capability Cache
;;; ============================================================

(defonce ^:private capability-cache (atom {}))  ; conn-id -> {:nrepl? bool :checked-at ms :ops #{}}

(defn- cache-capability! [conn-id capable? ops]
  (swap! capability-cache assoc conn-id
         {:nrepl? capable?
          :checked-at (System/currentTimeMillis)
          :ops (set (keys ops))}))

(defn- get-cached-capability [conn-id max-age-ms]
  (when-let [cached (get @capability-cache conn-id)]
    (let [age (- (System/currentTimeMillis) (:checked-at cached))]
      (when (< age max-age-ms)
        cached))))

(defn invalidate-capability!
  "Clear cached capability for a connection.
   Call when connection closes."
  [conn-id]
  (swap! capability-cache dissoc conn-id))

;;; ============================================================
;;; Response Handler (registered as on-message handler)
;;; ============================================================

(defn make-response-handler
  "Create a handler for nREPL responses.
   Register this as part of the on-message routing.

   Returns: (fn [conn-id event-id data] ...)"
  []
  (fn [_conn-id event-id data]
    (when (= event-id proto/response-event-id)
      (let [id (:id data)]
        (when id
          (resolve-pending! id data))))))

;;; ============================================================
;;; Low-Level Send/Receive
;;; ============================================================

(defn send-request!
  "Send an nREPL request and wait for response.

   Arguments:
   - conn-id: Target connection ID
   - request: nREPL request map (will add :id if missing)
   - timeout-ms: How long to wait for response

   Returns: Response map or nil on timeout"
  [conn-id request timeout-ms]
  (let [id (or (:id request) (str (java.util.UUID/randomUUID)))
        request (assoc request :id id)
        p (promise)]
    (register-pending! id p)
    (server/send-event-to-connection! conn-id (proto/wrap-request request))
    (let [result (deref p timeout-ms nil)]
      (when-not result
        (swap! pending-requests dissoc id))
      result)))

;;; ============================================================
;;; Connection Discovery
;;; ============================================================

(defn get-connections
  "Get list of active connections from registry.
   Returns seq of {:conn-id ... :opened-at ...} sorted by opened-at (newest first)."
  []
  (server/get-connections))

(defn get-latest-connection
  "Get the most recently connected conn-id.
   Returns nil if no connections."
  []
  (server/get-latest-connection))

;;; ============================================================
;;; Protocol Probe
;;; ============================================================

(defn probe-nrepl-capable?
  "Send describe probe to verify nREPL capability.

   Arguments:
   - conn-id: Target connection ID
   - timeout-ms: How long to wait (default 3000)

   Returns: {:nrepl? bool :ops #{...}} or nil on timeout"
  ([conn-id]
   (probe-nrepl-capable? conn-id 3000))
  ([conn-id timeout-ms]
   (let [response (send-request! conn-id (proto/describe-request) timeout-ms)]
     (if response
       (let [capable? (and (some? (:ops response))
                           (contains? (:ops response) "eval"))
             ops (:ops response)]
         (cache-capability! conn-id capable? ops)
         {:nrepl? capable? :ops (set (keys ops))})
       nil))))

(defn get-nrepl-connection!
  "Get a verified nREPL-capable connection.

   Arguments:
   - conn-id: Specific connection (optional, defaults to latest)
   - opts: {:timeout-ms 3000, :cache-max-age-ms 60000, :skip-probe? false}

   Returns: conn-id if verified

   Throws: ex-info if no connection or not nREPL-capable"
  ([]
   (get-nrepl-connection! nil {}))
  ([conn-id-or-opts]
   (if (map? conn-id-or-opts)
     (get-nrepl-connection! nil conn-id-or-opts)
     (get-nrepl-connection! conn-id-or-opts {})))
  ([conn-id {:keys [timeout-ms cache-max-age-ms skip-probe?]
             :or {timeout-ms 3000
                  cache-max-age-ms 60000
                  skip-probe? false}}]
   (let [conn (or conn-id (get-latest-connection))]
     (when-not conn
       (throw (ex-info "No connections available" {:type :no-connections})))

     ;; Check cache first
     (if-let [cached (when-not skip-probe?
                       (get-cached-capability conn cache-max-age-ms))]
       (if (:nrepl? cached)
         conn
         (throw (ex-info "Connection does not support nREPL (cached)"
                         {:conn-id conn :type :not-nrepl})))

       ;; Probe if not cached or skip-probe is false
       (if skip-probe?
         conn
         (let [result (probe-nrepl-capable? conn timeout-ms)]
           (cond
             (nil? result)
             (throw (ex-info "Probe timeout - connection not responding"
                             {:conn-id conn :type :probe-timeout}))

             (not (:nrepl? result))
             (throw (ex-info "Connection does not support nREPL"
                             {:conn-id conn :type :not-nrepl :ops (:ops result)}))

             :else conn)))))))

;;; ============================================================
;;; High-Level API
;;; ============================================================

(defn eval!
  "Evaluate code on a remote nREPL.

   Arguments:
   - conn-id: Target connection ID
   - code: Code string to evaluate
   - opts: {:timeout-ms 5000, :ns nil, :session nil}

   Returns: nREPL response map {:value ... :status [...] ...}
            or {:err ... :status [:done :error]} on error"
  ([conn-id code]
   (eval! conn-id code {}))
  ([conn-id code {:keys [timeout-ms ns session]
                  :or {timeout-ms 5000}}]
   (send-request! conn-id
                  (proto/eval-request code {:ns ns :session session})
                  timeout-ms)))

(defn load-file!
  "Load a file on a remote nREPL.

   Arguments:
   - conn-id: Target connection
   - file-content: File content as string
   - file-path: Path for error reporting
   - file-name: File name
   - opts: {:timeout-ms 10000, :session nil}

   Returns: nREPL response map"
  ([conn-id file-content file-path file-name]
   (load-file! conn-id file-content file-path file-name {}))
  ([conn-id file-content file-path file-name {:keys [timeout-ms session]
                                              :or {timeout-ms 10000}}]
   (send-request! conn-id
                  (proto/load-file-request file-content file-path file-name
                                           {:session session})
                  timeout-ms)))

;;; ============================================================
;;; Convenience Functions
;;; ============================================================

(defn eval-latest!
  "Evaluate code on the latest connected peer.

   Shorthand for:
     (eval! (get-nrepl-connection!) code opts)

   Probes connection on first call, uses cache after."
  ([code]
   (eval-latest! code {}))
  ([code opts]
   (let [conn (get-nrepl-connection!)]
     (eval! conn code opts))))

(defn load-file-latest!
  "Load file on the latest connected peer.

   Shorthand for:
     (load-file! (get-nrepl-connection!) ...)"
  ([file-content file-path file-name]
   (load-file-latest! file-content file-path file-name {}))
  ([file-content file-path file-name opts]
   (let [conn (get-nrepl-connection!)]
     (load-file! conn file-content file-path file-name opts))))
