# sente-lite Demo Testing Protocol

**Version:** 1.0
**Date:** 2025-10-28
**Location:** `/Users/franksiebenlist/Development/sente_lite/dev/scittle-demo/`

---

## Purpose

This document provides complete instructions for testing the three sente-lite demonstration servers with Babashka clients. All test scenarios have been verified working as of v0.16.0-demo-tests.

**Target audience:** AI assistants or developers who need to verify sente-lite functionality.

---

## Overview

Three demonstration servers showcase progressive sente-lite features:

1. **Echo Demo** (port 1343): Basic WebSocket communication with default echo behavior
2. **Heartbeat Demo** (port 1344): Server-initiated ping/pong for connection health monitoring
3. **Pub/Sub Demo** (port 1345): Channel-based messaging with subscribe/publish/unsubscribe

Each demo has:
- A server file (`sente-*-demo-server.clj`)
- A client file (`sente-*-demo-client.cljs`) for browser/Scittle
- A test client file (`test-*-client.bb`) for Babashka verification

---

## Critical Configuration Requirements

### 1. Server Classpath

**CRITICAL:** Demo servers MUST be run with explicit classpath to access sente-lite source code.

```bash
# ✅ CORRECT - Includes src/ directory
bb -cp src dev/scittle-demo/examples/sente-echo-demo-server.clj

# ❌ WRONG - Missing classpath, will fail with "Could not locate sente_lite/server"
bb dev/scittle-demo/examples/sente-echo-demo-server.clj
```

**Why:** The demo server files are in `dev/scittle-demo/examples/` but need access to `src/sente_lite/server.cljc`.

### 2. Heartbeat Configuration Key

**CRITICAL:** Server heartbeat config uses `:interval-ms` NOT `:ping-interval-ms`.

```clojure
;; ✅ CORRECT
{:heartbeat {:enabled true
             :interval-ms 5000}}  ; Ping every 5 seconds

;; ❌ WRONG - Will be ignored, defaults to 30000ms (30 seconds)
{:heartbeat {:enabled true
             :ping-interval-ms 5000}}
```

**Location in code:** `src/sente_lite/server.cljc:275`
```clojure
(let [interval-ms (get-in config [:heartbeat :interval-ms] 30000)
```

**Impact:** Using wrong key means server uses default 30-second interval instead of configured value.

### 3. WebSocket Message Format (Babashka Client)

**CRITICAL:** Messages from `babashka.http-client.websocket` arrive as `java.nio.HeapCharBuffer`, not String.

```clojure
;; ✅ CORRECT - Convert to String first
:on-message (fn [ws msg _last?]
              (let [msg-str (str msg)           ; Convert HeapCharBuffer
                    parsed (edn/read-string msg-str)]
                (handle-message parsed)))

;; ❌ WRONG - Tries to parse HeapCharBuffer directly
:on-message (fn [ws msg _last?]
              (let [parsed (edn/read-string msg)]  ; FAILS!
                (handle-message parsed)))
```

**Error if wrong:** `java.nio.HeapCharBuffer cannot be cast to java.lang.String`

### 4. Channel Message Detection

**CRITICAL:** Channel broadcast messages are identified by `:broadcast-time` field, not `:type`.

Server adds these fields to published messages:
- `:channel-id` - The channel name
- `:broadcast-time` - Timestamp when broadcast

```clojure
;; ✅ CORRECT - Detect by :broadcast-time presence
(let [has-broadcast-time? (contains? parsed :broadcast-time)]
  (when has-broadcast-time?
    ;; This is a channel message
    (handle-channel-message parsed)))

;; ❌ WRONG - Looking for :type :channel-message (doesn't exist)
(when (= (:type parsed) :channel-message)
  (handle-channel-message parsed))
```

**Why:** Server broadcasts user data as-is plus metadata. If user data has `:type` field, that's preserved. The `:broadcast-time` is the reliable indicator.

---

## Test Environment Setup

### Prerequisites

1. **Babashka installed** - All tests use `bb` command
2. **Project at root** - All commands assume current directory is project root
3. **All source files present** - Especially `src/sente_lite/server.cljc`
4. **Ports available** - 1343, 1344, 1345 must be free

### Clean Environment

Before testing, ensure no conflicting processes:

```bash
# Kill any existing bb processes
pkill -9 bb

# Verify ports are free (should show no output)
lsof -i TCP:1343 -i TCP:1344 -i TCP:1345 2>/dev/null || echo "✓ All ports free"
```

---

## Test Scenario 1: Echo Demo

### Purpose
Verify basic WebSocket communication and server's default echo behavior.

