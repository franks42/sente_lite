(ns sente-lite.queue
  "Send queue protocol and configuration for sente-lite.

   Provides bounded send queue with backpressure to decouple message
   sending from network I/O. Messages are queued and flushed in background.

   Platform implementations:
   - BB/JVM: queue_bb.clj (ArrayBlockingQueue)
   - Browser: queue_scittle.cljs (atom + vector)")

;; ============================================================================
;; Protocol
;; ============================================================================

(defprotocol ISendQueue
  "Protocol for send queue implementations."
  (enqueue! [this msg]
    "Add message to queue. Returns :ok if queued, :rejected if full.")
  (enqueue-blocking! [this msg timeout-ms]
    "Block until space available or timeout. Returns :ok or :timeout.
     BB/JVM only - not available in browser (JS is single-threaded).")
  (enqueue-async! [this msg opts]
    "Async enqueue with callback.
     opts: {:timeout-ms N :callback (fn [result] ...)}
     Callback called with :ok or :timeout.
     Works in both BB and browser.")
  (start! [this]
    "Start background flush thread/timer. Returns this.")
  (stop! [this]
    "Stop flush, drain remaining messages. Returns final stats.")
  (queue-stats [this]
    "Return {:depth :enqueued :sent :dropped :errors}."))

;; ============================================================================
;; Configuration
;; ============================================================================

(def default-config
  "Default send queue configuration."
  {:max-depth 1000
   :flush-interval-ms 10})

(defn merge-config
  "Merge user config with defaults."
  [user-config]
  (merge default-config user-config))

;; ============================================================================
;; Stats helpers
;; ============================================================================

(defn make-stats
  "Create initial stats map."
  []
  {:depth 0
   :enqueued 0
   :sent 0
   :dropped 0
   :errors 0})

(defn update-stats
  "Update stats atom with operation. Op is one of :enqueue :send :drop :error."
  [stats-atom op & {:keys [count] :or {count 1}}]
  (swap! stats-atom
         (fn [s]
           (case op
             :enqueue (-> s
                          (update :enqueued + count)
                          (update :depth + count))
             :send (-> s
                       (update :sent + count)
                       (update :depth - count))
             :drop (-> s
                       (update :dropped + count))
             :error (-> s
                        (update :errors + count))
             s))))
