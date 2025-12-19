# Review: Queue-Based Architecture Proposal

**Date**: 2025-12-19
**Reviewer**: Claude (Opus 4.5)
**Document Reviewed**: `doc/plan.md` (lines 2450-3700)

---

## Executive Summary

The proposal for PersistentQueue-based send/receive queues with backpressure and message bundling is well-researched with solid real-world performance data. The design is sound. **However, implementation complexity is underestimated and cross-platform (BB vs browser) concerns need more attention.**

**Recommendation**: Implement in phases—start with Send Queue only (Phase 1), which provides immediate batching benefits with manageable complexity.

---

## Proposal Overview

### What's Proposed

```
Application                       Application
     │                                 │
     ▼                                 ▼
┌─────────────┐                 ┌─────────────┐
│ Send Queue  │◄── backpressure │ Recv Queue  │
│ (bounded)   │                 │ (opt-in)    │
└─────┬───────┘                 └──────┬──────┘
      │ batch/flush                    │ worker threads
      ▼                                ▼
┌─────────────┐                 ┌─────────────┐
│ WS Frame    │────────────────►│ WS Frame    │
│ Sender      │    WebSocket    │ Receiver    │
└─────────────┘                 └─────────────┘
```

### Key Components

1. **Send Queue** (enabled by default):
   - Decouples message generation from network I/O
   - Enables batching/bundling multiple messages
   - Buffers during reconnection
   - Rate limiting support

2. **Receive Queue** (opt-in):
   - Decouples WebSocket thread from message processing
   - Absorbs traffic bursts
   - Enables priority processing
   - Prevents slow handlers from blocking WebSocket

3. **Message Bundling**:
   - Time-window based (flush every 10ms)
   - Size-based (max 4KB per batch)
   - Count-based (max 10 messages)

### Proposed Configuration

```clojure
{:queues {:receive {:enabled false          ; Direct processing default
                    :max-depth 10000
                    :workers 1}
          :send {:enabled true              ; Queue by default
                 :max-depth 1000
                 :flush-interval-ms 10
                 :flush-on-disconnect true}}}

{:batching {:enabled true
            :max-batch-size 10
            :max-batch-bytes 4096
            :flush-interval-ms 10}}
```

---

## Strengths

### 1. Well-Researched with Real-World Data

The plan includes concrete performance numbers:

| Metric | Value | Source |
|--------|-------|--------|
| Frame overhead reduction | 40-60% | Batching 10 messages |
| CPU reduction (peak) | 20-30% | Production chat systems |
| Burst absorption | 10x spikes | Queue smoothing |
| JSON compression | 60-80% | Text-based messages |
| Optimal chunk size | 64KB | Cross-network testing |

### 2. Sensible Defaults

```clojure
;; Send queued by default (enables batching)
;; Receive direct by default (simpler for fast handlers)
{:send {:enabled true}
 :receive {:enabled false}}
```

This is correct—most applications have fast message handlers (<1ms), so receive queues add unnecessary complexity.

### 3. Multiple Queue Strategies Documented

| Strategy | Use Case | Trade-offs |
|----------|----------|------------|
| Bounded `ArrayBlockingQueue` | General purpose (recommended) | May drop messages |
| Unbounded `ConcurrentLinkedQueue` | Low traffic only | OOM risk |
| Priority queue | Congestion scenarios | Starvation risk |
| Ring buffer | High performance | Complex implementation |

### 4. Integration with Other Features

The plan shows how queues compose with:
- **Batching**: Queue → batch → compress → send
- **Compression**: Compress on sender thread, not application thread
- **Chunking**: Large messages chunked in background
- **Reconnection**: Buffer during disconnect, flush on reconnect

### 5. Backpressure Design

```clojure
(defn send! [msg]
  (if (< (queue-depth) max-queue-depth)
    (do (enqueue! send-queue msg) {:status :queued})
    (do (tel/warn! "Send queue full" {:depth (queue-depth)})
        {:status :rejected :reason :queue-full})))
```

Application can react to backpressure—this is the right design.

---

## Concerns

### 1. Complexity Cost is Underestimated

**Plan estimates**:
- Batching: 8-12 hours
- Buffering: 12-16 hours

**Missing from estimates**:
- Thread safety between queue operations
- Error handling when queue is full
- Graceful shutdown (drain queue before close)
- Metrics collection (queue depth, rates)
- Connection close while messages queued
- Reconnection with messages buffered
- Browser implementation (no Java queues)
- Testing (unit + integration + browser)

