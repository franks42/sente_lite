# Sente-Lite Modules

Modular extensions for sente-lite that provide additional functionality beyond core WebSocket communication.

---

## Module: Remote Logging via Sente

**Status**: Proposed  
**Complexity**: Low-Medium  
**Dependencies**: Trove, sente-lite core  
**Use Cases**: Distributed logging, centralized observability, multi-process debugging

### Overview

Route Trove logging messages through sente channels to centralize logs from multiple processes (browser, nbb, BB clients) to a single server.

**Example Use Case**:
```
Browser Client (Scittle)
    ↓ trove/log!
    ↓ (wrapped log-fn)
    ↓ sente channel
Server (BB)
    ↓ :telemetry/log-msg handler
    ↓ (server's log-fn)
    ↓
Centralized log file with logs from all clients
```

### Architecture

#### Design Principles

1. **Pluggable**: Works with Trove's `*log-fn*` hook
2. **Optional**: Doesn't affect existing code
3. **Composable**: Can combine with local logging
4. **Resilient**: Graceful fallback if sente unavailable
5. **Configurable**: Per-client filtering and batching

#### Core Components

**Client Side** (`sente_lite/logging/remote.cljs`):
- `make-remote-log-fn` - Wraps local log-fn to also send via sente
- Configuration: log levels, batching, fallback behavior
- Handles sente send failures gracefully

**Server Side** (`sente_lite/logging/remote.clj`):
- `:telemetry/log-msg` event handler
- Reconstructs log context with client metadata
- Routes to server's configured log-fn
- Optional aggregation/filtering

#### Wire Format

```clojure
;; Client sends
[:telemetry/log-msg 
 {:ns "my.namespace"
  :level :info
  :id :my.namespace/event-id
  :data {:key "value"}
  :timestamp 1766108726643}]

;; Server receives and processes
{:event-id :telemetry/log-msg
 :data {:ns "my.namespace"
        :level :info
        :id :my.namespace/event-id
        :data {:key "value"}
        :timestamp 1766108726643}
 :uid "conn-1766108726643-1"}
```

### Implementation Phases

#### Phase 1: MVP (Low Effort, ~50-100 LOC)

**Goal**: Proof of concept, development/debugging use

**Features**:
- Simple wrapper function
- Send all logs via sente
- Server-side handler
- No filtering or batching

**Code Sketch**:
```clojure
;; Client
(defn make-remote-log-fn [local-log-fn sente-client]
  (fn [ns coords level id lazy_]
    (local-log-fn ns coords level id lazy_)
    (try
      (sente/send! sente-client 
        [:telemetry/log-msg 
         {:ns ns :level level :id id :data (force lazy_)}])
      (catch Exception _ nil))))

;; Server
(defmethod handle-event :telemetry/log-msg
  [{:keys [data uid]}]
  (trove/log! (assoc data :client-uid uid)))
```

**Pros**:
- ✅ Quick to implement
- ✅ Useful for development
- ✅ Validates the concept

**Cons**:
- ❌ Network overhead (every log = message)
- ❌ No filtering (all logs sent)
- ❌ No batching (potential flooding)
- ❌ Not production-ready

#### Phase 2: Production-Ready (~200-300 LOC)

**Goal**: Optimized for real-world use

**Features**:
- Configurable log levels (only send warn/error by default)
- Message batching (10-50 logs per send)
- Backpressure handling (max queue size)
- Client context preservation (client-id, uid, timestamp)
- Optional local-only fallback

**Configuration**:
```clojure
;; Client setup
(def remote-log-config
  {:enabled true
   :levels #{:warn :error}           ; Only send these levels
   :batch-size 20                    ; Batch 20 logs per message
   :batch-timeout-ms 5000            ; Or send after 5 seconds
   :max-queue-size 1000              ; Drop oldest if queue full
   :fallback-to-local true})         ; Use local log-fn if sente down

(trove/set-log-fn! 
  (remote-log/make-remote-log-fn local-log-fn sente-client remote-log-config))
```

**Pros**:
- ✅ Production-ready
- ✅ Configurable filtering
- ✅ Reduced network overhead
- ✅ Handles edge cases
- ✅ Preserves client context

**Cons**:
- ⚠️ More complex code
- ⚠️ Slight latency (batching delay)
- ⚠️ Server needs to handle batch processing

#### Phase 3: Advanced Features (~500+ LOC)

**Goal**: Enterprise-grade logging infrastructure

**Features**:
- Server-side log aggregation
- Structured query support
- Time-based correlation
- Log rotation/retention policies
- Metrics/statistics
- Sampling strategies (head/tail sampling)
- Circuit breaker pattern

**Capabilities**:
```clojure
;; Server-side aggregation
(def log-aggregator
  {:enabled true
   :retention-days 7
   :rotation-size-mb 100
   :index-by [:level :id :client-uid]
   :metrics {:total-logs true :errors-per-client true}})

;; Query logs
(log-query/find-logs {:level :error :client-uid uid :since-ms 3600000})
```

### Configuration Examples

#### Development Setup
```clojure
;; Browser client - send all logs to server
(trove/set-log-fn!
  (remote-log/make-remote-log-fn 
    console-log-fn 
    sente-client
    {:enabled true
     :levels #{:trace :debug :info :warn :error}
     :batch-size 5
     :batch-timeout-ms 1000}))
```

#### Production Setup
```clojure
;; Browser client - only send errors to server
(trove/set-log-fn!
  (remote-log/make-remote-log-fn 
    console-log-fn 
    sente-client
    {:enabled true
     :levels #{:error}
     :batch-size 50
     :batch-timeout-ms 10000
     :max-queue-size 500}))

;; Server - aggregate all client logs to file
(trove/set-log-fn!
  (timbre-log/get-log-fn {:file "logs/all-clients.log"}))
```

#### Hybrid Setup
```clojure
;; Browser - local console + remote errors
(defn hybrid-log-fn [ns coords level id lazy_]
  ;; Always log locally
  (console-log-fn ns coords level id lazy_)
  ;; Also send errors to server
  (when (= level :error)
    (remote-log/send-log sente-client ns level id lazy_)))

(trove/set-log-fn! hybrid-log-fn)
```

### Pros & Cons Analysis

#### Advantages
- ✅ **Centralized observability**: All logs in one place
- ✅ **Distributed debugging**: See what all clients are doing
- ✅ **Zero code changes**: Works with existing `trove/log!` calls
- ✅ **Composable**: Can combine with local logging
- ✅ **Resilient**: Graceful fallback if sente down
- ✅ **Flexible**: Configurable filtering/batching
- ✅ **Context-aware**: Preserves client identity
- ✅ **No new dependencies**: Uses existing Trove + sente-lite

#### Disadvantages
- ⚠️ **Network overhead**: Every log = potential network message
- ⚠️ **Latency**: Batching adds delay (mitigated in Phase 2)
- ⚠️ **Ordering**: Concurrent clients may interleave logs
- ⚠️ **Server load**: Server becomes bottleneck if many clients
- ⚠️ **Message loss**: If sente connection drops, logs lost (unless buffered)
- ⚠️ **Complexity**: More moving parts to debug
- ⚠️ **Privacy**: Logs sent over network (may need encryption)

### Practical Considerations

#### When to Use
- ✅ Development: Debug multi-client issues
- ✅ Staging: Monitor pre-production behavior
- ✅ Production (selective): Error tracking, critical events
- ✅ Multi-region: Aggregate logs from distributed clients

#### When NOT to Use
- ❌ High-frequency debug logging (use local only)
- ❌ Sensitive data in logs (security concern)
- ❌ Unreliable network (logs will be lost)
- ❌ Single-client applications (unnecessary complexity)

#### Backpressure Strategies

**Option 1: Drop Oldest**
```clojure
;; When queue full, remove oldest log
;; Pro: Never blocks
;; Con: Lose historical logs
```

**Option 2: Drop Newest**
```clojure
;; When queue full, reject new log
;; Pro: Keep historical context
;; Con: Lose current events
```

**Option 3: Blocking**
```clojure
;; Wait for queue space
;; Pro: No log loss
;; Con: Can block application
```

**Recommendation**: Drop oldest with warning logged locally

#### Message Ordering

**Problem**: Concurrent clients produce interleaved logs

**Solutions**:
1. Add timestamp to each log (already in Phase 2)
2. Server-side sorting by timestamp
3. Include sequence number per client
4. Accept eventual consistency

### Integration Points

#### With Existing Sente-Lite Features

**Channels**:
- Could use pub/sub for log distribution
- Server publishes aggregated logs back to clients
- Enables real-time log dashboards

**Callbacks**:
- `:on-open` - Start sending logs
- `:on-close` - Stop sending, fall back to local
- `:on-reconnect` - Resume sending

**Error Handling**:
- Integrate with existing error handlers
- Automatic error log forwarding
- Error context preservation

### File Structure

```
src/sente_lite/logging/
├── remote.cljs          # Client-side remote logging
├── remote.clj           # Server-side handler
├── config.cljc          # Shared configuration
└── aggregator.clj       # Phase 3: Server-side aggregation

test/sente_lite/logging/
├── remote_test.cljc     # Unit tests
└── integration_test.bb  # Integration tests

examples/
└── remote-logging-demo.bb  # Working example
```

### Next Steps

1. **Validate concept**: Build Phase 1 MVP
2. **Test in development**: Use with real multi-client scenario
3. **Gather feedback**: Identify pain points
4. **Implement Phase 2**: Production features
5. **Document patterns**: Best practices guide

### Related Modules

- **Metrics Module**: Track log volume, error rates
- **Dashboard Module**: Real-time log visualization
- **Filtering Module**: Advanced log filtering/sampling
- **Storage Module**: Persistent log storage

---

## Module: nREPL-over-Sente

**Status**: Proposed  
**Complexity**: Medium  
**Dependencies**: sente-lite core, nREPL protocol  
**Use Cases**: Browser REPL, interactive development, live code evaluation

### Overview

Route nREPL protocol messages through sente channels instead of a dedicated WebSocket connection. Enables interactive REPL access to Scittle browser code through the same sente connection used for other communication.

**Example Use Case**:
```
BB Server
    ↓ nREPL request (eval code)
    ↓ sente channel
Browser (Scittle)
    ↓ nREPL handler (evaluates code)
    ↓ async responses (streaming output, results)
    ↓ sente channel
BB Server
    ↓ REPL UI receives results
```

### Key Design: Fully Asynchronous

**Critical**: nREPL protocol is inherently asynchronous. Implementation must NOT use synchronous waiting.

#### Why Async Matters

nREPL messages have:
- **Session IDs**: Track multiple concurrent sessions
- **Request IDs**: Correlate responses to requests
- **Streaming responses**: Multiple messages per request (output, values, status)
- **Out-of-order delivery**: Responses may arrive in different order than requests
- **Long-running operations**: Eval can take arbitrary time

**Wrong approach** (synchronous):
```clojure
;; ❌ DON'T DO THIS
(let [response (wait-for-nrepl-response request)]
  (process response))
```

**Right approach** (asynchronous):
```clojure
;; ✅ DO THIS
(send-nrepl-request request)
;; Later, when response arrives:
(handle-nrepl-response response)
```

### Architecture

#### Wire Format

**Request** (Client → Server):
```clojure
[:nrepl/request
 {:session-id "sess-123"
  :request-id "req-456"
  :op "eval"
  :code "(+ 1 2)"
  :ns "user"
  :timestamp 1766108726643}]
```

**Response** (Server → Client, streaming):
```clojure
;; Output chunk
[:nrepl/output
 {:session-id "sess-123"
  :request-id "req-456"
  :out "user=> "
  :timestamp 1766108726644}]

;; Value result
[:nrepl/value
 {:session-id "sess-123"
  :request-id "req-456"
  :value "3"
  :timestamp 1766108726645}]

;; Status (done, error, etc.)
[:nrepl/status
 {:session-id "sess-123"
  :request-id "req-456"
  :status [:done]
  :timestamp 1766108726646}]
```

#### Request/Response Correlation

**Client-side tracking**:
```clojure
;; Track pending requests by request-id
(def pending-requests (atom {}))

;; Send request
(let [request-id (generate-uuid)]
  (swap! pending-requests assoc request-id
    {:callback on-response
     :timeout-ms 30000
     :sent-at (now)})
  (sente/send! client [:nrepl/request 
    {:request-id request-id ...}]))

;; Handle response
(defn handle-nrepl-response [response]
  (let [{:keys [request-id]} response
        {:keys [callback]} (get @pending-requests request-id)]
    (when callback
      (callback response))
    ;; Remove when status is :done
    (when (= (:status response) [:done])
      (swap! pending-requests dissoc request-id))))
```

#### Server-side Handler

```clojure
(defmethod handle-event :nrepl/request
  [{:keys [data uid]}]
  (let [{:keys [session-id request-id op code ns]} data]
    ;; Process nREPL operation asynchronously
    (process-nrepl-async
      {:session-id session-id
       :request-id request-id
       :op op
       :code code
       :ns ns}
      ;; Callback for each response chunk
      (fn [response-chunk]
        (sente/send-to-client! uid 
          [:nrepl/output response-chunk])))))
```

### Implementation Phases

#### Phase 1: Basic nREPL-over-Sente (Medium Effort, ~300-400 LOC)

**Goal**: Proof of concept with async request/response

**Features**:
- Request/response correlation by ID
- Streaming output support
- Session management
- Basic error handling
- Timeout handling

**Code Sketch**:
```clojure
;; Client
(defn send-nrepl-request [client {:keys [op code ns] :as req}]
  (let [request-id (generate-uuid)
        promise (promise)]
    (swap! pending-requests assoc request-id
      {:promise promise
       :responses (atom [])
       :timeout-ms 30000})
    (sente/send! client [:nrepl/request 
      (assoc req :request-id request-id)])
    promise))

(defn handle-nrepl-response [response]
  (let [{:keys [request-id status]} response
        {:keys [responses]} (get @pending-requests request-id)]
    (swap! responses conj response)
    (when (= status [:done])
      (deliver (:promise (get @pending-requests request-id))
        @responses)
      (swap! pending-requests dissoc request-id))))

;; Server
(defn process-nrepl-request [session-id request-id op code ns send-response-fn]
  ;; Evaluate code asynchronously
  (future
    (try
      ;; Send output as it happens
      (send-response-fn {:request-id request-id :out "Evaluating..."})
      ;; Evaluate
      (let [result (eval-in-ns code ns)]
        ;; Send value
        (send-response-fn {:request-id request-id :value (pr-str result)})
        ;; Send done status
        (send-response-fn {:request-id request-id :status [:done]}))
      (catch Exception e
        (send-response-fn {:request-id request-id :error (str e)})
        (send-response-fn {:request-id request-id :status [:done]})))))
```

