# Phase 6: Connection Management Plan

## Status: Planning

## Goal

Implement production-ready connection management features to make WebSocket connections robust and reliable.

## Current State Analysis

### Server (src/sente_lite/server.cljc)
✅ Has config: `:heartbeat-interval-ms 30000` (line 24)
✅ Has `:ping` → `:pong` message handler (lines 131-135)
❌ No active heartbeat sending mechanism
❌ No dead connection detection
❌ No ping timeout tracking

### Client (test/scripts/bb_client_tests/ws_client.clj)
✅ Basic WebSocket wrapper with callbacks
✅ Callbacks: on-open, on-message, on-close, on-error
❌ No connection state tracking
❌ No reconnection logic
❌ No automatic ping/pong handling

## Features to Implement

### 1. Server-Side Heartbeat

**Purpose**: Detect and close dead/stale connections

**Implementation**:
- Background task sends periodic `:ping` messages (every 30s by default)
- Track `last-pong-received` timestamp per connection
- Close connections that don't respond within `pong-timeout-ms` (default: 60s)
- Configurable: `:heartbeat {:enabled true :interval-ms 30000 :timeout-ms 60000}`

**Server Changes** (src/sente_lite/server.cljc):
```clojure
;; Add to connection state
(defn- add-connection! [channel conn-id]
  (let [conn-data {:id conn-id
                   :channel channel
                   :opened-at (System/currentTimeMillis)
                   :last-activity (System/currentTimeMillis)
                   :last-pong (System/currentTimeMillis)  ; NEW
                   :message-count 0}]
    ...))

;; Update pong handler to track timestamp
:pong
(do
  (update-connection-pong! channel)  ; NEW
  {:type "pong-ack"
   :timestamp (System/currentTimeMillis)})

;; New heartbeat background task
(defn- start-heartbeat-task! [config]
  (let [interval-ms (get-in config [:heartbeat :interval-ms] 30000)
        timeout-ms (get-in config [:heartbeat :timeout-ms] 60000)
        enabled? (get-in config [:heartbeat :enabled] true)]
    (when enabled?
      (future
        (while @server-state
          (Thread/sleep interval-ms)
          (send-heartbeat-pings! timeout-ms))))))

(defn- send-heartbeat-pings! [timeout-ms]
  (let [now (System/currentTimeMillis)
        wire-format (get-wire-format (:config @server-state))]
    (doseq [[channel conn-data] @connections]
      (let [time-since-pong (- now (:last-pong conn-data))]
        (if (> time-since-pong timeout-ms)
          ;; No pong received - close connection
          (do
            (tel/event! ::heartbeat-timeout {:conn-id (:id conn-data)
                                             :timeout-ms timeout-ms})
            (remove-connection! channel)
            #?(:bb (http/close channel)))
          ;; Send ping
          (send-message! channel {:type :ping
                                  :timestamp now} wire-format))))))
```

### 2. Client-Side Connection State

**Purpose**: Track connection state and enable state-based behavior

**States**:
- `:connecting` - Initial connection attempt in progress
- `:open` - Connection established and healthy
- `:closing` - Graceful close initiated
- `:closed` - Connection closed (may reconnect)
- `:reconnecting` - Attempting to reconnect
- `:failed` - Exceeded max reconnect attempts

**Client Changes** (ws_client.clj → ws_client_managed.clj):
```clojure
(defn create-managed-client
  "Create a managed WebSocket client with state tracking and reconnection"
  [{:keys [uri
           on-state-change
           reconnect {:enabled true
                      :max-attempts 5
                      :initial-delay-ms 1000
                      :max-delay-ms 30000
                      :backoff-multiplier 2}
           heartbeat {:auto-pong true}
           on-message
           on-open
           on-close
           on-error]}]
  (let [state (atom {:status :closed
                     :ws nil
                     :reconnect-attempt 0
                     :subscriptions #{}  ; Track subscriptions for re-subscribe
                     :uri uri
                     :config config})]

    ;; Return client handle with methods
    {:state state
     :connect! (fn [] (connect-with-state! state config))
     :disconnect! (fn [] (disconnect! state))
     :send! (fn [msg] (send-with-retry! state msg))
     :get-state (fn [] (:status @state))
     :subscribe! (fn [channel-id] (subscribe-with-tracking! state channel-id))
     :unsubscribe! (fn [channel-id] (unsubscribe-with-tracking! state channel-id))}))
```

