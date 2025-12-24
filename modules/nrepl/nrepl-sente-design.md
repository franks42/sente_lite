# nREPL-over-Sente Module Design

## Overview

This document captures the design for a Dynamic Code Deployment Channel using nREPL over sente-lite. This is NOT traditional development nREPL - it's production-capable infrastructure for remotely uploading and evaluating ClojureScript code in Scittle browsers.

## Use Cases

### 1. Dynamic Code Deployment (Human Developer)

**Problem**: Browser applications loaded via `<script>` tags are static. Once loaded, you cannot:
- Push new code to running browsers
- Hot-patch bugs without page reload
- Extend functionality dynamically
- Monitor and inspect live state

**Solution**: A persistent sente-lite channel that enables:
- Build up browser applications in real-time
- Extend functionality without reload
- Monitor and update/upgrade running code
- Full REPL semantics (eval, load-file, inspect)

### 2. AI-Assisted Development (nREPL-MCP)

**Problem**: AI assistants (Claude Code, etc.) can write code but cannot directly interact with running browser applications. They must rely on human feedback loops.

**Solution**: AI assistants connect via nREPL-MCP server to:
- **Direct browser access**: Evaluate code in live Scittle runtime
- **Real-time testing**: Run tests against actual browser state
- **Live monitoring**: Inspect application state, DOM, errors
- **Iterative development**: Upload code, observe results, refine
- **Autonomous debugging**: Check console errors, fix, verify fix worked

**Example workflow**:
```
[Claude Code] ─mcp→ [nREPL-MCP Server] ─bencode→ [Gateway:1347] ─sente→ [Browser]
                                                      ↓
                                              [AI sees result, iterates]
```

**This enables**:
- AI builds UI components and immediately sees them render
- AI catches runtime errors and fixes them in same session
- AI can verify DOM state matches expectations
- Tight feedback loop: write → run → observe → refine
- No human in the loop for basic verify/fix cycles

## Existing Implementations

### 1. Default System: Plain WebSocket (sci.nrepl.browser-server)

**Ports**: 1339 (bencode for editors), 1340 (WebSocket for browser)

**Loaded via**:
```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.nrepl.js"></script>
<script>scittle.nrepl.start();</script>
```

**Architecture**:
```
[Editor/Cider] ─bencode→ [BB Server:1339] ─WebSocket→ [Browser:1340]
```

**Characteristics**:
- Built into Scittle distribution
- Simple, minimal setup
- Single-purpose channel (nREPL only)
- No reconnection logic
- No multiplexing with other traffic

### 2. Alternative System: sente-lite (sente-nrepl-gateway)

**Ports**: 1347 (bencode for editors), 1342 (sente WebSocket)

**Files**:
- `dev/scittle-demo/sente-nrepl-gateway.clj` (~270 lines, BB server)
- `dev/scittle-demo/examples/sente-nrepl-client.cljs` (~170 lines, browser)

**Architecture**:
```
[Editor/Cider] ─bencode→ [Gateway:1347] ─sente→ [Browser via :1342]
                              ↓
                    [bencode↔EDN translation]
```

**Characteristics**:
- Uses sente-lite's full feature set
- Automatic reconnection with backoff
- Multiplexed with other app traffic
- Requires explicit setup/loading

## Comparison: Plain WS vs sente-lite

| Aspect | Plain WebSocket | sente-lite |
|--------|-----------------|------------|
| **Setup** | One script tag | Manual integration |
| **Reconnection** | None | Automatic with backoff |
| **Multiplexing** | Dedicated channel | Shared with app messages |
| **Production-ready** | No | Yes |
| **Editor support** | Built-in | Same (bencode proxy) |
| **State sync** | Lost on disconnect | Resumable |
| **Other traffic** | Separate connection | Same connection |
| **Monitoring** | None | Full telemetry integration |

## Why sente-lite is Essential for Production

1. **Reliability**: Auto-reconnect is mandatory for long-running production browsers
2. **Unified Channel**: One connection for code deployment + app traffic + telemetry
3. **State Management**: Track pending evals across reconnects
4. **Visibility**: Full observability via telemere integration
5. **Flexibility**: Same channel can carry other operational traffic
6. **AI Integration**: Stable connection for AI assistants to maintain context across multiple eval cycles

## Security Model

**Same-Origin Trust**: The nREPL connection originates from the same host that served the initial HTML. If you trust the host enough to load JavaScript from it, you implicitly trust code pushed over the same-origin WebSocket.

