# sente-lite Implementation Plan

**Version:** 2.3
**Created:** 2025-10-24
**Last Updated:** 2025-12-20
**Status:** v2.4.0 - Queue System COMPLETE ✅ (Send + Receive)

---

## Updates Log

### 2025-12-20: Receive Queue + Client Integration - COMPLETE ✅

**Milestone:** v2.4.0-recv-queue

**What Was Built:**

1. **Receive Queue (`recv_queue.cljc`):**
   - Waiters with predicate matching + timeout + callback
   - Buffer for unmatched messages (shock absorber for burst traffic)
   - `take!` API for RPC-style request/response
   - `rpc-waiter` helper for common pattern
   - Injectable time system for testable async code
   - FIFO waiter matching (first registered wins)

2. **Client Integration (both BB and Scittle):**
   - `:recv-queue` state in client
   - `create-recv-queue!` helper (fresh queue on each connection)
   - User messages `put!` to recv-queue
   - `:on-channel-ready` hook after every handshake
   - `close!` notifies all waiters with `{:error :closed}`
   - `take!` and `recv-queue-stats` API functions

3. **Client Feature Parity:**

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

4. **Documentation (`doc/queue-design.md`):**
   - Use cases: nREPL, logging, atom sync, file upload
   - Send-side backpressure explanation
   - Receive-side burst tolerance (buffer as shock absorber)
   - Waiter lifecycle and timeout handling
   - `:on-channel-ready` pattern for fresh handler registration

**Test Results:**
```
Receive Queue Unit Tests: 39 tests ✅ (13 test groups)
Full Test Suite: All passing ✅
```

**API Usage:**
```clojure
;; RPC-style request/response
(send! client [:nrepl/eval {:id "req-1" :code "(+ 1 2)"}])
(take! client {:pred #(= (:request-id %) "req-1")
               :timeout-ms 5000
               :callback (fn [response]
                           (if (:error response)
                             (handle-error response)
                             (handle-success response)))})

;; Using rpc-waiter helper
(take! client (rpc-waiter "req-1" 5000 handle-response))

;; Fresh handler registration on every connection
(make-client {:url "ws://..."
              :on-channel-ready (fn [client-id]
                                  ;; Register waiters here
                                  ...)})
```

**Files:**
- `src/sente_lite/recv_queue.cljc` - Implementation
- `src/sente_lite/test_helpers.cljc` - Injectable time system
- `test/scripts/recv_queue/test_recv_queue_bb.bb` - Unit tests
- `doc/queue-design.md` - Comprehensive design documentation

---

### 2025-12-19: Phase 1 Send Queue - IMPLEMENTATION COMPLETE ✅

**Milestone:** v2.3.0-queue

**What Was Built:**

1. **Core Protocol (`queue.cljc`):**
   - `ISendQueue` protocol: `enqueue!`, `start!`, `stop!`, `queue-stats`
   - Default config: `{:max-depth 1000 :flush-interval-ms 10}`
   - Callbacks: `:on-send`, `:on-error`

2. **BB Implementation (`queue_bb.clj`):**
   - Uses `java.util.concurrent.ArrayBlockingQueue` for thread-safe bounded queue
   - Background flush thread with `Thread/sleep`
   - Non-blocking `.offer` returns `:ok` or `:rejected` (backpressure)
   - Graceful shutdown: waits for flush thread, drains remaining messages
   - Stats: depth, enqueued, sent, dropped, errors

3. **Browser Implementation (`queue_scittle.cljs`):**
   - Atom-based queue with vector storage: `(atom {:queue [] :stats {...}})`
   - `js/setInterval` for periodic flush
   - Bounded enqueue with `:ok` / `:rejected` backpressure
   - SCI-compatible (no destructuring, explicit `first`/`second`)
   - Graceful shutdown: clears interval, flushes remaining

4. **Client Integration:**
   - Both `client_bb.clj` and `client_scittle.cljs` updated
   - Optional `:send-queue` config in `make-client`
   - `send!` routes through queue when enabled
   - `close!` drains queue before WebSocket close
   - New `queue-stats` function for observability

**Test Results (All Passing):**
```
Queue Tests:
  BB Unit: 29 tests ✅
  nbb Unit: 24 tests ✅
  BB-to-BB Integration: 39 tests ✅
  Playwright Browser: 13 tests ✅
```

