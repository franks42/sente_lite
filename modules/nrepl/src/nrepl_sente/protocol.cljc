(ns nrepl-sente.protocol
  "nREPL-over-sente protocol definitions.

   EDN message formats for nREPL operations transported via sente-lite.
   These are the same as standard nREPL messages, just without bencode encoding.")

;;; ============================================================
;;; Operations
;;; ============================================================

(def ops
  "Supported nREPL operations"
  #{:eval :load-file :describe :clone})

;;; ============================================================
;;; Request Message Builders
;;; ============================================================

(defn eval-request
  "Build an eval request message.

   Arguments:
   - code: String of code to evaluate
   - opts: Optional map with :id, :session, :ns

   Returns EDN message map."
  [code & [{:keys [id session ns]}]]
  (cond-> {:op :eval
           :code code}
    id (assoc :id id)
    session (assoc :session session)
    ns (assoc :ns ns)))

(defn load-file-request
  "Build a load-file request message.

   Arguments:
   - file-content: String content of the file
   - file-path: Path to the file (for error reporting)
   - file-name: Name of the file
   - opts: Optional map with :id, :session"
  [file-content file-path file-name & [{:keys [id session]}]]
  (cond-> {:op :load-file
           :file file-content
           :file-path file-path
           :file-name file-name}
    id (assoc :id id)
    session (assoc :session session)))

(defn describe-request
  "Build a describe request message."
  [& [{:keys [id session]}]]
  (cond-> {:op :describe}
    id (assoc :id id)
    session (assoc :session session)))

(defn clone-request
  "Build a clone (new session) request message."
  [& [{:keys [id session]}]]
  (cond-> {:op :clone}
    id (assoc :id id)
    session (assoc :session session)))

;;; ============================================================
;;; Response Message Builders
;;; ============================================================

(defn value-response
  "Build a successful value response.

   Arguments:
   - value: The result value (already stringified via pr-str)
   - opts: Map with :id, :session, :ns"
  [value {:keys [id session ns]}]
  (cond-> {:value value
           :status [:done]}
    id (assoc :id id)
    session (assoc :session session)
    ns (assoc :ns ns)))

(defn error-response
  "Build an error response.

   Arguments:
   - error-msg: Error message string
   - opts: Map with :id, :session, :ns, :ex (exception type)"
  [error-msg {:keys [id session ns ex]}]
  (cond-> {:err error-msg
           :status [:done :error]}
    id (assoc :id id)
    session (assoc :session session)
    ns (assoc :ns ns)
    ex (assoc :ex ex)))

(defn out-response
  "Build a stdout output response (for streaming output)."
  [text {:keys [id session]}]
  (cond-> {:out text}
    id (assoc :id id)
    session (assoc :session session)))

(defn describe-response
  "Build a describe response with server capabilities."
  [{:keys [id session]}]
  (cond-> {:versions {"nrepl-sente" {"major" "0" "minor" "1" "incremental" "0"}}
           :ops (zipmap (map name ops) (repeat {}))
           :status [:done]}
    id (assoc :id id)
    session (assoc :session session)))

(defn clone-response
  "Build a clone response with new session ID."
  [new-session-id {:keys [id]}]
  (cond-> {:new-session new-session-id
           :status [:done]}
    id (assoc :id id)))

;;; ============================================================
;;; Sente Event Wrappers
;;; ============================================================

(def request-event-id :nrepl/request)
(def response-event-id :nrepl/response)

(defn wrap-request
  "Wrap an nREPL request as a sente event."
  [request]
  [request-event-id request])

(defn wrap-response
  "Wrap an nREPL response as a sente event."
  [response]
  [response-event-id response])

(defn unwrap-event
  "Unwrap a sente event, returning [event-id data]."
  [event]
  [(first event) (second event)])
