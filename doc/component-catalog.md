# Component Catalog

Bottom-up analysis of sente-lite components to identify common patterns.

---

## Layer 1: Core Components

### 1.1 Server (sente-lite.server)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `start-server!` | Initialize and start |
| `stop-server!` | Shutdown |
| `get-server-stats` | Status/health |
| `get-server-port` | Runtime state query |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:port` | int | `3000` | TCP port (0 = ephemeral) |
| `:host` | string | `nil` | Bind address |
| `:wire-format` | keyword | `:edn` | Serialization |
| `:on-message` | fn | `nil` | User event handler |
| `:wrap-recv-evs?` | bool | `false` | Sente compat wrapping |
| `[:websocket :max-connections]` | int | `1000` | Connection limit |
| `[:websocket :max-message-bytes]` | int | `1048576` | Message size limit |
| `[:heartbeat :enabled]` | bool | `true` | Ping/pong enabled |
| `[:heartbeat :interval-ms]` | int | `30000` | Ping interval |
| `[:heartbeat :timeout-ms]` | int | `60000` | Pong timeout |
| `[:telemetry :enabled]` | bool | `true` | Telemetry on/off |
| `[:telemetry :handler-id]` | keyword | `:sente-lite-server` | Handler identifier |

**State:**
- `server-state` atom: `{:running?, :config, :http-server, :connections, :start-time}`

**Dependencies:** None (foundation layer)

---

### 1.2 Client (sente-lite.client-bb / client_scittle)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `make-client!` | Create and connect |
| `close!` | Disconnect |
| `get-status` | Status query |
| `get-stats` | Health/metrics |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:url` | string | *required* | WebSocket URL |
| `:on-open` | fn | `nil` | Connect callback |
| `:on-message` | fn | `nil` | Message callback |
| `:on-close` | fn | `nil` | Disconnect callback |
| `:on-reconnect` | fn | `nil` | Reconnect callback |
| `:auto-reconnect?` | bool | `true` | Auto-reconnect enabled |
| `:reconnect-delay` | int | `1000` | Initial delay ms |
| `:max-reconnect-delay` | int | `30000` | Max delay ms |
| `:wrap-recv-evs?` | bool | `false` | Sente compat |
| `[:send-queue :max-depth]` | int | `1000` | Queue size |

**State:**
- Client state atom: `{:status, :uid, :ws, :reconnect-enabled?, :reconnect-delay, :handlers}`

**Dependencies:** None (foundation layer)

---

## Layer 2: Channel Components

### 2.1 Channels (sente-lite.channels)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `create-channel!` | Create channel |
| `subscribe!` | Add subscriber |
| `unsubscribe!` | Remove subscriber |
| `unsubscribe-all!` | Remove all for conn |
| `list-channels` | Status query |
| `cleanup-expired-rpc-requests!` | Maintenance |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `[:channels :auto-create]` | bool | `false` | Auto-create on subscribe |
| `[:channels :default-config :max-subscribers]` | int | `1000` | Per-channel limit |
| `[:channels :default-config :max-subscriptions-per-conn]` | int | `100` | Per-conn limit |

**Per-operation Config:**
| Operation | Parameter | Default | Description |
|-----------|-----------|---------|-------------|
| `send-rpc-request!` | `:timeout-ms` | `5000` | RPC timeout |

**State:**
- `channels` atom: `{channel-id -> {:config, :subscribers, :message-history}}`
- `conn-subscriptions` atom: `{conn-id -> #{channel-ids}}`
- `rpc-requests` atom: `{request-id -> {:conn-id, :timeout}}`

**Dependencies:** Server (for connection management)

---

## Layer 3: General Module Components

### 3.1 Registry (sente-lite.registry)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `set-reg-root!` | Configure namespace root |
| `register!` | Create entry |
| `unregister!` | Remove entry |
| `unregister-prefix!` | Bulk remove |
| `list-registered` | Status query |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `reg-root` | string | `"sente-lite.registry"` | Namespace prefix |

**State:**
- `reg-root` atom: namespace prefix string
- `registered-names` atom: set of registered names

**Dependencies:** None (utility layer)

---

