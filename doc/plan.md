# sente-lite Implementation Plan

**Version:** 2.5
**Created:** 2025-10-24
**Last Updated:** 2025-12-22
**Status:** Production-ready with 12-test suite, nREPL module complete, browser bundle ready

---

## Updates Log

### 2025-12-22: Cleanup and Distribution

**Cleanup completed:**
- Removed 45+ stale test files (~5,500 lines deleted)
- Archived 23 old docs to `doc/archive/`
- Test suite consolidated to 12 focused tests

**Browser bundle added:**
- `dist/sente-lite-nrepl.cljs` - 70KB source bundle
- Automated testing in Phase 5 of test suite
- Local Maven: `clojure -T:build install`

**Documentation updated:**
- CONTEXT.md refreshed with current state
- All module READMEs current
- Stale references removed

---

### 2025-12-20: Internal Unification - COMPLETE ✅

**Milestone:** v2.8.0-internal-unification

**What Was Changed:**

Phase 2 of the subscription API unification: refactored `:on-message` and `take!` to use the
unified handler registry internally. This eliminates code duplication and creates a single
message dispatch path.

**Changes:**

1. **Removed recv-queue from clients:**
   - Removed `:recv-queue` state from client
   - Removed `create-recv-queue!` helper
   - Removed `recv-queue-stats` function
   - Deleted `src/sente_lite/recv_queue.cljc` source file
   - Deleted `test/scripts/recv_queue/` test directory

2. **`:on-message` now uses handler registry:**
   - Registered as catch-all handler (`:event-id :*`) in `make-client!`
   - Callback adapts message format: `(on-message-fn event-id data)`
   - No behavioral change for users

3. **`take!` now delegates to `on!`:**
   - Simple wrapper: `(on! client-id (assoc opts :once? true))`
   - Returns handler-id (can be cancelled with `off!`)
   - Same callback behavior: receives `{:event-id _ :data _}` or `{:error :timeout/:closed}`

4. **Added `notify-once-handlers-closed!`:**
   - Called on disconnect to notify all `:once?` handlers
   - Replaces recv-queue close notification behavior
   - Callbacks receive `{:error :closed :reason :disconnected}`

**Pattern Enforcement:**

> **Register handler BEFORE sending request** - This is now the only supported pattern.
> The previous recv-queue buffering was a workaround for timing issues that encouraged
> an anti-pattern. With the unified registry, handlers must be registered first.

**Files Modified:**
- `src/sente_lite/client_bb.clj` - Removed recv-queue, refactored take! and on-message
- `src/sente_lite/client_scittle.cljs` - Same changes for browser

**Test Results:**
```
on!/off! API Tests: 30 tests ✅
Full Test Suite: All categories passing ✅
```

---

### 2025-12-20: Unified on!/off! Handler API - COMPLETE ✅

**Milestone:** v2.7.0-on-off-api

**What Was Built:**

1. **Handler Registry System:**
   - Atom-based `{:handlers (atom {})}` in client state
   - Handler-id based registration and removal
   - Thread-safe dispatch to all matching handlers

2. **on! Function - Register Handlers:**
   ```clojure
   (on! client-id {:event-id :my/event    ;; Match specific event (or :* for all)
                   :pred (fn [msg] ...)   ;; Custom predicate matching
                   :once? true            ;; Auto-remove after first match
                   :timeout-ms 5000       ;; Timeout with {:error :timeout}
                   :callback (fn [msg] ...)})
   ;; Returns handler-id string
   ```

3. **off! Function - Remove Handlers:**
   ```clojure
   (off! client-id handler-id)           ;; Remove by handler-id
   (off! client-id {:event-id :my/event}) ;; Remove by event-id
   (off! client-id :all)                  ;; Remove all handlers
   ```

4. **handler-count Function:**
   ```clojure
   (handler-count client-id) ;; => number of registered handlers
   ```