### 3. Client-Side Automatic Reconnection

**Purpose**: Automatically reconnect after connection loss

**Strategy**: Exponential backoff with jitter
- Initial delay: 1s
- Multiply delay by 2 after each failed attempt
- Max delay: 30s
- Max attempts: 5 (then fail permanently)
- Jitter: ±25% randomness to prevent thundering herd

**Implementation**:
```clojure
(defn- reconnect-with-backoff! [state attempt]
  (let [config (:config @state)
        max-attempts (get-in config [:reconnect :max-attempts] 5)
        initial-delay (get-in config [:reconnect :initial-delay-ms] 1000)
        max-delay (get-in config [:reconnect :max-delay-ms] 30000)
        multiplier (get-in config [:reconnect :backoff-multiplier] 2)]

    (if (>= attempt max-attempts)
      ;; Exceeded max attempts
      (do
        (swap! state assoc :status :failed)
        (notify-state-change! state :failed)
        (tel/error! "Max reconnect attempts exceeded" {:attempts attempt}))

      ;; Calculate delay with exponential backoff and jitter
      (let [base-delay (* initial-delay (Math/pow multiplier attempt))
            capped-delay (min base-delay max-delay)
            jitter (* capped-delay 0.25 (- (rand) 0.5))  ; ±25%
            actual-delay (+ capped-delay jitter)]

        (tel/event! ::reconnect-scheduled {:attempt (inc attempt)
                                          :delay-ms actual-delay})
        (swap! state assoc :status :reconnecting :reconnect-attempt (inc attempt))
        (notify-state-change! state :reconnecting)

        (future
          (Thread/sleep actual-delay)
          (connect-with-state! state config))))))

(defn- on-connection-lost [state status reason]
  "Handle connection loss - trigger reconnection"
  (let [enabled? (get-in (:config @state) [:reconnect :enabled] true)]
    (tel/event! ::connection-lost {:status status :reason reason})
    (swap! state assoc :status :closed :ws nil)
    (notify-state-change! state :closed)

    (when enabled?
      (reconnect-with-backoff! state (:reconnect-attempt @state)))))
```

### 4. Subscription Restoration

**Purpose**: Automatically re-establish channel subscriptions after reconnect

**Implementation**:
```clojure
(defn subscribe-with-tracking! [state channel-id]
  "Subscribe to channel and track for restoration after reconnect"
  (let [ws (:ws @state)]
    (when (= :open (:status @state))
      (swap! state update :subscriptions conj channel-id)
      (send! ws (json/generate-string {:type "subscribe"
                                       :channel-id channel-id})))))

(defn- restore-subscriptions! [state]
  "Re-subscribe to all previously subscribed channels"
  (let [subscriptions (:subscriptions @state)
        ws (:ws @state)]
    (tel/event! ::restoring-subscriptions {:count (count subscriptions)})
    (doseq [channel-id subscriptions]
      (send! ws (json/generate-string {:type "subscribe"
                                       :channel-id channel-id})))))

(defn- on-connection-established [state ws]
  "Handle successful connection - restore subscriptions"
  (swap! state assoc :status :open :ws ws :reconnect-attempt 0)
  (notify-state-change! state :open)
  (restore-subscriptions! state))
```

### 5. Auto-Pong Response

**Purpose**: Automatically respond to server pings