### Server Start

```bash
# From project root
bb -cp src dev/scittle-demo/examples/sente-echo-demo-server.clj
```

**Expected output:**
```
Starting sente-lite echo demo server...
[telemetry logs...]
✓ Server listening on ws://localhost:1343

Echo behavior:
  - Client sends: {:type "test" :data "hello"}
  - Server echoes: {:type "echo" :original {...} :conn-id ... :timestamp ...}

Press Ctrl+C to stop
```

**Verification:**
```bash
# In separate terminal - check port listening
lsof -i TCP:1343 2>/dev/null | grep LISTEN
```

Should show bb process listening on 1343.

### Test Client Run

```bash
# In separate terminal, from project root
bb dev/scittle-demo/examples/test-echo-client.bb
```

**Expected output:**
```
Connecting to echo server on ws://localhost:1343...
✓ Connected to server
Waiting for connection...
✓ Connection established
📨 Received: {:type :welcome, :conn-id conn-..., :server-time ...}
📤 Sending test message...
📨 Received: {:type echo, :original {:type test, :data hello from bb client, :timestamp ...}, :conn-id conn-..., :timestamp ...}
📤 Sending second message...
📨 Received: {:type echo, :original {:type custom, :foo bar, :timestamp ...}, :conn-id conn-..., :timestamp ...}
📨 Received: {:type :ping, :timestamp ...}

Total messages received: 4
  1. {:type :welcome, :conn-id conn-..., :server-time ...}
  2. {:type echo, :original {:type test, :data hello from bb client, :timestamp ...}, :conn-id conn-..., :timestamp ...}
  3. {:type echo, :original {:type custom, :foo bar, :timestamp ...}, :conn-id conn-..., :timestamp ...}
  4. {:type :ping, :timestamp ...}
✓ Test complete
```

### Success Criteria

- ✅ Client connects (receives welcome message)
- ✅ 2 test messages echoed back correctly
- ✅ Heartbeat ping received
- ✅ Total: 4 messages
- ✅ No errors or exceptions

### Common Issues

**Issue:** "Could not locate sente_lite/server"
- **Cause:** Missing `-cp src` flag
- **Fix:** Use `bb -cp src dev/scittle-demo/examples/sente-echo-demo-server.clj`

**Issue:** Client receives 0 messages
- **Cause:** Message parsing error (HeapCharBuffer not converted)
- **Fix:** Ensure test client uses `(str msg)` before parsing

---

## Test Scenario 2: Heartbeat Demo

### Purpose
Verify server-initiated heartbeat ping/pong mechanism for connection health monitoring.

### Server Start

```bash
# From project root
bb -cp src dev/scittle-demo/examples/sente-heartbeat-demo-server.clj
```

**Expected output:**
```
Starting sente-lite heartbeat demo server...
[telemetry logs...]
✓ Server listening on ws://localhost:1344

Heartbeat enabled:
  - Server sends ping every 5 seconds
  - Client responds with pong (auto-pong enabled)
  - Connection health monitored via ping/pong

Press Ctrl+C to stop
```

**Key configuration in server (line 26-27):**
```clojure
:heartbeat {:enabled true
            :interval-ms 5000}  ; ← Must use :interval-ms, NOT :ping-interval-ms
```

### Test Client Run

```bash
# In separate terminal, from project root
bb dev/scittle-demo/examples/test-heartbeat-client.bb
```

**Expected output:**
```
Connecting to heartbeat server on ws://localhost:1344...
✓ Connected to heartbeat server
Waiting for connection...
✓ Connection established
Waiting for heartbeat pings... (server sends every 5 seconds)
Will run for 15 seconds
✓ Welcome message: {:type :welcome, :conn-id conn-..., :server-time ...}
💓 Received ping # 1
💚 Sent pong # 1
✓ Server acknowledged pong
💓 Received ping # 2
💚 Sent pong # 2
✓ Server acknowledged pong
💓 Received ping # 3
💚 Sent pong # 3
✓ Server acknowledged pong

=== Final Stats ===
Pings received: 3
Pongs sent: 3
✓ Test complete
```

### Success Criteria

- ✅ Client connects (receives welcome message)
- ✅ Server sends ping every 5 seconds
- ✅ Client auto-responds with pong
- ✅ Server acknowledges pongs with pong-ack
- ✅ 3 complete ping/pong cycles in 15 seconds
- ✅ No errors or timeouts

### Common Issues

**Issue:** No pings received in 15 seconds
- **Cause:** Wrong config key (`:ping-interval-ms` instead of `:interval-ms`)
- **Fix:** Update server config to use `:interval-ms`
- **Default fallback:** 30000ms (30 seconds) if wrong key used

