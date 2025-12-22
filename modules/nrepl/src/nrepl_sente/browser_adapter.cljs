;;; sente-lite nREPL Browser Adapter (Scittle)
;;;
;;; Connects the FakeWebSocket (browser_adapter.js) to sente-lite.
;;; Load this AFTER:
;;;   1. scittle.js
;;;   2. browser_adapter.js
;;;   3. scittle.nrepl.js
;;;   4. sente_lite/client_scittle.cljs

(ns nrepl-sente.browser-adapter
  "Bridges scittle.nrepl with sente-lite for browser-based nREPL."
  (:require [sente-lite.client-scittle :as sente]))

;; State - stores client-id string (not a map)
(defonce !client-id (atom nil))
(defonce !connected (atom false))

(defn log [& args]
  (apply js/console.log "[nrepl-adapter]" (map pr-str args)))

(defn get-ws-nrepl []
  (.-ws_nrepl js/window))

;;; --- Send function for FakeWebSocket ---

(defn send-nrepl-response!
  "Called when sci.nrepl.server sends a response.
   Routes the EDN string through sente-lite."
  [edn-str]
  (if-let [client-id @!client-id]
    (do
      (log "Response:" (subs edn-str 0 (min 60 (count edn-str))))
      ;; Send as :nrepl/response event using sente-lite's send! function
      (sente/send! client-id [:nrepl/response {:edn edn-str}]))
    (log "ERROR: No client-id set!")))

;;; --- Message handler for incoming nREPL requests ---

(defn handle-nrepl-request
  "Called when sente-lite receives an nREPL request from the server.
   Injects it into the FakeWebSocket for sci.nrepl.server to process."
  [data]
  (let [edn-str (get data :edn)]
    (log "Received request:" edn-str)
    (when-let [ws (get-ws-nrepl)]
      (.injectMessage ws edn-str))))

;;; --- Integration with sente-lite ---

(defn setup-message-handler!
  "Register handler for :nrepl/request events using sente/on!"
  [client-id]
  ;; Use sente-lite's on! function with proper options map
  (sente/on! client-id
             {:event-id :nrepl/request
              :callback (fn [msg]
                          (handle-nrepl-request (get msg :data)))})
  (log "Registered :nrepl/request handler for" client-id))

(defn connect!
  "Connect the adapter to a sente-lite client.

   Options:
   - :client - A sente-lite client-id (string returned by make-client!)
   - :on-connect - Callback when connected"
  [{:keys [client on-connect]}]
  (let [ws (get-ws-nrepl)]
    (when-not ws
      (throw (js/Error. "window.ws_nrepl not found. Load browser_adapter.js first!")))

    ;; Set up the send function on FakeWebSocket
    (.setSendFn ws send-nrepl-response!)

    (when client
      ;; Store client-id (string)
      (reset! !client-id client)
      (setup-message-handler! client)
      (reset! !connected true)
      (.flushPending ws)
      (log "Connected to existing sente-lite client")
      (when on-connect (on-connect)))))

(defn disconnect!
  "Disconnect the adapter."
  []
  (reset! !connected false)
  (reset! !client-id nil)
  (log "Disconnected"))

;;; --- Convenience for manual testing ---

(defn eval-code!
  "Convenience function to manually trigger an eval (for testing).
   Normally the server sends eval requests."
  [code & {:keys [id session] :or {id "manual" session "manual"}}]
  (let [msg (str "{:op :eval :code " (pr-str code)
                 " :id " (pr-str id)
                 " :session " (pr-str session) "}")]
    (handle-nrepl-request {:edn msg})))

;;; --- Status ---

(defn status []
  {:connected @!connected
   :ws-nrepl-ready (some? (get-ws-nrepl))
   :onmessage-set (some? (.-onmessage (get-ws-nrepl)))
   :client-id @!client-id})

(log "Browser adapter loaded. Call (connect! {:client your-sente-client}) to activate.")
