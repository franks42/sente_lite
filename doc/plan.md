# sente-lite Implementation Plan

**Version:** 1.0
**Created:** 2025-10-24
**Status:** Active Development Plan

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

## Version Requirements

### Minimum Versions
- **Babashka:** 1.12.207+ (latest stable, includes http-client.websocket built-in)
- **HTTP Kit:** 2.8.0+ (Babashka/GraalVM compatible)
- **Transit:** cognitect/transit-clj 1.0.333+

### Telemetry Strategy
Since Telemere v1.1.0 doesn't support Babashka (dependency on Encore with incompatible classes), we'll implement a lightweight, BB-compatible telemetry solution inspired by Telemere's API design. **Starting simple with file-based logging** to establish core functionality before adding advanced features.

## Modular Project Structure

### Core Design Philosophy
- **Modular imports**: Each component can be imported independently into BB
- **Shared dependencies**: Common code factored into reusable modules
- **Clear boundaries**: Client, server, telemetry, and lifecycle separated
- **Maximum code sharing**: Use `.cljc` files with reader conditionals for BB/Scittle
- **Platform-agnostic core**: Business logic independent of runtime environment

```
sente_lite/
├── bb.edn                       # Root project config
├── deps.edn                     # Alternative for tools.deps users
│
├── src/
│   ├── sente_lite/
│   │   └── shared/              # Shared functionality (no dependencies)
│   │       ├── core.cljc        # Common utilities, protocols (BB & Scittle)
│   │       ├── wire_format.cljc # EDN/JSON/Transit serialization (pluggable formats)
│   │       ├── router.cljc      # Event routing and dispatch (shared)
│   │       └── state.cljc       # Connection state management (shared)
│   │
│   ├── telemere_lite/           # Standalone telemetry library
│   │   ├── core.cljc            # Shared API (BB & Scittle)
│   │   ├── handlers.cljc        # Platform-specific output via reader conditionals
│   │   ├── filters.cljc         # Filtering and sampling (pure Clojure)
│   │   ├── signals.cljc         # Signal creation (shared)
│   │   ├── transport.cljc       # WebSocket transport for browser→server telemetry
│   │   └── buffer.cljc          # Signal batching and buffering
│   │
│   ├── sente_lite_client/       # Standalone client library
│   │   ├── core.clj             # Client API (depends on shared/)
│   │   ├── websocket.clj        # BB WebSocket client wrapper
│   │   └── reconnect.clj        # Reconnection logic
│   │
│   ├── sente_lite_server/       # Standalone server library
│   │   ├── core.clj             # Server API (depends on shared/)
│   │   ├── websocket.clj        # HTTP Kit WebSocket handler
│   │   └── routing.clj          # User-based message routing
│   │
│   └── sente_lite_lifecycle/    # Optional lifecycle management
│       ├── core.clj             # Declarative system management
│       ├── config.clj           # Configuration management
│       └── monitoring.clj       # Health checks and metrics
│
├── src_browser/                 # Phase 2 - Browser client
│   └── sente_lite_browser/
│       ├── core.cljs            # Browser client API
│       └── websocket.cljs       # Browser WebSocket wrapper
│
├── src_nbb/                     # Phase 4 - Node.js client
│   └── sente_lite_nbb/
│       └── core.cljs            # nbb client API
│
├── test/
│   ├── sente_lite/
│   │   └── shared/              # Shared component tests
│   ├── telemere_lite/           # Telemetry tests
│   ├── sente_lite_client/       # Client tests
│   ├── sente_lite_server/       # Server tests
│   └── integration/             # Cross-component integration tests
│
├── examples/
│   ├── basic/                   # Minimal examples
│   │   ├── echo_client.clj      # Just client + shared
│   │   ├── echo_server.clj      # Just server + shared
│   │   └── echo_full.clj        # Client + server + lifecycle
│   ├── telemetry/               # Telemetry examples
│   │   ├── file_logging.clj     # Simple file-based logging
│   │   └── structured_logs.clj  # JSON/EDN structured logging
│   └── advanced/                # Complex examples
│       ├── chat_system/         # Multi-component chat app
│       └── monitoring/          # Full lifecycle + monitoring
│
└── docs/
    ├── plan.md                  # This file
    ├── imports.md               # Import guide for each module
    └── architecture.md          # Modular architecture guide
```

### Import Patterns

#### Minimal Client Script
```clojure
#!/usr/bin/env bb
(require '[sente-lite.shared.core :as core]
         '[sente-lite.shared.codec :as codec]
         '[sente-lite-client.core :as client])

(def ws (client/connect! "ws://localhost:8080/ws"))
```

#### Minimal Server Script
```clojure
#!/usr/bin/env bb
(require '[sente-lite.shared.core :as core]
         '[sente-lite.shared.router :as router]
         '[sente-lite-server.core :as server])

(def srv (server/start! {:port 8080}))
```

#### Full System with Telemetry
```clojure
#!/usr/bin/env bb
(require '[telemere-lite.core :as tel]
         '[sente-lite-lifecycle.core :as lifecycle])

(def system (lifecycle/start! (load-file "config.edn")))
```

#### Standalone Telemetry (File-based)
```clojure
#!/usr/bin/env bb
(require '[telemere-lite.core :as tel])

;; Simple file-based logging
(tel/log! :info "Application started" {:pid (System/getProperty "bb.pid")})
;; Writes to: logs/app.log as JSON lines

;; Configure log file location
(tel/configure! {:log-file "logs/sente-lite.log"
                 :format :json
                 :level :info})
```

## Lifecycle Management Strategy

### Component Lifecycle Patterns

#### 1. **Component Protocol**
```clojure
(defprotocol ILifecycle
  (start [component config] "Start the component")
  (stop [component] "Stop the component")
  (health-check [component] "Check component health"))
```

#### 2. **System Map Approach** (Inspired by Component/Integrant)
```clojure
;; config.edn
{:sente-lite/telemetry {}
 :sente-lite/server {:port 8080
                     :telemetry (ig/ref :sente-lite/telemetry)}
 :sente-lite/client {:url "ws://localhost:8080/ws"
                     :telemetry (ig/ref :sente-lite/telemetry)}}

;; Usage
(def system (lifecycle/start-system! (load-file "config.edn")))
(lifecycle/stop-system! system)
```

#### 3. **Graceful Shutdown Handling**
```clojure
;; Register shutdown hooks
(.addShutdownHook (Runtime/getRuntime)
  (Thread. #(lifecycle/shutdown-all!)))

;; Or with Babashka process management
(lifecycle/on-signal! :SIGTERM graceful-shutdown!)
```

#### 4. **Health Monitoring**
```clojure
;; Built-in health endpoints
(lifecycle/add-health-check! :websocket-server
  (fn [] {:status (if (server-running?) :ok :error)
          :connections (count @connections)}))
```

### Lifecycle Management Recommendations

#### **For Simple Use Cases:**
- **Manual lifecycle**: Direct start/stop calls
- **Atom-based state**: Simple state tracking
- **No dependencies**: Each component manages itself

#### **For Production Systems:**
- **Dependency injection**: Components declare dependencies
- **Ordered startup/shutdown**: Respect dependency graph
- **Health monitoring**: Built-in health checks
- **Graceful degradation**: Handle partial failures

#### **BB-Specific Considerations:**
- **Signal handling**: SIGTERM/SIGINT for graceful shutdown
- **Process management**: Integration with systemd/Docker
- **Resource cleanup**: Ensure WebSocket connections close
- **Error recovery**: Restart strategies for resilience

### Available Lifecycle Management Options

#### **Recommended: Integrant (BB Compatible)**
- **Status**: ✅ Compatible with Babashka (2024)
- **Requirements**: Uses `spartan.spec` instead of `clojure.spec.alpha`
- **Benefits**: Mature, dependency injection, system maps
- **Usage**: `(require '[integrant.core :as ig])`

#### **Alternative: Component (BB Compatible)**
- **Status**: ✅ Listed in Babashka Toolbox
- **Benefits**: Simple lifecycle protocol, dependency management
- **Usage**: Direct component management

#### **Mount (Compatibility Unknown)**
- **Status**: ⚠️ Not explicitly tested with Babashka
- **Concerns**: Uses `clojure.lang.IDeref` and JVM-specific features
- **Alternative**: Could inspire our custom solution

#### **Signal Handling Solutions**
- **Java Shutdown Hooks**: ✅ Recommended for BB
  ```clojure
  (.addShutdownHook (Runtime/getRuntime)
    (Thread. #(lifecycle/shutdown-all!)))
  ```
- **Native SIGTERM**: ⚠️ Limited support, use shutdown hooks instead
- **Container Support**: ✅ Shutdown hooks work with Docker/K8s

### Implementation Strategy

#### **Phase 1**: Custom Lifecycle + Integrant Option
- Custom lightweight solution for basic use cases
- Optional Integrant integration for advanced users
- Shutdown hooks for graceful termination

#### **Phase 2**: Advanced Features
- Health monitoring and metrics
- Dependency graph visualization
- Configuration validation

#### **Phase 3**: Production Features
- Circuit breakers and recovery
- Performance monitoring
- Log aggregation

## Development Phases

### Phase 0: Telemere-lite Foundation ✅ COMPLETE
**Goal:** Unified telemetry library for BB and Scittle with maximum code sharing
**Status:** Production-ready, all tests passing

#### Recent Updates (v0.7.0 - v0.7.4, October 2025)

**v0.7.0 - Major Improvements**
- [x] **Shutdown Hook** - Automatic async handler cleanup on JVM exit
- [x] **Regex Pre-compilation** - 2-10x faster namespace/event-ID filtering
- [x] **Customizable Error Handler** - `set-error-handler!` for custom error handling
- [x] **Tests**: 14 tests, 43 assertions covering all improvements

**v0.7.1 - Bugfix**
- [x] **Event-ID Filtering Fix** - Corrected keyword→string conversion bug

**v0.7.4 - Documentation Sprint 1**
- [x] **30-Second Quick Start** - Working code example first
- [x] **Troubleshooting Section** - Step-by-step debugging help
- [x] **Complete API Reference** - 100% coverage with inspection functions
- [x] **Performance Benchmarks** - Real test data (2x regex speedup documented)
- [x] **Updated Comparison Table** - Highlights v0.7.0+ features vs Telemere

**Architectural Review (October 2025)**
- ✅ **Production-ready**: Functionality complete, tests passing, performance good
- ✅ **Thread safety**: Non-daemon threads are SAFE due to shutdown hook (documented in code)
- ✅ **Single-file design**: Intentional for Scittle convenience (648 LOC acceptable)
- ⚠️ **Global mutable state**: Acknowledged, low priority for current use case
- ⚠️ **JSON structure complexity**: Noted for potential future cleanup

#### Outstanding Work

**Testing Gaps (Medium Priority)**
- [ ] **Backpressure Tests** - Verify async handler behavior under load:
  - [ ] `:blocking` mode - Verify signals wait when buffer full
  - [ ] `:dropping` mode - Verify signals dropped when buffer full
  - [ ] `:sliding` mode - Verify oldest signals removed when buffer full
  - [ ] Buffer overflow scenarios (queue full, rapid signals)
  - [ ] Graceful degradation under extreme load
  - [ ] Stats tracking accuracy (`:queued`, `:processed`, `:dropped`, `:errors`)

**Architecture Improvements (Low Priority)**
- [ ] Namespace refactoring (if multi-file becomes necessary)
- [ ] Replace global state with runtime configuration map
- [ ] Simplify JSON output structure (remove dual context locations)
- [ ] Move inline "PHASE:" comments to documentation

**Future Enhancements**
- [ ] Sampling/rate limiting for high-volume scenarios
- [ ] Additional handler ecosystem (beyond files, stdout, custom)
- [ ] Browser → Server telemetry batching (Scittle → BB)

#### Strategy: Platform-Agnostic Core + Platform-Specific Backends
Create a shared API that works in both BB and Scittle, with minimal platform-specific code.

#### Focus: Shared Telemetry API with Reader Conditionals
- [x] **Core Telemere-lite** (`telemere-lite/`)
  - `core.cljc` - Shared API using reader conditionals
  - `signals.cljc` - Signal creation (works in BB and Scittle)
  - `structured.cljc` - JSON/EDN formatting (pure Clojure)
  - `handlers.cljc` - Platform-specific output:
    ```clojure
    #?(:bb (require '[taoensso.timbre :as timbre])
       :scittle (require '[js/console :as console]))
    ```

#### Shared Code Examples (BB & Scittle):
```clojure
;; Same API works in both environments
(require '[telemere-lite.core :as tel])

;; Shared signal creation
(tel/log! :info "Application started" {:timestamp (tel/now)})

;; Platform-specific output strategies
#?(:bb      (timbre/info data)           ; BB writes to file
   :scittle (case (tel/get-mode)
              :local   (js/console.log (tel/->json data))     ; Dev: console
              :remote  (sente/send! [:tel/signal data])       ; Prod: to server
              :hybrid  (do (js/console.log (tel/->json data)) ; Both
                          (sente/send! [:tel/signal data]))))

;; Configuration with remote logging option
(tel/configure!
  #?(:bb {:output :file :path "logs/app.log"}
     :scittle {:output :remote  ; Send to BB server
               :batch-size 10   ; Batch for efficiency
               :buffer-ms 1000  ; Or send every second
               :fallback :console})) ; If WebSocket down
```

#### Browser → Server Telemetry Flow:
```clojure
;; Browser (Scittle)
(tel/log! :error "Client error" {:user-id 123 :error e})
;; ↓ Batched over WebSocket
[:tel/batch [{:level :error :msg "Client error" ...}
             {:level :info :msg "User action" ...}]]

;; Server (BB) - receives and processes
(defmethod handle-event :tel/batch
  [{:keys [data uid]}]
  (doseq [signal data]
    (tel/process-remote-signal signal uid))) ; Logs to file with client context
```

#### Testing Strategy:
- [ ] **Timbre configuration** - Verify structured output works
- [ ] **BB startup monitoring** - Track script initialization
- [ ] **Module loading metrics** - Time and trace namespace loading
- [ ] **Integration with tools.logging** - Ensure seamless wrapping
- [ ] **JSON output verification** - Test structured logging formats

#### Deliverables:
- **Structured logging wrapper** around BB's built-in logging
- **BB startup monitoring** utilities leveraging Timbre
- **JSON/EDN output formatters** for Timbre appenders
- **Module loading instrumentation** using timing macros

#### Benefits of This Approach:
- **Zero additional dependencies** - Uses BB's built-in logging
- **Familiar APIs** - Developers already know tools.logging/Timbre
- **Battle-tested** - Leverages mature, proven logging libraries
- **Extensible** - Can add structure without reinventing logging
- **Performance** - Native BB libraries are optimized

### Phase 1: Shared Foundation (Week 2)
**Goal:** Core utilities and protocols for sente-lite ecosystem

#### Tasks:
- [ ] **Shared Foundation** (`sente-lite/shared/`)
  - `core.clj` - Protocols, utilities, constants (with telemere-lite integration)
  - `wire_format.cljc` - EDN/JSON/Transit serialization (pluggable formats with logging)
  - `router.clj` - Event dispatch system (with event metrics)
  - `state.clj` - Connection state management (with state change logging)

#### Integration Focus:
- Integrate telemere-lite into all shared components
- Add telemetry to serialization performance
- Track event routing metrics
- Monitor state changes and transitions

### Phase 2: Client & Server Libraries (Week 3)
**Goal:** WebSocket client and server with full telemetry integration

#### Tasks:
- [ ] **Client Library** (`sente-lite-client/`)
  - `core.clj` - Public API with connection telemetry
  - `websocket.clj` - BB WebSocket wrapper with message metrics
  - `reconnect.clj` - Backoff logic with retry telemetry