**Issue:** Only 1 ping received in 15 seconds
- **Cause:** Server using default 30-second interval (wrong config key)
- **Expected with correct config:** 3 pings in 15 seconds (at 0s, 5s, 10s)

---

## Test Scenario 3: Pub/Sub Demo

### Purpose
Verify channel-based messaging including subscribe, publish, unsubscribe, and message isolation.

### Server Start

```bash
# From project root
bb -cp src dev/scittle-demo/examples/sente-pubsub-demo-server.clj
```

**Expected output:**
```
Starting sente-lite pub/sub demo server...
[telemetry logs...]
✓ Server listening on ws://localhost:1345

Pub/Sub features:
  - Auto-create channels on first subscribe
  - Multiple subscribers per channel
  - Broadcast messages to all subscribers
  - Retain last 10 messages per channel

Available operations:
  - Subscribe:   {:type "subscribe" :channel-id "channel-name"}
  - Publish:     {:type "publish" :channel-id "channel-name" :data {...}}
  - Unsubscribe: {:type "unsubscribe" :channel-id "channel-name"}

Press Ctrl+C to stop
```

### Test Client Run

```bash
# In separate terminal, from project root
bb dev/scittle-demo/examples/test-pubsub-client.bb
```

**Expected output:**
```
Connecting to pub/sub server on ws://localhost:1345...
✓ Connected to pub/sub server
Waiting for connection...
✓ Welcome: conn-...
✓ Connection established

=== TEST 1: Subscribe to 'chat' channel ===
✓ Subscription result:
   Channel: chat
   Success: true
   Subscribers: 1

=== TEST 2: Publish message to 'chat' ===
📨 Channel message:
   Channel: chat
   Data: {:user Alice, :message Hello everyone!}
✓ Publish result: Delivered to 1

=== TEST 3: Subscribe to 'notifications' channel ===
✓ Subscription result:
   Channel: notifications
   Success: true
   Subscribers: 1

=== TEST 4: Publish to 'notifications' ===
📨 Channel message:
   Channel: notifications
   Data: {:type alert, :text New update available}
✓ Publish result: Delivered to 1

=== TEST 5: Unsubscribe from 'chat' ===
✓ Unsubscription result:
   Channel: chat
   Success: true

=== TEST 6: Publish to 'chat' after unsubscribe (should not receive) ===
✓ Publish result: Delivered to 0

=== FINAL STATS ===
Active subscriptions: #{notifications}
Channel messages received: 2

Message details:
  1. Channel:chat Data:{:user Alice, :message Hello everyone!}
  2. Channel:notifications Data:{:type alert, :text New update available}

✓ Test complete
```

### Success Criteria

- ✅ Subscribe to 'chat' channel succeeds (1 subscriber)
- ✅ Publish to 'chat' → message received (delivered to 1)
- ✅ Subscribe to 'notifications' succeeds (1 subscriber)
- ✅ Publish to 'notifications' → message received (delivered to 1)
- ✅ Unsubscribe from 'chat' succeeds
- ✅ Publish to 'chat' after unsubscribe → NOT received (delivered to 0)
- ✅ Total: 2 channel messages received (correct isolation)
- ✅ Final subscriptions: only 'notifications' active

### Channel Message Detection Logic

**How to identify channel broadcast messages:**

```clojure
(let [msg-str (str msg)
      parsed (edn/read-string msg-str)
      has-broadcast-time? (contains? parsed :broadcast-time)]
  (if has-broadcast-time?
    ;; This is a channel message
    ;; Server added :channel-id and :broadcast-time
    ;; Original data is merged in
    (println "Channel:" (:channel-id parsed)
             "Data:" (dissoc parsed :channel-id :broadcast-time))
    ;; Regular server response (welcome, subscription-result, etc.)
    (handle-by-type (:type parsed))))
```

### Common Issues