### 3.2 config-discovery (config-discovery.handlers)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `discover-from-hardcoded!` | Initialize defaults |
| `discover-from-html!` | Discover from DOM |
| `discover-from-json-script!` | Discover from JSON |
| `build-ws-url` | State query |

**Config Parameters (sets registry values):**
| Registry Key | Type | Description |
|--------------|------|-------------|
| `config.server/ws-host` | string | WebSocket host |
| `config.server/ws-port` | int | WebSocket port |
| `config.server/ws-path` | string | WebSocket path |
| `config.server/api-base` | string | API base URL |
| `config.env/mode` | keyword | Environment mode |

**Handler-specific Config:**
| Handler | Parameter | Default | Description |
|---------|-----------|---------|-------------|
| `discover-from-json-script!` | `:element-id` | `"sente-config"` | Script tag ID |
| `discover-from-json-script!` | `:register?` | `true` | Create vs update |

**State:** Uses registry (no internal state)

**Dependencies:** Registry

---

### 3.3 log-routing.registry-handlers

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `init!` | Initialize defaults |
| `init-with-sente!` | Initialize with sente handler |
| `register-impl!` | Add implementation |
| `use-handler!` | Switch handler |
| `get-handler` | Get current handler |
| `list-implementations` | Status query |

**Config Parameters (registry keys):**
| Registry Key | Type | Description |
|--------------|------|-------------|
| `telemetry/log-handler` | string | Points to current impl |
| `telemetry.impl/console` | fn | Console handler |
| `telemetry.impl/silent` | fn | Silent handler |
| `telemetry.impl/sente` | fn | Remote handler |

**Handler-creation Config:**
| Function | Parameter | Default | Description |
|----------|-----------|---------|-------------|
| `make-sente-handler` | `:channel` | `"log-routing"` | Pub/sub channel |
| `make-sente-handler` | `:source-id` | `"unknown"` | Source identifier |

**State:** Uses registry (no internal state)

**Dependencies:** Registry

---

### 3.4 atom-sync.publisher

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `start!` | Start publishing |
| `stop!` | Stop publishing |
| `publish-current!` | Force publish |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:atom-id` | keyword | *required* | Unique atom identifier |
| `:channel` | string | `"atom-sync"` | Pub/sub channel |

**State:**
- `versions` atom: `{atom-id -> version-number}`

**Dependencies:** Client (for pub/sub)

---

### 3.5 atom-sync.subscriber

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `start!` | Start receiving |
| `stop!` | Stop receiving |
| `request-current!` | Request initial sync (future) |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:atom-id` | keyword | *required* | Atom to sync |
| `:channel` | string | `"atom-sync"` | Pub/sub channel |
| `:on-update` | fn | `nil` | Update callback |

**State:** Uses target atom (no internal state)

**Dependencies:** Client (for pub/sub)

---

### 3.6 log-routing.sender

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `make-remote-log-fn` | Create wrapped log-fn |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:source-id` | string | `"unknown"` | Log source identifier |
| `:exclude-ns-prefixes` | set | `#{"sente-lite." "log-routing."}` | Namespaces to skip |

**State:** Re-entrant guard (`*sending?*` dynamic var)

**Dependencies:** Client (for pub/sub)

---

### 3.7 log-routing.receiver

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `start!` | Start receiving |
| `stop!` | Stop receiving |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:handler` | fn | `nil` | Log entry handler |
| `:channel` | string | `"log-routing"` | Pub/sub channel |

**State:** Uses client handlers (no internal state)

**Dependencies:** Client (for pub/sub)

---

## Common Patterns Identified

### Pattern 1: Lifecycle Functions

Almost every component has:
```
init!/start!    -> Create/configure
stop!           -> Cleanup
get-*/list-*    -> Status query
```

### Pattern 2: Config Parameter Categories

| Category | Examples |
|----------|----------|
| **Limits** | max-connections, max-depth, max-subscribers |
| **Timing** | interval-ms, timeout-ms, delay |
| **Toggles** | enabled, auto-create, auto-reconnect? |
| **Identity** | handler-id, source-id, atom-id |
| **Routing** | channel, port, host, path |
| **Callbacks** | on-message, on-open, on-update, handler |

### Pattern 3: State Management

| Approach | Used By |
|----------|---------|
| Internal atom | Server, Client, Channels |
| Registry | config-discovery, log-routing |
| External atom | atom-sync (user's atom) |
| Dynamic var | log-routing.sender (re-entrancy) |

### Pattern 4: Dependencies

```
Layer 4: Use Cases
    └── Layer 3: General Modules
            └── Layer 2: Channels
                    └── Layer 1: Core (Server/Client)
                            └── Layer 0: Registry (utility, no deps)
