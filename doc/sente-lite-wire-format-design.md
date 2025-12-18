# Sente-lite Wire Format Design

## Overview

This document specifies the wire format for sente-lite, designed for **full compatibility with taoensso/sente**.

## Goals

1. **Interoperability**: sente-lite clients can connect to Sente servers
2. **Backward Compatibility**: Migration path for existing sente-lite users
3. **Simplicity**: Minimal changes to existing sente-lite architecture
4. **Babashka/Scittle Support**: Must work without core.async

---

## Sente Wire Format Specification

### Event Structure

All Sente messages are **vectors** with the format:

```clojure
[event-id ?data]
```

- `event-id`: Namespaced keyword (e.g., `:my-app/action`, `:chsk/handshake`)
- `?data`: Optional payload (any Clojure data)

### Request/Reply Pattern

For events that expect a reply:

```clojure
;; Client sends (with callback UUID)
[[event-id data] "cb-uuid-123"]

;; Server replies
[:chsk/reply {:cb-uuid "cb-uuid-123" :data response-data}]
```

### System Events

| Event ID | Direction | Data Format | Description |
|----------|-----------|-------------|-------------|
| `:chsk/handshake` | S→C | `[uid csrf-token handshake-data first?]` | Connection established |
| `:chsk/state` | S→C | `[old-state new-state]` | Connection state change |
| `:chsk/recv` | S→C | `[event-id data]` | Server push (wrapped) |
| `:chsk/ws-ping` | Both | `nil` | Keep-alive ping |
| `:chsk/ws-pong` | Both | `nil` | Keep-alive pong |
| `:chsk/uidport-open` | Internal | `uid` | Client connected |
| `:chsk/uidport-close` | Internal | `uid` | Client disconnected |
| `:chsk/bad-event` | Internal | `event` | Malformed event |
| `:chsk/reply` | S→C | `{:cb-uuid X :data Y}` | Callback reply |

### Serialization

Sente uses "packers" for serialization:
- **EDN** (default): `pr-str` / `edn/read-string`
- **Transit+JSON**: For better performance and browser compatibility

---

## Sente-lite Wire Format

### Design Decisions

1. **Adopt Sente event format**: `[event-id data]` vectors
2. **Keep sente-lite extensions**: Channel pub/sub as custom events
3. **Reject legacy map format**: Map-based messages are deprecated and not supported
4. **Dual packer support**: EDN and Transit+JSON

### Event Mapping

| Legacy map format (deprecated) | Sente-compatible (canonical) |
|-------------------------------|------------------------------|
| `{:type "ping"}` | `[:chsk/ws-ping]` |
| `{:type "pong" :timestamp T}` | `[:chsk/ws-pong {:timestamp T}]` |
| `{:type "subscribe" :channel-id C}` | `[:sente-lite/subscribe {:channel-id C}]` |
| `{:type "unsubscribe" :channel-id C}` | `[:sente-lite/unsubscribe {:channel-id C}]` |
| `{:type "publish" :channel-id C :data D}` | `[:sente-lite/publish {:channel-id C :data D}]` |
| `{:type "message" :data D}` | `[:sente-lite/message D]` |
| `{:type "rpc" :method M :params P :id I}` | `[[:sente-lite/rpc {:method M :params P}] I]` |

### Handshake Protocol

```clojure
;; Server sends on WebSocket connect:
[:chsk/handshake [uid csrf-token {:sente-lite-version "2.x"} true]]

;; Client receives and stores uid
```

### Callback System

```clojure
;; Client sends request with callback
[[:my-app/get-user {:id 123}] "cb-uuid-456"]

;; Server sends reply
[:chsk/reply {:cb-uuid "cb-uuid-456" :data {:name "Alice"}}]
```

### Channel Pub/Sub (sente-lite extension)

```clojure
;; Subscribe to channel
[:sente-lite/subscribe {:channel-id "chat-room-1"}]

;; Server confirms
[:sente-lite/subscribed {:channel-id "chat-room-1" :success true}]

;; Publish to channel
[:sente-lite/publish {:channel-id "chat-room-1" :data {:msg "Hello!"}}]

;; Receive channel message
[:sente-lite/channel-msg {:channel-id "chat-room-1" :data {:msg "Hello!"} :from uid}]
```

---

## Implementation Plan

### Phase 1: Core Wire Format

Use `sente-lite.wire-format` namespace:

```clojure
(ns sente-lite.wire-format
  "Sente-compatible wire format for sente-lite")

;; Event encoding
(defn encode-event
  "Encode a sente-lite event to Sente wire format"
  [event-id data]
  [event-id data])

(defn encode-event-with-callback
  "Encode event with callback UUID"
  [event-id data cb-uuid]
  [[event-id data] cb-uuid])

;; Event decoding
(defn decode-event
  "Decode Sente wire format to sente-lite event"
  [wire-data]
  (cond
    ;; Event with callback: [[event-id data] cb-uuid]
    (and (vector? wire-data)
         (= 2 (count wire-data))
         (vector? (first wire-data)))
    {:event-id (ffirst wire-data)
     :data (second (first wire-data))
     :cb-uuid (second wire-data)}
    
    ;; Simple event: [event-id data]
    (and (vector? wire-data)
         (keyword? (first wire-data)))
    {:event-id (first wire-data)
     :data (second wire-data)}
    
    :else
    {:error :invalid-format :raw wire-data}))
```

