# Future Enhancements

## Multi-Process Testing (Priority: High)

**Current State:**
Tests run server + client in **same BB process**, communicating via localhost WebSocket:
```
┌─────────────────────────────┐
│  Single BB Process          │
│  ┌────────┐    ┌─────────┐ │
│  │ Server │◄──►│ Client  │ │
│  └────────┘    └─────────┘ │
└─────────────────────────────┘
```

**Desired State:**
Tests run server + clients in **separate BB processes** (true distributed system):
```
┌──────────────┐           ┌──────────────┐
│ BB Process 1 │           │ BB Process 2 │
│  ┌────────┐  │           │  ┌─────────┐ │
│  │ Server │  │◄─────────►│  │ Client1 │ │
│  └────────┘  │           │  └─────────┘ │
└──────────────┘           └──────────────┘
       ▲
       │ WebSocket
       │
┌──────┴───────┐           ┌──────────────┐
│              │           │ BB Process 3 │
│              │◄─────────►│  ┌─────────┐ │
│              │           │  │ Client2 │ │
│              │           │  └─────────┘ │
└──────────────┘           └──────────────┘
```

### Benefits

1. **Real-world validation**: Tests actual distributed behavior
2. **Process isolation**: Validates no shared state/memory
3. **Concurrency testing**: Multiple clients from different processes
4. **Failure scenarios**: Kill/restart individual processes
5. **Performance testing**: Measure actual network overhead
6. **Production parity**: Matches how sente-lite will actually be used

### Test Scenarios

#### Scenario 1: Basic Multi-Process Connection
**Setup:** 1 server BB + 2 client BBs
**Test:**
- Server starts on port (fixed or ephemeral)
- Client1 connects, subscribes to channel A
- Client2 connects, subscribes to channel B
- Client1 publishes to channel A → only Client1 receives
- Client2 publishes to channel B → only Client2 receives

#### Scenario 2: Concurrent Client Startup
**Setup:** 1 server BB + 10 client BBs starting simultaneously
**Test:**
- Server starts on known port
- All 10 clients launched concurrently (bash `&` or parallel)
- All clients connect within 1 second
- All clients subscribe to "announcements"
- Server broadcasts to "announcements" → all 10 receive

#### Scenario 3: Client Reconnection (Server Restart)
**Setup:** 1 server BB + 3 client BBs
**Test:**
- All clients connected and subscribed
- Kill server process
- Clients detect connection loss → enter :reconnecting
- Restart server process (new PID)
- Clients auto-reconnect
- Subscriptions restored
- Messages flow again

#### Scenario 4: Client Process Failure
**Setup:** 1 server BB + 3 client BBs
**Test:**
- All clients connected
- Kill Client2 process (SIGKILL - ungraceful)
- Server detects via heartbeat timeout
- Server removes Client2 from subscriptions
- Client1 and Client3 unaffected
- Publish to shared channel → Client1 + Client3 receive, no errors

#### Scenario 5: High-Frequency Pub/Sub
**Setup:** 1 server BB + 20 client BBs
**Test:**
- All clients subscribe to "events"
- Each client publishes 100 messages/second for 10 seconds
- Total: 20,000 messages
- Validate: All clients receive all broadcasts
- Measure: Latency, throughput, memory usage

#### Scenario 6: Ephemeral Port Discovery
**Setup:** 1 server BB (ephemeral port) + 5 client BBs
**Test:**
- Server starts with port 0
- Server writes actual port to file: `/tmp/server-port`
- Clients read port from file
- All clients connect to discovered port
- Validates: Service discovery pattern

### Implementation Approach

#### Option A: Shell Script Orchestration
```bash
#!/usr/bin/env bash
# test_multi_process.sh

# Start server in background
bb server.bb > /tmp/server.log 2>&1 &
SERVER_PID=$!
sleep 1  # Wait for startup

# Get actual port (if ephemeral)
PORT=$(bb -e "(require '[sente-lite.server :as s]) (s/get-server-port)")

# Start multiple clients
for i in {1..5}; do
  bb client.bb $PORT $i > /tmp/client-$i.log 2>&1 &
  CLIENT_PIDS[$i]=$!
done

# Wait for test completion
sleep 10

# Collect results
for i in {1..5}; do
  wait ${CLIENT_PIDS[$i]}
done

# Cleanup
kill $SERVER_PID
```