```

### Pattern 5: Health/Status Queries

| Component | Status Function | Returns |
|-----------|-----------------|---------|
| Server | `get-server-stats` | `{:running?, :connections, :uptime-ms}` |
| Client | `get-status` | `:connected`, `:disconnected`, etc. |
| Client | `get-stats` | `{:messages-sent, :messages-received}` |
| Registry | `list-registered` | Set of registered names |
| Channels | `list-channels` | Map of channel info |

---

## Proposed Component Protocol

Based on patterns observed:

```clojure
(defprotocol IComponent
  ;; Lifecycle
  (start! [this config])
  (stop! [this])

  ;; Status
  (status [this])       ; => :stopped | :starting | :running | :error
  (health [this])       ; => {:healthy? bool :details {...}}

  ;; Optional
  (config [this])       ; => current config map
  (stats [this]))       ; => metrics map
```

### Standard Config Keys (conventions)

```clojure
;; Limits
:max-*            ; Maximum values
:min-*            ; Minimum values

;; Timing
:*-ms             ; Milliseconds
:*-interval-ms    ; Recurring intervals
:*-timeout-ms     ; Timeouts
:*-delay          ; Delays

;; Toggles
:enabled          ; Boolean on/off
:auto-*           ; Automatic behaviors

;; Identity
:*-id             ; Identifiers

;; Callbacks (not serializable)
:on-*             ; Event callbacks
:handler          ; Processing function
```

---

## Archetypal Communication Patterns

Based on use cases from `doc/sente-lite-modules.md`:

### Pattern A: Fire-and-Forget (with optional ack)

**Example**: Remote Logging

```
Sender → Channel → Receiver
         (one-way, optional ack)
```

**Characteristics**:
- Wraps existing mechanism (Trove log-fn)
- Filters before sending (level, namespace)
- Optional batching (batch-size, batch-timeout-ms)
- Graceful fallback if channel unavailable
- No request correlation needed

**Config Categories**:
| Category | Parameters |
|----------|------------|
| Filtering | `:levels`, `:exclude-ns-prefixes` |
| Batching | `:batch-size`, `:batch-timeout-ms` |
| Buffering | `:max-queue-size`, `:fallback-to-local` |
| Identity | `:source-id` |

---

### Pattern B: Request/Response with Streaming

**Example**: nREPL-over-Sente

```
Client                    Server
  │ request (id=123)        │
  │───────────────────────→ │
  │                         │ process
  │       output chunk 1    │
  │←─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│
  │       output chunk 2    │
  │←─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─│
  │       result + status   │
  │←───────────────────────│
```

**Characteristics**:
- Request/response correlation by ID
- Multiple responses per request (streaming)
- Session management (session-id + request-id)
- **Fully async** - never block waiting
- Timeout handling with cleanup

**State Required**:
```clojure
(def pending-requests (atom {}))
;; {request-id -> {:callback, :timeout-ms, :responses, :sent-at}}
```

**Config Categories**:
| Category | Parameters |
|----------|------------|
| Timing | `:timeout-ms` |
| Session | `:session-id`, `:request-id` |
| Limits | `:max-pending-requests` |

---

### Pattern C: Directive-based Out-of-Band

**Example**: HTTP Blob Transfer

```
Server                              Browser
  │ [:blob/fetch {:url ...}]          │
  │────────────────────────────────→  │
  │        (sente directive)          │
  │                                   │
  │                                   ├──→ HTTP GET url
  │                                   │    (browser cache)
  │                                   │←── blob data
  │                                   │
  │       [:blob/complete]            │
  │←────────────────────────────────  │
