# Sente-lite Test Use Cases Summary

## Overview

Current test suite organized in 6 phases with 20 test scripts covering telemetry, async, WebSocket, channel pub/sub, and connection management functionality.

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
│  Phase 3: WebSocket Foundation (3 tests + 5 BB client tests)   │
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
│  ├──────────────────────────────────────┤                       │
│  │ 3d) BB Client Tests (5 tests)        │                       │
│  │     BB Server ↔ BB Native WS Client  │                       │
│  │     - Startup, Connection, Echo      │                       │
│  │     - Minimal tests                  │                       │
│  └──────────────────────────────────────┘                       │
│                                                                   │
│  Phase 4: Channel Pub/Sub with Real Clients (1 test) ✅         │
│  ┌──────────────────────────────────────┐                       │
│  │ 4a) Real Client Pub/Sub              │                       │
│  │     BB Server + 3 BB WS Clients      │                       │
│  │     - Channel subscriptions          │                       │
│  │     - Message publishing             │                       │
│  │     - Channel isolation validation   │                       │
│  └──────────────────────────────────────┘                       │
│                                                                   │
│  Phase 6: Connection Management (2 tests) ✅                     │
│  ┌──────────────────────────────────────┐                       │
│  │ 6a) Server Heartbeat                 │                       │
│  │     BB Server → Ping → BB Client     │                       │
│  │     - Periodic ping sending          │                       │
│  │     - Dead connection detection      │                       │
│  │     - Auto-close unresponsive        │                       │
│  ├──────────────────────────────────────┤                       │
│  │ 6b) Client State Tracking            │                       │
│  │     Managed Client Wrapper           │                       │
│  │     - State machine (6 states)       │                       │
│  │     - State change callbacks         │                       │
│  │     - Auto-pong to server pings      │                       │
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

### Phase 4: Channel Pub/Sub with Real Clients (NEW - 2025-10-26) ✅

**Purpose**: End-to-end validation of channel-based pub/sub messaging with real WebSocket connections

17. **bb_client_tests/04_channel_pubsub.bb** - Real Client Channel Pub/Sub ✅
    - **3 real WebSocket clients** using native Babashka client
    - **Channel subscription testing**:
      - client1 & client2 subscribe to "announcements" channel
      - client3 subscribes to "alerts" channel
    - **Message publishing**:
      - client1 publishes to "announcements" → client1 & client2 receive
      - client2 publishes to "alerts" → client3 receives
    - **Channel isolation validation**:
      - Messages don't leak between channels
      - Only subscribers receive published messages
    - **PASSING** after fixes:
      - Server: Added `broadcast-to-channel!` call in `:publish` handler
      - Test: Fixed validation to check `:channel-id` (not `:channel`)
      - Test: Fixed validation to check top-level fields (not nested `:message`)

```
┌──────────────────────────────────────────────────────────────┐
│  Phase 4 Architecture: Real Client Pub/Sub                  │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  BB Server (port 3000)                   3 BB Clients       │
│  ┌─────────────────────┐                 ┌─────────────┐   │
│  │ sente-lite.server   │                 │  client1    │   │
│  │  + channels system  │◄────subscribe───┤  (announce) │   │
│  │  + auto-create      │                 └─────────────┘   │
│  │  + broadcast        │                 ┌─────────────┐   │
│  │                     │◄────subscribe───┤  client2    │   │
│  │ Message Flow:       │                 │  (announce) │   │
│  │ 1. client1 publish  │                 └─────────────┘   │
│  │    ↓                │                 ┌─────────────┐   │
│  │ 2. channels/publish!│◄────subscribe───┤  client3    │   │
│  │    ↓                │                 │  (alerts)   │   │
│  │ 3. broadcast-to-    │                 └─────────────┘   │
│  │    channel!         │                                   │
│  │    ↓                │                                   │
│  │ 4. Deliver to all   │──────publish────►                 │
│  │    subscribers      │  (with channel isolation)         │
│  └─────────────────────┘                                   │
└──────────────────────────────────────────────────────────────┘
```

