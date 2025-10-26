# WebSocket Message Flow Issue

## Status: ✅ RESOLVED

## Issue
WebSocket connections open successfully between Java 11 WebSocket client and http-kit server, but **no messages flow in either direction**.

## Evidence

### Phase 2 Test (Connection Only)
```
Server: Sends welcome message (82 bytes) → Client never receives
Client: Events logged: [":open"] only
Result: Connection established ✅, but no data received
```

### Phase 3 Test (Client Sends Message)
```
Client: Sends message (77 bytes, .join() confirms completion) → Server never receives
Server: No websocket-message-received event logged
Server: message-count: 0
Result: Connection established ✅, but no data sent or received
```

### Telemetry Evidence
From `sente-lite-server.log`:
```json
// Server sends welcome
{"event-id":":sente-lite.server/message-sent","data":{"type":":welcome","size":82}}

// Client logs send + completion
{"event-id":":ws-client/ws-send","data":{"message-length":77}}
{"event-id":":ws-client/ws-send-complete","data":{"message-length":77}}

// Server never receives - NO websocket-message-received event
// Client never receives - NO ws-message-received event
```

## Root Cause Analysis

### Confirmed Working
- ✅ WebSocket upgrade (HTTP → WS)
- ✅ Connection establishment (handshake completes)
- ✅ Connection tracking (server sees active connection)
- ✅ http-kit `http/send!` called successfully
- ✅ Java WebSocket `.sendText()` completes via `.join()`

### Confirmed NOT Working
- ❌ Server → Client message delivery
- ❌ Client → Server message delivery
- ❌ http-kit `:on-message` handler never invoked
- ❌ Java WebSocket onText callback never invoked

### Possible Causes
1. **Babashka http-kit WebSocket compatibility issue**
   - http-kit may have WebSocket implementation differences in babashka
   - Possible SCI interpreter limitations for WebSocket callbacks

2. **Java 11 WebSocket ↔ http-kit interop**
   - Protocol mismatch or framing issue
   - Different WebSocket subprotocol expectations

3. **Async/threading issue**
   - Messages queued but not delivered
   - Callback threading incompatibility between Java WebSocket and http-kit

### What's NOT the Issue
- ❌ NOT telemetry filtering (fixed - server events now log)
- ❌ NOT serialization (welcome message serializes successfully)
- ❌ NOT client send API (`.join()` confirms completion)
- ❌ NOT server handler registration (handlers are registered correctly)

## Investigation Steps Taken

1. **Fixed telemetry** - Can now see server-side events
2. **Fixed client send** - Added `.join()` to wait for send completion
3. **Verified server sends** - message-sent event logged with correct size
4. **Verified client sends** - ws-send-complete event logged
5. **Checked handler registration** - `:on-message` properly registered with http-kit
6. **Checked both directions** - Neither direction works

## Next Steps

1. **Test with curl/websocat** - Verify server receives from different WebSocket client
2. **Test with different client** - Try ws npm package instead of Java 11 API
3. **Check http-kit version** - Verify babashka's http-kit WebSocket support status
4. **Simplify test** - Create minimal WebSocket echo server/client
5. **Check babashka issues** - Search for known http-kit WebSocket limitations
6. **Consider alternative** - May need different WebSocket server for babashka

## Impact

**BLOCKS Phase 3 and beyond** - Cannot test message exchange without working WebSocket data flow

## Workaround

None currently. WebSocket bidirectional communication is fundamental requirement.

## Priority

**CRITICAL** - Blocks all message-based testing and development

## Resolution (2025-10-26)

### Root Causes Identified

1. **Server Bug**: `src/sente_lite/server.cljc` line 272 used `:on-message` instead of `:on-receive`
   - http-kit's WebSocket API uses `:on-receive` for message callbacks
   - This prevented the server from receiving any messages from clients

2. **Client Bug**: Java 11 WebSocket API requires explicit `.request(n)` call
   - Java 11 WebSocket starts with receive counter at 0
   - Must call `.request(Long/MAX_VALUE)` in onOpen to allow message reception
   - Without this, client never invokes onText callback

### Fixes Applied

1. **Server**: Changed `:on-message` to `:on-receive` in server.cljc:272
2. **Client**: Added `.request(Long/MAX_VALUE)` in onOpen callback
3. **Client Refactor**: Switched from Java 11 API to `babashka.http-client.websocket`
   - Native Babashka implementation is simpler and requires no workarounds
   - Aligns with project philosophy: "Native capability first"

### Test Results

✅ Phase 3 message echo test: **PASSING**
✅ Minimal WebSocket echo test: **WORKING**
✅ Bidirectional communication: **CONFIRMED**

### Lessons Learned

1. Always verify API callback names match the library's expectations
2. Java 11 WebSocket requires explicit request management (not needed with native clients)
3. Native platform capabilities should be preferred over Java interop when available
