# Sente-lite Test Use Cases Summary

## Overview

Current test suite organized in 3 phases with 11+ test scripts covering telemetry, async, and WebSocket functionality.

---

## Test Architecture & Client Configuration

### Overview Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                    Test Suite Architecture                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  Phase 1: Telemere-lite Core (6 tests)                          │
│  ┌──────────────────────────────────────┐                       │
│  │  BB Test Script → telemere-lite API  │                       │
│  │  (Pure unit tests, no client)        │                       │
│  └──────────────────────────────────────┘                       │
│                                                                   │
│  Phase 2: Async Implementation (2 tests)                        │
│  ┌──────────────────────────────────────┐                       │
│  │  BB Test Script → async handlers     │                       │
│  │  (Performance tests, no client)      │                       │
│  └──────────────────────────────────────┘                       │
│                                                                   │
│  Phase 3: WebSocket Foundation (3 tests)                        │
│  ┌──────────────────────────────────────┐                       │
│  │ 3a) Browser Client Test              │                       │
│  │     BB → http-kit → Browser JS       │                       │
│  ├──────────────────────────────────────┤                       │
│  │ 3b) Server Foundation Test           │                       │
│  │     BB → sente-lite-server (no client)│                      │
│  ├──────────────────────────────────────┤                       │
│  │ 3c) Channel Integration Test         │                       │
│  │     BB → sente-lite channels         │                       │
│  │     (Simulated connection IDs)       │                       │
│  └──────────────────────────────────────┘                       │
└─────────────────────────────────────────────────────────────────┘
```

### Phase 1 & 2: Pure Babashka Unit Tests

**No Client Required** - Tests run entirely in Babashka process.

```
┌─────────────────────────────────────────────┐
│  test_*.bb (Babashka Script)                │
│                                             │
│  (require '[telemere-lite.core :as tel])   │
│                                             │
│  (tel/signal! {:level :info ...})          │
│        │                                    │
│        ├──→ Custom Handlers (async/sync)   │
│        └──→ Timbre (fallback logging)      │
│                                             │
│  Assert: stats, output files, behavior     │
└─────────────────────────────────────────────┘
```

**Configuration**: Tests configure handlers directly via API
```clojure
;; Async handler
(tel/add-file-handler! :async-test "async-test.log"
  {:async {:mode :dropping :buffer-size 1024 :n-threads 1}})

;; Sync handler
(tel/add-file-handler! :sync-test "sync-test.log"
  {:sync true})
```

### Phase 3a: WebSocket Foundation (Browser Client)

**Client**: Embedded HTML/JavaScript in test script

```
┌──────────────────┐                    ┌────────────────────┐
│  BB Test Script  │                    │   Browser Client   │
│                  │                    │  (Embedded HTML)   │
│  http-kit server │◄───── WebSocket ──┤                    │
│  (port 3000)     │      (ws://)      │  JavaScript:       │
│                  │                    │  - Connect         │
│  Echo handler:   │      JSON msgs    │  - Send messages   │
│  - on-open       │◄─────────────────►│  - Receive echoes  │
│  - on-message    │                    │  - Log to console  │
│  - on-close      │                    │                    │
│  - on-error      │                    │                    │
└──────────────────┘                    └────────────────────┘
```

**Configuration**: Test embeds browser client in HTTP response
```clojure
{:status 200
 :headers {"content-type" "text/html"}
 :body "<!DOCTYPE html>
        <html>
        <script>
          const ws = new WebSocket('ws://localhost:3000');
          ws.onopen = () => ws.send(JSON.stringify({type: 'ping'}));
          ws.onmessage = (e) => console.log('Echo:', e.data);
        </script>
        </html>"}
```

**Access**: Open browser to `http://localhost:3000` during test

### Phase 3b: Server Foundation (No Client)

**Client**: None - Server-only validation tests

```
┌──────────────────────────────────────────┐
│  test_server_foundation.bb               │
│                                          │
│  (require '[sente-lite.server-simple])  │
│                                          │
│  Server Lifecycle Tests:                │
│  ├─ start-server! → running?            │
│  ├─ get-server-stats → connections      │
│  ├─ wire-format system → available      │
│  └─ stop-server! → shutdown             │
│                                          │
│  HTTP Endpoints (not tested):           │
│  - GET /health → {:status "ok"}         │
│  - GET /stats → {:connections 0 ...}    │
│                                          │
│  No WebSocket client - server tests only│
└──────────────────────────────────────────┘
```

**Configuration**: Server with telemetry
```clojure
(def test-server
  (server/start-server!
    {:port 3001
     :telemetry {:enabled true
                 :handler-id :test-server}}))
```

**Manual Testing**: Can use `curl` or browser
- Health: `http://localhost:3001/health`
- Stats: `http://localhost:3001/stats`

### Phase 3c: Channel Integration (Simulated Clients)

**Client**: Simulated connection IDs (strings) - No actual WebSocket

```
┌─────────────────────────────────────────────────────────┐
│  test_channel_integration.bb                            │
│                                                         │
│  Multiple Servers (different wire formats):             │
│  ┌─────────────┬─────────────┬──────────────┐         │
│  │ Port 3002   │ Port 3003   │ Port 3004    │         │
│  │ JSON format │ EDN format  │ Transit+JSON │         │
│  └─────────────┴─────────────┴──────────────┘         │
│                                                         │
│  Simulated Connections:                                 │
│  conn1 = "conn-test-001"  ──┐                          │
│  conn2 = "conn-test-002"    ├──→ subscribe! channels   │
│  conn3 = "conn-test-003"  ──┘                          │
│                                                         │
│  Channel Operations:                                    │
│  ├─ create-channel! "test-channel"                     │
│  ├─ subscribe! conn1 "test-channel"                    │
│  ├─ publish! "test-channel" {:msg "hello"}             │
│  ├─ send-rpc-request! conn1 {:id 123 ...}             │
│  └─ broadcast-to-channel! "test-channel" data          │
│                                                         │
│  No actual WebSocket connections - API tests only      │
└─────────────────────────────────────────────────────────┘
```

**Configuration**: Servers with channel system
```clojure
;; JSON server with channels
(def test-server-json
  (server/start-server!
    {:port 3002
     :wire-format :json
     :channels {:auto-create true
                :default-config {:max-subscribers 100
                                :message-retention 5}}}))

;; Simulate connections (no actual WebSocket)
(def conn1 "conn-test-001")
(channels/subscribe! conn1 "test-channel")
```

**Key Difference**: Uses string IDs to simulate connections, not real WebSocket channels

---

## Test Organization

### Phase 1: Telemere-lite Core Tests (6 tests)

**Purpose**: Validate embedded telemetry/logging system

1. **test_official_api.bb** - Official API Compatibility
   - Signal foundation (signal! as core macro)
   - Log levels (debug, info, warn, error)
   - Error handling with context
   - Location capture (file, line, namespace)
   - Telemetry enable/disable

2. **test_simple_filtering.bb** - Simple Filtering
   - Basic event filtering
   - Level-based filtering

3. **test_filtering_api.bb** - Advanced Filtering API
   - Complex filtering rules
   - Filter composition

4. **test_event_correlation.bb** - Event Correlation
   - Trace IDs and spans
   - Event correlation across operations

5. **test_routing.bb** - Message Routing
   - Handler routing
   - Event dispatch

6. **test_timbre_functions.bb** - Timbre Functions
   - Timbre API compatibility
   - Migration helpers

### Phase 2: Async Implementation Tests (2 tests)

**Purpose**: Validate async telemetry performance

7. **test_async_simple.bb** - Simple Async Implementation
   - Basic async event handling
   - Queue management

8. **test_async_performance.bb** - Async Performance Benchmarks
   - Performance metrics
   - Claims 24.5x improvement over synchronous

### Phase 3: WebSocket Foundation Tests (3 tests + BB Client Suite)

**Purpose**: Core sente-lite WebSocket functionality

9. **test_websocket_foundation.bb** - WebSocket Foundation
   - **Raw http-kit WebSocket server** (port 3000)
   - Connection lifecycle (on-open, on-receive, on-close, on-error)
   - JSON message handling
   - Connection tracking
   - Echo server pattern
   - Browser test client included (HTML/JavaScript)

10. **test_server_foundation.bb** - Server Foundation
    - Uses `sente-lite.server-simple` namespace
    - Server startup/shutdown lifecycle
    - Wire format system (pluggable)
    - Health and stats endpoints
    - Connection state management
    - Telemetry integration
    - Monitoring (5-second test)

11. **test_channel_integration.bb** - Channel Integration
    - Uses full `sente-lite.server` namespace
    - **Multiple servers with different wire formats**:
      - Port 3002: JSON format
      - Port 3003: EDN format
      - Port 3004: Transit+JSON format
    - **Channel system**:
      - Channel creation (auto and manual)
      - Subscription management
      - Message publishing with delivery tracking
      - Broadcast to channel subscribers
    - **RPC patterns**:
      - Request/response with timeout
      - Request ID tracking
      - Response correlation
    - **Connection management**:
      - Connection-to-channel mapping
      - Subscription listing per connection
      - Unsubscribe (single and all)
    - **Cleanup operations**:
      - Expired RPC request cleanup
      - Graceful shutdown
    - Error handling validation

### Phase 3: BB Client Tests (NEW - 2025-10-26) ✅

**Purpose**: BB-to-BB WebSocket client-server testing with native Babashka client

12. **bb_client_tests/01_startup_test.bb** - Server Startup
    - Server lifecycle validation
    - Port binding verification
    - Telemetry initialization

13. **bb_client_tests/02_connection_test.bb** - Connection Lifecycle
    - WebSocket handshake
    - Connection tracking
    - Clean disconnection
    - Uses `babashka.http-client.websocket`

14. **bb_client_tests/03_message_echo.bb** - Bidirectional Communication ✅
    - Client → Server message delivery
    - Server → Client message delivery
    - Message echo validation
    - JSON serialization/deserialization
    - **PASSING** after fixing:
      - Server: `:on-message` → `:on-receive` (http-kit callback name)
      - Client: Refactored to native `babashka.http-client.websocket`

15. **minimal_ws_echo.bb** - Minimal Echo Server
    - Simplest possible WebSocket echo
    - Used for isolating WebSocket bugs
    - Validates http-kit `:on-receive` callback

16. **minimal_ws_client.bb** - Minimal Native Client
    - Simplest possible Babashka WebSocket client
    - Uses `babashka.http-client.websocket` (native)
    - No Java interop or workarounds needed

### Additional Test (Not in Runner)

12. **test_wire_formats.bb** - Wire Format System
    - **Format testing**:
      - JSON (lossy)
      - EDN (lossless)
      - Transit+JSON (lossless)
      - Transit+JSON+Bencode (nREPL tunneling)
    - **Round-trip validation**:
      - Comprehensive Clojure types
      - Byte array handling
      - Complex nested data
    - **Custom format registration**:
      - Protocol implementation (IWireFormat)
      - Example: Uppercase JSON format
    - **Performance benchmarks**:
      - 1000 iterations with simple data
      - Format comparison (size and speed)
    - **Format registry**:
      - Available formats listing
      - Format info (name, content-type, binary?)
    - Error handling

---

## Test Coverage Analysis

### ✅ What's Tested

**Infrastructure:**
- ✅ Telemetry system (telemere-lite)
- ✅ Async event handling
- ✅ Raw WebSocket with http-kit
- ✅ Wire format system (JSON, EDN, Transit)
- ✅ Server lifecycle management

**sente-lite Core Features:**
- ✅ Channel creation and management
- ✅ Pub/sub messaging
- ✅ RPC request/response patterns
- ✅ Connection tracking
- ✅ Subscription management
- ✅ Message broadcasting
- ✅ Multi-format server support
- ✅ Health and stats endpoints

**Quality:**
- ✅ Error handling
- ✅ Cleanup operations
- ✅ Performance benchmarks
- ✅ Custom format extensibility

### ⚠️ What's Missing/Incomplete

**Client-Side Testing:**
- ✅ BB-to-BB WebSocket client tests (COMPLETE - 2025-10-26)
- ✅ Connection/reconnection logic (basic tests PASSING)
- ✅ Client-side message handling (bidirectional WORKING)
- ❌ No browser WebSocket client tests (beyond manual HTML)
- ❌ No client heartbeat/keepalive
- ❌ No automatic reconnection with backoff

**Protocol Features:**
- ❌ No WebSocket handshake validation
- ❌ No authentication/authorization
- ❌ No session management
- ❌ No CSRF protection
- ❌ No connection handshake (client ID negotiation)

**Real-World Scenarios:**
- ❌ No multi-client stress testing
- ❌ No connection drop/reconnection tests
- ❌ No message ordering guarantees
- ❌ No message delivery guarantees
- ❌ No backpressure handling

**Integration:**
- ❌ No nREPL integration tests (despite bencode format)
- ❌ No Transit multiplexer tests
- ❌ No cross-format compatibility tests

**Sente API Compatibility:**
- ❌ No Sente API surface comparison
- ❌ No event format validation ([:event-id {:data}])
- ❌ No callback registry tests
- ❌ No user ID routing

---

## Test Architecture Insights

### Current Implementation Status

Based on test analysis, the following appears **IMPLEMENTED**:

1. **sente-lite.server-simple** (test_server_foundation.bb:6)
   - Basic server with wire formats
   - Health/stats endpoints
   - No channel system

2. **sente-lite.server** (test_channel_integration.bb:6)
   - Full server with channels
   - `start-server!`, `stop-server!`, `get-server-stats`
   - `broadcast-to-channel!`

3. **sente-lite.channels** (test_channel_integration.bb:7)
   - `create-channel!`, `list-channels`
   - `subscribe!`, `unsubscribe!`, `unsubscribe-all!`
   - `publish!`
   - `send-rpc-request!`, `send-rpc-response!`
   - `cleanup-expired-rpc-requests!`
   - `get-subscriptions`

4. **sente-lite.wire-format** (test_wire_formats.bb:6)
   - `IWireFormat` protocol
   - `serialize`, `deserialize`, `content-type`, `format-name`, `binary?`
   - `get-format`, `register-format!`, `available-formats`
   - `format-info`, `round-trip-test`, `compare-formats`

### What Tests Reveal About Implementation

**Multi-server support:**
- Tests start 3 servers simultaneously (ports 3002, 3003, 3004)
- Each with different wire format
- Suggests server state is instance-based, not global

**Channel system design:**
- Channels are global (not per-server)
- Auto-creation on first publish
- Max subscribers and message retention configurable
- RPC request tracking with timeouts

**Connection model:**
- String-based connection IDs
- Connection lifecycle managed separately from channels
- Connection-to-channel many-to-many relationship

**Wire format philosophy:**
- Pluggable via protocol
- Format selected per-server at startup
- No runtime format switching

---

## Test Gaps for Sente Compatibility

To achieve ~85% Sente API compatibility, tests should validate:

### Required Use Cases

1. **Client-Server Handshake**
   - Client connects, receives unique ID
   - Client sends handshake event
   - Server acknowledges with `:chsk/handshake`

2. **Event Format**
   - Events as `[:event-id {:data}]`
   - Server receives and routes events
   - Client receives events via callback

3. **User ID Routing**
   - Multiple clients per user ID
   - `send!` to specific user (all their connections)
   - User presence tracking

4. **Callback Registry**
   - Client registers event handlers
   - `:ch-recv` returns callback registry (not channel)
   - Multiple handlers per event type

5. **Connection Management**
   - Automatic reconnection with backoff
   - Heartbeat/keepalive (ping/pong)
   - Connection state tracking (:connecting, :open, :closed)

6. **Message Acknowledgment**
   - Optional message IDs
   - ACK/NACK responses
   - Reply callbacks

---

## Test Execution

### Run All Tests
```bash
./test/scripts/run_all_tests.bb
```

### Run Individual Tests
```bash
bb test/scripts/test_channel_integration.bb
bb test/scripts/test_wire_formats.bb
```

### Current Test Results
According to run_all_tests.bb success message:
- All 11 tests passing
- Claims "Production-ready"
- Claims "Full-featured sente-lite implementation"

**Note**: test_wire_formats.bb is NOT in the test runner!

---

## Recommendations

### Immediate Actions

1. **Add test_wire_formats.bb to run_all_tests.bb**
   - Critical test being skipped
   - Validates core serialization

2. **Run all tests to verify current state**
   - Validate claimed production-readiness
   - Check for any failures

3. **Document actual Sente API surface**
   - What's implemented vs planned
   - Current API vs Sente API

### Next Testing Priorities

1. **Client-side testing**
   - Browser WebSocket client
   - Connection lifecycle
   - Event handling

2. **Sente compatibility validation**
   - Event format tests
   - User routing tests
   - Callback registry tests

3. **Integration testing**
   - nREPL over WebSocket
   - Transit multiplexer
   - Multi-client scenarios

4. **Stress testing**
   - Many concurrent connections
   - High message throughput
   - Memory leak detection

---

## Phase 3 Implementation Notes (2025-10-26)

### Critical Bugs Fixed

**Bug #1: http-kit Callback Name Mismatch**
- **Issue**: `src/sente_lite/server.cljc:272` used `:on-message` instead of `:on-receive`
- **Impact**: Server never received messages from clients
- **Fix**: Changed to `:on-receive` (http-kit's actual callback name)
- **Reference**: `doc/issues/websocket-message-flow-issue.md`

**Bug #2: Java 11 WebSocket Receive Counter**
- **Issue**: Java 11 WebSocket API starts with receive counter at 0
- **Impact**: Client never invoked onText callback, couldn't receive messages
- **Workaround**: Added `.request(Long/MAX_VALUE)` in onOpen
- **Better Solution**: Refactored to `babashka.http-client.websocket` (native)

### Client Refactor: Native Babashka WebSocket

**Before**: Java 11 WebSocket API
- Required Java interop (`java.net.http.WebSocket`)
- Needed `.request()` workaround
- More complex code (~94 lines)

**After**: `babashka.http-client.websocket`
- Native Babashka implementation
- No workarounds needed
- Simpler, cleaner code (~60 lines)
- Aligns with project philosophy: "Native capability first"

**Files Changed**:
- `test/scripts/bb_client_tests/ws_client.clj` - Refactored to native
- `test/scripts/minimal_ws_client.bb` - New minimal native client
- `test/scripts/minimal_ws_echo.bb` - Minimal test server

### Lessons Learned

1. **Always verify API callback names** - Don't assume `:on-message` is universal
2. **Native platforms first** - Babashka's native WebSocket is simpler than Java interop
3. **Minimal reproduction tests** - Created `minimal_ws_*.bb` to isolate issues
4. **Documentation matters** - Java 11 WebSocket's `.request()` requirement is poorly documented

---

## File References

### Test Files
- `test/scripts/run_all_tests.bb` - Main test runner
- `test/scripts/test_websocket_foundation.bb` - Raw WebSocket server
- `test/scripts/test_server_foundation.bb` - Server lifecycle
- `test/scripts/test_channel_integration.bb` - Channel system (most comprehensive)
- `test/scripts/test_wire_formats.bb` - Serialization (NOT in runner)

### Implementation Files (Referenced by Tests)
- `src/sente_lite/server.cljc` - Full server with channels
- `src/sente_lite/server_simple.cljc` - Basic server
- `src/sente_lite/channels.cljc` - Channel system
- `src/sente_lite/wire_format.cljc` - Serialization

### Documentation
- `doc/plan.md` - Overall implementation plan
- `doc/http2-investigation-2025-10.md` - HTTP/2 decision
