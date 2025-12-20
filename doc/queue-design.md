# sente-lite Queue Design

**Created:** 2025-12-20
**Status:** Phase 1 Complete (recv-queue + client integration)

## Overview

This document describes the queue architecture for sente-lite, covering:
- Send queue (client → server buffering)
- Receive queue (server → client routing with waiters)
- Use cases and their ordering requirements

## Use Cases & Ordering Requirements

### 1. nREPL Request/Response

**Flow:**
```
Browser                              Server
───────                              ──────
{:op "eval" :code "(+ 1 2)"}  ────►  Evaluate
        ◄─────────────────────────   {:value "3" :status [:done]}
```

**Ordering:**
- Within same session-id: Serialized by nREPL (guaranteed order)
- Across session-ids: Parallel (responses arrive in any order)
- Our transport preserves send order (FIFO queue + TCP)

**Pattern:** RPC with waiters
```clojure
(send! client [:nrepl/eval {:id "req-1" :session "sess-1" :code "..."}])
(take! recv-queue {:pred #(= (:id %) "req-1")
                   :timeout-ms 30000
                   :callback handle-response})
```

**On disconnect:** All waiters notified with `{:error :closed}`

---

### 2. Logging Events

**Flow:**
```
Browser                              Server
───────                              ──────
(log :info "user clicked")  ───────► Append to log store
(log :warn "slow response") ───────► Append to log store
(log :error "failed")       ───────► Append to log store
```

**Ordering:**
- Within same source: FIFO (chronological)
- Across sources: Independent
- No response needed

**Pattern:** Unidirectional stream (fire and forget)
```clojure
(send! client [:log/event {:level :info :ns "app.core" :msg "..."}])
```

**On disconnect:** Unsent logs in send queue can be:
- Dropped (acceptable for logs)
- Persisted locally for retry

---

### 3. Atom Sync (Reagent atoms)

**Flow:**
```
Browser                              Server
───────                              ──────
@app-state = {:count 0}              (source of truth)
        ◄──────────────────────────  {:count 1}  (push)
        ◄──────────────────────────  {:count 2}  (push)
(swap! app-state inc) ─────────────► Request change
        ◄──────────────────────────  {:count 4}  (confirmed)
```

**Ordering:**
- Within same atom: Strict FIFO (state updates must apply in order)
- Across atoms: Independent

**Pattern:** Subscription per atom-id
```clojure
;; Subscribe to updates for specific atom
(subscribe! recv-queue "atom:app-state"
            (fn [msg] (reset! app-state (:value msg))))

;; Request change (fire-and-forget or confirmed)
(send! client [:atom/swap {:atom-id "app-state" :fn 'inc}])
```

**On disconnect:** Resync full state on reconnect via `:on-channel-ready`

---

### 4. File/Blob Upload

**Flow:**
```
Browser                              Server
───────                              ──────
Chunk 1 of file-xyz ───────────────► Buffer
Chunk 2 of file-xyz ───────────────► Buffer
Chunk 3 of file-xyz ───────────────► Buffer
[:upload/complete] ────────────────► Assemble
        ◄──────────────────────────  {:status :ok :url "..."}
```

**Ordering:**
- Within same file: Strict FIFO (chunks must reassemble)
- Across files: Independent

**Pattern:** Stream for chunks + RPC for completion
```clojure
(doseq [chunk chunks]
  (send! client [:upload/chunk {:file-id "xyz" :n n :data data}]))

(send! client [:upload/complete {:file-id "xyz"}])
(take! recv-queue {:pred #(= (:file-id %) "xyz")
                   :timeout-ms 30000
                   :callback handle-result})
```

**On disconnect:** Partial upload lost, restart or resume on reconnect

---

## Ordering Guarantees Summary

| Use Case | Direction | Within Partition | Across Partitions | Response? |
|----------|-----------|------------------|-------------------|-----------|
| nREPL | Bidirectional | FIFO (per session) | Independent | Yes (RPC) |
| Logging | Client → Server | FIFO (per source) | Independent | No |
| Atom sync | Bidirectional | FIFO (per atom-id) | Independent | Optional |
| File upload | Client → Server | FIFO (per file-id) | Independent | Yes (completion) |

**Key insight:** All use cases need FIFO within a partition, but no ordering across partitions.

---

## Send Queue Design

### Purpose
Decouple message sending from network I/O. Buffer messages when sending faster than network can handle.

