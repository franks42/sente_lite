# Babashka-to-Babashka Client Testing Plan

**Created**: 2025-10-26
**Status**: NOT STARTED
**Priority**: CRITICAL - Core WebSocket functionality is currently untested

## Current Reality

### What Actually Works
- ✅ **telemere-lite core**: 6/6 tests passing
- ✅ **Async telemetry**: 2/2 tests passing

### What Has NEVER Been Tested
- ❌ **sente-lite WebSocket server**: 70KB of code, NEVER executed
- ❌ **Connection lifecycle**: Start/stop/reconnect - untested
- ❌ **Message routing**: Event dispatch - untested
- ❌ **Channel system**: Pub/sub - untested
- ❌ **Wire formats**: JSON/EDN/Transit - untested
- ❌ **Client implementation**: Does not exist (no BB client, no browser client)

### Existing Test Failures
- `test_websocket_foundation.bb` - Classpath error, never ran
- `test_server_foundation.bb` - Unknown state, not verified
- `test_channel_integration.bb` - Was hanging, not re-tested

## The Plan: BB-to-BB Automated Testing

### Why BB-to-BB First?

1. **Real use case**: BB-to-BB communication is a documented project goal
2. **Fully automated**: No manual browser interaction needed
3. **CI/CD ready**: Can run in automated pipelines
4. **Easier debugging**: Single runtime (BB), simpler stack traces
5. **Fast iteration**: Quick test/debug cycles

### Implementation Phases

#### Phase 1: Verify Server Starts (FOUNDATION)
**Goal**: Confirm the server code actually runs without crashing

**Tasks**:
1. Create minimal test script that starts sente-lite server
2. Verify server accepts configuration
3. Verify server binds to port
4. Verify server can be stopped gracefully
5. Handle any startup errors/missing dependencies

**Expected issues**:
- Missing dependencies in bb.edn
- Namespace loading failures
- Configuration errors
- Port binding issues

**Success criteria**: Server starts and stops cleanly

#### Phase 2: Implement BB WebSocket Client
**Goal**: Create a Babashka WebSocket client for testing

**Implementation**:
```clojure
;; Use babashka.http-client.websocket
(require '[babashka.http-client.websocket :as ws])

(def client-conn
  (ws/websocket "ws://localhost:3000"
                {:on-open (fn [ws] (println "Connected"))
                 :on-message (fn [ws msg] (println "Received:" msg))
                 :on-close (fn [ws status reason] (println "Closed"))
                 :on-error (fn [ws error] (println "Error:" error))}))
```

**Tasks**:
1. Create `src/sente_lite/client_bb.cljc` - BB WebSocket client
2. Implement connection lifecycle
3. Implement message send/receive
4. Implement reconnection logic
5. Match sente-lite API patterns

**Success criteria**: Client connects and disconnects cleanly

#### Phase 3: Basic Connection Tests
**Goal**: Verify connection lifecycle works

**Test scenarios**:
1. **Connect/Disconnect**
   - Client connects to server
   - Server accepts connection
   - Client disconnects gracefully
   - Server handles disconnection

2. **Connection State**
   - Client tracks connection state (:connecting, :open, :closed)
   - Server tracks active connections
   - Both handle state transitions correctly

3. **Multiple Connections**
   - Multiple clients connect to same server
   - Server tracks all connections
   - Disconnection of one doesn't affect others

**Success criteria**: All connection lifecycle tests pass

#### Phase 4: Message Exchange Tests
**Goal**: Verify basic message send/receive works

**Test scenarios**:
1. **Client → Server**
   - Client sends message
   - Server receives message
   - Server can extract event ID and data

2. **Server → Client**
   - Server sends message
   - Client receives message
   - Client can extract event ID and data

3. **Bidirectional**
   - Client sends, server replies
   - Server sends, client acknowledges
   - Both directions work simultaneously

4. **Wire Format Testing**
   - Test with JSON
   - Test with EDN
   - Test with Transit+JSON
   - Verify format negotiation

**Success criteria**: All message exchange tests pass

#### Phase 5: Channel System Tests
**Goal**: Verify pub/sub messaging works

**Test scenarios**:
1. **Basic Pub/Sub**
   - Client subscribes to channel
   - Server publishes to channel
   - Client receives publication

2. **Multiple Subscribers**
   - Multiple clients subscribe to same channel
   - Server publishes once
   - All clients receive message

3. **Selective Subscription**
   - Client1 subscribes to channel-A
   - Client2 subscribes to channel-B
   - Publications go to correct subscribers only

4. **Unsubscribe**
   - Client unsubscribes from channel
   - No longer receives publications
   - Can re-subscribe later

