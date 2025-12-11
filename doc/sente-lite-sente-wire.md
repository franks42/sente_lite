# Sente-lite ↔ Sente Wire Compatibility Analysis

## Executive Summary

**Current Status**: Sente-lite and Sente are **NOT wire-compatible**.

However, the formats are conceptually similar - both wrap Clojure data for WebSocket transmission. This document analyzes the differences and outlines a path to wire compatibility.

**Goal**: Enable sente-lite clients (BB/Scittle) to communicate with Sente servers, and vice versa.

---

## Why Sente-lite Exists

Sente requires:
- `core.async` - Not available in Babashka
- Compiled ClojureScript - Not compatible with Scittle/SCI interpreter
- JVM-specific server adapters

Sente-lite was created to provide similar functionality for:
- **Babashka** - Server and client
- **Scittle** - Browser client (SCI-interpreted ClojureScript)

---

## Wire Format Comparison

### Sente Wire Format

From `taoensso.sente` source (v1.21.0):

```clojure
;; Event format
[event-id ?data]
;; Examples:
[:my-app/hello {:user "alice"}]
[:chsk/handshake [uid csrf-token handshake-data first-handshake?]]

;; With callback (for request/reply)
[<clj> <?cb-uuid>]

;; Special client-side events:
[:chsk/ws-ping]
[:chsk/handshake [<?uid> nil <?handshake-data> <first-handshake?>]]
[:chsk/state [<old-state-map> <new-state-map> <open-change?>]]
[:chsk/recv <ev-as-pushed-from-server>]

;; Special server-side events:
[:chsk/ws-ping]
[:chsk/ws-pong]
[:chsk/uidport-open <uid>]
[:chsk/uidport-close <uid>]
[:chsk/bad-event <event>]
```

**Key characteristics**:
- Events are **vectors**: `[event-id data]`
- Event IDs are **namespaced keywords**: `:my-app/action`
- System events use `:chsk/*` namespace
- Callbacks use UUID wrapping: `[event cb-uuid]`
- Serialization via "packers" (EDN, Transit, MessagePack, etc.)

### Sente-lite Wire Format

From `sente-lite.wire-multiplexer`:

```clojure
;; Message format
{:type "message-type"
 :data {...}
 :timestamp 1234567890}

;; With envelope (multiplexed)
{:format "EDN"
 :payload "serialized-data"}

;; Channel operations
{:type "subscribe" :channel-id "my-channel"}
{:type "publish" :channel-id "my-channel" :data {...}}
{:type "ping"}
{:type "pong" :timestamp 123 :original-timestamp 456}
```

**Key characteristics**:
- Messages are **maps**: `{:type ... :data ...}`
- Types are **strings**: `"subscribe"`, `"publish"`
- No namespaced event IDs
- Envelope wrapping for format negotiation
- Serialization via wire-format system (JSON, EDN, Transit)

---

## Side-by-Side Comparison

| Aspect | Sente | Sente-lite |
|--------|-------|------------|
| **Structure** | Vector `[id data]` | Map `{:type :data}` |
| **Event IDs** | Keywords `:ns/event` | Strings `"event"` |
| **System prefix** | `:chsk/*` | None (just types) |
| **Callbacks** | UUID in vector | RPC request-id in map |
| **Handshake** | `:chsk/handshake` event | None (implicit) |
| **State tracking** | `:chsk/state` events | Connection state atom |
| **Ping/pong** | `:chsk/ws-ping/pong` | `{:type "ping/pong"}` |
| **Serialization** | Packers | Wire formats |
| **Channel system** | User-level routing | Built-in pub/sub |

---

## Why the Divergence?

Looking at the history, sente-lite diverged because:

1. **Simplicity for BB/Scittle**: Maps are easier to work with in constrained environments
2. **No core.async**: Sente's callback system relies on core.async channels
3. **Built-in channels**: Sente-lite added pub/sub as first-class feature
4. **JSON-first**: Browser compatibility prioritized JSON over EDN

**However**, the core concepts are the same:
- Both send Clojure data over WebSocket
- Both support request/reply patterns
- Both have ping/pong for connection health
- Both support multiple serialization formats

---

## Path to Wire Compatibility

### Option 1: Sente-lite Speaks Sente Protocol

Add a "Sente compatibility mode" to sente-lite:

```clojure
;; sente-lite with Sente wire format
(def sente-mode? true)

;; Convert sente-lite message to Sente format
(defn ->sente-event [msg]
  (let [event-id (keyword "sente-lite" (:type msg))]
    [event-id (:data msg)]))

;; Convert Sente event to sente-lite message
(defn <-sente-event [[event-id data]]
  {:type (name event-id)
   :data data
   :timestamp (System/currentTimeMillis)})
```

### Option 2: Protocol Adapter Layer

Create an adapter that translates between formats:

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│ Sente-lite  │ ←→  │  Adapter    │ ←→  │   Sente     │
│   Client    │     │   Layer     │     │   Server    │
└─────────────┘     └─────────────┘     └─────────────┘
```

### Option 3: Native Sente Format in Sente-lite

Rewrite sente-lite to use Sente's wire format natively:

```clojure
;; Instead of:
{:type "subscribe" :channel-id "ch1"}

;; Use:
[:sente-lite/subscribe {:channel-id "ch1"}]
```

---

## Recommended Approach: Option 3 (Native Sente Format)

### Rationale

1. **True compatibility** - Can talk to real Sente servers
2. **Familiar API** - Sente users know the format
3. **Future-proof** - Aligned with ecosystem
4. **Minimal overhead** - No translation layer

### Implementation Plan

#### Phase 1: Core Event Format

Change message structure from maps to vectors:

```clojure
;; Before (sente-lite)
{:type "ping"}
{:type "subscribe" :channel-id "ch1" :data {:user "alice"}}

;; After (Sente-compatible)
[:chsk/ws-ping]
[:sente-lite/subscribe {:channel-id "ch1" :user "alice"}]
```

#### Phase 2: System Events

Adopt Sente's system event namespace:

| Sente-lite Current | Sente Compatible |
|--------------------|------------------|
| `{:type "ping"}` | `[:chsk/ws-ping]` |
| `{:type "pong"}` | `[:chsk/ws-pong]` |
| Connection open | `[:chsk/uidport-open uid]` |
| Connection close | `[:chsk/uidport-close uid]` |

#### Phase 3: Handshake Protocol

Implement Sente's handshake:

```clojure
;; Server sends on connect:
[:chsk/handshake [uid csrf-token handshake-data first?]]

;; Client receives and stores uid
```

#### Phase 4: Callback/Reply System

Adopt Sente's callback UUID pattern:

```clojure
;; Request with callback
[[:my-app/get-user {:id 123}] "cb-uuid-123"]

;; Reply
[:chsk/reply {:cb-uuid "cb-uuid-123" :data {:name "Alice"}}]
```

#### Phase 5: Packer Compatibility

Ensure sente-lite's wire formats match Sente's packers:
- EDN packer ↔ `:edn` wire format
- Transit packer ↔ `:transit-json` wire format

---

## Migration Strategy

### For Existing Sente-lite Users

1. **Deprecation period**: Support both formats temporarily
2. **Auto-detection**: Detect incoming format, respond in same format
3. **Config flag**: `{:wire-format :sente-compat}` to opt-in

### For New Users

Default to Sente-compatible format.

---

## Testing Plan

1. **Unit tests**: Verify format conversion
2. **Integration tests**: Sente-lite client ↔ Sente server
3. **Integration tests**: Sente client ↔ Sente-lite server (if applicable)
4. **Stress tests**: Performance comparison

---

## Open Questions

1. **CSRF handling**: Sente has built-in CSRF. Do we need it for BB clients?
2. **Ajax fallback**: Sente supports Ajax long-polling. Should sente-lite?
3. **User ID model**: Sente's uid system vs sente-lite's conn-id system
4. **Channel system**: Keep sente-lite's pub/sub or align with Sente's event routing?

---

## Conclusion

The formats are "similar in spirit" - both accomplish the same goal of sending Clojure data over WebSocket. The divergence was pragmatic (simplicity for BB/Scittle) but not fundamental.

**Recommendation**: Adopt Sente's wire format natively in sente-lite v2.0 to enable true interoperability. This would allow:

- Sente-lite (BB) clients connecting to existing Sente servers
- Gradual migration from Sente to sente-lite
- Shared tooling and debugging

---

## References

- [Sente v1.21.0 Source](https://github.com/taoensso/sente/blob/master/src/taoensso/sente.cljc)
- [Sente Wiki](https://github.com/taoensso/sente/wiki)
- [Sente-lite wire-multiplexer.cljc](../src/sente_lite/wire_multiplexer.cljc)
- [Sente-lite server.cljc](../src/sente_lite/server.cljc)

---

**Document Status**: Initial Analysis
**Last Updated**: 2025-12-10
**Author**: Cascade AI Assistant