### Implementation
```
┌─────────────────────────────────────────────┐
│                Send Queue                    │
├─────────────────────────────────────────────┤
│  Bounded buffer: [msg, msg, msg, ...]       │
│  Max depth: 1000 (configurable)             │
│  Flush interval: 10ms (configurable)        │
├─────────────────────────────────────────────┤
│  enqueue!(msg) → :ok | :rejected            │
│  start!() → begin flush loop                │
│  stop!() → drain remaining, return stats    │
│  queue-stats() → {:depth :sent :dropped}    │
└─────────────────────────────────────────────┘
```

### Backpressure

**Scenario:** WebSocket can't send (slow network, connection issue) → queue stops draining → buffer fills up.

When queue is full, `enqueue!` returns `:rejected`. Application decides:
- Drop the message
- Retry later
- Apply backpressure upstream

**Enqueue options (implemented 2025-12-20):**

| Function | Behavior | Platform |
|----------|----------|----------|
| `enqueue!` | Non-blocking, returns `:ok` or `:rejected` | BB + Browser |
| `enqueue-blocking!` | Blocks until space or timeout, returns `:ok` or `:timeout` | BB only |
| `enqueue-async!` | Async with callback, calls back with `:ok` or `:timeout` | BB + Browser |

```clojure
;; Non-blocking (current behavior)
(case (enqueue! queue msg)
  :ok      (println "queued")
  :rejected (println "queue full"))

;; Blocking with timeout (BB only - JVM can block, JS cannot)
(case (enqueue-blocking! queue msg 5000)
  :ok      (println "queued after waiting")
  :timeout (println "gave up after 5s"))

;; Async with callback (both platforms)
(enqueue-async! queue msg {:timeout-ms 5000
                           :callback (fn [result]
                                       (case result
                                         :ok      (println "queued")
                                         :timeout (println "timed out")))})
```

---

### Phase 2: Event-Driven Async (Refactoring)

**Problem with polling:**
- Wastes CPU cycles checking every 10ms
- Adds 0-10ms latency to every async enqueue
- Not elegant - busy-waiting antipattern
- Scales poorly with many waiters

**Goal:** True event-driven `enqueue-async!` - waiters notified immediately when space available.

#### BB/JVM Design

Use `java.util.concurrent.locks.Condition`:

```
┌─────────────────────────────────────────────────────────────┐
│                    BBSendQueue                               │
├─────────────────────────────────────────────────────────────┤
│  queue: ArrayBlockingQueue                                   │
│  lock: ReentrantLock                                         │
│  space-available: Condition  ← signaled after flush          │
│  waiters: atom [{:msg :callback :deadline}]                  │
└─────────────────────────────────────────────────────────────┘

enqueue-async! flow:
─────────────────
1. Try immediate enqueue → success? call callback(:ok), done
2. Add waiter to list: {:msg msg :callback cb :deadline (+ now timeout)}
3. Waiter thread: lock.lock(), space-available.await(remaining-time)
4. On signal: try enqueue, if success → callback(:ok), remove waiter
5. On timeout: callback(:timeout), remove waiter

flush! flow (after draining):
──────────────────────────────
1. lock.lock()
2. space-available.signalAll()  ← wake all waiting threads
3. lock.unlock()
```

**Key insight:** `ArrayBlockingQueue.offer()` + `Condition.await()` gives us:
- Immediate success when space available
- Efficient waiting (no CPU burn)
- Precise timeout handling
- Immediate wake-up when space freed

#### Scittle/Browser Design

Use waiter list + flush hook:

```
┌─────────────────────────────────────────────────────────────┐
│                   ScittleSendQueue                           │
├─────────────────────────────────────────────────────────────┤
│  queue: atom []                                              │
│  waiters: atom [{:msg :callback :deadline :timeout-id}]      │
└─────────────────────────────────────────────────────────────┘

enqueue-async! flow:
─────────────────
1. Try immediate enqueue → success? callback(:ok), done
2. Add waiter: {:msg msg :callback cb :deadline (+ now timeout)}
3. Set timeout: js/setTimeout → callback(:timeout), remove waiter
4. Return cancel function

flush! flow (after sending):
──────────────────────────────
1. Check if space available (count < max-depth)
2. If space: process-waiters!
   - Take first waiter
   - Try enqueue → success?
     - Clear timeout, callback(:ok), remove waiter
   - Repeat while space and waiters exist
```