5. **Dispatch Order:**
   - All matching handlers in the unified registry receive the message
   - `:once?` handlers auto-removed after first match
   - Timeout handlers callback with `{:error :timeout :handler-id "..."}`
   - Note: recv-queue removed in v2.8.0 - all dispatch now via unified registry

6. **Platform Implementations:**

   | Feature | client_bb.clj | client_scittle.cljs |
   |---------|---------------|---------------------|
   | Handler registry | ✅ atom | ✅ atom |
   | Timeout mechanism | ✅ future/Thread/sleep | ✅ js/setTimeout |
   | Cancel timeout | ✅ future-cancel | ✅ js/clearTimeout |
   | SCI-compatible | N/A | ✅ no destructuring |

**Test Results:**
```
on!/off! API Tests: 30 tests ✅ (8 test groups)
Full Test Suite: All 4 categories passing ✅
```

**Use Cases:**
- Replace on-message for event-specific handlers
- RPC-style request/response with timeout
- One-shot handlers for expected responses
- Predicate-based routing for complex matching
- Catch-all handlers with :event-id :*

**Files:**
- `src/sente_lite/client_bb.clj` - BB implementation (+203 lines)
- `src/sente_lite/client_scittle.cljs` - Scittle implementation (+212 lines)
- `test/scripts/test_on_off_api.bb` - Comprehensive test suite
- `doc/api-design.md` - API design documentation

---

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
- ✅ **Unified on!/off! handler API**: Merge recv_queue waiters with on-message subscriptions (v2.7.0-on-off-api)
  - `on!` registers handlers with event-id, pred, once?, timeout-ms, callback
  - `off!` removes by handler-id, event-id, or :all
  - See `doc/api-design.md` for full API design
- ✅ **Internal unification**: Refactor take! and :on-message to use handler registry (v2.8.0-internal-unification)
  - Single dispatch path: unified handler registry only
  - recv-queue removed (was obsolete after on!/off! implementation)
  - `take!` is now thin wrapper around `on!` with `:once? true`
  - `:on-message` registered as catch-all handler (`:event-id :*`)
- **Message batching**: Batch multiple messages for efficiency (future)

### Infrastructure Improvements
- **UUIDv7 for conn-id**: Better uniqueness and sortability
- **Authentication hooks**: Token-based auth, user ID routing
- **Performance optimizations**: See Phase 3 specs in archive

### Observability
- **Prometheus metrics**: Connection counts, message rates
- **Distributed tracing**: Request correlation across client/server

---

## Current Focus (2025-12-22)

### Completed Milestones
1. **nREPL module** - 74 tests, all layers complete
2. **Browser bundle** - 70KB, automated in test suite
3. **Cleanup** - Stale files removed, docs archived
4. **12-test suite** - Wire format, server, channels, nREPL, bundle

### Next Steps
1. **CSRF Token Support** - Foundation for future Ajax/HTTP features
2. **datascript-sync module** - One-way tx-log replication (see below)
3. **Cross-runtime testing** - Verify modules work BB↔Scittle↔nbb
4. **Component system** - 108 tests, multimethod-based lifecycle

---

## CSRF Token Support (Planned)

**Current state:** Wire format supports CSRF (Sente-compatible), but server sends `nil`.

**Why implement:**
- Foundation for future Ajax fallback (HTTP requests need CSRF protection)
- Foundation for HTTP blob transfer (out-of-band large file uploads)
- Makes sente-lite a more complete Sente replacement
- Security best practice

**Implementation scope:**
1. Server accepts `:csrf-token` in config (or auto-generates UUID if not provided)
2. Token sent in handshake: `[:chsk/handshake [uid csrf-token handshake-data first?]]`
3. Clients store token in state, expose via `(get-csrf-token client-id)`
4. Future: validation helper/middleware for HTTP endpoints

