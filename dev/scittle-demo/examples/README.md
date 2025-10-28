# WebSocket Hello World Examples

## Success Report (2025-10-28)

✅ **Bidirectional WebSocket communication verified working** between Babashka server and Scittle browser client.

### What Works

1. **Server → Client**: Server sends "Welcome from server!", client receives it
2. **Client → Server**: Client sends "Hello from browser!", server receives it
3. **WebSocket handshake**: Connection establishment successful
4. **Port management**: Clean server startup on port 1342

### Test Files

#### Basic Versions (Proven Working)
- `hello-server.clj` - Minimal WebSocket server on port 1342
- `hello-client.cljs` - Minimal WebSocket client connecting from browser

#### With Telemetry (Template)
- `hello-server-telemetry.clj` - Server with telemetry logging stubs
- `hello-client-telemetry.cljs` - Client with telemetry logging stubs

### How to Test

1. **Start BB dev environment**:
   ```bash
   cd dev/scittle-demo
   bb dev  # Starts ports 1338, 1339, 1340, 1341
   ```

2. **Start browser**:
   ```bash
   npm run interactive  # Opens Playwright with DevTools
   ```

3. **Load server**:
   ```bash
   bb load-bb examples/hello-server.clj
   ```

4. **Load client**:
   ```bash
   bb load-browser examples/hello-client.cljs
   ```

5. **Verify** in browser console:
   - "Client: Connected to server!"
   - "Client received: Welcome from server!"

### Architecture

```
┌─────────────────┐         WebSocket         ┌──────────────────┐
│  BB Server      │◄──────────────────────────►│  Browser Client  │
│  Port 1342      │   ws://localhost:1342      │  (Scittle)       │
│  http-kit       │                            │  Native WS API   │
└─────────────────┘                            └──────────────────┘
```

### Next Steps

- Integrate real telemere-lite logging
- Load full sente-lite implementation
- Test with Transit wire format
- Add multiplexing capabilities

### Notes

- The telemetry versions use stub logging functions (`log!`) that will be replaced with real telemere-lite integration
- Basic versions use `println` for immediate visibility
- All files tested with Babashka v1.4+ and Scittle nREPL