**Configuration**: Server with auto-create channels
```clojure
(def test-server
  (server/start-server!
   {:port 3000
    :host "localhost"
    :wire-format :json
    :channels {:auto-create true
               :default-config {:max-subscribers 100
                                :message-retention 5}}}))

;; Real WebSocket client with message handler
(def client1
  (ws/connect!
   {:uri "ws://localhost:3000/"
    :on-message (message-handler :client1)}))

;; Message handler - IMPORTANT: Convert CharBuffer to String
(defn message-handler [client-id]
  (fn [ws data last]
    (let [data-str (str data) ; babashka.http-client.websocket returns CharBuffer!
          msg (json/parse-string data-str true)]
      (swap! received-messages update client-id (fnil conj []) msg))))
```

**Critical Findings**:

1. **Server Broadcast Bug** (FIXED):
   - `:publish` handler called `channels/publish!` but never broadcasted
   - `channels/publish!` only tracks publication, doesn't deliver messages
   - Fix: Added `(broadcast-to-channel! channel-id message-data)` after publish
   - Impact: Fundamental missing feature that blocked all pub/sub functionality

2. **CharBuffer Discovery**:
   - `babashka.http-client.websocket` returns `java.nio.HeapCharBuffer` objects
   - Not strings like Java 11 WebSocket API
   - Must convert with `(str data)` before JSON parsing
   - Error was: `ClassCastException: java.nio.HeapCharBuffer cannot be cast to java.lang.String`

3. **Message Structure**:
   - Broadcasted messages have `:channel-id` (not `:channel`)
   - Message data is at top level (not nested under `:message`)
   - Server adds `:broadcast-time` timestamp automatically

**Test Results**: Phase 4 PASSED ✅
- All 3 clients connected successfully
- Subscriptions processed correctly
- Published messages delivered to correct subscribers
- Channel isolation working (no message leakage)

### Phase 6: Connection Management (NEW - 2025-10-26) ✅

**Purpose**: Production-ready connection management with heartbeat, state tracking, and auto-reconnection

#### Phase 6a: Server Heartbeat ✅

18. **bb_client_tests/05_heartbeat.bb** - Server Heartbeat Detection
    - **Server-side heartbeat mechanism**:
      - Background task sends pings every 30s (configurable: `:interval-ms`)
      - Tracks `:last-pong` timestamp per connection
      - Automatically closes connections that don't respond within 60s (configurable: `:timeout-ms`)
    - **Test with aggressive settings** (2s interval, 5s timeout):
      - Responsive client: Auto-responds to pings, stays connected
      - Unresponsive client: Ignores pings, closed after timeout
    - **PASSING** - Server detects and closes dead connections

**Configuration**:
```clojure
{:heartbeat {:enabled true
             :interval-ms 30000    ; Send ping every 30s
             :timeout-ms 60000}}   ; Close if no pong for 60s
```

**Implementation** (`src/sente_lite/server.cljc`):
- `send-heartbeat-pings!` - Check all connections and close dead ones
- `start-heartbeat-task!` - Background future with while loop
- `update-connection-pong!` - Track pong responses
- `:pong` handler - Update timestamp when client responds
- Clean shutdown: while loop exits when `@server-state` nil

**Telemetry Events**:
- `::heartbeat-check` - Periodic check initiated
- `::heartbeat-ping-sent` - Ping sent to connection
- `::heartbeat-timeout` - Connection exceeded timeout
- `::closing-dead-connection` - Closing unresponsive connection
- `::heartbeat-cleanup-complete` - Cleanup cycle complete

#### Phase 6b: Client State Tracking ✅