**Pros**:
- ✅ Fully asynchronous
- ✅ Handles streaming responses
- ✅ Proper request correlation
- ✅ Timeout handling

**Cons**:
- ⚠️ No session persistence
- ⚠️ No variable binding across requests
- ⚠️ Basic error handling

#### Phase 2: Full nREPL Compatibility (~500-600 LOC)

**Goal**: Complete nREPL protocol support

**Features**:
- Session persistence (variables, bindings)
- All nREPL ops (eval, load-file, complete, etc.)
- Proper error semantics
- Interrupt/cancel support
- Middleware support

**Configuration**:
```clojure
(def nrepl-config
  {:enabled true
   :session-timeout-ms 3600000    ; 1 hour
   :eval-timeout-ms 30000         ; 30 seconds
   :max-output-size 10000         ; Truncate large outputs
   :supported-ops #{:eval :load-file :complete :info}})
```

**Pros**:
- ✅ Full nREPL compatibility
- ✅ Session state across requests
- ✅ Variable persistence
- ✅ Interrupt support

**Cons**:
- ⚠️ More complex state management
- ⚠️ Potential memory issues (session state)

#### Phase 3: Advanced Features (~700+ LOC)

**Goal**: Production-grade REPL experience

**Features**:
- Debugger integration
- Breakpoint support
- Stack trace formatting
- Code completion
- Documentation lookup
- Performance profiling

### Async Patterns

#### Pattern 1: Promise-Based

```clojure
;; Client sends request and returns promise
(let [response-promise (send-nrepl-request client {:op "eval" :code "..."})]
  ;; Later, when ready
  (let [responses @response-promise]
    (process-responses responses)))
```

**Pros**: Simple, familiar pattern  
**Cons**: Still blocking on deref

#### Pattern 2: Callback-Based

```clojure
;; Client sends request with callback
(send-nrepl-request client 
  {:op "eval" :code "..."}
  {:on-output (fn [output] ...)
   :on-value (fn [value] ...)
   :on-error (fn [error] ...)
   :on-done (fn [] ...)})
```

**Pros**: Fully async, no blocking  
**Cons**: Callback hell with many requests

#### Pattern 3: Channel-Based (Recommended)

```clojure
;; Client sends request, returns channel
(let [response-ch (send-nrepl-request client {:op "eval" :code "..."})]
  ;; Listen to channel
  (go-loop []
    (when-let [response (<! response-ch)]
      (handle-response response)
      (recur))))
```

**Pros**: Composable, async-friendly, handles streaming  
**Cons**: Requires core.async (or similar)

**For sente-lite**: Use callback pattern (no core.async dependency)

#### Pattern 4: Persistent Queue in Atom (Recommended for sente-lite)

```clojure
;; Track all responses in a persistent queue
(def response-queue (atom clojure.lang.PersistentQueue/EMPTY))
(def pending-requests (atom {}))

;; Send request
(defn send-nrepl-request [client {:keys [op code ns] :as req}]
  (let [request-id (generate-uuid)]
    (swap! pending-requests assoc request-id
      {:op op
       :code code
       :ns ns
       :sent-at (now)
       :responses []})
    (sente/send! client [:nrepl/request 
      (assoc req :request-id request-id)])
    request-id))

;; Handle response - just queue it
(defn handle-nrepl-response [response]
  (let [{:keys [request-id]} response]
    ;; Add to queue
    (swap! response-queue conj response)
    ;; Update pending request
    (swap! pending-requests update-in [request-id :responses] conj response)
    ;; Cleanup when done
    (when (= (:status response) [:done])
      (swap! pending-requests dissoc request-id))))

;; Process queue asynchronously
(defn process-response-queue []
  (loop []
    (when-let [response (peek @response-queue)]
      ;; Process response
      (let [{:keys [request-id]} response
            {:keys [on-output on-value on-error on-done]} 
            (get-in @pending-requests [request-id :handlers])]
        (case (:type response)
          :output (when on-output (on-output (:out response)))
          :value (when on-value (on-value (:value response)))
          :error (when on-error (on-error (:error response)))
          :status (when on-done (on-done))))
      ;; Remove from queue
      (swap! response-queue pop)
      (recur))))

;; Start queue processor
(defn start-queue-processor []
  (future
    (loop []
      (if (empty? @response-queue)
        (do (Thread/sleep 10) (recur))
        (process-response-queue)))))

;; Usage
(let [request-id (send-nrepl-request client 
  {:op "eval" :code "(+ 1 2)"})]
  (swap! pending-requests assoc-in [request-id :handlers]
    {:on-output (fn [out] (println out))
     :on-value (fn [val] (println "Result:" val))
     :on-error (fn [err] (println "Error:" err))
     :on-done (fn [] (println "Done"))}))
```

**Pros**:
- ✅ Fully async, no blocking
- ✅ Persistent queue handles ordering
- ✅ Easy to inspect queue state
- ✅ Simple to debug (just look at atoms)
- ✅ No external dependencies
- ✅ Handles backpressure naturally
- ✅ Can pause/resume processing

**Cons**:
- ⚠️ Requires queue processor thread
- ⚠️ Slight latency (queue processing delay)
- ⚠️ Memory overhead (queue grows if processor slow)

**Best for sente-lite**: This pattern combines simplicity with full async capability. The persistent queue is transparent and debuggable, making it ideal for development.

### Comparison: All Async Patterns

| Pattern | Blocking | Complexity | Dependencies | Best For |
|---------|----------|-----------|--------------|----------|
| **Promise** | Yes (on deref) | Low | None | Simple cases |
| **Callback** | No | Medium | None | General use |
| **Channel** | No | High | core.async | Complex flows |
| **Queue** | No | Medium | None | **sente-lite** |

### Comparison: Dedicated vs. Sente

| Aspect | Dedicated WS | Sente Channel |
|--------|-------------|---------------|
| **Connections** | 2 | 1 |
| **Setup** | Custom | Automatic |
| **Reconnect** | Manual | Automatic |
| **Heartbeat** | Manual | Automatic |
| **Concurrent use** | No | Yes |
| **Resource usage** | Higher | Lower |
| **Async handling** | Required | Required |
| **Head-of-line blocking** | No | Possible |
| **Latency** | Lower | Slightly higher |

### Potential Issues & Solutions

#### Head-of-Line Blocking

**Problem**: Long-running eval blocks other messages

**Solutions**:
1. **Accept it**: For dev use, acceptable
2. **Timeout**: Cancel evals after N seconds
3. **Priority queuing**: Route non-nREPL messages first
4. **Separate channel**: Use dedicated sente channel for nREPL

**Recommendation**: Accept for Phase 1, add timeout handling

#### Session State Management

**Problem**: Session state grows unbounded

**Solutions**:
1. **TTL**: Expire sessions after inactivity
2. **Limit**: Max N sessions per client
3. **Cleanup**: Manual session cleanup
4. **Memory bounds**: Limit session size

**Recommendation**: TTL + manual cleanup

#### Concurrent Requests

**Problem**: Multiple eval requests in flight

**Solutions**:
1. **Queue**: Serialize requests
2. **Parallel**: Allow concurrent evals
3. **Limit**: Max N concurrent evals

**Recommendation**: Allow concurrent, limit to 5 per session

### Integration with sente-lite

**Lifecycle hooks**:
```clojure
;; On client connect
:on-open (fn [uid]
  (create-nrepl-session uid))

;; On client disconnect
:on-close (fn [code reason]
  (cleanup-nrepl-sessions uid))

;; On reconnect
:on-reconnect (fn [uid]
  (restore-nrepl-session uid))
```

**Error handling**:
```clojure
;; Use sente's error handling
(defmethod handle-event :nrepl/request
  [{:keys [data uid]}]
  (try
    (process-nrepl-request data uid)
    (catch Exception e
      (sente/send-to-client! uid [:nrepl/error {:error (str e)}]))))
```

### File Structure

```
src/sente_lite/nrepl/
├── handler.clj          # Server-side nREPL handler
├── client.cljs          # Client-side nREPL support
├── protocol.cljc        # Shared wire format
├── async.cljc           # Async utilities
└── session.clj          # Session management

test/sente_lite/nrepl/
├── handler_test.clj     # Server tests
├── client_test.cljs     # Client tests
└── integration_test.bb  # Integration tests

examples/
└── nrepl-over-sente-demo.bb  # Working example
```

### Bidirectional nREPL Proxy Pattern

**Advanced Architecture**: Route nREPL through sente in both directions using a proxy pattern.

#### Browser → Server Direction

```
nREPL Client (external tool)
    ↓ bencode request
    ↓
nREPL Server Proxy (BB)
    ↓ extract request message
    ↓ send via sente
    ↓
nREPL Handler (Scittle)
    ↓ eval code in browser
    ↓ send response via sente
    ↓
nREPL Server Proxy (BB)
    ↓ wrap in bencode
    ↓
nREPL Client (external tool)
    ↓ bencode response
```

**Implementation** (BB Server):
```clojure
;; nREPL server proxy handler
(defmethod handle-event :nrepl/request
  [{:keys [data uid]}]
  ;; Extract nREPL request from bencode (from external nREPL client)
  (let [{:keys [op code ns session]} data]
    ;; Forward to browser via sente
    (sente/send-to-client! uid [:nrepl/request
      {:op op :code code :ns ns :session session}])))

;; Capture response from browser
(defmethod handle-event :nrepl/response
  [{:keys [data uid]}]
  ;; Wrap in bencode and return to external nREPL client
  (let [bencode-response (bencode/encode data)]
    (send-to-nrepl-client bencode-response)))
```

#### Server → Browser Direction (Reversible)

```
nREPL Client (Scittle)
    ↓ nREPL request (no bencode)
    ↓ send via sente
    ↓
nREPL Handler (BB)
    ↓ eval code on server
    ↓ send response via sente
    ↓
nREPL Client (Scittle)
    ↓ nREPL response
```

**Implementation** (BB Server):
```clojure
;; nREPL server handler for browser requests
(defmethod handle-event :nrepl/browser-request
  [{:keys [data uid]}]
  (let [{:keys [op code ns session request-id]} data]
    ;; Evaluate on BB server
    (process-nrepl-request
      {:op op :code code :ns ns}
      ;; Send responses back to browser
      (fn [response-chunk]
        (sente/send-to-client! uid [:nrepl/response
          (assoc response-chunk :request-id request-id)])))))
```

#### Key Advantages

- ✅ **Bidirectional**: Works in both directions on same channel
- ✅ **Transparent**: Proxy is invisible to nREPL clients
- ✅ **Reuses code**: Borrow from existing scittle-nrepl-server proxy
- ✅ **No bencode in browser**: Scittle side uses pure EDN
- ✅ **Standard nREPL tools**: External tools connect normally
- ✅ **Symmetric**: Same pattern works both ways

#### Proxy Implementation Strategy

**Phase 1: Browser → Server (Proxy)**
```clojure
;; src/sente_lite/nrepl/proxy.clj
(defn start-nrepl-proxy-server [sente-send-fn]
  ;; Start standard nREPL server
  ;; Intercept requests, forward to browser via sente
  ;; Capture responses, wrap in bencode
  )
```

**Phase 2: Server → Browser (Reverse)**
```clojure
;; src/sente_lite/nrepl/reverse_proxy.clj
(defn handle-browser-nrepl-request [request uid sente-send-fn]
  ;; Process nREPL request on server
  ;; Send responses back to browser
  )
```

#### Code Reuse from Existing Implementation

The existing scittle-nrepl-server proxy already has:
- Bencode encoding/decoding
- Request/response correlation
- Session management
- Error handling

**Files to reference**:
- `dev/scittle-demo/nrepl-server-proxy.clj` (or similar)
- Extract bencode handling logic
- Adapt to sente message format

#### Wire Format

**Browser → Server (via proxy)**:
```clojure
;; External nREPL client sends bencode
;; Proxy extracts and sends to browser
[:nrepl/request
 {:session-id "sess-123"
  :request-id "req-456"
  :op "eval"
  :code "(+ 1 2)"
  :ns "user"}]
```

**Server → Browser (reverse)**:
```clojure
;; Browser sends nREPL request directly
[:nrepl/browser-request
 {:session-id "sess-789"
  :request-id "req-012"
  :op "eval"
  :code "(js/alert \"Hello\")"
  :ns "user"}]
```

#### Configuration Example

```clojure
;; BB Server setup
(def nrepl-config
  {:proxy {:enabled true
           :port 7888
           :bind "127.0.0.1"}
   :reverse {:enabled true
             :max-eval-time-ms 30000}})

;; Start proxy
(start-nrepl-proxy-server sente-send-fn nrepl-config)

;; Now external tools can connect:
;; nrepl-client :connect 127.0.0.1:7888
;; And Scittle can also send nREPL requests to BB
```

#### Use Cases

**Browser → Server (Proxy)**:
- Use standard nREPL tools (Cider, Calva) to debug browser code
- External IDE connects to proxy, evaluates in browser
- Transparent to IDE (looks like normal nREPL)

**Server → Browser (Reverse)**:
- Browser-side REPL can eval on server
- Useful for testing server-side code from browser
- Bidirectional development workflow

#### Comparison: Proxy vs. Direct

| Aspect | Direct | Proxy |
|--------|--------|-------|
| **External tools** | No | Yes |
| **Standard nREPL** | No | Yes |
| **IDE integration** | No | Yes |
| **Complexity** | Lower | Higher |
| **Bidirectional** | Yes | Yes |
| **Code reuse** | N/A | Existing proxy |

### Next Steps

1. **Locate existing proxy code**: Find scittle-nrepl-server proxy implementation
2. **Extract bencode logic**: Isolate reusable components
3. **Implement Phase 1**: Browser → Server proxy
4. **Test with IDE**: Connect Cider/Calva to proxy
5. **Implement Phase 2**: Server → Browser reverse proxy
6. **Document patterns**: Best practices for bidirectional nREPL

---

## Module: HTTP Blob Transfer via Sente Directives

**Status**: Proposed  
**Complexity**: Medium  
**Dependencies**: sente-lite core, HTTP client/server  
**Use Cases**: Large file transfers, model loading, asset distribution, high-performance data sync

### Overview

Send directives through sente channels to instruct the other side to fetch large blobs via HTTP instead of WebSocket. The HTTP layer handles compression, chunking, caching, and resumable transfers automatically. Sente carries lightweight control messages and metadata.

**Example Use Case**:
```
Server wants to send 500MB model to Browser
    ↓ sente directive
    ↓ [:blob/fetch-and-eval {:url "http://server/models/model.bin" 
                              :handler :ml/load-model}]
Browser receives directive
    ↓ HTTP GET with browser caching/compression
    ↓ Automatic chunking, resumable on failure
    ↓ Browser cache handles subsequent requests
    ↓
Browser processes blob via handler
    ↓ :ml/load-model handler receives blob
    ↓ eval/render/display as needed
```