Key points:
- No additional auth/authz required for same-origin
- The browser's same-origin policy provides the security boundary
- This is NOT a public endpoint - it's an operational channel
- Production deployment should use HTTPS/WSS

**NOT in scope** (would require auth):
- Cross-origin nREPL access
- Public internet exposure
- Multi-tenant isolation

## Component Architecture

### Current Flow Analysis

**Gateway (BB Server) - existing code in `sente-nrepl-gateway.clj`:**
```
[Editor] ──bencode──→ read-bencode() → session-loop() → handle-eval()
                                                              │
                                                    create EDN msg
                                                              │
                                                    send-to-browser!()
                                                              ↓
                                                        [sente channel]
                                                              ↓
                                   send-bencode-response() ← handle-browser-message()
                                              │
[Editor] ←──bencode──────────────────────────┘
```

**Browser - existing code in `sente-nrepl-client.cljs`:**
```
[sente channel] → handle-message() → dispatch on :nrepl/eval
                                            │
                                     eval-code() ← scittle.core.eval_string
                                            │
                                     sente/send!() → [:nrepl/response {...}]
                                            ↓
                                     [sente channel]
```

### Decoupled Components

The key insight: **Focus on EDN messages, not bencode**. Bencode is just one of many possible transports for the EDN protocol.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           COMPONENT ARCHITECTURE                            │
└─────────────────────────────────────────────────────────────────────────────┘

                              BB SERVER SIDE
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│  ┌──────────────────────┐      ┌──────────────────────┐                    │
│  │  LAYER 3a: Bencode   │      │  LAYER 3b: Direct    │                    │
│  │  Proxy (for editors) │      │  Function API        │                    │
│  │                      │      │                      │                    │
│  │  bencode→EDN         │      │  (eval! conn code)   │                    │
│  │  EDN→bencode         │      │  (load-file! c p)    │                    │
│  └──────────┬───────────┘      └──────────┬───────────┘                    │
│             │                             │                                 │
│             └──────────┬──────────────────┘                                 │
│                        ↓                                                    │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  LAYER 2: Sente Channel Adapter                                     │   │
│  │                                                                     │   │
│  │  send-nrepl-request!   : EDN → sente → browser                     │   │
│  │  handle-nrepl-response : browser → sente → EDN → deliver to caller │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                        │                                                    │
└────────────────────────┼────────────────────────────────────────────────────┘
                         │
                   [sente-lite channel - EDN messages]
                         │
┌────────────────────────┼────────────────────────────────────────────────────┐
│                        ↓                           BROWSER SIDE             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  LAYER 1: nREPL Server (EDN Protocol Handler)                       │   │
│  │                                                                     │   │
│  │  receive-nrepl-request : parse EDN, dispatch on :op                │   │
│  │  handle-eval           : eval via scittle.core.eval_string         │   │
│  │  handle-load-file      : load file content                         │   │
│  │  send-nrepl-response   : build EDN response, send via sente        │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Component Interfaces (CLJC Protocol)

**Layer 1: Browser nREPL Server (protocol.cljc)**
```clojure
;; Input message format
{:op :eval           ; or :load-file, :describe
 :code "(+ 1 2)"
 :id "msg-123"
 :session "sess-456"}

;; Output message format
{:id "msg-123"
 :session "sess-456"
 :value "3"          ; or :err, :out, :ex
 :ns "user"
 :status [:done]}    ; or [:done :error]
```

**Layer 2: Sente Channel Adapter**
```clojure
;; Send request TO browser
(send-nrepl-request! conn-id {:op :eval :code "(+ 1 2)" :id "123"})

;; Receive response FROM browser (callback or promise)
(on-nrepl-response! handler-fn)  ; handler-fn called with response EDN
```

**Layer 3a: Bencode Proxy**
```clojure
;; Receives bencode, converts to EDN, calls Layer 2
(start-bencode-proxy! {:port 1347 :channel-adapter adapter})

;; Receives EDN response from Layer 2, converts to bencode, sends to socket
```

**Layer 3b: Direct Function API**
```clojure
;; Synchronous-style API (returns promise/deferred)
(eval! conn-id "(+ 1 2)")        ; => promise of {:value "3" ...}
(load-file! conn-id "foo.cljs" "(ns foo) ...") ; => promise of result

;; Or with callback
(eval! conn-id "(+ 1 2)" {:on-result (fn [r] ...)})
```

### Two Calling Patterns

**Pattern A: Via Bencode Proxy (Editors/MCP)**
```
[Editor/MCP] ─bencode→ [Bencode Proxy:1347] ─EDN→ [Channel Adapter] ─sente→ [Browser]
```
- Editor connects via standard nREPL bencode protocol
- Proxy translates bencode ↔ EDN
- Leverages existing editor tooling (Cider, Calva, nREPL-MCP)

