# Async Migration Strategy for Telemere-lite

**Objective:** Transform synchronous telemetry into production-ready async implementation without breaking existing APIs.

## Migration Philosophy

1. **Zero Breaking Changes**: All existing code continues to work unchanged
2. **Incremental Rollout**: Handler-by-handler async adoption
3. **Performance First**: Eliminate telemetry-induced latency
4. **Graceful Degradation**: Async failures don't crash application

## Phase 2A: Basic Async Implementation (Critical Path)

### 1. Async Handler Wrapper System

```clojure
;; Core async handler infrastructure
#?(:bb
   (defn- async-handler-wrapper
     "Wrap any handler function with async dispatch"
     [handler-fn {:keys [mode buffer-size n-threads]
                  :or {mode :dropping buffer-size 1024 n-threads 1}}]
     (let [queue (java.util.concurrent.LinkedBlockingQueue. buffer-size)
           executor (java.util.concurrent.Executors/newFixedThreadPool n-threads)
           stats (atom {:queued 0 :processed 0 :dropped 0 :errors 0})]

       ;; Background processing
       (dotimes [_ n-threads]
         (.submit executor
           (fn []
             (try
               (while (not (.isShutdown executor))
                 (when-let [signal (.poll queue 1000 java.util.concurrent.TimeUnit/MILLISECONDS)]
                   (try
                     (handler-fn signal)
                     (swap! stats update :processed inc)
                     (catch Exception e
                       (swap! stats update :errors inc)
                       (binding [*out* *err*]
                         (println "Async handler error:" (.getMessage e)))))))
               (catch InterruptedException _
                 ;; Thread shutdown
                 )))))

       ;; Return signal dispatcher
       {:dispatch-fn
        (fn [signal]
          (case mode
            :dropping
            (if (.offer queue signal)
              (swap! stats update :queued inc)
              (swap! stats update :dropped inc))

            :blocking
            (do (.put queue signal)
                (swap! stats update :queued inc))

            :sliding
            (do (when (= (.size queue) buffer-size)
                  (.poll queue) ; Remove oldest
                  (swap! stats update :dropped inc))
                (.offer queue signal)
                (swap! stats update :queued inc))))

        :stats-fn #(deref stats)
        :shutdown-fn #(.shutdown executor)})))
```

### 2. Enhanced Handler Registration

```clojure
#?(:bb
   (defn add-handler!
     "Enhanced handler registration with async support"
     ([handler-id handler-fn]
      (add-handler! handler-id handler-fn {}))
     ([handler-id handler-fn opts]
      (let [final-handler (if (:async opts)
                           (async-handler-wrapper handler-fn (:async opts))
                           {:dispatch-fn handler-fn
                            :stats-fn #({:mode :sync})
                            :shutdown-fn #(println "Sync handler - no shutdown needed")})]
        (swap! *handlers* assoc handler-id
               {:handler final-handler
                :opts opts
                :enabled? true})))))
```

### 3. Signal Dispatch Update

```clojure
;; Updated log-with-location! for async dispatch
(defn- log-with-location!
  "Internal function that does the actual logging with source location"
  [level msg context file line ns-str]
  (when (and *telemetry-enabled*
             #?(:bb (and (ns-allowed? ns-str)
                         (event-id-allowed? (:event-id context)))
                :scittle true))
    (let [location {:file file :line line :ns ns-str}
          safe-context (serialize-for-json (or context {}))
          enhanced-context (assoc safe-context :location location)
          signal {:timestamp (now)
                  :level level
                  :ns ns-str
                  :msg [msg enhanced-context]
                  :context nil}]
      #?(:bb (do
               ;; Send to configured handlers (now async-capable)
               (doseq [[handler-id {:keys [handler enabled?]}] @*handlers*]
                 (when enabled?
                   (try
                     ((:dispatch-fn handler) signal) ; ← Now async!
                     (catch Exception e
                       (binding [*out* *err*]
                         (println "Handler" handler-id "failed:" (.getMessage e)))))))
               ;; Also send to Timbre (backwards compatibility)
               (case level
                 :debug (timbre/debug msg enhanced-context)
                 :info  (timbre/info msg enhanced-context)
                 :warn  (timbre/warn msg enhanced-context)
                 :error (timbre/error msg enhanced-context)
                 (timbre/info msg enhanced-context)))
         :scittle (js/console.log (str "[" level "] " msg " " (->json signal)))))))
```