**Key insight:** No polling loop. Flush naturally creates the "space available" event.

#### Implementation Tasks

1. **BB: Add signaling infrastructure**
   - Add `ReentrantLock` + `Condition` to BBSendQueue record
   - Modify flush loop to signal after drain
   - Rewrite `enqueue-async!` to await on condition

2. **Scittle: Add waiter processing**
   - Add waiters atom to state
   - Add `process-waiters!` function
   - Call `process-waiters!` at end of `flush!`
   - Rewrite `enqueue-async!` to register waiter

3. **Tests: Verify behavior**
   - Existing tests should pass (API unchanged)
   - Add latency test: verify callback within 1-2ms of space available
   - Add concurrent waiter test: multiple waiters, verify FIFO

#### API (unchanged)

```clojure
;; Same API, better implementation
(enqueue-async! queue msg {:timeout-ms 5000
                           :callback (fn [result] ...)})
```

---

### On Disconnect
**Fixed (2025-12-20):** `send-raw!` now throws on failure instead of returning false.
Queue catches exception and counts as `:errors` (not `:sent`).

**Behavior:**
- `send-raw!` throws when WebSocket not open
- Queue's error handler catches and increments `:errors` stat
- Queue's `:on-error` callback notified with exception and message
- Application can handle via `:on-error` callback

