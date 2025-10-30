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

### Server-First Declaration with Client Response

**Why this pattern?**
- Server initiates WebSocket upgrade, already "speaks first"
- Existing `:welcome` message is perfect hook for capabilities
- Client can respond with its capabilities immediately
- Simple, synchronous, one round-trip
- No protocol ambiguity about who goes first

**Flow:**
```
1. Client connects to server
2. Server sends :welcome with server-capabilities
3. Client receives welcome, sends :client-capabilities
4. Both parties now have complete capability map
```

### Alternative Approaches Considered (and rejected)

**HTTP Headers during upgrade**: Limited size, not EDN-friendly
**Separate HTTP endpoint**: Extra round-trip, more complexity
**Client-first**: Doesn't match WebSocket connection semantics

## Capability Data Structure

### Server Capabilities

Sent in the `:welcome` message:

```clojure
{:type :welcome
 :conn-id "conn-1234"
 :server-time 1234567890
 :capabilities {:version "0.12.0"
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
                :extensions {}}}  ; User-defined capabilities
```

### Client Capabilities

Sent immediately after receiving `:welcome`:

```clojure
{:type :client-capabilities
 :client-id "browser-abc123"
 :capabilities {:version "0.12.0"
                :protocol-version 1
                :features #{:auto-reconnect
                           :compression
                           :nrepl-server    ; ← Client has nREPL!
                           :pub-sub}
                :wire-formats #{:edn :transit-json}
                :compression #{:gzip :none}
                :limits {:max-message-size (* 512 1024)  ; 512KB browser limit
                        :heartbeat-interval 30000}
                :services {:nrepl {:port nil  ; WebSocket-based
                                  :ops #{:eval :load-file :clone :describe}}}
                :extensions {:browser-type "scittle"
                            :user-agent "Mozilla/5.0 ..."}}}
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
;; 1. Server → Client (on connection)
[:welcome {:type :welcome
           :conn-id "conn-1234"
           :server-time 1234567890
           :capabilities {:features #{:heartbeat :pub-sub :echo}
                         :wire-formats #{:edn}
                         :limits {:max-message-size (* 1024 1024)}}}]

;; 2. Client → Server (immediately after welcome)
[:client-capabilities
 {:client-id "browser-abc"
  :capabilities {:features #{:auto-reconnect :nrepl-server}
                :services {:nrepl {:ops #{:eval :load-file}}}
                :extensions {:browser-type "scittle"}
                :limits {:max-message-size (* 512 1024)}}}]

;; 3. Server now knows: "This client has nREPL!"
;; Server can route nREPL commands to this client
(when (supports-feature? "conn-1234" :nrepl-server)
  (register-nrepl-client! "conn-1234"))
```

### Example 2: Negotiating compression

```clojure
;; Server advertises
{:capabilities {:compression #{:gzip :brotli :none}}}

;; Client responds
{:capabilities {:compression #{:gzip :none}}}  ; No brotli support

;; Intersection = #{:gzip :none}
;; Server picks :gzip (best mutual capability)
(def mutual-compression
  (set/intersection server-compression client-compression))

(def best-compression
  (first (filter mutual-compression [:brotli :gzip :none])))
;; => :gzip
```

### Example 3: Protocol version mismatch

```clojure
;; Server (protocol v2)
{:capabilities {:protocol-version 2}}

;; Client (protocol v1)
{:capabilities {:protocol-version 1}}

;; Server rejects connection
(when-not (= server-protocol client-protocol)
  (close-connection! conn-id
    {:reason :protocol-version-mismatch
     :server-version 2
     :client-version 1}))
```

## Implementation Strategy

### Phase 1: Core Capability Exchange

**Goal**: Basic capability exchange working

Tasks:
- [ ] Add `:capabilities` field to `start-server!` options
- [ ] Extend `:welcome` message to include server capabilities
- [ ] Add `:client-capabilities` message type
- [ ] Store client capabilities in connection state
- [ ] Add basic query functions (`supports-feature?`, `get-client-capabilities`)

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

Old clients (pre-v0.12.0) won't send `:client-capabilities` message.

### Solution

Assume minimal capabilities after timeout:

```clojure
(defn handle-new-connection
  "Handle new WebSocket connection with capability exchange"
  [conn-id ws-connection]
  ;; Send welcome with server capabilities
  (send-welcome! conn-id (get-server-capabilities))

  ;; Wait for client capabilities (with timeout)
  (let [result (wait-for-message conn-id
                                 :client-capabilities
                                 5000)]  ; 5 second timeout
    (if result
      ;; Modern client - has capabilities
      (do
        (store-client-capabilities! conn-id (:capabilities result))
        (log/info "Client capabilities received" {:conn-id conn-id
                                                  :features (get-in result [:capabilities :features])}))
      ;; Legacy client - assume minimal capabilities
      (do
        (store-client-capabilities! conn-id (legacy-client-capabilities))
        (log/warn "Client did not send capabilities, assuming legacy"
                  {:conn-id conn-id})))))

(defn legacy-client-capabilities
  "Minimal capabilities for legacy clients"
  []
  {:protocol-version 0  ; ← Indicates legacy
   :features #{:basic-messaging}  ; Minimal set
   :wire-formats #{:edn}
   :compression #{:none}
   :limits {:max-message-size (* 1024 1024)}})

(defn wait-for-message
  "Wait for specific message type with timeout"
  [conn-id msg-type timeout-ms]
  (let [promise-chan (promise)
        timeout-chan (timeout timeout-ms)

        ;; Register temporary handler
        handler-id (register-temp-handler!
                    conn-id
                    msg-type
                    (fn [msg]
                      (deliver promise-chan msg)))]

    ;; Wait for either message or timeout
    (let [result (alt!
                   promise-chan ([msg] msg)
                   timeout-chan ([_] nil))]
      (unregister-temp-handler! conn-id handler-id)
      result)))
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