```

**Characteristics**:
- Control messages via WebSocket (lightweight)
- Actual data via HTTP (caching, compression, resume)
- Handler dispatch based on `:handler` field
- Verification (SHA256), progress tracking

**Config Categories**:
| Category | Parameters |
|----------|------------|
| Transfer | `:url`, `:handler`, `:metadata` |
| Reliability | `:retry-count`, `:timeout-ms`, `:verify` |
| Caching | `:cache`, `:sha256` |

---

### Pattern D: Protocol Proxy/Tunnel

**Example**: LSP over Sente

```
Browser Editor    Sente Channel    LSP Server
     │                 │                │
     │  :lsp/request   │                │
     │────────────────→│  JSON-RPC      │
     │                 │───────────────→│
     │                 │    response    │
     │                 │←───────────────│
     │  :lsp/response  │                │
     │←────────────────│                │
     │                 │                │
     │                 │  notification  │
     │  :lsp/notify    │←───────────────│
     │←────────────────│                │
```

**Characteristics**:
- Tunnel existing protocol (LSP, nREPL) over sente
- Protocol translation layer (JSON-RPC ↔ sente events)
- Multiple backend servers (clojure-lsp, rust-analyzer)
- Bidirectional (requests + unsolicited notifications)
- Session per editor/file

**Config Categories**:
| Category | Parameters |
|----------|------------|
| Servers | `:servers {:lang {:command :args}}` |
| Session | `:max-sessions`, `:session-timeout-ms` |
| Methods | Supported LSP methods list |

---

### Pattern E: Reactive State Sync

**Example**: Atom Synchronization

```
Publisher                        Subscriber
  │ atom change (watch)            │
  │───────────────────────────────→│
  │                                │ reset! atom
  │                                │ triggers watchers
  │                                │ UI updates
```

**Variants**:
1. **One-way** (Server → Client): Simple push
2. **Two-way** (Server ↔ Client): Needs conflict resolution
3. **Multi-master**: Version vectors, CRDTs

**Characteristics**:
- Uses atom watchers (`add-watch`, `remove-watch`)
- Version/timestamp for ordering
- Optional debouncing for rapid changes
- Conflict resolution for two-way

**Config Categories**:
| Category | Parameters |
|----------|------------|
| Direction | `:one-way`, `:two-way` |
| Timing | `:debounce-ms`, `:sync-interval-ms` |
| Conflict | `:conflict-resolution` (`:last-writer-wins`, `:merge`) |
| Filtering | `:paths` (which keys to sync) |

---

## Cross-Pattern Analysis

### Common Config Categories

| Category | Patterns | Examples |
|----------|----------|----------|
| **Timing** | All | timeout-ms, interval-ms, debounce-ms |
| **Identity** | A, B, D | source-id, session-id, request-id |
| **Limits** | All | max-*, batch-size, queue-size |
| **Filtering** | A, E | levels, paths, exclude-prefixes |
| **Reliability** | B, C | retry-count, fallback, verify |

### State Management by Pattern

| Pattern | State Atom | Contents |
|---------|------------|----------|
| A: Fire-forget | Optional buffer | Pending messages to batch |
| B: Req/Resp | `pending-requests` | {id → callback, timeout, responses} |
| C: Directive | Stateless | (HTTP layer handles) |
| D: Proxy | `sessions` | {session-id → server, pending} |
| E: Sync | External atom | User's data |

### Implementation Complexity

All modules follow 3-phase pattern:

| Phase | LOC | Features |
|-------|-----|----------|
| **1: MVP** | 100-300 | Core functionality, development use |
| **2: Production** | 300-500 | Batching, reliability, config |
| **3: Enterprise** | 500+ | Pooling, caching, monitoring |

---

## Summary: Config Parameter Count by Component

| Component | Config Params | Lifecycle Fns | Has State |
|-----------|--------------|---------------|----------|
| Server | 12 | 3 | Yes |
| Client | 10 | 3 | Yes |
| Channels | 4 | 6 | Yes |
| Registry | 1 | 5 | Yes |
| config-discovery | 2 | 4 | No (uses registry) |
| log-routing.handlers | 3 | 6 | No (uses registry) |
| atom-sync.publisher | 2 | 3 | Yes (versions) |
| atom-sync.subscriber | 3 | 3 | No |
| log-routing.sender | 2 | 1 | Yes (dynamic var) |
| log-routing.receiver | 2 | 2 | No |

**Total: ~40 unique config parameters across all components**

---

## Platform Compatibility Research

### Tested Platforms
- **Babashka (BB)**: GraalVM native Clojure
- **nbb**: Node.js ClojureScript via SCI
- **Scittle**: Browser ClojureScript via SCI

### Feature Compatibility Matrix

| Feature | Babashka | nbb | Scittle | Notes |
|---------|----------|-----|---------|-------|
| `defprotocol` | ✅ | ✅ | ✅ | Works on all |
| `reify` | ✅ | ✅ | ✅ | Works on all |
| `defrecord` | ✅ | ✅ | ✅ | Works on all |
| `defrecord` + inline protocol | ✅ | ✅ | ✅ | Works on all |
| `defmulti/defmethod` | ✅ | ✅ | ✅ | Works on all |
| `satisfies?` | ✅ | ✅ | ✅ | Works on all |
| `deftype` | ✅ | ✅ | ❌ | **Fails in Scittle** |
| `extend-protocol` | ✅ | ✅ | ❌ | **Crashes Scittle** |
| `extend-type` | ✅ | ✅ | ❌ | **Crashes Scittle** |

### Critical Scittle Limitations

**`extend-protocol` crashes with:**
```
No protocol method HasName.getName defined for type null
```

This is a [known class of SCI bug](https://github.com/babashka/sci/issues/306) where
protocol methods are called on `nil`. The Scittle-bundled SCI version exhibits this.

**`deftype` fails with:**
```
[object Object] is not a constructor
```

Per [SCI README](https://github.com/babashka/sci): "Currently SCI has limited support
for `deftype` and does not support `definterface`."

### Sources
- [SCI GitHub](https://github.com/babashka/sci)
- [SCI CHANGELOG](https://github.com/babashka/sci/blob/master/CHANGELOG.md)
- [Issue #279: Implement protocols](https://github.com/babashka/sci/issues/279)
- [Issue #306: HasName.getName on nil](https://github.com/babashka/sci/issues/306)
- [Issue #783: extend protocols to JS built-ins](https://github.com/babashka/sci/issues/783)

---

## Implementation Approach Analysis

### ❌ Rejected: defprotocol + extend-protocol

```clojure
;; Crashes Scittle - DO NOT USE
(defprotocol IComponent (start! [this]))
(defrecord Server [config])
(extend-protocol IComponent
  Server
  (start! [this] ...))  ; CRASHES
