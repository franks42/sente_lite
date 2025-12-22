;;; FakeWebSocket for sente-lite nREPL Browser Adapter (Pure CLJS)
;;;
;;; This creates a FakeWebSocket at window.ws_nrepl that intercepts nREPL
;;; messages and routes them through sente-lite instead of a direct WebSocket.
;;;
;;; ╔═══════════════════════════════════════════════════════════════════════════╗
;;; ║  IMPORTANT: This code should be INLINED in HTML, not loaded as file!     ║
;;; ║  External files with 'src' attribute use async XHR even with             ║
;;; ║  eval_script_tags(), so the order guarantee doesn't work.                 ║
;;; ║                                                                           ║
;;; ║  CORRECT USAGE (inline in HTML):                                          ║
;;; ║  <script src="scittle.js"></script>                                       ║
;;; ║  <script type="application/x-scittle">                                    ║
;;; ║    ;; Paste FakeWebSocket code inline here                                ║
;;; ║  </script>                                                                ║
;;; ║  <script>scittle.core.eval_script_tags();</script> <!-- Force eval! -->  ║
;;; ║  <script src="scittle.nrepl.js"></script>          <!-- AFTER! -->        ║
;;; ║                                                                           ║
;;; ║  See: test/test_browser_adapter.html for working example                  ║
;;; ╚═══════════════════════════════════════════════════════════════════════════╝

(ns nrepl-sente.fake-websocket
  "Creates FakeWebSocket for hijacking scittle.nrepl.js")

(js/console.log "[fake-ws] Initializing FakeWebSocket for sente-lite integration")

;;; ============================================================
;;; FakeWebSocket Implementation
;;; ============================================================

(defonce !state
  (atom {:ready-state 1  ; WebSocket.OPEN
         :onmessage nil
         :onerror nil
         :onclose nil
         :onopen nil
         :pending-inbound []
         :send-fn nil}))

(defn- create-fake-websocket
  "Create a FakeWebSocket JS object that mimics the WebSocket API."
  []
  (let [obj (js-obj)]

    ;; readyState property (scittle.nrepl checks this)
    (js/Object.defineProperty obj "readyState"
      #js {:get (fn [] (:ready-state @!state))
           :configurable true})

    ;; onmessage - scittle.nrepl.js sets this
    (js/Object.defineProperty obj "onmessage"
      #js {:get (fn [] (:onmessage @!state))
           :set (fn [f]
                  (swap! !state assoc :onmessage f)
                  (js/console.log "[fake-ws] onmessage handler attached"))
           :configurable true})

    ;; onerror
    (js/Object.defineProperty obj "onerror"
      #js {:get (fn [] (:onerror @!state))
           :set (fn [f] (swap! !state assoc :onerror f))
           :configurable true})

    ;; onclose
    (js/Object.defineProperty obj "onclose"
      #js {:get (fn [] (:onclose @!state))
           :set (fn [f] (swap! !state assoc :onclose f))
           :configurable true})

    ;; onopen
    (js/Object.defineProperty obj "onopen"
      #js {:get (fn [] (:onopen @!state))
           :set (fn [f] (swap! !state assoc :onopen f))
           :configurable true})

    ;; send() - Called by sci.nrepl.server to send responses
    (aset obj "send"
      (fn [data]
        (if-let [send-fn (:send-fn @!state)]
          (send-fn data)
          (js/console.warn "[fake-ws] send() called but send-fn not set:" data))))

    ;; injectMessage() - Called by sente-lite when nREPL request arrives
    (aset obj "injectMessage"
      (fn [data]
        (if-let [onmessage (:onmessage @!state)]
          (onmessage #js {:data data})
          (swap! !state update :pending-inbound conj data))))

    ;; flushPending() - Process queued messages
    (aset obj "flushPending"
      (fn []
        (let [pending (:pending-inbound @!state)]
          (swap! !state assoc :pending-inbound [])
          (when-let [onmessage (:onmessage @!state)]
            (doseq [data pending]
              (onmessage #js {:data data}))))))

    ;; setSendFn() - Called by browser_adapter.cljs
    (aset obj "setSendFn"
      (fn [f]
        (swap! !state assoc :send-fn f)
        (js/console.log "[fake-ws] Send function registered")))

    ;; close()
    (aset obj "close"
      (fn []
        (js/console.log "[fake-ws] close() called")
        (swap! !state assoc :ready-state 3)  ; WebSocket.CLOSED
        (when-let [onclose (:onclose @!state)]
          (onclose #js {:code 1000 :reason "Normal closure"}))))

    obj))

;;; ============================================================
;;; Install FakeWebSocket
;;; ============================================================

(defn install!
  "Install the FakeWebSocket at window.ws_nrepl.
   Call this BEFORE scittle.nrepl.js loads.
   Safe to call multiple times - only installs once."
  []
  (if (aget js/window "ws_nrepl")
    (js/console.log "[fake-ws] window.ws_nrepl already installed, skipping")
    (let [ws (create-fake-websocket)]
      (aset js/window "ws_nrepl" ws)
      (js/console.log "[fake-ws] window.ws_nrepl installed"))))

;; Install immediately when this namespace loads (idempotent)
(install!)

(js/console.log "[fake-ws] Ready - scittle.nrepl.js can now be loaded")