### 4. Convenience Functions Update

```clojure
#?(:bb
   (defn add-file-handler!
     "Add file output handler with optional async"
     ([file-path]
      (add-file-handler! :file file-path {}))
     ([handler-id file-path]
      (add-file-handler! handler-id file-path {}))
     ([handler-id file-path opts]
      ;; Default to async for production readiness
      (let [default-async {:async {:mode :dropping :buffer-size 1024 :n-threads 1}}
            final-opts (if (contains? opts :async) opts default-async)]
        (add-handler! handler-id (file-handler file-path) final-opts)))))

#?(:bb
   (defn add-stdout-handler!
     "Add stdout output handler with optional async"
     ([]
      (add-stdout-handler! :stdout {}))
     ([handler-id]
      (add-stdout-handler! handler-id {}))
     ([handler-id opts]
      (let [default-async {:async {:mode :dropping :buffer-size 1024 :n-threads 1}}
            final-opts (if (contains? opts :async) opts default-async)]
        (add-handler! handler-id (stdout-handler) final-opts)))))
```

## Phase 2B: Advanced Async Features

### 1. Handler Statistics and Monitoring

```clojure
#?(:bb
   (defn get-handler-stats
     "Get performance statistics for all handlers"
     []
     (into {}
       (map (fn [[handler-id {:keys [handler]}]]
              [handler-id ((:stats-fn handler))])
            @*handlers*))))

#?(:bb
   (defn get-handler-health
     "Check handler health and queue status"
     []
     (let [stats (get-handler-stats)]
       {:healthy? (every? #(< (:errors %) 10) (vals stats))
        :total-queued (reduce + (map :queued (vals stats)))
        :total-dropped (reduce + (map :dropped (vals stats)))
        :handlers (count stats)
        :details stats})))
```

### 2. Graceful Shutdown

```clojure
#?(:bb
   (defn shutdown-telemetry!
     "Gracefully shutdown all async handlers"
     []
     (doseq [[handler-id {:keys [handler]}] @*handlers*]
       (try
         ((:shutdown-fn handler))
         (println "Shutdown handler:" handler-id)
         (catch Exception e
           (println "Error shutting down handler" handler-id ":" (.getMessage e)))))
     (reset! *handlers* {})))
```

### 3. Back-pressure Detection

```clojure
#?(:bb
   (defn detect-backpressure
     "Detect if any handlers are experiencing back-pressure"
     []
     (let [stats (get-handler-stats)
           high-drops (filter #(> (:dropped %) 100) (vals stats))
           high-queue (filter #(> (:queued %) 5000) (vals stats))]
       {:backpressure? (or (seq high-drops) (seq high-queue))
        :high-drops (count high-drops)
        :high-queue (count high-queue)
        :recommendation (cond
                         (seq high-drops) "Increase buffer sizes or reduce signal volume"
                         (seq high-queue) "Add more processing threads"
                         :else "No back-pressure detected")})))
```

## Phase 2C: Production Deployment Features

### 1. Performance Benchmarking