### Architecture

#### Design Principles

1. **Offload to HTTP**: Large transfers use HTTP, not WebSocket
2. **Browser caching**: Automatic caching, compression, deduplication
3. **Lightweight control**: Sente carries directives and metadata only
4. **Handler routing**: Blob destination determined by handler type
5. **Resumable**: HTTP layer handles partial transfers, retries
6. **Bidirectional**: Works both directions (server→browser, browser→server)

#### Core Components

**Directive Format**:
```clojure
;; Server → Browser
[:blob/fetch-and-eval
 {:url "http://server/models/model.bin"
  :handler :ml/load-model
  :metadata {:size 524288000
             :sha256 "abc123..."
             :content-type "application/octet-stream"}
  :options {:timeout-ms 300000
            :retry-count 3
            :cache true}}]

;; Browser → Server
[:blob/fetch-and-process
 {:url "http://browser/uploads/data.csv"
  :handler :data/import
  :metadata {:size 104857600
             :sha256 "def456..."}}]
```

#### Handler Types

**Evaluation Handler** (`:eval`):
```clojure
;; Browser receives blob and evaluates as code
[:blob/fetch-and-eval
 {:url "http://server/code/plugin.cljs"
  :handler :eval}]
```

**Presentation Handler** (`:display`):
```clojure
;; Browser receives blob and displays (HTML, SVG, etc.)
[:blob/fetch-and-display
 {:url "http://server/assets/dashboard.html"
  :handler :ui/dashboard}]
```

**Graphics Handler** (`:render`):
```clojure
;; Browser receives blob and renders (image, 3D model, etc.)
[:blob/fetch-and-render
 {:url "http://server/models/scene.glb"
  :handler :graphics/webgl}]
```

**Custom Handler**:
```clojure
;; Route to custom handler for processing
[:blob/fetch-and-process
 {:url "http://server/data/dataset.bin"
  :handler :custom/my-processor}]
```

### Implementation Phases

#### Phase 1: Basic Blob Transfer (~200-300 LOC)

**Goal**: Proof of concept with simple HTTP fetch

**Features**:
- Send fetch directive via sente
- Browser fetches via HTTP
- Route to handler for processing
- Basic error handling

**Code Sketch**:
```clojure
;; Server
(defn send-blob-directive [uid url handler metadata]
  (sente/send-to-client! uid [:blob/fetch-and-process
    {:url url :handler handler :metadata metadata}]))

;; Browser
(defmethod handle-message :blob/fetch-and-process
  [{:keys [data]}]
  (let [{:keys [url handler metadata]} data]
    (fetch url)
      .then(response => response.arrayBuffer())
      .then(blob => (process-blob-with-handler handler blob metadata)))))

;; Handler dispatch
(defmulti process-blob-with-handler (fn [handler _ _] handler))

(defmethod process-blob-with-handler :eval [_ blob _]
  (eval-blob blob))

(defmethod process-blob-with-handler :graphics/webgl [_ blob _]
  (render-webgl-model blob))
```

**Pros**:
- ✅ Simple to implement
- ✅ Leverages browser HTTP
- ✅ Automatic compression/caching
- ✅ Works immediately

**Cons**:
- ⚠️ No resume on failure
- ⚠️ No progress tracking
- ⚠️ No verification (SHA256)

#### Phase 2: Robust Transfer (~400-500 LOC)

**Goal**: Production-ready with reliability features

**Features**:
- SHA256 verification
- Progress tracking via sente
- Resumable transfers
- Retry logic
- Timeout handling
- Partial content support (HTTP 206)

**Code Sketch**:
```clojure
;; Server sends directive with verification
(defn send-blob-directive [uid url handler metadata]
  (let [sha256 (compute-sha256 url)]
    (sente/send-to-client! uid [:blob/fetch-and-process
      {:url url
       :handler handler
       :metadata (assoc metadata :sha256 sha256)
       :options {:retry-count 3
                 :timeout-ms 300000
                 :verify true}}])))

;; Browser with progress tracking
(defmethod handle-message :blob/fetch-and-process
  [{:keys [data]}]
  (let [{:keys [url handler metadata options]} data]
    (fetch-with-progress url
      :on-progress (fn [loaded total]
        ;; Send progress back to server
        (sente/send! client [:blob/progress
          {:url url :loaded loaded :total total}]))
      :on-complete (fn [blob]
        ;; Verify SHA256
        (if (verify-sha256 blob (:sha256 metadata))
          (process-blob-with-handler handler blob metadata)
          (handle-verification-error url))))))
```

**Pros**:
- ✅ Reliable transfers
- ✅ Progress visibility
- ✅ Resumable on failure
- ✅ Verification
- ✅ Timeout handling

**Cons**:
- ⚠️ More complex code
- ⚠️ Server-side progress tracking overhead

#### Phase 3: Advanced Features (~600+ LOC)

**Goal**: Enterprise-grade blob distribution

**Features**:
- CDN support (multiple URLs)
- Bandwidth throttling
- Deduplication (same blob, multiple requests)
- Streaming processing (process while downloading)
- Compression negotiation
- Mirror/fallback URLs

### Wire Format & Protocol

**Directive** (Sente → Browser):
```clojure
[:blob/fetch-and-process
 {:url "http://server/data.bin"
  :handler :custom/processor
  :metadata {:size 104857600
             :sha256 "abc123..."
             :content-type "application/octet-stream"
             :timestamp 1766108726643}
  :options {:timeout-ms 300000
            :retry-count 3
            :verify true
            :cache true}}]
```

**Progress** (Browser → Sente):
```clojure
[:blob/progress
 {:url "http://server/data.bin"
  :loaded 52428800
  :total 104857600
  :percent 50}]
```

**Completion** (Browser → Sente):
```clojure
[:blob/complete
 {:url "http://server/data.bin"
  :handler :custom/processor
  :status :success
  :duration-ms 5000
  :bytes-transferred 104857600}]
```

**Error** (Browser → Sente):
```clojure
[:blob/error
 {:url "http://server/data.bin"
  :error "Network timeout"
  :retry-count 3
  :status-code 503}]
```

### Configuration Example

```clojure
;; Server setup
(def blob-config
  {:enabled true
   :base-url "http://server/blobs"
   :timeout-ms 300000
   :retry-count 3
   :verify-sha256 true
   :max-concurrent 5})

;; Send large model to browser
(send-blob-directive uid 
  "http://server/models/gpt-2.bin"
  :ml/load-model
  {:size 548000000
   :description "GPT-2 model"})

;; Browser receives and loads
(defmethod process-blob-with-handler :ml/load-model [_ blob _]
  (load-ml-model blob))
```

### Advantages

**Performance**:
- ✅ HTTP compression (gzip, brotli)
- ✅ Browser caching (avoid re-download)
- ✅ Chunked transfer encoding
- ✅ Parallel downloads (multiple connections)
- ✅ Resumable on failure (HTTP 206)

**Architecture**:
- ✅ Sente carries only metadata
- ✅ HTTP handles heavy lifting
- ✅ Offloads from WebSocket
- ✅ Bidirectional (both directions)
- ✅ Flexible handler routing

**Operations**:
- ✅ Leverage HTTP caching infrastructure
- ✅ Use CDN for distribution
- ✅ Standard HTTP monitoring/logging
- ✅ No WebSocket bandwidth limits

### Disadvantages

**Complexity**:
- ⚠️ Two protocols (sente + HTTP)
- ⚠️ More moving parts
- ⚠️ Requires HTTP server setup

**Coordination**:
- ⚠️ Need to coordinate sente + HTTP
- ⚠️ Verification overhead (SHA256)
- ⚠️ Progress tracking adds latency

### Use Cases

**Server → Browser**:
- ✅ Large ML models (100MB+)
- ✅ Asset bundles (images, fonts)
- ✅ Code plugins/extensions
- ✅ Database snapshots
- ✅ Video/media files

**Browser → Server**:
- ✅ Large file uploads
- ✅ Bulk data import
- ✅ Log aggregation
- ✅ Analytics data

### Integration with sente-lite

**Lifecycle hooks**:
```clojure
;; Track active transfers
:on-open (fn [uid]
  (init-blob-transfers uid))

:on-close (fn [code reason]
  (cleanup-blob-transfers uid))
```

**Error handling**:
```clojure
;; Handle blob transfer errors
(defmethod handle-event :blob/error
  [{:keys [data uid]}]
  (log/error "Blob transfer failed" data)
  ;; Retry or notify user
  )
```

### File Structure

```
src/sente_lite/blob/
├── transfer.cljc        # Core transfer logic
├── handler.clj          # Server-side handler
├── client.cljs          # Client-side fetcher
├── verify.cljc          # SHA256 verification
└── progress.cljc        # Progress tracking

test/sente_lite/blob/
├── transfer_test.cljc   # Unit tests
└── integration_test.bb  # Integration tests

examples/
└── blob-transfer-demo.bb  # Working example
```

### Comparison: WebSocket vs. HTTP Blob Transfer

| Aspect | WebSocket | HTTP Blob |
|--------|-----------|-----------|
| **Compression** | Manual | Automatic |
| **Caching** | No | Yes |
| **Resumable** | No | Yes (206) |
| **Chunking** | Manual | Automatic |
| **Bandwidth** | Limited | Unlimited |
| **Setup** | Simple | Moderate |
| **Latency** | Lower | Slightly higher |
| **Large files** | Inefficient | Efficient |

### Next Steps

1. **Validate use case**: Identify real blob transfer needs
2. **Build Phase 1 MVP**: Basic fetch + handler routing
3. **Test with real data**: 100MB+ file transfer
4. **Add verification**: SHA256 validation
5. **Implement Phase 2**: Progress tracking, resumable
6. **Benchmark**: Compare with WebSocket transfer

---

## Module: Language Server Protocol (LSP) over Sente

**Status**: Proposed  
**Complexity**: Medium-High  
**Dependencies**: sente-lite core, LSP protocol, language servers  
**Use Cases**: In-browser code editing, IDE features, code completion, diagnostics, refactoring

### Overview

Route Language Server Protocol (LSP) messages through sente channels to enable IDE-like features in the browser. Query LSP servers running on the BB server from browser editors without needing separate WebSocket connections or LSP-specific proxies.

**Example Use Case**:
```
Browser Editor (Scittle)
    ↓ LSP request (completion, hover, diagnostics)
    ↓ sente channel
    ↓
LSP Server Handler (BB)
    ↓ clojure-lsp, rust-analyzer, typescript-language-server, etc.
    ↓ LSP response
    ↓ sente channel
    ↓
Browser Editor (Scittle)
    ↓ Display completions, hover info, diagnostics
```

### Architecture

#### Design Principles

1. **Unified transport**: LSP over sente, not separate WebSocket
2. **Any LSP server**: Works with clojure-lsp, rust-analyzer, etc.
3. **JSON-RPC**: LSP is JSON-RPC, maps naturally to sente events
4. **Bidirectional**: Server can push diagnostics, notifications
5. **Multiple editors**: Support multiple browser editors on same connection
6. **Session management**: Track LSP sessions per editor/file

#### Core Components

**LSP Request Format**:
```clojure
;; Browser → Server
[:lsp/request
 {:session-id "editor-123"
  :request-id "req-456"
  :method "textDocument/completion"
  :params {:textDocument {:uri "file:///project/src/core.clj"}
           :position {:line 10 :character 5}}}]
```

**LSP Response Format**:
```clojure
;; Server → Browser
[:lsp/response
 {:session-id "editor-123"
  :request-id "req-456"
  :result [{:label "defn"
            :kind 3
            :detail "macro"
            :documentation "Define a function"}
           ...]}]
```

**LSP Notification Format**:
```clojure
;; Server → Browser (unsolicited)
[:lsp/notification
 {:method "textDocument/publishDiagnostics"
  :params {:uri "file:///project/src/core.clj"
           :diagnostics [{:range {:start {:line 5 :character 0}
                                  :end {:line 5 :character 10}}
                          :severity 1
                          :message "Unused variable"}]}}]
```

#### Server-Side Handler

```clojure
;; Handler for LSP requests from browser
(defmethod handle-event :lsp/request
  [{:keys [data uid]}]
  (let [{:keys [session-id request-id method params]} data
        lsp-server (get-lsp-server session-id)
        response (send-to-lsp-server lsp-server method params)]
    ;; Send response back to browser
    (sente/send-to-client! uid [:lsp/response
      {:session-id session-id
       :request-id request-id
       :result (:result response)}])))

;; Handle LSP notifications from server
(defn forward-lsp-notifications [session-id uid lsp-server]
  ;; Listen to LSP server notifications
  ;; Forward to browser via sente
  (listen-to-lsp-server lsp-server
    (fn [notification]
      (sente/send-to-client! uid [:lsp/notification notification]))))
```

### Implementation Phases

#### Phase 1: Basic LSP Proxy (~300-400 LOC)

**Goal**: Simple LSP request/response routing

**Features**:
- Route LSP requests to language server
- Return responses to browser
- Basic session management
- Support common methods (completion, hover, definition)

**Supported Methods**:
- `textDocument/completion`
- `textDocument/hover`
- `textDocument/definition`
- `textDocument/references`
- `textDocument/formatting`

**Code Sketch**:
```clojure
;; Server
(defn start-lsp-server [language]
  ;; Start language server process
  ;; (clojure-lsp, rust-analyzer, etc.)
  )

(defmethod handle-event :lsp/request
  [{:keys [data uid]}]
  (let [{:keys [method params]} data
        result (query-lsp-server method params)]
    (sente/send-to-client! uid [:lsp/response
      {:result result}])))

;; Browser
(defmethod handle-message :lsp/response
  [{:keys [data]}]
  (let [{:keys [result]} data]
    (display-completions result)))
```

**Pros**:
- ✅ Simple to implement
- ✅ Works with any LSP server
- ✅ Reuses sente connection
- ✅ No separate proxy needed

**Cons**:
- ⚠️ No notifications
- ⚠️ No session persistence
- ⚠️ No file watching

#### Phase 2: Full LSP Support (~500-600 LOC)

**Goal**: Complete LSP protocol implementation

**Features**:
- All LSP methods
- Server notifications (diagnostics, etc.)
- File watching and change tracking
- Workspace management
- Multiple language servers
- Session persistence

**Configuration**:
```clojure
(def lsp-config
  {:enabled true
   :servers {:clojure {:command "clojure-lsp"
                       :args ["--stdio"]}
             :rust {:command "rust-analyzer"}
             :typescript {:command "typescript-language-server"
                          :args ["--stdio"]}}
   :max-sessions 10
   :session-timeout-ms 3600000})
```