**Implementation**:
```clojure
(defn- handle-message [state data]
  "Process incoming message, handle pings automatically"
  (let [msg (json/parse-string (str data) true)
        auto-pong? (get-in (:config @state) [:heartbeat :auto-pong] true)]

    (cond
      ;; Auto-respond to pings
      (and (= "ping" (:type msg)) auto-pong?)
      (do
        (tel/event! ::auto-pong-response {:timestamp (:timestamp msg)})
        (send! (:ws @state)
               (json/generate-string {:type "pong"
                                     :timestamp (System/currentTimeMillis)
                                     :original-timestamp (:timestamp msg)})))

      ;; Pass to user handler
      :else
      (when-let [on-message (get-in (:config @state) [:on-message])]
        (on-message msg)))))
```

## Testing Strategy

### Test: Heartbeat Detection (test_heartbeat.bb)
1. Start server with short heartbeat interval (2s) and timeout (5s)
2. Connect client
3. Client responds to pings for 10s → connection stays alive
4. Client stops responding to pings → server closes connection after timeout

### Test: Reconnection with Backoff (test_reconnection.bb)
1. Start server
2. Connect managed client
3. Subscribe to channel "test"
4. Stop server (simulate connection loss)
5. Client enters :reconnecting state
6. Client attempts reconnection with exponential backoff
7. Restart server after 5s
8. Client reconnects successfully
9. Subscriptions automatically restored
10. Verify "test" channel subscription still active

### Test: Connection State Transitions (test_connection_states.bb)
1. Track state changes via callback
2. Verify state progression: :connecting → :open
3. Close connection: :open → :closing → :closed
4. Trigger reconnect: :closed → :reconnecting → :open
5. Exceed max attempts: :reconnecting → :failed

### Test: Subscription Restoration (test_subscription_restore.bb)
1. Connect client, subscribe to 3 channels
2. Disconnect and reconnect
3. Verify all 3 subscriptions restored automatically
4. Publish to channels, verify client receives messages

## Implementation Order

1. ✅ **Phase 6a: Server Heartbeat** (server.cljc)
   - Add heartbeat background task
   - Track last-pong per connection
   - Close dead connections
   - Test with basic client

2. ✅ **Phase 6b: Client State Tracking** (ws_client_managed.clj)
   - Create managed client wrapper
   - Implement state machine
   - State change callbacks
   - Test state transitions

3. ✅ **Phase 6c: Auto-Reconnection** (ws_client_managed.clj)
   - Implement exponential backoff
   - Connection loss detection
   - Reconnection attempts
   - Test reconnection scenarios

4. ✅ **Phase 6d: Subscription Restoration** (ws_client_managed.clj)
   - Track subscriptions
   - Restore after reconnect
   - Test restoration

5. ✅ **Phase 6e: Integration Test** (test_connection_management.bb)
   - End-to-end test with all features
   - Heartbeat + Reconnection + Restoration
   - Stress test (multiple clients, multiple disconnects)

## Configuration

### Server Config
```clojure
{:heartbeat {:enabled true
             :interval-ms 30000    ; Send ping every 30s
             :timeout-ms 60000}    ; Close if no pong for 60s
 :connections {:max 1000
               :idle-timeout-ms 300000}}  ; 5 minutes
```

### Client Config
```clojure
{:reconnect {:enabled true
             :max-attempts 5
             :initial-delay-ms 1000
             :max-delay-ms 30000
             :backoff-multiplier 2}
 :heartbeat {:auto-pong true}
 :on-state-change (fn [old-state new-state] ...)
 :on-message (fn [msg] ...)
 :on-reconnect (fn [] ...)}
```

## Success Criteria

- ✅ Server detects and closes dead connections within 60s
- ✅ Client automatically reconnects after connection loss
- ✅ Client uses exponential backoff (1s → 2s → 4s → 8s → 16s → 30s)
- ✅ Subscriptions automatically restored after reconnect
- ✅ Client responds to pings automatically
- ✅ Connection state accurately tracked and reported
- ✅ All existing tests still pass
- ✅ New integration test passes

## Non-Goals (Future Phases)

- Message delivery guarantees (requires message queue)
- Message ordering guarantees (requires sequence numbers)
- Connection pooling (single connection per client)
- Load balancing (single server)
- Session persistence (stateless connections)
