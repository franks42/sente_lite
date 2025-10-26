# Ring Jetty9 Adapter Migration Plan

**Purpose:** Test info.sunng/ring-jetty9-adapter in our current WebSocket server implementation to ensure compatibility with Babashka and evaluate HTTP/2 capabilities.

**Goal:** Use the same tests as we currently have while gaining HTTP/2 support.

---

## Phase 1: Environment Setup

### 1.1 Create bb.edn Configuration
```clojure
;; bb.edn
{:paths ["src"]
 :deps {info.sunng/ring-jetty9-adapter {:mvn/version "0.37.6"}
        ring/ring-core {:mvn/version "1.9.6"}
        ;; Keep existing deps for comparison
        http-kit/http-kit {:mvn/version "2.8.0"}}
 :min-bb-version "1.1.0"}
```

### 1.2 Verify Dependency Resolution
- Test `bb deps prep` works without errors
- Confirm all dependencies download correctly
- Check babashka can load the jetty9 namespace

## Phase 2: Server Implementation

### 2.1 Create Jetty9 Server Module
**File:** `src/sente_lite/server_jetty9.cljc`
- Copy structure from existing `server.cljc`
- Replace http-kit calls with jetty9 equivalents
- Maintain identical API surface for compatibility
- Support both HTTP/1.1 + WebSocket and HTTP/2 modes

### 2.2 Key API Translations
```clojure
;; http-kit → jetty9 mappings
http/run-server → jetty/run-jetty
http/send! → websocket send via jetty
http/on-close → jetty websocket handlers
http/on-receive → jetty message handlers
```

### 2.3 Configuration Options
- Map http-kit's `:max-ws` to jetty9 equivalent
- Add HTTP/2 configuration options
- Maintain backward compatibility for existing configs

## Phase 3: Compatibility Testing

### 3.1 Create Jetty9 Test Runner
**File:** `test_jetty9_compatibility.bb`
- Duplicate existing test structure
- Run identical test scenarios with both servers
- Compare results side-by-side

### 3.2 Test Matrix
```clojure
;; Test scenarios for both http-kit and jetty9
test-scenarios:
- Basic WebSocket connection
- Wire format serialization (JSON, EDN, Transit)
- Channel creation and subscription
- Message publishing and delivery
- RPC request/response patterns
- Connection lifecycle management
- Error handling and cleanup
- Large message handling
- Concurrent connections
```

### 3.3 Reuse Existing Tests
- `test_server_foundation.bb` → Adapt for jetty9
- `test_channel_integration.bb` → Run with both servers
- `test_wire_formats.bb` → Verify format compatibility

## Phase 4: HTTP/2 Specific Testing

### 4.1 HTTP/2 Feature Tests
**File:** `test_jetty9_http2.bb`
- Test HTTP/2 connection establishment
- Verify `SETTINGS_MAX_FRAME_SIZE` negotiation
- Test multiplexed streams
- Compare frame size behavior vs WebSocket

### 4.2 Server-Sent Events Testing
- Implement SSE endpoint for comparison
- Test bidirectional communication (SSE + fetch)
- Compare latency vs WebSocket

## Phase 5: Performance Comparison

### 5.1 Benchmark Script
**File:** `benchmark_servers.bb`
- Message throughput comparison
- Connection establishment time
- Memory usage patterns
- CPU utilization under load

### 5.2 Specific Metrics
- WebSocket messages/second
- HTTP/2 streams/second
- Large message handling (approaching size limits)
- Concurrent connection scalability

## Phase 6: Integration Validation

### 6.1 Wire Format Compatibility
- Ensure all existing wire formats work identically
- Test message serialization/deserialization
- Verify byte array handling works correctly

### 6.2 Channel System Integration
- Test channel creation, subscription, publishing
- Verify RPC patterns work unchanged
- Confirm telemetry integration functions

### 6.3 Client Compatibility
- Test with existing scittle client code
- Verify WebSocket connections work unchanged
- Test any HTTP/2 specific client features

## Phase 7: Migration Assessment

### 7.1 API Compatibility Report
- Document any breaking changes required
- Identify configuration differences
- Note performance characteristics

### 7.2 Deployment Considerations
- Dependency size impact
- Startup time comparison
- Distribution complexity (single script vs deps)

### 7.3 Feature Matrix
```
| Feature              | http-kit | jetty9 |
|---------------------|----------|--------|
| WebSocket           | ✅       | ✅     |
| HTTP/2              | ❌       | ✅     |
| Frame Size Neg.     | ❌       | ✅     |
| Built-in to bb      | ✅       | ❌     |
| Zero Dependencies   | ✅       | ❌     |
```

## Expected Deliverables

1. **Working jetty9 server implementation** (`server_jetty9.cljc`)
2. **Compatibility test suite** (`test_jetty9_compatibility.bb`)
3. **HTTP/2 feature tests** (`test_jetty9_http2.bb`)
4. **Performance benchmarks** (`benchmark_servers.bb`)
5. **Migration guide** documenting findings and recommendations

## Success Criteria

- All existing tests pass with jetty9 implementation
- WebSocket functionality is identical to http-kit version
- HTTP/2 features demonstrate clear advantages
- Performance is comparable or better than http-kit
- Integration complexity is acceptable for project goals

## Implementation Tasks

1. Create bb.edn with ring-jetty9-adapter dependencies
2. Create jetty9-based server implementation
3. Port existing server.cljc to use jetty9 adapter
4. Create jetty9 compatibility test script
5. Test WebSocket functionality with jetty9
6. Test HTTP/2 functionality with jetty9
7. Compare performance jetty9 vs http-kit
8. Validate wire format compatibility
9. Test channel system integration
10. Document jetty9 migration findings

---

**Note:** This plan ensures we thoroughly evaluate jetty9 as a replacement while maintaining our existing functionality and gaining insight into HTTP/2 benefits.