**Pros**:
- ✅ Full IDE experience
- ✅ Real-time diagnostics
- ✅ Multiple language support
- ✅ File tracking

**Cons**:
- ⚠️ More complex state management
- ⚠️ Resource overhead (multiple servers)

#### Phase 3: Advanced Features (~700+ LOC)

**Goal**: Enterprise-grade LSP infrastructure

**Features**:
- Language server pooling
- Load balancing
- Caching (completions, definitions)
- Performance optimization
- Custom language servers
- Debugging support

### Wire Format & Protocol

**Initialize**:
```clojure
[:lsp/initialize
 {:session-id "editor-123"
  :rootPath "/project"
  :capabilities {:textDocument {:completion {}}}}]
```

**Request**:
```clojure
[:lsp/request
 {:session-id "editor-123"
  :request-id "req-456"
  :method "textDocument/completion"
  :params {...}}]
```

**Response**:
```clojure
[:lsp/response
 {:session-id "editor-123"
  :request-id "req-456"
  :result [...]}]
```

**Notification**:
```clojure
[:lsp/notification
 {:method "textDocument/publishDiagnostics"
  :params {...}}]
```

### Configuration Example

```clojure
;; Server setup
(def lsp-config
  {:clojure {:command "clojure-lsp"
             :args ["--stdio"]
             :enabled true}
   :rust {:command "rust-analyzer"
          :enabled true}})

;; Start LSP servers
(start-lsp-servers lsp-config)

;; Browser editor sends completion request
(sente/send! client [:lsp/request
  {:session-id "editor-1"
   :request-id "req-1"
   :method "textDocument/completion"
   :params {...}}])

;; Server responds with completions
;; Browser displays in editor
```

### Advantages

**IDE Features**:
- ✅ Code completion
- ✅ Hover documentation
- ✅ Go to definition
- ✅ Find references
- ✅ Diagnostics/linting
- ✅ Code formatting
- ✅ Refactoring

**Architecture**:
- ✅ Reuses sente connection
- ✅ Works with any LSP server
- ✅ No separate proxy needed
- ✅ Bidirectional (notifications)
- ✅ Multiple editors supported

**Operations**:
- ✅ Leverage existing LSP servers
- ✅ Standard LSP protocol
- ✅ Easy to add new languages
- ✅ Familiar to developers

### Disadvantages

**Complexity**:
- ⚠️ LSP protocol is complex
- ⚠️ Multiple language servers to manage
- ⚠️ Resource overhead (server processes)

**Performance**:
- ⚠️ Latency (sente + LSP roundtrip)
- ⚠️ Server load (multiple requests)
- ⚠️ Memory (language server state)

### Use Cases

**In-Browser IDE**:
- ✅ Code editor with IDE features
- ✅ Clojure/ClojureScript development
- ✅ Multi-language support
- ✅ Educational tools

**Development Tools**:
- ✅ Code review tools
- ✅ Documentation generators
- ✅ Static analysis
- ✅ Refactoring tools

### Related Projects

**Babashka Pod for LSP** (Recommended):
- `com.github.clojure-lsp/clojure-lsp` - Clojure LSP as Babashka pod
  - Available in pod-registry
  - Load with `(pods/load-pod 'com.github.clojure-lsp/clojure-lsp "2022.11.03-00.14.57")`
  - Provides LSP API directly in Babashka
  - No separate process needed
  - Perfect for sente-lite integration

**Existing LSP WebSocket Proxies**:
- `lsp-ws-proxy` (Rust, general-purpose)
- `codemirror/lsp-client` (Browser LSP client)
- `val-town/vtlsp` (Full LSP infrastructure)

**Integration Pattern**:
The existing `lsp-ws-proxy` project shows the pattern:
- Start LSP server process (stdio)
- Proxy JSON-RPC messages over WebSocket
- Browser client sends LSP requests
- Server responds with LSP responses

**For sente-lite**: Use Babashka pod approach:
- Load clojure-lsp pod in BB server
- Call LSP API directly from sente handlers
- Route LSP requests/responses through sente
- No separate proxy process needed
- Simpler, more integrated architecture

### File Structure

```
src/sente_lite/lsp/
├── handler.clj          # Server-side LSP handler
├── client.cljs          # Client-side LSP client
├── protocol.cljc        # LSP protocol utilities
├── server.clj           # Language server management
└── session.clj          # Session tracking

test/sente_lite/lsp/
├── handler_test.clj     # Server tests
├── client_test.cljs     # Client tests
└── integration_test.bb  # Integration tests

examples/
└── lsp-over-sente-demo.bb  # Working example
```

### Comparison: Dedicated Proxy vs. Sente

| Aspect | Dedicated Proxy | Sente LSP |
|--------|-----------------|-----------|
| **Connections** | 2 | 1 |
| **Setup** | Separate proxy | Integrated |
| **Notifications** | Possible | Native |
| **Session mgmt** | Manual | Automatic |
| **Complexity** | Lower | Medium |
| **Reusability** | Limited | High |
| **Bidirectional** | Yes | Yes |

### Next Steps

1. **Investigate lsp-ws-proxy**: Study existing implementation
2. **Design LSP-over-Sente**: Map LSP JSON-RPC to sente events
3. **Implement Phase 1**: Basic request/response routing
4. **Test with clojure-lsp**: Verify with real language server
5. **Add notifications**: Support server-to-client messages
6. **Implement Phase 2**: Full LSP support with multiple servers

---

## Module: State Synchronization Patterns

**Status**: Proposed  
**Complexity**: Low-High (varies by pattern)  
**Dependencies**: sente-lite core, optional: datascript, shadow-dom  
**Use Cases**: Shared state, real-time collaboration, data binding, reactive UI

### Overview

Synchronize state across sente channels using various patterns: one-way atom syncing, two-way syncing, DataScript instances, and Shadow DOM integration. Each pattern trades off complexity for synchronization guarantees.

### Pattern 1: One-Way Atom Syncing (Simple)

**Status**: Implemented in many projects  
**Complexity**: Low  
**Use Cases**: Server→Browser state push, read-only client state

#### Architecture

```
Server Atom
    ↓ watch/add-watch
    ↓ (on change)
    ↓ sente send
    ↓
Browser Atom
    ↓ receive message
    ↓ reset!
    ↓ triggers watchers
    ↓ UI updates via handlers
```

#### Implementation with Plain Atoms

```clojure
;; Server side
(def app-state (atom {:users [] :count 0}))

(add-watch app-state :sync
  (fn [key ref old-state new-state]
    ;; Send to all connected clients
    (doseq [uid (get-connected-uids)]
      (sente/send-to-client! uid [:state/update new-state]))))

;; Browser side
(def app-state (atom {}))

(defmethod handle-message :state/update
  [{:keys [data]}]
  ;; Update local atom (triggers watchers)
  (reset! app-state data))

;; UI watches atom
(add-watch app-state :ui
  (fn [_ _ old new]
    (render-ui new)))
```

#### Implementation with Reagent Atoms (Recommended for Browser)

Reagent atoms are **perfect for browser-side state** because they integrate seamlessly with React components:

```clojure
;; Browser side with Reagent
(def app-state (r/atom {:users [] :count 0}))

;; Receive updates from server
(defmethod handle-message :state/update
  [{:keys [data]}]
  ;; Update Reagent atom (triggers component re-renders)
  (reset! app-state data))

;; Reagent component watches atom automatically
(defn users-list []
  (let [users (:users @app-state)]
    [:div
     [:h2 "Users"]
     [:ul
      (for [user users]
        ^{:key (:id user)}
        [:li (:name user)])]]))

;; Component automatically re-renders when atom changes
(defn app []
  [:div
   [users-list]])

;; Mount to DOM
(r/render [app] (js/document.getElementById "app"))
```

**Reagent Advantages**:
- ✅ Automatic component re-renders on atom change
- ✅ No manual watchers needed
- ✅ Efficient React diffing
- ✅ Deref syntax (`@app-state`) in components
- ✅ Reactions for computed values
- ✅ Ratoms for local component state

#### Reagent Reactions for Computed State

```clojure
;; Derived state using reactions
(def user-count (r/reaction (count (:users @app-state))))
(def active-users (r/reaction (filter :active (:users @app-state))))

;; Use in components
(defn stats []
  [:div
   [:p "Total users: " @user-count]
   [:p "Active users: " (count @active-users)]])
```

#### Two-Way Sync with Reagent

```clojure
;; Browser sends changes back to server
(add-watch app-state :sync
  (fn [key ref old-state new-state]
    ;; Send only if changed by user (not from server)
    (when (not= old-state new-state)
      (sente/send! client [:state/update
        {:state new-state
         :timestamp (now)
         :client-id client-id}]))))

;; Component can update state
(defn user-input []
  [:input
   {:value (:search @app-state)
    :on-change (fn [e]
      (swap! app-state assoc :search (.. e -target -value)))}])
```

#### Form Field Syncing Pattern (Real-Time)

**Powerful pattern**: Every form field change is automatically synced to server atoms in real-time.

```clojure
;; Browser: Form component with Reagent
(defn form-component []
  [:form
   [:input
    {:type "text"
     :placeholder "Name"
     :value (:name @form-state)
     :on-change (fn [e]
       (swap! form-state assoc :name (.. e -target -value)))}]
   [:input
    {:type "email"
     :placeholder "Email"
     :value (:email @form-state)
     :on-change (fn [e]
       (swap! form-state assoc :email (.. e -target -value)))}]
   [:textarea
    {:value (:message @form-state)
     :on-change (fn [e]
       (swap! form-state assoc :message (.. e -target -value)))}]
   [:button
    {:on-click (fn []
      (sente/send! client [:form/submit @form-state]))}
    "Submit"]])

;; Browser: Watch form state and sync to server
(add-watch form-state :sync
  (fn [key ref old-state new-state]
    (when (not= old-state new-state)
      ;; Send each field change in real-time
      (sente/send! client [:form/field-change
        {:field (diff-field old-state new-state)
         :value (get-in new-state (diff-field old-state new-state))
         :timestamp (now)}]))))

;; Server: Receive form field changes
(def form-atoms (atom {}))

(defmethod handle-event :form/field-change
  [{:keys [data uid]}]
  (let [{:keys [field value timestamp]} data
        form-id (get-form-id uid)]
    ;; Update server-side form atom
    (swap! (get-or-create-form-atom form-id) assoc-in field value)
    ;; Trigger change handlers
    (trigger-field-change-handlers form-id field value)))

;; Server: Change handlers triggered automatically
(defn on-email-change [form-id email]
  ;; Validate email in real-time
  (if (valid-email? email)
    (update-form-validation form-id :email :valid)
    (update-form-validation form-id :email :invalid)))

(defn on-name-change [form-id name]
  ;; Update character count
  (update-form-stats form-id :name-length (count name)))
```

**Real-Time Features Enabled**:
- ✅ Live validation (email, phone, etc.)
- ✅ Character counters
- ✅ Autocomplete suggestions
- ✅ Duplicate checking
- ✅ Field dependencies
- ✅ Dynamic form updates
- ✅ Server-side change handlers

**Example: Live Validation**:
```clojure
;; Server validates email in real-time
(defmethod handle-event :form/field-change
  [{:keys [data uid]}]
  (let [{:keys [field value]} data]
    (when (= field :email)
      ;; Check if email already exists
      (if (email-exists? value)
        (sente/send-to-client! uid [:form/error
          {:field :email :message "Email already registered"}])
        (sente/send-to-client! uid [:form/valid
          {:field :email}])))))

;; Browser receives validation feedback
(defmethod handle-message :form/error
  [{:keys [data]}]
  (let [{:keys [field message]} data]
    (swap! form-state assoc-in [:errors field] message)))

(defmethod handle-message :form/valid
  [{:keys [data]}]
  (let [{:keys [field]} data]
    (swap! form-state update :errors dissoc field)))
```

**Pros**:
- ✅ Simple to implement
- ✅ Familiar pattern (atoms + watchers)
- ✅ Works with existing code
- ✅ Low overhead
- ✅ **Reagent: Automatic React integration**
- ✅ **Reagent: Efficient re-renders**
- ✅ **Reagent: Reactions for derived state**
- ✅ **Real-time form validation**
- ✅ **Server-side change handlers**
- ✅ **Live feedback to user**

**Cons**:
- ⚠️ Network overhead (every keystroke)
- ⚠️ No conflict resolution
- ⚠️ No offline support
- ⚠️ No history/undo
- ⚠️ Debouncing may be needed for performance

### Pattern 2: Two-Way Atom Syncing (Complex)

**Status**: Proposed  
**Complexity**: High  
**Use Cases**: Collaborative editing, shared forms, real-time data

#### Challenges

- **Conflict resolution**: What if both sides change simultaneously?
- **Ordering**: Which change wins?
- **Convergence**: Do both sides eventually agree?
- **Offline**: What happens when disconnected?

#### Approaches

**Last-Write-Wins (LWW)**:
```clojure
;; Include timestamp with every change
[:state/update {:path [:users 0 :name]
                :value "Alice"
                :timestamp 1766108726643
                :client-id "client-123"}]

;; Server applies if timestamp is newer
(if (> new-timestamp old-timestamp)
  (update-state new-value)
  (send-current-state-back))
```

**Operational Transformation (OT)**:
```clojure
;; Track operations, transform concurrent changes
[:state/operation {:op :set
                   :path [:text]
                   :value "new text"
                   :version 5}]

;; Server transforms against other concurrent ops
;; Both clients converge to same state
```

**CRDT (Conflict-free Replicated Data Type)**:
```clojure
;; Use CRDT data structure (e.g., Yjs, Automerge)
;; Automatically handles conflicts
;; Both sides converge without coordination
```

**Code Sketch (LWW)**:
```clojure
;; Server
(defn apply-state-update [path value timestamp client-id]
  (let [current-ts (get-timestamp-at-path path)]
    (if (> timestamp current-ts)
      (do
        (update-state path value)
        ;; Broadcast to all clients
        (broadcast-state-update path value timestamp))
      ;; Send current state back to client
      (send-current-state client-id))))

;; Browser
(add-watch app-state :sync
  (fn [_ _ old new]
    ;; Send change with timestamp
    (sente/send! client [:state/update
      {:path (diff-path old new)
       :value (get-in new (diff-path old new))
       :timestamp (now)
       :client-id client-id}])))
```

**Pros**:
- ✅ Bidirectional sync
- ✅ Real-time collaboration
- ✅ Automatic conflict resolution

**Cons**:
- ⚠️ Complex implementation
- ⚠️ Requires careful design
- ⚠️ Performance overhead
- ⚠️ Debugging difficult