**Use case - HTTP blob transfer:**
```
[Browser] ──WebSocket──→ [Server]: "upload 50MB file"
[Server]  ──WebSocket──→ [Browser]: {:upload-url "/api/upload"}
[Browser] ──HTTP POST──→ [Server]: multipart + X-CSRF-Token header
```

---

## Telemetry Strategy (Clarification)

**Current approach - keep it simple:**

| Platform | Library | Filtering | Routing |
|----------|---------|-----------|---------|
| BB/JVM | Timbre (built-in) | ✅ Full (level, ns, appenders) | ✅ Full |
| Browser/Scittle | Trove | ⚠️ Level only | ❌ None (sente routing) |

**Philosophy:** Don't build custom log filtering in sente-lite. Rely on:
- **Server-side:** Timbre handles all filtering/routing (powerful, mature)
- **Browser:** Trove for minimal logging, route interesting events to server via sente
- **Server does heavy lifting:** Filter/aggregate/store logs received from browsers

**Log-routing module:** Current implementation routes browser logs to server via sente channel.
This is intentionally crude - real filtering happens server-side with Timbre.

**Future: Telemere**
- Peter Taoensso's next-gen telemetry (structured, OpenTelemetry-inspired)
- Currently requires ClojureScript compilation (not Scittle/SCI compatible)
- **Watch for:** Telemere Scittle compatibility or Trove improvements
- If Telemere becomes SCI-compatible, adopt it across all platforms

**Browser telemetry alternatives (researched):**
- OpenTelemetry JS SDK - Too heavy, needs bundling, overkill for our use
- Lightweight JS loggers - Just console wrappers, no real routing
- **Conclusion:** Current approach (Trove + sente routing) is pragmatic

---

## datascript-sync Module (Planned)

**Concept:** One-way DataScript replication via tx-log over sente-lite.

```
┌─────────────────────────────────────────────────────────────────┐
│                     SERVER (single writer)                       │
│                                                                  │
│   (d/transact! conn [{:user/name "Alice"}])                     │
│         │                                                        │
│         ↓                                                        │
│   tx-report: {:tx-data [{:e 1 :a :user/name :v "Alice" ...}]}   │
│         │                                                        │
│         └──────── sente publish ─────────────────────────────────┤
└─────────────────────────────────────────────────────────────────┘
                              │
                        [sente-lite channel]
                              │
┌─────────────────────────────────────────────────────────────────┐
│                     CLIENT (read replica)                        │
│                                                                  │
│   receive tx-data → (d/db-with local-db tx-data)                │
│   local queries always consistent with server                   │
└─────────────────────────────────────────────────────────────────┘
```

**Why this pattern:**
- **Single master** - Server writes, clients read (no conflicts, like Datomic)
- **Incremental** - Only tx-data sent, not full DB dumps
- **Deterministic** - Same tx-data → same DB state
- **Event sourcing** - tx-log IS the source of truth
- **Already serializable** - datascript-transit handles it

**Wire format:**
```clojure
{:event-id :datascript/tx
 :data {:tx-data [{:e 1 :a :user/name :v "Alice" :tx 536870913 :added true}]
        :tx-id 536870913}}
```

**Implementation scope:**
1. Server: Listen to DataScript conn, publish tx-data on change
2. Client: Subscribe to channel, apply tx-data with `d/db-with`
3. Use datascript-transit for serialization
4. Optional: tx-id tracking for reconnection/catchup

**Dependencies:** datascript, datascript-transit

---

## Two-Way Sync (Research Notes)

**Status:** Deprioritized - hard problem, no good existing solution to port.

