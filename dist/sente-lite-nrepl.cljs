;;; sente-lite-nrepl.cljs - Bundled source for Scittle
;;; Generated: 2025-12-22T12:56:41.780984
;;; 
;;; Usage in HTML:
;;;   <script src="scittle.js"></script>
;;;   <script src="scittle.nrepl.js"></script>
;;;   <script src="sente-lite-nrepl.cljs" type="application/x-scittle"></script>
;;;
;;; Then use:
;;;   (require '[sente-lite.client-scittle :as client])
;;;   (require '[nrepl-sente.browser-adapter :as adapter])
;;;


;;; ============================================================
;;; Source: ../src/sente_lite/packer.cljc
;;; ============================================================

(ns sente-lite.packer
  #?(:clj
     (:require [clojure.edn :as edn])
     :bb
     (:require [clojure.edn :as edn])))

(defn pack
  [clj-val]
  (pr-str clj-val))

(defn unpack
  [packed-val]
  #?(:clj (edn/read-string packed-val)
     :bb (edn/read-string packed-val)
     :cljs
     #_{:clj-kondo/ignore [:unresolved-symbol]}
     (read-string packed-val)))


;;; ============================================================
;;; Source: ../src/sente_lite/queue_scittle.cljs
;;; ============================================================

(ns sente-lite.queue-scittle
  "Browser/Scittle send queue implementation using atom + vector.

   Uses atom for thread-safe state and js/setInterval for periodic flush.
   SCI-compatible: no vector destructuring, no type hints.")

;; ============================================================================
;; Note: We inline protocol and helpers here for Scittle compatibility.
;; Scittle can't easily share .cljc protocols, so we define everything inline.
;; ============================================================================

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-config
  {:max-depth 1000
   :flush-interval-ms 10})

(defn merge-config [user-config]
  (merge default-config user-config))

;; ============================================================================
;; Browser Send Queue Implementation
;; ============================================================================

(defn make-stats []
  {:depth 0
   :enqueued 0
   :sent 0
   :dropped 0
   :errors 0})

(defn make-send-queue
  "Create a new browser send queue.

   Options:
     :max-depth         - Maximum queue size (default: 1000)
     :flush-interval-ms - Flush interval in ms (default: 10)
     :on-send           - (required) Function to send message: (fn [msg] ...)
     :on-error          - (optional) Error handler: (fn [error msg] ...)"
  [opts]
  (let [on-send (get opts :on-send)
        on-error (get opts :on-error)
        config (merge-config opts)
        max-depth (get config :max-depth)
        flush-interval-ms (get config :flush-interval-ms)
        ;; State atom holds queue, stats, and waiters
        state (atom {:queue []
                     :stats (make-stats)
                     :running false
                     :interval-id nil
                     :waiters []})]

    (assert on-send ":on-send callback is required")

    ;; Forward declarations for mutual recursion
    (letfn [(enqueue! [msg]
              (let [current-state @state
                    queue-vec (get current-state :queue)
                    queue-size (count queue-vec)]
                (if (>= queue-size max-depth)
                  (do
                    (swap! state update-in [:stats :dropped] inc)
                    :rejected)
                  (do
                    (swap! state update :queue conj msg)
                    (swap! state update-in [:stats :enqueued] inc)
                    (swap! state update-in [:stats :depth] inc)
                    :ok))))

            (process-waiters! []
              ;; Process waiting async enqueue requests when space available
              (loop []
                (let [current-state @state
                      queue-vec (get current-state :queue)
                      queue-size (count queue-vec)
                      waiters-vec (get current-state :waiters)]
                  (when (and (< queue-size max-depth)
                             (pos? (count waiters-vec)))
                    ;; Take first waiter
                    (let [waiter (first waiters-vec)
                          msg (get waiter :msg)
                          callback (get waiter :callback)
                          timeout-id (get waiter :timeout-id)]
                      ;; Remove waiter from list
                      (swap! state update :waiters rest)
                      ;; Clear timeout
                      (when timeout-id
                        (js/clearTimeout timeout-id))
                      ;; Try enqueue
                      (let [result (enqueue! msg)]
                        (if (= result :ok)
                          (do
                            (callback :ok)
                            ;; Continue processing if more waiters and space
                            (recur))
                          ;; Should not happen (we checked space), but handle gracefully
                          (callback :rejected))))))))

            (flush! []
              (let [current-state @state
                    queue-vec (get current-state :queue)]
                (when (pos? (count queue-vec))
                  ;; Atomically take all messages
                  (swap! state assoc :queue [])
                  ;; Send each message
                  (doseq [msg queue-vec]
                    (try
                      (on-send msg)
                      (swap! state update-in [:stats :sent] inc)
                      (swap! state update-in [:stats :depth] dec)
                      (catch :default e
                        (swap! state update-in [:stats :errors] inc)
                        (when on-error
                          (on-error e msg)))))
                  ;; After flush, process any waiters (space now available)
                  (process-waiters!))))

            (start! []
              (when-not (get @state :running)
                ;; Flush immediately on start (like BB does)
                (flush!)
                (let [interval-id (js/setInterval (fn [] (flush!)) flush-interval-ms)]
                  (swap! state assoc
                         :running true
                         :interval-id interval-id))))

            (stop! []
              (let [current-state @state
                    interval-id (get current-state :interval-id)]
                ;; Clear interval
                (when interval-id
                  (js/clearInterval interval-id))
                (swap! state assoc :running false :interval-id nil)
                ;; Drain remaining messages
                (flush!)
                ;; Cancel all pending waiters with :timeout
                (let [waiters-vec (get @state :waiters)]
                  (doseq [waiter waiters-vec]
                    (let [timeout-id (get waiter :timeout-id)
                          callback (get waiter :callback)]
                      (when timeout-id
                        (js/clearTimeout timeout-id))
                      (callback :timeout)))
                  (swap! state assoc :waiters []))
                ;; Return final stats
                (get @state :stats)))

            (queue-stats []
              (let [current-state @state
                    stats (get current-state :stats)
                    queue-vec (get current-state :queue)]
                (assoc stats :depth (count queue-vec))))

            (enqueue-blocking! [_msg _timeout-ms]
              ;; Not supported in browser - JS is single-threaded
              (throw (js/Error. "enqueue-blocking! not supported in browser (JS is single-threaded)")))

            (enqueue-async! [msg opts]
              (let [timeout-ms (get opts :timeout-ms 30000)
                    callback (get opts :callback)]
                (assert callback ":callback is required for enqueue-async!")
                ;; Try immediate enqueue first
                (let [result (enqueue! msg)]
                  (if (= result :ok)
                    ;; Immediate success
                    (callback :ok)
                    ;; Register waiter (event-driven, no polling)
                    (let [deadline (+ (.now js/Date) timeout-ms)
                          waiter-atom (atom nil)
                          timeout-id (js/setTimeout
                                      (fn []
                                        ;; Timeout reached - remove waiter and notify
                                        (swap! state update :waiters
                                               (fn [waiters]
                                                 (remove (fn [w] (= w @waiter-atom)) waiters)))
                                        (callback :timeout))
                                      timeout-ms)
                          waiter {:msg msg
                                  :callback callback
                                  :deadline deadline
                                  :timeout-id timeout-id}]
                      (reset! waiter-atom waiter)
                      (swap! state update :waiters conj waiter))))))]

      ;; Return queue object as a map with functions
      {:enqueue! enqueue!
       :enqueue-blocking! enqueue-blocking!
       :enqueue-async! enqueue-async!
       :start! start!
       :stop! stop!
       :queue-stats queue-stats
       :state state})))