### Pattern 3: DataScript Instance Syncing

**Status**: Proposed  
**Complexity**: Medium-High  
**Use Cases**: Complex data, queries, reactive views

#### Architecture

```
Server DataScript DB
    ↓ track transactions
    ↓ sente send
    ↓
Browser DataScript DB
    ↓ apply transactions
    ↓ query results update
    ↓ UI re-renders
```

#### Implementation

```clojure
;; Server
(def db (d/create-conn schema))

(defn sync-transaction [tx-data]
  ;; Apply transaction
  (d/transact! db tx-data)
  ;; Send to all clients
  (doseq [uid (get-connected-uids)]
    (sente/send-to-client! uid [:db/transaction tx-data])))

;; Browser
(def db (d/create-conn schema))

(defmethod handle-message :db/transaction
  [{:keys [data]}]
  ;; Apply same transaction
  (d/transact! db data)
  ;; Queries automatically update
  ;; UI re-renders via subscriptions)

;; Reactive query
(def users-query
  (reaction
    (d/q '[:find ?e ?name
           :where [?e :user/name ?name]]
         @db)))

;; UI watches query
(add-watch users-query :ui
  (fn [_ _ old new]
    (render-users new)))
```

**Pros**:
- ✅ Powerful queries
- ✅ Reactive views
- ✅ Complex data handling
- ✅ Transactions

**Cons**:
- ⚠️ Requires DataScript
- ⚠️ Schema management
- ⚠️ Conflict resolution still needed
- ⚠️ More overhead

### Pattern 4: Shadow DOM Integration

**Status**: Proposed  
**Complexity**: Medium  
**Use Cases**: Component state, encapsulation, style isolation

#### Architecture

```
Server State
    ↓ sente sync
    ↓
Browser Atom
    ↓
Shadow DOM Component
    ↓ encapsulated styles
    ↓ isolated state
    ↓ slot-based content
```

#### Implementation

```clojure
;; Browser component
(defn sync-shadow-component [element-id state-atom]
  (let [host (js/document.getElementById element-id)
        shadow (.attachShadow host #js{:mode "open"})]
    ;; Create shadow DOM structure
    (.innerHTML shadow
      "<style>
         :host { display: block; }
         .content { padding: 10px; }
       </style>
       <div class='content'>
         <slot></slot>
       </div>")
    
    ;; Watch state and update shadow DOM
    (add-watch state-atom :shadow
      (fn [_ _ old new]
        (update-shadow-dom shadow new)))))

;; Server sends state updates
(add-watch app-state :sync
  (fn [_ _ old new]
    (sente/send-to-client! uid [:shadow/update new])))

;; Browser receives and updates
(defmethod handle-message :shadow/update
  [{:keys [data]}]
  (reset! component-state data))
```

**Pros**:
- ✅ Style encapsulation
- ✅ Component isolation
- ✅ Reusable components
- ✅ Clean separation

**Cons**:
- ⚠️ Browser compatibility
- ⚠️ Shadow DOM complexity
- ⚠️ Slot management
- ⚠️ Debugging harder

### Comparison: All Patterns

| Pattern | Complexity | Bidirectional | Conflicts | Offline | Best For |
|---------|-----------|---------------|-----------|---------|----------|
| **One-Way Atom** | Low | No | N/A | No | Simple state push |
| **Two-Way Atom** | High | Yes | LWW/OT/CRDT | Possible | Collaboration |
| **DataScript** | Medium-High | Yes | Transactions | Possible | Complex data |
| **Shadow DOM** | Medium | No | N/A | No | Components |

### Configuration Example

```clojure
;; One-way syncing
(def sync-config
  {:enabled true
   :direction :server->browser
   :debounce-ms 100
   :batch-updates true})

;; Two-way syncing with LWW
(def sync-config
  {:enabled true
   :direction :bidirectional
   :conflict-resolution :last-write-wins
   :timestamp-fn (fn [] (System/currentTimeMillis))
   :offline-support true})

;; DataScript syncing
(def sync-config
  {:enabled true
   :db-type :datascript
   :schema {...}
   :sync-transactions true
   :reactive-queries true})
```

### Integration with sente-lite

**Lifecycle hooks**:
```clojure
;; Initialize sync on connect
:on-open (fn [uid]
  (init-state-sync uid))

;; Clean up on disconnect
:on-close (fn [code reason]
  (cleanup-state-sync uid))

;; Restore on reconnect
:on-reconnect (fn [uid]
  (restore-state-sync uid))
```

**Error handling**:
```clojure
;; Handle sync conflicts
(defmethod handle-event :state/conflict
  [{:keys [data uid]}]
  (resolve-conflict data uid))
```

### File Structure

```
src/sente_lite/sync/
├── atom.cljc            # One-way atom syncing
├── bidirectional.cljc   # Two-way syncing
├── datascript.clj       # DataScript integration
├── shadow.cljs          # Shadow DOM integration
└── conflict.cljc        # Conflict resolution

test/sente_lite/sync/
├── atom_test.cljc       # Unit tests
├── bidirectional_test.cljc
└── integration_test.bb  # Integration tests

examples/
└── state-sync-demo.bb   # Working examples
```

### Next Steps

1. **Start with one-way**: Implement simple atom syncing first
2. **Add two-way**: Implement with LWW conflict resolution
3. **Explore DataScript**: For complex data scenarios
4. **Shadow DOM**: For component encapsulation
5. **Benchmark**: Compare performance of each pattern

---

## Additional Modules & Patterns

### 1. Metrics & Observability
**Use Cases**: Connection metrics, message throughput, latency, performance monitoring

Track and report metrics through sente:
- Connection uptime, latency, message rates
- Server-side metrics pushed to browser
- Browser-side metrics sent to server
- Real-time dashboards

### 2. Retry & Circuit Breaker
**Use Cases**: Advanced reconnection strategies, backpressure handling

Intelligent retry logic over sente:
- Exponential backoff
- Circuit breaker pattern
- Jitter to prevent thundering herd
- Graceful degradation

### 3. Message Compression
**Use Cases**: Bandwidth optimization for large payloads

Compress messages before sending:
- Gzip/brotli compression
- Automatic for large messages
- Transparent to handlers
- Bandwidth savings

### 4. Rate Limiting & Backpressure
**Use Cases**: Per-client rate limiting, flow control

Prevent overwhelming either side:
- Token bucket algorithm
- Per-client quotas
- Adaptive backpressure
- Queue management

### 5. Authentication & Authorization
**Use Cases**: Token-based auth, permission checking, role-based access

Secure sente channels:
- JWT token validation
- Permission checks per event
- Role-based handlers
- Audit logging

### 6. Request/Response Tracking
**Use Cases**: Correlation IDs, request tracing, debugging

Trace requests across systems:
- Correlation IDs
- Distributed tracing
- Request/response pairing
- Performance profiling

### 7. Caching Layer
**Use Cases**: Client-side caching, offline support, data deduplication

Cache frequently accessed data:
- Browser-side cache
- Server-side cache
- Cache invalidation
- Offline-first support

### 8. Analytics & Event Tracking
**Use Cases**: User behavior analysis, event aggregation, insights

Track events through sente:
- User actions
- Performance events
- Error tracking
- Aggregation and reporting

### 9. File Synchronization
**Use Cases**: Keep files in sync across processes, collaborative editing

Sync file changes:
- Watch file system
- Send diffs over sente
- Apply changes on other side
- Conflict resolution for concurrent edits
- Use case: Collaborative code editor

### 10. Database Replication
**Use Cases**: Keep databases in sync, master-slave replication

Replicate database changes:
- Track mutations
- Send change log over sente
- Apply on replica
- Consistency guarantees
- Use case: Browser-side read replica of server DB

### 11. Message Queue/Pub-Sub
**Use Cases**: Decouple producers and consumers, fan-out messaging

Implement pub-sub over sente:
- Topic subscriptions
- Message routing
- Fan-out to multiple subscribers
- Durable queues
- Use case: Real-time notifications, event streaming

### 12. Command/Event Sourcing
**Use Cases**: Audit trail, event replay, temporal queries

Track all state changes as events:
- Immutable event log
- Event replay for state reconstruction
- Temporal queries (state at time T)
- Audit trail
- Use case: Financial transactions, audit logs

### 13. Distributed Locking
**Use Cases**: Coordinate access to shared resources

Implement locks over sente:
- Acquire/release locks
- Deadlock detection
- Lock timeouts
- Fair queuing
- Use case: Prevent concurrent modifications

### 14. Presence & Awareness
**Use Cases**: See who's online, cursor positions, collaborative awareness

Track user presence:
- Online/offline status
- Cursor positions
- Active selections
- User awareness
- Use case: Collaborative editing, multiplayer games

### 15. Time Synchronization
**Use Cases**: Keep clocks in sync, handle clock skew

Synchronize time across processes:
- NTP-like protocol
- Clock skew detection
- Timestamp correction
- Use case: Distributed tracing, event ordering

### 16. Configuration Management
**Use Cases**: Push config changes, feature flags, A/B testing

Manage configuration remotely:
- Push config updates
- Feature flags
- A/B test variants
- Hot reload
- Use case: Feature toggles, dynamic configuration

### 17. Health Checks & Heartbeat
**Use Cases**: Monitor health, detect failures, auto-recovery

Implement health monitoring:
- Periodic heartbeats
- Health check responses
- Failure detection
- Auto-recovery triggers
- Use case: Detect dead connections, trigger reconnect

### 18. Batch Operations
**Use Cases**: Efficient bulk operations, reduce message count

Batch multiple operations:
- Collect operations
- Send in batch
- Atomic processing
- Reduce overhead
- Use case: Bulk imports, batch updates

### 19. Streaming/Chunking
**Use Cases**: Handle large data streams, progressive delivery

Stream data in chunks:
- Progressive delivery
- Backpressure handling
- Resume on failure
- Use case: Large file transfers, streaming analytics

### 20. RPC/Procedure Calls
**Use Cases**: Call functions on other side, distributed computing

Implement RPC over sente:
- Call functions remotely
- Pass arguments, return results
- Error handling
- Timeout handling
- Use case: Distributed computation, microservices

### 21. Debugging & Inspection
**Use Cases**: Remote debugging, state inspection, breakpoints

Debug remotely:
- Inspect state
- Set breakpoints
- Step through code
- Inspect variables
- Use case: Browser debugging from server, remote REPL

### 22. Consensus & Voting
**Use Cases**: Distributed consensus, voting, quorum

Implement consensus:
- Voting protocols
- Quorum checks
- Byzantine fault tolerance
- Use case: Cluster coordination, distributed decisions

### 23. Dependency Injection
**Use Cases**: Inject dependencies, service discovery

Manage dependencies:
- Service registry
- Service discovery
- Dependency resolution
- Use case: Microservices, plugin systems

### 24. Schema Validation
**Use Cases**: Validate messages, enforce contracts

Validate message schemas:
- JSON Schema validation
- Type checking
- Contract enforcement
- Use case: API contracts, data validation

### 25. Compression & Encoding
**Use Cases**: Optimize encoding, reduce payload size

Optimize encoding:
- MessagePack, Protocol Buffers
- Custom encodings
- Compression algorithms
- Use case: Bandwidth optimization, mobile networks

### 26. Message Routing & Relay
**Use Cases**: Route messages between clients, multi-server architecture, service discovery

Route messages to targets that may not be directly connected:
- Client-to-client via server relay
- Server-to-server routing
- Service discovery and routing
- Message forwarding
- Use case: Real-time chat, distributed systems

### 27. Client-to-Client Communication
**Use Cases**: Real-time chat, collaborative communication, peer messaging

Enable direct communication between clients through server relay:
- Real-time chat messages
- Peer-to-peer notifications
- Presence awareness
- Typing indicators
- Use case: Chat applications, collaborative tools

### 28. Shared Whiteboard
**Use Cases**: Collaborative drawing, real-time sketching, visual collaboration

Synchronize drawing state across multiple clients:
- Drawing operations (strokes, shapes)
- Real-time canvas updates
- Undo/redo support
- Cursor positions
- Use case: Collaborative design, teaching, brainstorming

### 29. Multi-Client State Synchronization
**Use Cases**: Keep many/all browsers in sync, master-client architecture, state replication

Synchronize state across multiple connected clients:
- Server as source of truth
- Master-client architecture
- Broadcast updates to all clients
- Selective client updates
- Use case: Real-time dashboards, collaborative apps, multiplayer games

### 30. Service Routing & Discovery
**Use Cases**: Multi-server architecture, service mesh, request routing

Route requests to appropriate service based on availability:
- Service registry
- Health-based routing
- Load balancing
- Failover
- Use case: Microservices, distributed systems

---

## Pattern: Advanced Routing & Synchronization

### Client-to-Client via Server Relay

**Architecture**:
```
Client A                Server              Client B
   ↓                      ↓                    ↓
[Message] ──→ [:msg/send-to-client]
                    ↓
              [Route to B]
                    ↓
                         ──→ [:msg/receive] ──→ [Display]
```

**Implementation**:
```clojure
;; Client A sends message to Client B
(sente/send! client [:msg/send-to-client
  {:to-uid "client-b-uid"
   :message "Hello from A"}])

;; Server routes message
(defmethod handle-event :msg/send-to-client
  [{:keys [data uid]}]
  (let [{:keys [to-uid message]} data]
    ;; Forward to target client
    (sente/send-to-client! to-uid [:msg/receive
      {:from-uid uid
       :message message}])))

;; Client B receives message
(defmethod handle-message :msg/receive
  [{:keys [data]}]
  (let [{:keys [from-uid message]} data]
    (display-message from-uid message)))
```

**Use Cases**:
- Real-time chat
- Peer notifications
- Collaborative messaging
- Presence updates

### Multi-Client State Synchronization

**Architecture**:
```
Server State
    ↓
[Broadcast to all connected clients]
    ↓
Client A ← Client B ← Client C ← Client D
    ↓        ↓        ↓        ↓
[Update] [Update] [Update] [Update]
```

**Implementation**:
```clojure
;; Server maintains shared state
(def shared-state (atom {:users [] :count 0}))

;; When state changes, broadcast to all clients
(add-watch shared-state :broadcast
  (fn [key ref old-state new-state]
    (when (not= old-state new-state)
      ;; Send to all connected clients
      (doseq [uid (get-connected-uids)]
        (sente/send-to-client! uid [:state/update new-state])))))

;; Each client receives and updates
(defmethod handle-message :state/update
  [{:keys [data]}]
  (reset! local-state data)
  (render-ui @local-state))
```

**Use Cases**:
- Real-time dashboards
- Collaborative applications
- Multiplayer games
- Live data feeds

### Master-Client Architecture

