# Capability Negotiation Design for sente-lite

**Date**: 2025-10-30
**Version**: 0.12.0-draft
**Status**: Design Proposal

## Table of Contents

- [Overview](#overview)
- [Core Principle](#core-principle)
- [Recommended Approach](#recommended-approach)
- [Capability Data Structure](#capability-data-structure)
- [API Design](#api-design)
- [Message Flow Examples](#message-flow-examples)
- [Implementation Strategy](#implementation-strategy)
- [Backward Compatibility](#backward-compatibility)
- [Feature Detection Pattern](#feature-detection-pattern)
- [Extension Mechanism](#extension-mechanism)
- [Error Handling](#error-handling)
- [Summary](#summary)

## Overview

This document describes a capability negotiation system for sente-lite that allows both server and client to introspect each other's capabilities. This enables dynamic feature detection, service discovery (like nREPL servers on clients), and intelligent feature negotiation (compression, wire formats, etc.).

### Design Goals

- **Declarative**: Capabilities as data structures
- **Simple**: One round-trip handshake
- **Extensible**: User-defined capabilities via `:extensions`
- **Backward compatible**: Legacy clients work with minimal capabilities
- **Efficient**: Piggyback on existing `:welcome` message

## Core Principle

**"Capabilities as Data"**

The key insight is to treat capabilities as **declarative data structures** that both parties exchange during connection handshake. This fits perfectly with sente-lite's philosophy of declarative system topology.

Capabilities are not just booleans ("supports X?") but rich data structures that include:
- Feature sets
- Protocol versions
- Supported formats
- Resource limits
- Service endpoints
- Custom extensions

## Recommended Approach

### Hybrid: Sente-Compatible + Optional Client Capabilities

**How Sente does it (for reference):**
- Server sends `:chsk/handshake [uid nil handshake-data first-handshake?]`
- Client receives, updates state, **does NOT respond**
- **No negotiation** - Both sides pre-configured to match
- Works for Sente because: homogeneous clients, no compression, no service discovery

**Why sente-lite needs more:**
- ✅ Heterogeneous clients (BB vs browser vs embedded)
- ✅ Compression negotiation (some clients support, some don't)
- ✅ Service discovery (clients can offer nREPL!)
- ✅ Dynamic adaptation (server routes based on client capabilities)

**Our approach (Sente-compatible extension):**
```
1. Client connects to server
2. Server sends :chsk/handshake with server capabilities (like Sente)
3. Client receives, stores server capabilities
4. Client OPTIONALLY sends :chsk/client-info (NEW, backward compatible)
5. Server assumes defaults if no client-info received (Sente behavior)
```

**Why this works:**
- ✅ Backward compatible (clients that don't send info work like Sente)
- ✅ Follows Sente's pattern (server-first handshake)
- ✅ Optional negotiation (clients CAN advertise capabilities)
- ✅ Zero round-trips for basic clients (like Sente)
- ✅ One round-trip for advanced clients (service discovery, etc.)

### Alternative Approaches Considered (and rejected)

**Configuration only (pure Sente)**: Doesn't support service discovery or negotiation
**Mandatory bidirectional**: Breaks Sente compatibility, more complex
**HTTP Headers during upgrade**: Limited size, not EDN-friendly
**Separate HTTP endpoint**: Extra round-trip, more complexity

## Capability Data Structure

### Server Capabilities

Sent in `:chsk/handshake` message (Sente-compatible format):

```clojure
;; Sente format: [:chsk/handshake [uid nil handshake-data first-handshake?]]
;; We use handshake-data for capabilities:
[:chsk/handshake
 ["user-123"                    ; uid
  nil                           ; reserved
  {:capabilities                ; handshake-data (NEW - our capabilities)
   {:version "0.12.0"
                :protocol-version 1
                :features #{:heartbeat
                           :auto-reconnect
                           :pub-sub
                           :compression
                           :message-retention
                           :broadcast
                           :echo}
                :wire-formats #{:edn :transit-json :transit-msgpack}
                :compression #{:gzip :none}
                :limits {:max-message-size (* 1024 1024)  ; 1MB
                        :max-connections 1000
                        :heartbeat-interval 30000
                        :max-reconnect-delay 30000}
                :extensions {}}}
  true]}                        ; first-handshake?
```

### Client Capabilities

**OPTIONAL** - Sent after receiving `:chsk/handshake` (NEW message type):

```clojure
;; NEW: Optional client capability advertisement
[:chsk/client-info
 {:capabilities {:version "0.12.0"
                :protocol-version 1
                :features #{:auto-reconnect
                           :compression
                           :nrepl-server    ; ← Client has nREPL!
                           :pub-sub}
                :wire-formats #{:edn :transit-json}
                :compression #{:gzip :none}
                :limits {:max-message-size (* 512 1024)  ; 512KB browser limit
                        :heartbeat-interval 30000}
                :services {:nrepl {:port nil  ; WebSocket via sente
                                  :ops #{:eval :load-file :clone :describe}}}
                :extensions {:browser-type "scittle"
                            :user-agent "Mozilla/5.0 ..."}}}]

;; IMPORTANT: If client doesn't send this, server assumes defaults (Sente behavior)
```

### Field Definitions

#### `:version`
- Semantic version string of sente-lite library
- Used for debugging and compatibility checks
- Example: `"0.12.0"`

#### `:protocol-version`
- Integer protocol version number
- Breaking protocol changes increment this
- Allows server/client to refuse incompatible connections
- Current: `1`

#### `:features`
- Set of supported feature keywords
- Standard features:
  - `:heartbeat` - Ping/pong keepalive
  - `:auto-reconnect` - Automatic reconnection with exponential backoff
  - `:pub-sub` - Channel-based publish/subscribe
  - `:compression` - Message compression
  - `:message-retention` - Server retains messages for offline clients
  - `:broadcast` - Server can broadcast to all clients
  - `:echo` - Server echoes messages back
  - `:nrepl-server` - Client provides nREPL server

#### `:wire-formats`
- Set of supported serialization formats
- Standard formats: `:edn`, `:transit-json`, `:transit-msgpack`
- Allows format negotiation

#### `:compression`
- Set of supported compression algorithms
- Standard: `:gzip`, `:brotli`, `:none`
- Allows compression negotiation

#### `:limits`
- Resource limits and timeouts
- Common fields:
  - `:max-message-size` - Bytes
  - `:max-connections` - Integer (server only)
  - `:heartbeat-interval` - Milliseconds
  - `:max-reconnect-delay` - Milliseconds (client only)

#### `:services`
- Map of service name → service descriptor
- For advertising server-like capabilities on clients
- Example: nREPL server running in browser

#### `:extensions`
- Map of user-defined capability data
- For application-specific features
- No schema restrictions

## Mandatory Features Recommendation

### The Two-Tier Approach

We recommend a two-tier system for mandatory capabilities:

#### Tier 1: Minimal Compliant Implementation (MANDATORY)

Every sente-lite implementation **MUST** support:

```clojure
{:capabilities
 {:wire-formats #{:edn}           ; MANDATORY - Clojure native, works everywhere
  :features #{:handshake          ; MANDATORY - Required for capability negotiation
              :event}             ; MANDATORY - Basic event messaging
  :compression #{:none}}}         ; MANDATORY - Baseline (no compression)
```

**Wire format** (following Sente's approach):
```clojure
;; All messages use same format: [event-vector ?callback-uuid]
[[:user/notification {:msg "Hi"}] nil]              ; One-way
[[:user/get {:id 123}] "uuid-abc-123"] ; With reply
```

**Pros:**
- ✅ Zero dependencies
- ✅ Works in all environments (BB, Scittle, JVM, Node)
- ✅ Simplest possible implementation
- ✅ Guaranteed interoperability
- ✅ Easy to test and verify
- ✅ Single wire format (like Sente)
- ✅ Forward compatible (can add `:reply-fn` later)

**Cons:**
- ❌ No request/response pattern yet
- ❌ No reliability features (ping/pong, auto-reconnect)

**When to use:**
- Learning/experimentation
- Minimal embedded systems
- Proof-of-concept implementations
- When you need absolute minimal footprint

#### Tier 2: Production-Ready Implementation (RECOMMENDED)

Production implementations **SHOULD** support:

```clojure
{:capabilities
 {:wire-formats #{:edn}
  :features #{:handshake
              :event
              :reply-fn         ; + Server can reply to events (like Sente)
              :reconnect        ; + auto-reconnect (already implemented)
              :ping-pong}       ; + heartbeat for dead connection detection
  :compression #{:none}}}
```

**Wire format** (same as Tier 1, just library handles callbacks):
```clojure
;; Client sends with callback UUID
[[:user/get {:id 123}] "uuid-abc-123"]

;; Server replies (includes cb-uuid)
[{:name "Alice" :id 123} "uuid-abc-123"]

;; Client matches uuid → callback, invokes callback
```

**Pros:**
- ✅ Request/response pattern (Sente-style `:reply-fn`)
- ✅ Message correlation handled by library (transparent to users)
- ✅ Connection reliability (`:reconnect`, `:ping-pong`)
- ✅ Production-ready out of the box
- ✅ Still no external dependencies
- ✅ Auto-reconnect already implemented in `client-scittle.cljs`
- ✅ Same wire format as Tier 1 (just adds callback handling)
- ✅ Follows proven Sente pattern

**Cons:**
- ❌ More complex than Tier 1 (callback management)
- ❌ Requires internal state (callback UUID → fn mapping)
- ❌ Requires heartbeat implementation
- ❌ Needs timeout handling for callbacks

**When to use:**
- Production applications
- Reliable communication requirements
- Applications that need request/response (RPC-like patterns)
- Long-lived connections

**API Examples** (Sente-style):
```clojure
;; One-way event (no callback)
(send! client [:user/notification {:msg "Hi"}])

;; Request/response (with callback - Sente style)
(send! client [:user/get {:id 123}]
       5000  ; timeout-ms
       (fn [reply]
         (if (cb-success? reply)
           (println "Success:" reply)
           (println "Error:" reply))))

;; Or promise-based (browser-friendly)
@(request! client [:user/get {:id 123}]
           {:timeout 5000})
```

### Optional Features (Negotiate Dynamically)

These features **MAY** be supported and should be negotiated via capabilities:

```clojure
{:capabilities
 {:wire-formats #{:edn :json :transit}    ; Additional serialization formats
  :features #{:pubsub                     ; Publish/subscribe messaging
              :broadcast                  ; Server broadcast to all clients
              :message-retention          ; Offline message queueing
              :batching}                  ; Batch multiple events (like Sente's :chsk/recv)
  :compression #{:gzip :deflate}          ; Actual compression algorithms
  :services {:nrepl {:port 1339}}}}       ; Service discovery (nREPL, etc.)
```

**Pros:**
- ✅ Rich feature set for advanced use cases
- ✅ Backward compatible (negotiated, not required)
- ✅ Enables sophisticated patterns

**Cons:**
- ❌ More complex to implement
- ❌ May require external dependencies (compression, transit)
- ❌ Not all environments support all features

### Decision Criteria

When deciding what to mandate:

1. **Can communication work without it?**
   - If NO → Mandatory (Tier 1)
   - If YES → Optional or Tier 2

2. **Is it universally available?**
   - If YES → Can be mandatory
   - If NO → Must be optional (negotiate)

3. **Does it improve reliability significantly?**
   - If YES → Tier 2 (recommended)
   - If NO → Optional

4. **Have we implemented and tested it?**
   - If YES across all targets → Can mandate
   - If NO → Keep optional until proven

**Examples:**
- `:edn` → Tier 1 MANDATORY (needed for communication, universal, tested)
- `:gzip` → Optional (communication works without it, not universal yet)
- `:ping-pong` → Tier 2 (improves reliability significantly, universal, not yet tested)
- `:pubsub` → Optional (not all use cases need it)

### Recommendation: Start with Tier 1 Only

**Current state of implementation:**
- ✅ EDN serialization - WORKING
- ✅ Basic event send (one-way) - WORKING
- ✅ Auto-reconnect - WORKING (in `client-scittle.cljs`)
- ❌ `:reply-fn` feature - NOT IMPLEMENTED (callback handling)
- ❌ Message correlation (callback UUIDs) - NOT IMPLEMENTED
- ❌ Timeout handling for callbacks - NOT IMPLEMENTED
- ❌ Ping/pong heartbeat - NOT IMPLEMENTED

**Therefore:**

Start by mandating only **Tier 1** for now:
```clojure
;; MANDATORY for all implementations (v0.12.0)
;; Following Sente's minimal baseline
{:capabilities
 {:wire-formats #{:edn}
  :features #{:handshake :event}  ; Just basic events
  :compression #{:none}}}
```

**Wire format** (Sente-compatible):
```clojure
;; Format: [event-vector ?callback-uuid]
[[:user/notification {:msg "Hi"}] nil]  ; No callback
```

**Upgrade to Tier 2** once we've implemented and tested:
- `:reply-fn` feature (server can reply to events)
- Callback UUID generation and tracking
- `cbs-waiting_` atom for callback management (like Sente)
- Callback timeout handling
- `cb-success?` helper (distinguish timeouts/errors from success)
- Ping/pong heartbeat mechanism

**Rationale:**
- Don't mandate features we haven't proven work
- Keep initial capability system simple
- **Follow Sente's proven design** (single event format)
- Easy to add `:reply-fn` later without breaking wire format
- Backward compatibility maintained through negotiation
- Same wire format Tier 1 → Tier 2 (just add callback handling)

### Default Capability Maps

Based on this recommendation:

```clojure
(defn default-server-capabilities
  "Tier 1 mandatory + features actually implemented (Sente-compatible)"
  []
  {:version version
   :protocol-version 1
   :features #{:handshake          ; Tier 1 - MANDATORY
               :event              ; Tier 1 - MANDATORY (Sente-style events)
               ;; Add when implemented:
               ;; :reply-fn        ; Tier 2 - Server can reply to events
               :reconnect          ; Tier 2 - client has it
               :pub-sub            ; Optional - server has it
               :echo               ; Optional - for testing
               :broadcast}         ; Optional - server has it
   :wire-formats #{:edn}           ; Tier 1 - MANDATORY
   :compression #{:none}           ; Tier 1 - MANDATORY
   :limits {:max-message-size (* 1024 1024)
           :max-connections 1000
           :heartbeat-interval 30000}
   :extensions {}})

(defn default-client-capabilities
  "Tier 1 mandatory + features actually implemented (Sente-compatible)"
  []
  {:version version
   :protocol-version 1
   :features #{:handshake          ; Tier 1 - MANDATORY
               :event              ; Tier 1 - MANDATORY (Sente-style events)
               ;; Add when implemented:
               ;; :reply-fn        ; Tier 2 - Client supports callbacks
               :reconnect          ; Tier 2 - IMPLEMENTED in client-scittle
               :pub-sub}           ; Optional - client has it
   :wire-formats #{:edn}           ; Tier 1 - MANDATORY
   :compression #{:none}           ; Tier 1 - MANDATORY
   :limits {:max-message-size (* 512 1024)
           :heartbeat-interval 30000}
   :extensions {}})
```

### Evolution Path

**v0.12.0** (initial capability system):
- Mandate: Tier 1 only (`:edn`, `:event`, `:handshake`, `:none`)
- Wire format: Sente-compatible `[event-vector ?cb-uuid]`
- Advertise: Features we've implemented (`:reconnect`, `:pub-sub`)

**v0.13.0** (after implementing Tier 2 features):
- Mandate: Tier 2 (add `:reply-fn`, `:ping-pong`)
- Implement: Callback UUID tracking (Sente-style `cbs-waiting_`)
- Add: `cb-success?` helper, timeout handling
- Wire format: Same as v0.12.0 (backward compatible)

**v0.14.0+** (mature):
- Mandate: Proven reliable features
- Advertise: Rich optional feature set (`:batching`, `:message-retention`)
- Support: Advanced compression, multiple wire formats
- Full Sente API compatibility (~85%+)

## API Design

### Server-Side API

```clojure
(ns sente-lite.server)

(defn start-server!
  "Start server with capability declaration

  Options:
    :capabilities - Map of server capabilities (default: default-server-capabilities)
    :on-client-capabilities - Callback fn when client announces capabilities
                             Signature: (fn [conn-id capabilities])
    ... (existing options)

  Example:
    (start-server! {:port 8080
                    :capabilities {:features #{:heartbeat :pub-sub :nrepl-routing}
                                  :extensions {:auth-type :oauth2}}
                    :on-client-capabilities (fn [conn-id caps]
                                             (when (contains? (:features caps) :nrepl-server)
                                               (register-nrepl-client! conn-id)))})"
  [{:keys [port
           capabilities
           on-client-capabilities
           on-message
           ...]
    :or {capabilities (default-server-capabilities)}}]
  ...)

(defn default-server-capabilities
  "Default server capability map"
  []
  {:version version
   :protocol-version 1
   :features #{:heartbeat :pub-sub :echo :broadcast}
   :wire-formats #{:edn}
   :compression #{:none}
   :limits {:max-message-size (* 1024 1024)
           :max-connections 1000
           :heartbeat-interval 30000}
   :extensions {}})

(defn get-client-capabilities
  "Get capabilities for a specific client

  Args:
    conn-id - Connection ID

  Returns:
    Capability map or nil if not yet received"
  [conn-id]
  (get-in @connections [conn-id :capabilities]))

(defn supports-feature?
  "Check if client supports a feature

  Args:
    conn-id - Connection ID
    feature - Feature keyword (e.g., :auto-reconnect, :nrepl-server)

  Returns:
    Boolean"
  [conn-id feature]
  (contains? (get-in @connections [conn-id :capabilities :features])
             feature))

(defn get-all-clients-with-feature
  "Get all connected clients that support a feature

  Args:
    feature - Feature keyword

  Returns:
    Set of conn-ids"
  [feature]
  (into #{}
        (comp (filter (fn [[_ conn]]
                       (contains? (get-in conn [:capabilities :features]) feature)))
              (map first))
        @connections))
```

### Client-Side API

```clojure
(ns sente-lite.client-scittle)

(defn make-client!
  "Create client with capability declaration

  Options:
    :capabilities - Map of client capabilities (default: default-client-capabilities)
    :on-server-capabilities - Callback fn when server announces capabilities
                             Signature: (fn [capabilities])
    ... (existing options)

  Example:
    (make-client! {:url \"ws://localhost:8080\"
                   :capabilities {:features #{:auto-reconnect :nrepl-server}
                                 :services {:nrepl {:ops #{:eval :load-file}}}
                                 :extensions {:browser-type \"scittle\"}}
                   :on-server-capabilities (fn [caps]
                                            (when (contains? (:features caps) :compression)
                                              (enable-compression!)))})"
  [{:keys [url
           capabilities
           on-server-capabilities
           on-message
           ...]
    :or {capabilities (default-client-capabilities)}}]
  ...)

(defn default-client-capabilities
  "Default client capability map"
  []
  {:version version
   :protocol-version 1
   :features #{:auto-reconnect :pub-sub}
   :wire-formats #{:edn}
   :compression #{:none}
   :limits {:max-message-size (* 512 1024)
           :heartbeat-interval 30000}
   :extensions {}})

(defn get-server-capabilities
  "Get server capabilities for this client

  Args:
    client-id - Client ID

  Returns:
    Capability map or nil if not yet received"
  [client-id]
  (get-in @clients [client-id :server-capabilities]))

(defn server-supports?
  "Check if server supports a feature

  Args:
    client-id - Client ID
    feature - Feature keyword

  Returns:
    Boolean"
  [client-id feature]
  (contains? (get-in @clients [client-id :server-capabilities :features])
             feature))
```

## Message Flow Examples

### Example 1: Browser with nREPL connects

```clojure
;; 1. Server → Client (on connection, Sente-compatible)
[:chsk/handshake
 ["user-123"  ; uid
  nil         ; reserved
  {:capabilities {:features #{:handshake :event :reply-fn :pub-sub}
                  :wire-formats #{:edn}
                  :compression #{:none}
                  :limits {:max-message-size (* 1024 1024)}}}
  true]]      ; first-handshake?

;; 2. Client → Server (OPTIONAL - NEW for service discovery)
[:chsk/client-info
 {:capabilities {:features #{:event :auto-reconnect :nrepl-server}
                :services {:nrepl {:ops #{:eval :load-file}}}
                :extensions {:browser-type "scittle"}
                :limits {:max-message-size (* 512 1024)}}}]

;; 3. Server now knows: "This client has nREPL!"
;; Server can route nREPL commands to this client
(when (supports-feature? "user-123" :nrepl-server)
  (register-nrepl-client! "user-123"))

;; NOTE: If client doesn't send :chsk/client-info, server assumes:
;;       {:features #{:event} :wire-formats #{:edn} :compression #{:none}}
```

### Example 2: Negotiating compression

```clojure
;; 1. Server advertises (in handshake)
[:chsk/handshake
 ["user-456" nil
  {:capabilities {:compression #{:gzip :deflate :none}}}
  true]]

;; 2. Client responds with its capabilities (OPTIONAL)
[:chsk/client-info
 {:capabilities {:compression #{:gzip :none}}}]  ; No deflate support

;; 3. Server calculates intersection
(def server-comp #{:gzip :deflate :none})
(def client-comp #{:gzip :none})
(def mutual-comp (set/intersection server-comp client-comp))
;; => #{:gzip :none}

;; 4. Server picks best mutual algorithm
(def best-comp (first (filter mutual-comp [:deflate :gzip :none])))
;; => :gzip

;; 5. Server stores choice for this client
(swap! clients assoc-in ["user-456" :compression] :gzip)

;; NOTE: If client doesn't send :chsk/client-info,
;;       server assumes {:compression #{:none}} (Sente default)
```

### Example 3: Protocol version mismatch

```clojure
;; Server (protocol v2) sends handshake
[:chsk/handshake
 ["user-789" nil
  {:capabilities {:protocol-version 2}}
  true]]

;; Client (protocol v1) responds
[:chsk/client-info
 {:capabilities {:protocol-version 1}}]

;; Server detects mismatch and rejects
(when-not (= server-protocol client-protocol)
  (send! uid [:chsk/close {:reason :protocol-version-mismatch
                           :server-version 2
                           :client-version 1}])
  (close-connection! uid))

;; NOTE: If client doesn't send :chsk/client-info,
;;       server assumes protocol-version 1 (default)
```

## Implementation Strategy

### Phase 1: Core Capability Exchange (Sente-Compatible)

**Goal**: Sente-compatible handshake with optional client capabilities

Tasks:
- [ ] Add `handshake-data-fn` to `start-server!` options (Sente-compatible)
- [ ] Send `:chsk/handshake` with capabilities in handshake-data (Sente format)
- [ ] Add `:chsk/client-info` message handler (NEW, optional)
- [ ] Store client capabilities in connection state (defaults if not sent)
- [ ] Add basic query functions (`supports-feature?`, `get-client-capabilities`)
- [ ] Test with legacy clients (those that don't send :chsk/client-info)

**Estimated effort**: 2-3 hours

### Phase 2: Feature Detection Helpers

**Goal**: Rich capability negotiation helpers

Tasks:
- [ ] Add capability negotiation helpers (intersection, best-match)
- [ ] Add protocol version validation
- [ ] Add warnings for unsupported features
- [ ] Add `on-client-capabilities` callback hook

**Estimated effort**: 1-2 hours

### Phase 3: Feature-Specific Logic

**Goal**: Use capabilities to enable/disable features dynamically

Tasks:
- [ ] Compression negotiation logic
- [ ] Wire format negotiation logic
- [ ] Service discovery (nREPL, etc.)
- [ ] Feature-based routing

**Estimated effort**: 2-3 hours

### Phase 4: Documentation & Testing

**Goal**: Complete documentation and test coverage

Tasks:
- [ ] Update README with capability examples
- [ ] Add capability negotiation tests
- [ ] Add backward compatibility tests
- [ ] Document extension mechanism

**Estimated effort**: 2 hours

**Total estimated effort**: 7-10 hours

## Backward Compatibility

### Problem

1. **Sente clients** won't send `:chsk/client-info` (they don't know about it)
2. **Old sente-lite clients** (pre-v0.12.0) won't send it either

### Solution (Better than timeout - instant default)

Assume defaults immediately, update if `:chsk/client-info` arrives:

```clojure
(defn handle-new-connection
  "Handle new WebSocket connection with Sente-compatible handshake"
  [uid ws-connection]
  ;; 1. Store default capabilities immediately (Sente-compatible defaults)
  (swap! clients assoc uid {:capabilities (default-client-capabilities)})

  ;; 2. Send handshake with server capabilities (Sente format)
  (send! uid [:chsk/handshake
              [uid nil
               {:capabilities (get-server-capabilities)}
               true]])  ; first-handshake?

  (log/info "Handshake sent, default capabilities assumed"
            {:uid uid
             :defaults (default-client-capabilities)}))

;; Separate handler for optional client-info
(defn handle-client-info
  "Handle optional :chsk/client-info from advanced clients"
  [{:keys [uid ?data]}]
  (let [capabilities (?data :capabilities)]
    (swap! clients assoc-in [uid :capabilities] capabilities)
    (log/info "Client capabilities updated"
              {:uid uid
               :features (:features capabilities)})

    ;; Trigger feature-specific logic
    (when (contains? (:features capabilities) :nrepl-server)
      (register-nrepl-client! uid))

    (when (contains? (:features capabilities) :compression)
      (negotiate-compression! uid capabilities))))

(defn default-client-capabilities
  "Default capabilities for clients that don't send :chsk/client-info
   These are Sente-compatible minimal capabilities"
  []
  {:protocol-version 1         ; Assume current protocol
   :features #{:event}         ; Sente baseline
   :wire-formats #{:edn}       ; Sente default
   :compression #{:none}       ; No compression
   :limits {:max-message-size (* 1024 1024)}})
```

### Detection Strategy

```clojure
(defn legacy-client?
  "Check if client is legacy (pre-capability)"
  [conn-id]
  (= 0 (get-in @connections [conn-id :capabilities :protocol-version])))

(defn modern-client?
  "Check if client supports capability negotiation"
  [conn-id]
  (not (legacy-client? conn-id)))
```

## Feature Detection Pattern

### Server-Side Feature Detection

```clojure
;; In application code
(defn handle-connection [conn-id]
  (let [caps (get-client-capabilities conn-id)]
    (cond
      ;; Client has nREPL server
      (supports-feature? conn-id :nrepl-server)
      (do
        (log/info "Registering nREPL-capable client" {:conn-id conn-id})
        (register-nrepl-client! conn-id))

      ;; Client supports compression
      (supports-feature? conn-id :compression)
      (do
        (let [best-comp (negotiate-compression conn-id)]
          (log/info "Enabling compression" {:conn-id conn-id :compression best-comp})
          (enable-compression! conn-id best-comp)))

      ;; Regular client
      :else
      (register-app-client! conn-id))))

(defn negotiate-compression
  "Find best mutual compression algorithm"
  [conn-id]
  (let [server-comp #{:gzip :brotli :none}
        client-comp (get-in @connections [conn-id :capabilities :compression])
        mutual (set/intersection server-comp client-comp)]
    ;; Prefer better compression
    (first (filter mutual [:brotli :gzip :none]))))
```

### Client-Side Feature Detection

```clojure
(defn handle-welcome [client-id welcome-msg]
  (let [server-caps (:capabilities welcome-msg)]
    ;; Store server capabilities
    (swap! clients assoc-in [client-id :server-capabilities] server-caps)

    ;; Send our capabilities
    (send! client-id [:client-capabilities
                      {:client-id client-id
                       :capabilities (get-client-config client-id :capabilities)}])

    ;; Enable features based on server support
    (when (contains? (:features server-caps) :compression)
      (let [best-comp (negotiate-compression client-id server-caps)]
        (enable-compression! client-id best-comp)))

    (when (contains? (:features server-caps) :heartbeat)
      (start-heartbeat! client-id (:heartbeat-interval (:limits server-caps))))))
```

## Extension Mechanism

### User-Defined Capabilities

Users can add custom capabilities via `:extensions`:

```clojure
;; Server declares custom capability
(start-server!
  {:port 8080
   :capabilities
   {:features #{:heartbeat :pub-sub}
    :extensions {:custom-auth :oauth2
                :custom-feature-x true
                :api-version "v2"
                :supported-languages ["en" "es" "fr"]}}})

;; Client checks for it
(defn connect-to-server [client-id]
  (make-client!
    {:url "ws://localhost:8080"
     :on-server-capabilities
     (fn [caps]
       (let [extensions (:extensions caps)]
         ;; Check custom auth
         (when (= (:custom-auth extensions) :oauth2)
           (enable-oauth2-flow! client-id))

         ;; Check API version
         (when-not (= (:api-version extensions) "v2")
           (log/warn "Server API version mismatch"
                     {:expected "v2" :actual (:api-version extensions)}))

         ;; Pick language
         (let [langs (:supported-languages extensions)
               preferred (select-language langs)]
           (set-client-language! client-id preferred))))}))
```

### Extension Best Practices

1. **Namespace your keys**: Use `:myapp/feature` to avoid conflicts
2. **Document extensions**: Create schema for your extensions
3. **Fail gracefully**: Handle missing extensions
4. **Version extensions**: Include version info in extension data

Example:
```clojure
{:extensions {:myapp/auth {:version 1
                          :type :oauth2
                          :scopes ["read" "write"]}
              :myapp/realtime {:version 2
                              :features [:live-cursors :live-presence]}}}
```

## Error Handling

### How Sente Handles Unknown Messages

sente-lite follows Sente's error handling strategy to ensure compatibility and robustness.

#### Framework-Level Handling (Sente Core)

**Event Validation:**
- All events must conform to `[event-id ?event-data]` structure
- Event IDs must be namespaced keywords (e.g., `:user/notification`, `:chat/message`)
- Invalid events are wrapped as `[:chsk/bad-event original-event]` and still routed to handlers
- Malformed messages are logged at warning level with full details
- No silent drops - all validation failures are visible in logs

**Error Propagation:**
- When `?reply-fn` calls fail, errors propagate through `truss/try*` blocks with logging
- Callback UUID mismatches are logged as warnings
- Network errors trigger reconnection logic (if enabled)

#### Application-Level Handling (Recommended Pattern)

Sente's example applications use **multimethod dispatch** with a `:default` handler for unknown event types:

**Client-Side Pattern:**
```clojure
(defmethod -event-msg-handler
  :default ; Fallback for unhandled events
  [{:as ev-msg :keys [event]}]
  (log/debug "Unhandled event: %s" event))
```

**Server-Side Pattern:**
```clojure
(defmethod -event-msg-handler
  :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid (:uid session)]
    (log/debug "Unhandled event: %s" event)
    ;; Echo back to client if it expects a response
    (when ?reply-fn
      (?reply-fn {:unmatched-event-as-echoed-from-server event}))))
```

### sente-lite Error Handling Strategy

**Framework Level:**
1. ✅ **Validate event structure** - Ensure `[event-id ?event-data]` format
2. ✅ **Log validation failures** - Never fail silently
3. ✅ **Route bad events** - Wrap as `[:chsk/bad-event x]` for application handling
4. ✅ **Handle missing ?reply-fn** - Check existence before calling

**Application Level (Recommended):**
1. ✅ **Use multimethod dispatch** - Matches Sente pattern
2. ✅ **Implement `:default` handler** - Catch unhandled events
3. ✅ **Log at appropriate level** - Debug for unknown events, warn/error for validation failures
4. ✅ **Echo back if reply expected** - Return error info to client when `?reply-fn` exists

**Example Implementation:**

```clojure
;; Server-side event router
(defmulti handle-event
  "Dispatch events by event-id (first element of event vector)"
  (fn [{:keys [event]}] (first event)))

;; Handle specific events
(defmethod handle-event :user/get
  [{:keys [event ?reply-fn uid]}]
  (let [[_ query] event
        result (fetch-user query)]
    (when ?reply-fn
      (?reply-fn {:status :ok :data result}))))

;; Default handler for unknown events
(defmethod handle-event :default
  [{:keys [event ?reply-fn uid]}]
  (log/debug "Unhandled event from client"
             {:uid uid :event event})
  (when ?reply-fn
    (?reply-fn {:status :error
                :error-type :unhandled-event
                :message "Server does not handle this event type"
                :event event})))

;; Bad event handler (framework-routed validation failures)
(defmethod handle-event :chsk/bad-event
  [{:keys [event ?reply-fn uid]}]
  (let [[_ bad-event] event]
    (log/warn "Received malformed event from client"
              {:uid uid :bad-event bad-event})
    (when ?reply-fn
      (?reply-fn {:status :error
                  :error-type :malformed-event
                  :message "Event structure is invalid"}))))
```

### Capability Negotiation and Error Handling

**Missing Capability Scenarios:**

1. **Client uses unsupported compression:**
```clojure
;; Client sends message with :gzip compression
;; Server doesn't support :gzip (only :none)
;; → Server logs warning, attempts to decompress anyway, may fail
;; → Better: Check capabilities first, don't send compressed if unsupported

(defn send-with-capability-check [client event-vec]
  (let [server-caps (get-server-capabilities client)
        compression-supported? (contains? (:compression server-caps) :gzip)]
    (if compression-supported?
      (send! client event-vec {:compress? true})
      (send! client event-vec {:compress? false}))))
```

2. **Client requests feature server doesn't support:**
```clojure
;; Client sends [:nrepl/eval code] but server has no nREPL
;; → Server's :default handler catches it
;; → Logs at debug level (normal for heterogeneous clients)
;; → Returns error via ?reply-fn if client expects response

(defmethod handle-event :nrepl/eval
  [{:keys [?reply-fn uid]}]
  (if (supports-feature? :nrepl)
    (do-nrepl-eval ...)
    (do
      (log/debug "Client attempted nREPL eval but feature not enabled"
                 {:uid uid})
      (when ?reply-fn
        (?reply-fn {:status :error
                    :error-type :feature-not-supported
                    :feature :nrepl})))))
```

3. **Server sends message in unsupported wire format:**
```clojure
;; Prevented at capability negotiation:
;; Server checks client capabilities before sending

(defn broadcast-to-clients [event-vec]
  (doseq [[uid client-state] @clients]
    (let [caps (:capabilities client-state)
          formats (:wire-formats caps)
          format (if (contains? formats :transit-json)
                   :transit-json
                   :edn)] ; Fall back to mandatory :edn
      (send! uid event-vec {:format format}))))
```

### Best Practices

1. ✅ **Always implement `:default` handler** - Catch unknown events
2. ✅ **Check capabilities before using features** - Don't assume support
3. ✅ **Return errors via ?reply-fn** - Let clients know what went wrong
4. ✅ **Log at appropriate levels** - Debug for unknown events, warn/error for problems
5. ✅ **Never fail silently** - Make errors visible for debugging
6. ✅ **Use :chsk/bad-event for validation failures** - Framework-level error routing
7. ✅ **Test with mismatched capabilities** - Ensure graceful degradation

## Summary

This design provides:

✅ **Clear negotiation** - Server speaks first, client responds
✅ **Feature discovery** - Both parties know each other's capabilities
✅ **Extensibility** - `:extensions` map for custom features
✅ **Backward compatibility** - Legacy clients get minimal capabilities
✅ **Data-driven** - Capabilities as declarative data
✅ **Simple API** - `supports-feature?`, `get-capabilities`
✅ **One round-trip** - Efficient, no multiple handshakes
✅ **Service discovery** - Clients can advertise services (nREPL, etc.)
✅ **Format negotiation** - Compression, wire formats, etc.
✅ **Resource limits** - Both parties know limits upfront
✅ **Robust error handling** - Sente-compatible validation, logging, and graceful degradation

### Next Steps

1. Review and approve design
2. Implement Phase 1 (core capability exchange)
3. Test with BB-to-BB clients
4. Test with browser clients (Scittle)
5. Add capability-based feature toggling
6. Document in main README
7. Create migration guide for existing code

---

**Document Status**: Draft for review
**Last Updated**: 2025-10-30
**Author**: Claude Code + Frank Siebenlist
