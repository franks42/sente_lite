(ns nrepl-sente.server
  "nREPL server that evaluates code and returns results.

   Works on both Babashka (using load-string) and Scittle (using eval_string).
   Receives EDN messages via sente, evaluates, returns EDN responses.

   Namespace state is persisted across requests (session-based):
   - BB/CLJ: Uses atoms to track namespace per session
   - Scittle/CLJS: Uses scittle.core/eval_string which has built-in persistence

   Usage:
     ;; Create handler for sente on-message
     (def handler (make-nrepl-handler send-fn))

     ;; In sente on-message callback
     (handler conn-id event)"
  (:require [nrepl-sente.protocol :as proto]
            #?(:clj [clojure.tools.reader :as reader])
            #?(:clj [clojure.tools.reader.reader-types :as rt])))

;;; ============================================================
;;; Namespace State (CLJ only - Scittle handles this internally)
;;; ============================================================

;; Atom tracking sessions for persistence across requests.
;; Map of session-id -> {:ns <namespace object>}
#?(:clj (defonce !sessions (atom {})))

#?(:clj
   (defn- get-session-ns
     [session-id]
     (if session-id
       (get-in @!sessions [session-id :ns] (find-ns 'user))
       (find-ns 'user))))

#?(:clj
   (defn- update-session-ns!
     [session-id new-ns]
     (when session-id
       (swap! !sessions assoc-in [session-id :ns] new-ns))))

;;; ============================================================
;;; Platform-specific eval
;;; ============================================================

#?(:clj
   (defn eval-code
     "Evaluate code string on Babashka/JVM using reader + eval.
      Namespace changes persist across requests via !sessions atom.
      Uses binding [*ns* ...] to ensure namespace context is maintained."
     [code-string session-id]
     (try
       ;; Evaluate in the context of session's namespace
       (binding [*ns* (get-session-ns session-id)]
         (let [rdr (rt/string-push-back-reader code-string)
               result (loop [last-result nil]
                        (let [form (reader/read rdr false ::eof)]
                          (if (= form ::eof)
                            last-result
                            (recur (eval form)))))
               final-ns *ns*]
           ;; Save current namespace for next request
           (update-session-ns! session-id final-ns)
           {:success true
            :value (pr-str result)
            :ns (str (ns-name final-ns))}))
       (catch Exception e
         {:success false
          :error (.getMessage e)
          :ex (str (type e))
          :ns (str (ns-name (get-session-ns session-id)))}))))

#?(:cljs
   (defn- scittle-eval
     "Evaluate code string on Scittle using eval_string."
     [code-string]
     (let [eval-fn (.-eval_string (.-core js/window.scittle))]
       (try
         (let [result (eval-fn code-string)
               current-ns (eval-fn "(str *ns*)")]
           {:success true
            :value (pr-str result)
            :ns current-ns})
         (catch :default e
           (let [current-ns (try (eval-fn "(str *ns*)") (catch :default _ "user"))]
             {:success false
              :error (.-message e)
              :ex (str (type e))
              :ns current-ns}))))))

#?(:cljs
   (defn- nbb-eval
     "Evaluate code string on nbb using nbb.core/load-string.
      Returns a Promise that resolves to the result map.
      State persists across calls in nbb's global context."
     [code-string]
     ;; Require at runtime to avoid issues when running in Scittle
     ;; Wrapped in try-catch for safety if nbb.core isn't available
     (try
       (let [nbb-core (js/require "nbb.core")
             load-string (.-load_string nbb-core)]
         (-> (load-string code-string)
             (.then (fn [result]
                      ;; Get current namespace
                      (-> (load-string "(str *ns*)")
                          (.then (fn [ns-str]
                                   {:success true
                                    :value (pr-str result)
                                    :ns ns-str})))))
             (.catch (fn [e]
                       ;; Return error result
                       (js/Promise.resolve
                        {:success false
                         :error (.-message e)
                         :ex (str (type e))
                         :ns "user"})))))
       (catch :default e
         ;; Return synchronous error if require fails
         {:success false
          :error (str "nbb.core not available: " (.-message e))
          :ex "RequireError"
          :ns "user"}))))

#?(:cljs
   (defn- detect-runtime
     "Detect if we're running in Scittle (browser) or nbb (Node.js)."
     []
     (cond
       ;; Browser with Scittle
       (and (exists? js/window)
            (exists? js/window.scittle))
       :scittle

       ;; Node.js (nbb)
       (exists? js/process)
       :nbb

       :else
       :unknown)))

#?(:cljs
   (def ^:private runtime (detect-runtime)))

#?(:cljs
   (defn eval-code
     "Evaluate code string on Scittle or nbb.
      Automatically detects the runtime environment.
      session-id arg is ignored for CLJS (runtime manages logic)."
     [code-string session-id]
     (case runtime
       :scittle (scittle-eval code-string)
       :nbb (nbb-eval code-string)
       {:success false
        :error "Unknown runtime - neither Scittle nor nbb detected"
        :ex "RuntimeError"
        :ns "user"})))

;;; ============================================================
;;; Operation Handlers
;;; ============================================================

(defn handle-eval
  "Handle :eval operation."
  [{:keys [code id session ns]}]
  (let [result (eval-code code session)
        opts {:id id :session session :ns (:ns result)}]
    (if (:success result)
      (proto/value-response (:value result) opts)
      (proto/error-response (:error result) (assoc opts :ex (:ex result))))))

(defn handle-load-file
  "Handle :load-file operation (same as eval with file content)."
  [{:keys [file id session] :as msg}]
  (handle-eval (assoc msg :code file)))

(defn handle-describe
  "Handle :describe operation."
  [{:keys [id session]}]
  (proto/describe-response {:id id :session session}))

(defn handle-clone
  "Handle :clone operation."
  [{:keys [id]}]
  (let [new-session (str #?(:clj (java.util.UUID/randomUUID)
                            :cljs (random-uuid)))]
    (proto/clone-response new-session {:id id})))

;;; ============================================================
;;; Request Dispatcher
;;; ============================================================

(defn dispatch-request
  "Dispatch an nREPL request to the appropriate handler.

   Arguments:
   - request: EDN map with :op and operation-specific keys

   Returns: EDN response map"
  [request]
  (let [op (keyword (:op request))]
    (case op
      :eval (handle-eval request)
      :load-file (handle-load-file request)
      :describe (handle-describe request)
      :clone (handle-clone request)
      ;; Unknown operation
      (proto/error-response (str "Unknown operation: " op)
                            {:id (:id request)
                             :session (:session request)}))))

;;; ============================================================
;;; Sente Integration
;;; ============================================================

(defn make-nrepl-handler
  "Create an nREPL message handler for sente.

   Arguments:
   - send-fn: Function to send response back, called as (send-fn conn-id event)

   Returns: Handler function (fn [conn-id event-id data] ...)

   Note: sente-lite server's :on-message callback receives 3 args:
         (on-message conn-id event-id data)

   Usage with sente-lite server:
     (def nrepl-handler (make-nrepl-handler sente/send-event-to-connection!))

     (sente/start-server!
       {:port 8080
        :on-message nrepl-handler})"
  [send-fn]
  (fn [conn-id event-id data]
    (when (= event-id proto/request-event-id)
      ;; Inject conn-id as default session if missing to ensure state persistence
      (let [request (if (:session data)
                      data
                      (assoc data :session conn-id))
            response (dispatch-request request)]
        (send-fn conn-id (proto/wrap-response response))))))

(defn make-simple-handler
  "Create a simple handler that returns response directly (for testing).

   Returns: Function (fn [request] response)"
  []
  dispatch-request)