**Pattern B: Direct Function Calls (Server Runtime)**
```
(def conn-id @browser-conn-id)
(eval! conn-id "(reset! app-state new-val)")
;; => {:value "new-val" :status [:done]}
```
- Server code calls functions directly
- No bencode encoding/decoding
- Lower latency, simpler for programmatic use
- Useful for: server-initiated code push, health checks, state inspection

### Benefits of This Decomposition

1. **Reuse existing nREPL code**: The EDN message format IS nREPL, just without bencode
2. **Testable in isolation**: Each layer can be unit tested independently
3. **Flexible composition**: Mix and match layers as needed
4. **Two entry points**: Bencode proxy OR direct function calls
5. **Browser code unchanged**: Same nREPL server handles both patterns

## Implementation Approach

### Simpler than Full nREPL

We don't need the full nREPL protocol. For dynamic code deployment, we need:

1. **eval** - Evaluate code string, return result
2. **load-file** - Load a file's contents into browser
3. **describe** - Report capabilities (optional)

We explicitly DON'T need:
- Session management (browser is the session)
- Interrupt (browser is single-threaded)
- Middleware stacking
- Complex completion/eldoc (nice-to-have, not essential)

### Message Flow

**From Editor (via bencode proxy)**:
```clojure
;; Editor sends bencode to port 1347
{:op "eval" :code "(+ 1 2)" :id "123"}

;; Gateway translates to sente event
[:nrepl/eval {:code "(+ 1 2)" :id "123"}]

;; Browser evaluates, sends response
[:nrepl/result {:id "123" :value "3" :status [:done]}]

;; Gateway translates back to bencode
{:id "123" :value "3" :status ["done"]}
```

**Direct sente (no editor)**:
```clojure
;; Server sends directly
(sente/send! conn-id [:nrepl/eval {:code "(reset! app-state new-val)"}])

;; Browser responds
[:nrepl/result {:value "new-val" :status [:done]}]
```

### Browser Handler

```clojure
(defn handle-nrepl-eval [{:keys [code id]}]
  (try
    (let [result (scittle.core/eval_string code)]
      [:nrepl/result {:id id
                      :value (pr-str result)
                      :status [:done]}])
    (catch :default e
      [:nrepl/result {:id id
                      :err (.-message e)
                      :status [:done :error]}])))
```

## Files to Refactor/Create

### Existing (to refactor into module):
- `dev/scittle-demo/sente-nrepl-gateway.clj` → `modules/nrepl/src/gateway.clj`
- `dev/scittle-demo/examples/sente-nrepl-client.cljs` → `modules/nrepl/src/client.cljs`

### New:
- `modules/nrepl/README.md` - Usage documentation
- `modules/nrepl/src/protocol.cljc` - Shared event definitions
- `modules/nrepl/examples/` - Working examples

## Module Structure (Proposed)

```
modules/nrepl/
├── README.md                           # Usage guide
├── nrepl-sente-design.md              # This document
├── src/
│   ├── protocol.cljc                  # Layer 0: EDN message format, shared constants
│   │
│   ├── server.cljc                    # Layer 1: Browser nREPL server
│   │                                  #   - receive-nrepl-request
│   │                                  #   - handle-eval, handle-load-file
│   │                                  #   - send-nrepl-response
│   │
│   ├── channel.cljc                   # Layer 2: Sente channel adapter
│   │                                  #   - send-nrepl-request!
│   │                                  #   - on-nrepl-response!
│   │                                  #   - pending request tracking
│   │
│   ├── api.clj                        # Layer 3b: Direct function API (BB)
│   │                                  #   - eval!, load-file!, describe
│   │                                  #   - Promise/callback interface
│   │
│   └── bencode_proxy.clj              # Layer 3a: Bencode proxy (BB)
│                                      #   - bencode↔EDN translation
│                                      #   - Socket server for editors
│
├── examples/
│   ├── direct-api.bb                  # Using Layer 3b from BB
│   ├── with-editor.html               # Full setup with bencode proxy
│   └── ai-integration.bb              # Example AI/MCP usage
│
└── test/
    ├── protocol_test.cljc             # EDN format tests
    ├── server_test.bb                 # Browser server tests (via Playwright)
    ├── channel_test.bb                # Channel adapter tests
    └── api_test.bb                    # Direct API tests
```

## Testing Strategy: BB-to-BB First

**Principle**: Test everything with BB-to-BB before touching browser code.