```

**Problems:**
- Crashes Scittle entirely (not caught by try/catch)
- Brittle - fails silently on some platforms
- Can't add protocol implementations after defrecord

### ⚠️ Limited: defprotocol + inline defrecord

```clojure
;; Works but inflexible
(defprotocol IComponent (start! [this]))
(defrecord Server [config]
  IComponent
  (start! [this] ...))  ; Must be inline
```

**Problems:**
- Implementation must be defined with defrecord (not extensible)
- Can't add new protocol methods to existing record types
- Can't implement protocols on plain maps
- Tight coupling between type and behavior

### ✅ Recommended: Multimethods with :type dispatch

```clojure
;; Works everywhere, extensible, plain data
(defmulti start! :component/type)
(defmulti stop! :component/type)
(defmulti status :component/type)
(defmulti health :component/type)

;; Components are plain maps
(defn make-server [config]
  {:component/type :sente-lite/server
   :config config
   :state (atom {:status :stopped})})

;; Implementations - can be in separate namespaces
(defmethod start! :sente-lite/server [{:keys [state config]}]
  (swap! state assoc :status :running)
  :ok)

(defmethod stop! :sente-lite/server [{:keys [state]}]
  (swap! state assoc :status :stopped)
  :ok)

(defmethod status :sente-lite/server [{:keys [state]}]
  (:status @state))

(defmethod health :sente-lite/server [{:keys [state]}]
  {:healthy? (= :running (:status @state))})