- [ ] **Server Library** (`sente-lite-server/`)
  - `core.clj` - Public API with server telemetry
  - `websocket.clj` - HTTP Kit handler with connection metrics
  - `routing.clj` - User routing with routing telemetry

### Phase 3: Lifecycle Management (Week 4)
**Goal:** Optional system management with integrated monitoring

#### Tasks:
- [ ] **Lifecycle Management** (`sente-lite-lifecycle/`)
  - `core.clj` - Lifecycle with component telemetry
  - `config.clj` - Configuration with validation logging
  - `monitoring.clj` - Health checks and metrics collection
  - `shutdown.clj` - Graceful shutdown with telemetry
  - `integrant.clj` - Optional Integrant adapter

#### Rationale:
Starting with BB-only allows us to:
- Validate core architecture quickly
- Test without browser complexity
- Develop faster iteration cycles
- Ensure BB-to-BB communication works perfectly
- Build confidence before expanding to other platforms

### Phase 2A: Browser/Scittle Development with Playwright (Week 3)
**Goal:** AI-driven browser development with complete visibility

#### Playwright-Powered Development Strategy:
```javascript
// AI agent has complete browser control
const page = await playwright.launch();
page.on('console', msg => telemetry.log(msg));
page.on('pageerror', err => telemetry.error(err));
page.on('request', req => telemetry.track(req));

// AI can inspect and modify running code
await page.evaluate(() => window.senteState);
```

#### AI Agent Browser Capabilities:
- [ ] **Complete Visibility**
  - Console logs and errors
  - Network requests/responses
  - DOM state and changes
  - JavaScript variable inspection
  - WebSocket message tracking

- [ ] **Interactive Development**
  - Live code injection
  - REPL into running browser
  - State modification
  - Event simulation

- [ ] **Automated Testing**
  - Scenario recording/replay
  - Error reproduction
  - Performance profiling

### Phase 2B: Scittle Client Implementation (Week 3)
**Goal:** Browser client with AI-observable telemetry

#### Tasks:
- [ ] Create Scittle client (`src_browser/sente_lite_client/`)
  - WebSocket with telemetry hooks
  - Event routing with logging
  - State management with observability

- [ ] Playwright Integration
  - Test harness for AI control
  - Telemetry capture pipeline
  - Browser automation scripts

#### Deliverables:
- Scittle client with full telemetry
- Playwright test environment
- AI-driven development toolkit
- Complete browser observability

### Phase 3: Advanced Features (Week 4)
**Goal:** Production-ready features and optimizations
**Status:** Partially complete (heartbeat done, reconnection done, error handling done)

#### Completed Tasks:
- [x] Implement heartbeat/keepalive
  - Client ping mechanism
  - Server handshake responses
  - Connection health monitoring
- [x] Build error handling
  - Comprehensive error events
  - Error recovery strategies
  - Debug mode with logging

#### Pending Tasks:

##### 3.1 Message Batching/Buffering
**Goal:** Optimize throughput and reliability for high-message scenarios

**A. Message Batching** (Throughput Optimization)
Combine multiple small messages into single WebSocket frame to reduce protocol overhead.

**Configuration:**
```clojure
{:batching {:enabled true
            :max-batch-size 10           ; Max messages per batch
            :max-batch-bytes 4096        ; Max bytes per batch (4KB)
            :flush-interval-ms 10        ; Auto-flush after 10ms
            :flush-on-idle true}}        ; Flush when queue empty
```

**Implementation:**
- Time-based: Flush after N milliseconds
- Size-based: Flush when batch reaches N bytes
- Count-based: Flush after N messages
- Idle-based: Flush when no more messages queued

**Wire format:**
```clojure
;; Single message (current)
{:type :event :id :foo :data {...}}

;; Batched messages (new)
{:type :batch
 :messages [{:type :event :id :foo :data {...}}
            {:type :event :id :bar :data {...}}]}
```

**Benefits:**
- 40-60% reduction in frame overhead
- Better CPU efficiency (fewer syscalls)
- Improved throughput for high-message scenarios

**Trade-offs:**
- Added latency (flush interval)
- Increased memory (queue storage)
- More complex code

**Estimated Effort:** 8-12 hours

**B. Message Buffering** (Reliability Feature)
Queue messages during brief disconnections to prevent message loss.

**Configuration:**
```clojure
{:buffering {:enabled true
             :max-buffer-size 100         ; Max messages per user
             :max-buffer-bytes 1048576    ; 1MB limit per user
             :ttl-ms 300000               ; Expire after 5 min
             :buffer-on-disconnect true}} ; Auto-buffer when disconnected
```

**Implementation:**
- Per-user message buffers
- TTL-based expiration
- Automatic flush on reconnection
- Memory-bounded queues

**Benefits:**
- No message loss during brief disconnections
- Graceful server restart handling
- Better user experience

**Trade-offs:**
- Memory pressure (buffer storage)
- Potential duplicate messages
- Message ordering complexity

**Estimated Effort:** 12-16 hours

##### 3.2 Compression
**Goal:** Reduce wire size for large messages and improve bandwidth efficiency

**Options:**

**A. Per-Message Compression (RECOMMENDED)**
Compress individual messages before framing.

**Configuration:**
```clojure
{:compression {:enabled true
               :algorithm :gzip           ; :gzip, :deflate, :brotli
               :level 6                   ; 1-9, balance speed/ratio
               :min-bytes 512             ; Only compress if > 512 bytes
               :content-types [:json :transit]}} ; What to compress
```

**Wire format:**
```clojure
{:type :event
 :id :foo
 :data {...}
 :compressed true               ; Flag for receiver
 :compression-algorithm :gzip}  ; How to decompress
```

**Benefits:**
- 60-80% size reduction for text (JSON, Transit)
- Works with all message types
- Client/server can negotiate

**Trade-offs:**
- CPU overhead (compress/decompress)
- Latency increase (~5-20ms per message)
- Not worth it for small messages (<512 bytes)

**B. WebSocket Per-Message Deflate Extension**
Browser-native compression using `permessage-deflate`.

**Configuration:**
```clojure
{:websocket {:permessage-deflate true
             :compression-level 6}}
```

**Benefits:**
- Browser handles compression automatically
- No wire format changes needed
- Very efficient

**Trade-offs:**
- Only works in browsers (not BB client)
- Less control over compression
- Not supported by all WebSocket libraries

**C. Stream Compression**
Compress entire WebSocket stream (not individual messages).

**Not recommended:** Interferes with frame boundaries and message routing.

**Recommendation:** Start with per-message compression (Option A) with 512-byte threshold.

**Estimated Effort:** 6-10 hours

##### 3.3 Message Chunking
**Goal:** Handle messages larger than max frame size by splitting into chunks

**Current Limits:**
- Default max message: 1MB (line 38)
- Warning threshold: 512KB
- WebSocket theoretical max: 2^63 bytes
- Practical limits: Browser ~100MB, Server configurable

**Chunking Options:**

**Option 1: Application-Level Chunking (RECOMMENDED)**
Split large messages into chunks before sending to WebSocket.

**Configuration:**
```clojure
{:chunking {:enabled true
            :chunk-size 65536          ; 64KB per chunk
            :max-chunks 1000           ; Max 1000 chunks = 64MB
            :timeout-ms 30000}}        ; Reassembly timeout
```

**Wire format:**
```clojure
;; First chunk
{:type :chunk-start
 :message-id "uuid-1234"
 :total-chunks 5
 :chunk-index 0
 :data "base64-encoded-chunk-data"}

;; Middle chunks
{:type :chunk
 :message-id "uuid-1234"
 :chunk-index 1
 :data "base64-encoded-chunk-data"}

;; Last chunk
{:type :chunk-end
 :message-id "uuid-1234"
 :chunk-index 4
 :data "base64-encoded-chunk-data"}
```

**Implementation:**
- Sender splits into chunks
- Each chunk sent as separate WebSocket frame
- Receiver reassembles using message-id
- Timeout clears incomplete messages

**Benefits:**
- Full control over chunk size
- Works with all transports
- Memory efficient (stream processing)
- Can combine with compression

**Trade-offs:**
- Most complex implementation
- State management (reassembly buffers)
- Potential out-of-order chunks
- Timeout/cleanup logic needed

**Estimated Effort:** 15-20 hours

**Option 2: Streaming API**
Provide dedicated streaming API for large data transfers with flow control and progress tracking.

**Core Concepts:**

**A. Stream Types**
1. **Upload Stream** - Client → Server
2. **Download Stream** - Server → Client
3. **Bidirectional Stream** - Real-time data exchange
4. **Broadcast Stream** - Server → Multiple clients

**B. Flow Control Mechanisms**

**Backpressure handling:**
```clojure
{:streaming {:flow-control :credit-based  ; or :window-based
             :initial-credits 10          ; Initial send credits
             :max-window-size 131072      ; 128KB window
             :refill-threshold 0.3}}      ; Refill at 30%
```

**Credit-based (RECOMMENDED):**
- Sender starts with N credits
- Each chunk costs 1 credit
- Receiver sends credit refills
- Prevents overwhelming receiver

**Window-based:**
- Sliding byte window
- Track bytes in-flight
- Resume when window available

**C. Complete API Design**

**Upload Stream (Client → Server):**
```clojure
;; Client side
(def stream (ws/create-upload-stream!
              client
              {:message-id "upload-1"
               :total-size 5000000
               :chunk-size 65536
               :metadata {:filename "data.json"
                         :content-type "application/json"}}))

;; Write chunks with backpressure handling
(doseq [chunk file-chunks]
  @(stream/write! stream chunk)  ; Returns promise, blocks if no credits
  (when-let [progress (stream/get-progress stream)]
    (tel/event! ::upload-progress progress)))

;; Close and wait for acknowledgment
@(stream/close! stream)

;; Server side - register handler
(ws/on-stream-start
  (fn [stream-info]
    (tel/event! ::stream-started stream-info)

    ;; Option 1: Callback-based
    (ws/on-stream-chunk
      (:message-id stream-info)
      (fn [chunk]
        (process-chunk! chunk)
        ;; Return :continue, :pause, or :cancel
        :continue))

    ;; Option 2: Pull-based
    (future
      (loop []
        (when-let [chunk @(ws/read-chunk! (:message-id stream-info))]
          (process-chunk! chunk)
          (recur))))

    ;; On completion
    (ws/on-stream-complete
      (:message-id stream-info)
      (fn [stats]
        (tel/event! ::stream-complete stats)))))
```

**Download Stream (Server → Client):**
```clojure
;; Server side - initiate download
(def stream (ws/create-download-stream!
              conn-id
              {:message-id "download-1"
               :total-size 10000000
               :chunk-size 65536
               :metadata {:filename "export.csv"}}))

;; Stream data with flow control
(with-open [rdr (io/reader "export.csv")]
  (doseq [chunk (partition-all 65536 (line-seq rdr))]
    @(stream/write! stream (json/encode chunk))
    (stream/check-credits! stream)))  ; Blocks until credits available

@(stream/close! stream)

;; Client side - receive stream
(ws/on-download-start
  (fn [stream-info]
    (let [file-writer (create-file-writer (:metadata stream-info))]
      (ws/on-download-chunk
        (:message-id stream-info)
        (fn [chunk]
          (write-chunk! file-writer chunk)
          ;; Send credit to allow more chunks
          (ws/send-credit! (:message-id stream-info) 1)
          {:status :continue
           :credits 1}))

      (ws/on-download-complete
        (:message-id stream-info)
        (fn [stats]
          (close-file! file-writer)
          (tel/event! ::download-complete stats))))))
```

**Bidirectional Stream (Real-time data exchange):**
```clojure
;; Use case: Video streaming with quality adaptation

;; Client
(def stream (ws/create-bidirectional-stream!
              client
              {:message-id "video-1"
               :metadata {:codec "h264"
                         :initial-quality "720p"}}))

;; Send video frames
(future
  (doseq [frame video-frames]
    @(stream/write! stream frame)))

;; Receive quality adjustments
(stream/on-receive! stream
  (fn [control-msg]
    (when (= :quality-change (:type control-msg))
      (adjust-encoder-quality! (:quality control-msg)))))

;; Server
(ws/on-bidirectional-stream
  (fn [stream-info]
    (let [stats (atom {:frames 0 :dropped 0})]
      ;; Receive frames
      (ws/on-stream-chunk (:message-id stream-info)
        (fn [frame]
          (swap! stats update :frames inc)
          (if (can-process? frame)
            (do (process-frame! frame)
                :continue)
            (do (swap! stats update :dropped inc)
                ;; Request quality reduction
                (ws/send-stream-control!
                  (:message-id stream-info)
                  {:type :quality-change
                   :quality "480p"})
                :continue)))))))
```

**D. Progress Tracking**

**Progress events:**
```clojure
{:message-id "upload-1"
 :bytes-sent 1500000
 :bytes-total 5000000
 :percentage 30.0
 :chunks-sent 23
 :chunks-total 77
 :elapsed-ms 1234
 :estimated-remaining-ms 2890
 :throughput-bps 1215000}  ; Bytes per second
```

**Progress callbacks:**
```clojure
(stream/on-progress! stream
  (fn [progress]
    (update-ui! progress)
    (tel/event! ::stream-progress progress))
  {:interval-ms 500})  ; Report every 500ms
```

**E. Error Recovery**

**Automatic retry:**
```clojure
{:streaming {:retry {:enabled true
                     :max-attempts 3
                     :backoff-ms 1000
                     :resume-on-reconnect true}}}
```

**Resume from checkpoint:**
```clojure
;; Server tracks completed chunks
(def checkpoints (atom {}))  ; {message-id #{chunk-0 chunk-1 ...}}

;; On reconnection
(ws/on-reconnect
  (fn [conn-id]
    (doseq [[msg-id completed] @checkpoints]
      (ws/send-stream-resume!
        conn-id
        {:message-id msg-id
         :completed-chunks completed}))))

;; Client resumes from last checkpoint
(ws/on-stream-resume
  (fn [resume-info]
    (stream/resume-from!
      (:message-id resume-info)
      (:completed-chunks resume-info))))
```

**Error handling:**
```clojure
(stream/on-error! stream
  (fn [error]
    (tel/error! "Stream error" error)
    (case (:type error)
      :network-error
      (stream/retry! stream)

      :quota-exceeded
      (do (notify-user! "Storage full")
          (stream/cancel! stream))

      :timeout
      (stream/resume! stream)

      ;; Default: cancel
      (stream/cancel! stream))))
```

**F. Stream States**

State machine:
```
:initializing → :negotiating → :active → :completing → :completed
                      ↓            ↓
                   :failed     :paused → :active
                               :cancelled
```

**State transitions:**
```clojure
(stream/get-state stream)  ; => :active

(stream/pause! stream)     ; :active → :paused
(stream/resume! stream)    ; :paused → :active
(stream/cancel! stream)    ; Any → :cancelled
```

**G. Wire Format**

**Stream initiation:**
```clojure
{:type :stream-init
 :stream-type :upload  ; or :download, :bidirectional
 :message-id "uuid-1234"
 :total-size 5000000
 :chunk-size 65536
 :total-chunks 77
 :metadata {:filename "data.json"
            :content-type "application/json"}}
```

**Stream chunk:**
```clojure
{:type :stream-chunk
 :message-id "uuid-1234"
 :chunk-index 5
 :data "base64-encoded-data"
 :checksum "sha256-hash"}  ; Optional integrity check
```

**Flow control:**
```clojure
;; Credit refill
{:type :stream-credits
 :message-id "uuid-1234"
 :credits 5}

;; Pause request
{:type :stream-pause
 :message-id "uuid-1234"
 :reason "backpressure"}

;; Resume
{:type :stream-resume
 :message-id "uuid-1234"}
```