**Architecture**:
```
Master Client (Browser)
    ↓
[Authoritative State]
    ↓
Server (Relay)
    ↓
[Broadcast to other clients]
    ↓
Slave Clients (Read-Only)
```

**Implementation**:
```clojure
;; Master client has write access
(defmethod handle-message :state/update
  [{:keys [data uid]}]
  (if (is-master? uid)
    ;; Master can update
    (do
      (swap! shared-state merge data)
      ;; Broadcast to all
      (broadcast-state-update @shared-state))
    ;; Slaves get read-only copy
    (reset! local-state data)))

;; Slave clients can't modify
(defn slave-component []
  [:div
   [:p "State: " @local-state]
   ;; No input fields, just display
   ])
```

**Use Cases**:
- Presentation mode (one presenter, many viewers)
- Teacher-student scenarios
- Controlled synchronization

### Shared Whiteboard Pattern

**Architecture**:
```
Client A (Drawing)
    ↓
[Stroke: x1,y1 → x2,y2]
    ↓
Server (Relay)
    ↓
[Broadcast drawing operation]
    ↓
Client B ← Client C ← Client D
[Render] [Render] [Render]
```

**Implementation**:
```clojure
;; Client sends drawing operations
(defn on-mouse-move [e]
  (when (mouse-down?)
    (let [x (.. e -clientX)
          y (.. e -clientY)
          prev-pos @last-pos]
      (sente/send! client [:draw/stroke
        {:from prev-pos :to [x y]}])
      (reset! last-pos [x y]))))

;; Server relays drawing operations
(defmethod handle-event :draw/stroke
  [{:keys [data uid]}]
  ;; Broadcast to all clients
  (doseq [other-uid (get-connected-uids)]
    (when (not= other-uid uid)
      (sente/send-to-client! other-uid [:draw/stroke data]))))

;; All clients render the stroke
(defmethod handle-message :draw/stroke
  [{:keys [data]}]
  (let [{:keys [from to]} data]
    (draw-line canvas from to)))
```

**Features**:
- Real-time collaborative drawing
- Multiple users drawing simultaneously
- Undo/redo support
- Cursor positions
- Color/tool selection

### Message Routing Between Servers

**Architecture**:
```
Server A ←→ Server B ←→ Server C
   ↓           ↓           ↓
Clients    Clients    Clients
```

**Implementation**:
```clojure
;; Server A routes message to Server B
(defmethod handle-event :msg/route-to-server
  [{:keys [data]}]
  (let [{:keys [target-server message]} data]
    ;; Forward to target server
    (send-to-server target-server [:msg/relay message])))

;; Server B receives and routes to client
(defmethod handle-event :msg/relay
  [{:keys [data]}]
  (let [{:keys [target-client message]} data]
    ;; Forward to target client
    (sente/send-to-client! target-client [:msg/receive message])))
```

**Use Cases**:
- Multi-server architecture
- Service mesh
- Load balancing
- Failover routing

### Comparison: Routing Patterns

| Pattern | Complexity | Latency | Scalability | Best For |
|---------|-----------|---------|-------------|----------|
| **Client-to-Client** | Low | Low | Medium | Chat, messaging |
| **Multi-Client Sync** | Low | Low | High | Dashboards, real-time |
| **Master-Client** | Medium | Low | High | Presentations, teaching |
| **Shared Whiteboard** | Medium | Medium | Medium | Collaborative drawing |
| **Server Routing** | High | Medium | High | Microservices |

---

## Pattern: Multipurpose Channel Architecture

All these modules share a common pattern:

```
Browser                          Server
   ↓                               ↓
[Event Handler]                [Event Handler]
   ↓                               ↓
[Sente Channel] ←──────────────→ [Sente Channel]
   ↓                               ↓
[Module Logic]                 [Module Logic]
   ↓                               ↓
[State/Action]                 [State/Action]
```

**Key Benefits**:
- ✅ Single connection for all communication
- ✅ Unified error handling
- ✅ Shared authentication/authorization
- ✅ Consistent logging
- ✅ Simplified deployment
- ✅ Reduced resource usage
- ✅ Easier debugging

**Composition**:
Modules can be combined:
- Logging + Metrics + Analytics
- State Sync + Presence + Awareness
- RPC + Batch Operations + Streaming
- Database Replication + Consensus + Locking

**Scalability**:
- Multiple sente channels for different concerns
- Channel prioritization
- Message routing based on type
- Load balancing across channels

### Transport Abstraction

**Important Architectural Insight**: Sente is layered on top of a bidirectional channel (WebSocket). The same module patterns work with **any bidirectional transport**:

```
Sente Modules
    ↓
[Event Routing & Serialization]
    ↓
[Bidirectional Channel]
    ↓
WebSocket | Socket | Pipe | IPC | Custom Transport
```

**This means**:
- ✅ Modules work over WebSocket (current)
- ✅ Modules work over raw sockets
- ✅ Modules work over Unix pipes
- ✅ Modules work over IPC channels
- ✅ Modules work over custom transports
- ✅ Modules work over network protocols
- ✅ Modules work over local channels

**Implications**:
1. **Flexibility**: Not locked into WebSocket
2. **Portability**: Same logic works across different transports
3. **Testing**: Can test with pipes/sockets instead of WebSocket
4. **Optimization**: Can choose transport based on use case
5. **Fallback**: Can switch transports if primary fails

**Example: Same Module Over Different Transports**:
```clojure
;; Module logic is transport-agnostic
(defmethod handle-event :state/update
  [{:keys [data uid]}]
  ;; Works the same whether data came from WebSocket, socket, or pipe
  (update-state data))

;; Transport layer handles the difference
(defn start-sente-server [transport-type]
  (case transport-type
    :websocket (start-websocket-server)
    :socket (start-socket-server)
    :pipe (start-pipe-server)
    :ipc (start-ipc-server)))
```

**Use Cases for Alternative Transports**:
- **Pipes**: Local development, testing, debugging
- **Sockets**: High-performance local communication
- **IPC**: Inter-process communication
- **Custom**: Domain-specific optimizations

**Real-World Example: Subprocess Communication**

A practical use case: Babashka server spawns a separate process where stdin/stdout/stderr are connected to the server. With sente abstraction:

```clojure
;; Server spawns subprocess with connected pipes
(def proc (ProcessBuilder. ["./subprocess"]))
(.redirectInput proc ProcessBuilder$Redirect/PIPE)
(.redirectOutput proc ProcessBuilder$Redirect/PIPE)
(.redirectError proc ProcessBuilder$Redirect/PIPE)
(def process (.start proc))

;; Wrap stdin/stdout as sente transport
(def subprocess-transport
  (create-pipe-transport
    (.getInputStream process)
    (.getOutputStream process)))

;; Use same sente handlers as WebSocket
(defmethod handle-event :subprocess/compute
  [{:keys [data]}]
  ;; Works identically whether message came from WebSocket or pipe
  (let [result (expensive-computation data)]
    (send-response result)))

;; Subprocess communicates via stdin/stdout
;; Server treats it like any other sente client
;; All modules (logging, state sync, RPC, etc.) work transparently
```

**Benefits**:
- ✅ Same event handlers for subprocess and browser clients
- ✅ All modules work with subprocess communication
- ✅ Easy to test (use pipes instead of WebSocket)
- ✅ No special subprocess communication code needed
- ✅ Transparent fallback if WebSocket unavailable

This architectural property makes sente-lite particularly powerful: the same modules, handlers, and patterns work regardless of the underlying transport mechanism. Whether communicating with browsers over WebSocket, subprocesses over pipes, or other processes over sockets, the application logic remains identical.

### Comparison: Socket.IO vs Sente-Lite

**Socket.IO** (JavaScript/Node.js):
- Mature, widely-used library
- Automatic fallback (WebSocket → polling)
- Built-in rooms/namespaces
- Middleware support
- Large ecosystem
- Heavier (~100KB minified)

**Sente-Lite** (Clojure/ClojureScript):
- Minimal, focused design
- Transport-agnostic (WebSocket, pipes, sockets, IPC)
- Event-based routing (defmethod)
- Functional/reactive patterns
- Lightweight (~20KB)
- Works across JVM/Babashka/Scittle

| Feature | Socket.IO | Sente-Lite |
|---------|-----------|-----------|
| **Language** | JavaScript | Clojure/ClojureScript |
| **Size** | ~100KB | ~20KB |
| **Fallback** | Automatic (polling) | Manual (transport layer) |
| **Rooms** | Built-in | Custom via modules |
| **Namespaces** | Built-in | Event routing |
| **Middleware** | Yes | Custom handlers |
| **Transport** | WebSocket only | Any bidirectional |
| **Serialization** | JSON | EDN |
| **Async** | Callbacks/Promises | Atoms/Reactions |
| **State Sync** | Manual | Atoms + watchers |
| **Modules** | Limited | 30+ patterns |
| **Learning Curve** | Medium | Low (if Clojure familiar) |

**When to Use Socket.IO**:
- JavaScript-only stack
- Need automatic fallback to polling
- Large Node.js ecosystem
- Team familiar with JavaScript

**When to Use Sente-Lite**:
- Clojure/ClojureScript stack
- Need transport abstraction
- Want minimal, composable design
- Need to work with Babashka/Scittle
- Building modular real-time features
- Want functional/reactive patterns

**Key Difference**: Socket.IO is a complete solution with batteries included. Sente-lite is a minimal foundation designed for composability—you build what you need as modules on top.

---

## Core Sente Features

### Auto-Buffering & Event Batching

**What is Auto-Buffering?**

Sente automatically buffers outgoing messages and batches them together before sending. This is a core optimization that reduces network overhead:

```
Application sends events:
  Event 1 → [buffered]
  Event 2 → [buffered]
  Event 3 → [buffered]
  
At optimal time (or buffer full):
  [Event 1, Event 2, Event 3] → Single network packet
```

**How It Works**:
1. Application sends events via `send!` or `send-to-client!`
2. Events are queued in an internal buffer
3. Buffer is flushed at optimal time (usually milliseconds)
4. Multiple events sent in single network packet
5. Receiver unpacks and processes each event

**Benefits**:
- ✅ Reduces network overhead (fewer packets)
- ✅ Improves bandwidth efficiency
- ✅ Especially beneficial over Ajax (high overhead per request)
- ✅ Transparent to application code
- ✅ Automatic—no configuration needed

**Example**:
```clojure
;; Without batching: 3 separate network requests
(sente/send-to-client! uid [:event/1 data1])
(sente/send-to-client! uid [:event/2 data2])
(sente/send-to-client! uid [:event/3 data3])

;; With auto-batching: 1 network request with 3 events
;; Sente automatically batches these together
;; Application code is identical
```

**Bandwidth Savings**:
- Over WebSocket: ~20-30% reduction (less critical)
- Over Ajax: ~50-80% reduction (very significant)
- Especially important for high-frequency events (metrics, logging, etc.)

**Buffering Behavior**:
- Events are buffered for a short time (typically 10-50ms)
- Buffer is flushed when:
  - Time window expires
  - Buffer reaches size limit
  - Explicit flush is called
  - Connection closes

**Configuration**:
```clojure
;; Server-side (if configurable in Sente)
(start-server 
  {:event-buffer-ms 25      ; Flush every 25ms
   :event-buffer-size 100}) ; Or when 100 events queued
```

**Use Cases Where Batching Shines**:
- High-frequency logging (100+ messages/second)
- Metrics collection (CPU, memory, etc.)
- Real-time analytics events
- Presence updates
- Cursor position tracking
- Any scenario with many small events

**Important Note**: Event ordering is maintained within a batch. Events are processed in the order they were sent, even when batched together.

### Simple Implementation

Event buffering is straightforward to implement. Here's a minimal example for sente-lite:

```clojure
;; Server-side event buffer with backpressure
(def event-buffer (atom {})) ; {uid -> {:events [...] :size N}}
(def max-buffer-size 1000)   ; Max events per client

(defn buffer-event [uid event-id data]
  "Queue event for batching, return :ok or :backpressure"
  (swap! event-buffer
    (fn [buffers]
      (let [client-buf (get buffers uid {:events [] :size 0})
            new-size (inc (:size client-buf))]
        (if (>= new-size max-buffer-size)
          ;; Backpressure: buffer full
          buffers
          ;; Add to buffer
          (assoc buffers uid
            {:events (conj (:events client-buf) [event-id data])
             :size new-size})))))
  
  ;; Return status
  (let [buf-size (get-in @event-buffer [uid :size] 0)]
    (if (>= buf-size max-buffer-size)
      :backpressure
      :ok)))

(defn flush-buffer [uid]
  "Send all buffered events to client"
  (when-let [{:keys [queue]} (get @send-buffer uid)]
    (when (seq queue)
      ;; Convert queue to vector for sending
      (let [events (vec queue)]
        ;; Send all events in one message
        (sente/send-to-client! uid [:batch/events events])
        ;; Clear buffer
        (swap! send-buffer dissoc uid)))))

;; Flush periodically (e.g., every 25ms)
(defn start-buffer-flusher []
  (future
    (loop []
      (Thread/sleep 25)
      ;; Flush all buffers
      (doseq [uid (keys @send-buffer)]
        (flush-buffer uid))
      (recur))))

;; Application code: check backpressure status
(case (buffer-event uid :metric/cpu {:value 45})
  :ok (log! :info :metric-buffered)
  :backpressure (do
    (log! :warn :backpressure-triggered {:uid uid})
    ;; Options: queue elsewhere, drop, or notify sender
    ))
```

**Client-side**:
```clojure
;; Receive batched events
(defmethod handle-message :batch/events
  [{:keys [data]}]
  (doseq [[event-id event-data] data]
    ;; Process each event as if it came individually
    (handle-event event-id event-data)))
```

### Dual-Purpose: Buffering + Backpressure

The same queue provides both optimizations:

**1. Message Bundling** (bandwidth optimization):
- Queue messages for 25ms
- Send all together in one packet
- 50-80% bandwidth reduction over Ajax

**2. Backpressure** (flow control):
- Track queue size per client
- Return `:ok` or `:backpressure` status
- Application decides what to do when backpressured
- Prevents silent message loss

**Important: Two Distinct Limits**

Message bundling has two separate constraints:

**1. Frame Size Limit** (~64KB typical):
- Determines which messages fit in a single WebSocket frame
- When adding a message would exceed frame size → flush current frame, start new one
- Message still gets queued (for next frame)
- Automatic, transparent to application
- Prevents oversized frames that would be rejected

**2. Total Buffer Limit** (e.g., 1000 events):
- Determines when to tell application to back off
- When buffer reaches this limit → return `:backpressure`
- Application must handle it (queue elsewhere, drop, notify, block)
- Application-visible, requires explicit handling
- Prevents unbounded memory growth

