# WebSocket Message Flow Issue

## Status: 🔴 BLOCKING

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