**Files Created:**
- `src/sente_lite/queue.cljc` - Protocol and defaults
- `src/sente_lite/queue_bb.clj` - BB implementation
- `src/sente_lite/queue_scittle.cljs` - Browser implementation
- `test/scripts/queue/test_queue_bb.bb` - BB unit tests
- `test/scripts/queue/test_queue_nbb.cljs` - nbb unit tests
- `test/scripts/queue/test_queue_integration_bb.bb` - Integration test
- `dev/scittle-demo/test-queue-scittle.html` - Browser test page
- `dev/scittle-demo/test-queue-server.bb` - Test server (port 1346)
- `dev/scittle-demo/playwright-queue-test.mjs` - Playwright runner

**API Usage:**
```clojure
;; Create client with queue enabled
(def client (make-client {:url "ws://localhost:1345/"
                          :send-queue {:max-depth 1000
                                       :flush-interval-ms 10}}))

;; Send with backpressure handling
(case (send! client [:my/event {:data "here"}])
  :ok      (println "Queued successfully")
  :rejected (println "Queue full, apply backpressure"))

;; Get queue stats
(queue-stats client)
;; => {:depth 0 :enqueued 100 :sent 100 :dropped 0 :errors 0}
```

**Performance Verified:**
- Enqueue latency: <1ms (non-blocking)
- High throughput: 5000 msgs in 10ms (BB), 1000 msgs in 7ms (browser)
- Zero message loss under normal operation
- Complete drain on graceful shutdown

---

### 2025-12-18: v2.1.0 - Scittle Browser Integration Complete 🎉

**Major Milestone:** Browser client tested with Playwright automated tests.

**Changes Made:**
1. **Fixed SCI/Scittle compatibility in wire_format.cljc:**
   - Removed vector destructuring (replaced with `first`/`second`/`nth`)
   - Changed `trove/log!` to `log!` with `:refer [log!]`
   - Replaced `cljs.reader/read-string` with direct `read-string` (available in SCI)

2. **Fixed client_scittle.cljs:**
   - Changed `trove/log!` to `log!` with `:refer [log!]`

3. **New test infrastructure:**
   - `dev/scittle-demo/test-client-scittle.html` - 16 browser tests
   - `dev/scittle-demo/playwright-client-test.mjs` - Automated Playwright runner
   - `dev/scittle-demo/test-server.bb` - Simple BB server for testing

4. **Cross-platform test matrix updated:**
   - Added "BB Server ↔ Scittle Client (browser)" test
   - All 7 cross-platform tests passing

**Test Results:**
```
[PASS] BB Server <-> BB Client (unit test)
[PASS] BB Server <-> BB Client (multiprocess)
[PASS] nbb Server <-> nbb Client
[PASS] BB Server <-> nbb Client
[PASS] nbb Server <-> BB Client
[PASS] BB Server <-> Scittle Client (browser) ← NEW
[PASS] Sente Server <-> BB Client
```

---

## Executive Summary

sente-lite is a lightweight WebSocket library providing ~85% Sente API compatibility for Babashka, Scittle/SCI, and Node.js environments. This plan outlines the implementation strategy, architecture decisions, and development phases.

## Core Architecture Decisions

### 1. Technology Stack
- **Serialization:** EDN primary (Clojure-to-Clojure default), JSON/Transit available
- **Rationale:** Scittle nREPL uses EDN over WebSocket, EDN performance acceptable for primary use case
- **Async Model:** Callbacks/promises (no core.async)
- **Telemetry:** Alternative to Telemere (custom BB-compatible solution)
- **WebSocket Libraries:**
  - Babashka Server: `org.httpkit.server` (v2.8.0+, BB compatible)
  - Babashka Client: `babashka.http-client.websocket` (built-in since BB 1.1.171)
  - Browser: Native WebSocket API
  - Node.js (nbb): `ws` npm package
  - JVM: http-kit or Aleph

### 2. Design Principles
1. **Native capability first** - Use environment-native features
2. **Sente API compatibility** (~85%) - Ease migration path
3. **Declarative topology** - System configuration as data
4. **Pure business logic** - Separate from I/O concerns
5. **Built-in observability** - Metrics and error handling

### 3. Key Implementation Choices
- **Event format:** `[event-id ?data]` vector pairs
- **Routing:** Map-based dispatch with atom registry
- **Reconnection:** Exponential backoff with configurable limits
- **State management:** Atom-based with callback notifications
- **Message size:** 1MB default limit with warnings at 512KB

### 4. Client Identity and Reconnection Architecture