;; ============================================================================
;; Convenience functions for protocol-like access
;; ============================================================================

(defn enqueue! [queue msg]
  ((get queue :enqueue!) msg))

(defn start! [queue]
  ((get queue :start!))
  queue)

(defn stop! [queue]
  ((get queue :stop!)))

(defn queue-stats [queue]
  ((get queue :queue-stats)))

(defn enqueue-blocking! [queue msg timeout-ms]
  ((get queue :enqueue-blocking!) msg timeout-ms))

(defn enqueue-async! [queue msg opts]
  ((get queue :enqueue-async!) msg opts))


;;; ============================================================
;;; Source: ../src/sente_lite/client_scittle.cljs
;;; ============================================================

(ns sente-lite.client-scittle
  "Lightweight WebSocket client for Scittle/browser with Sente-compatible wire format.

  Provides sente-like API for browser environments:
  - Native WebSocket (no dependencies)
  - Sente-compatible wire format: [event-id data]
  - Simple callback-based API (no core.async)
  - Automatic reconnection with backoff

  Usage:
    (require '[sente-lite.client-scittle :as sente])

    (def client (sente/make-client!
                  {:url \"ws://localhost:3000/ws\"
                   :on-message (fn [event-id data] (println \"Received:\" event-id data))
                   :on-open (fn [uid] (println \"Connected as\" uid))
                   :on-close (fn [event] (println \"Disconnected\"))}))

    (sente/send! client :my/event {:data \"value\"})
    (sente/subscribe! client \"my-channel\")
    (sente/close! client)
  
  NOTE: SCI/Scittle requires macros to be referred directly, not namespace-qualified."
  (:require [taoensso.trove :as trove :refer [log!]]
            [sente-lite.packer :as packer]
            [sente-lite.queue-scittle :as q]))

;; Event IDs (Sente-compatible)
(def ^:const event-handshake :chsk/handshake)
(def ^:const event-ws-ping :chsk/ws-ping)
(def ^:const event-ws-pong :chsk/ws-pong)
(def ^:const event-subscribe :sente-lite/subscribe)
(def ^:const event-unsubscribe :sente-lite/unsubscribe)
(def ^:const event-publish :sente-lite/publish)

(defn- system-event-id?
  [event-id]
  (and (keyword? event-id)
       (= "chsk" (namespace event-id))))

(defn- normalize-recv
  [event-id data config]
  (let [wrap? (get config :wrap-recv-evs? false)]
    (cond
      (and (not wrap?)
           (= event-id :chsk/recv)
           (vector? data)
           (keyword? (first data)))
      [(first data) (second data)]

      (and wrap?
           (not= event-id :chsk/recv)
           (not (system-event-id? event-id)))
      [:chsk/recv [event-id data]]

      :else
      [event-id data])))

;;; State Management

(defonce ^:private clients (atom {}))  ; client-id -> client-state

(defn- generate-client-id []
  (str "client-" (.now js/Date) "-" (rand-int 10000)))

(defn- generate-handler-id []
  (str "h-" (.now js/Date) "-" (rand-int 10000)))

;;; Client State

(defn- make-client-state [config]
  {:id (generate-client-id)
   :config config
   :ws nil
   :status :disconnected
   :uid nil                    ; Server-assigned user ID from handshake
   :reconnect-count 0
   :reconnect-enabled? (get config :auto-reconnect? true)  ; default true
   :reconnect-delay (get config :reconnect-delay 1000)     ; default 1s
   :max-reconnect-delay (get config :max-reconnect-delay 30000)  ; default 30s
   :last-connect-attempt nil
   :message-count-sent 0
   :message-count-received 0
   :send-queue nil
   :handlers (atom {})})

;;; Telemetry - uses Trove event ID pattern (:sente-lite.client/*)

;;; WebSocket Lifecycle

(defn- handle-open [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)]
    (log! {:level :debug
           :id :sente-lite.client/ws-connected
           :data {:client-id client-id
                  :url (:url config)
                  :ready-state (.. event -target -readyState)}})
    (swap! clients assoc-in [client-id :status] :connected)
    ;; Note: on-open/on-reconnect callbacks are called after handshake in handle-message,
    ;; not here, so the user gets the uid from the server.
    ))

(defn- parse-message
  "Parse message - expects EDN event vector format [event-id data]"
  [raw-data]
  (try
    (let [parsed (packer/unpack raw-data)]
      (if (vector? parsed)
        {:event-id (first parsed)
         :data (second parsed)}
        {:error :invalid-format :raw raw-data}))
    (catch js/Error e
      (log! {:level :warn
             :id :sente-lite.client/parse-failed
             :data {:raw-data raw-data
                    :error (.-message e)}})
      {:error :parse-failed :raw raw-data})))

(defn- send-event!
  "Send an event vector directly (packs to EDN)"
  [ws event]
  (.send ws (packer/pack event)))

(defn- send-raw!
  "Internal: Send serialized message directly over WebSocket (bypassing queue).
   Used by queue flush. Takes [serialized message] tuple.
   Throws on failure so queue can track as error (not silent loss)."
  [client-id serialized message]
  (if-let [client-state (get @clients client-id)]
    (let [ws (get client-state :ws)
          ready-state (if ws (.-readyState ws) -1)]
      (if (= ready-state 1) ; WebSocket.OPEN = 1
        (do
          (.send ws serialized)
          (swap! clients update-in [client-id :message-count-sent] inc)
          (log! {:level :trace
                 :id :sente-lite.client/msg-sent
                 :data {:client-id client-id
                        :message-type (first message)
                        :size (count serialized)}})
          true)
        (do
          (log! {:level :warn
                 :id :sente-lite.client/send-failed
                 :data {:client-id client-id
                        :status (get client-state :status)
                        :ready-state ready-state}})
          (throw (js/Error. (str "WebSocket not open (state=" ready-state ")"))))))
    (throw (js/Error. "Client not found"))))

;;; Handler Registry Dispatch

(defn- handler-matches?
  "Check if a handler matches a message."
  [handler msg]
  (let [pred (get handler :pred)
        handler-event-id (get handler :event-id)
        msg-event-id (get msg :event-id)]
    (cond
      pred (pred msg)
      (= handler-event-id :*) true
      handler-event-id (= handler-event-id msg-event-id)
      :else false)))

(defn- dispatch-to-handlers!
  "Dispatch message to all matching handlers. Removes :once? handlers after match."
  [client-id msg]
  (when-let [client-state (get @clients client-id)]
    (let [handlers-atom (get client-state :handlers)
          handlers-map @handlers-atom]
      (doseq [entry handlers-map]
        (let [handler-id (first entry)
              handler (second entry)]
          (when (handler-matches? handler msg)
            (try
              ((get handler :callback) msg)
              ;; Remove once? handlers after match
              (when (get handler :once?)
                ;; Cancel timeout if present
                (when-let [timeout-id (get handler :timeout-id)]
                  (js/clearTimeout timeout-id))
                (swap! handlers-atom dissoc handler-id))
              (catch :default e
                (log! {:level :error
                       :id :sente-lite.client/handler-error
                       :data {:client-id client-id
                              :handler-id handler-id
                              :event-id (get msg :event-id)
                              :error (.-message e)}})))))))))

(defn- handle-handshake
  "Handle :chsk/handshake event - extract uid and store it"
  [client-id data ws]
  (let [uid (first data)
        csrf-token (second data)]
    (swap! clients assoc-in [client-id :uid] uid)
    (log! {:level :info
           :id :sente-lite.client/handshake-received
           :data {:client-id client-id
                  :uid uid
                  :has-csrf (some? csrf-token)}})
    uid))

(defn- handle-message [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        ws (:ws client-state)
        raw-data (.-data event)
        parsed (parse-message raw-data)]

    (swap! clients update-in [client-id :message-count-received] inc)

    (if (:error parsed)
      (log! {:level :warn
             :id :sente-lite.client/msg-error
             :data {:client-id client-id
                    :error (:error parsed)}})
      (let [[event-id data] (normalize-recv (:event-id parsed) (:data parsed) config)]
        (log! {:level :trace
               :id :sente-lite.client/msg-recv
               :data {:client-id client-id
                      :event-id event-id
                      :message-size (.-length raw-data)}})

        (cond
          ;; Handle handshake
          (= event-id event-handshake)
          (let [uid (handle-handshake client-id data ws)
                ;; Get fresh state to check reconnect count
                current-state (get @clients client-id)
                is-reconnect? (> (get current-state :reconnect-count 0) 0)]
            (if is-reconnect?
              (when-let [on-reconnect (:on-reconnect config)]
                (log! {:level :trace
                       :id :sente-lite.client/callback-on-reconnect
                       :data {:client-id client-id :uid uid}})
                (on-reconnect))
              (when-let [on-open (:on-open config)]
                (log! {:level :trace
                       :id :sente-lite.client/callback-on-open
                       :data {:client-id client-id :uid uid}})
                (on-open uid)))
            ;; Call :on-channel-ready on EVERY connection (initial + reconnect)
            (when-let [on-channel-ready (:on-channel-ready config)]
              (log! {:level :trace
                     :id :sente-lite.client/callback-on-channel-ready
                     :data {:client-id client-id :uid uid :is-reconnect? is-reconnect?}})
              (on-channel-ready client-id)))

          ;; Handle server ping -> respond with pong
          (= event-id event-ws-ping)
          (do
            (log! {:level :trace
                   :id :sente-lite.client/auto-pong
                   :data {:client-id client-id}})
            (send-event! ws [event-ws-pong]))

          ;; User messages: dispatch to unified handler registry
          :else
          (let [msg {:event-id event-id :data data}]
            (dispatch-to-handlers! client-id msg)))))))

(defn- handle-error [client-state event]
  (let [client-id (:id client-state)
        ws (.-target event)]
    (log! {:level :error
           :id :sente-lite.client/ws-error
           :data {:client-id client-id
                  :ready-state (.-readyState ws)}})))

(declare attempt-reconnect!)  ; forward declaration
(declare on!)  ; forward declaration for take!

(defn- notify-once-handlers-closed!
  "Notify all :once? handlers that connection closed, and remove them."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (let [handlers-atom (get client-state :handlers)
          handlers-map @handlers-atom
          once-handlers (filter (fn [entry]
                                  (let [handler (second entry)]
                                    (get handler :once?)))
                                handlers-map)]
      (doseq [entry once-handlers]
        (let [handler-id (first entry)
              handler (second entry)]
          ;; Cancel timeout if present
          (when-let [timeout-id (get handler :timeout-id)]
            (js/clearTimeout timeout-id))
          ;; Notify callback
          (try
            ((get handler :callback) {:error :closed :reason :disconnected})
            (catch :default e
              (log! {:level :error
                     :id :sente-lite.client/notify-close-error
                     :data {:client-id client-id
                            :handler-id handler-id
                            :error (.-message e)}})))
          ;; Remove handler
          (swap! handlers-atom dissoc handler-id)))
      (when (pos? (count once-handlers))
        (log! {:level :debug
               :id :sente-lite.client/once-handlers-notified
               :data {:client-id client-id
                      :count (count once-handlers)}})))))

(defn- handle-close [client-state event]
  (let [client-id (:id client-state)]
    ;; Only process if client still exists (not removed by close!)
    (when (get @clients client-id)
      (let [config (:config client-state)
            code (.-code event)
            reason (.-reason event)
            was-clean (.-wasClean event)
            reconnect-enabled? (:reconnect-enabled? client-state)]
        (swap! clients assoc-in [client-id :status] :disconnected)
        (log! {:level :debug
               :id :sente-lite.client/disconnected
               :data {:client-id client-id
                      :code code
                      :reason reason
                      :was-clean was-clean
                      :will-reconnect? reconnect-enabled?}})

        ;; Notify all :once? handlers that connection closed
        (notify-once-handlers-closed! client-id)

        (when-let [on-close (:on-close config)]
          (on-close event))

        ;; Auto-reconnect if enabled
        (when reconnect-enabled?
          (let [current-client-state (get @clients client-id)
                delay-ms (:reconnect-delay current-client-state)
                reconnect-count (:reconnect-count current-client-state)]
            (log! {:level :debug
                   :id :sente-lite.client/reconnect-scheduled
                   :data {:client-id client-id
                          :delay-ms delay-ms
                          :reconnect-count reconnect-count}})
            (js/setTimeout #(attempt-reconnect! client-id) delay-ms)))))))

;;; Reconnection Logic

(defn- attempt-reconnect! [client-id]
  (when-let [client-state (get @clients client-id)]
    (when (:reconnect-enabled? client-state)
      (let [config (:config client-state)
            url (:url config)
            reconnect-count (:reconnect-count client-state)
            new-reconnect-count (inc reconnect-count)]

        (log! {:level :debug
               :id :sente-lite.client/reconnect-attempt
               :data {:client-id client-id
                      :reconnect-count new-reconnect-count
                      :url url}})

        (try
          ;; Increment reconnect count BEFORE creating WebSocket
          (swap! clients update-in [client-id :reconnect-count] inc)

          ;; Calculate exponential backoff for NEXT reconnection
          (let [base-delay (:reconnect-delay client-state)
                max-delay (:max-reconnect-delay client-state)
                next-delay (min (* base-delay (Math/pow 2 new-reconnect-count)) max-delay)]
            (swap! clients assoc-in [client-id :reconnect-delay] next-delay))

          ;; Create new WebSocket
          (let [ws (js/WebSocket. url)
                updated-client-state (get @clients client-id)]

            ;; Store new ws in client state
            (swap! clients assoc-in [client-id :ws] ws)

            ;; Setup handlers with updated client state
            (set! (.-onopen ws) (partial handle-open updated-client-state))
            (set! (.-onmessage ws) (partial handle-message updated-client-state))
            (set! (.-onerror ws) (partial handle-error updated-client-state))
            (set! (.-onclose ws) (partial handle-close updated-client-state))

            (log! {:level :trace
                   :id :sente-lite.client/reconnect-initiated
                   :data {:client-id client-id}}))

          (catch js/Error e
            (log! {:level :error
                   :id :sente-lite.client/reconnect-failed
                   :data {:client-id client-id
                          :error (.-message e)
                          :reconnect-count new-reconnect-count}})
            ;; Failed reconnect - try again after delay if still enabled
            (when (get-in @clients [client-id :reconnect-enabled?])
              (let [retry-delay (get-in @clients [client-id :reconnect-delay])]
                (log! {:level :debug
                       :id :sente-lite.client/reconnect-retry
                       :data {:client-id client-id
                              :retry-delay retry-delay}})
                (js/setTimeout #(attempt-reconnect! client-id) retry-delay)))))))))

;;; Public API

(defn make-client!
  "Create and connect a WebSocket client with auto-reconnect and telemetry.

  Config options:
    :url                  - WebSocket URL (required, e.g. \"ws://localhost:3000/ws\")
    :on-open              - Called on initial connection (fn [uid])
    :on-reconnect         - Called after reconnection (fn [])
    :on-channel-ready     - Called on EVERY connection (initial + reconnect) after handshake.
                            Use this to register waiters fresh each time. (fn [client-id])
    :on-message           - Called with parsed message (fn [event-id data])
                            Registered internally as catch-all handler via on!
    :on-close             - Called when connection closes (fn [event])
    :on-error             - Called on error (fn [event])
    :auto-reconnect?      - Enable auto-reconnect (default: true)
    :reconnect-delay      - Initial reconnect delay in ms (default: 1000)
    :max-reconnect-delay  - Maximum reconnect delay in ms (default: 30000)
    :send-queue           - Send queue config map (optional):
                            {:max-depth 1000          ; max queued messages
                             :flush-interval-ms 10}   ; flush interval

  Returns client-id handle for send!/close!/set-reconnect!/take!/on!/off! operations."
  [config]
  (let [client-state (make-client-state config)
        client-id (get client-state :id)
        url (get config :url)
        send-queue-config (get config :send-queue)
        on-message-fn (get config :on-message)
        ws (js/WebSocket. url)]

    (log! {:level :debug
           :id :sente-lite.client/creating
           :data {:client-id client-id
                  :url url
                  :send-queue (some? send-queue-config)
                  :on-message (some? on-message-fn)
                  :initial-state (.-readyState ws)}})

    ;; Store client state
    (swap! clients assoc client-id (assoc client-state :ws ws))

    ;; Setup handlers
    (set! (.-onopen ws) (partial handle-open (get @clients client-id)))
    (set! (.-onmessage ws) (partial handle-message (get @clients client-id)))
    (set! (.-onerror ws) (partial handle-error (get @clients client-id)))
    (set! (.-onclose ws) (partial handle-close (get @clients client-id)))

    ;; Register :on-message as catch-all handler if provided
    (when on-message-fn
      (let [handlers-atom (get (get @clients client-id) :handlers)
            handler-id (str "on-message-" (generate-handler-id))]
        (swap! handlers-atom assoc handler-id
               {:id handler-id
                :event-id :*
                :callback (fn [msg]
                            (on-message-fn (get msg :event-id) (get msg :data)))
                :once? false})
        (log! {:level :debug
               :id :sente-lite.client/on-message-registered
               :data {:client-id client-id
                      :handler-id handler-id}})))

    ;; Create and start send queue if configured
    (when send-queue-config
      (let [queue (q/make-send-queue
                   (merge send-queue-config
                          {:on-send (fn [msg]
                                      ;; msg is [serialized message] tuple
                                      (let [serialized (first msg)
                                            message (second msg)]
                                        (send-raw! client-id serialized message)))
                           :on-error (fn [e msg]
                                       (let [message (second msg)]
                                         (log! {:level :error
                                                :id :sente-lite.client/queue-send-error
                                                :data {:client-id client-id
                                                       :message-type (first message)
                                                       :error (str e)}})))}))]
        (swap! clients assoc-in [client-id :send-queue] queue)
        (q/start! queue)
        (log! {:level :debug
               :id :sente-lite.client/queue-started
               :data {:client-id client-id
                      :max-depth (get send-queue-config :max-depth 1000)
                      :flush-interval-ms (get send-queue-config :flush-interval-ms 10)}})))

    (log! {:level :trace
           :id :sente-lite.client/handlers-attached
           :data {:client-id client-id}})

    ;; Return client-id as handle
    client-id))

(defn send!
  "Send message through client. Message should be an event vector [event-id data].

  If send-queue is configured:
    Returns :ok if message was queued, :rejected if queue is full.
    Message will be sent asynchronously by the background flush timer.

  If no send-queue (direct send):
    Returns true if sent immediately, false if failed.

  Example:
    (send! client [:my/event {:data \"value\"}])"
  [client-id message]
  (if-let [client-state (get @clients client-id)]
    (let [send-queue (get client-state :send-queue)]
      (if send-queue
        ;; Queue-based sending
        (let [serialized (packer/pack message)
              result (q/enqueue! send-queue [serialized message])]
          (when (= result :rejected)
            (log! {:level :warn
                   :id :sente-lite.client/queue-full
                   :data {:client-id client-id
                          :message-type (first message)}}))
          result)
        ;; Direct sending (no queue)
        (let [ws (get client-state :ws)
              ready-state (if ws (.-readyState ws) -1)]
          (if (= ready-state 1) ; WebSocket.OPEN = 1
            (let [serialized (packer/pack message)]
              (.send ws serialized)
              (swap! clients update-in [client-id :message-count-sent] inc)
              (log! {:level :trace
                     :id :sente-lite.client/msg-sent
                     :data {:client-id client-id
                            :message-type (first message)
                            :size (count serialized)}})
              true)
            (do
              (log! {:level :warn
                     :id :sente-lite.client/send-failed
                     :data {:client-id client-id
                            :ready-state ready-state
                            :status (get client-state :status)}})
              false)))))
    (do
      (log! {:level :error
             :id :sente-lite.client/invalid-client-id
             :data {:client-id client-id}})
      false)))

(defn close!
  "Close WebSocket connection gracefully. Stops send queue and drains remaining messages."
  [client-id]
  (if-let [client-state (get @clients client-id)]
    (let [ws (get client-state :ws)
          send-queue (get client-state :send-queue)]
      ;; Stop queue first (drains remaining messages)
      (when send-queue
        (let [final-stats (q/stop! send-queue)]
          (log! {:level :debug
                 :id :sente-lite.client/queue-stopped
                 :data {:client-id client-id
                        :final-stats final-stats}})))
      ;; Remove client from registry to prevent on-close from re-adding
      (swap! clients dissoc client-id)
      (when ws
        (log! {:level :debug
               :id :sente-lite.client/closing
               :data {:client-id client-id}})
        (.close ws))
      true)
    (do
      (log! {:level :warn
             :id :sente-lite.client/close-failed
             :data {:client-id client-id}})
      false)))

(defn get-status
  "Get current client status. Returns :connected, :disconnected, or nil if invalid client-id."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (:status client-state)))

(defn get-stats
  "Get client statistics including message counts."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    {:client-id client-id
     :status (:status client-state)
     :messages-sent (:message-count-sent client-state)
     :messages-received (:message-count-received client-state)
     :reconnect-count (:reconnect-count client-state)}))

(defn list-clients
  "List all active client IDs."
  []
  (keys @clients))

(defn set-reconnect!
  "Enable or disable auto-reconnect for a client.
  Useful for stopping reconnection attempts when shutting down."
  [client-id enabled?]
  (if-let [client-state (get @clients client-id)]
    (do
      (swap! clients assoc-in [client-id :reconnect-enabled?] enabled?)
      (log! {:level :debug
             :id :sente-lite.client/reconnect-setting-updated
             :data {:client-id client-id
                    :enabled? enabled?}})
      true)
    (do
      (log! {:level :warn
             :id :sente-lite.client/reconnect-setting-failed
             :data {:client-id client-id}})
      false)))

;;; Channel/Pub-Sub API (event vector format)

(defn subscribe!
  "Subscribe to a channel. Returns true if message was sent.
  
  Example:
    (subscribe! client \"my-channel\")"
  [client-id channel-id]
  (send! client-id [event-subscribe {:channel-id channel-id}]))

(defn unsubscribe!
  "Unsubscribe from a channel. Returns true if message was sent.
  
  Example:
    (unsubscribe! client \"my-channel\")"
  [client-id channel-id]
  (send! client-id [event-unsubscribe {:channel-id channel-id}]))

(defn publish!
  "Publish a message to a channel. Returns true if message was sent.
  
  Example:
    (publish! client \"my-channel\" {:msg \"Hello!\"})
    (publish! client \"my-channel\" {:msg \"Hello!\"} :exclude-sender? true)"
  [client-id channel-id data & {:keys [exclude-sender?] :or {exclude-sender? false}}]
  (send! client-id [event-publish {:channel-id channel-id
                                   :data data
                                   :exclude-sender? exclude-sender?}]))

(defn get-uid
  "Get the server-assigned user ID for this client.
  Returns nil if not yet connected or handshake not received."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (get client-state :uid)))

(defn queue-stats
  "Get send queue statistics. Returns nil if no queue configured.
  Stats include: :depth :enqueued :sent :dropped :errors"
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (when-let [send-queue (get client-state :send-queue)]
      (q/queue-stats send-queue))))

;;; Receive Queue / RPC API

(defn take!
  "Register a one-shot handler for a message matching the predicate.
  This is a convenience wrapper around on! with :once? true.

  IMPORTANT: Register the handler BEFORE sending the request to ensure
  you don't miss the response.

  Options:
    :pred        - Predicate function (fn [msg] -> bool). msg is {:event-id :data}
    :event-id    - Event ID to match (alternative to :pred)
    :callback    - Called when message matches or timeout/close occurs
    :timeout-ms  - Optional timeout in milliseconds

  Returns handler-id (can be used with off! to cancel).

  The callback receives either:
  - The matching message: {:event-id :some/event :data {...}}
  - On timeout: {:error :timeout}
  - On disconnect: {:error :closed :reason :disconnected}

  Example:
    (take! client {:pred #(= (:event-id %) :my/response)
                   :timeout-ms 5000
                   :callback (fn [msg]
                               (if (:error msg)
                                 (println \"Error:\" msg)
                                 (println \"Got:\" msg)))})"
  [client-id opts]
  ;; Delegate to on! with :once? true
  (on! client-id (assoc opts :once? true)))

(defn rpc-waiter
  "Create waiter options for RPC-style request/response matching by request-id.

  Usage:
    (take! client (rpc-waiter \"req-123\" 5000
                              (fn [response]
                                (if (:error response)
                                  (handle-error response)
                                  (handle-success response)))))"
  [request-id timeout-ms callback]
  {:pred (fn [msg]
           (= (get-in msg [:data :request-id]) request-id))
   :timeout-ms timeout-ms
   :callback callback})

;;; Unified Handler API (on!/off!)

(defn on!
  "Register a message handler.

  Options:
    :event-id   - Event ID to match (keyword), or :* for all events
    :pred       - Predicate function (fn [msg] -> bool), alternative to :event-id
    :callback   - Handler function (fn [msg] ...), receives {:event-id :data}
    :once?      - If true, handler removed after first match (default: false)
    :timeout-ms - For :once? handlers, timeout in ms. Callback receives {:error :timeout}

  Returns handler-id (for removal with off!)

  Examples:
    ;; Persistent handler
    (on! client {:event-id :server/push :callback handle-push})

    ;; One-shot with timeout (RPC)
    (on! client {:event-id :my/response :once? true :timeout-ms 5000 :callback ...})

    ;; Predicate matching
    (on! client {:pred #(= (:id (:data %)) req-id) :once? true :callback ...})

    ;; Catch-all
    (on! client {:event-id :* :callback log-all-events})"
  [client-id opts]
  (if-let [client-state (get @clients client-id)]
    (let [handlers-atom (get client-state :handlers)
          handler-id (generate-handler-id)
          callback (get opts :callback)
          once? (get opts :once? false)
          timeout-ms (get opts :timeout-ms)
          handler (cond-> {:id handler-id
                           :callback callback
                           :once? once?}
                    (contains? opts :event-id) (assoc :event-id (get opts :event-id))
                    (contains? opts :pred) (assoc :pred (get opts :pred)))]

      (assert callback ":callback is required for on!")

      ;; Setup timeout for once? handlers if specified
      (let [handler-with-timeout
            (if (and once? timeout-ms)
              (let [timeout-id
                    (js/setTimeout
                     (fn []
                       ;; Check if handler still exists (not already matched)
                       (when (contains? @handlers-atom handler-id)
                         (swap! handlers-atom dissoc handler-id)
                         (callback {:error :timeout})))
                     timeout-ms)]
                (assoc handler :timeout-id timeout-id))
              handler)]

        (swap! handlers-atom assoc handler-id handler-with-timeout)

        (log! {:level :trace
               :id :sente-lite.client/handler-registered
               :data {:client-id client-id
                      :handler-id handler-id
                      :event-id (get opts :event-id)
                      :once? once?
                      :timeout-ms timeout-ms}})

        handler-id))
    (do
      (log! {:level :error
             :id :sente-lite.client/on-invalid-client
             :data {:client-id client-id}})
      nil)))

(defn off!
  "Remove message handler(s).

  Forms:
    (off! client handler-id)           ; Remove specific handler
    (off! client {:event-id :foo})     ; Remove all handlers for event-id
    (off! client :all)                 ; Remove all handlers

  Returns true if any handlers were removed, false otherwise."
  [client-id id-or-opts]
  (if-let [client-state (get @clients client-id)]
    (let [handlers-atom (get client-state :handlers)]
      (cond
        ;; Remove all handlers
        (= id-or-opts :all)
        (let [had-handlers? (pos? (count @handlers-atom))]
          ;; Cancel all timeouts
          (doseq [entry @handlers-atom]
            (let [handler (second entry)]
              (when-let [timeout-id (get handler :timeout-id)]
                (js/clearTimeout timeout-id))))
          (reset! handlers-atom {})
          (log! {:level :debug
                 :id :sente-lite.client/handlers-cleared
                 :data {:client-id client-id}})
          had-handlers?)

        ;; Remove by event-id
        (map? id-or-opts)
        (let [target-event-id (get id-or-opts :event-id)
              matching-ids (for [entry @handlers-atom
                                 :let [id (first entry)
                                       handler (second entry)]
                                 :when (= (get handler :event-id) target-event-id)]
                             id)
              count-before (count @handlers-atom)]
          (doseq [id matching-ids]
            (when-let [handler (get @handlers-atom id)]
              (when-let [timeout-id (get handler :timeout-id)]
                (js/clearTimeout timeout-id)))
            (swap! handlers-atom dissoc id))
          (let [removed-count (- count-before (count @handlers-atom))]
            (log! {:level :debug
                   :id :sente-lite.client/handlers-removed
                   :data {:client-id client-id
                          :event-id target-event-id
                          :removed-count removed-count}})
            (pos? removed-count)))

        ;; Remove by handler-id
        (string? id-or-opts)
        (let [handler-id id-or-opts
              handler (get @handlers-atom handler-id)]
          (if handler
            (do
              (when-let [timeout-id (get handler :timeout-id)]
                (js/clearTimeout timeout-id))
              (swap! handlers-atom dissoc handler-id)
              (log! {:level :trace
                     :id :sente-lite.client/handler-removed
                     :data {:client-id client-id
                            :handler-id handler-id}})
              true)
            false))

        :else
        (do
          (log! {:level :warn
                 :id :sente-lite.client/off-invalid-arg
                 :data {:client-id client-id
                        :arg id-or-opts}})
          false)))
    (do
      (log! {:level :error
             :id :sente-lite.client/off-invalid-client
             :data {:client-id client-id}})
      false)))

(defn handler-count
  "Get count of registered handlers for a client."
  [client-id]
  (when-let [client-state (get @clients client-id)]
    (count @(get client-state :handlers))))


;;; ============================================================
;;; Source: ../src/sente_lite/registry.cljc
;;; ============================================================

(ns sente-lite.registry
  "FQN-based registry for managing named resources across processes.

   Uses Clojure's intern/resolve primitives. Works on all sente-lite
   runtimes: Babashka, Scittle, nbb.

   User-facing names are relative: \"state/user-prefs\"
   Internal FQNs are hidden: sente-lite.registry.state/user-prefs")

;; -----------------------------------------------------------------------------
;; Configuration
;; -----------------------------------------------------------------------------

(defonce ^:private reg-root (atom "sente-lite.registry"))

(defn get-reg-root
  "Get the current registry root namespace."
  []
  @reg-root)

(defn set-reg-root!
  "Set project-specific root namespace.
   Example: (set-reg-root! \"my-app.registry\")"
  [root]
  (reset! reg-root root))

;; -----------------------------------------------------------------------------
;; Internal Helpers
;; -----------------------------------------------------------------------------

(def ^:private valid-name-pattern
  "Pattern for valid registry names: category/name or category.sub/name"
  #"[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*/[a-z][a-z0-9-]*")

(defn- valid-name?
  "Check if name matches required format."
  [name]
  (and (string? name)
       (re-matches valid-name-pattern name)))

(defn- validate-name!
  "Throw if name is invalid."
  [name]
  (when-not (valid-name? name)
    (throw (ex-info "Invalid registry name format. Expected: category/name (e.g., state/user-prefs)"
                    {:name name
                     :pattern (str valid-name-pattern)}))))

(defn- absolute-fqn
  "Convert relative name to absolute FQN string.
   \"state/user-prefs\" -> \"sente-lite.registry.state/user-prefs\""
  [name]
  (let [slash-idx (.indexOf ^String name "/")
        category (subs name 0 slash-idx)
        var-name (subs name (inc slash-idx))]
    (str @reg-root "." category "/" var-name)))

(defn- name->symbols
  "Convert relative name to [ns-symbol name-symbol]."
  [name]
  (let [fqn (absolute-fqn name)
        sym (symbol fqn)]
    [(symbol (namespace sym))
     (symbol (clojure.core/name sym))]))

;; -----------------------------------------------------------------------------
;; Internal State Tracking
;; -----------------------------------------------------------------------------

;; Track registered names for list-registered
(defonce ^:private registered-names (atom #{}))

;; -----------------------------------------------------------------------------
;; Registration
;; -----------------------------------------------------------------------------

(defn register!
  "Create resource with initial value. Returns the reference (atom).

   Example: (register! \"state/user-prefs\" {:theme \"dark\"})"
  [name initial-value]
  (validate-name! name)
  (let [[ns-sym name-sym] (name->symbols name)]
    (create-ns ns-sym)
    (let [a (atom initial-value)]
      (intern ns-sym name-sym a)
      (swap! registered-names conj name)
      a)))

(defn ensure!
  "Create if not exists (nil initial value), return reference.
   Idempotent - safe to call multiple times.

   Example: (ensure! \"state/counter\")"
  [name]
  (validate-name! name)
  (let [[ns-sym name-sym] (name->symbols name)
        _ (create-ns ns-sym)
        existing (find-var (symbol (absolute-fqn name)))]
    (if existing
      @existing
      (let [a (atom nil)]
        (intern ns-sym name-sym a)
        (swap! registered-names conj name)
        a))))

;; -----------------------------------------------------------------------------
;; Read
;; -----------------------------------------------------------------------------

(defn get-ref
  "Get the reference (atom) itself. Returns nil if not registered.
   Use for hot paths where lookup overhead matters.

   Example: (let [ref (get-ref \"state/counter\")]
              (swap! ref inc))"
  [name]
  (validate-name! name)
  (when-let [v (find-var (symbol (absolute-fqn name)))]
    @v))

(defn get-value
  "Get current value.

   Example: (get-value \"state/user-prefs\") => {:theme \"dark\"}"
  [name]
  (when-let [ref (get-ref name)]
    @ref))

(defn resolve-ref
  "Resolve a registry value with one level of indirection.
   If the value is a string pointing to another registered name,
   resolve that reference and return its value.

   Use for configuration patterns where one entry points to another:
     telemetry/log-fn        -> \"telemetry.impl/console\"
     telemetry.impl/console  -> (fn [level msg data] ...)

   Example:
     (register! \"telemetry.impl/console\" console-log-fn)
     (register! \"telemetry.impl/sente\" sente-log-fn)
     (register! \"telemetry/log-fn\" \"telemetry.impl/console\")

     (resolve-ref \"telemetry/log-fn\")  => console-log-fn
     (set-value! \"telemetry/log-fn\" \"telemetry.impl/sente\")
     (resolve-ref \"telemetry/log-fn\")  => sente-log-fn"
  [name]
  (let [v (get-value name)]
    (if (and (string? v) (valid-name? v))
      ;; Check if it's a valid reference by trying to resolve it
      (if-let [resolved (get-value v)]
        resolved   ;; It's a reference, return resolved value
        v)         ;; Reference not found, return as-is
      v)))

;; -----------------------------------------------------------------------------
;; Write
;; -----------------------------------------------------------------------------

(defn set-value!
  "Replace value.

   Example: (set-value! \"state/user-prefs\" {:theme \"light\"})"
  [name new-value]
  (if-let [ref (get-ref name)]
    (reset! ref new-value)
    (throw (ex-info "Registry name not found" {:name name}))))

(defn swap-value!
  "Update via function (like swap!).

   Example: (swap-value! \"state/counter\" inc)
   Example: (swap-value! \"state/user-prefs\" assoc :theme \"light\")"
  [name f & args]
  (if-let [ref (get-ref name)]
    (apply swap! ref f args)
    (throw (ex-info "Registry name not found" {:name name}))))

;; -----------------------------------------------------------------------------
;; Write (with reference) - for hot paths
;; -----------------------------------------------------------------------------

(defn set-ref!
  "Set value using cached reference.

   Example: (set-ref! (get-ref \"state/counter\") 42)"
  [ref new-value]
  (reset! ref new-value))

(defn swap-ref!
  "Update using cached reference.

   Example: (swap-ref! (get-ref \"state/counter\") inc)"
  [ref f & args]
  (apply swap! ref f args))

;; -----------------------------------------------------------------------------
;; Discovery
;; -----------------------------------------------------------------------------

(defn registered?
  "Check if name exists.

   Example: (registered? \"state/user-prefs\") => true"
  [name]
  (validate-name! name)
  (contains? @registered-names name))

(defn list-registered
  "List all registered names (relative).

   Example: => #{\"state/user-prefs\" \"state/counter\" \"config/theme\"}"
  []
  @registered-names)

(defn list-registered-prefix
  "List registered names matching prefix.

   Example: (list-registered-prefix \"sync/\") => #{\"sync/shared-state\"}"
  [prefix]
  (into #{} (filter #(.startsWith ^String % prefix) @registered-names)))

;; -----------------------------------------------------------------------------
;; Cleanup
;; -----------------------------------------------------------------------------

(defn unregister!
  "Clear value and remove from tracking.
   Note: underlying var cannot be removed, only cleared.

   Example: (unregister! \"state/old-data\")"
  [name]
  (validate-name! name)
  (when-let [ref (get-ref name)]
    (reset! ref nil)
    (swap! registered-names disj name)
    true))

(defn unregister-prefix!
  "Unregister all names matching prefix.

   Example: (unregister-prefix! \"sync/\")"
  [prefix]
  (let [names-to-remove (list-registered-prefix prefix)]
    (doseq [name names-to-remove]
      (when-let [ref (get-ref name)]
        (reset! ref nil)))
    (swap! registered-names #(reduce disj % names-to-remove))
    (count names-to-remove)))

;; -----------------------------------------------------------------------------
;; Watch (Reactive Updates)
;; -----------------------------------------------------------------------------

(defn watch!
  "Add watch for value changes.
   callback: (fn [key name old-value new-value] ...)

   Example: (watch! \"state/counter\" :my-watch
              (fn [k n old new] (println \"Changed:\" old \"->\" new)))"
  [name key callback]
  (if-let [ref (get-ref name)]
    (add-watch ref key (fn [k _ old new]
                         (callback k name old new)))
    (throw (ex-info "Registry name not found" {:name name}))))

(defn watch-resolved!
  "Add watch that resolves references before calling callback.
   Use with configuration pointers where value is a reference to another entry.

   When telemetry/log-fn changes from \"telemetry.impl/console\" to
   \"telemetry.impl/sente\", callback receives the actual functions,
   not the strings.

   callback: (fn [key name old-resolved new-resolved] ...)

   Example:
     (watch-resolved! \"telemetry/log-fn\" :switch-logger
       (fn [k n old-fn new-fn]
         (println \"Logger switched from\" old-fn \"to\" new-fn)))"
  [name key callback]
  (if-let [ref (get-ref name)]
    (add-watch ref key
               (fn [k _ old new]
                 (let [resolve-if-ref (fn [v]
                                        (if (and (string? v) (valid-name? v))
                                          (or (get-value v) v)
                                          v))
                       old-resolved (resolve-if-ref old)
                       new-resolved (resolve-if-ref new)]
                   (callback k name old-resolved new-resolved))))
    (throw (ex-info "Registry name not found" {:name name}))))

(defn unwatch!
  "Remove watch.

   Example: (unwatch! \"state/counter\" :my-watch)"
  [name key]
  (if-let [ref (get-ref name)]
    (remove-watch ref key)
    (throw (ex-info "Registry name not found" {:name name}))))


;;; ============================================================
;;; Source: ../modules/nrepl/src/nrepl_sente/protocol.cljc
;;; ============================================================

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


;;; ============================================================
;;; Source: ../modules/nrepl/src/nrepl_sente/server.cljc
;;; ============================================================

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


;;; ============================================================
;;; Source: ../modules/nrepl/src/nrepl_sente/browser_adapter.cljs
;;; ============================================================

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
