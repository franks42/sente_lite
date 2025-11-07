# Trove Event ID Mapping for Sente-Lite Phase 2

## Overview

This document maps our current `::event-id` keywords to Trove-style `:namespace.component/event` format, following Sente v1.21.0 conventions.

**Current**: 98 logging calls across 6 files
**Target**: ~60 calls (reduce by ~40%)
**Pattern**: `:sente-lite.{component}/{event-type}`

---

## Event ID Mapping by Component

### Server Lifecycle (:info level)
**Component**: `sente-lite.server`

| Old ID | New ID | Level | Keep? |
|--------|--------|-------|-------|
| `::server-starting` | `:sente-lite.server/starting` | :info | ✅ |
| `::server-started` | `:sente-lite.server/started` | :info | ✅ |
| `::server-stopping` | `:sente-lite.server/stopping` | :info | ✅ |
| `::server-stopped` | `:sente-lite.server/stopped` | :info | ✅ |

### Connection Management (:debug level)
**Component**: `sente-lite.server`

| Old ID | New ID | Level | Keep? |
|--------|--------|-------|-------|
| `::connection-added` | `:sente-lite.server/conn-added` | :debug | ✅ |
| `::connection-removed` | `:sente-lite.server/conn-removed` | :debug | ✅ |
| `::websocket-opened` | `:sente-lite.server/ws-open` | :debug | ✅ |
| `::websocket-closed` | `:sente-lite.server/ws-close` | :debug | ✅ |

### Request Handling (:trace level - internal flow)
**Component**: `sente-lite.server`

| Old ID | New ID | Level | Keep? |
|--------|--------|-------|-------|
| `::http-request` | `:sente-lite.server/http-req` | :trace | ✅ |
| `::websocket-request` | `:sente-lite.server/ws-req` | :trace | ✅ |
| `::websocket-message-received` | `:sente-lite.server/ws-msg-recv` | :trace | ✅ |

### Message Routing (:trace level - internal flow)
**Component**: `sente-lite.server`

| Old ID | New ID | Level | Keep? |
|--------|--------|-------|-------|
| `::message-parsed` | `:sente-lite.server/msg-parsed` | :trace | ⚠️ Too verbose |
| `::message-sent` | `:sente-lite.server/msg-sent` | :trace | ⚠️ Too verbose |
| `::message-wrapped` | `:sente-lite.server/msg-wrapped` | :trace | ❌ Remove |
| `::message-unwrapped` | `:sente-lite.server/msg-unwrapped` | :trace | ❌ Remove |

### Pub/Sub (:debug level)
**Component**: `sente-lite.pubsub`

| Old ID | New ID | Level | Keep? |
|--------|--------|-------|-------|
| `::subscription-added` | `:sente-lite.pubsub/sub-added` | :debug | ✅ |
| `::subscription-removed` | `:sente-lite.pubsub/sub-removed` | :debug | ✅ |
| `::subscription-request` | `:sente-lite.pubsub/sub-req` | :trace | ⚠️ Maybe remove |
| `::subscription-rejected` | `:sente-lite.pubsub/sub-rejected` | :warn | ✅ |

### RPC (:debug level)
**Component**: `sente-lite.rpc`

| Old ID | New ID | Level | Keep? |
|--------|--------|-------|-------|
| `::rpc-request-sent` | `:sente-lite.rpc/req-sent` | :trace | ✅ |
| `::rpc-response-sent` | `:sente-lite.rpc/resp-sent` | :trace | ✅ |
| `::rpc-response-failed` | `:sente-lite.rpc/resp-failed` | :error | ✅ |
| `::rpc-request-expired` | `:sente-lite.rpc/req-expired` | :warn | ✅ |
| `::rpc-cleanup-completed` | `:sente-lite.rpc/cleanup-done` | :trace | ❌ Remove |

### Heartbeat (:trace level - internal)
**Component**: `sente-lite.heartbeat`

| Old ID | New ID | Level | Keep? |
|--------|--------|-------|-------|
| `::heartbeat-stopped` | `:sente-lite.heartbeat/stopped` | :debug | ✅ |
| (heartbeat pings) | `:sente-lite.heartbeat/ping` | :trace | ❌ Too noisy |

### Serialization (:trace level - very verbose)
**Component**: `sente-lite.format`

| Old ID | New ID | Level | Keep? |
|--------|--------|-------|-------|
| `::multiplex-serialize-start` | - | :trace | ❌ Remove |
| `::multiplex-serialize-complete` | `:sente-lite.format/mux-serial` | :trace | ⚠️ Keep only on error |
| `::multiplex-deserialize-start` | - | :trace | ❌ Remove |
| `::multiplex-deserialize-complete` | `:sente-lite.format/mux-deserial` | :trace | ⚠️ Keep only on error |

---

## Log Level Philosophy (from Sente v1.21.0)

### :trace (40% - internal flow, very verbose)
- Message routing details
- Request/response flow
- Serialization steps
- Can be filtered out in production

### :debug (30% - lifecycle events)
- Connection added/removed
- Subscription changes
- RPC calls
- Good for development

### :info (20% - important events)
- Server starting/stopping
- Configuration changes
- Retained messages sent

### :warn (5% - anomalies)
- Subscription rejected
- RPC timeout
- Recoverable errors

### :error (5% - failures)
- Connection errors
- RPC failures
- Serialization errors

---

## Reduction Strategy

**Keep (~60 calls)**:
- ✅ All :error level (failures)
- ✅ All :warn level (anomalies)
- ✅ All :info level (server lifecycle)
- ✅ Most :debug level (connections, subscriptions)
- ✅ Some :trace level (main flow only)

**Remove (~38 calls)**:
- ❌ Redundant message wrapping/unwrapping
- ❌ Start/complete pairs (keep only complete or error)
- ❌ Too-granular internal flow (heartbeat pings)
- ❌ Serialization step logging (unless error)

---

## Implementation Order

1. **server.cljc** (39 calls → ~25 calls)
   - Update server lifecycle events
   - Convert connection events to :debug
   - Convert message routing to :trace
   - Remove redundant logging

2. **channels.cljc** (17 calls → ~12 calls)
   - Update channel lifecycle
   - Convert message flow to :trace

3. **transit_multiplexer.cljc** (13 calls → ~5 calls)
   - Keep only error cases
   - Remove start/complete pairs

4. **Other files** (~29 calls → ~18 calls)
   - Similar pattern: lifecycle stays, flow becomes :trace, redundancy removed

---

## Example Transformations

### BEFORE (our current style)
```clojure
(tel/event! ::connection-added {:conn-id conn-id :timestamp ts})
(tel/log! :debug "Heartbeat ping" {:conn-id id})
(tel/event! ::multiplex-serialize-start {:data data})
```

### AFTER (Trove style)
```clojure
;; Connection event - keep as :debug
(tel/log! {:level :debug
           :id :sente-lite.server/conn-added
           :data {:conn-id conn-id}})

;; Heartbeat - remove (too verbose)
;; (removed)

;; Serialization - keep only if error occurs
;; (conditional logging only)
```

---

## Success Criteria

- ✅ All event IDs use `:namespace.component/event` format
- ✅ Total calls reduced from 98 to ~60
- ✅ Appropriate log levels (:trace for flow, :debug for lifecycle)
- ✅ All tests still pass
- ✅ No functionality lost (just less verbose logging)