19. **bb_client_tests/06_state_tracking.bb** - Client Connection State Machine
    - **Managed WebSocket client wrapper** (`ws_client_managed.clj`):
      - State machine with 6 states
      - State change callbacks
      - Send validation (only works in `:open`)
      - Auto-pong response to server pings
    - **States**:
      - `:closed` - No connection
      - `:connecting` - Connection attempt in progress
      - `:open` - Connection established and healthy
      - `:closing` - Graceful close initiated
      - `:reconnecting` - Attempting to reconnect (Phase 6c)
      - `:failed` - Exceeded max reconnect attempts (Phase 6c)
    - **Test validates**:
      - Initial state (:closed)
      - Connection state progression (:closed → :connecting → :open)
      - Send only works in :open state
      - Graceful disconnect (:open → :closing → :closed)
      - All state transitions tracked via callback
    - **PASSING** - State tracking working correctly

**API** (`ws_client_managed.clj`):
```clojure
(def client
  (wsm/create-managed-client
   {:uri "ws://localhost:3000/"
    :on-state-change (fn [old-state new-state] ...)
    :on-message (fn [msg] ...)
    :heartbeat {:auto-pong true}}))

;; Methods
((:connect! client))       ; Initiate connection
((:disconnect! client))    ; Graceful close
((:send! client) message)  ; Send (validates state)
((:get-state client))      ; Current state keyword
```

**State Transitions**:
```
:closed ──connect!──> :connecting ──success──> :open
:open ──disconnect!──> :closing ──complete──> :closed
:open ──lost──> :closed
```

**Auto-Pong Feature**:
- Client automatically responds to server `:ping` messages
- Configurable: `{:heartbeat {:auto-pong true}}`
- No application code needed for heartbeat responses
- Maintains connection health transparently

**Key Implementation Details**:
- State tracked in atom: `{:status :state :ws client :reconnect-attempt 0 ...}`
- State change notifications via callback
- Message handler checks for ping and auto-responds
- Send validates connection is :open before transmitting

#### Phase 6c: Auto-Reconnection ✅

20. **bb_client_tests/07_reconnection.bb** - Automatic Reconnection with Exponential Backoff
    - **Auto-reconnection logic** in managed client:
      - Detects connection loss (not graceful disconnect)
      - Exponential backoff: `initial-delay * (multiplier ^ attempt)`
      - Jitter: ±25% randomness to prevent thundering herd
      - Max attempts tracking → `:failed` state
    - **Test 1: Successful reconnection**:
      - Client connects to server
      - Server stops (simulates connection loss)
      - Server restarts
      - Client automatically reconnects
      - Validates reconnection succeeded
    - **Test 2: Max attempts exceeded**:
      - Client tries to connect to stopped server
      - Retries with exponential backoff (500ms, 1s, 2s)
      - Reaches `:failed` state after max attempts
      - No further reconnection attempts
    - **PASSING** - Auto-reconnection working correctly

**Configuration**:
```clojure
(def client
  (wsm/create-managed-client
   {:uri "ws://localhost:3000/"
    :reconnect {:enabled true
                :max-attempts 5
                :initial-delay-ms 1000
                :max-delay-ms 30000
                :backoff-multiplier 2}}))
```

**Backoff Calculation**:
```
Attempt 0: 1000ms + jitter (±250ms) = 750-1250ms
Attempt 1: 2000ms + jitter (±500ms) = 1500-2500ms
Attempt 2: 4000ms + jitter (±1000ms) = 3000-5000ms
Attempt 3: 8000ms + jitter (±2000ms) = 6000-10000ms
Attempt 4: 16000ms + jitter (±4000ms) = 12000-20000ms
Attempt 5: 30000ms + jitter (capped at max-delay)
```

**State Transitions with Reconnection**:
```
:closed ──connect!──> :connecting ──success──> :open
:open ──lost──> :closed ──auto──> :reconnecting ──retry──> :connecting
:connecting ──failed──> :reconnecting (with backoff delay)
:reconnecting ──max attempts──> :failed
```