#### Ephemeral Session ID Design
- **Current Implementation:** Server generates unique `conn-id` per WebSocket connection
- **Lifecycle:** Created on connect, destroyed on disconnect, never reused
- **No Persistence:** Subscriptions, state, and identity are NOT restored automatically after reconnection
- **Rationale:** Keeps infrastructure layer "dumb" about client identity and authentication

#### Security Model
**Why NO persistent client IDs without authentication:**
- Persistent client IDs without authentication enable **impersonation attacks**
- An attacker could reuse a valid UUID to impersonate another client
- Stable identities require proper authentication (JWT, OAuth, mTLS, etc.)

**Architecture Decision:**
- **Infrastructure layer:** Identity-agnostic, provides only ephemeral `conn-id`
- **Application layer:** Responsible for authentication, authorization, and persistent identity
- **Separation of concerns:** Infrastructure handles connection mechanics, application handles business logic

#### Reconnection Strategy
**Infrastructure responsibilities:**
1. Auto-reconnect with exponential backoff
2. WebSocket connection lifecycle management
3. Basic health monitoring (ping/pong)
4. Connection state notifications via hooks

**Application responsibilities:**
1. Track what subscriptions/state need restoration
2. Decide what to restore after reconnection
3. Handle authentication/re-authentication
4. Manage persistent client identity if needed

**Design Pattern: Application-Controlled Restoration**
```clojure
;; Application tracks its own state
(def app-subscriptions (atom #{}))

;; Infrastructure provides hooks
{:on-connect (fn [conn-id]
               ;; Initial setup
               (subscribe-to-channels! @app-subscriptions))

 :on-reconnect (fn [new-conn-id]
                 ;; Application decides what to restore
                 (subscribe-to-channels! @app-subscriptions))

 :on-disconnect (fn [reason]
                  ;; Cleanup if needed
                  )}
```

**Rationale (YAGNI principle):**
- Don't solve problems we don't have yet
- Simpler separation of concerns
- Application knows best what needs restoration
- Avoids security risks of automatic state restoration

#### API Contract
**Infrastructure provides:**
- `:on-connect` - Called when new connection established (initial or reconnection)
- `:on-reconnect` - Called specifically after reconnecting (not initial connect)
- `:on-disconnect` - Called when connection lost
- `:on-error` - Called on connection errors
- Auto-reconnect mechanics with exponential backoff

**Application provides:**
- State tracking (subscriptions, preferences, etc.)
- Restoration logic in `:on-reconnect` hook
- Authentication/authorization logic
- Persistent identity management (if needed)

**Example: Complete Reconnection Flow**
```clojure
#!/usr/bin/env bb
;; Application-controlled subscription restoration

;; APPLICATION STATE (not infrastructure)
(def app-subscriptions (atom #{}))
(def reconnect-count (atom 0))

;; INFRASTRUCTURE STATE
(def ws-client (atom nil))
(def status (atom :disconnected))

(defn subscribe-to-channel! [channel-id]
  (swap! app-subscriptions conj channel-id)
  (when @ws-client
    (ws/send! @ws-client (pr-str {:type :subscribe :channel-id channel-id}))))

(defn attempt-reconnect! []
  (swap! reconnect-count inc)
  (let [client (ws/websocket
                {:uri "ws://localhost:1345/"
                 :on-open (fn [ws]
                            (reset! status :connected)

                            ;; APPLICATION decides what to restore
                            (doseq [channel-id @app-subscriptions]
                              (ws/send! ws (pr-str {:type :subscribe :channel-id channel-id}))))

                 :on-close (fn [ws status reason]
                             (reset! status :disconnected)
                             ;; Auto-reconnect after delay
                             (Thread/sleep 1000)
                             (attempt-reconnect!))})]
    (reset! ws-client client)))
```

**Security Best Practices:**
1. Never trust client-provided IDs without authentication
2. Use ephemeral session IDs for unauthenticated connections
3. Implement proper authentication for persistent identity
4. Validate all client actions server-side
5. Rate-limit reconnection attempts
6. Log security-relevant events (failed auth, suspicious patterns)

## ⚠️ CRITICAL: SCI/Scittle Limitations

**Date Discovered:** 2025-10-29
**Severity:** HIGH - Silent runtime failures in production

### The Destructuring Problem

**SCI (Small Clojure Interpreter) used by Scittle does NOT reliably support destructuring:**

❌ **BROKEN - Function parameter destructuring:**
```clojure
(defn handle-message
  [[event-type event-data]]  ; FAILS with "nth not supported on this type function(...)"
  (case event-type
    :welcome (do-something event-data)
    ...))
```

