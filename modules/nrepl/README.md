# nREPL-over-Sente Module

Dynamic code deployment over sente-lite WebSocket connections.

## Overview

This module enables nREPL-style code evaluation over sente-lite channels. Unlike traditional development nREPL, this is **production-capable infrastructure** for remotely deploying and evaluating code in browsers, nbb, or Babashka peers.

**Key Features:**
- Push code to running browsers without page reload
- Hot-patch bugs dynamically
- Full REPL semantics (eval, load-file, describe)
- Works with nREPL clients (Cider, Calva, nREPL-MCP)
- Automatic reconnection via sente-lite

## Use Cases

### 1. Dynamic Code Deployment
Push updates to browsers without reloading:
```
[Developer] → [BB Server] → [sente-lite] → [Browser]
```

### 2. AI-Assisted Development
AI assistants connect via nREPL-MCP to interact with live browsers:
```
[Claude Code] → [nREPL-MCP] → [Bencode Proxy] → [sente-lite] → [Browser]
```

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                          BB SERVER                                │
│                                                                   │
│  ┌─────────────────┐    ┌─────────────────┐                      │
│  │ Layer 3a:       │    │ Layer 3b:       │                      │
│  │ Bencode Proxy   │    │ Direct API      │                      │
│  │ (for editors)   │    │ (for scripts)   │                      │
│  └────────┬────────┘    └────────┬────────┘                      │
│           └──────────┬───────────┘                                │
│                      ↓                                            │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Layer 2: Client API (client.clj)                            │ │
│  │ eval!, load-file!, get-connections, probe-nrepl-capable?    │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
                          │
                    [sente-lite]
                          │
┌──────────────────────────────────────────────────────────────────┐
│                    BROWSER / NBB / BB PEER                        │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │ Layer 1: nREPL Server (server.cljc)                         │ │
│  │ handle-eval, handle-load-file, handle-describe              │ │
│  └─────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────┘
```

## Quick Start

### Server-Side (BB)

```clojure
(require '[sente-lite.server :as server]
         '[nrepl-sente.server :as nrepl-server]
         '[nrepl-sente.client :as client]
         '[nrepl-sente.proxy :as proxy])

;; Create handlers
(def nrepl-handler (nrepl-server/make-nrepl-handler server/send-event-to-connection!))
(def response-handler (client/make-response-handler))

;; Start sente-lite server with nREPL handling
(server/start-server!
  {:port 8765
   :on-message (fn [conn-id event-id data]
                 (nrepl-handler conn-id event-id data)
                 (response-handler conn-id event-id data))})

;; Start bencode proxy for editors/nREPL-MCP
(proxy/start! {:port 1347})

;; Or use direct API
(client/eval! conn-id "(+ 1 2 3)")
;; => {:value "6" :status [:done] ...}
```

### Browser Client (Scittle)

```html
<!-- 1. Load Scittle -->
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.js"></script>

<!-- 2. Create FakeWebSocket BEFORE scittle.nrepl.js -->
<script type="application/x-scittle">
  ;; FakeWebSocket hijacks window.ws_nrepl
  (defonce !fake-ws-state
    (atom {:ready-state 1 :onmessage nil :send-fn nil}))
  ;; ... (see fake_websocket.cljs for full code)
</script>
<script>scittle.core.eval_script_tags();</script>

<!-- 3. NOW load scittle.nrepl.js -->
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.nrepl.js"></script>

<!-- 4. Load sente-lite and adapter -->
<script type="application/x-scittle" src="sente_lite/client_scittle.cljs"></script>
<script type="application/x-scittle" src="nrepl_sente/browser_adapter.cljs"></script>
```

### BB Peer (BB-to-BB)

```clojure
(require '[sente-lite.client-bb :as client]
         '[nrepl-sente.server :as nrepl-server])

(def my-client
  (client/make-client!
    {:url "ws://localhost:8765/"
     :on-message (fn [event-id data]
                   (nrepl-server/handle-message! event-id data send!))}))
```

## API Reference

### Layer 1: Server (server.cljc)

```clojure
;; Create message handler
(make-nrepl-handler send-fn)

;; Handle incoming nREPL request
(handle-nrepl! conn-id event-id data send-fn)
```

Supported operations:
- `:eval` - Evaluate code string
- `:load-file` - Load file content
- `:describe` - Report capabilities
- `:clone` - Create session (no-op, returns session ID)

### Layer 2: Client API (client.clj)

```clojure
;; Connection discovery
(get-connections)           ; List all active connections
(get-latest-connection)     ; Most recent connection
(probe-nrepl-capable? conn) ; Check if peer supports nREPL
(get-nrepl-connection!)     ; Get verified nREPL-capable connection

;; High-level API
(eval! conn-id code)        ; Evaluate code
(load-file! conn-id path content)  ; Load file
(eval-latest! code)         ; Eval on latest connection
(load-file-latest! path content)

;; Response handling
(make-response-handler)     ; Create handler for routing responses
```

### Layer 3a: Bencode Proxy (proxy.clj)

```clojure
(start! {:port 1347})       ; Start proxy for editors
(stop!)                     ; Stop proxy
(get-proxy-port)            ; Get listening port
```

## Testing

```bash
# Run all nREPL tests (74 tests)
bb modules/nrepl/test/test_nrepl_bb_to_bb.bb      # 22 tests
bb modules/nrepl/test/test_nrepl_client_api.bb    # 24 tests
bb modules/nrepl/test/test_nrepl_proxy.bb         # 19 tests
bb modules/nrepl/test/test_nrepl_ns_persistence.bb # 9 tests

# Start proxy server for manual testing
bb modules/nrepl/test/run_proxy_server.bb
# Then connect with: nrepl://localhost:1347
```

## Files

```
modules/nrepl/
├── README.md                    # This file
├── nrepl-sente-design.md        # Detailed design document
├── src/nrepl_sente/
│   ├── protocol.cljc            # EDN message format
│   ├── server.cljc              # Layer 1: nREPL server
│   ├── client.clj               # Layer 2: Client API
│   ├── proxy.clj                # Layer 3a: Bencode proxy
│   ├── fake_websocket.cljs      # Browser FakeWebSocket
│   └── browser_adapter.cljs     # Browser sente integration
└── test/
    ├── test_nrepl_bb_to_bb.bb
    ├── test_nrepl_client_api.bb
    ├── test_nrepl_proxy.bb
    ├── test_nrepl_ns_persistence.bb
    └── run_proxy_server.bb
```

## Design Details

See `nrepl-sente-design.md` for:
- FakeWebSocket hijack pattern for browsers
- Why sente-lite over plain WebSocket
- Security model (same-origin trust)
- Protocol discovery options
- BB-to-BB first testing strategy

## License

Eclipse Public License 2.0
