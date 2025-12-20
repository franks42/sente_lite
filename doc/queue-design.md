# sente-lite Queue Design

**Created:** 2025-12-20
**Status:** Implementation in progress

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
When queue is full, `enqueue!` returns `:rejected`. Application decides:
- Drop the message
- Retry later
- Apply backpressure upstream

### On Disconnect
**Current behavior (bug):** Messages silently lost - `on-send` returns false but queue counts as "sent"

**Correct behavior:**
- Stop flush loop
- Return unsent messages to application via callback
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
1. Check subscriptions by channel-id
2. Check waiters by predicate (FIFO - first matching waiter wins)
3. Buffer if no match (up to max-depth)
4. Drop or call fallback if buffer full

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

## Open Questions

1. **Subscription API:** Should subscriptions be part of recv_queue or separate?
2. **Buffer on close:** Return buffered messages or drop them?
3. **Send queue fix:** Throw on send failure vs return false and check?
4. **Batching (Phase 2):** How does batching interact with ordering?