#### Option B: BB Parent Process
```clojure
#!/usr/bin/env bb
;; test_multi_process.bb

(require '[babashka.process :as proc])

;; Start server
(def server (proc/process ["bb" "server.bb"] {:out :inherit}))
(Thread/sleep 1000)

;; Start clients
(def clients
  (for [i (range 5)]
    (proc/process ["bb" "client.bb" (str i)] {:out :inherit})))

;; Wait for completion
(Thread/sleep 10000)

;; Cleanup
(run! proc/destroy clients)
(proc/destroy server)
```

#### Option C: Test Framework Integration
```clojure
;; test/scripts/bb_client_tests/10_multi_process.bb

(deftest multi-process-pubsub
  (with-server-process {:port 0}
    (let [port (get-server-port-from-file)]
      (with-client-processes 5 {:port port}
        (publish-to-all {:type "test"})
        (assert-all-received {:timeout 5000})))))
```

### Files to Create

1. **test/scripts/bb_client_tests/server_process.bb** - Standalone server launcher
2. **test/scripts/bb_client_tests/client_process.bb** - Standalone client launcher
3. **test/scripts/bb_client_tests/10_multi_process_basic.bb** - Basic multi-process test
4. **test/scripts/bb_client_tests/11_multi_process_concurrent.bb** - Concurrent clients
5. **test/scripts/bb_client_tests/12_multi_process_resilience.bb** - Failure scenarios
6. **test/scripts/bb_client_tests/multi_process_helpers.clj** - Shared utilities

### Technical Considerations

**Inter-Process Communication:**
- Server writes port to file: `/tmp/sente-lite-server-port-{PID}`
- Clients read port from file before connecting
- Use file locking if concurrent access

**Process Management:**
- Use `babashka.process` for spawning/managing
- Capture stdout/stderr to temp files
- Ensure cleanup on test failure (try/finally)
- Use PID files for tracking

**Synchronization:**
- Polling for server readiness (port file exists)
- Clients signal ready via file: `/tmp/client-{N}-ready`
- Test coordinator waits for all ready signals

**Result Collection:**
- Each process writes results to file
- Coordinator aggregates and validates
- JSON format for structured results

**Timeouts:**
- Server startup: 5 seconds max
- Client connection: 2 seconds max
- Test execution: 30 seconds max
- Cleanup: 5 seconds max

### Success Criteria

- ✅ All scenarios pass consistently (10 runs)
- ✅ No shared state between processes
- ✅ Clean startup/shutdown (no zombie processes)
- ✅ Proper error handling (process crashes)
- ✅ Performance acceptable (< 10ms latency)
- ✅ Memory stable (no leaks over 1000 messages)
- ✅ Integration with run_all_tests.bb

### Priority Ranking

1. **HIGH - Scenario 1**: Basic multi-process (foundation)
2. **HIGH - Scenario 6**: Ephemeral port discovery (validates new feature)
3. **MEDIUM - Scenario 3**: Reconnection across processes
4. **MEDIUM - Scenario 2**: Concurrent startup
5. **LOW - Scenario 4**: Process failure handling
6. **LOW - Scenario 5**: Performance/stress testing

### Estimated Effort

- Infrastructure (process helpers): 4-6 hours
- Scenario 1 (basic): 2-3 hours
- Scenario 6 (ephemeral): 1-2 hours
- Scenarios 2-5: 2-3 hours each
- Documentation: 1-2 hours
- **Total**: 15-25 hours

---

## Other Future Enhancements

### Authentication & Authorization
- WebSocket handshake validation
- Token-based auth
- User ID routing with permissions
- Session management

### Protocol Features
- CSRF protection
- Connection ID negotiation
- Message ordering guarantees
- Delivery guarantees (at-least-once)
- Backpressure handling

### Browser Client
- Official browser JavaScript client
- Same API as BB client
- Auto-reconnection
- State management

### Performance
- Connection pooling
- Message batching
- Compression (deflate/gzip)
- Binary format (MessagePack, Transit+msgpack)

### Monitoring
- Prometheus metrics
- Health check endpoints
- Connection metrics
- Message flow tracing

### nREPL Integration
- Transit multiplexer tests
- nREPL over WebSocket E2E tests
- Bencode format validation