**Issue:** Channel messages not detected
- **Cause:** Looking for `:type :channel-message` (doesn't exist)
- **Fix:** Detect by presence of `:broadcast-time` field

**Issue:** User data's `:type` field interfering with detection
- **Example:** User publishes `{:type "alert" :text "..."}`, gets detected as server message
- **Solution:** Check `:broadcast-time` FIRST, before checking `:type`

**Issue:** Messages from unsubscribed channel still received
- **Cause:** Server routing bug or test timing issue
- **Expected:** After unsubscribe, publish should deliver to 0 connections

---

## Wire Format: EDN

All three demos use **EDN** (Extensible Data Notation) as the wire format.

### Configuration

```clojure
{:wire-format :edn}  ; Default for Clojure-to-Clojure communication
```

**Location in server code:** `src/sente_lite/server.cljc:20`

### Message Serialization

**Sending (client → server):**
```clojure
(ws/send! client (pr-str {:type "test" :data "hello"}))
```

**Receiving (server → client):**
```clojure
(let [msg-str (str msg)  ; Convert HeapCharBuffer to String
      parsed (edn/read-string msg-str)]
  (handle-message parsed))
```

### Alternative Formats

Server also supports:
- `:wire-format :json` - JSON serialization
- `:wire-format :transit` - Transit format (for rich types)

**Note:** Test clients are hardcoded for EDN. To test other formats, create new test client with appropriate serialization.

---

## Server Message Types

### Server-Initiated Messages

1. **`:welcome`** - Sent immediately on connection
   ```clojure
   {:type :welcome
    :conn-id "conn-1234567890-1234"
    :server-time 1234567890000}
   ```

2. **`:ping`** - Heartbeat ping (if enabled)
   ```clojure
   {:type :ping
    :timestamp 1234567890000}
   ```

3. **`:pong-ack`** - Acknowledges client pong
   ```clojure
   {:type :pong-ack
    :timestamp 1234567890000}
   ```

4. **Channel broadcasts** - Published messages (has `:broadcast-time`, no `:type`)
   ```clojure
   {:user "Alice"                    ; User data
    :message "Hello!"                ; User data
    :channel-id "chat"               ; Server added
    :broadcast-time 1234567890000}   ; Server added
   ```

### Client-Initiated Message Responses

1. **`:echo`** - Default response for unrecognized message types
   ```clojure
   {:type "echo"
    :original {:type "test" :data "hello"}
    :conn-id "conn-1234567890-1234"
    :timestamp 1234567890000}
   ```

2. **`:subscription-result`** - Response to subscribe request
   ```clojure
   {:type :subscription-result
    :channel-id "chat"
    :success true
    :reason nil
    :subscriber-count 1
    :retained-messages []}
   ```

3. **`:unsubscription-result`** - Response to unsubscribe request
   ```clojure
   {:type :unsubscription-result
    :channel-id "chat"
    :success true}
   ```

4. **`:publish-result`** - Response to publish request
   ```clojure
   {:type :publish-result
    :channel-id "chat"
    :success true
    :message-id "msg-1234"
    :delivered-to 2}
   ```

---

## Complete Test Sequence

### Run All Three Tests

```bash
# Terminal 1: Echo demo
bb -cp src dev/scittle-demo/examples/sente-echo-demo-server.clj

# Terminal 2: Test echo
bb dev/scittle-demo/examples/test-echo-client.bb
# [Verify output, then Ctrl+C in Terminal 1]

# Terminal 1: Heartbeat demo
bb -cp src dev/scittle-demo/examples/sente-heartbeat-demo-server.clj

# Terminal 2: Test heartbeat
bb dev/scittle-demo/examples/test-heartbeat-client.bb
# [Verify output, then Ctrl+C in Terminal 1]

# Terminal 1: Pub/sub demo
bb -cp src dev/scittle-demo/examples/sente-pubsub-demo-server.clj

# Terminal 2: Test pub/sub
bb dev/scittle-demo/examples/test-pubsub-client.bb
# [Verify output, then Ctrl+C in Terminal 1]
```

### Automated Test Runner (Optional)

Create `test-all-demos.bb` for automated testing:

```clojure
#!/usr/bin/env bb

(require '[babashka.process :as p]
         '[clojure.string :as str])

(defn test-demo [server-file client-file demo-name]
  (println (str "\n=== Testing " demo-name " ==="))

  ;; Start server in background
  (let [server (p/process ["bb" "-cp" "src" server-file]
                          {:out :inherit :err :inherit})]
    (Thread/sleep 2000)  ; Wait for server startup

    ;; Run client
    (let [result (p/shell {:continue true}
                          (str "bb " client-file))]
      (if (zero? (:exit result))
        (println "✅" demo-name "PASSED")
        (println "❌" demo-name "FAILED"))

      ;; Kill server
      (p/destroy server)
      (Thread/sleep 1000)

      result)))

;; Run all tests
(test-demo "dev/scittle-demo/examples/sente-echo-demo-server.clj"
           "dev/scittle-demo/examples/test-echo-client.bb"
           "Echo Demo")

(test-demo "dev/scittle-demo/examples/sente-heartbeat-demo-server.clj"
           "dev/scittle-demo/examples/test-heartbeat-client.bb"
           "Heartbeat Demo")

(test-demo "dev/scittle-demo/examples/sente-pubsub-demo-server.clj"
           "dev/scittle-demo/examples/test-pubsub-client.bb"
           "Pub/Sub Demo")

(println "\n=== All Tests Complete ===")
```

---

## Troubleshooting

### Server Won't Start

**Symptom:** "Could not locate sente_lite/server"

**Cause:** Missing classpath

**Fix:**
```bash
# ✅ CORRECT
bb -cp src dev/scittle-demo/examples/sente-echo-demo-server.clj

# ❌ WRONG
bb dev/scittle-demo/examples/sente-echo-demo-server.clj
```

### Port Already in Use

**Symptom:** Server fails with "Address already in use"

**Cause:** Previous test server still running

**Fix:**
```bash
# Kill all bb processes
pkill -9 bb

# Verify ports free
lsof -i TCP:1343 -i TCP:1344 -i TCP:1345 2>/dev/null || echo "✓ All ports free"
```

### Client Receives 0 Messages

**Symptom:** Test client shows "Total messages received: 0"

**Cause:** Message parsing error (HeapCharBuffer not converted)

**Fix:** Ensure test client uses:
```clojure
(let [msg-str (str msg)  ; ← This conversion is CRITICAL
      parsed (edn/read-string msg-str)]
  ...)
```

### Heartbeat Not Working

**Symptom:** No pings received in 15 seconds

**Cause:** Wrong config key (`:ping-interval-ms` instead of `:interval-ms`)

**Fix:** Update server config:
```clojure
;; ✅ CORRECT
:heartbeat {:enabled true :interval-ms 5000}

;; ❌ WRONG
:heartbeat {:enabled true :ping-interval-ms 5000}
```

### Channel Messages Not Detected

**Symptom:** Pub/sub test shows 0 channel messages

**Cause:** Wrong detection logic (looking for `:type :channel-message`)

**Fix:** Detect by `:broadcast-time` field:
```clojure
(if (contains? parsed :broadcast-time)
  ;; Channel message
  (handle-channel-message parsed)
  ;; Server response
  (handle-by-type (:type parsed)))
```

---

## Verification Checklist

Before claiming "all tests passed", verify:

- [ ] **Echo Demo:**
  - [ ] Server starts on port 1343
  - [ ] Client receives 4 messages (welcome, 2 echoes, 1 ping)
  - [ ] No errors in output

- [ ] **Heartbeat Demo:**
  - [ ] Server starts on port 1344
  - [ ] Client receives 3 pings in 15 seconds
  - [ ] Client sends 3 pongs
  - [ ] Server acknowledges all pongs
  - [ ] No timeouts

- [ ] **Pub/Sub Demo:**
  - [ ] Server starts on port 1345
  - [ ] Subscribe to 2 channels succeeds
  - [ ] 2 channel messages received
  - [ ] Unsubscribe prevents further messages
  - [ ] Final delivered-to count is 0 after unsubscribe

- [ ] **All Demos:**
  - [ ] Correct classpath used (`bb -cp src`)
  - [ ] EDN wire format works
  - [ ] No parsing errors
  - [ ] All ports cleaned up after tests

**ONLY mark complete when ALL checkboxes = ✅**

---

## Reference: Key Code Locations

### Server Configuration
- **Default wire format:** `src/sente_lite/server.cljc:20`
- **Heartbeat interval key:** `src/sente_lite/server.cljc:275`
- **Message routing:** `src/sente_lite/server.cljc:134-228`
- **Channel broadcast:** `src/sente_lite/server.cljc:530-554`

### Demo Files
- **Servers:** `dev/scittle-demo/examples/sente-*-demo-server.clj`
- **Test clients:** `dev/scittle-demo/examples/test-*-client.bb`
- **Browser clients:** `dev/scittle-demo/examples/sente-*-demo-client.cljs`

### Documentation
- **Deployment protocol:** `dev/scittle-demo/DEPLOYMENT-PROTOCOL.md`
- **This document:** `dev/scittle-demo/TESTING-PROTOCOL.md`

---

## Tags and Versions

**Demo files created:** v0.15.0-sente-demos
**Test clients verified:** v0.16.0-demo-tests
**Documentation date:** 2025-10-28

---

## Remember

**IF A TEST FAILS → REPORT IT HONESTLY**

- Don't skip verification steps
- Don't claim tests pass when they fail
- Don't guess at expected output
- Do report exact error messages
- Do verify ALL checkboxes before claiming success

The goal is **working, verified code**, not optimistic claims.