### Why BB-to-BB First?

1. **Faster iteration**: No browser startup, no Playwright, instant feedback
2. **Easier debugging**: Full stack traces, println works, no console.log
3. **Proves the layers**: If it works BB-to-BB, browser is just a different eval target
4. **nREPL-MCP available**: Can test bencode proxy using Claude's nREPL-MCP tools

### Testing Phases

**Phase 1: BB nREPL Server (Layer 1 on BB)**
```
[BB Client] ──sente-lite──→ [BB nREPL Server]
                                   │
                            (eval via load-string)
                                   │
                            ←─response─┘
```
- Create `server.cljc` that works on BOTH BB and Scittle
- Test with BB first using `clojure.core/load-string` instead of `scittle.core.eval_string`
- Same code, different eval function per platform

**Phase 2: Channel Adapter (Layer 2)**
```
[BB] ──eval! ──→ [Channel Adapter] ──sente──→ [BB nREPL Server]
```
- Test send-nrepl-request! / on-nrepl-response!
- Test pending request tracking
- Test timeout handling

**Phase 3: Bencode Proxy (Layer 3a) via nREPL-MCP**
```
[Claude/nREPL-MCP] ──bencode:1347──→ [Proxy] ──sente──→ [BB nREPL Server]
```
- Use `mcp__nrepl_mcp_server__nrepl-eval` to test from this conversation
- Real editor protocol compliance verified
- AI can directly test its own integration path!

**Phase 4: Direct API (Layer 3b)**
```
(eval! conn-id "(+ 1 2)")  ; from BB server code
```
- Promise-based API
- Callback-based API

**Phase 5: Port to Scittle**
- Replace `clojure.core/load-string` with `scittle.core.eval_string`
- Test via Playwright
- Should "just work" if BB-to-BB is solid

### Test Matrix

| Layer | BB-to-BB | BB-to-Scittle | Via nREPL-MCP |
|-------|----------|---------------|---------------|
| L1: Server | First | Last | N/A |
| L2: Channel | Second | After L1 | N/A |
| L3a: Bencode | Third | After L2 | Yes! |
| L3b: Direct API | Fourth | After L2 | N/A |

## Connection Selection

**Problem**: Which conn-id should we send nREPL requests to?

### Simple Approach: Latest Connection (Phase 1)

For dev/testing, just use the most recently connected peer:

```clojure
(defn get-latest-connection []
  (->> (sente/get-connections)
       (sort-by :connected-at >)
       first
       :conn-id))

;; Simple API - uses latest connection
(nrepl/eval! "(+ 1 2)")

;; Explicit target when app knows which peer
(nrepl/eval! my-specific-conn-id "(+ 1 2)")
```

- No bookkeeping, no stale data - queries live connection state
- If target doesn't support nREPL → describe probe fails → error returned
- Good enough for single-browser dev workflow

### App-Managed Selection (Advanced)

For multi-peer scenarios, the app knows which peer it wants:
- App tracks peer identities (e.g., "browser-123" assigned on connect)
- App maintains its own `peer-name → conn-id` mapping
- App passes explicit conn-id to nREPL module

This is the app's problem, not the nREPL module's problem.

## Protocol Discovery

**Problem**: How does the server know the other side supports nREPL-over-sente?

### Current Approach: Describe Probe (Phase 1)

Send `:nrepl/describe` request and wait for response:

```clojure
(defn nrepl-capable? [conn-id timeout-ms]
  (let [response (send-request! conn-id (proto/describe-request) timeout-ms)]
    (and response (contains? (:ops response) "eval"))))
```

- Simple, works without additional infrastructure
- Synchronous check before sending eval requests
- Timeout indicates no nREPL support

### Future Enhancement: Synced Config Atom

The describe probe doesn't handle **dynamic capability addition** - if nREPL is added after connection, there's no notification.

**Better approach**: Use the registry/config-discovery pattern:

```clojure
;; Config atom synced between server and client
(registry/get-value "config.capabilities/nrepl")  ; => true or nil

;; Watch for capability changes
(registry/watch! "config.capabilities/nrepl"
  (fn [old new]
    (when new (println "nREPL now available!"))))
```

**Discovery algorithm**:
1. Check synced config atom for `:nrepl` capability
2. If atom exists and truthy → nREPL supported
3. If atom doesn't exist → fallback to describe probe
4. Cache result, invalidate on disconnect

**Benefits**:
- Reactive: notified when capability added/removed
- Works with dynamic setup (add nREPL after connection)
- Consistent with sente-lite's config-discovery patterns
- No polling or repeated probes

