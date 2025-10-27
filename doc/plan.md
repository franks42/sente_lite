# sente-lite Implementation Plan

**Version:** 1.0
**Created:** 2025-10-24
**Status:** Active Development Plan

---

## Executive Summary

sente-lite is a lightweight WebSocket library providing ~85% Sente API compatibility for Babashka, Scittle/SCI, and Node.js environments. This plan outlines the implementation strategy, architecture decisions, and development phases.

## Core Architecture Decisions

### 1. Technology Stack
- **Serialization:** Transit-json primary, JSON fallback
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
│   │       ├── codec.cljc       # Transit/JSON serialization (shared)
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
  - `codec.clj` - Transit/JSON serialization (with logging)
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
**Status:** Partially complete (heartbeat done, reconnection done)

#### Tasks:
- [x] Implement heartbeat/keepalive
  - Client ping mechanism
  - Server handshake responses
  - Connection health monitoring
- [ ] Add message batching/buffering
  - Client-side event buffering
  - Batch send optimization
  - Offline queue management
- [x] Build error handling
  - Comprehensive error events
  - Error recovery strategies
  - Debug mode with logging

#### Deliverables:
- [x] Heartbeat mechanism
- [ ] Message buffering (deferred)
- [x] Robust error handling

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

### 🚧 IN PROGRESS

**Testing Architecture**
- ✅ Single-process tests (server + client in same BB)
- 📋 Multi-process tests (separate BB processes) - PLANNED in FUTURE-ENHANCEMENTS.md

### 📋 PLANNED

See `doc/FUTURE-ENHANCEMENTS.md` for detailed roadmap:
- Multi-process testing (HIGH priority)
- Authentication & authorization
- Browser client (Scittle)
- nREPL integration
- Performance optimizations

---

## Updates Log

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

- 📋 **Multi-Process Testing**: Documented future enhancement
  - 6 test scenarios defined
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