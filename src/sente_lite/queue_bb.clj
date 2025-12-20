(ns sente-lite.queue-bb
  "Babashka send queue implementation using ArrayBlockingQueue.

   Uses Java concurrent queue for thread-safe bounded queue with
   non-blocking offer and efficient batch drain."
  (:require [sente-lite.queue :as q])
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]
           [java.util ArrayList]))

;; ============================================================================
;; BB Send Queue Implementation
;; ============================================================================

(defrecord BBSendQueue [queue stats running? flush-thread config on-send on-error
                        waiters]
  q/ISendQueue

  (enqueue! [_this msg]
    (if (.offer ^ArrayBlockingQueue queue msg)
      (do
        (q/update-stats stats :enqueue)
        :ok)
      (do
        (q/update-stats stats :drop)
        :rejected)))

  (enqueue-blocking! [_this msg timeout-ms]
    (if (.offer ^ArrayBlockingQueue queue msg timeout-ms TimeUnit/MILLISECONDS)
      (do
        (q/update-stats stats :enqueue)
        :ok)
      :timeout))

  (enqueue-async! [this msg opts]
    (let [timeout-ms (get opts :timeout-ms 30000)
          callback (get opts :callback)]
      (assert callback ":callback is required for enqueue-async!")
      ;; Try immediate enqueue first
      (let [result (q/enqueue! this msg)]
        (if (= result :ok)
          ;; Immediate success
          (callback :ok)
          ;; Register waiter (event-driven, no polling)
          (let [deadline (+ (System/currentTimeMillis) timeout-ms)
                waiter-id (Object.)  ;; Unique identity
                waiter {:id waiter-id
                        :msg msg
                        :callback callback
                        :deadline deadline
                        :timed-out? (atom false)}]
            ;; Add to waiter list
            (swap! waiters conj waiter)
            ;; Start timeout thread
            (future
              (Thread/sleep timeout-ms)
              ;; Check if still waiting (not already processed)
              (when (compare-and-set! (:timed-out? waiter) false true)
                ;; Remove from waiter list and callback
                (swap! waiters (fn [ws] (remove #(= (:id %) waiter-id) ws)))
                (callback :timeout))))))))

  (start! [this]
    (reset! running? true)
    (let [interval-ms (:flush-interval-ms config)
          process-waiters! (fn []
                             ;; Process waiting async enqueue requests when space available
                             (loop []
                               (let [ws @waiters
                                     queue-size (.size ^ArrayBlockingQueue queue)
                                     max-depth (:max-depth config)]
                                 (when (and (< queue-size max-depth)
                                            (seq ws))
                                   (let [waiter (first ws)]
                                     ;; Try to claim this waiter (atomically mark as processed)
                                     (when (compare-and-set! (:timed-out? waiter) false true)
                                       ;; Remove from waiter list
                                       (swap! waiters (fn [wss] (remove #(= (:id %) (:id waiter)) wss)))
                                       ;; Enqueue and callback
                                       (let [result (q/enqueue! this (:msg waiter))]
                                         ((:callback waiter) (if (= result :ok) :ok :rejected))))
                                     (recur))))))
          thread (Thread.
                  (fn []
                    (while @running?
                      (try
                        ;; Drain all available messages
                        (let [batch (ArrayList.)]
                          (when (pos? (.drainTo ^ArrayBlockingQueue queue batch))
                            (doseq [msg batch]
                              (try
                                (on-send msg)
                                (q/update-stats stats :send)
                                (catch Exception e
                                  (q/update-stats stats :error)
                                  (when on-error
                                    (on-error e msg)))))
                            ;; After flush, process any waiters (space now available)
                            (process-waiters!)))
                        ;; Sleep before next flush
                        (Thread/sleep interval-ms)
                        (catch InterruptedException _
                          ;; Thread interrupted, exit gracefully
                          (reset! running? false))
                        (catch Exception e
                          (q/update-stats stats :error)
                          (when on-error
                            (on-error e nil)))))))]
      (.setDaemon thread true)
      (.setName thread "sente-lite-send-queue")
      (.start thread)
      (reset! flush-thread thread)
      this))

  (stop! [_this]
    (reset! running? false)
    ;; Interrupt thread if sleeping
    (when-let [^Thread t @flush-thread]
      (.interrupt t)
      ;; Wait for thread to finish (max 1 second)
      (.join t 1000))
    ;; Drain remaining messages
    (let [batch (ArrayList.)]
      (.drainTo ^ArrayBlockingQueue queue batch)
      (doseq [msg batch]
        (try
          (on-send msg)
          (q/update-stats stats :send)
          (catch Exception e
            (q/update-stats stats :error)
            (when on-error
              (on-error e msg))))))
    ;; Cancel all pending waiters with :timeout
    (doseq [waiter @waiters]
      (when (compare-and-set! (:timed-out? waiter) false true)
        ((:callback waiter) :timeout)))
    (reset! waiters [])
    @stats)

  (queue-stats [_this]
    (assoc @stats :depth (.size ^ArrayBlockingQueue queue))))

;; ============================================================================
;; Factory Function
;; ============================================================================

(defn make-send-queue
  "Create a new BB send queue.

   Options:
     :max-depth        - Maximum queue size (default: 1000)
     :flush-interval-ms - Flush interval in ms (default: 10)
     :on-send          - (required) Function to send message: (fn [msg] ...)
     :on-error         - (optional) Error handler: (fn [exception msg] ...)"
  [{:keys [on-send on-error] :as opts}]
  (assert on-send ":on-send callback is required")
  (let [config (q/merge-config opts)
        queue (ArrayBlockingQueue. (:max-depth config))]
    (->BBSendQueue queue
                   (atom (q/make-stats))
                   (atom false)
                   (atom nil)
                   config
                   on-send
                   on-error
                   (atom []))))
