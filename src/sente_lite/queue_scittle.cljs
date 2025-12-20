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
