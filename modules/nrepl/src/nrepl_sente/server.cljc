(ns nrepl-sente.server
  "nREPL server that evaluates code and returns results.

   Works on both Babashka (using load-string) and Scittle (using eval_string).
   Receives EDN messages via sente, evaluates, returns EDN responses.

   Usage:
     ;; Create handler for sente on-message
     (def handler (make-nrepl-handler send-fn))

     ;; In sente on-message callback
     (handler conn-id event)"
  (:require [nrepl-sente.protocol :as proto]))

;;; ============================================================
;;; Platform-specific eval
;;; ============================================================

#?(:clj
   (defn eval-code
     "Evaluate code string on Babashka/JVM using load-string."
     [code-string]
     (try
       (let [result (load-string code-string)]
         {:success true
          :value (pr-str result)
          :ns (str *ns*)})
       (catch Exception e
         {:success false
          :error (.getMessage e)
          :ex (str (type e))
          :ns (str *ns*)})))

   :cljs
   (defn eval-code
     "Evaluate code string on Scittle using eval_string."
     [code-string]
     (try
       (let [result (.eval_string (.-core js/window.scittle) code-string)]
         {:success true
          :value (pr-str result)
          :ns (str *ns*)})
       (catch :default e
         {:success false
          :error (.-message e)
          :ex (str (type e))
          :ns (str *ns*)}))))

;;; ============================================================
;;; Operation Handlers
;;; ============================================================

(defn handle-eval
  "Handle :eval operation."
  [{:keys [code id session ns]}]
  (let [result (eval-code code)
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
      (let [response (dispatch-request data)]
        (send-fn conn-id (proto/wrap-response response))))))

(defn make-simple-handler
  "Create a simple handler that returns response directly (for testing).

   Returns: Function (fn [request] response)"
  []
  dispatch-request)
