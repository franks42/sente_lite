# Sente-lite Test Use Cases Summary

## Overview

Current test suite organized in 3 phases with 11+ test scripts covering telemetry, async, and WebSocket functionality.

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

### Phase 3: WebSocket Foundation Tests (3 tests)

**Purpose**: Core sente-lite WebSocket functionality

9. **test_websocket_foundation.bb** - WebSocket Foundation
   - **Raw http-kit WebSocket server** (port 3000)
   - Connection lifecycle (on-open, on-message, on-close, on-error)
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
- ❌ No browser WebSocket client tests
- ❌ No connection/reconnection logic tests
- ❌ No client-side message handling
- ❌ No client heartbeat/keepalive

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