**Connection Loss Detection**:
- **Graceful close** (user called `:disconnect!`):
  - State was `:closing` before close → NO reconnection
- **Unexpected close** (connection lost):
  - State was `:open` before close → triggers reconnection
  - `:on-close` handler checks state and decides
- **Connection failure** (server down):
  - Exception in `:websocket` call → triggers reconnection

**Key Implementation** (`ws_client_managed.clj:119-153`):
```clojure
(defn- reconnect-with-backoff! [state]
  (let [current-attempt (:reconnect-attempt @state)
        max-attempts (get-in @state [:config :reconnect :max-attempts] 5)]
    (if (>= current-attempt max-attempts)
      ;; Give up
      (update-state! state {:status :failed})

      ;; Calculate delay and retry
      (let [base-delay (* initial-delay (Math/pow multiplier current-attempt))
            capped-delay (min base-delay max-delay)
            jitter (* capped-delay 0.25 (- (rand) 0.5))
            actual-delay (long (+ capped-delay jitter))]

        (update-state! state {:status :reconnecting
                             :reconnect-attempt (inc current-attempt)})
        (future
          (Thread/sleep actual-delay)
          (connect-internal! state))))))
```

**Telemetry Events**:
- `::reconnect-scheduled` - Backoff delay calculated
- `::reconnect-attempt` - Reconnection attempt started
- `::connection-lost` - Unexpected close detected
- Max attempts exceeded logs error

#### Phase 6d: Subscription Restoration ✅

21. **bb_client_tests/08_subscription_restoration.bb** - Automatic Subscription Restoration After Reconnect
    - **Subscription management** in managed client:
      - Subscriptions tracked in `:subscriptions` set
      - `subscribe!` method adds to set and sends to server
      - `unsubscribe!` method removes from set and sends to server
      - `get-subscriptions` method returns current set
    - **Auto-restoration logic**:
      - Called from `:on-open` handler after reconnection
      - Iterates through `:subscriptions` set
      - Sends subscribe message for each channel
      - 100ms delay between subscriptions for processing
    - **Test validates**:
      - Client subscribes to 2 channels initially
      - Connection lost (server stops)
      - Server restarts
      - Client automatically reconnects (Phase 6c)
      - Subscriptions automatically restored
      - Server confirms both subscriptions
    - **PASSING** - Core subscription restoration working

**API Extension**:
```clojure
(def client
  (wsm/create-managed-client
   {:uri "ws://localhost:3000/"
    :on-message (fn [msg] ...)
    :reconnect {:enabled true}}))

;; Subscribe to channel
((:subscribe! client) "channel-id")

;; Unsubscribe from channel
((:unsubscribe! client) "channel-id")

;; Get current subscriptions
((:get-subscriptions client))  ; => #{"channel-id" ...}
```

**Subscription Lifecycle**:
```
1. User calls (:subscribe! client) "channel-1"
   → Adds to :subscriptions set
   → Sends {:type "subscribe" :channel-id "channel-1"} to server

2. Connection lost (server restart)
   → Client detects loss (Phase 6c)
   → Triggers auto-reconnection

3. Reconnection succeeds
   → :on-open handler fires
   → restore-subscriptions! called automatically
   → Iterates :subscriptions set
   → Sends subscribe message for each channel
   → Server confirms each subscription

4. Client receives messages on restored channels
```

**Key Implementation** (`ws_client_managed.clj:70-83`):
```clojure
(defn- restore-subscriptions! [state ws]
  (let [subscriptions (:subscriptions @state)]
    (when (seq subscriptions)
      (tel/event! ::restoring-subscriptions
                  {:count (count subscriptions)
                   :channels subscriptions})
      (doseq [channel-id subscriptions]
        (tel/event! ::restoring-subscription {:channel channel-id})
        (ws/send! ws (json/generate-string
                      {:type "subscribe"
                       :channel-id channel-id}))
        ;; Small delay to ensure processing
        (Thread/sleep 100)))))
```

