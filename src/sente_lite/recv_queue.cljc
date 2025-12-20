(ns sente-lite.recv-queue
  "Receive queue with waiter support for request/response patterns.

   Key features:
   - Waiters register with predicate + callback + optional timeout
   - Messages matched to first waiter whose predicate returns true
   - Unmatched messages buffered (up to max-depth) or sent to fallback
   - On close, all waiters notified with error
   - Fully testable via injectable time functions

   Usage:
     (def queue (make-recv-queue {:max-depth 100
                                  :on-unmatched println}))

     ;; Wait for specific message
     (take! queue {:pred #(= (:id %) \"req-123\")
                   :callback (fn [msg] (println \"Got:\" msg))
                   :timeout-ms 5000})

     ;; Put message (from WebSocket)
     (put! queue {:id \"req-123\" :data \"hello\"})
     ;; -> callback called with {:id \"req-123\" :data \"hello\"}

     ;; On disconnect
     (close! queue :disconnected)
     ;; -> all pending waiters called with {:error :disconnected}"
  (:require [sente-lite.test-helpers :as th]))

;; ============================================================================
;; Receive Queue Implementation
;; ============================================================================

(defn make-recv-queue
  "Create a new receive queue.

   Options:
     :max-depth     - Max buffered messages (default: 100)
     :on-unmatched  - Called for messages that don't match any waiter
                      and buffer is full: (fn [msg] ...)
     :now-fn        - Time function (default: th/now)
     :set-timeout-fn - Timer function (default: th/set-timeout)

   Returns queue object with functions:
     :take!   - Register waiter
     :put!    - Add message
     :close!  - Close queue, notify waiters
     :stats   - Get queue statistics
     :state   - Internal state atom (for testing)"
  [{:keys [max-depth on-unmatched now-fn set-timeout-fn]
    :or {max-depth 100
         now-fn th/now
         set-timeout-fn th/set-timeout}}]

  (let [;; State: buffer, waiters, closed status
        state (atom {:buffer []           ; Unmatched messages
                     :waiters []          ; Pending waiters
                     :closed? false
                     :close-reason nil
                     :stats {:received 0
                             :matched 0
                             :buffered 0
                             :dropped 0
                             :timeouts 0
                             :waiters-notified 0}})

        ;; Waiter ID generator
        waiter-id-counter (atom 0)

        ;; Helper: remove waiter by ID
        remove-waiter! (fn [waiter-id]
                         (swap! state update :waiters
                                (fn [ws]
                                  (vec (remove (fn [w] (= (get w :id) waiter-id)) ws)))))

        ;; Helper: find and remove first matching waiter
        find-matching-waiter (fn [msg]
                               (let [waiters (get @state :waiters)]
                                 (first (filter (fn [w]
                                                  (try
                                                    ((get w :pred) msg)
                                                    (catch #?(:clj Exception :cljs :default) _
                                                      false)))
                                                waiters))))

        ;; Take: register a waiter
        take-fn (fn [{:keys [pred callback timeout-ms]
                      :or {pred (fn [_] true)}}]
                  (let [current-state @state]
                    (cond
                      ;; Queue already closed - call callback immediately with error
                      (get current-state :closed?)
                      (do
                        (callback {:error :closed
                                   :reason (get current-state :close-reason)})
                        nil)

                      ;; Check buffer for matching message
                      :else
                      (let [buffer (get current-state :buffer)
                            match-idx (first (keep-indexed
                                              (fn [idx msg]
                                                (when (try
                                                        (pred msg)
                                                        (catch #?(:clj Exception :cljs :default) _
                                                          false))
                                                  idx))
                                              buffer))]
                        (if match-idx
                          ;; Found match in buffer - call immediately
                          (let [msg (nth buffer match-idx)]
                            (swap! state update :buffer
                                   (fn [b]
                                     (vec (concat (subvec b 0 match-idx)
                                                  (subvec b (inc match-idx))))))
                            (swap! state update-in [:stats :matched] inc)
                            (callback msg)
                            nil)

                          ;; No match - register as waiter
                          (let [waiter-id (swap! waiter-id-counter inc)
                                cancel-timeout (when timeout-ms
                                                 (set-timeout-fn
                                                  (fn []
                                                    ;; Check if waiter still exists
                                                    (when (some (fn [w] (= (get w :id) waiter-id))
                                                                (get @state :waiters))
                                                      (remove-waiter! waiter-id)
                                                      (swap! state update-in [:stats :timeouts] inc)
                                                      (callback {:error :timeout})))
                                                  timeout-ms))
                                waiter {:id waiter-id
                                        :pred pred
                                        :callback callback
                                        :cancel-timeout cancel-timeout
                                        :created-at (now-fn)}]
                            (swap! state update :waiters conj waiter)
                            ;; Return cancel function
                            (fn []
                              (when cancel-timeout (cancel-timeout))
                              (remove-waiter! waiter-id))))))))

        ;; Put: add message to queue
        put-fn (fn [msg]
                 (let [current-state @state]
                   (when-not (get current-state :closed?)
                     (swap! state update-in [:stats :received] inc)
                     ;; Try to find matching waiter
                     (if-let [waiter (find-matching-waiter msg)]
                       ;; Found waiter - call callback, remove waiter
                       (do
                         (remove-waiter! (get waiter :id))
                         (when-let [cancel (get waiter :cancel-timeout)]
                           (cancel))
                         (swap! state update-in [:stats :matched] inc)
                         ((get waiter :callback) msg))
                       ;; No waiter - buffer or drop
                       (let [buffer (get @state :buffer)]
                         (if (< (count buffer) max-depth)
                           (do
                             (swap! state update :buffer conj msg)
                             (swap! state update-in [:stats :buffered] inc))
                           (do
                             (swap! state update-in [:stats :dropped] inc)
                             (when on-unmatched
                               (on-unmatched msg)))))))))

        ;; Close: notify all waiters
        close-fn (fn [reason]
                   (when-not (get @state :closed?)
                     (swap! state assoc :closed? true :close-reason reason)
                     ;; Notify all waiters
                     (let [waiters (get @state :waiters)]
                       (doseq [waiter waiters]
                         (when-let [cancel (get waiter :cancel-timeout)]
                           (cancel))
                         (swap! state update-in [:stats :waiters-notified] inc)
                         ((get waiter :callback) {:error :closed :reason reason}))
                       (swap! state assoc :waiters []))
                     ;; Return final stats with buffer contents
                     {:stats (get @state :stats)
                      :buffered-messages (get @state :buffer)}))

        ;; Stats
        stats-fn (fn []
                   (let [s @state]
                     (assoc (get s :stats)
                            :buffer-depth (count (get s :buffer))
                            :waiter-count (count (get s :waiters))
                            :closed? (get s :closed?))))]

    ;; Return queue object
    {:take! take-fn
     :put! put-fn
     :close! close-fn
     :stats stats-fn
     :state state}))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn take!
  "Register a waiter on the queue.
   Returns cancel function, or nil if matched immediately or queue closed."
  [queue opts]
  ((get queue :take!) opts))

(defn put!
  "Put a message on the queue."
  [queue msg]
  ((get queue :put!) msg))

(defn close!
  "Close the queue with given reason.
   Returns {:stats ... :buffered-messages [...]}."
  [queue reason]
  ((get queue :close!) reason))

(defn stats
  "Get queue statistics."
  [queue]
  ((get queue :stats)))

;; ============================================================================
;; RPC Helper
;; ============================================================================

(defn rpc-waiter
  "Create a waiter config for RPC-style request/response.

   Usage:
     (take! queue (rpc-waiter \"req-123\" 5000
                              (fn [response]
                                (if (:error response)
                                  (handle-error response)
                                  (handle-success response)))))"
  [request-id timeout-ms callback]
  {:pred (fn [msg]
           (= (get msg :request-id) request-id))
   :timeout-ms timeout-ms
   :callback callback})