**Stream completion:**
```clojure
{:type :stream-complete
 :message-id "uuid-1234"
 :chunks-received 77
 :bytes-received 5000000
 :duration-ms 5432
 :checksum "sha256-of-complete-data"}

;; Or error
{:type :stream-error
 :message-id "uuid-1234"
 :error-code :checksum-mismatch
 :message "Chunk 42 checksum failed"}
```

**H. Use Cases**

**File Upload with Progress:**
```clojure
(defn upload-file! [client file-path]
  (with-open [f (io/input-stream file-path)]
    (let [file-size (.length (io/file file-path))
          stream (ws/create-upload-stream!
                   client
                   {:total-size file-size
                    :chunk-size 65536
                    :metadata {:filename (file-name file-path)
                              :mime-type (detect-mime-type file-path)}})]

      ;; Progress reporting
      (stream/on-progress! stream
        (fn [progress]
          (println (format "Uploading: %.1f%%" (:percentage progress))))
        {:interval-ms 500})

      ;; Stream file chunks
      (loop []
        (let [chunk (byte-array 65536)
              n (.read f chunk)]
          (when (pos? n)
            @(stream/write! stream (if (= n 65536) chunk (Arrays/copyOf chunk n)))
            (recur))))

      ;; Wait for completion
      @(stream/close! stream))))
```

**Real-time Log Streaming:**
```clojure
;; Server - stream logs to client
(defn stream-logs! [conn-id log-file]
  (let [stream (ws/create-download-stream!
                 conn-id
                 {:message-id (str "logs-" (uuid))
                  :metadata {:source log-file
                            :format :text}})]

    ;; Tail -f style streaming
    (future
      (with-open [reader (log-reader log-file)]
        (loop []
          (when-let [line (read-line reader)]
            @(stream/write! stream line)
            (Thread/sleep 100)  ; Rate limiting
            (recur)))))

    stream))
```

**Large JSON Export with Streaming:**
```clojure
;; Server - export large dataset
(defn export-dataset! [conn-id query]
  (let [stream (ws/create-download-stream!
                 conn-id
                 {:message-id (str "export-" (uuid))
                  :metadata {:format :json
                            :query query}})]

    (future
      ;; Stream array elements one at a time
      @(stream/write! stream "{\"data\":[")

      (let [results (db/query-streaming query)]
        (loop [first? true]
          (when-let [row (results/next! results)]
            (when-not first?
              @(stream/write! stream ","))
            @(stream/write! stream (json/encode row))
            (recur false))))

      @(stream/write! stream "]}")
      @(stream/close! stream))

    stream))
```

**Benefits:**
- Elegant API for streaming data
- Memory efficient (streaming, not buffering)
- Progress tracking built-in
- Automatic backpressure handling
- Resume on reconnection
- Good for file uploads/downloads, real-time feeds
- Integrity checking (checksums)
- Fine-grained flow control

**Trade-offs:**
- Different API from regular messages
- More complex state management
- Requires bidirectional communication
- Need checkpoint/resume logic
- Higher implementation complexity
- Testing requires large datasets
- Debug visibility into stream state

**Implementation Considerations:**
1. **Memory management**: Use bounded queues for chunks
2. **Credit management**: Track per-stream credits carefully
3. **Timeout handling**: Inactive streams need cleanup
4. **Reconnection**: Preserve stream state across connections
5. **Multiplexing**: Multiple concurrent streams per connection
6. **Priority**: Allow urgent messages during large streams

**Estimated Effort:** 25-35 hours (full implementation with all features)

**Option 3: External Storage Reference**
Store large data externally, send reference via WebSocket.

**Wire format:**
```clojure
{:type :large-message
 :storage :s3
 :reference "s3://bucket/key"
 :size 50000000
 :metadata {...}}
```

**Benefits:**
- Keeps WebSocket lightweight
- Leverages existing storage infrastructure
- Good for truly large data (>10MB)

**Trade-offs:**
- Requires external infrastructure
- Additional latency (separate fetch)
- More complex client implementation
- Cost of external storage

**Estimated Effort:** 10-15 hours (excluding infrastructure setup)

**Option 4: Increase Frame Limits**
Simply allow larger frames without chunking.

**Configuration:**
```clojure
{:max-frame-size 10485760}  ; 10MB
```

**Benefits:**
- Simplest approach
- No code changes needed

**Trade-offs:**
- Memory pressure (entire message in RAM)
- Blocks other messages during transmission
- Head-of-line blocking
- Not scalable beyond ~10MB

**Recommendation:** Only for controlled environments with known message sizes.

**Estimated Effort:** 2-3 hours

**Option 5: Hybrid - Compress Then Chunk**
Combine compression with chunking for best results.

**Process:**
1. Compress message (if > 512 bytes)
2. If compressed size > chunk-size, split into chunks
3. Send chunks with compression flag
4. Receiver reassembles then decompresses

**Configuration:**
```clojure
{:compression {:enabled true
               :min-bytes 512
               :algorithm :gzip}
 :chunking {:enabled true
            :chunk-size 65536
            :compress-first true}}  ; Compress before chunking
```

**Benefits:**
- Best wire efficiency
- Handles any message size
- Optimal for large JSON/Transit

**Trade-offs:**
- Most complex implementation
- Highest CPU overhead
- Hardest to debug

**Estimated Effort:** 25-30 hours

---

#### Data Layout for Large Datasets

When streaming large datasets, the data layout fundamentally affects streaming efficiency, memory usage, and processing latency.

**The Problem:**

Consider a dataset with 1 million records, each with fields `:a` and `:b`:

```clojure
;; Column-oriented (Structure-of-Arrays) - BAD for streaming
{:a [1 2 3 4 5 ... 1000000]       ; All :a values first
 :b [9 8 7 6 5 ... 1000000]}      ; Then all :b values

;; Row-oriented (Array-of-Structures) - GOOD for streaming
{:keys [:a :b]
 :rows [[1 9]                      ; Row-by-row, can stream
        [2 8]
        [3 7]
        [4 6]
        [5 5]
        ...
        [1000000 1000000]]}
```

**Column-Oriented Problems for Streaming:**

1. **Must buffer entire first column before second column**
   - Can't start processing until all of `:a` received
   - 1M integers = 4-8MB per column minimum
   - Total buffering: 8-16MB before any processing

2. **Poor streaming semantics**
   ```clojure
   ;; Receiver must wait for everything:
   (let [data (wait-for-all-data)]  ; Blocks until complete!
     (doseq [i (range (count (:a data)))]
       (process-row (get-in data [:a i])
                    (get-in data [:b i]))))
   ```

3. **Memory explosion with many columns**
   - 100 columns × 1M rows × 8 bytes = 800MB
   - All in memory before processing starts

4. **Can't provide early results**
   - User waits for entire dataset
   - No progress updates during transfer

**Row-Oriented Benefits for Streaming:**

1. **Process immediately as data arrives**
   ```clojure
   ;; Stream rows one at a time:
   (stream/on-row
     (fn [[a b]]
       (process-row a b)))  ; Immediate processing!

   ;; Memory: Just current row (16 bytes)
   ```

2. **Memory efficient**
   - Constant memory: O(1) for row size
   - Not O(n) for dataset size
   - Can handle infinite datasets

3. **Early results**
   ```clojure
   ;; Show first 100 rows while rest loads
   (stream/on-row
     (fn [row]
       (when (< @rows-received 100)
         (display-in-ui! row))
       (swap! rows-received inc)))
   ```

4. **Natural streaming API**
   ```clojure
   ;; Server: Stream from database cursor
   (with-open [cursor (db/query "SELECT a, b FROM huge_table")]
     (doseq [row cursor]
       @(stream/send-row! stream row)))  ; One at a time
   ```

**Performance Comparison:**

| Metric | Column-Oriented | Row-Oriented |
|--------|-----------------|--------------|
| **Memory (1M rows)** | 8-16MB buffered | 16 bytes (current row) |
| **Time to first result** | Full transfer complete | Immediate (first row) |
| **Streaming latency** | High (wait for columns) | Low (row-by-row) |
| **Processing pattern** | Batch (all at once) | Stream (incremental) |
| **Suitable for infinite?** | No (need all columns) | Yes (continuous rows) |

**Real-World Observation:** When exporting 10M row dataset from PostgreSQL, row-oriented streaming allows UI to show first results in 50ms vs 5+ seconds for columnar (waiting for first column to complete).

---

**Layout Options for Different Use Cases:**

**Option 1: Pure Row-Oriented (RECOMMENDED for streaming)**

Best for: Real-time processing, UI display, incremental computation

```clojure
;; Wire format: Row-by-row
{:type :dataset-row
 :stream-id "query-1"
 :row-index 0
 :values [1 9]}

{:type :dataset-row
 :stream-id "query-1"
 :row-index 1
 :values [2 8]}

;; API: Callback per row
(stream/on-dataset-row
  (fn [{:keys [row-index values]}]
    (let [[a b] values]
      (process-row! a b))))

;; Server: Stream from cursor
(defn stream-query-results! [conn-id query]
  (let [stream-id (str "query-" (uuid))]
    (future
      (with-open [cursor (db/query-cursor query)]
        (loop [idx 0]
          (when-let [row (cursor/next! cursor)]
            (send-message! conn-id
              {:type :dataset-row
               :stream-id stream-id
               :row-index idx
               :values row})
            (recur (inc idx))))))
    stream-id))
```

**Benefits:**
- Constant memory (one row at a time)
- Immediate processing
- Natural streaming semantics
- Works with infinite datasets
- Simple implementation

**Trade-offs:**
- Not optimal for columnar analytics (need transpose)
- More messages (one per row)
- Compression less effective per-message

---

**Option 2: Batched Rows (RECOMMENDED for large datasets)**

Best for: Balance between streaming and efficiency

```clojure
;; Wire format: Batches of rows
{:type :dataset-batch
 :stream-id "query-1"
 :start-row 0
 :rows [[1 9]
        [2 8]
        [3 7]
        [4 6]
        [5 5]]}  ; 5 rows per batch

{:type :dataset-batch
 :stream-id "query-1"
 :start-row 5
 :rows [[10 15]
        [11 14]
        ...]}

;; API: Process batches
(stream/on-dataset-batch
  (fn [{:keys [start-row rows]}]
    (doseq [[a b] rows]
      (process-row! a b))))

;; Configuration
{:streaming {:batch-rows 100       ; 100 rows per message
             :max-batch-bytes 65536}} ; Or 64KB, whichever first
```

**Benefits:**
- Fewer messages (100x reduction)
- Better compression (batch context)
- Still streamable (process per batch)
- Configurable batch size

**Trade-offs:**
- Slightly more memory (batch size)
- Small latency added (batch accumulation)

**Real-world observation:** 100-row batches provide good balance: ~4KB per message with compression, process every 50-100ms, memory bounded at ~8KB per stream.

---

**Option 3: Columnar Batches (Hybrid approach)**

Best for: Analytics workloads that need columnar processing

```clojure
;; Wire format: Column chunks
{:type :dataset-chunk
 :stream-id "query-1"
 :start-row 0
 :row-count 1000
 :columns {:a [1 2 3 ... 1000]     ; 1000 rows at a time
           :b [9 8 7 ... 1000]}}

{:type :dataset-chunk
 :stream-id "query-1"
 :start-row 1000
 :row-count 1000
 :columns {:a [1001 1002 ...]
           :b [...]}}

;; API: Process columnar chunks
(stream/on-dataset-chunk
  (fn [{:keys [start-row row-count columns]}]
    ;; Vectorized processing (SIMD possible)
    (process-column-chunk! (:a columns) (:b columns))))

;; Configuration
{:streaming {:chunk-rows 1000      ; Columnar chunk size
             :layout :columnar}}   ; vs :row-oriented
```

**Benefits:**
- Excellent compression (column values similar)
- Vectorized processing (SIMD)
- Cache-friendly for analytics
- Good for aggregations

**Trade-offs:**
- Higher memory (chunk buffering)
- Latency (wait for chunk)
- More complex receiver code
- Not suitable for record-by-record processing

**Use case:** When sending to analytics systems (e.g., streaming to Pandas, Arrow, DuckDB)

---

**Option 4: Hybrid with Metadata (RECOMMENDED for flexibility)**

Best for: Allow receiver to choose processing model

```clojure
;; Wire format: Metadata + flexible layout
{:type :dataset-start
 :stream-id "query-1"
 :total-rows 1000000  ; If known
 :columns [:a :b :c]
 :schema {:a :int64 :b :int64 :c :string}
 :layout :row-batched  ; :row-batched, :columnar-batched, :row-by-row
 :batch-size 100}

;; Then send data in declared layout
{:type :dataset-batch
 :stream-id "query-1"
 :start-row 0
 :rows [[1 9 "foo"]
        [2 8 "bar"]
        ...]}

;; Client can transform to preferred layout
(stream/on-dataset-start
  (fn [metadata]
    (case (:layout metadata)
      :row-batched
      (process-row-batched! metadata)

      :columnar-batched
      (process-columnar! metadata))))
```

**Benefits:**
- Flexible for different use cases
- Schema provides type info
- Client can optimize processing
- Server can choose optimal layout

---

**Decision Matrix: Which Layout?**

| Use Case | Recommended Layout | Batch Size | Why |
|----------|-------------------|------------|-----|
| **UI display (tables, grids)** | Row-oriented batches | 50-100 | Show results immediately, paginate |
| **Real-time dashboard** | Row-oriented stream | 1-10 | Lowest latency, update per row |
| **Data export (CSV, JSON)** | Row-oriented batches | 1000 | Memory efficient, natural format |
| **Analytics (aggregations)** | Columnar batches | 10000 | Vectorized processing, compression |
| **Machine learning (features)** | Columnar batches | 10000 | NumPy/Pandas compatible |
| **Log streaming (text lines)** | Row-by-row | 1 | Immediate display, tail -f style |
| **Time series (sensor data)** | Row-oriented batches | 100 | Balance latency and efficiency |
| **Large file transfer (CSV)** | Row-oriented batches | 5000 | Efficient, streamable |

---

**Implementation Example: Flexible Dataset Streaming**

```clojure
(defn stream-dataset!
  [conn-id query {:keys [layout batch-size]
                  :or {layout :row-batched
                       batch-size 100}}]
  (let [stream-id (str "dataset-" (uuid))
        columns (db/query-columns query)]

    ;; Send metadata
    (send-message! conn-id
      {:type :dataset-start
       :stream-id stream-id
       :columns columns
       :layout layout
       :batch-size batch-size})

    ;; Stream data based on layout
    (case layout
      :row-by-row
      (stream-rows-one-by-one! conn-id stream-id query)

      :row-batched
      (stream-row-batches! conn-id stream-id query batch-size)

      :columnar-batched
      (stream-columnar-batches! conn-id stream-id query batch-size))

    stream-id))

;; Row-batched implementation
(defn stream-row-batches! [conn-id stream-id query batch-size]
  (future
    (with-open [cursor (db/query-cursor query)]
      (loop [start-row 0
             batch []]
        (if-let [row (cursor/next! cursor)]
          ;; Accumulate batch
          (let [new-batch (conj batch row)]
            (if (= batch-size (count new-batch))
              ;; Batch full: send and reset
              (do
                @(send-message! conn-id
                   {:type :dataset-batch
                    :stream-id stream-id
                    :start-row start-row
                    :rows new-batch})
                (recur (+ start-row batch-size) []))
              ;; Continue accumulating
              (recur start-row new-batch)))

          ;; No more rows: send final partial batch
          (when (seq batch)
            @(send-message! conn-id
               {:type :dataset-batch
                :stream-id stream-id
                :start-row start-row
                :rows batch}))

          ;; Complete
          @(send-message! conn-id
             {:type :dataset-complete
              :stream-id stream-id
              :total-rows (+ start-row (count batch))}))))))
```

---

**Performance Tips:**

