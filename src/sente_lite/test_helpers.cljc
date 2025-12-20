(ns sente-lite.test-helpers
  "Test helpers for time-dependent code.

   Provides simulated clock and timer system that can be controlled
   in tests, eliminating flaky timing-dependent tests.

   Usage:
     (with-simulated-time
       (let [result (atom nil)
             timeout-fn (set-timeout #(reset! result :fired) 5000)]
         (advance-time! 4999)
         (assert (nil? @result))  ; Not yet
         (advance-time! 2)
         (assert (= :fired @result))))  ; Now fired")

;; ============================================================================
;; Simulated Time System
;; ============================================================================

(def ^:dynamic *simulated-time*
  "Current simulated time in milliseconds. nil = use real time."
  nil)

(def ^:dynamic *pending-timers*
  "Atom containing pending timers: [{:id :f :fires-at}]"
  nil)

(def ^:dynamic *timer-id-counter*
  "Counter for generating timer IDs"
  nil)

;; ============================================================================
;; Time Functions (use these in production code)
;; ============================================================================

(defn now
  "Get current time in milliseconds.
   Uses simulated time if in test context, otherwise real time."
  []
  (if *simulated-time*
    @*simulated-time*
    #?(:clj (System/currentTimeMillis)
       :cljs (.now js/Date))))

(defn set-timeout
  "Schedule function f to run after delay-ms milliseconds.
   Returns a cancel function.
   Uses simulated timers if in test context, otherwise real timers."
  [f delay-ms]
  (if *pending-timers*
    ;; Simulated timer
    (let [id (swap! *timer-id-counter* inc)
          fires-at (+ @*simulated-time* delay-ms)]
      (swap! *pending-timers* conj {:id id :f f :fires-at fires-at})
      ;; Return cancel function
      (fn []
        (swap! *pending-timers*
               (fn [timers]
                 (vec (remove (fn [t] (= (get t :id) id)) timers))))))
    ;; Real timer
    #?(:clj
       (let [thread (Thread.
                     (fn []
                       (try
                         (Thread/sleep delay-ms)
                         (f)
                         (catch InterruptedException _ nil))))
             started (atom false)]
         (.start thread)
         (reset! started true)
         ;; Return cancel function
         (fn []
           (when @started
             (.interrupt thread))))
       :cljs
       (let [timer-id (js/setTimeout f delay-ms)]
         ;; Return cancel function
         (fn []
           (js/clearTimeout timer-id))))))

(defn set-interval
  "Schedule function f to run every interval-ms milliseconds.
   Returns a cancel function.
   Uses simulated timers if in test context, otherwise real timers."
  [f interval-ms]
  (if *pending-timers*
    ;; Simulated interval - schedule first, reschedule on fire
    (let [id (swap! *timer-id-counter* inc)
          cancelled (atom false)
          schedule-next (fn schedule-next []
                          (when-not @cancelled
                            (let [fires-at (+ @*simulated-time* interval-ms)]
                              (swap! *pending-timers* conj
                                     {:id id
                                      :f (fn []
                                           (f)
                                           (schedule-next))
                                      :fires-at fires-at}))))]
      (schedule-next)
      ;; Return cancel function
      (fn []
        (reset! cancelled true)
        (swap! *pending-timers*
               (fn [timers]
                 (vec (remove (fn [t] (= (get t :id) id)) timers))))))
    ;; Real interval
    #?(:clj
       (let [running (atom true)
             thread (Thread.
                     (fn []
                       (while @running
                         (try
                           (Thread/sleep interval-ms)
                           (when @running (f))
                           (catch InterruptedException _ nil)))))]
         (.start thread)
         (fn []
           (reset! running false)
           (.interrupt thread)))
       :cljs
       (let [timer-id (js/setInterval f interval-ms)]
         (fn []
           (js/clearInterval timer-id))))))

;; ============================================================================
;; Test Control Functions
;; ============================================================================

(defn advance-time!
  "Advance simulated time by ms milliseconds and fire any due timers.
   Timers are fired in order of their scheduled time.
   Only works within with-simulated-time context."
  [ms]
  (assert *simulated-time* "advance-time! only works within with-simulated-time")
  (swap! *simulated-time* + ms)
  (let [current-time @*simulated-time*]
    ;; Keep firing timers until none are due
    ;; (timers might schedule more timers)
    (loop []
      (let [timers @*pending-timers*
            due-timers (filter (fn [t] (<= (get t :fires-at) current-time)) timers)]
        (when (seq due-timers)
          ;; Sort by fires-at to fire in order
          (let [sorted (sort-by :fires-at due-timers)]
            (doseq [timer sorted]
              ;; Remove before firing (timer might reschedule itself)
              (swap! *pending-timers*
                     (fn [ts] (vec (remove (fn [t] (= (get t :id) (get timer :id))) ts))))
              ;; Fire the timer
              ((get timer :f))))
          ;; Check if firing timers scheduled more due timers
          (recur))))))

(defn pending-timer-count
  "Return count of pending timers. Useful for test assertions."
  []
  (if *pending-timers*
    (count @*pending-timers*)
    0))

(defn clear-timers!
  "Cancel all pending timers. Useful for test cleanup."
  []
  (when *pending-timers*
    (reset! *pending-timers* [])))

;; ============================================================================
;; Test Context Macro
;; ============================================================================

#?(:clj
   (defmacro with-simulated-time
     "Execute body with simulated time starting at 0.
      All calls to now, set-timeout, set-interval within body
      will use simulated time that you control with advance-time!"
     [& body]
     `(binding [*simulated-time* (atom 0)
                *pending-timers* (atom [])
                *timer-id-counter* (atom 0)]
        (try
          ~@body
          (finally
            (clear-timers!))))))

;; For ClojureScript/Scittle where macros don't work the same way,
;; provide a function-based alternative
(defn run-with-simulated-time
  "Execute f with simulated time. For environments where macro doesn't work."
  [f]
  (binding [*simulated-time* (atom 0)
            *pending-timers* (atom [])
            *timer-id-counter* (atom 0)]
    (try
      (f)
      (finally
        (clear-timers!)))))

;; ============================================================================
;; Callback Tracking (for testing async callbacks)
;; ============================================================================

(defn make-callback-tracker
  "Create a callback tracker for testing.
   Returns {:callback fn, :calls atom, :wait-for-calls fn}"
  []
  (let [calls (atom [])]
    {:callback (fn [& args]
                 (swap! calls conj (vec args)))
     :calls calls
     :call-count (fn [] (count @calls))
     :last-call (fn [] (last @calls))
     :nth-call (fn [n] (nth @calls n nil))
     :reset! (fn [] (reset! calls []))}))
