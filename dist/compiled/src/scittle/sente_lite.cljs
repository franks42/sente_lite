(ns scittle.sente-lite
  "Scittle plugin for sente-lite + nREPL browser adapter.

   This plugin registers the following namespaces with SCI:
   - sente-lite.client-scittle  (WebSocket client)
   - sente-lite.registry        (State registry)
   - nrepl-sente.protocol       (nREPL message protocol)
   - nrepl-sente.browser-adapter (Browser-side nREPL adapter)

   Usage in HTML:
     <script src=\"scittle.js\"></script>
     <script src=\"scittle.sente-lite.js\"></script>
     <script>scittle.core.eval_script_tags();</script>

   Then in ClojureScript:
     (require '[sente-lite.client-scittle :as client])
     (require '[sente-lite.registry :as reg])
     (require '[nrepl-sente.browser-adapter :as adapter])"
  (:require [sci.core :as sci]
            ;; Import the actual implementations
            [sente-lite.client-scittle :as client]
            [sente-lite.registry :as reg]
            [sente-lite.packer :as packer]
            [nrepl-sente.protocol :as proto]
            [nrepl-sente.browser-adapter :as adapter]))

;; =============================================================================
;; SCI Namespace Definitions
;; =============================================================================

;; --- sente-lite.packer ---
(def packer-ns (sci/create-ns 'sente-lite.packer nil))

(def packer-namespace
  {'pack   (sci/copy-var packer/pack packer-ns)
   'unpack (sci/copy-var packer/unpack packer-ns)})

;; --- sente-lite.client-scittle ---
(def client-ns (sci/create-ns 'sente-lite.client-scittle nil))

(def client-namespace
  {'make-client!   (sci/copy-var client/make-client! client-ns)
   'send!          (sci/copy-var client/send! client-ns)
   'close!         (sci/copy-var client/close! client-ns)
   'on!            (sci/copy-var client/on! client-ns)
   'off!           (sci/copy-var client/off! client-ns)
   'take!          (sci/copy-var client/take! client-ns)
   'get-status     (sci/copy-var client/get-status client-ns)
   'get-stats      (sci/copy-var client/get-stats client-ns)
   'get-uid        (sci/copy-var client/get-uid client-ns)
   'list-clients   (sci/copy-var client/list-clients client-ns)
   'set-reconnect! (sci/copy-var client/set-reconnect! client-ns)
   'subscribe!     (sci/copy-var client/subscribe! client-ns)
   'unsubscribe!   (sci/copy-var client/unsubscribe! client-ns)
   'publish!       (sci/copy-var client/publish! client-ns)
   'queue-stats    (sci/copy-var client/queue-stats client-ns)
   'rpc-waiter     (sci/copy-var client/rpc-waiter client-ns)
   'handler-count  (sci/copy-var client/handler-count client-ns)})

;; --- sente-lite.registry ---
(def registry-ns (sci/create-ns 'sente-lite.registry nil))

(def registry-namespace
  {'register!              (sci/copy-var reg/register! registry-ns)
   'ensure!                (sci/copy-var reg/ensure! registry-ns)
   'get-value              (sci/copy-var reg/get-value registry-ns)
   'set-value!             (sci/copy-var reg/set-value! registry-ns)
   'swap-value!            (sci/copy-var reg/swap-value! registry-ns)
   'get-ref                (sci/copy-var reg/get-ref registry-ns)
   'set-ref!               (sci/copy-var reg/set-ref! registry-ns)
   'swap-ref!              (sci/copy-var reg/swap-ref! registry-ns)
   'resolve-ref            (sci/copy-var reg/resolve-ref registry-ns)
   'registered?            (sci/copy-var reg/registered? registry-ns)
   'list-registered        (sci/copy-var reg/list-registered registry-ns)
   'list-registered-prefix (sci/copy-var reg/list-registered-prefix registry-ns)
   'unregister!            (sci/copy-var reg/unregister! registry-ns)
   'unregister-prefix!     (sci/copy-var reg/unregister-prefix! registry-ns)
   'watch!                 (sci/copy-var reg/watch! registry-ns)
   'watch-resolved!        (sci/copy-var reg/watch-resolved! registry-ns)
   'unwatch!               (sci/copy-var reg/unwatch! registry-ns)})

;; --- nrepl-sente.protocol ---
;; Only constants, no functions
(def protocol-ns (sci/create-ns 'nrepl-sente.protocol nil))

(def protocol-namespace
  {'request-event-id  proto/request-event-id   ; :nrepl/request
   'response-event-id proto/response-event-id  ; :nrepl/response
   'ops               proto/ops})

;; --- nrepl-sente.browser-adapter ---
(def adapter-ns (sci/create-ns 'nrepl-sente.browser-adapter nil))

(def adapter-namespace
  {'connect!    (sci/copy-var adapter/connect! adapter-ns)
   'disconnect! (sci/copy-var adapter/disconnect! adapter-ns)
   'status      (sci/copy-var adapter/status adapter-ns)
   'eval-code!  (sci/copy-var adapter/eval-code! adapter-ns)})

;; =============================================================================
;; Combined SCI Configuration
;; =============================================================================

(def config
  {:namespaces
   {'sente-lite.packer           packer-namespace
    'sente-lite.client-scittle   client-namespace
    'sente-lite.registry         registry-namespace
    'nrepl-sente.protocol        protocol-namespace
    'nrepl-sente.browser-adapter adapter-namespace}})

;; =============================================================================
;; Plugin Registration
;; =============================================================================

(defn register! []
  (when-let [register-fn (and (exists? js/scittle)
                              (aget js/scittle "core" "register_plugin_BANG_"))]
    (register-fn "sente-lite" (clj->js config))
    (js/console.log "[scittle.sente-lite] Plugin registered successfully"))
  (when-not (exists? js/scittle)
    (js/console.error "[scittle.sente-lite] ERROR: scittle.js must be loaded first!")))

;; Auto-register on load
(register!)