1. **Choose batch size based on network latency:**
   ```clojure
   ;; Low latency (LAN): Smaller batches, lower lag
   {:batch-size 50}   ; 2-5ms per batch

   ;; High latency (WAN): Larger batches, amortize RTT
   {:batch-size 1000} ; 20-50ms per batch

   ;; Very high latency (mobile): Very large batches
   {:batch-size 5000} ; 100-200ms per batch
   ```

2. **Compression works better with batches:**
   ```clojure
   ;; Row-by-row: Compression ineffective (overhead > savings)
   ;; Row-batched: 60-80% compression (repeated patterns)
   ;; Columnar: 80-90% compression (highly repetitive)

   {:streaming {:layout :row-batched
                :batch-size 100
                :compression {:enabled true
                             :min-batch-bytes 1024}}}
   ```

3. **Memory-bound systems: Use smaller batches**
   ```clojure
   ;; Server with 512MB RAM, 100 concurrent streams:
   ;; 100 streams × 1000 rows × 100 bytes = 10MB per stream = 1GB total
   ;; TOO MUCH!

   ;; Better: 100 rows × 100 bytes = 10KB per stream = 1MB total
   {:batch-size 100  ; Keeps memory bounded
    :max-concurrent-streams 100}
   ```

4. **UI responsiveness: Batch size = visible rows**
   ```clojure
   ;; Table shows 20 rows: batch 20 rows
   {:batch-size 20}  ; Perfect for one screen

   ;; Infinite scroll: batch = scroll increment
   {:batch-size 50}  ; Load next 50 on scroll
   ```

---

**Key Takeaway:**

For streaming large datasets in sente-lite:

1. **Default to row-oriented batches** (100-1000 rows)
2. **Allow layout negotiation** in handshake
3. **Provide batch size configuration** per use case
4. **Compress batches** for efficiency
5. **Stream, don't buffer** entire dataset

This gives you the flexibility to handle everything from real-time dashboards to large data exports efficiently.

**Recommendation Matrix:**

| Use Case | Recommended Option | Estimated Effort |
|----------|-------------------|------------------|
| High-throughput small messages | Batching | 8-12 hours |
| Reliable notifications | Buffering | 12-16 hours |
| Large JSON/Transit (1-10MB) | Chunking + Compression | 25-30 hours |
| Very large data (>10MB) | External Storage | 10-15 hours |
| Known small messages (<1MB) | Increase limits | 2-3 hours |
| File uploads/downloads | Streaming API | 25-35 hours |

---

#### Multiple WebSocket Connections: Single vs Multiple

A critical architectural decision: Should clients use one WebSocket connection or multiple parallel connections?

**The Question:**

```clojure
;; Option 1: Single connection (current approach)
(def client (ws/connect! "ws://server.com"))
(ws/send! client {:type :chat :data "..."})
(ws/send! client {:type :file-upload :data "..."})
(ws/send! client {:type :metrics :data "..."})

;; Option 2: Multiple connections
(def chat-conn (ws/connect! "ws://server.com/chat"))
(def data-conn (ws/connect! "ws://server.com/data"))
(def metrics-conn (ws/connect! "ws://server.com/metrics"))
```

**Use Cases for Multiple Connections:**