**Realistic estimates**:
- Send Queue + Batching: 20-30 hours
- Receive Queue + Workers: 25-35 hours
- Full implementation: 50-70 hours

### 2. PersistentQueue vs. Java Concurrent Queues

The plan uses both interchangeably:

```clojure
;; PersistentQueue (immutable, requires atom)
(def q (atom clojure.lang.PersistentQueue/EMPTY))
(swap! q conj msg)  ; Creates new queue each time

;; ArrayBlockingQueue (mutable, thread-safe)
(def q (java.util.concurrent.ArrayBlockingQueue. 1000))
(.offer q msg)  ; Mutates in place, returns true/false
```

**Performance difference**: Java concurrent queues are ~10x faster for high-throughput scenarios.

**Recommendation**: Use `ArrayBlockingQueue` for BB:
```clojure
(def send-queue (java.util.concurrent.ArrayBlockingQueue. 1000))

(defn enqueue! [msg]
  (if (.offer send-queue msg 100 java.util.concurrent.TimeUnit/MILLISECONDS)
    :queued
    :rejected))
```

### 3. Cross-Platform Implementation Gap

**Babashka** has access to Java concurrent utilities:
```clojure
(java.util.concurrent.ArrayBlockingQueue. 1000)
```

**Scittle/Browser** does NOT:
```clojure
;; Browser needs atom-based implementation
(def send-queue (atom {:queue [] :max-size 1000}))

(defn enqueue! [msg]
  (let [result (atom :rejected)]
    (swap! send-queue
      (fn [{:keys [queue max-size]}]
        (if (< (count queue) max-size)
          (do (reset! result :queued)
              {:queue (conj queue msg) :max-size max-size})
          {:queue queue :max-size max-size})))
    @result))
```

**Recommendation**: Split implementation:
```
src/sente_lite/queue_bb.clj       ; Uses ArrayBlockingQueue
src/sente_lite/queue_scittle.cljs ; Uses atom-based queue
src/sente_lite/queue.cljc         ; Common protocol/interface
```

### 4. Flush Timer Mechanism Differs

**Babashka** requires a background thread:
```clojure
(future
  (loop []
    (Thread/sleep 10)
    (flush-send-queue!)
    (recur)))
```

**Browser** uses `setInterval`:
```clojure
(js/setInterval flush-send-queue! 10)
```

These are fundamentally different mechanisms.

**Recommendation**: Abstract the timer:
```clojure
(defprotocol FlushTimer
  (start-timer! [this flush-fn interval-ms])
  (stop-timer! [this]))

;; BB: Thread-based
;; Browser: setInterval-based
```

### 5. Message Ordering Guarantees Not Explicit

**Questions the plan doesn't clearly answer**:
- If A enqueued before B, is A guaranteed to arrive before B?
- What happens to ordering during reconnection?
- Priority queues explicitly break FIFO—is this acceptable?

**Recommendation**: Add explicit ordering guarantees:
```clojure
;; Sente-lite ordering guarantees:
;; 1. Single sender: FIFO within queue (A before B if enqueued first)
;; 2. Priority queue: Higher priority may jump queue (documented)
;; 3. Reconnection: Buffered messages sent in original order
;; 4. Batching: Messages within batch maintain relative order
```

### 6. Wire Format for Batches

**Proposed** (verbose):
```clojure
{:type :batch
 :messages [{:type :event :id :foo :data {...}}
            {:type :event :id :bar :data {...}}]}
```

**Simpler alternative**:
```clojure
;; Single message (current)
[:my/event {:data "foo"}]

;; Batch: Just an array of messages
[[:event-1 {:data "foo"}]
 [:event-2 {:data "bar"}]
 [:event-3 {:data "baz"}]]

;; Detection
(defn batch? [msg]
  (and (vector? msg) (vector? (first msg))))
```

**Benefits of simpler format**:
- Fewer bytes on wire
- No `:type :batch` wrapper
- Compatible with existing Sente format

### 7. Graceful Shutdown Complexity

Not addressed in plan:
```clojure
;; What happens when connection closes?
;; 1. Stop accepting new messages
;; 2. Flush remaining queue
;; 3. Wait for flush to complete (with timeout)
;; 4. Close WebSocket

(defn graceful-close! [client timeout-ms]
  (stop-accepting-messages!)
  (let [flushed? (flush-with-timeout! timeout-ms)]
    (when-not flushed?
      (log/warn "Queue not fully flushed" {:remaining (queue-depth)}))
    (close-websocket!)))
```