**Implementation**: Requires atom-sync module integration (future work).

## Open Questions

1. **Namespace**: `sente-lite.nrepl` or `nrepl-sente` or separate?
2. **Component integration**: Use component.cljc for gateway lifecycle?
3. **Telemetry**: How much visibility into eval traffic?
4. **Batching**: Support for multiple evals in single message?
5. **MCP protocol**: What additional ops would AI assistants benefit from? (inspect-state, list-namespaces, doc lookup?)
6. **Multi-browser**: Can one AI session control multiple browser tabs/instances?

## Pros/Cons Summary

### Pros of sente-lite for nREPL:
- Production-ready reconnection
- Multiplexed with app traffic (one connection)
- Full observability integration
- State tracking across reconnects
- Consistent with rest of sente-lite ecosystem

### Cons:
- More setup than built-in scittle.nrepl
- Additional code to maintain
- Slight latency overhead (EDN envelope)

## Decision

**Use sente-lite** for production dynamic code deployment because:
1. Auto-reconnect is non-negotiable for production
2. Unified channel simplifies architecture
3. Same-origin trust model is sufficient
4. Investment pays off in operational capability

## Implementation Status

### Phase 1: BB-to-BB (Layer 1) - COMPLETE

**Files created**:
- `modules/nrepl/src/nrepl_sente/protocol.cljc` - EDN message format
- `modules/nrepl/src/nrepl_sente/server.cljc` - nREPL server (eval handler)
- `modules/nrepl/test/test_nrepl_bb_to_bb.bb` - BB-to-BB test suite

**Test results**: 22/22 tests pass

**Verified operations**:
- `:eval` - code evaluation via `load-string`
- `:load-file` - file content evaluation
- `:describe` - server capability reporting
- `:clone` - session creation
- Error handling (divide by zero, etc.)
- Rapid sequential evals

### Phase 2: Channel Adapter / Client API (Layer 2) - COMPLETE

**Files created**:
- `modules/nrepl/src/nrepl_sente/client.clj` - Client API (BB/server-side only)
- `modules/nrepl/test/test_nrepl_client_api.bb` - Client API test suite

**Server changes**:
- `src/sente_lite/server.cljc` - Added registry-based connection tracking
  - `get-connections` - list active connections from registry
  - `get-latest-connection` - get most recent conn-id
  - Connections registered in registry on connect, unregistered on disconnect

**Client API features**:
- `get-connections` / `get-latest-connection` - connection discovery via registry
- `probe-nrepl-capable?` - send describe probe to verify nREPL capability
- `get-nrepl-connection!` - get verified connection (with probe and caching)
- `eval!` / `load-file!` - high-level API with pending request tracking
- `eval-latest!` / `load-file-latest!` - convenience functions
- `make-response-handler` - handler for routing responses to pending requests
- Capability caching with configurable max-age

**Test results**: 24/24 tests pass

**Pattern for server-side usage**:
```clojure
;; Combined handler for bidirectional nREPL
(def nrepl-handler (nrepl-server/make-nrepl-handler send-fn))
(def response-handler (client/make-response-handler))

(server/start-server! {:on-message (fn [conn-id event-id data]
                                     (nrepl-handler conn-id event-id data)
                                     (response-handler conn-id event-id data))})

;; Now can call browser from server
(client/eval! conn-id "(+ 1 2)")
;; => {:value "3" :status [:done] ...}
```

### Phase 3: Bencode Proxy (Layer 3a) - COMPLETE

**Files created**:
- `modules/nrepl/src/nrepl_sente/proxy.clj` - Bencode proxy server
- `modules/nrepl/test/test_nrepl_proxy.bb` - Proxy test suite

**Proxy features**:
- Accepts bencode connections from editors/nREPL-MCP
- Translates bencode ↔ EDN
- Routes requests to sente-lite peers via client API
- Automatic connection discovery (latest connection)
- Optional explicit target via registry (`nrepl.proxy/target-conn`)
- Filters REPL setup noise (clojure.main/repl-requires, etc.)

**Test results**: 19/19 tests pass

**Usage**:
```clojure
(require '[nrepl-sente.proxy :as proxy])

;; Start proxy (uses latest connection)
(proxy/start! {:port 1347})

;; Connect with nREPL client (Cider, nREPL-MCP, etc.)
;; nrepl://localhost:1347

;; Stop
(proxy/stop!)
```

**Total test count**: 65 tests (22 + 24 + 19)

**nREPL-MCP Integration Test (VERIFIED)**:

End-to-end test using Claude's nREPL-MCP tools:
```
[Claude/nREPL-MCP] ─bencode→ [Proxy:1347] ─sente→ [Peer:8765] ─eval→ [Result]
```

| Test | Result |
|------|--------|
| Basic arithmetic `(+ 1 2 3 4 5)` | ✓ 15 |
| Variable definition `(def my-var 42)` | ✓ |
| State persistence `(* my-var 10)` | ✓ 420 |
| Error handling `(/ 1 0)` | ✓ "Divide by zero" |
| Complex expressions `(let [a b c] ...)` | ✓ 6000 |
| Function definition `(defn square ...)` | ✓ |
| Function use `(map square (range 1 6))` | ✓ (1 4 9 16 25) |
| Cross-session state | ✓ all vars accessible |
| sente-lite server access `(server/get-server-port)` | ✓ 8765 |

**Test server script**: `modules/nrepl/test/run_proxy_server.bb`

### Phase 4: Scittle Browser Adapter - COMPLETE

**The Problem**: `scittle.nrepl.js` is a fantastic ready-made nREPL server that uses SCI to evaluate code. But it expects a direct WebSocket connection (`window.ws_nrepl`). We want to reuse this code but route messages through sente-lite instead.

**The Solution**: **FakeWebSocket Hijack Pattern**

Create a fake WebSocket object at `window.ws_nrepl` BEFORE loading `scittle.nrepl.js`. The nREPL server attaches to it, thinking it's a real WebSocket. We intercept both directions:

```
┌────────────────────────────────────────────────────────────────────────────┐
│                     FAKESOCKET HIJACK PATTERN                              │
└────────────────────────────────────────────────────────────────────────────┘

                          BROWSER
┌───────────────────────────────────────────────────────────────────────────┐
│                                                                           │
│  ┌─────────────────┐         ┌─────────────────────────────────────────┐ │
│  │ scittle.nrepl.js│         │          FakeWebSocket                  │ │
│  │                 │◄───────►│  (window.ws_nrepl)                      │ │
│  │ - sci.nrepl     │ attach  │                                         │ │
│  │ - server code   │ onmessage│  .send(data)    → sendFn → sente      │ │
│  │                 │         │  .injectMessage(data) ← from sente     │ │
│  └─────────────────┘         └─────────────────────────────────────────┘ │
│                                          │                                │
│                              ┌───────────┴───────────┐                   │
│                              │   browser_adapter.cljs │                   │
│                              │                        │                   │
│                              │  - connect!            │                   │
│                              │  - handle-nrepl-request│                   │
│                              │  - send-nrepl-response!│                   │
│                              └───────────┬────────────┘                   │
│                                          │                                │
│                              ┌───────────┴───────────┐                   │
│                              │  sente-lite client    │                   │
│                              │  (client_scittle.cljs) │                   │
│                              └───────────┬────────────┘                   │
│                                          │                                │
└──────────────────────────────────────────┼────────────────────────────────┘
                                           │
                                     [WebSocket]
                                           │
┌──────────────────────────────────────────┼────────────────────────────────┐
│                                          │                    BB SERVER   │
│                              ┌───────────┴───────────┐                   │
│                              │   sente-lite server   │                   │
│                              └───────────────────────┘                   │
└───────────────────────────────────────────────────────────────────────────┘
```

**Files created**:
- `modules/nrepl/src/nrepl_sente/fake_websocket.cljs` - FakeWebSocket (inline version in HTML)
- `modules/nrepl/src/nrepl_sente/browser_adapter.cljs` - Scittle integration

**Key insight**: `scittle.nrepl.js` checks for `window.ws_nrepl` at load time. If it exists and has `readyState === 1` (OPEN), it attaches its `onmessage` handler to receive eval requests.

**FakeWebSocket (CLJS)**:
```clojure
(defonce !fake-ws-state
  (atom {:ready-state 1 :onmessage nil :send-fn nil :pending-inbound []}))

(defn create-fake-ws []
  (let [obj (js-obj)]
    ;; readyState property (scittle.nrepl checks this)
    (js/Object.defineProperty obj "readyState"
      #js {:get (fn [] (:ready-state @!fake-ws-state)) :configurable true})

    ;; onmessage - scittle.nrepl.js sets this
    (js/Object.defineProperty obj "onmessage"
      #js {:get (fn [] (:onmessage @!fake-ws-state))
           :set (fn [f] (swap! !fake-ws-state assoc :onmessage f))
           :configurable true})

    ;; send() - Called when nREPL sends response
    (aset obj "send" (fn [data]
      (if-let [f (:send-fn @!fake-ws-state)] (f data))))

    ;; injectMessage() - Called when sente receives nREPL request
    (aset obj "injectMessage" (fn [data]
      (if-let [f (:onmessage @!fake-ws-state)] (f #js {:data data}))))

    ;; setSendFn() - Called by browser_adapter.cljs
    (aset obj "setSendFn" (fn [f]
      (swap! !fake-ws-state assoc :send-fn f)))
    obj))

(when-not (aget js/window "ws_nrepl")
  (aset js/window "ws_nrepl" (create-fake-ws)))
```