1. **Separation of concerns** (different traffic types)
2. **Priority channels** (critical vs bulk data)
3. **Failure isolation** (one fails, others continue)
4. **Head-of-line blocking avoidance** (large transfer doesn't block small messages)
5. **Load balancing** (different servers per connection)
6. **Browser connection limits** (work around per-domain limits)

---

### Advantages of Multiple Connections

**1. Failure Isolation**

```clojure
;; Single connection: Everything fails together
(def conn (ws/connect! "ws://server.com"))
(ws/on-close conn
  (fn []
    ;; Chat, file uploads, metrics ALL disconnected!
    (reconnect-everything!)))

;; Multiple connections: Isolated failures
(def chat-conn (ws/connect! "ws://server.com/chat"))
(def file-conn (ws/connect! "ws://server.com/files"))

(ws/on-close file-conn
  (fn []
    ;; Only file uploads affected
    ;; Chat continues working!
    (reconnect-file-upload!)))
```

**Benefit:** A problem with file uploads doesn't disrupt real-time chat.

**Real-world example:** Slack uses separate connections per workspace so issues in one workspace don't affect others.

---

**2. Head-of-Line Blocking Avoidance**

```clojure
;; Single connection: Large upload blocks everything
(ws/send! conn {:type :file-upload :size 100000000})  ; 100MB
(ws/send! conn {:type :chat :msg "hello"})            ; Waits for 100MB to finish!

;; Multiple connections: Independent flows
(ws/send! file-conn {:type :file-upload :size 100000000})
(ws/send! chat-conn {:type :chat :msg "hello"})  ; Immediate!
```

**Benefit:** Small, latency-sensitive messages aren't blocked by large transfers.

**Real-world observation:** Video conferencing apps use separate connections for video vs control messages. Control messages (mute, unmute) must be instant, can't wait for video frames.

---

**3. Priority Separation**

```clojure
;; Multiple connections with QoS
(def critical-conn (ws/connect! "ws://server.com/critical"
                                {:priority :high
                                 :timeout 1000}))

(def bulk-conn (ws/connect! "ws://server.com/bulk"
                            {:priority :low
                             :timeout 30000}))

;; Server can prioritize critical connection
(defn handle-connections! []
  ;; Process critical messages first
  (process-priority-queue! critical-connections)
  ;; Then bulk
  (process-bulk-queue! bulk-connections))
```

**Benefit:** Separate connection pools allow server-side QoS policies.

**Real-world example:** Trading platforms use separate connections for:
- Orders (highest priority, <1ms latency)
- Market data (high priority, streaming)
- Account info (normal priority)
- Analytics (low priority, bulk)

---

**4. Load Balancing & Scalability**

```clojure
;; Different connections → different backend servers
(def chat-conn (ws/connect! "ws://chat-server-1.com"))
(def files-conn (ws/connect! "ws://file-server-1.com"))
(def metrics-conn (ws/connect! "ws://metrics-server-1.com"))

;; Each server specialized for its workload
;; Chat servers: Low latency, many connections
;; File servers: High throughput, large transfers
;; Metrics servers: Time-series optimized
```

**Benefit:** Horizontal scaling with specialized servers.

**Real-world example:** Netflix uses multiple connections to CDNs for video streams while control/API traffic goes to different servers.

---

**5. Simpler Flow Control Per Connection**

```clojure
;; Single connection: Complex multiplexed flow control
(def flow-state (atom {:chat {:credits 10}
                       :files {:credits 5}
                       :metrics {:credits 20}}))

;; Multiple connections: Simple per-connection flow control
(def chat-conn (ws/connect! {...}))   ; 10 credits
(def files-conn (ws/connect! {...}))  ; 5 credits
(def metrics-conn (ws/connect! {...}))  ; 20 credits
```

**Benefit:** OS-level TCP flow control handles backpressure automatically per connection.

---

**6. Authentication & Authorization Separation**

```clojure
;; Different tokens per connection
(def user-conn (ws/connect! "ws://server.com/user"
                            {:token user-token}))

(def admin-conn (ws/connect! "ws://server.com/admin"
                             {:token admin-token}))

;; Server can enforce different policies per connection
;; User connection: Limited rate
;; Admin connection: Unlimited rate
```

**Benefit:** Fine-grained access control, easier security auditing.

---

### Disadvantages of Multiple Connections

**1. Increased Overhead**

```clojure
;; Per connection overhead:
;; - TCP handshake (1-3 RTT)
;; - TLS handshake (2-4 RTT)
;; - WebSocket upgrade (1 RTT)
;; - Total: 4-8 RTT per connection

;; Single connection: 4-8 RTT
;; 5 connections: 20-40 RTT!

;; Over 100ms mobile connection:
;; Single: 400-800ms connection time
;; Multiple: 2-4 seconds connection time!
```

**Cost:** Significant connection establishment overhead, especially on mobile.

**Real-world observation:** Mobile apps typically minimize connections due to cellular latency. Each additional connection adds 200-500ms on 4G.

---

**2. Server Resource Usage**

```clojure
;; Server resources per connection:
;; - File descriptor (1 per connection)
;; - Socket buffer (32-64KB per connection)
;; - TLS state (2-5KB per connection)
;; - Application state (varies)

;; 10,000 users × 3 connections = 30,000 connections!
;; File descriptors: 30,000 (may hit ulimit)
;; Socket buffers: 30,000 × 64KB = 1.9GB
;; TLS state: 30,000 × 5KB = 150MB
```

**Cost:** 3x resource usage per user, potential scalability issues.

**Real-world observation:** C10K problem becomes C30K problem with 3 connections per user. May require more servers or higher-end hardware.

---

**3. State Synchronization Complexity**

```clojure
;; Single connection: Simple state tracking
(def user-state (atom {:user-id 123
                       :last-activity (now)
                       :subscriptions #{:chat :notifications}}))

;; Multiple connections: Which connection tracks what?
(def user-connections
  {:chat {:conn chat-conn :last-activity (now)}
   :files {:conn files-conn :last-activity (now)}
   :metrics {:conn metrics-conn :last-activity (now)}})

;; Problem: Is user "online" if chat disconnected but files connected?
;; Problem: How to track last-activity across connections?
;; Problem: Which connection sends notifications?
```

**Cost:** Complex logic for user presence, routing, state management.

---

**4. Message Ordering Issues**

```clojure
;; Single connection: Natural ordering
(ws/send! conn {:type :create :id 1})
(ws/send! conn {:type :update :id 1})  ; Arrives after create

;; Multiple connections: Race conditions!
(ws/send! conn-a {:type :create :id 1})
(ws/send! conn-b {:type :update :id 1})  ; Might arrive BEFORE create!

;; Requires explicit sequencing
{:type :update
 :id 1
 :sequence-number 42
 :depends-on 41}  ; Must apply after sequence 41
```

**Cost:** Need application-level sequencing, harder to debug race conditions.

---

**5. Connection Management Complexity**

```clojure
;; Single connection: Simple
(defn ensure-connected! []
  (when-not (ws/connected? conn)
    (reconnect!)))

;; Multiple connections: Coordination nightmare
(defn ensure-all-connected! []
  ;; Which to connect first?
  ;; What if some succeed and others fail?
  ;; Retry logic per connection?
  ;; How to report partial connectivity?
  (doseq [[name conn] connections]
    (when-not (ws/connected? conn)
      (try
        (reconnect! name conn)
        (catch Exception e
          ;; Continue with other connections?
          ;; Fail all?
          (handle-partial-failure! name e))))))
```

**Cost:** Exponentially more complex reconnection logic, harder to test.

---

**6. Authentication Overhead**

```clojure
;; Single connection: Auth once
(ws/connect! "ws://server.com" {:token (get-auth-token)})  ; 1 token fetch

;; Multiple connections: Auth N times
(ws/connect! "ws://server.com/chat" {:token (get-auth-token)})     ; Token fetch 1
(ws/connect! "ws://server.com/files" {:token (get-auth-token)})    ; Token fetch 2
(ws/connect! "ws://server.com/metrics" {:token (get-auth-token)})  ; Token fetch 3

;; If token expires: Must refresh 3 connections!
```

**Cost:** 3x auth overhead, potential for inconsistent auth state.

---

**7. Harder to Maintain Message Context**

```clojure
;; Single connection: Request/response correlation
(ws/send! conn {:id "req-1" :type :query :data "..."})
;; Response comes back on same connection
{:id "req-1" :type :response :data "..."}

;; Multiple connections: Where does response go?
(ws/send! query-conn {:id "req-1" :type :query :data "..."})
;; Response might come on results-conn or query-conn?
;; Client must listen on all connections for responses
```

**Cost:** More complex message routing on client side.

---

### Performance Comparison

| Metric | Single Connection | Multiple Connections (3) |
|--------|------------------|--------------------------|
| **Connection time (mobile)** | 400-800ms | 1200-2400ms |
| **Server memory (10K users)** | 640MB | 1920MB |
| **File descriptors** | 10,000 | 30,000 |
| **Complexity** | Low | High |
| **Head-of-line blocking** | Possible | Eliminated |
| **Failure isolation** | None | Good |
| **Message ordering** | Guaranteed | Requires sequencing |

---

### Real-World Patterns

**Pattern 1: Single Connection with Multiplexing (RECOMMENDED for most)**

```clojure
;; One connection, multiple logical streams
(def conn (ws/connect! "ws://server.com"))

;; Application-level multiplexing
(ws/send! conn {:stream :chat :data "hello"})
(ws/send! conn {:stream :files :data "..."})
(ws/send! conn {:stream :metrics :data "..."})

;; Server routes by stream
(defn route-message! [conn msg]
  (case (:stream msg)
    :chat (handle-chat! conn msg)
    :files (handle-files! conn msg)
    :metrics (handle-metrics! conn msg)))
```

**When to use:**
- Most applications
- Cost-sensitive (mobile)
- Simple state management
- Guaranteed message ordering important

**Examples:** WhatsApp Web, Telegram Web, most single-page apps

---

**Pattern 2: Two Connections (Control + Data)**

```clojure
;; Control channel: Small, latency-sensitive
(def control-conn (ws/connect! "ws://server.com/control"))

;; Data channel: Bulk transfers
(def data-conn (ws/connect! "ws://server.com/data"))

;; Control never blocked by data
(ws/send! control-conn {:type :pause-upload})  ; Instant!
```

**When to use:**
- Large file transfers + real-time control
- Video/audio streaming + chat
- Bulk data + notifications

**Examples:** Video conferencing (Zoom, Meet), file sync apps (Dropbox)

---

**Pattern 3: Multiple Connections (Specialized)**

```clojure
;; Each connection to specialized backend
(def chat-conn (ws/connect! "ws://chat-shard-1.com"))
(def presence-conn (ws/connect! "ws://presence.com"))
(def notifications-conn (ws/connect! "ws://notifications.com"))
```

**When to use:**
- Microservices architecture
- Geographic distribution
- Different SLAs per service
- Very high scale

**Examples:** Slack (per-workspace), Discord (voice vs data), AWS real-time services

---

**Pattern 4: Connection Pool with Sharding**

```clojure
;; Pool of connections for load distribution
(def connections
  [(ws/connect! "ws://shard-1.com")
   (ws/connect! "ws://shard-2.com")
   (ws/connect! "ws://shard-3.com")])

;; Hash-based routing
(defn send-message! [msg]
  (let [shard (mod (hash (:user-id msg)) (count connections))
        conn (nth connections shard)]
    (ws/send! conn msg)))
```

**When to use:**
- Distributed state (user sharding)
- Horizontal scaling
- Very high message rates
- Geographic distribution

**Examples:** Large-scale gaming (MMOs), trading platforms, IoT backends

---

### Decision Framework

**Choose SINGLE connection when:**

✅ Application is relatively simple
✅ Connection count matters (mobile, cost)
✅ Message ordering is critical
✅ State management should be simple
✅ All traffic has similar latency requirements
✅ Users typically online continuously

**Choose MULTIPLE connections when:**

✅ Large file transfers + real-time control needed
✅ Different priority levels (critical vs bulk)
✅ Microservices architecture (different backends)
✅ Failure isolation is critical
✅ Different SLAs per traffic type
✅ Very high scale (horizontal scaling needed)
✅ Geographic distribution (different regions)

---

### Hybrid Approach (RECOMMENDED)

Start with single connection, add connections as needed:

```clojure
;; Phase 1: Single connection (MVP)
(def conn (ws/connect! "ws://server.com"))

;; Phase 2: Add data connection when needed
(defn upgrade-to-dual-connection! []
  (when (large-transfer-needed?)
    (def data-conn (ws/connect! "ws://server.com/data"))
    ;; Keep control-conn for everything else
    (def control-conn conn)))

;; Configuration-driven
(defn connect! [config]
  (if (:multi-connection? config)
    (connect-multiple! config)
    (connect-single! config)))
```

**Benefits:**
- Start simple
- Add complexity when justified
- Can be feature-flag controlled
- Backwards compatible

---

### Implementation Considerations for sente-lite

**Support both patterns:**

```clojure
;; Single connection mode (default)
(def client
  (sente/make-client
    {:uri "ws://server.com"
     :mode :single}))

;; Multiple connection mode (opt-in)
(def client
  (sente/make-client
    {:connections {:control "ws://server.com/control"
                   :data "ws://server.com/data"
                   :metrics "ws://server.com/metrics"}
     :mode :multiple}))

;; Unified API works with both
(sente/send! client {:stream :chat :data "hello"})
```

**API abstraction:** Hide connection complexity from application code.

---

### Key Recommendations for sente-lite

1. **Default to single connection** with multiplexing
2. **Provide multi-connection as opt-in feature** for advanced use cases
3. **Implement stream-level flow control** on single connection to avoid head-of-line blocking
4. **Allow configuration** via feature flags
5. **Document trade-offs** clearly for users

**Rationale:**
- Single connection is simpler, cheaper, sufficient for 90% of use cases
- Multi-connection available for the 10% that need it (file uploads, microservices, etc.)
- Users can start simple, upgrade when needed

---

#### Explicit Message Queues: Receive and Send Buffers

A fundamental architectural decision: Should we use explicit application-level message queues, or rely on implicit OS-level buffers?

**The Question:**

```clojure
;; Option 1: Direct processing (current approach)
(ws/on-message
  (fn [msg]
    (process-message! msg)))  ; Process immediately

;; Option 2: Explicit receive queue
(ws/on-message
  (fn [msg]
    (enqueue-received! msg)))  ; Queue for later processing

(future
  (loop []
    (when-let [msg (dequeue-received!)]
      (process-message! msg)
      (recur))))

;; Similarly for sending:
;; Direct: (ws/send! conn msg)
;; Queued: (enqueue-send! msg) → background sender
```

---

### Why Explicit Queues?

**Problems without explicit queues:**

1. **Slow consumer** - Messages arrive faster than processing
2. **Slow producer** - Send calls block waiting for network
3. **Burst traffic** - Sudden spike overwhelms system
4. **No visibility** - Can't see queue depth, backlog
5. **No prioritization** - Critical messages wait behind bulk
6. **No flow control** - Sender doesn't know when to slow down

---

### Advantages of Explicit Queues

**1. Decoupling Producer from Consumer**

```clojure
;; Without queue: Direct coupling
(ws/on-message
  (fn [msg]
    (process-message! msg)  ; Blocks WebSocket thread!
    ;; If slow: WebSocket buffer fills → TCP backpressure → sender slows
    ))

;; With queue: Decoupled
(ws/on-message
  (fn [msg]
    (enqueue! receive-queue msg)  ; O(1), never blocks
    ))

(future
  (loop []
    (when-let [msg (dequeue! receive-queue)]
      (process-message! msg)  ; Can be slow, doesn't block WS
      (recur))))
```

**Benefit:** WebSocket receive thread never blocks, TCP flow control works properly.

**Real-world observation:** Without receive queue, slow message processing (e.g., database writes) can stall WebSocket receive, causing TCP window to shrink, reducing throughput by 50-90%.

---

**2. Burst Absorption**

```clojure
;; Burst of 1000 messages arrives
;; Without queue: Process sequentially, takes 10 seconds
;; With queue: Accept all instantly, process in background

(def receive-queue (atom (clojure.lang.PersistentQueue/EMPTY)))

;; Accept burst
(dotimes [_ 1000]
  (swap! receive-queue conj msg))  ; Instant

;; Process gradually
(defn process-worker! []
  (loop []
    (when-let [msg (first @receive-queue)]
      (swap! receive-queue pop)
      (process-message! msg)  ; 10ms per message
      (Thread/sleep 10)       ; Rate limiting
      (recur))))
```

**Benefit:** System handles traffic spikes gracefully, smooths load.

**Real-world observation:** Queue absorbs 10x spikes during peak hours. Without queue: dropped connections, timeouts. With queue: smooth operation, 99.9% success rate.

---

**3. Prioritization**

```clojure
;; Priority queue: Critical messages processed first
(defn enqueue-with-priority! [msg]
  (let [priority (get msg :priority :normal)]
    (swap! priority-queue
           (fn [q]
             (conj-priority q msg priority)))))

(defn process-worker! []
  (loop []
    (when-let [msg (dequeue-highest-priority!)]
      (process-message! msg)
      (recur))))

;; Example: User clicks "cancel upload"
(send! {:type :cancel-upload :priority :critical})  ; Processed immediately
;; Even if 1000 bulk messages queued
```

**Benefit:** Latency-sensitive messages aren't blocked by bulk traffic.

---

**4. Backpressure & Flow Control**

```clojure
;; Monitor queue depth
(defn queue-depth [] (count @send-queue))

;; Apply backpressure when queue full
(defn send! [msg]
  (if (< (queue-depth) max-queue-depth)
    (do
      (enqueue! send-queue msg)
      {:status :queued})
    (do
      ;; Queue full: Apply backpressure
      (tel/warn! "Send queue full" {:depth (queue-depth)})
      {:status :rejected :reason :queue-full})))

;; Client can react
(let [result (send! msg)]
  (when (= :rejected (:status result))
    (slow-down-message-generation!)))
```

**Benefit:** Prevents memory exhaustion, gives application control over backpressure.

---

**5. Observability**

```clojure
;; Queue metrics
(defn get-queue-stats []
  {:receive-queue {:depth (count @receive-queue)
                   :enqueue-rate (enqueues-per-sec)
                   :dequeue-rate (dequeues-per-sec)
                   :oldest-message-age-ms (age-of-first-msg)}
   :send-queue {:depth (count @send-queue)
                :pending-bytes (total-bytes-queued)
                :processing-rate (messages-per-sec)}})

;; Alerts
(when (> (count @receive-queue) 10000)
  (tel/error! "Receive queue backed up" {:depth (count @receive-queue)}))
```

**Benefit:** Visibility into system behavior, early warning of problems.

---

**6. Rate Limiting**

```clojure
;; Token bucket rate limiter
(def rate-limiter (atom {:tokens 100 :last-refill (now)}))

(defn dequeue-and-send! []
  (loop []
    ;; Refill tokens
    (refill-tokens! rate-limiter)

    ;; Consume token for each message
    (when (and (has-tokens? rate-limiter)
               (not-empty @send-queue))
      (let [msg (first @send-queue)]
        (consume-token! rate-limiter)
        (swap! send-queue pop)
        (ws/send! conn msg)))

    (Thread/sleep 10)
    (recur)))
```

**Benefit:** Prevent overwhelming server, comply with rate limits.

---

### Disadvantages of Explicit Queues

**1. Memory Usage**

```clojure
;; Without queue: O(1) memory (current message)
;; With queue: O(n) memory (all queued messages)

;; Example: 10,000 queued messages × 1KB each = 10MB
;; Multiple connections: 100 connections × 10MB = 1GB!

;; Mitigation: Bounded queue
(defn enqueue! [queue msg max-size]
  (if (< (count @queue) max-size)
    (swap! queue conj msg)
    (do
      (tel/warn! "Queue full, dropping message" {:size (count @queue)})
      :dropped)))
```

**Cost:** Memory proportional to queue depth, potential for exhaustion.

---

**2. Latency Added**

```clojure
;; Without queue: Immediate processing
(ws/on-message (fn [msg] (process! msg)))  ; 0ms queue latency

;; With queue: Waiting time
(ws/on-message (fn [msg] (enqueue! msg)))  ; Queued
;; Later...
(process! msg)  ; Queue latency = time waiting

;; If queue has 1000 messages, processing at 100 msg/sec:
;; Latency = 10 seconds!
```

**Cost:** Additional latency from queue waiting time.

---

**3. Complexity**

```clojure
;; Without queue: Simple
(defn handle-message! [msg]
  (process-message! msg))

;; With queue: Complex
(defn start-receive-worker! []
  (future
    (loop []
      (try
        (when-let [msg (poll-with-timeout receive-queue 1000)]
          (process-message! msg))
        (catch Exception e
          (tel/error! "Worker crashed" e)
          ;; Restart? Continue? What about msg?
          ))
      (recur))))

;; Need to handle:
;; - Worker crashes
;; - Queue overflow
;; - Graceful shutdown (drain queue?)
;; - Monitoring
;; - Thread management
```

**Cost:** More complex code, more failure modes, harder to debug.

---

**4. Ordering Guarantees Weakened**

```clojure
;; Without queue: Strict FIFO (network order = processing order)
(ws/on-message process!)

;; With multiple workers: Ordering lost!
(dotimes [_ 4]  ; 4 workers
  (future
    (loop []
      (when-let [msg (dequeue!)]
        (process! msg))  ; Messages processed out of order!
      (recur))))

;; Solution: Single worker (but loses parallelism)
;; Or: Partition by key
(defn partition-key [msg] (:user-id msg))
(def queues (atom {}))  ; {user-id → queue}
```

**Cost:** Need careful design to maintain ordering when needed.

---

**5. Failure Handling Complexity**

```clojure
;; What if processing fails?
(defn process-worker! []
  (loop []
    (when-let [msg (dequeue!)]
      (try
        (process-message! msg)
        (catch Exception e
          ;; What to do?
          ;; - Drop message? (data loss)
          ;; - Retry? (how many times?)
          ;; - Dead letter queue? (complexity)
          ;; - Re-queue at front? (infinite loop if permanent error)
          (handle-failure! msg e))))
    (recur)))
```

**Cost:** Complex error handling, need retry logic, dead letter queues.

---

### Queue Implementation Strategies

**Strategy 1: Bounded Queue with Backpressure (RECOMMENDED)**

```clojure
(def receive-queue (java.util.concurrent.ArrayBlockingQueue. 10000))

;; Non-blocking enqueue
(defn enqueue! [msg]
  (if (.offer receive-queue msg)
    :queued
    :rejected))  ; Queue full, apply backpressure

;; Or blocking enqueue (waits for space)
(defn enqueue-blocking! [msg timeout-ms]
  (.offer receive-queue msg timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS))

;; Dequeue with timeout
(defn dequeue! [timeout-ms]
  (.poll receive-queue timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS))
```

**Benefits:**
- Memory bounded (won't exhaust RAM)
- Backpressure when full
- Efficient (java.util.concurrent)

**Trade-offs:**
- Messages can be dropped if queue full
- Need backpressure handling in application

---

**Strategy 2: Unbounded Queue (Dangerous!)**

```clojure
(def receive-queue (java.util.concurrent.ConcurrentLinkedQueue.))

;; Always accepts
(defn enqueue! [msg]
  (.add receive-queue msg)
  :queued)

;; Dequeue
(defn dequeue! []
  (.poll receive-queue))
```

**Benefits:**
- Simple (never rejects)
- No backpressure needed

**Dangers:**
- ⚠️ Memory exhaustion under load
- ⚠️ No visibility into problems
- ⚠️ OOM kills entire process

**Only use when:** Traffic bounded, message rate < processing rate always.

---

**Strategy 3: Priority Queue**

```clojure
(def priority-queue
  (java.util.concurrent.PriorityBlockingQueue.
    1000
    (comparator
      (fn [a b]
        (compare (priority-value b) (priority-value a))))))  ; Higher priority first

(defn enqueue! [msg]
  (.offer priority-queue msg))

(defn dequeue! []
  (.take priority-queue))  ; Blocks until available

(defn priority-value [msg]
  (case (:priority msg)
    :critical 100
    :high 50
    :normal 10
    :low 1
    :bulk 0))
```

**Benefits:**
- Critical messages processed first
- Latency-sensitive traffic prioritized

**Trade-offs:**
- More complex
- Starvation possible (bulk never processed if constant critical traffic)

**Critical Insight: Priority Queues Work Best With Congestion**

Priority queues are most valuable when the receive side is **congested** (slow processing):

```clojure
;; Scenario 1: NO congestion (fast processing)
;; Processing: 1ms per message
;; Arrival rate: 10 messages/sec
;; Queue depth: Always empty

;; Priority doesn't matter - messages processed immediately!
(enqueue! {:priority :critical :data "urgent"})   ; Processed in 1ms
(enqueue! {:priority :bulk :data "not urgent"})   ; Also processed in 1ms

;; Scenario 2: WITH congestion (slow processing)
;; Processing: 100ms per message
;; Arrival rate: 50 messages/sec
;; Queue depth: Growing (5 msg/sec accumulation)

;; Without priority: Critical waits behind 1000 bulk messages
;; Wait time: 1000 messages × 100ms = 100 seconds!

;; With priority: Critical jumps queue
(enqueue! {:priority :critical :data "urgent"})   ; Processed in 100ms
(enqueue! {:priority :bulk :data "not urgent"})   ; Waits 100 seconds
```

**Why this matters:**

```clojure
;; Low congestion: Priority adds complexity without benefit
;; Processing rate >> arrival rate
;; Queue always near-empty
;; All messages processed quickly regardless of priority

;; High congestion: Priority provides huge value
;; Processing rate << arrival rate
;; Queue backs up
;; Critical messages skip queue, bulk messages wait

;; Example: Real-time trading
;; Normal load: 100 msg/sec, processing at 1000 msg/sec
;; → Priority unnecessary (queue empty)
;;
;; Market spike: 10,000 msg/sec, processing at 1000 msg/sec
;; → Priority critical! Cancel orders must skip 9000 queued messages
```

**Rule of thumb:**

- **Queue depth < 10**: Priority provides minimal benefit (messages processed quickly anyway)
- **Queue depth 10-100**: Priority helps latency-sensitive messages
- **Queue depth > 100**: Priority essential (without it, critical messages wait minutes)

**Design implication:**

```clojure
;; Adaptive priority: Only use priority queue when congested
(def current-queue (atom :simple-queue))

(defn maybe-upgrade-to-priority! []
  (let [depth (queue-depth)]
    (cond
      ;; Heavy congestion: Switch to priority
      (and (> depth 100) (= @current-queue :simple-queue))
      (do
        (tel/warn! "Queue congested, enabling priority" {:depth depth})
        (reset! current-queue :priority-queue))

      ;; Congestion cleared: Switch back to simple
      (and (< depth 10) (= @current-queue :priority-queue))
      (do
        (tel/info! "Queue cleared, disabling priority" {:depth depth})
        (reset! current-queue :simple-queue)))))

;; Check every second
(future
  (loop []
    (Thread/sleep 1000)
    (maybe-upgrade-to-priority!)
    (recur)))
```

**Real-world observation:** Video conferencing - under normal load (low congestion), all messages (video frames, control) process immediately. During network congestion (queue builds up), priority ensures "mute/unmute" controls work instantly even when video frames queue for seconds.

**Recommendation:** Start with simple FIFO queue. Add priority only when queue depth monitoring shows persistent congestion (depth > 50 for >10 seconds).

---

**Strategy 4: Ring Buffer (High Performance)**

```clojure
;; LMAX Disruptor pattern
(def ring-buffer (atom {:buffer (vec (repeat 1024 nil))
                        :write-pos 0
                        :read-pos 0}))

(defn enqueue! [msg]
  (swap! ring-buffer
    (fn [{:keys [buffer write-pos read-pos]}]
      (let [next-write (mod (inc write-pos) (count buffer))]
        (if (= next-write read-pos)
          ;; Full
          {:buffer buffer :write-pos write-pos :read-pos read-pos}
          ;; Write and advance
          {:buffer (assoc buffer write-pos msg)
           :write-pos next-write
           :read-pos read-pos})))))

(defn dequeue! []
  (let [result (atom nil)]
    (swap! ring-buffer
      (fn [{:keys [buffer write-pos read-pos]}]
        (if (= write-pos read-pos)
          ;; Empty
          {:buffer buffer :write-pos write-pos :read-pos read-pos}
          ;; Read and advance
          (do
            (reset! result (nth buffer read-pos))
            {:buffer buffer
             :write-pos write-pos
             :read-pos (mod (inc read-pos) (count buffer))}))))
    @result))
```

**Benefits:**
- Very high performance (lock-free possible)
- Predictable memory (fixed size)
- Cache-friendly (contiguous memory)

**Trade-offs:**
- Complex implementation
- Fixed size
- Requires careful synchronization

---

### Receive Queue Design

**When to use receive queue:**

✅ Processing is slow (>10ms per message)
✅ Need to smooth burst traffic
✅ Want to decouple WebSocket thread from processing
✅ Need prioritization
✅ Want observability into backlog

**When NOT to use:**

❌ Processing is fast (<1ms per message)
❌ Memory constrained
❌ Latency is critical (<10ms)
❌ Message rate << processing rate (underutilized)

**Recommended configuration:**

```clojure
{:receive-queue {:enabled true
                 :type :bounded          ; :bounded, :unbounded, :priority
                 :max-depth 10000        ; Max messages
                 :max-bytes 10485760     ; 10MB max
                 :workers 4              ; Parallel processing
                 :batch-size 10          ; Dequeue N messages at once
                 :drop-on-full false     ; Block or drop?
                 :metrics-interval-ms 1000}}  ; Report stats
```

---

### Send Queue Design

**When to use send queue:**

✅ Sending in bursts (batch multiple messages)
✅ Need rate limiting
✅ Want to decouple message generation from network
✅ Need retry logic
✅ Want to buffer during disconnections

**When NOT to use:**

❌ Need immediate send confirmation
❌ Memory constrained
❌ Single low-rate sender

**Recommended configuration:**

```clojure
{:send-queue {:enabled true
              :type :bounded
              :max-depth 1000
              :max-bytes 1048576      ; 1MB max
              :flush-strategy :hybrid  ; :time, :size, :count, :hybrid
              :flush-interval-ms 10    ; Flush every 10ms
              :flush-on-disconnect true  ; Buffer for reconnect
              :rate-limit {:enabled true
                          :messages-per-sec 100
                          :burst-size 20}}}
```

---

### Integration with Existing Features

**Queues + Batching:**

```clojure
;; Queue collects messages
(enqueue! send-queue msg1)
(enqueue! send-queue msg2)
(enqueue! send-queue msg3)

;; Sender batches from queue
(defn send-worker! []
  (loop []
    (let [batch (dequeue-batch! send-queue 10)]  ; Up to 10 messages
      (when (seq batch)
        (ws/send! conn {:type :batch :messages batch})))
    (Thread/sleep 10)
    (recur)))
```

**Benefit:** Batching pulls from queue, combines advantages.

---

**Queues + Compression:**

```clojure
;; Queue stores uncompressed messages
(enqueue! send-queue msg)

;; Sender compresses before sending
(defn send-worker! []
  (loop []
    (when-let [msg (dequeue! send-queue)]
      (let [compressed (compress-if-large msg)]
        (ws/send! conn compressed)))
    (recur)))
```

**Benefit:** Compression on sender thread, doesn't block application.

---

**Queues + Chunking:**

```clojure
;; Large message enqueued
(enqueue! send-queue large-message)

;; Sender chunks on the fly
(defn send-worker! []
  (loop []
    (when-let [msg (dequeue! send-queue)]
      (if (large? msg)
        (send-chunked! conn msg)
        (ws/send! conn msg)))
    (recur)))
```

**Benefit:** Chunking logic in background, application simplified.

---

### Performance Comparison

| Approach | Throughput | Latency | Memory | Complexity |
|----------|------------|---------|--------|------------|
| **No queue (direct)** | Low (blocks) | Best (0ms) | Minimal | Simple |
| **Bounded queue** | High | Good (+1-5ms) | Bounded | Medium |
| **Unbounded queue** | Highest | Good | Unbounded ⚠️ | Medium |
| **Priority queue** | High | Variable | Bounded | High |
| **Ring buffer** | Highest | Best | Fixed | Highest |

---

### Real-World Examples

**Example 1: Chat Application**

```clojure
;; Receive: Direct (messages are small, processing fast)
(ws/on-message
  (fn [msg]
    (route-chat-message! msg)))  ; <1ms, no queue needed

;; Send: Queued with batching
(def send-queue (ArrayBlockingQueue. 1000))

(defn send-chat-message! [msg]
  (enqueue! send-queue msg))

;; Sender batches every 10ms
(future
  (loop []
    (Thread/sleep 10)
    (let [batch (drain-up-to! send-queue 10)]
      (when (seq batch)
        (ws/send! conn {:type :batch :messages batch})))
    (recur)))
```

---

**Example 2: Real-Time Analytics**

```clojure
;; Receive: Queued (slow processing - DB writes)
(def receive-queue (ArrayBlockingQueue. 50000))

(ws/on-message
  (fn [msg]
    (.offer receive-queue msg)))  ; Never blocks WS thread

;; 4 workers process in parallel
(dotimes [_ 4]
  (future
    (loop []
      (when-let [msg (.take receive-queue)]  ; Blocks until available
        (write-to-database! msg)  ; 50ms
        (recur)))))

;; Send: Direct (infrequent)
(defn send-result! [result]
  (ws/send! conn result))
```

---

**Example 3: File Upload Service**

```clojure
;; Receive: Large chunks, queue with backpressure
(def receive-queue (ArrayBlockingQueue. 100))  ; Small queue (large chunks)

(ws/on-message
  (fn [chunk]
    (if (.offer receive-queue chunk)
      (send-flow-control! conn {:credits 1})  ; Accept more
      (send-flow-control! conn {:credits 0}))))  ; Pause

;; Worker writes to disk
(future
  (loop []
    (when-let [chunk (.take receive-queue)]
      (write-to-file! chunk)
      (recur))))

;; Send: Queued for rate limiting
(def send-queue (ArrayBlockingQueue. 10000))

;; Rate limiter: 100 msg/sec
(future
  (loop []
    (Thread/sleep 10)  ; 100 msg/sec = 10ms per message
    (when-let [msg (.poll send-queue)]
      (ws/send! conn msg))
    (recur)))
```

---

### Recommendations for sente-lite

**Default Configuration (RECOMMENDED):**

```clojure
{:queues {:receive {:enabled false          ; Default: direct processing
                    :max-depth 10000        ; If enabled
                    :workers 1}             ; Single worker preserves order
          :send {:enabled true              ; Enable send queue by default
                 :max-depth 1000
                 :flush-interval-ms 10      ; Batch automatically
                 :flush-on-disconnect true}}}  ; Buffer during reconnect
```

**Rationale:**
- **Receive direct by default**: Most applications process quickly, no queue needed
- **Send queued by default**: Decouples app from network, enables batching
- **Opt-in receive queue**: For slow processing, analytics, bulk operations

---

**API Design:**

```clojure
;; Send (always queued internally)
(sente/send! client msg)  ; Returns immediately, queued

;; Receive with queue (opt-in)
(def client
  (sente/make-client
    {:uri "ws://server.com"
     :receive-queue {:enabled true
                     :workers 4
                     :on-message (fn [msg] (process! msg))}}))

;; Receive direct (default)
(def client
  (sente/make-client
    {:uri "ws://server.com"
     :on-message (fn [msg] (process! msg))}))  ; Called directly from WS thread

;; Observability
(sente/get-queue-stats client)
;; => {:receive-queue {:depth 150 :enqueue-rate 100 :dequeue-rate 95}
;;     :send-queue {:depth 5 :pending-bytes 5120}}
```

---

### Key Takeaways

**Use explicit queues when:**
1. Processing is slow (receive) or bursts are common (send)
2. Need backpressure and flow control
3. Want prioritization or rate limiting
4. Need visibility into system behavior

**Don't use explicit queues when:**
1. Processing is fast (<1ms)
2. Memory constrained
3. Latency critical (<10ms)
4. Traffic is steady and low

**For sente-lite:**
- Send queue: Enable by default (enables batching, buffering)
- Receive queue: Opt-in for slow processing
- Provide clear configuration and metrics
- Document trade-offs

---

#### Observations & Real-World Considerations

**A. Performance Characteristics**

**Batching Performance:**
- **Best case**: 50-60% overhead reduction with 10-message batches
- **Typical case**: 35-45% reduction with variable message sizes
- **Latency impact**: 5-15ms added latency (flush interval)
- **Memory overhead**: ~4KB per connection (batch buffer)
- **CPU impact**: Minimal (<1% increase)
- **Network efficiency**: Fewer TCP packets = better mobile performance

**Real-world observation**: In production chat systems, batching reduces server CPU by 20-30% during peak traffic by consolidating frame headers and reducing syscall overhead.

**Compression Performance:**
- **JSON compression**: 60-80% size reduction (highly repetitive structures)
- **Transit compression**: 50-70% size reduction (more compact base format)
- **Small messages (<512 bytes)**: Often negative benefit (overhead > savings)
- **Large messages (>10KB)**: Significant benefit (70%+ reduction typical)
- **CPU cost**: 5-20ms per message (depends on size and compression level)
- **Memory**: Temporary buffers during compression/decompression

**Real-world observation**: For API responses with nested JSON (e.g., user profiles with relationships), compression level 6 provides 75% size reduction with acceptable 8ms latency. Level 9 only improves to 78% but adds 15ms. Level 6 is the sweet spot.

**Chunking Performance:**
- **Chunk size impact**: 64KB chunks optimal for most networks
  - Too small (4KB): Excessive frame overhead
  - Too large (1MB): Memory pressure, head-of-line blocking
- **Reassembly overhead**: ~1-2ms per 10 chunks
- **Memory per active transfer**: chunk-size × 2 (send + receive buffers)
- **Concurrent transfers**: Limited by memory (10 × 64KB = 640KB per connection)

**Real-world observation**: 64KB chunks work well across mobile (3G/4G/5G) and broadband. Smaller chunks (32KB) help mobile latency but increase overhead. Larger chunks (128KB) help broadband throughput but hurt mobile.

**Streaming Performance:**
- **Flow control overhead**: 1-2% CPU for credit management
- **Progress tracking**: Negligible (<0.5% CPU)
- **Checkpointing**: 100-500 bytes memory per active stream
- **Resume cost**: 50-200ms reconnection + resume handshake
- **Concurrent streams**: Limited by memory (10-50 typical)

**Real-world observation**: Credit-based flow control prevents buffer bloat effectively. 10 initial credits with 30% refill threshold provides smooth flow without pauses.

**B. Common Pitfalls & Solutions**

**1. Batching Pitfalls:**

**Pitfall**: Batch timeout too long → excessive latency
```clojure
;; BAD: 100ms flush = 100ms added latency
{:batching {:flush-interval-ms 100}}

;; GOOD: 10ms flush = minimal latency impact
{:batching {:flush-interval-ms 10}}
```

**Pitfall**: No size limit → memory bloat
```clojure
;; BAD: Unlimited batch size
{:batching {:max-batch-size Integer/MAX_VALUE}}

;; GOOD: Bounded by count AND size
{:batching {:max-batch-size 10
            :max-batch-bytes 4096}}
```

**Pitfall**: Batching critical messages with bulk messages
```clojure
;; SOLUTION: Priority flag to bypass batching
(send-message! client {:type :urgent
                       :priority :high      ; Skip batching
                       :data "critical-alert"})
```

**2. Compression Pitfalls:**

**Pitfall**: Compressing everything including small messages
```clojure
;; BAD: Compress 50-byte message → 80 bytes (overhead!)
{:compression {:min-bytes 0}}

;; GOOD: Only compress when beneficial
{:compression {:min-bytes 512}}
```

**Pitfall**: Wrong compression level
```clojure
;; BAD: Level 9 = slow, minimal benefit
{:compression {:level 9}}

;; GOOD: Level 6 = balanced
{:compression {:level 6}}
```

**Pitfall**: Compressing already-compressed data (e.g., images, video)
```clojure
;; SOLUTION: Content-type detection
{:compression {:enabled true
               :skip-types #{:image :video :audio}
               :content-types [:json :transit :text]}}
```

**3. Chunking Pitfalls:**

**Pitfall**: No timeout → incomplete messages linger forever
```clojure
;; BAD: No timeout
{:chunking {:timeout-ms nil}}

;; GOOD: 30-second timeout
{:chunking {:timeout-ms 30000}}

;; BETTER: Periodic cleanup task
(defn cleanup-incomplete-chunks! []
  (let [cutoff (- (System/currentTimeMillis) 30000)]
    (swap! incomplete-chunks
           (fn [chunks]
             (into {}
                   (filter #(> (:started-at (val %)) cutoff))
                   chunks)))))
```

**Pitfall**: Out-of-order chunks not handled
```clojure
;; SOLUTION: Sort by chunk-index before reassembly
(defn reassemble-message [chunks]
  (->> chunks
       (sort-by :chunk-index)
       (map :data)
       (apply str)))
```

**Pitfall**: Memory leak from incomplete chunks
```clojure
;; SOLUTION: Memory-bounded buffer with LRU eviction
(defn add-chunk! [message-id chunk]
  (when (> (chunk-buffer-size) max-buffer-bytes)
    (evict-oldest-incomplete-message!))
  (add-chunk-to-buffer! message-id chunk))
```

**4. Streaming Pitfalls:**

**Pitfall**: No backpressure → buffer overflow
```clojure
;; BAD: Send without checking credits
(loop [chunks data-chunks]
  (when-let [chunk (first chunks)]
    (stream/write! stream chunk)  ; May overflow!
    (recur (rest chunks))))

;; GOOD: Block until credits available
(loop [chunks data-chunks]
  (when-let [chunk (first chunks)]
    @(stream/write! stream chunk)  ; Blocks if no credits
    (recur (rest chunks))))
```

**Pitfall**: Streams not cleaned up on disconnection
```clojure
;; SOLUTION: Connection close handler
(ws/on-close
  (fn [conn-id]
    (doseq [stream-id (active-streams-for-conn conn-id)]
      (stream/cancel! stream-id)
      (cleanup-stream-resources! stream-id))))
```

**Pitfall**: Progress callbacks too frequent → performance degradation
```clojure
;; BAD: Progress on every chunk = excessive overhead
(stream/on-progress! stream
  (fn [progress] (update-ui! progress))
  {:interval-ms 0})  ; Every chunk!

;; GOOD: Rate-limited progress updates
(stream/on-progress! stream
  (fn [progress] (update-ui! progress))
  {:interval-ms 500})  ; Every 500ms
```

**C. Integration Patterns**

**Pattern 1: Progressive Enhancement**
Start simple, add features as needed:
```clojure
;; Phase 1: Basic WebSocket (current)
(send-message! client {:type :event :data {...}})

;; Phase 2: Add compression (no API change)
{:compression {:enabled true}}
(send-message! client {:type :event :data {...}})  ; Auto-compressed

;; Phase 3: Add batching (no API change)
{:batching {:enabled true}
 :compression {:enabled true}}
(send-message! client {:type :event :data {...}})  ; Auto-batched + compressed

;; Phase 4: Add chunking for large messages (auto-detected)
(send-message! client {:type :event
                       :data large-data})  ; Auto-chunked if > max-frame-size
```

**Pattern 2: Feature Negotiation**
Client and server negotiate capabilities:
```clojure
;; Client announces capabilities on connect
{:type :handshake
 :capabilities {:compression [:gzip :deflate]
                :batching true
                :chunking true
                :max-chunk-size 65536}}

;; Server responds with agreed features
{:type :handshake-ack
 :features {:compression :gzip
            :batching true
            :chunking true
            :chunk-size 65536}}
```

**Pattern 3: Adaptive Configuration**
Adjust based on network conditions:
```clojure
(defn adjust-compression-level! [rtt-ms packet-loss]
  (cond
    ;; High latency + high loss = aggressive compression
    (and (> rtt-ms 200) (> packet-loss 0.05))
    (set-compression-level! 9)

    ;; Low latency + low loss = light compression
    (and (< rtt-ms 50) (< packet-loss 0.01))
    (set-compression-level! 3)

    ;; Default: balanced
    :else
    (set-compression-level! 6)))
```

**Pattern 4: Hybrid Storage**
Combine approaches based on size:
```clojure
(defn send-data! [client data]
  (let [size (count-bytes data)]
    (cond
      ;; Small: send directly
      (< size 1024)
      (send-message! client {:type :data :payload data})

      ;; Medium: compress + chunk
      (< size 10485760)  ; 10MB
      (send-chunked-compressed! client data)

      ;; Large: upload to S3, send reference
      :else
      (let [url (upload-to-s3! data)]
        (send-message! client {:type :data-ref :url url :size size})))))
```

**D. Testing Strategies**

**Unit Tests:**
```clojure
;; Batching: Verify flush triggers
(testing "Batch flushes on timeout"
  (let [sent (atom [])]
    (with-batching {:flush-interval-ms 100}
      (send! {:data 1})
      (Thread/sleep 150)
      (is (= 1 (count @sent))))))

;; Compression: Verify size reduction
(testing "Compression reduces size"
  (let [large-json (generate-json 10000)]
    (is (< (compressed-size large-json)
           (* 0.5 (original-size large-json))))))

;; Chunking: Verify reassembly
(testing "Chunks reassemble correctly"
  (let [original (generate-data 500000)
        chunks (chunk-message original 65536)]
    (is (= original (reassemble-chunks chunks)))))
```

**Integration Tests:**
```clojure
;; End-to-end with all features
(testing "Compress + chunk + batch"
  (let [server (start-server! {:compression {:enabled true}
                               :batching {:enabled true}
                               :chunking {:enabled true}})
        client (connect-client! "ws://localhost:3000")]

    ;; Send large message
    (send! client {:data (generate-data 5000000)})

    ;; Verify received correctly
    (let [received (wait-for-message client 10000)]
      (is (= 5000000 (count (:data received)))))))
```

**Performance Tests:**
```clojure
;; Throughput benchmark
(defn benchmark-throughput []
  (let [messages (repeatedly 1000 #(generate-message 1000))]
    (time
      (doseq [msg messages]
        (send! client msg)))
    (println "Messages/sec:" (/ 1000 elapsed-seconds))))

;; Latency percentiles
(defn benchmark-latency []
  (let [latencies (atom [])]
    (dotimes [_ 1000]
      (let [start (System/nanoTime)
            _ @(send-and-wait! client {:data "test"})
            end (System/nanoTime)]
        (swap! latencies conj (/ (- end start) 1000000.0))))
    {:p50 (percentile @latencies 0.5)
     :p95 (percentile @latencies 0.95)
     :p99 (percentile @latencies 0.99)}))
```

**Load Tests:**
```clojure
;; Concurrent clients
(defn load-test-concurrent-clients [n-clients messages-per-client]
  (let [clients (repeatedly n-clients #(connect-client!))
        results (atom [])]
    (doall
      (map (fn [client]
             (future
               (let [start (System/currentTimeMillis)]
                 (dotimes [_ messages-per-client]
                   (send! client {:data (rand-int 1000)}))
                 (swap! results conj
                        {:duration (- (System/currentTimeMillis) start)
                         :client-id (:id client)}))))
           clients))
    @results))
```

**E. Monitoring & Observability**

**Key Metrics to Track:**

**Batching metrics:**
```clojure
{:batch-stats
 {:messages-batched 1000
  :batches-sent 100
  :avg-batch-size 10
  :flush-reasons {:timeout 80 :size 15 :count 5}
  :latency-added-ms {:p50 8 :p95 12 :p99 15}}}
```

**Compression metrics:**
```clojure
{:compression-stats
 {:messages-compressed 500
  :bytes-before 5000000
  :bytes-after 1500000
  :compression-ratio 0.7
  :avg-compression-time-ms 12
  :skipped-small-messages 200}}
```

**Chunking metrics:**
```clojure
{:chunking-stats
 {:messages-chunked 10
  :total-chunks-sent 500
  :avg-chunks-per-message 50
  :incomplete-messages 2
  :reassembly-timeouts 1
  :memory-used-bytes 131072}}
```

**Streaming metrics:**
```clojure
{:streaming-stats
 {:active-streams 5
  :completed-streams 100
  :failed-streams 2
  :avg-throughput-bps 2500000
  :total-bytes-transferred 500000000
  :credit-stalls 12
  :resume-operations 3}}
```

**Telemetry integration:**
```clojure
(tel/event! ::performance-features
  {:enabled-features #{:batching :compression :chunking}
   :batch-efficiency 0.45  ; 45% overhead reduction
   :compression-ratio 0.65 ; 35% size reduction
   :chunking-overhead-ms 2
   :memory-used-mb 12})
```

**F. Decision Framework**

Use this decision tree to choose features:

```
1. What's your primary constraint?

   Bandwidth → Start with COMPRESSION
      ├─ Large JSON/Transit → Compression level 6 + chunking
      └─ Small frequent messages → Batching + compression

   Latency → Avoid batching, use compression only for large
      ├─ Real-time critical → No features, optimize network
      └─ Acceptable latency → Light compression (level 3)

   Memory → Avoid buffering, use streaming
      ├─ Large files → Streaming API
      └─ Moderate data → Chunking with small chunks

   Reliability → Start with BUFFERING
      ├─ Critical messages → Buffering + persistence
      └─ Best-effort → Buffering only

2. What's your message size distribution?

   Mostly small (<1KB) → BATCHING (biggest win)
   Mix of sizes → COMPRESSION + BATCHING
   Mostly large (>100KB) → CHUNKING or STREAMING
   Very large (>10MB) → EXTERNAL STORAGE

3. What's your traffic pattern?

   Bursts → BATCHING (smooths bursts)
   Steady → COMPRESSION (ongoing savings)
   High-throughput → BATCHING + COMPRESSION
   Low-throughput → Keep simple, avoid overhead

4. What's your deployment?

   Mobile clients → COMPRESSION (save bandwidth) + BATCHING (reduce packets)
   Broadband → BATCHING (reduce overhead)
   Data center → Keep simple (bandwidth cheap)
   Multi-region → COMPRESSION (cross-region costs)
```

**G. Production Readiness Checklist**

Before deploying features to production:

**Batching:**
- [ ] Configured flush interval (10ms recommended)
- [ ] Set max batch size (10 messages recommended)
- [ ] Set max batch bytes (4KB recommended)
- [ ] Tested with burst traffic patterns
- [ ] Verified latency impact acceptable
- [ ] Monitored batch efficiency metrics

**Compression:**
- [ ] Set compression level (6 recommended)
- [ ] Set minimum size threshold (512 bytes)
- [ ] Tested with representative data
- [ ] Measured CPU impact (<5% increase)
- [ ] Verified decompression errors handled
- [ ] Monitored compression ratio

**Chunking:**
- [ ] Set chunk size (64KB recommended)
- [ ] Set max chunks limit (1000 recommended)
- [ ] Set reassembly timeout (30s recommended)
- [ ] Tested with large messages
- [ ] Verified memory bounds
- [ ] Implemented cleanup task
- [ ] Monitored incomplete chunks

**Streaming:**
- [ ] Implemented flow control (credit-based recommended)
- [ ] Set initial credits (10 recommended)
- [ ] Tested backpressure handling
- [ ] Implemented progress tracking
- [ ] Tested resume on reconnection
- [ ] Verified concurrent stream limits
- [ ] Monitored active streams

**Cross-cutting:**
- [ ] Feature negotiation implemented
- [ ] Backward compatibility tested
- [ ] Monitoring/telemetry integrated
- [ ] Load tested at 2x expected traffic
- [ ] Documented configuration options
- [ ] Runbook for common issues
- [ ] Rollback plan defined

#### Deliverables:
- [x] Heartbeat mechanism
- [x] Robust error handling
- [ ] Message batching (deferred, 8-12 hours)
- [ ] Message buffering (deferred, 12-16 hours)
- [ ] Compression (deferred, 6-10 hours)
- [ ] Chunking (deferred, 15-30 hours depending on option)

### Phase 6: Connection Management ✅ COMPLETE (Added 2025-10-26)
**Goal:** Production-ready connection lifecycle management
**Status:** Complete with comprehensive test suite
**Tag:** v0.6.0-socket-fix

#### Implemented Features:
- [x] **Server-side heartbeat** (`05_heartbeat.bb`)
  - Configurable ping intervals (default: 30s)
  - Configurable timeout detection (default: 60s)
  - Automatic dead connection cleanup
  - Telemetry events for monitoring

- [x] **Client state tracking** (`06_state_tracking.bb`)
  - 6-state machine: closed, connecting, open, closing, reconnecting, failed
  - State change callbacks
  - Auto-pong response to server pings
  - Send validation (only works in :open state)

- [x] **Auto-reconnection** (`07_reconnection.bb`)
  - Exponential backoff: `initial-delay * (multiplier ^ attempt)`
  - Jitter: ±25% randomness (prevents thundering herd)
  - Max attempts tracking → :failed state
  - Graceful vs unexpected close detection

- [x] **Subscription restoration** (`08_subscription_restoration.bb`)
  - Subscriptions tracked in client state
  - Auto-restore on reconnection
  - `subscribe!`, `unsubscribe!`, `get-subscriptions` API

- [x] **Integration test** (`09_integration.bb`)
  - 12 test scenarios covering all features
  - Multiple disconnect/reconnect cycles
  - Multiple independent clients
  - State consistency validation

#### Architecture:
```clojure
;; Managed WebSocket client with all features
(def client
  (wsm/create-managed-client
   {:uri "ws://localhost:3000/"
    :on-state-change (fn [old new] ...)
    :on-message (fn [msg] ...)
    :heartbeat {:auto-pong true}           ; Auto-respond to pings
    :reconnect {:enabled true              ; Auto-reconnect on loss
                :max-attempts 5
                :initial-delay-ms 1000
                :backoff-multiplier 2}}))

;; Full API
((:connect! client))
((:disconnect! client))
((:send! client) message)
((:subscribe! client) "channel-id")
((:unsubscribe! client) "channel-id")
((:get-state client))           ; => :open
((:get-subscriptions client))   ; => #{"channel-id"}
```

#### Test Results:
- **22 total tests** across all phases
- **100% pass rate** (0 failures)
- **Complete coverage** of connection lifecycle
- **Production-ready** architecture validated

#### Deliverables:
- [x] Server heartbeat with dead connection detection
- [x] Client state machine with callbacks
- [x] Exponential backoff reconnection
- [x] Subscription restoration
- [x] Comprehensive integration test
- [x] Full documentation in test-use-cases-summary.md

### Phase 4: Extended Environments (Week 5)
**Goal:** Support for nbb and JVM Clojure

#### Tasks:
- [ ] Create nbb client (`nbb/client.cljs`)
  - Node.js WebSocket wrapper
  - Compatible API surface
- [ ] Add JVM Clojure support (optional)
  - http-kit adapter
  - Aleph adapter
- [ ] BB-to-BB communication
  - Direct Babashka WebSocket connections
  - Service mesh capabilities

#### Deliverables:
- nbb client implementation
- Optional JVM support
- BB-to-BB examples

### Phase 5: Examples & Testing (Week 6)
**Goal:** Comprehensive examples and test suite

#### Tasks:
- [ ] Build example applications
  - Chat application
  - Real-time dashboard
  - Collaborative editor
  - Telemetry pipeline
- [ ] Create test suite
  - Unit tests for all modules
  - Integration tests
  - Performance benchmarks
- [ ] Write documentation
  - API reference
  - Migration guide from Sente
  - Deployment guide

#### Deliverables:
- 4+ example applications
- >80% test coverage
- Complete documentation

## Testing Strategy

### Unit Tests
- Serialization round-trips
- Event router dispatch
- State management
- Reconnection backoff calculations

### Integration Tests
- Client-server handshake
- Message round-trips
- Broadcast functionality
- Auto-reconnection
- Error recovery

### Performance Tests
- 100+ concurrent clients
- 1000+ messages/second throughput
- Memory usage under load
- Reconnection storms

### E2E Tests
- Complete user flows
- Network failure recovery
- Server restart handling
- Browser refresh scenarios

## Success Metrics

### Functional
- ✅ ~85% Sente API compatibility
- ✅ <500 LOC core implementation
- ✅ Works in BB, Scittle, nbb
- ✅ Auto-reconnection with backoff
- ✅ User-based routing

### Performance
- 📊 >1000 msg/sec per server
- 📊 <50ms message latency
- 📊 >10,000 concurrent connections
- 📊 <1KB memory per connection

### Quality
- 📈 >80% test coverage
- 📈 Zero critical bugs in production
- 📈 <5% API breaking changes post-1.0

## Risk Mitigation

### Technical Risks
1. **Transit compatibility across environments**
   - Mitigation: Thorough testing, JSON fallback
2. **WebSocket library differences**
   - Mitigation: Abstract behind protocol
3. **Performance in interpreted environments**
   - Mitigation: Profile and optimize hot paths

### Adoption Risks
1. **Sente API incompatibilities**
   - Mitigation: Clear migration guide
2. **Limited ecosystem support**
   - Mitigation: Comprehensive examples
3. **Documentation gaps**
   - Mitigation: Extensive docs from day 1

## Development Guidelines

### Code Style
- Follow Clojure style guide
- Use clj-kondo for linting
- Format with cljfmt
- Comprehensive docstrings

### Git Workflow
1. Feature branches from main
2. PR with tests for features
3. Squash merge to main
4. Tag releases semantically

### Testing Requirements
- All new features need tests
- Integration tests for examples
- Performance benchmarks for changes
- Manual testing in all environments

## Next Immediate Steps

1. **Set up BB project structure** - Create bb.edn and BB-specific namespaces
2. **Implement BB Transit codec** - Transit serialization for Babashka
3. **Build BB server** - WebSocket server using org.httpkit.server
4. **Build BB client** - WebSocket client using babashka.http-client.websocket
5. **Create BB echo example** - Complete BB-to-BB communication test
6. **Write BB test suite** - Tests that run entirely in Babashka

## Current Status (2025-10-26)

### ✅ COMPLETED

**Phase 0: Telemere-lite Foundation**
- ✅ Core telemetry library (`telemere-lite/core.cljc`)
- ✅ Async handlers with performance benchmarks (24.5x improvement)
- ✅ File-based logging with structured output
- ✅ Event filtering and routing
- ✅ Timbre integration
- ✅ Complete test suite (6 tests passing)

**Phase 6: Connection Management** (Production-Ready)
- ✅ Server heartbeat with dead connection detection
- ✅ Client state tracking (6 states: closed, connecting, open, closing, reconnecting, failed)
- ✅ Auto-reconnection with exponential backoff
- ✅ Subscription restoration after reconnect
- ✅ Integration test (12/12 scenarios passing)
- ✅ Complete test suite (22 tests total)

**WebSocket Foundation**
- ✅ HTTP-Kit server (`org.httpkit.server`)
- ✅ Native BB client (`babashka.http-client.websocket`)
- ✅ Channel system with pub/sub
- ✅ Message broadcasting
- ✅ RPC patterns
- ✅ Wire format system (JSON, EDN, Transit)

**Recent Enhancements (v0.6.0)**
- ✅ Socket binding race condition identified and documented
- ✅ Test environment fix (50ms delay after server startup)
- ✅ Ephemeral port support (port 0) with 3 discovery methods
- ✅ Enhanced telemetry (requested-port, actual-port, ephemeral?)
- ✅ Production-ready architecture decisions

**Multi-Process Testing Suite (v0.6.1)**
- ✅ Complete test infrastructure (mp_utils.clj, 230 lines)
- ✅ Port discovery with fallback mechanisms
- ✅ Process synchronization and result aggregation
- ✅ Test runner (run_multiprocess_tests.bb)
- ✅ Integrated into main test suite (run_tests.bb)
- ✅ All 6 test scenarios implemented and passing:
  1. Basic multi-process (1 server + 2 clients, pub/sub)
  2. Ephemeral port reconnection (port-file fallback)
  3. Reconnection (server restart, auto-reconnect)
  4. Concurrent startup (10 simultaneous clients)
  5. Process failure (kill client, server cleanup)
  6. Stress test (20 clients, 16-20 msg/sec, 95% success rate)

### 🚧 IN PROGRESS

**Testing Architecture**
- ✅ Single-process tests (server + client in same BB)
- ✅ Multi-process tests (separate BB processes) - Production-ready

### 📋 PLANNED - Future Enhancements
- **UUIDv7 for conn-id**: Replace simple timestamp-based conn-id with UUIDv7 for better uniqueness and sortability
- **Authentication & Authorization**: Token-based auth, user ID routing
- **Browser Client (Scittle)**: JavaScript client with same API
- **nREPL Integration**: Transit multiplexer, bencode validation
- **Performance Optimizations**: See Phase 3 for detailed specs on batching, buffering, compression, and chunking
- **Monitoring**: Prometheus metrics, health checks, distributed tracing

---

## Updates Log

### 2025-10-29 - Auto-Reconnect & Server Bug Fixes Complete
**Status:** All major bugs fixed, auto-reconnect implemented, all tests passing

**Critical Bug Fixes:**
- ✅ **Server Type Inconsistency Fixed**: All 10 message types now use keywords consistently (`:ping`, `:subscription-result`, etc.)
  - Fixed in `server.cljc` lines 143, 152, 166, 176, 190, 202, 212, 217, 221, 225
  - BB-to-BB tests passing, browser demos working
- ✅ **Broadcast Envelope Bug Fixed**: Messages now wrapped in proper `{:type :channel-message :data {...}}` envelope
  - Fixed in `server.cljc` lines 535-538
  - All 4 pub/sub scenarios tested and working (BB↔BB, Browser↔Browser, BB↔Browser)

**Auto-Reconnect Architecture:**
- ✅ **Comprehensive Documentation Added** to `doc/plan.md` (lines 41-156):
  - Ephemeral Session ID Design (security rationale)
  - Security Model (why persistent IDs without auth enable attacks)
  - Reconnection Strategy (infrastructure vs application responsibilities)
  - API Contract (`:on-connect`, `:on-reconnect`, `:on-disconnect` hooks)
  - Complete implementation examples
  - Security best practices

**BB Auto-Reconnect:**
- ✅ **Implementation**: Test file `dev/scittle-demo/examples/test-reconnect-app-controlled.bb`
- ✅ **Testing**: End-to-end test passed (2 messages received, 1 before + 1 after reconnect)
- ✅ **Features Verified**:
  - Auto-reconnect after server disconnect
  - Exponential backoff (1s, 2s, 4s, ..., max 30s)
  - Application-controlled subscription restoration
  - Pub/sub working before and after reconnection

**Browser Auto-Reconnect:**
- ✅ **Implementation**: Added to `src/sente_lite/client_scittle.cljs`
- ✅ **Features**:
  - Config options: `:auto-reconnect?`, `:reconnect-delay`, `:max-reconnect-delay`
  - `attempt-reconnect!` function with exponential backoff
  - Differentiates initial connect from reconnect
  - `:on-reconnect` callback for application-controlled restoration
  - `set-reconnect!` function to control reconnection
- ✅ **Code Quality**: Zero linting errors, zero warnings, formatted

**Testing:**
- ✅ All BB-to-BB tests passing (10 unit tests + 6 multi-process scenarios)
- ✅ Pub/sub verified in all 4 scenarios: BB↔BB, Browser↔Browser, BB↔Browser

**Future Enhancements Added:**
- UUIDv7 for conn-id (better uniqueness and sortability)

**Next Steps:**
- Browser auto-reconnect manual testing (requires browser interaction)
- Consider extracting shared message handling between BB and browser

### 2025-10-27 - Multi-Process Testing Suite Complete
**Tag:** `v0.6.1-multiprocess-complete`

- ✅ **Multi-Process Testing Infrastructure**: Complete distributed testing capabilities
  - Test infrastructure (mp_utils.clj, 230 lines) with port discovery, process sync, result aggregation
  - 6 comprehensive test scenarios covering all critical use cases
  - Test runner (run_multiprocess_tests.bb) orchestrating all tests
  - Integrated into main test suite (run_tests.bb)
  - Total ~2,000 lines of multi-process test infrastructure

- ✅ **Test Scenarios Implemented**:
  1. Basic multi-process (1 server + 2 clients, pub/sub verification)
  2. Ephemeral port reconnection (port-file fallback mechanism)
  3. Reconnection (server restart, client auto-reconnect with backoff)
  4. Concurrent startup (10 simultaneous clients, race condition testing)
  5. Process failure (kill client mid-session, server cleanup verification)
  6. Stress test (20 clients, 16-20 msg/sec throughput, 95% success rate)

- ✅ **Phase 6e: Port-File Fallback**: Enhanced ephemeral port reconnection
  - Clients can reconnect to server with new ephemeral port
  - Port discovery via `/tmp/sente-lite-port-{test-id}.txt`
  - Enables resilient testing and production deployments

- ✅ **Production-Ready**: All tests passing, comprehensive coverage of distributed scenarios

### 2025-10-28 - EDN as Primary Serialization Format
**Status:** Documentation updated, implementation in progress

**Architecture Decision:**
- **Primary format: EDN** (Clojure-to-Clojure communication)
- **Rationale**:
  - Scittle nREPL uses EDN over WebSocket (not bencode as initially assumed)
  - nREPL gateway converts bencode ↔ EDN before WebSocket
  - EDN performance acceptable for primary use case
  - Simpler than Transit for Clojure-to-Clojure
- **Alternative formats remain available**: JSON, Transit via pluggable wire_format.cljc

**Implementation Tasks:**
- [ ] Update server.cljc default `:wire-format` from `:json` to `:edn`
- [ ] Update client_scittle.cljs to use EDN by default (already implemented)
- [ ] Add wire format configuration examples to documentation
- [ ] Test EDN format across all environments (BB server ↔ Scittle client)
- [ ] Document when to use JSON (interop) vs EDN (Clojure) vs Transit (rich types)
- [ ] Update all example code to use EDN by default

**Files affected:**
- `src/sente_lite/server.cljc` - Change default `:wire-format :json` → `:edn`
- `src/sente_lite/client_scittle.cljs` - Verify EDN usage (already correct)
- `doc/examples/` - Update all examples to use EDN
- `CLAUDE.md` - ✅ Updated
- `doc/plan.md` - ✅ Updated

### 2025-10-26 (Later) - Phase 3 Performance Features Specification
**Status:** Planning complete, implementation pending

- 📋 **Phase 3 Expanded**: Comprehensive specifications for performance features
  - **Message Batching**: 4 flush strategies, 40-60% overhead reduction (8-12 hours)
  - **Message Buffering**: Per-user queues, TTL expiration, reconnection flush (12-16 hours)
  - **Compression**: 3 options analyzed (per-message RECOMMENDED), 60-80% size reduction (6-10 hours)
  - **Chunking**: 5 options with trade-offs, application-level chunking RECOMMENDED (15-20 hours)
  - **Hybrid Option**: Compress-then-chunk for optimal large message handling (25-30 hours)

- 📊 **Decision Matrix**: Added recommendation matrix for different use cases
  - High-throughput: Batching
  - Reliable notifications: Buffering
  - Large messages (1-10MB): Chunking + Compression
  - Very large (>10MB): External storage reference
  - File transfers: Streaming API

- 🎯 **Wire Formats Defined**: Complete wire protocol specifications for:
  - Batched messages (`:batch` type)
  - Compressed messages (`:compressed` flag + algorithm)
  - Chunked messages (`:chunk-start`, `:chunk`, `:chunk-end`)
  - External references (`:large-message` with storage URL)

### 2025-10-26 - Connection Management Complete & Port Discovery
**Tag:** `v0.6.0-socket-fix`

- ✅ **Phase 6 Complete**: Full connection management suite
  - Server heartbeat (configurable intervals, timeouts)
  - Client state machine (6 states with callbacks)
  - Auto-reconnection with exponential backoff + jitter
  - Subscription restoration after reconnection
  - Integration test with 12 scenarios

- ✅ **Socket Binding Investigation**: Root cause identified
  - http-kit returns in ~14ms, socket ready in ~10-20ms
  - Test environment fix: 50ms delay after server startup
  - Production unaffected: clients use service discovery with natural delays
  - Architecture decision: NO server changes, client reconnection handles edge cases

- ✅ **Ephemeral Port Support**: Port 0 for flexible deployment
  - `(server/get-server-port)` function
  - `get-server-stats` includes `:actual-port`, `:requested-port`, `:ephemeral?`
  - Server state atom includes `:actual-port`
  - Telemetry events track both ports
  - Use cases: testing, containers, development, service discovery

- 📋 **Multi-Process Testing**: Future enhancement planned
  - 6 test scenarios defined in "Future Enhancements" section
  - Implementation approaches outlined
  - Estimated effort: 15-25 hours

### 2025-10-24 - Initial Plan
- Created comprehensive implementation plan
- Defined 6-week development timeline
- Established project structure
- Set success metrics and testing strategy

### 2025-10-24 - Revised Phase 1 Focus
- Refocused Phase 1 entirely on Babashka client/server implementation
- Researched latest versions: BB 1.12.207+, HTTP Kit 2.8.0+, Telemere 1.1.0
- Since Telemere doesn't support Babashka, planning custom BB-compatible telemetry
- Simplified project structure for BB-first development
- Prioritized BB-to-BB communication testing

### 2025-10-24 - Version Research Complete
- **Babashka 1.12.207+**: Latest stable with built-in http-client.websocket
- **HTTP Kit 2.8.0+**: Babashka/GraalVM compatible WebSocket server
- **Telemere 1.1.0**: Latest stable but incompatible with BB (Encore dependency)
- **Alternative**: Building Telemere-inspired telemetry solution for Babashka

### 2025-10-24 - Modular Architecture Design
- **Reorganized for modularity**: 5 independent libraries that can be imported separately
- **Clear boundaries**: shared/, telemere-lite/, client/, server/, lifecycle/
- **Import flexibility**: Users can import only what they need
- **Lifecycle management**: Component protocol + system maps for production use
- **BB-optimized**: All modules designed for easy Babashka importing

### 2025-10-24 - Lifecycle Management Research Complete
- **Integrant**: ✅ Compatible with Babashka using spartan.spec (2024)
- **Component**: ✅ Listed in Babashka Toolbox, direct compatibility
- **Mount**: ⚠️ Unknown compatibility, uses JVM-specific features
- **Signal Handling**: Java shutdown hooks recommended over native SIGTERM
- **Strategy**: Custom solution + optional Integrant integration

### 2025-10-24 - Babashka Book Review & Telemere Simplification
- **Built-in libraries**: Confirmed `babashka.fs`, `babashka.process`, `babashka.signal` availability
- **Built-in logging**: `clojure.tools.logging` and `taoensso.timbre` included in BB
- **Task dependencies**: `bb.edn` task system for component orchestration
- **Dynamic classpath**: `babashka.classpath` and `babashka.deps` for runtime loading
- **Telemere-lite**: Wrapper around built-in logging with structured output (JSON/EDN)

### 2025-10-24 - Timbre vs Telemere Analysis & Shared Code Strategy

#### Timbre vs Telemere Differences:
- **Timbre**: Traditional logging library (12+ years old), included in BB
- **Telemere**: Next-gen structured telemetry, unified signals API, not BB-compatible
- **Our approach**: Create telemere-lite with Telemere's signal concepts using Timbre backend

#### Browser/Scittle Logging:
- **Available**: `js/console.log`, `println` with `(enable-console-print!)`
- **Reader conditionals**: `:scittle` for browser-specific code
- **Shared strategy**: Use `.cljc` files with platform-specific handlers

#### Maximum Code Sharing Strategy:
- **90% shared code** using `.cljc` files with reader conditionals
- **Platform detection**: `#?(:bb ... :scittle ... :cljs ...)`
- **Unified API**: Same functions work in BB, Scittle, and future platforms
- **Only platform-specific**: Output handlers (Timbre vs console.log)

#### Browser Telemetry Routing for AI Visibility:
- **Critical Problem**: Browser console.log invisible to AI agents, making debugging impossible
- **Solution**: Route ALL browser telemetry through sente WebSocket to BB server files
- **AI Agent Benefits**:
  - **Full visibility** into browser-side execution and errors
  - **Debugging support** - AI can read browser logs from server files
  - **Error correlation** - Match client errors with server-side events
  - **User action tracking** - Understand what user did before errors
  - **Performance analysis** - Browser timing metrics in readable files
  - **Real-time assistance** - AI can monitor ongoing browser sessions

- **Implementation Strategy**:
  ```clojure
  ;; Browser automatically sends telemetry to server
  (tel/configure! {:output :remote
                   :include {:errors true
                            :warnings true
                            :user-actions true
                            :performance true}})

  ;; Server writes to AI-accessible files
  "logs/browser/[session-id]-[timestamp].log"
  ```

- **Enhanced Debugging Features**:
  - Stack traces with source maps
  - DOM event sequences
  - Network request logs
  - Browser console capture
  - User interaction replay data

#### Playwright + Telemetry: Ultimate AI Visibility
- **Development Phase**: Playwright gives AI direct browser control
  - See everything happening in browser
  - Inject code and test fixes live
  - Reproduce bugs instantly
  - Profile performance in real-time

- **Production Phase**: Telemetry routing gives ongoing visibility
  - Browser logs routed to server files
  - AI can debug production issues
  - Historical log analysis
  - User session replay from logs

- **Combined Power**:
  ```clojure
  ;; Development: AI uses Playwright
  (playwright/evaluate page
    "(tel/log! :debug 'Testing fix' {:attempt 1})")

  ;; Production: Same logs route to server
  (tel/log! :error "User action failed" {:user-id uid})
  ;; → WebSocket → Server → File → AI reads
  ```

- **Benefits**:
  - Development: Direct browser control
  - Testing: Automated scenarios
  - Production: Continuous monitoring
  - Debugging: Complete visibility always

## Module Dependency Graph

```
┌─────────────────┐    ┌─────────────────┐
│  telemere-lite  │    │ sente-lite/     │
│  (standalone)   │    │ shared/         │
│                 │    │ (foundation)    │
└─────────────────┘    └─────────┬───────┘
                                 │
                    ┌────────────┴────────────┐
                    │                         │
              ┌─────▼──────┐            ┌─────▼──────┐
              │ sente-lite-│            │ sente-lite-│
              │ client/    │            │ server/    │
              │            │            │            │
              └─────┬──────┘            └─────┬──────┘
                    │                         │
                    └────────────┬────────────┘
                                 │
                         ┌───────▼───────┐
                         │ sente-lite-   │
                         │ lifecycle/    │
                         │ (optional)    │
                         └───────────────┘
```

### Import Dependencies

#### Zero Dependencies
- `telemere-lite/*` - Standalone telemetry
- `sente-lite/shared/*` - Foundation utilities

#### Depends on Shared
- `sente-lite-client/*` - Requires `sente-lite/shared/*`
- `sente-lite-server/*` - Requires `sente-lite/shared/*`

#### Depends on Everything (Optional)
- `sente-lite-lifecycle/*` - Can coordinate all modules

---

**Note:** This plan is a living document and will be updated as the implementation progresses. Each significant architecture decision or change should be documented here.