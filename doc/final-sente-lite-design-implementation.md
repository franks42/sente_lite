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
- [Observability & Telemetry Design](#observability--telemetry-design)
- [nREPL Gateway Architectures](#nrepl-gateway-architectures)
- [Implementation Sequence & Roadmap](#implementation-sequence--roadmap)
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

### Three-Tier Capability Discovery

sente-lite provides **three complementary mechanisms** for capability discovery, ordered from most to least efficient:

#### Tier 1: Implicit Discovery via Default Handler (Always Available, Zero Cost)

**Core Insight:** Event-IDs implicitly declare capabilities. If a peer doesn't handle an event, the `:default` handler catches it.

```clojure
;; Try to use a feature
[:nrepl/eval "(+ 1 2 3)"]

;; If peer doesn't support it, default handler responds
{:status :error
 :error-type :event-not-supported
 :message "This event-id is not handled by this peer"
 :event-id :nrepl/eval}

;; Now you know: peer doesn't support :nrepl/eval
```

**Properties:**
- ✅ Always works (requires only `:default` handler implementation)
- ✅ Zero protocol overhead (uses standard event mechanism)
- ✅ Lazy discovery (only discover features you actually use)
- ✅ Self-documenting (event-id ≈ capability)
- ❌ Requires one round-trip per feature discovery
- ❌ Can't know capabilities before attempting to use them

**Recursive Property:**
Even capability query events use this mechanism! If you send `:capabilities/list` and the peer doesn't implement it, you get `:event-not-supported` back, telling you to fall back to trial-error.

#### Tier 2: Explicit Capability Query Events (Optional, On-Demand)

For introspection and avoiding trial-error, peers can implement capability query events:

```clojure
;; Query all capabilities
[:capabilities/list]
→ {:status :ok
   :capabilities #{:nrepl/eval :nrepl/load-file :chat/send :user/get}}

;; Query specific feature
[:capabilities/query {:feature :nrepl}]
→ {:status :ok
   :supported true
   :details {:ops #{:eval :load-file :doc}
            :port 1339}}

;; If peer doesn't implement these events:
[:capabilities/list]
→ {:status :error
   :error-type :event-not-supported}  ; Fall back to Tier 1
```

**Properties:**
- ✅ One round-trip gets all capabilities
- ✅ Know before using (can adapt UI, choose features)
- ✅ Useful for debugging and introspection
- ✅ Graceful degradation (if not supported, use Tier 1)
- ❌ Optional (not all peers implement)
- ❌ Still requires one round-trip before use

#### Tier 3: Upfront Exchange via Handshake (Optional, Immediate)

For immediate knowledge (service discovery, compression negotiation), exchange capabilities during connection:

```clojure
;; Server sends on connect (Sente-compatible)
[:chsk/handshake
 [uid nil
  {:capabilities {:compression #{:gzip :none}
                 :features #{:event :reply-fn}}}
  true]]

;; Client OPTIONALLY sends info (backward compatible extension)
[:chsk/client-info
 {:capabilities {:features #{:event :nrepl-server}
                :services {:nrepl {:port 1339
                                  :ops #{:eval :load-file}}}}}]

;; Now both sides know immediately, zero probing needed
```

**Properties:**
- ✅ Immediate knowledge (no discovery round-trip)
- ✅ Essential for service discovery (e.g., nREPL servers on clients)
- ✅ Enables smart decisions before first message (compression, format)
- ✅ Backward compatible (optional `:chsk/client-info`)
- ❌ Requires upfront design (what to include?)
- ❌ Can't discover dynamically added capabilities

### How Sente Does It (Reference)

**Sente's approach:**
- Server sends `:chsk/handshake [uid nil handshake-data first-handshake?]`
- Client receives, updates state, **does NOT respond**
- **No negotiation** - Both sides pre-configured to match
- Works for Sente because: homogeneous clients, no compression, no service discovery

**Why sente-lite needs all three tiers:**
- ✅ Heterogeneous clients (BB vs browser vs embedded) → Different capabilities
- ✅ Compression negotiation (some clients support, some don't) → Tier 3 avoids probing
- ✅ Service discovery (clients can offer nREPL!) → Tier 3 for immediate routing
- ✅ Dynamic capabilities (install handlers at runtime) → Tier 1 & 2 discover new features
- ✅ Backward compatibility → Tier 1 always works

### Decision Matrix: Which Tier to Use?

| Scenario | Best Tier | Rationale |
|----------|-----------|-----------|
| Service discovery (client nREPL) | **Tier 3** | Server needs immediate routing info |
| Compression negotiation | **Tier 3** | Must know before sending large message |
| Wire format selection | **Tier 3** | Must agree before first message |
| Optional features (pub/sub) | **Tier 2** | Query once, use many times |
| Rarely used features | **Tier 1** | Lazy discovery, simple |
| Dynamic features (runtime install) | **Tier 1** | Can't know upfront |
| Debugging/introspection | **Tier 2** | List all capabilities |
| Legacy/minimal clients | **Tier 1** | Only requires `:default` handler |

### Implementation Requirements

**REQUIRED (All implementations MUST provide):**
1. `:default` handler that returns `{:error-type :event-not-supported}` when `?reply-fn` exists
2. This enables Tier 1 (implicit discovery) for free

**RECOMMENDED (Most implementations SHOULD provide):**
3. `:capabilities/list` event handler (enables Tier 2)
4. `:chsk/client-info` event handler on server (enables Tier 3)

**OPTIONAL (Advanced implementations MAY provide):**
5. `:capabilities/query` for fine-grained queries
6. `:capabilities/install` for dynamic capability installation (via nREPL)

### Alternative Approaches Considered (and rejected)

**Configuration only (pure Sente)**: Doesn't support service discovery or negotiation
**Mandatory bidirectional handshake**: Breaks Sente compatibility, more complex
**HTTP Headers during upgrade**: Limited size, not EDN-friendly
**Separate HTTP endpoint**: Extra round-trip, more complexity
**Only implicit discovery**: No way to avoid probing for compression/format negotiation
**Only explicit exchange**: Can't discover dynamically added capabilities

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
;; Server (protocol version 2) sends handshake
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
                :api-version "2"
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
         (when-not (= (:api-version extensions) "2")
           (log/warn "Server API version mismatch"
                     {:expected "2" :actual (:api-version extensions)}))

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

### Dynamic Capability Installation (Advanced)

One of the most powerful patterns enabled by the three-tier approach: **installing new capabilities at runtime via nREPL**.

When a peer advertises an nREPL server (via Tier 3 handshake), other peers can dynamically install new event handlers:

```clojure
;; 1. Client advertises nREPL capability on connect
[:chsk/client-info
 {:capabilities {:features #{:nrepl-server}
                :services {:nrepl {:port 1339
                                  :ops #{:eval :load-file}}}}}]

;; 2. Server sees nREPL capability, stores for later use
;; (via handle-client-info)

;; 3. Later, server installs a new event handler on the client
[:nrepl/eval
 "(defmethod handle-event :analytics/track
    [{:keys [event ?reply-fn]}]
    (let [[_ data] event]
      (track-analytics! data)
      (when ?reply-fn
        (?reply-fn {:status :ok :tracked true}))))"]

;; 4. Client now supports :analytics/track!
;; Server can immediately use it:
[:analytics/track {:page "/home" :user-id "123"}]
→ {:status :ok :tracked true}

;; 5. Verify with capability query (if client supports it)
[:capabilities/list]
→ {:status :ok
   :capabilities #{:chat/send :analytics/track}}  ; New capability listed!
```

**Why This Works:**

1. **nREPL as Meta-Capability**: The ability to eval code is a meta-capability that enables all other capabilities
2. **Zero Deployment**: Install features without restarting or redeploying
3. **A/B Testing**: Install different handlers on different client cohorts
4. **Hot-Patching**: Fix bugs or add features to running systems
5. **Progressive Enhancement**: Start minimal, add features as needed

**Discovery Flow with Dynamic Installation:**

```clojure
;; Try feature (Tier 1 - implicit discovery)
[:analytics/track {:page "/login"}]
→ {:status :error :error-type :event-not-supported}

;; Feature not supported. Check if peer has nREPL (Tier 3)
(let [client-caps (get-client-capabilities uid)]
  (when (contains? (:features client-caps) :nrepl-server)
    ;; Install the feature!
    (install-event-handler! uid
      :analytics/track
      "(defmethod handle-event :analytics/track ...)")))

;; Try again
[:analytics/track {:page "/login"}]
→ {:status :ok :tracked true}  ; Now it works!
```

**Security Considerations:**

⚠️ **CRITICAL**: Dynamic code installation is powerful but dangerous!

1. **Authentication Required**: Only allow trusted peers to install handlers
2. **Sandbox Evaluation**: Consider using safe-eval or restricted namespaces
3. **Code Review**: In production, review/approve code before installation
4. **Rollback Mechanism**: Keep original handlers for rollback
5. **Audit Logging**: Log all dynamic installations with timestamps and source

**Safe Installation Helper:**

```clojure
(defn install-event-handler!
  "Safely install event handler via nREPL with validation"
  [uid event-id handler-code]
  ;; 1. Validate peer has nREPL
  (let [caps (get-client-capabilities uid)]
    (when-not (contains? (:features caps) :nrepl-server)
      (throw (ex-info "Peer doesn't support nREPL" {:uid uid}))))

  ;; 2. Validate authentication/authorization
  (when-not (authorized-to-install? uid)
    (throw (ex-info "Not authorized to install handlers" {:uid uid})))

  ;; 3. Validate handler code (basic checks)
  (when-not (valid-handler-code? handler-code)
    (throw (ex-info "Invalid handler code" {:code handler-code})))

  ;; 4. Send installation request
  (send! uid [:nrepl/eval handler-code]
    (fn [response]
      (if (= (:status response) :ok)
        (do
          ;; 5. Log successful installation
          (log/info "Event handler installed"
                    {:uid uid :event-id event-id})
          ;; 6. Update peer's capability cache
          (swap! clients assoc-in [uid :capabilities :features]
                 (conj (get-in @clients [uid :capabilities :features])
                       event-id)))
        (log/error "Failed to install handler"
                   {:uid uid :event-id event-id :error response})))))
```

**Alternative: Capability Installation Event:**

For safer, structured installation without raw code eval:

```clojure
;; Instead of sending raw code, send structured capability spec
[:capabilities/install
 {:event-id :analytics/track
  :handler-type :analytics
  :config {:endpoint "https://analytics.example.com"
          :batch-size 100}}]

;; Client validates and installs from pre-approved handlers
(defmethod handle-event :capabilities/install
  [{:keys [event ?reply-fn]}]
  (let [[_ spec] event
        {:keys [event-id handler-type config]} spec]
    (if-let [template (get approved-handler-templates handler-type)]
      (do
        ;; Install from approved template
        (install-templated-handler! event-id template config)
        (when ?reply-fn
          (?reply-fn {:status :ok :installed event-id})))
      (when ?reply-fn
        (?reply-fn {:status :error
                    :error-type :unknown-handler-type
                    :handler-type handler-type})))))
```

This approach is safer as it only allows installation from pre-approved templates, not arbitrary code execution.

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

## Observability & Telemetry Design

### Key Telemetry Points for Comprehensive Observability

The following telemetry events provide complete visibility into sente-lite operations:

#### 1. Connection Lifecycle (CRITICAL - Foundation)
```clojure
;; server.cljc & client_scittle.cljs
(tel/event! ::connection-opened {:conn-id "..." :from :client/:server})
(tel/event! ::connection-closed {:conn-id "..." :reason :normal/:error/:timeout})
(tel/event! ::connection-failed {:conn-id "..." :error "..." :retry-count 3})
(tel/event! ::reconnect-attempt {:conn-id "..." :attempt 2 :backoff-ms 2000})
(tel/event! ::reconnect-success {:conn-id "..." :total-attempts 3 :total-duration-ms 5000})
```

#### 2. Message Flow (HIGH VALUE - Shows Activity)
```clojure
;; Every message through the system
(tel/event! ::message-sent {:conn-id "..." :event-id :user/login :size-bytes 245})
(tel/event! ::message-received {:conn-id "..." :event-id :user/login :size-bytes 245})
(tel/event! ::message-routed {:conn-id "..." :event-id :user/login :handler :found/:not-found})
(tel/event! ::message-failed {:conn-id "..." :event-id :user/login :error "..."})
```

#### 3. Event Handler Dispatch (CRITICAL for Debugging)
```clojure
;; In multimethod dispatch system (Tier 1 capability discovery)
(tel/event! ::event-dispatched {:conn-id "..." :event-id :user/login :handler-found true})
(tel/event! ::event-unhandled {:conn-id "..." :event-id :unknown/feature})  ; Hit :default
(tel/event! ::event-handler-error {:conn-id "..." :event-id :user/login :error "..."})
```

#### 4. Capability Negotiation (Tier 1/2/3)
```clojure
;; Tier 3: Handshake
(tel/event! ::handshake-sent {:conn-id "..." :capabilities #{:nrepl :compression}})
(tel/event! ::handshake-received {:conn-id "..." :peer-capabilities #{:nrepl}})
(tel/event! ::capabilities-negotiated {:conn-id "..." :agreed #{:nrepl}})

;; Tier 2: Explicit queries
(tel/event! ::capability-query {:conn-id "..." :feature :nrepl})
(tel/event! ::capability-response {:conn-id "..." :feature :nrepl :supported true})

;; Tier 1: Implicit discovery
(tel/event! ::capability-discovered {:conn-id "..." :event-id :nrepl/eval :via :default-handler})
```

#### 5. Heartbeat/Health (CRITICAL for Production)
```clojure
(tel/event! ::heartbeat-sent {:conn-id "..." :sequence 42})
(tel/event! ::heartbeat-received {:conn-id "..." :latency-ms 15})
(tel/event! ::heartbeat-timeout {:conn-id "..." :last-seen-ms 65000})
```

#### 6. Channel Operations (Pub/Sub)
```clojure
(tel/event! ::channel-subscribed {:conn-id "..." :channel-id "room-123"})
(tel/event! ::channel-unsubscribed {:conn-id "..." :channel-id "room-123"})
(tel/event! ::channel-message {:channel-id "room-123" :subscriber-count 5})
(tel/event! ::channel-created {:channel-id "room-123"})
(tel/event! ::channel-destroyed {:channel-id "room-123" :total-messages 1523})
```

#### 7. Wire Format & Compression
```clojure
(tel/event! ::message-serialized {:format :edn :size-bytes 1024})
(tel/event! ::message-compressed {:algorithm :gzip :original 1024 :compressed 256 :ratio 0.25})
(tel/event! ::message-decompressed {:algorithm :gzip :compressed 256 :decompressed 1024})
(tel/event! ::parse-error {:format :edn :error "..." :raw-size 500})
```

#### 8. Dynamic Capability Installation (nREPL Pattern)
```clojure
(tel/event! ::nrepl-eval-sent {:conn-id "..." :code-hash "abc123" :code-length 245})
(tel/event! ::handler-installed {:conn-id "..." :event-id :analytics/track :method :nrepl})
(tel/event! ::handler-install-failed {:conn-id "..." :event-id :analytics/track :error "..."})
```

#### 9. Performance Metrics (Aggregatable)
```clojure
(tel/event! ::message-latency {:event-id :user/login :latency-ms 45})
(tel/event! ::queue-depth {:conn-id "..." :depth 15 :type :send-buffer})
(tel/event! ::active-connections {:count 127 :peak 150})
```

#### 10. Security Events
```clojure
(tel/event! ::auth-attempt {:conn-id "..." :method :token})
(tel/event! ::auth-success {:conn-id "..." :user-id "user-123"})
(tel/event! ::auth-failed {:conn-id "..." :reason :invalid-token})
(tel/event! ::unauthorized-handler-install {:conn-id "..." :event-id :admin/delete})
```

### Centralized Telemetry via Sente Channels

**Key Insight**: Route ALL telemetry events (from both browser and server) to a centralized BB server for unified debugging and monitoring.

#### Architecture:

```
Browser Client                  BB Server
    |                              |
    | [:telemetry/event {...}]     |
    |----------------------------->|
    |                              | → Append to single atom/file
    |                              | → Timeline: [client-event, server-event, ...]
    |                              |
Server Process                     |
    | (tel/event! ::foo)           |
    |----------------------------->| → Same atom/file
    |                              |

RESULT: Single unified timeline with ALL events from ALL sources!
```

#### Benefits:

1. **Unified Timeline** - See exact cause-and-effect across client and server
2. **Easy Correlation** - Filter by request-id to see full flow
3. **Single Debug Location** - One file/atom instead of scattered logs
4. **Production Ready** - Browsers can't write files; server centralizes
5. **Better Testing** - Assert on events from both sides in one place

#### Implementation:

**Browser Side:**
```clojure
;; telemere-lite/scittle.cljs
(defn add-remote-handler!
  "Send telemetry events to server via sente"
  [send-fn]
  (tel/add-handler! :remote-sink
    (fn [signal]
      (send-fn [:telemetry/event (assoc signal :source :browser)]))
    {:async {:mode :dropping :buffer-size 512}}))
```

**Server Side:**
```clojure
;; Handle incoming telemetry from clients
(defmethod handle-event :telemetry/event
  [{:keys [event conn-id]}]
  (let [[_ signal] event]
    ;; Add to centralized atom/file (already has server events)
    (swap! all-events conj (assoc signal :conn-id conn-id))))

;; Server's own telemetry also goes to same sink
(tel/add-handler! :centralized
  (fn [signal]
    (swap! all-events conj (assoc signal :source :server)))
  {:async {:mode :dropping :buffer-size 1024}})
```

**Testing Pattern:**
```clojure
(deftest test-reconnect-with-unified-telemetry
  ;; Server collects everything
  (def all-events (atom []))
  (tel/add-handler! :test-sink
    (fn [signal] (swap! all-events conj signal)))

  ;; Run test
  (start-client!)
  (kill-server!)
  (Thread/sleep 2000)

  ;; Check BOTH client and server events in one place!
  (let [events @all-events
        client-events (filter #(= :browser (:source %)) events)
        server-events (filter #(= :server (:source %)) events)]
    (assert (some #(= ::connection-opened (:event-id %)) client-events))
    (assert (some #(= ::connection-closed (:event-id %)) server-events)))

  ;; Save artifact for debugging
  (spit "logs/test-reconnect-2025-10-31.edn" (pr-str @all-events)))
```

**This should be a capability:**
```clojure
;; Browser advertises capability
[:chsk/handshake {:capabilities {:telemetry-remote-sink true}}]

;; Server acknowledges
[:chsk/handshake-ack {:capabilities {:telemetry-remote-sink true}}]

;; Browser configures remote sink
(when (get-in @capabilities [:server :telemetry-remote-sink])
  (add-remote-handler! send-fn))
```

### Testing Strategy: Dual Sinks (Atom + File)

telemere-lite supports multiple handlers simultaneously, perfect for testing:

```clojure
(deftest test-with-dual-sinks
  ;; Sink 1: Atom (for assertions - fast, easy)
  (def test-events (atom []))
  (tel/add-handler! :test-capture
                    (fn [signal] (swap! test-events conj signal))
                    {:sync true})

  ;; Sink 2: File (for artifact - permanent record)
  (tel/add-file-handler! :test-file
                         "logs/test-reconnect-2025-10-31-123456.edn"
                         {:sync true})

  ;; Run test - events go to BOTH atom and file
  (start-client!)
  (kill-server!)
  (Thread/sleep 2000)

  ;; Assert on atom (fast, easy)
  (assert (some #(= (:event-id %) ::connection-closed) @test-events))

  ;; File automatically saved for debugging later

  ;; Cleanup
  (tel/remove-handler! :test-capture)
  (tel/remove-handler! :test-file))
```

**Benefits:**
- ✅ **Atom** - Fast assertions, easy filtering, direct access
- ✅ **File** - Permanent record, survives crashes, shareable
- ✅ **Zero overhead** - Multiple handlers via keyed map
- ✅ **Already implemented** - No code changes needed

### Where Telemetry Goes in Code

**Key principle**: Telemetry at boundaries and state transitions

1. **`server.cljc`** - Server-side connection management, message routing, dispatch
2. **`client_scittle.cljs`** - Client-side connection, reconnection logic
3. **`wire-format.cljc`** - Serialization/deserialization, compression
4. **`channels.cljc`** - Pub/sub operations
5. **Multimethod dispatch** - Event handler routing (Tier 1 discovery)
6. **Handshake logic** - Capability negotiation (Tier 2/3)

## nREPL Gateway Architectures

### Browser nREPL Gateway (Implemented)

**Purpose**: Allow editor to control browser via nREPL → Sente → Browser SCI

```
Editor                    BB Gateway                Browser
  |                          |                         |
  | bencode nREPL           |                         |
  |------------------------>|                         |
  |                          | [:nrepl/eval ...] (EDN)|
  |                          |----------------------->|
  |                          |                        | (SCI eval)
  |                          | [:nrepl/response ...]  |
  |                          |<-----------------------|
  | bencode response        |                         |
  |<------------------------|                         |

File: dev/scittle-demo/sente-nrepl-gateway.clj (EXISTS)
```

**Status**: ✅ Implemented and working

### BB-to-BB nREPL Event Handlers (Simple Implementation)

**Key Insight**: This is NOT a separate "gateway" - it's just regular event handlers that forward to existing nREPL server!

**Purpose**: Allow BB client to send nREPL commands to BB server via Sente

```
BB Client A              Sente Connection          BB Server B
  |                          |                         |
  | (Connected via Sente)    |                         |
  |                          |                         |
  | [:nrepl/eval {:code ...}]|                         |
  |------------------------>|                         |
  |                          | Just a multimethod!     |
  |                          | ↓                       |
  |                          | (defmethod handle-event |
  |                          |   :nrepl/eval ...)      |
  |                          | ↓                       |
  |                          | Use BB's nREPL client   |
  |                          | to forward to local     |
  |                          | nREPL server (1338)     |
  |                          |                    ✓    |
  |                          | Response via callback   |
  | [:nrepl/response {...}]  |                         |
  |<------------------------|                         |

Implementation: Just event handlers + BB's nREPL client code
```

**Status**: 🔲 Not implemented yet (low priority - can use direct nREPL for BB-to-BB)

**Why this is simple:**
- ✅ **Not a separate service** - just regular multimethod handlers
- ✅ **Reuses BB's nREPL client** - connection logic, bencode handling, session management
- ✅ **Forwards to ANY nREPL server** - local port 1338 or any other
- ✅ **Same pattern as other events** - `:user/login`, `:chat/message`, etc.
- ✅ **Event-ID = Capability** - `:nrepl/eval` handler = nREPL capability

**Implementation (when needed):**

```clojure
;; Use BB's nREPL client to connect to local nREPL server
(require '[bencode.core :as bencode]
         '[babashka.nrepl.client :as nrepl-client])

;; Reusable connection to local nREPL server
(defonce nrepl-conn (atom nil))

(defn get-or-create-nrepl-connection!
  "Get or create connection to local nREPL server using BB's nREPL client"
  []
  (or @nrepl-conn
      (reset! nrepl-conn
        (nrepl-client/connect {:host "localhost" :port 1338}))))

;; Event handler - just forwards to nREPL using BB's client
(defmethod handle-event :nrepl/eval
  [{:keys [event ?reply-fn conn-id]}]
  (let [[_ {:keys [code id]}] event
        conn (get-or-create-nrepl-connection!)]
    (future  ; Don't block event dispatch
      (try
        ;; Use BB's nREPL client - handles bencode, sessions, etc.
        (let [response (nrepl-client/message conn {:op "eval" :code code :id id})]
          (when ?reply-fn
            (?reply-fn [:nrepl/response response])))
        (catch Exception e
          (tel/error! "nREPL eval failed" {:conn-id conn-id :error (str e)})
          (when ?reply-fn
            (?reply-fn [:nrepl/response {:status ["error"] :ex (str e)}])))))))

;; Same pattern for other nREPL ops
(defmethod handle-event :nrepl/load-file
  [{:keys [event ?reply-fn]}]
  (let [[_ {:keys [file id]}] event
        conn (get-or-create-nrepl-connection!)]
    (future
      (let [response (nrepl-client/message conn {:op "load-file" :file file :id id})]
        (when ?reply-fn
          (?reply-fn [:nrepl/response response]))))))

;; Default handler catches unsupported nREPL ops
(defmethod handle-event :default
  [{:keys [event ?reply-fn]}]
  (let [[event-id] event]
    (when (and ?reply-fn (namespace event-id) (= "nrepl" (namespace event-id)))
      (?reply-fn {:status :error
                  :error-type :event-not-supported
                  :message "This nREPL operation is not supported"
                  :event-id event-id}))))
```

**Benefits of using BB's nREPL client:**
- ✅ **Connection pooling** - Reuse existing connection
- ✅ **Session management** - BB handles it
- ✅ **Bencode handling** - No manual bencode wrapping
- ✅ **Error handling** - Built-in retries, reconnection
- ✅ **Less code** - Reuse existing infrastructure
- ✅ **More reliable** - Battle-tested code

**When to use:**
- Only if you need remote BB client to control BB server via Sente
- For BB-to-BB, can use direct nREPL connections (simpler)
- For Browser-to-BB, MUST use gateway pattern (browser can't run nREPL server)

## Implementation Sequence & Roadmap

### Phase 1: Basic Sente Connection (FIRST)

**Goal**: Establish reliable sente-lite connection with minimal capabilities

**Tasks:**
1. Implement multimethod-based event dispatch (`:default` handler required)
2. Test BB-to-BB basic messages (`:edn` format, `:event` type)
3. Test Browser-to-BB basic messages
4. Verify connection lifecycle (open, close, reconnect)
5. Implement Tier 1 capability discovery (implicit via `:default` handler)
6. Add basic telemetry (connection events, message events)

**Success Criteria:**
- ✅ BB clients can connect to BB server
- ✅ Browser clients can connect to BB server
- ✅ Send/receive basic event messages `[:event-id {:data}]`
- ✅ Unknown events return `{:error-type :event-not-supported}`
- ✅ Telemetry events captured in atom during tests
- ✅ Test files saved to `logs/test-*.edn`

**Tools:**
- Use nREPL MCP tool to drive tests
- Use `tel/add-handler!` with atom for test assertions
- Use `tel/add-file-handler!` for test artifacts

### Phase 2: nREPL Capability (SECOND)

**Goal**: Enable dynamic code execution and runtime extension

**Tasks:**
1. Implement `:nrepl/eval` handler (server-side)
2. Test browser nREPL gateway (already exists: `dev/scittle-demo/sente-nrepl-gateway.clj`)
3. Test dynamic handler installation via nREPL
4. Add nREPL telemetry events (eval sent/received, handler installed)
5. Document nREPL security considerations

**Success Criteria:**
- ✅ Editor can eval code in browser via nREPL → Sente → Browser
- ✅ Can install event handlers at runtime via `:nrepl/eval`
- ✅ nREPL events appear in centralized telemetry

**Deferred:**
- ⏸️ BB-to-BB reverse nREPL gateway (use direct nREPL servers instead)

### Phase 3: Centralized Telemetry Capability (THIRD)

**Goal**: Route all telemetry to central BB server for unified observability

**Tasks:**
1. Implement `:telemetry/event` handler (server-side)
2. Implement `add-remote-handler!` (browser-side)
3. Add telemetry capability to handshake
4. Test unified timeline (browser + server events in one file/atom)
5. Test filtering by source, conn-id, event-id
6. Document telemetry patterns

**Success Criteria:**
- ✅ Browser telemetry events sent to server via `[:telemetry/event ...]`
- ✅ Server telemetry and browser telemetry in same atom/file
- ✅ Single unified timeline for debugging
- ✅ Can correlate client-server interactions by request-id
- ✅ Production-ready observability

### Phase 4: Additional Capabilities (LATER)

**After core capabilities work:**

1. **Compression** (Tier 3 negotiation required)
   - gzip + none, 1KB threshold
   - See `doc/sente-lite-compression-feature.md`

2. **Tier 2 Explicit Queries** (optional)
   - `:capabilities/list`
   - `:capabilities/query`

3. **Tier 3 Handshake** (optional, but useful for compression)
   - `:chsk/handshake` with capabilities
   - `:chsk/client-info` with service discovery

4. **BB-to-BB Reverse nREPL Gateway** (if needed)
   - Only if we need to route nREPL through sente channel
   - Currently can use direct nREPL servers

## Summary

This design provides **three complementary tiers** of capability discovery, each solving different problems:

### Tier 1: Implicit Discovery (Always Available)
✅ **Event-ID ≈ Capability** - Trying an event discovers if it's supported
✅ **Zero protocol overhead** - Uses standard `:default` handler mechanism
✅ **Recursive property** - Even capability queries use this (graceful degradation)
✅ **Always works** - Requires only `:default` handler implementation
✅ **Lazy discovery** - Only discover features you actually use

### Tier 2: Explicit Query Events (Optional)
✅ **On-demand introspection** - `:capabilities/list`, `:capabilities/query`
✅ **One round-trip** - Get all capabilities before use
✅ **Useful for debugging** - List all supported event-ids
✅ **Graceful fallback** - If not supported, falls back to Tier 1

### Tier 3: Upfront Exchange (Optional)
✅ **Immediate knowledge** - Via `:chsk/handshake` and `:chsk/client-info`
✅ **Service discovery** - Clients advertise nREPL servers, etc.
✅ **Smart decisions** - Know compression/format support before sending
✅ **Backward compatible** - Optional `:chsk/client-info` (Sente clients work)

### Additional Features

✅ **Sente compatibility** - Follows Sente's patterns and wire format
✅ **Dynamic installation** - Install handlers at runtime via nREPL
✅ **Robust error handling** - Validation, logging, graceful degradation
✅ **Extensibility** - `:extensions` map for custom features
✅ **Data-driven** - Capabilities as declarative data structures
✅ **Simple API** - `supports-feature?`, `get-capabilities`, event routing
✅ **Security considered** - Safe installation patterns, authentication hooks

### Key Insights

1. **Event-IDs declare capabilities implicitly** - If you can handle `:nrepl/eval`, you support nREPL
2. **Default handler enables discovery** - Returns `:event-not-supported` for unknown events
3. **Recursive discovery** - Capability queries themselves are discoverable
4. **nREPL as meta-capability** - Can install ANY other capability at runtime
5. **Choose the right tier** - Compression needs Tier 3, rare features use Tier 1

### Implementation Roadmap

See [Implementation Sequence & Roadmap](#implementation-sequence--roadmap) for detailed phases:

1. ✅ **Design Complete** - Three-tier capability discovery, telemetry, nREPL gateways
2. 🚧 **Phase 1**: Basic Sente Connection - Multimethod dispatch, Tier 1 discovery, basic telemetry
3. ⏸️ **Phase 2**: nREPL Capability - Browser gateway (exists), dynamic handler installation
4. ⏸️ **Phase 3**: Centralized Telemetry - Unified timeline, remote sinks
5. ⏸️ **Phase 4**: Additional Capabilities - Compression, Tier 2/3 (optional)

---

**Document Status**: Design Complete - Ready for Implementation
**Last Updated**: 2025-10-31
**Author**: Claude Code + Frank Siebenlist