**Scittle integration (browser_adapter.cljs)**:
```clojure
(defn connect! [{:keys [client]}]
  (let [ws (.-ws_nrepl js/window)]
    ;; Set up outbound: nREPL response → sente
    (.setSendFn ws send-nrepl-response!)

    ;; Set up inbound: sente → nREPL request
    (sente/on! client {:event-id :nrepl/request
                       :callback handle-nrepl-request})))

(defn send-nrepl-response! [edn-str]
  (sente/send! @!client-id [:nrepl/response {:edn edn-str}]))

(defn handle-nrepl-request [msg]
  (.injectMessage (.-ws_nrepl js/window) (:edn (:data msg))))
```

**⚠️ CRITICAL: HTML Load Order with `eval_script_tags()`**

The FakeWebSocket **MUST** be created **BEFORE** loading `scittle.nrepl.js`. Use inline CLJS + forced evaluation:

```html
<!-- 1. Load Scittle core -->
<script src="scittle.js"></script>

<!-- ⚠️ 2. INLINE FakeWebSocket (queued for Scittle) -->
<script type="application/x-scittle">
  ;; FakeWebSocket code here (inline, not external file!)
  (defonce !fake-ws-state (atom {...}))
  (defn create-fake-ws [] ...)
  (when-not (aget js/window "ws_nrepl")
    (aset js/window "ws_nrepl" (create-fake-ws)))
</script>

<!-- ⚠️ 3. FORCE EVALUATION before scittle.nrepl.js loads! -->
<script>scittle.core.eval_script_tags();</script>

<!-- 4. NOW load scittle.nrepl.js (finds window.ws_nrepl ready) -->
<script src="scittle.nrepl.js"></script>

<!-- 5. Load sente-lite client and adapter -->
<script src="client_scittle.cljs" type="application/x-scittle"></script>
<script src="browser_adapter.cljs" type="application/x-scittle"></script>
```

**Why `eval_script_tags()` is required**:

Scittle CLJS scripts are normally queued and evaluated after DOMContentLoaded. Without forced evaluation:
```javascript
// scittle.nrepl.js checks at load time:
if (window.ws_nrepl && window.ws_nrepl.readyState === 1) {
  // Attach onmessage handler to existing socket ✓
  window.ws_nrepl.onmessage = function(e) { ... }
} else {
  // Create new WebSocket - WRONG! Our FakeWebSocket isn't ready yet!
  window.ws_nrepl = new WebSocket(...)
}
```

If our FakeWebSocket isn't already at `window.ws_nrepl` with `readyState = 1` (OPEN), the nREPL code will create its own real WebSocket and we lose the ability to intercept messages.

**Common mistake**: Loading in wrong order causes silent failure - nREPL appears to work but messages go to a real WebSocket instead of sente-lite.

**Pure CLJS Solution using `eval_script_tags()`**:

By default, Scittle CLJS scripts (`type="application/x-scittle"`) are **queued** and processed **after** all synchronous scripts have loaded. This would cause a problem:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DEFAULT BROWSER SCRIPT LOADING ORDER                     │
└─────────────────────────────────────────────────────────────────────────────┘

   <script src="scittle.js">           │ 1. Loads and runs immediately
   <script src="fake.cljs" type="..."> │ 2. QUEUED for Scittle (not run yet!)
   <script src="scittle.nrepl.js">     │ 3. Loads and runs immediately ← PROBLEM!

   After all scripts load:
   Scittle processes queued .cljs      │ 4. TOO LATE - nrepl.js already ran
```

**Solution**: Use `scittle.core.eval_script_tags()` to force immediate execution BEFORE scittle.nrepl.js loads:

```html
<!-- 1. Load Scittle core -->
<script src="scittle.js"></script>

<!-- 2. Inline FakeWebSocket CLJS (queued but not yet run) -->
<script type="application/x-scittle">
  (defonce !fake-ws-state (atom {:ready-state 1 :onmessage nil ...}))
  (defn create-fake-ws [] ...)
  (when-not (aget js/window "ws_nrepl")
    (aset js/window "ws_nrepl" (create-fake-ws)))