```clojure
;; Benchmark sync vs async performance
(defn benchmark-telemetry-impact
  "Measure telemetry performance impact"
  [test-fn iterations]
  (let [;; Test with telemetry disabled
        _ (tel/set-enabled! false)
        baseline (time-ms (dotimes [_ iterations] (test-fn)))

        ;; Test with sync telemetry
        _ (tel/set-enabled! true)
        _ (tel/clear-handlers!)
        _ (tel/add-file-handler! "bench-sync.log" {:sync true})
        sync-time (time-ms (dotimes [_ iterations]
                            (tel/event! ::benchmark-test {})
                            (test-fn)))

        ;; Test with async telemetry
        _ (tel/clear-handlers!)
        _ (tel/add-file-handler! "bench-async.log" {:async {:mode :dropping}})
        async-time (time-ms (dotimes [_ iterations]
                             (tel/event! ::benchmark-test {})
                             (test-fn)))]

    {:baseline-ms baseline
     :sync-ms sync-time
     :async-ms async-time
     :sync-overhead-pct (/ (* 100 (- sync-time baseline)) baseline)
     :async-overhead-pct (/ (* 100 (- async-time baseline)) baseline)
     :improvement-factor (/ sync-time async-time)}))
```

### 2. Configuration Management

```clojure
#?(:bb
   (defn configure-production-telemetry!
     "Setup telemetry for production deployment"
     [{:keys [log-level file-path async-mode buffer-size]
       :or {log-level :info
            file-path "logs/app.log"
            async-mode :dropping
            buffer-size 2048}}]

     ;; Clear existing setup
     (tel/clear-handlers!)
     (tel/clear-filters!)

     ;; Production-optimized setup
     (tel/set-min-level! log-level)
     (tel/set-ns-filter! {:allow #{"sente-lite.*" "app.*"}
                          :disallow #{"debug.*" "test.*"}})

     ;; Async file handler with back-pressure protection
     (tel/add-file-handler! :app-log file-path
       {:async {:mode async-mode
                :buffer-size buffer-size
                :n-threads 2}})

     ;; Error handler with immediate alerting
     (tel/add-stderr-handler! :errors
       {:async {:mode :blocking  ; Never drop errors
                :buffer-size 100
                :n-threads 1}})

     (tel/log! :info "Production telemetry configured"
               {:async-mode async-mode
                :buffer-size buffer-size
                :log-level log-level})))
```

## Migration Timeline

### Week 1: Async Infrastructure
- [ ] Implement async-handler-wrapper
- [ ] Update handler registration system
- [ ] Add basic stats/monitoring
- [ ] Unit tests for async dispatch

### Week 2: Integration & Testing
- [ ] Update convenience functions (add-file-handler!, etc.)
- [ ] Performance benchmarking suite
- [ ] WebSocket load testing
- [ ] Back-pressure simulation

### Week 3: Production Features
- [ ] Graceful shutdown mechanisms
- [ ] Handler health monitoring
- [ ] Configuration management
- [ ] Production deployment guide

### Week 4: Validation & Documentation
- [ ] Performance regression testing
- [ ] Documentation updates
- [ ] Migration examples
- [ ] Rollback procedures

## Success Criteria

### Performance Targets
- [ ] **<0.1ms latency impact** for telemetry calls
- [ ] **>50,000 signals/sec** throughput capability
- [ ] **Zero application blocking** under normal conditions
- [ ] **Graceful degradation** under extreme load

### Reliability Targets
- [ ] **99.9% signal delivery** in normal conditions
- [ ] **Zero application crashes** from telemetry failures
- [ ] **Automatic recovery** from handler failures
- [ ] **Observability** into telemetry system health

## Risk Mitigation

### Rollback Strategy
1. **Feature flag**: Easy sync/async toggle
2. **A/B testing**: Side-by-side performance comparison
3. **Incremental deployment**: Handler-by-handler migration
4. **Monitoring**: Real-time performance tracking

### Testing Strategy
1. **Unit tests**: Async dispatch correctness
2. **Integration tests**: WebSocket + telemetry interaction
3. **Load tests**: High-volume signal processing
4. **Chaos tests**: Handler failure scenarios

---

**This async migration preserves all existing APIs while delivering production-grade performance for high-volume WebSocket applications.**