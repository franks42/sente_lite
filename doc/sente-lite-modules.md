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

## Other Potential Modules

### 1. Metrics & Observability
Track connection metrics, message throughput, latency

### 2. Retry & Circuit Breaker
Advanced reconnection strategies, circuit breaker pattern

### 3. Message Compression
Compress large messages before sending

### 4. Rate Limiting
Per-client rate limiting, backpressure handling

### 5. Authentication Middleware
Token-based auth, permission checking

### 6. Request/Response Tracking
Correlation IDs, request tracing

### 7. Caching Layer
Client-side message caching, offline support

### 8. Analytics
Event tracking, user behavior analysis

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