</script>

<!-- 3. FORCE IMMEDIATE evaluation of FakeWebSocket -->
<script>scittle.core.eval_script_tags();</script>

<!-- 4. NOW load scittle.nrepl.js - FakeWebSocket is installed! -->
<script src="scittle.nrepl.js"></script>
```

This works because:
1. The inline CLJS is queued by Scittle
2. `eval_script_tags()` processes all queued scripts immediately
3. `window.ws_nrepl` is now set with our FakeWebSocket
4. scittle.nrepl.js finds it and attaches its `onmessage` handler
5. Subsequent `eval_script_tags()` calls at page bottom work fine (idempotent)

**Key insight**: The FakeWebSocket code MUST be INLINE (not external file) because external `src` attributes use async XHR fetch even with `eval_script_tags()`. Inline scripts are processed synchronously.

**Test results**: 4 evals → 8 responses (value + done each)
- `(+ 1 2 3)` → 6 ✓
- `(def x 42)` → #'user/x ✓
- `(* x 2)` → 84 ✓ (state persists!)
- `(do (def y 10) (+ x y))` → 52 ✓

**Why this is elegant**:
1. **100% Clojure/Script** - No JavaScript files needed, pure CLJS throughout
2. **Zero changes to scittle.nrepl.js** - We don't fork or modify upstream
3. **Reuses proven code** - sci.nrepl is battle-tested
4. **Clean separation** - FakeWebSocket and adapter are pure Scittle CLJS
5. **Decoupled from transport** - sente-lite handles reconnection, the nREPL code doesn't care

### Phase 5: nbb nREPL Module - COMPLETE

See: `modules/nrepl-nbb/README.md`

Unlike Scittle, nbb doesn't have an nREPL server to hijack. Instead, we use `nbb.core/load-string` directly. The `server.cljc` already detects the runtime and uses the appropriate eval function.

**Files created**:
- `modules/nrepl-nbb/README.md` - Comprehensive documentation
- `modules/nrepl-nbb/examples/nbb_nrepl_client.cljs` - Working nbb client
- `modules/nrepl-nbb/test/test_nbb_nrepl.bb` - Integration test

**Test results**: 5/5 tests pass
```
[test]   ✓ test-1: (+ 1 2 3) = 6
[test]   ✓ test-2: (def my-var 42) = #'user/my-var
[test]   ✓ test-3: (* my-var 10) = 420 (state persisted!)
[test]   ✓ test-4: (do (def y 5) (+ my-var y)) = 47
[test]   ✓ test-5: (/ 1 0) = ##Inf (JS returns Infinity, not error)
```

**Key insight documented**: Why sync vs async eval doesn't matter - the WebSocket transport layer already provides async decoupling. The eval function runs inside an async message handler callback, so whether it returns immediately (sync) or via Promise (async), the transport layer just waits for `send!` to be called.

## Updates Log

| Date | Change |
|------|--------|
| 2024-12-21 | Initial design document created |
| 2024-12-21 | Added AI-assisted development use case (nREPL-MCP integration) |
| 2024-12-21 | Added component architecture with 4 decoupled layers |
| 2024-12-21 | Added two calling patterns: bencode proxy vs direct function API |
| 2024-12-21 | Added BB-to-BB first testing strategy with nREPL-MCP integration |
| 2024-12-21 | **Phase 1 complete**: Layer 1 (server.cljc) working, 22/22 tests pass |
| 2024-12-21 | Added protocol discovery: describe probe now, synced config atom future |
| 2024-12-21 | Added connection selection: latest connection for dev, app-managed for advanced |
| 2024-12-21 | **Phase 2 complete**: Client API (client.clj) with registry-based discovery, 24/24 tests pass |
| 2024-12-21 | Added registry-based connection tracking to server.cljc (get-connections, get-latest-connection) |
| 2024-12-21 | **Phase 3 complete**: Bencode proxy (proxy.clj), 19/19 tests pass, total 65 tests |
| 2024-12-21 | **nREPL-MCP integration verified**: Full end-to-end test with Claude's nREPL-MCP tools working |
| 2024-12-21 | **Phase 4 complete**: Scittle Browser Adapter - FakeWebSocket hijack pattern documented |
| 2024-12-21 | **Phase 5 complete**: nbb nREPL module with working example and tests (5/5 pass) |
| 2024-12-21 | Added critical documentation about FakeWebSocket load order requirement |
| 2024-12-21 | Documented why sync vs async eval is irrelevant (transport layer provides async boundary) |