---

## Implementation Recommendations

### Phase 1: Send Queue Only (MVP)

**Scope**: Just the send queue, no batching yet.

```clojure
;; Configuration
{:send-queue {:enabled true
              :max-depth 1000}}

;; API unchanged
(send! client [:my/event data])  ; Now queued internally

;; Return value indicates queue status
;; => {:status :queued}
;; => {:status :rejected :reason :queue-full}
```

**Why this first?**
- Simplest change to existing code
- Decouples application from network I/O
- Foundation for batching
- Measurable improvement (async send)

**Estimated effort**: 15-20 hours (including tests)

### Phase 2: Message Batching

**Scope**: Add batching on top of send queue.

```clojure
{:batching {:enabled true
            :max-batch-size 10
            :max-batch-bytes 4096
            :flush-interval-ms 10}}
```

**Wire format change**:
```clojure
;; Batched messages sent as array
[[:event-1 data1] [:event-2 data2] [:event-3 data3]]
```

**Estimated effort**: 10-15 hours (wire format changes, both ends)

### Phase 3: Receive Queue (Optional)

**Scope**: Only for applications with slow message handlers.

```clojure
{:receive-queue {:enabled true
                 :max-depth 10000
                 :workers 4}}
```

**Estimated effort**: 20-25 hours (worker threads, error handling, shutdown)

---

## Minimal BB Implementation (Phase 1)

```clojure
(ns sente-lite.send-queue
  (:import [java.util.concurrent ArrayBlockingQueue TimeUnit]))

(defn make-send-queue
  "Create a bounded send queue with background flusher."
  [{:keys [max-depth flush-interval-ms on-send on-error]
    :or {max-depth 1000
         flush-interval-ms 10}}]
  (let [queue (ArrayBlockingQueue. max-depth)
        running? (atom true)
        stats (atom {:enqueued 0 :sent 0 :dropped 0 :errors 0})

        send-one! (fn [msg]
                    (try
                      (on-send msg)
                      (swap! stats update :sent inc)
                      (catch Exception e
                        (swap! stats update :errors inc)
                        (when on-error (on-error e msg)))))

        flush! (fn []
                 (loop []
                   (when-let [msg (.poll queue 0 TimeUnit/MILLISECONDS)]
                     (send-one! msg)
                     (recur))))

        flush-thread (future
                       (while @running?
                         (try
                           (Thread/sleep flush-interval-ms)
                           (flush!)
                           (catch InterruptedException _
                             (reset! running? false)))))]

    {:enqueue!
     (fn [msg]
       (if (.offer queue msg 0 TimeUnit/MILLISECONDS)
         (do (swap! stats update :enqueued inc)
             {:status :queued})
         (do (swap! stats update :dropped inc)
             {:status :rejected :reason :queue-full})))

     :stats (fn [] @stats)

     :depth (fn [] (.size queue))

     :flush-now! flush!

     :stop!
     (fn []
       (reset! running? false)
       (.interrupt (Thread/currentThread))
       (flush!)  ; Final flush
       @flush-thread
       @stats)}))

;; Usage
(def queue (make-send-queue
             {:max-depth 1000
              :flush-interval-ms 10
              :on-send (fn [msg] (ws/send! conn msg))
              :on-error (fn [e msg] (log/error "Send failed" e))}))

((:enqueue! queue) [:my/event {:data "foo"}])
;; => {:status :queued}

((:stats queue))
;; => {:enqueued 150 :sent 148 :dropped 0 :errors 2}
```

### Minimal Browser Implementation (Phase 1)

```clojure
(ns sente-lite.send-queue-browser)

(defn make-send-queue
  [{:keys [max-depth flush-interval-ms on-send on-error]
    :or {max-depth 1000
         flush-interval-ms 10}}]
  (let [queue (atom [])
        stats (atom {:enqueued 0 :sent 0 :dropped 0 :errors 0})
        timer-id (atom nil)

        send-one! (fn [msg]
                    (try
                      (on-send msg)
                      (swap! stats update :sent inc)
                      (catch :default e
                        (swap! stats update :errors inc)
                        (when on-error (on-error e msg)))))

        flush! (fn []
                 (let [msgs @queue]
                   (reset! queue [])
                   (doseq [msg msgs]
                     (send-one! msg))))

        start-timer! (fn []
                       (reset! timer-id
                         (js/setInterval flush! flush-interval-ms)))]

    (start-timer!)

    {:enqueue!
     (fn [msg]
       (if (< (count @queue) max-depth)
         (do (swap! queue conj msg)
             (swap! stats update :enqueued inc)
             {:status :queued})
         (do (swap! stats update :dropped inc)
             {:status :rejected :reason :queue-full})))

     :stats (fn [] @stats)

     :depth (fn [] (count @queue))

     :flush-now! flush!

     :stop!
     (fn []
       (when @timer-id
         (js/clearInterval @timer-id)
         (reset! timer-id nil))
       (flush!)
       @stats)}))
```