**Remaining work:**
- Stop flush loop on disconnect (pause, don't drain to errors)
- Return unsent messages to application via disconnect callback
- Application decides: persist locally, retry on reconnect, or drop

### Files
- `src/sente_lite/queue.cljc` - Protocol definition
- `src/sente_lite/queue_bb.clj` - BB implementation (ArrayBlockingQueue)
- `src/sente_lite/queue_scittle.cljs` - Browser implementation (atom + vector)

---

## Receive Queue Design

### Purpose
Route incoming messages to appropriate handlers. Support two patterns:
1. **Waiters** - One-shot handlers waiting for specific response (RPC)
2. **Subscriptions** - Persistent handlers for message streams

### Implementation
```
┌─────────────────────────────────────────────┐
│               Receive Queue                  │
├─────────────────────────────────────────────┤
│  Subscriptions (persistent, per channel):   │
│  ┌────────────────────────────────────────┐ │
│  │ "logs" → callback (FIFO delivery)      │ │
│  │ "atom:xyz" → callback (FIFO delivery)  │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  Waiters (one-shot, predicate-based):       │
│  ┌────────────────────────────────────────┐ │
│  │ {pred, callback, timeout, cancel-fn}   │ │
│  │ {pred, callback, timeout, cancel-fn}   │ │
│  └────────────────────────────────────────┘ │
│                                              │
│  Buffer (unmatched messages):               │
│  [msg, msg, ...] (max-depth bounded)        │
└─────────────────────────────────────────────┘
```

### Message Routing
1. Check waiters by predicate (FIFO - first matching waiter wins)
2. Call `:on-message` callback (subscriptions see ALL messages)
3. Buffer unmatched messages (up to max-depth)
4. Drop or call `on-unmatched` if buffer full

### Burst Tolerance (not backpressure)

The buffer acts as a **shock absorber** for bursty traffic:

```
Without buffer:              With buffer:
─────────────────           ─────────────────
Messages arrive             Messages arrive
     ↓                           ↓
No waiter ready?            No waiter ready?
     ↓                           ↓
   LOST                     Buffer it (up to max-depth)
                                 ↓
                            Waiter registers later
                                 ↓
                            Match from buffer → delivered
```

**Scenarios it handles:**
- **Race condition** - Response arrives slightly before waiter registers
- **Burst of messages** - Multiple messages arrive faster than app can process
- **Slow predicate matching** - Complex predicates take time to evaluate

**What it doesn't do:**
- Tell the server to slow down (true backpressure)
- Guarantee delivery if buffer overflows
- Handle sustained overload (buffer fills, messages drop)

For most RPC patterns (nREPL, request/response), the buffer rarely fills because
waiters are registered before requests are sent. It's a safety net for timing edge cases.

### Waiter Lifecycle
```clojure
;; Register waiter
(def cancel (take! queue {:pred #(= (:id %) "req-1")
                          :timeout-ms 5000
                          :callback handle-response}))

;; Three possible outcomes:
;; 1. Message arrives matching predicate → callback(msg)
;; 2. Timeout expires → callback({:error :timeout})
;; 3. Queue closed → callback({:error :closed :reason ...})

;; Optional: cancel before any outcome
(cancel)
```

### On Disconnect
```clojure
(close! queue :disconnected)
;; → All waiters called with {:error :closed :reason :disconnected}
;; → All subscriptions called with {:error :closed :reason :disconnected}
;; → Returns {:stats {...} :buffered-messages [...]}
```

### Testability
Time-dependent behavior (timeouts) uses injectable functions:
```clojure
(make-recv-queue {:now-fn my-clock
                  :set-timeout-fn my-timer})
```

Test helper provides simulated time:
```clojure
(th/with-simulated-time
  (let [queue (make-recv-queue {})]
    (take! queue {:timeout-ms 5000 :callback #(reset! result %)})
    (th/advance-time! 5001)
    (assert (= {:error :timeout} @result))))
```

### Files
- `src/sente_lite/recv_queue.cljc` - Implementation
- `src/sente_lite/test_helpers.cljc` - Injectable time system
- `test/scripts/recv_queue/test_recv_queue_bb.bb` - Unit tests (39 passing)

---

## Client Integration

### Connection Lifecycle
```
┌─────────────────────────────────────────────────────────┐
│                      Client                              │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  :on-channel-ready (fn [client]                         │
│    ;; Called on EVERY new connection (initial+reconnect)│
│    ;; Register handlers fresh each time                 │
│    (subscribe! (:recv-queue client) "logs" log-handler) │
│    (subscribe! (:recv-queue client) "atoms" atom-handler│))
│                                                          │
│  :on-disconnect (fn [{:keys [unsent pending]}]          │
│    ;; unsent = messages still in send queue             │
│    ;; pending = waiters that didn't get response        │
│    ;; Application decides what to retry                 │)
│                                                          │
├─────────────────────────────────────────────────────────┤
│  Send Queue              │  Receive Queue               │
│  ───────────             │  ─────────────               │
│  Buffers outgoing        │  Routes incoming             │
│  FIFO flush              │  Waiters + Subscriptions     │
│  Backpressure :rejected  │  Timeouts + Close notify     │
└─────────────────────────────────────────────────────────┘
```

### Why `:on-channel-ready` instead of persistent handlers?
- Clean mental model: each connection starts fresh
- No hidden state accumulating across reconnects
- Application explicitly controls what to restore
- Deterministic: same function runs each time

---

## Implementation Status (2025-12-20)

**Completed:**
- ✅ Receive queue with waiters, timeouts, buffer
- ✅ Injectable time system for testable async code
- ✅ 39 unit tests passing
- ✅ Send queue fix: throws on failure (not silent loss)
- ✅ Client integration (both BB and Scittle)
- ✅ `:on-channel-ready` hook after every handshake
- ✅ `take!` API for RPC-style request/response
- ✅ Fresh recv-queue on each reconnect

**Client Feature Parity:**

| Feature | client_bb.clj | client_scittle.cljs |
|---------|---------------|---------------------|
| `recv-queue` require | ✅ | ✅ |
| `:recv-queue` in state | ✅ | ✅ |
| `create-recv-queue!` helper | ✅ | ✅ |
| `put!` on user messages | ✅ | ✅ |
| `close!` on disconnect | ✅ | ✅ |
| Fresh queue on reconnect | ✅ | ✅ |
| `:on-channel-ready` hook | ✅ | ✅ |
| `take!` API | ✅ | ✅ |
| `recv-queue-stats` API | ✅ | ✅ |

**Files:**
- `src/sente_lite/recv_queue.cljc` - Receive queue implementation
- `src/sente_lite/test_helpers.cljc` - Injectable time system
- `src/sente_lite/client_bb.clj` - BB client with recv-queue
- `src/sente_lite/client_scittle.cljs` - Browser client with recv-queue
- `test/scripts/recv_queue/test_recv_queue_bb.bb` - Unit tests

---

## Open Questions

1. **Subscription API:** Currently separate from recv_queue. Subscriptions use `:on-message` callback, waiters use `take!`. Could unify in Phase 2.
2. ~~**Buffer on close:** Return buffered messages or drop them?~~ **Resolved:** Returns buffered messages in close result.
3. ~~**Send queue fix:** Throw on send failure vs return false and check?~~ **Resolved:** Throws on failure.
4. **Batching (Phase 2):** How does batching interact with ordering?