**Research (2025-12-22):**
- [Datsync](https://github.com/metasoarous/datsync) - Unmaintained since 2021
- [Electric Clojure](https://github.com/hyperfiddle/electric) - Compiler-level solution, paradigm shift
- [DataScript](https://github.com/tonsky/datascript) - Intentionally omits sync ("keep library lightweight")
- CRDTs (Automerge, Yjs, Schism) - Complex, specific data structures

**Conclusion:** Two-way sync is essentially distributed consensus. There's a reason Datomic has a single transaction point. Options if needed:
1. Single-master pattern (one writer, readers get tx-log)
2. Electric Clojure adoption (if paradigm fits)
3. CRDTs for specific collaborative editing

**atom-sync module:** Keep Phase 1 (one-way) as-is. Two-way deferred indefinitely.

### Enhancement: Transactional Atom Wrapper (Optimization Path)

**Problem:** One-way atom sync currently sends full state on every change. For large atoms, this is wasteful.

**Solution:** Transactional wrapper that records operations explicitly (like DataScript tx-log):

```clojure
;; Instead of swap! which requires full-state diff
(swap! state assoc :count 42)

;; Use explicit transactional wrapper
(defn tx! [atom & ops]
  (let [tx-id (inc @tx-counter)]
    (swap! atom #(reduce apply-op % ops))
    (publish! {:tx-id tx-id :ops ops})))

;; Usage - operations are data, easily serialized
(tx! state
  [:assoc :count 42]
  [:update-in [:users 0 :name] str " Jr."])
```

**Benefits:**
- **No diffing required** - operations are already the delta
- **Smaller payloads** - send ops, not full state
- **Same pattern as datascript-sync** - tx-log replication
- **Idempotent replay** - tx-id enables exactly-once semantics

**Trade-off:** Requires discipline to use `tx!` instead of direct `swap!`. Options to enforce:
1. Wrap atom in protocol that only exposes `tx!`
2. Use add-watch to detect unauthorized mutations
3. Document as convention (simplest)

**Related:** [editscript](https://github.com/juji-io/editscript) by Juji (same author as datalevin) could be used for computing diffs if full swap! compatibility needed, but explicit ops are simpler.

**Status:** Future enhancement - not blocking Phase 1 one-way sync.

---

## Backlog (From External Reviews)

Items identified from Cascade AI reviews (process-registry, project-SWE, stress-backpressure).
**Not urgent** - captured here for future consideration.

### Technical Debt (Medium Priority)
- [x] ~~Remove `src_legacy/` directory~~ - Removed 2025-12-22 (wire_multiplexer.cljc, transit_multiplexer.cljc)
- [x] ~~Remove deprecated wire format v1~~ - Already done: v1 is detection+rejection only (helpful error messages), no actual v1 support exists
- [ ] Consolidate multiple state atoms in clients
- [ ] Externalize vendored Trove (or keep if stable)

### Server-Side Improvements (Medium Priority)
- [ ] **Outbound backpressure** - Per-connection queuing for slow clients
- [ ] **Broadcast throttling** - Limit CPU/network during spikes
- [ ] **Rate limiting** - Prevent message flooding

### Client-Side Improvements (Low Priority)
- [ ] **Rate limiting** - Configurable send rate limits
- [ ] **Message prioritization** - Control messages over data
- [ ] **Queue rejection callback** - Notify app layer on rejection

### Security Hardening (Low Priority - trusted world)
- [ ] Authentication hooks (JWT, token-based)
- [ ] Connection rate limiting
- [ ] Input validation/sanitization
- [ ] Message encryption option

### Performance (Low Priority)
- [ ] Message batching for high-throughput
- [ ] Compression for large payloads
- [ ] Performance benchmarking suite
- [ ] Memory leak detection for long-running processes

### Testing Gaps (Low Priority)
- [ ] Chaos testing (network failures, partial outages)
- [ ] Stress testing beyond 20 clients
- [ ] Slow client simulation
- [ ] Malformed message handling tests

### Monitoring (Low Priority)
- [ ] Queue depth metrics
- [ ] Rejection rate metrics
- [ ] Send failure metrics
- [ ] Health check endpoints

---

## Archive Reference

For detailed historical planning content, see:
- `doc/plan-archive.md` - Original phase specs, detailed telemere-lite design, old updates log