### Phase 2: System Events

```clojure
;; Handshake
(defn make-handshake [uid csrf-token handshake-data first?]
  [:chsk/handshake [uid csrf-token handshake-data first?]])

;; Reply
(defn make-reply [cb-uuid data]
  [:chsk/reply {:cb-uuid cb-uuid :data data}])

;; Ping/Pong
(def ws-ping [:chsk/ws-ping])
(def ws-pong [:chsk/ws-pong])
```

### Phase 3: Backward Compatibility

```clojure
(defn detect-wire-version
  "Detect if message is legacy map format or vector event format"
  [raw-message]
  (cond
    (and (string? raw-message)
         (str/starts-with? raw-message "["))
    :v2
    
    (and (string? raw-message)
         (str/starts-with? raw-message "{"))
    :v1
    
    :else
    :unknown))

(defn parse-message
  "Parse message, auto-detecting version"
  [raw-message format-spec]
  (let [version (detect-wire-version raw-message)
        wire-format (wire/get-format format-spec)
        parsed (wire/deserialize wire-format raw-message)]
    (case version
      :v2 (decode-event parsed)
      :v1 {:error :v1-format-not-supported}
      {:error :unknown-version})))
```

### Phase 4: Integration

Update server and client to use vector-based event format:

1. **Server**: Send vector events
2. **Client**: Send vector events, handle vector responses

---

## Migration Strategy

### For Existing sente-lite Users

1. **Phase 1**: Adopt vector-based events as the canonical wire format
2. **Phase 2**: Remove legacy map-format references from docs/tests

### Configuration

```clojure
;; Server config
{:wire-format :edn}          ;; EDN serialization
{:wire-format :json}         ;; JSON serialization
{:wire-format :transit-json} ;; Transit+JSON serialization
```

---

## Testing Plan

1. **Unit Tests**: Encode/decode round-trip
2. **Integration**: sente-lite client ↔ Sente server
3. **Compatibility**: Legacy map-format inputs are rejected with clear errors
4. **Performance**: Compare EDN vs JSON vs Transit+JSON serialization

---

## Open Questions

1. **CSRF Handling**: Sente requires CSRF token. How to handle in BB clients?
   - **Proposal**: Fetch from `/csrf-token` endpoint before connecting

2. **User ID Model**: Sente uses `uid`, sente-lite uses `conn-id`
   - **Proposal**: Add `uid` support, keep `conn-id` as internal identifier

3. **Ajax Fallback**: Sente supports Ajax long-polling
   - **Proposal**: Not needed for sente-lite (WebSocket-only is fine for BB/Scittle)

---

## Actual Sente Wire Format (Captured 2025-12-11)

Testing with a real Sente server revealed the actual wire format:

### CSRF Token Handling

- **Disable at Sente level**: `:csrf-token-fn nil` in `make-channel-socket!`
- **Client-id required**: Pass `?client-id=UUID` in WebSocket URL query params
- **CSRF in URL**: When enabled, pass `?csrf-token=TOKEN` in URL

### Captured Wire Messages

```clojure
;; Server → Client: Handshake (wrapped in buffer vector)
[[:chsk/handshake ["uid-uuid" nil]]]
;; Note: Only 2 elements [uid csrf-token], not 4 as documented

;; Client → Server: Simple event
[:test/echo {:msg "Hello"}]

;; Client → Server: Event with callback
[[:test/echo {:data "with callback"}] "cb-uuid-123"]

;; Server → Client: Reply with callback
[{:echo {:data "..."}, :timestamp 123} "cb-uuid-123"]
;; Note: NOT wrapped in [:chsk/reply ...], just [data cb-uuid]
```

### Key Differences from Documentation

1. **Handshake**: Wire format is `[uid csrf-token]` (2 elements), not 4
2. **Event buffering**: Server wraps events in outer vector `[[event]]`
3. **Reply format**: `[reply-data cb-uuid]` on wire, `:chsk/reply` is client-side wrapper
4. **Ping response**: Server replies with string `"pong"` not event

### Implications for sente-lite

- Our vector-based format works for sente-lite ↔ sente-lite
- For Sente interop, need to handle buffered event unwrapping
- Reply format difference is internal (client-side processing)

---

## References

- [Sente GitHub](https://github.com/taoensso/sente)
- [Sente Wiki](https://github.com/taoensso/sente/wiki)
- [sente-lite wire-format.cljc](../src/sente_lite/wire_format.cljc)
- [sente-lite-sente-wire.md](./sente-lite-sente-wire.md)

---

**Document Status**: Draft
**Created**: 2025-12-10
**Author**: Cascade AI Assistant