**Flush Triggers**:
- Time expires (e.g., 25ms)
- Frame size would be exceeded (automatic)
- Buffer full (backpressure signal to app)

**Implementation with Frame Size Awareness Using PersistentQueue**:
```clojure
;; Sender-side buffer using PersistentQueue
;; Clojure/Babashka version:
(def send-buffer (atom {})) ; {uid -> {:queue PersistentQueue :size N :bytes B}}

;; ClojureScript/Scittle version:
(def send-buffer (atom {})) ; {uid -> {:queue #queue [] :size N :bytes B}}

(def max-buffer-size 1000)   ; Max events per client
(def max-frame-size 65536)   ; Max bytes per frame

(defn buffer-event [uid event-id data]
  "Queue event for bundling, flush if frame size exceeded"
  (let [event-bytes (count (pr-str [event-id data]))]
    (swap! send-buffer
      (fn [buffers]
        (let [client-buf (get buffers uid {:queue #queue [] :size 0 :bytes 0})
              new-size (inc (:size client-buf))
              new-bytes (+ (:bytes client-buf) event-bytes)]
          
          ;; Check constraints
          (cond
            ;; Frame size exceeded: flush before adding
            (> new-bytes max-frame-size)
            (do
              ;; Flush existing events first
              (flush-buffer uid)
              ;; Then add new event to fresh buffer
              (assoc buffers uid
                {:queue (conj #queue [] [event-id data])
                 :size 1
                 :bytes event-bytes}))
            
            ;; Buffer full: don't add
            (>= new-size max-buffer-size)
            buffers
            
            ;; OK to add
            :else
            (assoc buffers uid
              {:queue (conj (:queue client-buf) [event-id data])
               :size new-size
               :bytes new-bytes})))))
    
    ;; Return status
    (let [{:keys [size bytes]} (get @send-buffer uid {:size 0 :bytes 0})]
      (cond
        (>= size max-buffer-size) :backpressure
        (>= bytes max-frame-size) :frame-full
        :else :ok))))

**Implementation is minimal but frame-aware**:
1. Queue messages in atom (with size AND byte tracking)
2. Flush periodically (timer)
3. Check buffer size AND frame size before adding
4. Flush if frame size would be exceeded
5. Return status to caller
6. Clear the queue after flush

### Migration Strategy: Incremental PersistentQueue Adoption

**Phase 1: Drop-in Replacement (max-queue-size = 1)**:
```clojure
(def max-buffer-size 1)   ; Queue size of 1 = current behavior
(def max-frame-size 65536) ; Still respect frame limits
```
- ✅ No functional change (messages sent immediately)
- ✅ Replaces current implementation with PersistentQueue
- ✅ Enables infrastructure for future phases
- ✅ Tests existing behavior with new data structure
- ✅ Low risk, easy to validate

**Phase 2: Enable Sender-Side Bundling**:
```clojure
(def max-buffer-size 100)  ; Bundle up to 100 messages
(def flush-ms 25)          ; Flush every 25ms
```
- ✅ Reduce bandwidth (50-80% over Ajax)
- ✅ Maintain frame size awareness
- ✅ Return backpressure status to application
- ✅ Application can handle backpressure

**Phase 3: Enable Receiver-Side Queue**:
```clojure
(def recv-queue (atom {:queue #queue [] :bytes 0}))
(def max-recv-queue-size 5000)
(def max-recv-bytes 100000000) ; 100MB
```
- ✅ Decouple reception from processing
- ✅ Handle slow handlers without dropping messages
- ✅ Provide backpressure visibility
- ✅ Prevent silent message loss

**Why This Order?**:
1. **Phase 1**: Infrastructure change, no behavior change
2. **Phase 2**: Sender-side optimization (bandwidth savings)
3. **Phase 3**: Receiver-side reliability (message preservation)

**Receiver-Side First Alternative**:
If receiver-side feels more useful/easier:
- Start with Phase 1 (infrastructure)
- Skip Phase 2, go directly to Phase 3
- Add sender-side bundling later if needed
- Receiver-side prevents message loss (more critical)

### Compression in the Pipeline

**Where Should Compression Happen?**

**Option 1: Before Queue** (compress each message):
```
Application → Compress → Send-Queue → Flush → Send
```
- ❌ Can't bundle compressed messages (each is different)
- ❌ Compression overhead per message
- ❌ Smaller queue (but less important benefit)

**Option 2: After Queue** (compress batch at flush):
```
Application → Send-Queue → Compress Batch → Send
```
- ✅ Bundle messages first (better compression ratio)
- ✅ Compress once per batch (more efficient)
- ✅ Backpressure limits based on uncompressed size
- ✅ Cleaner architecture (queue handles bundling, compression is separate)
- ✅ Fewer compression operations overall

**Recommended: Compress After Queue**

**Why**:
1. **Better compression ratio**: Compressing a batch of 100 messages is more efficient than compressing each individually
2. **Fewer operations**: One compress per batch vs. one per message
3. **Cleaner separation**: Queue handles bundling, compression is a separate concern
4. **Backpressure clarity**: Limits based on actual message count, not compressed size

**Implementation**:
```clojure
(defn flush-buffer [uid]
  "Send all buffered events to client (with optional compression)"
  (when-let [{:keys [queue]} (get @send-buffer uid)]
    (when (seq queue)
      (let [events (vec queue)
            ;; Serialize batch
            serialized (pr-str [:batch/events events])
            ;; Compress if enabled
            compressed (if (compression-enabled?)
                         (compress-gzip serialized)
                         serialized)]
        ;; Send compressed batch
        (sente/send-to-client! uid compressed)
        ;; Clear buffer
        (swap! send-buffer dissoc uid)))))
```

**Backpressure Limits**:
- Track uncompressed message count and byte size
- Compression happens at flush (doesn't affect backpressure decisions)
- Application sees actual message volume, not compressed size

### Compression Frame Size Optimization

**The Challenge**: Compression ratio is unknown until after compression, but frame size limits are fixed.

**Problem Scenarios**:
1. Fill frame with uncompressed messages, then compress → may exceed frame size
2. Use conservative estimate → waste frame space (only use 30% of available)
3. Compress, check, retry → complex logic and potential overhead

**Option 1: Conservative Estimate** (recommended for Phase 2):
```clojure
(def max-frame-size 65536)
(def compression-ratio 0.3)  ; Assume 70% compression
(def safe-uncompressed-size (* max-frame-size compression-ratio))

;; Queue messages until safe-uncompressed-size, then flush
```
- ✅ Simple to implement
- ✅ Safe (never exceeds frame size)
- ✅ Good enough for most scenarios
- ❌ Wastes frame space (only use 30% of available)

**Option 2: Two-Phase Flush** (optimize later):
```clojure
(defn flush-buffer [uid]
  (let [events (vec queue)
        serialized (pr-str [:batch/events events])
        compressed (compress-gzip serialized)]
    
    ;; Phase 1: Check if compressed fits
    (if (> (count compressed) max-frame-size)
      ;; Phase 2: Binary search to find max messages that fit
      (let [fitting-events (find-max-fitting-events events)]
        (if fitting-events
          (do
            (send-compressed fitting-events)
            (queue-remaining-events (drop (count fitting-events) events)))
          nil))  ; Nothing fits (shouldn't happen)
      ;; Compressed fits: send as-is
      (send-compressed events))))
```
- ✅ Optimal bundling (use full frame)
- ✅ Handles compression uncertainty
- ✅ No wasted frame space
- ❌ More complex (binary search or loop)

**Recommendation**:
1. **Phase 2**: Use Option 1 (conservative estimate)
   - Simple, safe, good enough
   - Compression still provides 50-80% bandwidth savings
   - Wasting 70% of frame space is acceptable trade-off
   
2. **Later Optimization**: Implement Option 2 if needed
   - Only if frame space utilization becomes critical
   - Measure actual compression ratios first
   - May not be worth the complexity

### How Sente Addresses This

**Sente's Approach**:
- ✅ Provides gzip-wrapping packer (v1.21+)
- ✅ Packer composition (wrap any packer with gzip)
- ❌ No explicit frame size optimization
- ❌ No message bundling (no queue)
- ❌ Compression per-message, not per-batch

**Why Sente Doesn't Solve It**:
1. Sente doesn't do message bundling (no queue infrastructure)
2. Compression is per-message, not per-batch
3. No frame size awareness in packer layer
4. Relies on WebSocket protocol to handle oversized frames

**Sente-Lite Improvement**:
- ✅ Bundle messages first (better compression ratio)
- ✅ Compress batch, not individual messages
- ✅ Frame size awareness (conservative estimate)
- ✅ Two-phase optimization (if needed later)
- ✅ Unified PersistentQueue architecture

**Comparison**:
| Feature | Sente | Sente-Lite |
|---------|-------|-----------|
| Message bundling | ❌ No | ✅ Yes (Phase 2) |
| Compression | ✅ Per-message | ✅ Per-batch |
| Frame size aware | ❌ No | ✅ Yes |
| Batch compression ratio | N/A | 50-80% savings |
| Optimization strategy | N/A | Conservative → Two-phase |

This is a key architectural advantage of sente-lite: **bundling + compression together** for better efficiency than Sente's per-message approach.

**Backpressure Strategies**:
```clojure
(defn send-with-backpressure! [uid event-id data]
  (case (buffer-event uid event-id data)
    :ok (log! :trace :event-buffered)
    :frame-full
    (do
      ;; Frame is full, new message will be in next frame
      (log! :debug :frame-full {:uid uid})
      :ok) ; Message was queued for next frame
    :backpressure
    (case (get-backpressure-strategy)
      :queue (queue-to-persistent-store uid event-id data)
      :drop (log! :warn :event-dropped {:uid uid :event event-id})
      :notify (notify-client-backpressure uid)
      :block (wait-for-buffer-space uid))))
```

**Why This Works**:
- ✅ Single queue serves two purposes
- ✅ No additional overhead
- ✅ Transparent to application
- ✅ Application controls backpressure response
- ✅ Prevents silent message loss
- ✅ Enables intelligent flow control

**For Sente-Lite**:
This could be a simple module that wraps the core send functions:
```clojure
;; sente_lite.modules.buffering-with-backpressure
(defn make-buffered-sender [flush-ms max-buffer-size]
  ;; Returns wrapped send-to-client! with buffering + backpressure
  ;; Returns {:status :ok|:backpressure :queue-size N}
  )
```

The beauty is that buffering and backpressure are the same mechanism—you get both optimizations with minimal code.

### Why Sente Doesn't Use Buffering for Backpressure

**Interesting Gap**: Sente implements event batching/buffering but does NOT use it for backpressure. Instead, it relies on `core.async/put!` which throws exceptions when buffer fills.

**Why the Separation**:
- **Buffering** is time-based (flush every 25ms) and transparent
- **Backpressure** is size-based (buffer full) and requires application awareness
- Sente treats them as separate concerns
- Sente's buffering is automatic—application doesn't interact with it
- Backpressure requires explicit status return to caller

**Sente-Lite Opportunity**:
Unify them in a single module where:
- Buffering is still automatic (transparent)
- But backpressure status is available to application if needed
- Application can opt-in to backpressure handling
- Same queue serves both purposes

This is a design choice—Sente chose separation of concerns, but sente-lite can provide a simpler unified approach where the buffering queue naturally provides backpressure visibility without extra complexity.

## Receiver-Side Queue & Backpressure

**Sente's Receiver Implementation**:
- ✅ Provides `ch-recv` - a core.async channel for received messages
- ✅ Channel has default buffer (usually 1024 messages)
- ✅ Application hooks event handlers to channel
- ❌ No explicit backpressure handling
- ❌ If handlers slow, buffer fills and messages drop
- ❌ No visibility into receiver backpressure
- ❌ No queue for handling slow consumers

**Important: Buffer is Message-Count Based, Not Byte-Size Based**:
- core.async buffer size = number of messages, not bytes
- `(chan 1024)` = buffer 1024 messages (regardless of size)
- A 1MB message counts as 1 message
- 1000 x 1KB messages count as 1000 messages
- No protection against large message floods
- Backpressure is message-count based, not byte-size based

**The Problem**:
- Sender-side backpressure tells sender to slow down
- But receiver-side backpressure is missing
- If handlers can't keep up, messages silently drop
- No way to know receiver is backed up

**Sente-Lite Should Implement**:

A **receiver-side queue module** that:
1. **Decouples reception from processing** - queue messages as they arrive
2. **Handles slow consumers** - queue persists if handlers are slow
3. **Provides backpressure visibility** - application knows receiver status
4. **Prevents message loss** - queue persists before handlers process

**Why PersistentQueue?**

Use `PersistentQueue` for the receiver queue:
- ✅ FIFO semantics (first-in, first-out)
- ✅ O(1) conj (add) and O(1) peek/pop (remove)
- ✅ Immutable (safe for concurrent access)
- ✅ Built-in to Clojure/ClojureScript (no external dependency)
- ✅ Efficient for queue operations
- ✅ Works seamlessly with atoms
- ❌ Not suitable for priority queuing (if needed later)

**Platform-Specific Access**:
- **Clojure/Babashka**: `clojure.lang.PersistentQueue/EMPTY`
- **ClojureScript/Scittle**: `#queue []` or `cljs.core.PersistentQueue`

**Example Implementation**:
```clojure
;; Receiver-side queue using PersistentQueue
;; Clojure/Babashka version:
(def recv-queue (atom {:queue clojure.lang.PersistentQueue/EMPTY 
                       :bytes 0 
                       :processing false}))

;; ClojureScript/Scittle version:
(def recv-queue (atom {:queue #queue []
                       :bytes 0 
                       :processing false}))
(def max-recv-queue-size 5000)
(def max-recv-bytes 100000000) ; 100MB

;; Receive message and queue it
(defn queue-received-message [event-id data]
  "Queue received message, return status"
  (let [msg-bytes (count (pr-str [event-id data]))]
    (swap! recv-queue
      (fn [q]
        (let [new-size (inc (count (:queue q)))
              new-bytes (+ (:bytes q) msg-bytes)]
          (cond
            ;; Queue full: backpressure
            (>= new-size max-recv-queue-size) q
            ;; Bytes limit: backpressure
            (>= new-bytes max-recv-bytes) q
            ;; OK to add
            :else
            (-> q
              (update :queue conj [event-id data])
              (assoc :bytes new-bytes))))))
    
    ;; Return status
    (let [{:keys [queue bytes]} @recv-queue
          queue-size (count queue)]
      (cond
        (>= queue-size max-recv-queue-size) :backpressure
        (>= bytes max-recv-bytes) :backpressure
        (> queue-size 100) :slow
        :else :ok))))

;; Process queued messages
(defn process-queue []
  "Process messages from queue with backpressure awareness"
  (when-not (:processing @recv-queue)
    (swap! recv-queue assoc :processing true)
    (try
      (loop []
        (when-let [[event-id data] (peek (:queue @recv-queue))]
          ;; Process message
          (try
            (handle-event event-id data)
            (catch Exception e
              (log! :error :handler-error {:event event-id :error e})))
          ;; Remove from queue and update bytes
          (let [msg-bytes (count (pr-str [event-id data]))]
            (swap! recv-queue
              (fn [q]
                (-> q
                  (update :queue pop)
                  (update :bytes - msg-bytes)))))
          (recur)))
      (finally
        (swap! recv-queue assoc :processing false)))))

;; Start processing loop
(defn start-receiver-processor []
  (future
    (loop []
      (Thread/sleep 10) ; Process every 10ms
      (process-queue)
      (recur))))
```

**Benefits**:
- ✅ Decouples reception from processing
- ✅ Slow handlers don't drop messages
- ✅ Application sees receiver backpressure status
- ✅ Can implement retry, persistence, prioritization
- ✅ Prevents silent message loss

**Backpressure Strategies**:
```clojure
(case (queue-received-message event-id data)
  :ok (log! :trace :message-queued)
  :slow (log! :warn :receiver-slow {:queue-size queue-size})
  :backpressure (do
    ;; Options: persist to disk, notify sender, drop oldest
    (case (get-receiver-backpressure-strategy)
      :persist (persist-to-disk event-id data)
      :notify (notify-sender-receiver-backpressure)
      :drop (log! :error :receiver-queue-full))))
```

**Why This Matters**:
- Sender-side buffering prevents sender overload
- Receiver-side queue prevents receiver overload
- Together they provide end-to-end flow control
- Prevents silent message loss on both sides

---

## Protocol Enhancements (Sente v1.21+)

Sente v1.21 introduced several important protocol-level enhancements that improve performance, flexibility, and reliability:

### 1. Binary Serialization & Packers

**MessagePack Packer** (v1.21+):
- High-speed binary serialization
- Smaller payload size than EDN
- Better performance for large data transfers
- UUID support

```clojure
;; Use MessagePack packer for binary serialization
(require '[taoensso.sente.packers.msgpack :as msgpack])

(def packer (msgpack/make-packer))

;; Server configuration
(start-server {:packer packer})

;; Benefits:
;; - Smaller payloads (binary vs text)
;; - Faster serialization/deserialization
;; - Better for large data transfers
;; - Ideal for metrics, logging, streaming data
```

**Gzip-Wrapping Packer** (v1.21+):
- Compress messages on the wire
- Transparent compression/decompression
- Reduces bandwidth usage
- Works with any packer (EDN, MessagePack, etc.)

```clojure
;; Wrap any packer with gzip compression
(require '[taoensso.sente.packers.gzip :as gzip])

(def compressed-packer 
  (gzip/make-gzip-packer msgpack/make-packer))

;; Benefits:
;; - Reduces bandwidth by 50-80% for text data
;; - Transparent to application code
;; - Ideal for high-volume messaging
;; - Works with any serialization format
```

### 2. WebSocket Binary Type

**Default Change** (v1.21+):
- Changed from `blob` to `arraybuffer`
- Better performance for binary data
- More efficient memory usage
- Enables true binary communication

```clojure
;; WebSocket now defaults to arraybuffer
;; This enables efficient binary data transfer
;; No configuration needed—automatic

;; Benefits:
;; - Faster binary data handling
;; - Lower memory overhead
;; - Better for large transfers
;; - Native browser support
```

### 3. Backpressure & Flow Control

**Connection State Recovery** (v1.21+):
- Recover from temporary disconnections
- Maintain message ordering
- Prevent message loss
- Configurable recovery window

```clojure
;; Server configuration with connection recovery
(start-server 
  {:connection-state-recovery
   {:backup-secs 5  ; Keep state for 5 seconds
    :max-msgs 100}}) ; Max messages to buffer

;; Benefits:
;; - Automatic reconnection without data loss
;; - Maintains message ordering
;; - Prevents duplicate processing
;; - Transparent to application code
```

**WebSocket Ping/Timeout** (v1.21+):
- Enabled by default
- Detects dead connections
- Configurable timeout (default: 10 seconds)
- Prevents resource leaks

```clojure
;; Server configuration
(start-server 
  {:ws-ping-timeout-ms 10000  ; 10 second timeout
   :ws-kalive-ms 5000})       ; Keep-alive interval

;; Benefits:
;; - Detects disconnected clients
;; - Prevents zombie connections
;; - Automatic cleanup
;; - Reduces server resource usage
```

### 4. Flexible Packer Architecture

**Custom Packer Support** (v1.21+):
- Define custom serialization formats
- Compose packers (e.g., compress + encrypt)
- Dynamic packer selection
- Full control over wire format

```clojure
;; Custom packer example
(defn make-custom-packer []
  {:pack (fn [data]
           ;; Custom serialization logic
           (-> data
               (serialize-custom)
               (encrypt-aes)
               (compress)))
   :unpack (fn [packed]
             ;; Custom deserialization logic
             (-> packed
                 (decompress)
                 (decrypt-aes)
                 (deserialize-custom)))})

;; Use in server
(start-server {:packer (make-custom-packer)})

;; Benefits:
;; - Encryption support
;; - Custom compression algorithms
;; - Domain-specific formats
;; - Full flexibility
```

### 5. Improved Reliability

**Better Error Handling** (v1.21+):
- Don't throw on Ajax read timeouts (configurable)
- Better WebSocket close handling
- More informative error messages
- Graceful degradation

**Lightweight Timer Implementation** (v1.21+):
- Custom timer implementation
- Reduced resource usage
- Better performance
- More reliable timeout handling

### Implementation Dependencies: Binary Mode → Compression

**Important**: Compression requires binary mode first.

**Why**:
- Text EDN compression: ~30-40% reduction (modest)
- Binary + compression: ~70-90% reduction (significant)
- Gzip designed for binary data

**Implementation Order**:
1. **Phase 1**: Binary packer (MessagePack or similar)
   - Serialize to bytes instead of text
   - 50-70% smaller payloads
   
2. **Phase 2**: Compression wrapper
   - Wrap binary packer with gzip
   - Additional 30-50% reduction
   - Total: 70-90% reduction vs text EDN

### Compression Availability Across Platforms

**JVM/Babashka**:
- ✅ `java.util.zip.GZIPOutputStream/GZIPInputStream`
- ✅ Built-in, no dependencies
- ✅ Always available

**Browser/Scittle**:
- ✅ `pako` library (high-speed zlib port)
- ⚠️ Requires external dependency (CDN or npm)
- ✅ Works in all modern browsers
- ✅ Implements DEFLATE algorithm (same as gzip)

**Node.js/nbb**:
- ✅ Native `zlib` module
- ✅ Built-in, no dependencies

**Challenge**: No universal compression across all platforms without external dependencies.

### Standardize on GZIP

**Recommendation**: Use **GZIP** (gzip format with DEFLATE algorithm) across all platforms.

**Why GZIP?**
- ✅ Industry standard
- ✅ Widely supported everywhere
- ✅ Includes header and CRC32 checksum (error detection)
- ✅ Works with standard gzip tools
- ✅ Pako implements it (`pako.gzip()`, `pako.ungzip()`)
- ✅ Java has `GZIPOutputStream/GZIPInputStream`
- ✅ Node.js has native gzip support

**Pako Details**:
- ✅ JavaScript port of zlib v1.2.8
- ✅ Binary-equal to C implementation
- ✅ ~10x faster than other JS implementations
- ✅ Supports: `gzip()`, `ungzip()`, `deflate()`, `inflate()`
- ✅ For sente-lite: use `pako.gzip()` and `pako.ungzip()`

**Platform-Specific Implementation**:
```clojure
;; Server (JVM/Babashka)
(import [java.util.zip GZIPOutputStream GZIPInputStream]
        [java.io ByteArrayOutputStream ByteArrayInputStream])

(defn compress-gzip [data]
  (let [out (ByteArrayOutputStream.)
        gz (GZIPOutputStream. out)]
    (.write gz data)
    (.close gz)
    (.toByteArray out)))

;; Browser (Scittle) - requires pako CDN
;; <script src="https://cdn.jsdelivr.net/npm/pako@2.1.0/dist/pako.min.js"></script>
(defn compress-gzip [data]
  (js/pako.gzip data))

;; Node.js (nbb)
(require '[node.zlib :as zlib])

(defn compress-gzip [data]
  (zlib/gzipSync data))
```

**Solution**: Platform-specific GZIP implementation
- JVM: Built-in `java.util.zip.GZIPOutputStream`
- Browser: `pako.gzip()` (via CDN)
- Node.js: Native `zlib.gzipSync()`
- All use same GZIP format (interoperable)

### Scittle/ClojureScript Pako Integration

**Current State**:
- ❌ No built-in gzip in Scittle/ClojureScript
- ✅ Pako is standard JavaScript solution
- ✅ No special shim needed—direct JavaScript interop

**Simple Pako Wrapper**:
```clojure
;; sente_lite/compression/pako.cljs
(ns sente-lite.compression.pako
  "Pako gzip compression wrapper for Scittle/ClojureScript")

(defn gzip
  "Compress data using pako.gzip"
  [data]
  (when-not (exists? js/pako)
    (throw (js/Error. "pako library not loaded. Add script tag to HTML.")))
  (js/pako.gzip data))

(defn ungzip
  "Decompress gzip data using pako.ungzip"
  [compressed-data]
  (when-not (exists? js/pako)
    (throw (js/Error. "pako library not loaded. Add script tag to HTML.")))
  (js/pako.ungzip compressed-data))

(defn gzip-string
  "Compress string to gzip bytes"
  [s]
  (gzip s))

(defn ungzip-string
  "Decompress gzip bytes to string"
  [compressed-data]
  (js/pako.ungzip compressed-data #js {:to "string"}))
```

**HTML Setup**:
```html
<!-- Load pako before Scittle -->
<script src="https://cdn.jsdelivr.net/npm/pako@2.1.0/dist/pako.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.js"></script>

<script type="application/x-scittle">
  (require '[sente-lite.compression.pako :as gzip])
  
  ;; Use it
  (def compressed (gzip/gzip-string "Hello, World!"))
  (def decompressed (gzip/ungzip-string compressed))
</script>
```

**Key Points**:
- ✅ No special shim needed—just JavaScript interop
- ✅ Load pako via CDN before Scittle
- ✅ Access via `js/pako` in ClojureScript
- ✅ Simple wrapper functions for Clojure-style API
- ✅ Error checking for missing library
- ✅ Pako is de facto standard (no better alternatives)

## Backpressure & Flow Control

**Sente's Current Backpressure**:
- ⚠️ Limited backpressure support
- Uses `core.async/put!` without backpressure
- If buffer fills (1024 + buffer-size), `put!` throws exception
- Exception drops messages (silent failure)
- Relies on server-level backpressure (if available)

**The Problem**:
- Receiver can't keep up with sender
- Buffer fills up
- Exception thrown
- Message dropped silently
- Connection may close

**Server-Specific Issues**:
- **http-kit**: No backpressure mechanism
- **Immutant (servlet)**: No way to exert backpressure
- **Immutant (standalone)**: Has backpressure support

**Sente-Lite Should Implement**:

1. **Explicit Backpressure Module** (recommended)
   - Flow control at application level
   - Don't rely on server backpressure
   - Sender checks if receiver is ready
   - Pause sending if buffer fills

```clojure
;; Backpressure-aware sending
(defn send-with-backpressure! [uid event-id data]
  "Send only if buffer has space, otherwise queue or reject"
  (if (buffer-has-space? uid)
    (sente/send-to-client! uid [event-id data])
    (handle-backpressure uid event-id data)))

(defn handle-backpressure [uid event-id data]
  "Options: queue, drop, or notify sender"
  (case (get-backpressure-strategy)
    :queue (queue-event uid event-id data)
    :drop (log-dropped-event uid event-id)
    :notify (notify-sender-backpressure uid)))
```

2. **Buffer Management**
   - Configurable buffer sizes
   - Monitor buffer usage
   - Alert when approaching limits
   - Metrics on buffer pressure

3. **Graceful Degradation**
   - Handle buffer overflow gracefully
   - Don't drop messages silently
   - Implement retry or queue persistence
   - Notify application of backpressure

### Protocol Enhancement Recommendations for Sente-Lite

Based on Sente v1.21+ enhancements, sente-lite should consider:

1. **Add Binary Packer Module** (prerequisite for compression)
   - MessagePack or similar binary serialization
   - 50-70% smaller payloads
   - Prerequisite for effective compression

2. **Add Compression Module** (depends on binary packer)
   - Platform-specific gzip (JVM/Browser/Node.js)
   - Additional 30-50% reduction on binary
   - Total: 70-90% reduction vs text EDN

3. **Add Connection Recovery Module**
   - Automatic reconnection with state preservation
   - Message buffering and ordering

4. **Add Custom Packer Support**
   - Enable encryption, custom formats
   - Composable packer architecture

5. **Add Backpressure Handling Module**
   - Flow control for high-volume messaging
   - Buffer management
   - Prevent overwhelming receivers

### Comparison: Protocol Features

| Feature | Purpose | Impact |
|---------|---------|--------|
| **MessagePack** | Binary serialization | 50-80% smaller payloads |
| **Gzip Compression** | Bandwidth optimization | 50-80% bandwidth reduction |
| **Connection Recovery** | Reliability | Zero message loss on reconnect |
| **WebSocket Ping** | Health checking | Prevent zombie connections |
| **Custom Packers** | Flexibility | Enable encryption, custom formats |
| **Backpressure** | Flow control | Prevent receiver overwhelm |

---

## Module Development Guidelines

### Principles
1. **Zero impact**: Don't affect core sente-lite
2. **Opt-in**: Users explicitly enable
3. **Configurable**: Sensible defaults, customizable
4. **Tested**: Unit + integration tests
5. **Documented**: Clear examples and API docs
6. **Composable**: Work well with other modules

### Structure
```clojure
(ns sente-lite.logging.remote
  "Remote logging via sente channels"
  (:require [taoensso.trove :as trove]
            [sente-lite.client :as client]))

;; Public API
(defn make-remote-log-fn [...])
(defn configure! [...])

;; Internal implementation
(defn- send-log-batch [...])
(defn- handle-backpressure [...])
```

### Testing
- Unit tests for each function
- Integration tests with sente-lite
- Example scripts demonstrating usage
- Performance benchmarks

---

**Last Updated**: 2025-12-18  
**Status**: Proposed  
**Next Review**: After Phase 1 MVP implementation