---

## Wire Format Recommendation

### Batched Messages (Simplified)

```clojure
;; Current single message
[:my/event {:data "foo"}]

;; Batched: Array of messages (no wrapper)
[[:event-1 {:data "foo"}]
 [:event-2 {:data "bar"}]
 [:event-3 {:data "baz"}]]

;; Detection on receive
(defn process-incoming [msg]
  (if (and (vector? msg) (vector? (first msg)))
    ;; Batch: process each
    (doseq [m msg] (handle-message m))
    ;; Single: process directly
    (handle-message msg)))
```

**Why this format?**
- Minimal overhead (no `:type :batch` wrapper)
- Self-describing (batch is array of arrays)
- Compatible with existing Sente format
- Easy to detect and process

---

## Performance Expectations

| Metric | Without Queue | With Queue | With Batching |
|--------|---------------|------------|---------------|
| Send latency | 0ms (direct) | +0-5ms (queue) | +5-15ms (flush) |
| Frame overhead | 100% | 100% | 40-60% |
| CPU (peak) | 100% | 95% | 70-80% |
| Burst handling | Poor | Good | Good |
| Backpressure | None | Yes | Yes |

---

## Testing Strategy

### Unit Tests

```clojure
;; Queue accepts messages
(testing "enqueue succeeds when not full"
  (let [q (make-send-queue {:max-depth 10})]
    (is (= :queued (:status ((:enqueue! q) [:test {}]))))))

;; Queue rejects when full
(testing "enqueue rejects when full"
  (let [q (make-send-queue {:max-depth 2})]
    ((:enqueue! q) [:msg1 {}])
    ((:enqueue! q) [:msg2 {}])
    (is (= :rejected (:status ((:enqueue! q) [:msg3 {}]))))))

;; Flush sends all messages
(testing "flush sends queued messages"
  (let [sent (atom [])
        q (make-send-queue {:on-send #(swap! sent conj %)})]
    ((:enqueue! q) [:msg1 {}])
    ((:enqueue! q) [:msg2 {}])
    ((:flush-now! q))
    (is (= 2 (count @sent)))))
```

### Integration Tests

```clojure
;; BB-to-BB with queue
(testing "messages delivered in order"
  (let [received (atom [])]
    (with-server {:on-message #(swap! received conj %)}
      (with-client {:send-queue {:enabled true}}
        (doseq [i (range 100)]
          (send! client [:msg i]))
        (Thread/sleep 100)
        (is (= (range 100) (map second @received)))))))
```

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Complexity underestimated | High | Medium | Phase implementation, double estimates |
| Cross-platform issues | Medium | High | Separate BB/browser implementations |
| Message loss during disconnect | Medium | High | Flush-before-close protocol |
| Memory pressure | Low | High | Bounded queues, backpressure |
| Breaking changes | Low | High | Additive API, optional queues |

---

## Summary

| Aspect | Assessment |
|--------|------------|
| **Overall Design** | ✅ Sound - well-researched, sensible defaults |
| **Queue Strategies** | ✅ Comprehensive options documented |
| **Performance Data** | ✅ Real-world numbers included |
| **Effort Estimates** | ⚠️ Too optimistic - double them |
| **Cross-Platform** | ⚠️ Needs separate BB vs browser implementations |
| **Ordering Guarantees** | ⚠️ Needs explicit documentation |
| **Wire Format** | ⚠️ Could be simpler |

### Recommendations

1. **Start with Phase 1** (Send Queue only) - 15-20 hours, immediate benefit
2. **Double effort estimates** - Real implementation is 2x plan
3. **Split implementations** - BB uses Java queues, browser uses atoms
4. **Simplify wire format** - Array of arrays, no wrapper
5. **Document ordering** - Explicit FIFO guarantees
6. **Test thoroughly** - BB-to-BB first, then browser

The current synchronous design works. Don't over-engineer until you have measurable performance problems. Phase 1 (send queue) provides immediate benefits with manageable risk.