```

**Advantages:**
- ✅ Works on all platforms (BB, nbb, Scittle)
- ✅ Components are plain maps (inspectable, serializable config)
- ✅ Extensible - add implementations anywhere, anytime
- ✅ Open system - new component types without modifying core
- ✅ Namespaced keywords prevent collisions
- ✅ No crashes, no brittle protocol machinery
- ✅ Familiar Clojure pattern

**Considerations:**
- No compile-time method checking (runtime error if method missing)
- Must remember to implement all methods for new types
- Dispatch adds minor overhead (negligible)

---

## Recommended Component System Design

### Core Multimethods

```clojure
(ns sente-lite.component)

;; Lifecycle
(defmulti start!
  "Initialize component. Returns :ok or throws."
  :component/type)

(defmulti stop!
  "Shutdown component. Returns :ok or throws."
  :component/type)

;; Introspection
(defmulti status
  "Returns :stopped | :starting | :running | :stopping | :error"
  :component/type)

(defmulti health
  "Returns {:healthy? bool :details map}"
  :component/type)

;; Optional
(defmulti stats
  "Returns runtime metrics map"
  :component/type)

;; Default implementations
(defmethod start! :default [c]
  (throw (ex-info "No start! implementation" {:type (:component/type c)})))

(defmethod stop! :default [_] :ok)

(defmethod status :default [{:keys [state]}]
  (if state (:status @state) :unknown))

(defmethod health :default [c]
  {:healthy? (= :running (status c))
   :type (:component/type c)})

(defmethod stats :default [_] {})
```

### Component Factory Pattern

```clojure
(defn make-component
  "Create a component map with standard structure."
  [component-type config]
  {:component/type component-type
   :config config
   :state (atom {:status :stopped
                 :started-at nil
                 :error nil})})

;; Convenience
(defn running? [c] (= :running (status c)))
(defn stopped? [c] (= :stopped (status c)))
```

### Example: Server Component

```clojure
(ns sente-lite.component.server
  (:require [sente-lite.component :as c]))

(defn make-server [config]
  (c/make-component :sente-lite/server config))

(defmethod c/start! :sente-lite/server
  [{:keys [config state] :as component}]
  (when (c/stopped? component)
    (let [port (:port config 3000)]
      ;; ... actual server startup ...
      (swap! state assoc
             :status :running
             :started-at (System/currentTimeMillis)
             :port port)))
  :ok)

(defmethod c/stop! :sente-lite/server
  [{:keys [state] :as component}]
  (when (c/running? component)
    ;; ... actual server shutdown ...
    (swap! state assoc :status :stopped))
  :ok)

(defmethod c/health :sente-lite/server
  [{:keys [state config]}]
  (let [{:keys [status started-at port]} @state]
    {:healthy? (= :running status)
     :status status
     :port port
     :uptime-ms (when started-at
                  (- (System/currentTimeMillis) started-at))
     :config-port (:port config)}))
```

### Usage

```clojure
(require '[sente-lite.component :as c]
         '[sente-lite.component.server :as server])

(def my-server (server/make-server {:port 8080}))

(c/start! my-server)
(c/status my-server)   ; => :running
(c/health my-server)   ; => {:healthy? true :status :running :port 8080 ...}
(c/stop! my-server)
```

### Alternative: Maps with Function Values

For simpler cases where extensibility isn't needed:

```clojure
(defn make-simple-component [config init-fn cleanup-fn]
  (let [state (atom {:status :stopped})]
    {:config config
     :state state
     :start! (fn []
               (init-fn config)
               (swap! state assoc :status :running))
     :stop! (fn []
              (cleanup-fn)
              (swap! state assoc :status :stopped))
     :status (fn [] (:status @state))}))

;; Usage - call functions directly
((:start! my-component))
((:status my-component))
```

**Trade-off**: Simpler but not extensible, functions not serializable.

---

## Summary: Recommended Approach

| Aspect | Recommendation |
|--------|----------------|
| **Dispatch** | Multimethods on `:component/type` |
| **Components** | Plain maps with atom for mutable state |
| **Type keys** | Namespaced keywords (`:sente-lite/server`) |
| **Extensions** | Add `defmethod` in any namespace |
| **Factory** | `make-*` functions returning component maps |
| **Protocols** | Avoid - use multimethods instead |
| **deftype** | Avoid - doesn't work in Scittle |
| **extend-protocol** | Avoid - crashes Scittle |