**Success criteria**: All pub/sub tests pass

#### Phase 6: RPC Tests
**Goal**: Verify request/reply pattern works

**Test scenarios**:
1. **Basic RPC**
   - Client sends request with reply-fn
   - Server processes and replies
   - Client receives reply via callback

2. **Timeout Handling**
   - Client sends request with timeout
   - Server doesn't reply
   - Client triggers timeout handler

3. **Error Handling**
   - Server processes request, returns error
   - Client receives error response
   - Error is properly formatted

**Success criteria**: All RPC tests pass

#### Phase 7: Stress & Edge Cases
**Goal**: Verify robustness under load

**Test scenarios**:
1. **High Message Volume**
   - Send 1000+ messages rapidly
   - Verify all are received
   - Verify no memory leaks

2. **Large Messages**
   - Send messages approaching size limits
   - Verify proper handling or rejection

3. **Connection Interruption**
   - Force disconnect during message send
   - Verify reconnection works
   - Verify message buffering/retry

4. **Concurrent Operations**
   - Multiple threads sending simultaneously
   - Subscribe/unsubscribe during publications
   - Start/stop server during connections

**Success criteria**: All stress tests pass without crashes

## Test Organization

### New Test Files Structure
```
test/scripts/
├── bb_client_tests/
│   ├── 01_server_startup.bb          # Phase 1: Can we start the server?
│   ├── 02_client_connection.bb       # Phase 2 & 3: Client connects
│   ├── 03_message_exchange.bb        # Phase 4: Basic messaging
│   ├── 04_wire_formats.bb            # Phase 4: JSON/EDN/Transit
│   ├── 05_pubsub_basic.bb            # Phase 5: Channels
│   ├── 06_pubsub_multi.bb            # Phase 5: Multiple subscribers
│   ├── 07_rpc_basic.bb               # Phase 6: Request/reply
│   ├── 08_rpc_errors.bb              # Phase 6: Error handling
│   ├── 09_stress_volume.bb           # Phase 7: High load
│   └── 10_stress_reconnect.bb        # Phase 7: Resilience
└── run_bb_client_tests.bb            # Test suite runner
```

### Test Execution
```bash
# Run full BB-to-BB test suite
./test/scripts/run_bb_client_tests.bb

# Run specific phase
bb test/scripts/bb_client_tests/01_server_startup.bb
```

## After BB-to-BB Tests Pass

### Browser Testing with Playwright

**Why wait for browser testing**:
- Browser testing is harder to automate
- Browser-specific issues are separate from core functionality
- Want core protocol working before adding browser complexity

**Playwright test structure**:
```javascript
// test/playwright/websocket_basic.spec.js
test('sente-lite browser client connects', async ({ page }) => {
  await page.goto('http://localhost:3000/test.html');
  // Wait for WebSocket connection
  const connected = await page.evaluate(() => {
    return window.senteClient.state === 'open';
  });
  expect(connected).toBe(true);
});
```

**Browser test scenarios**:
1. Connection from Chrome/Firefox/Safari
2. Browser-specific WebSocket behavior
3. Page refresh handling
4. Multiple tabs from same browser
5. Cross-origin scenarios (if applicable)

## Success Metrics

### Phase 1-3: Foundation (CRITICAL)
- ❌ Server starts without errors
- ❌ Client connects to server
- ❌ Connection lifecycle works

**Until these pass, nothing else matters.**

### Phase 4-5: Core Functionality
- ❌ Messages are exchanged correctly
- ❌ Wire formats work (JSON/EDN/Transit)
- ❌ Pub/sub channels work

### Phase 6-7: Production Ready
- ❌ RPC pattern works
- ❌ Handles load and stress
- ❌ Recovers from errors

### Browser Testing
- ❌ Playwright tests pass
- ❌ Manual browser testing works
- ❌ Cross-browser compatibility verified

## Current Blockers

1. **No client implementation exists** - Must build BB client
2. **Server has never been tested** - May have bugs or missing dependencies
3. **Wire format code untested** - May not work as designed
4. **Channel system untested** - May have logic errors

## Notes

- **Do not assume anything works** until tests prove it
- **Do not write optimistic documentation** for untested code
- **Do not claim "production-ready"** until all tests pass
- **Focus on one phase at a time** - don't skip ahead

## References

- `src/sente_lite/server.cljc` - Server implementation (untested)
- `src/sente_lite/channels.cljc` - Channel system (untested)
- `src/sente_lite/wire_format.cljc` - Wire formats (untested)
- `babashka.http-client.websocket` - BB WebSocket client API