**State Management**:
- Subscriptions stored in atom: `{:subscriptions #{"chan1" "chan2"} ...}`
- Subscribe adds with `(swap! state update :subscriptions conj channel-id)`
- Unsubscribe removes with `(swap! state update :subscriptions disj channel-id)`
- Persists across reconnections automatically

**Telemetry Events**:
- `::restoring-subscriptions` - Starting restoration with count
- `::restoring-subscription` - Restoring individual channel
- `::client-subscribe-requested` - User called subscribe!
- `::client-unsubscribe-requested` - User called unsubscribe!

#### Phase 6e: Integration Test ✅

22. **bb_client_tests/09_integration.bb** - Complete Connection Management Integration
    - **Integration scope**: Tests all Phase 6 features working together
      - Server heartbeat (Phase 6a)
      - Client state tracking with auto-pong (Phase 6b)
      - Auto-reconnection with exponential backoff (Phase 6c)
      - Subscription restoration after reconnect (Phase 6d)

    - **Test scenarios** (12 tests total):
      1. Initial connection establishment
      2. Channel subscription state tracking
      3. Heartbeat and auto-pong configuration
      4. Subscription state maintenance
      5. Connection loss detection (transitions to `:reconnecting`)
      6. Auto-reconnection after server restart
      7. Subscription restoration after reconnect
      8. Connection and subscription stability
      9. State consistency after reconnection
      10. Multiple disconnect/reconnect cycles
      11. Multiple independent clients with separate state
      12. Independent subscription management across clients

    - **Key validations**:
      - Connection lifecycle: `:closed` → `:connecting` → `:open` → `:reconnecting` → `:open`
      - Subscription state preserved across reconnections
      - Multiple clients maintain independent state
      - Reconnection with exponential backoff works correctly
      - State remains consistent through multiple cycles

    - **Test approach**:
      - Focus on state management integration (not message flow)
      - Individual phase tests validate message delivery
      - Integration test validates features work together as a system
      - Tests state consistency, not timing-dependent message reception

    - **Example test flow**:
      ```clojure
      ;; Create managed client with all features
      (def client
        (wsm/create-managed-client
         {:uri "ws://localhost:3000/"
          :on-state-change (fn [old new] ...)
          :on-message (fn [msg] ...)
          :heartbeat {:auto-pong true}  ; Phase 6b
          :reconnect {:enabled true     ; Phase 6c
                      :max-attempts 5
                      :initial-delay-ms 1000}}))

      ;; Connect and subscribe
      ((:connect! client))
      ((:subscribe! client) "channel-1")

      ;; Simulate connection loss
      (stop-server!)
      ;; Client automatically enters :reconnecting state

      ;; Restart server
      (start-server!)
      ;; Client automatically reconnects and restores subscriptions

      ;; Validate state
      (= :open ((:get-state client)))
      (= #{"channel-1"} ((:get-subscriptions client)))
      ```

    - **State management validation**:
      - Connection state tracked correctly through lifecycle
      - Subscriptions preserved in client state atom
      - Multiple clients isolated (no state leakage)
      - State consistent after multiple reconnection cycles

    - **PASSING**: 12/12 tests (100% success rate)
      - All connection management features integrate correctly
      - State management works reliably across all scenarios
      - Multiple clients can coexist with independent state
      - Reconnection + subscription restoration work together

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
- ✅ Pub/sub messaging (tested with real clients - Phase 4)
- ✅ Channel isolation (verified - Phase 4)
- ✅ Message broadcasting to subscribers (fixed and tested)
- ✅ RPC request/response patterns
- ✅ Connection tracking
- ✅ Subscription management
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
- ✅ Multi-client pub/sub testing (3 clients - Phase 4)
- ✅ Channel isolation testing (Phase 4)
- ❌ No multi-client stress testing (high volume/many clients)
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