❌ **BROKEN - Let binding destructuring:**
```clojure
(defn handle-message
  [msg]
  (let [[event-type event-data] msg]  ; ALSO FAILS with same error
    (case event-type
      :welcome (do-something event-data)
      ...)))
```

✅ **WORKS - Explicit first/second calls:**
```clojure
(defn handle-message
  [msg]
  (let [event-type (first msg)        ; ✅ WORKS!
        event-data (second msg)]
    (case event-type
      :welcome (do-something event-data)
      ...)))
```

### Why This Matters

1. **Silent Failures:** Code that works in regular Clojure/ClojureScript will fail mysteriously in Scittle
2. **Cryptic Errors:** Error message "nth not supported on this type function(...)" doesn't clearly indicate destructuring is the issue
3. **Production Impact:** Can cause complete application failure with no obvious cause
4. **Testing Gap:** BB-to-BB tests work fine (no SCI), browser tests fail (uses SCI)

### Coding Rules for Scittle/SCI Code

**ALWAYS follow these rules when writing code that will run in Scittle:**

1. **Never use destructuring in function parameters** - Use simple parameters
2. **Never use destructuring in let bindings** - Use `first`, `second`, `nth`, or explicit accessors
3. **Test in actual browser** - BB tests won't catch these issues
4. **Check existing demos** - heartbeat-demo, pubsub-demo use correct patterns

### Map Destructuring Status

**UNKNOWN:** Whether map destructuring (`:keys`) works in SCI. Needs testing:
```clojure
;; Needs verification:
(let [{:keys [op code id]} event-data]  ; Does this work in SCI?
  ...)
```

If you encounter issues with map destructuring, use explicit `get` calls instead:
```clojure
(let [op (get event-data :op)
      code (get event-data :code)
      id (get event-data :id)]
  ...)
```

### Historical Context

This limitation was discovered during nREPL gateway implementation (2025-10-29) when browser client crashed with "nth not supported" error. The issue was initially confusing because:
- BB-to-BB tests worked perfectly
- The error message was cryptic
- Parameter destructuring looked identical to working demos
- The issue was actually in the `let` binding, not the parameter

**Resolution:** Changed from `let` destructuring to explicit `first`/`second` calls. Browser immediately worked.

### References

- Working examples: `dev/scittle-demo/examples/sente-heartbeat-demo-client.cljs`
- Working examples: `dev/scittle-demo/examples/sente-pubsub-demo-client.cljs`
- Bug context: `CONTEXT.md` (Session 5 - nREPL Gateway BLOCKER RESOLVED)

---

## Version Requirements

### Minimum Versions
- **Babashka:** 1.12.207+ (latest stable, includes http-client.websocket built-in)
- **HTTP Kit:** 2.8.0+ (Babashka/GraalVM compatible)
- **Transit:** cognitect/transit-clj 1.0.333+

### Telemetry Strategy
Since Telemere v1.1.0 doesn't support Babashka (dependency on Encore with incompatible classes), we'll implement a lightweight, BB-compatible telemetry solution inspired by Telemere's API design. **Starting simple with file-based logging** to establish core functionality before adding advanced features.


---

## What's Next (Potential Enhancements)

### Queue System Phase 2  ✅ COMPLETE
- ✅ **Send-side async backpressure**: `enqueue-blocking!` and `enqueue-async!` (v2.5.0-backpressure)
- ✅ **Event-driven async refactor**: Replace polling with true event-driven (v2.6.0-event-driven-async)
  - BB: Waiter list + `compare-and-set!` for atomic claim + `process-waiters!` after flush
  - Scittle: Waiter list + `process-waiters!` hook in `flush!`
  - No polling - callbacks invoked immediately when space freed
  - See `doc/queue-design.md` "Phase 2: Event-Driven Async" for full design
- **Subscription API unification**: Merge recv_queue waiters with on-message subscriptions
- **Message batching**: Batch multiple messages for efficiency

### Infrastructure Improvements
- **UUIDv7 for conn-id**: Better uniqueness and sortability
- **Authentication hooks**: Token-based auth, user ID routing
- **Performance optimizations**: See Phase 3 specs in archive

### Observability
- **Prometheus metrics**: Connection counts, message rates
- **Distributed tracing**: Request correlation across client/server

---

## Archive Reference

For detailed historical planning content, see:
- `doc/plan-archive.md` - Original phase specs, detailed telemere-lite design, old updates log

