# Scittle nREPL Demo

This demo sets up a persistent Babashka instance that serves a browser-based Clojure REPL using Scittle with nREPL-over-WebSocket.

## Architecture

```
┌─────────────────────────────────────────────────┐
│  Babashka Instance (Persistent)                 │
│                                                  │
│  ┌──────────────────┐  ┌────────────────────┐  │
│  │ HTTP Server      │  │ nREPL WebSocket    │  │
│  │ Port: 1341       │  │ Ports: 1339, 1340  │  │
│  └──────────────────┘  └────────────────────┘  │
│           │                      │               │
└───────────┼──────────────────────┼───────────────┘
            │                      │
            ▼                      ▼
    ┌───────────────────────────────────────┐
    │  Browser (Chromium via Playwright)    │
    │                                        │
    │  ┌──────────────────────────────────┐ │
    │  │ Scittle (ClojureScript Runtime)  │ │
    │  │  - Loads via CDN                  │ │
    │  │  - Connects to WebSocket :1340    │ │
    │  │  - Evaluates playground.cljs      │ │
    │  └──────────────────────────────────┘ │
    └───────────────────────────────────────┘
            ▲
            │
    ┌───────┴────────┐
    │ Your Editor    │
    │ (CIDER, Calva) │
    │ → :1339        │
    └────────────────┘
```

## What This Enables

1. **AI-Driven Browser Development**: Claude can use Playwright to control the browser, inspect DOM, and verify ClojureScript execution
2. **Live REPL to Browser**: Connect your editor's nREPL client to evaluate code directly in the browser
3. **Automated Testing**: Playwright tests can verify Scittle functionality without manual intervention
4. **Future sente-lite Integration**: Foundation for adding WebSocket communication alongside nREPL

## Quick Start

### 1. Start the BB Server

```bash
cd dev/scittle-demo
bb dev
```

This starts:
- HTTP server on port 1341 (serves index.html)
- nREPL server on port 1339 (for editor connection)
- WebSocket server on port 1340 (for browser ↔ BB communication)

### 2. Manual Browser Testing

Open http://localhost:1341 in your browser and you should see:
- Alert: "Scittle nREPL Demo loaded!"
- Console log with connection info
- DOM element: "ClojureScript is running in the browser!"

### 3. Connect Your Editor

**For CIDER (Emacs):**
```elisp
M-x cider-connect-cljs
Host: localhost
Port: 1339
REPL type: nbb
```

**For Calva (VS Code):**
1. "Calva: Connect to a Running REPL Server"
2. Enter host: localhost, port: 1339
3. Select "nbb" as the REPL type

Once connected, evaluate code in `playground.cljs` and see it execute in the browser!

### 4. Playwright Browser Testing

First, install Playwright:
```bash
cd dev/scittle-demo
npm run install-playwright
```

**Option A: Automated Test (30 seconds)**
```bash
npm test
```

This will:
- Launch Chromium browser
- Navigate to http://localhost:1341
- Verify Scittle loads
- Check DOM manipulation
- Capture a screenshot
- Keep browser open for 30 seconds for inspection

**Option B: Interactive Session (indefinite)**
```bash
npm run interactive
```

This will:
- Launch Chromium browser with DevTools
- Keep browser open indefinitely for manual testing
- Log all WebSocket traffic (nREPL messages)
- Log browser console output
- Stay open until you press Ctrl+C

Use this interactive mode to connect an nREPL client and test live code evaluation.

### 5. Connect nREPL Client (Rebel)

With the interactive browser session running:

```bash
clojure -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.0.0"}}}' \
        -m nrepl.cmdline --connect --host localhost --port 1339
```

Or use rebel-readline for a better REPL experience:
```bash
clojure -Sdeps '{:deps {com.bhauman/rebel-readline {:mvn/version "0.1.4"} nrepl/nrepl {:mvn/version "1.0.0"}}}' \
        -m nrepl.cmdline --connect --host localhost --port 1339 --interactive
```

Now evaluate code and watch it execute in the browser:
```clojure
(+ 1 2 3)
;; => 6

(js/alert "Hello from nREPL!")
;; Alert appears in browser

(-> js/document
    (.getElementById "body")
    (.appendChild (js/document.createTextNode "REPL works!")))
;; Text appears in browser
```

## Files

- `bb.edn` - Babashka dependencies and tasks
- `index.html` - HTML page that loads Scittle
- `playground.cljs` - ClojureScript code executed in browser
- `playwright-test.mjs` - Automated 30-second browser test
- `playwright-interactive.mjs` - Interactive browser session for manual testing
- `package.json` - Node.js dependencies (Playwright)

## Next Steps

- [ ] Add sente-lite WebSocket connection alongside nREPL
- [ ] Implement bidirectional messaging (browser ↔ BB)
- [ ] Create test scenarios for sente-lite functionality
- [ ] Build real-time telemetry from browser to BB

## How It Works

### Browser nREPL Connection

1. Browser loads `index.html` which includes:
   - `scittle.js` - Core Scittle runtime (SCI interpreter)
   - `scittle.nrepl.js` - nREPL **server** implementation (runs in browser!)
   - `playground.cljs` - Your ClojureScript code

2. `scittle.nrepl.js` opens WebSocket connection to `ws://localhost:1340`

3. BB's `sci.nrepl.browser-server` acts as a **protocol gateway**:
   - Accepts bencode messages from nREPL clients (editor/rebel) on port 1339
   - Converts bencode ↔ EDN string format
   - Proxies messages over WebSocket to browser's nREPL server on port 1340

4. Your nREPL client connects to port 1339 and can now evaluate code in the browser:
   ```
   nREPL Client → BB Gateway → Browser nREPL Server
   (bencode)      :1339        (EDN/WebSocket)      :1340
   ```

### Why This Matters for AI Development

- **Observable**: Playwright gives AI full visibility into browser state (DOM, console, errors)
- **Automated**: No manual clicking required - AI can verify functionality programmatically
- **Iterative**: AI can make changes, run tests, inspect failures, and iterate
- **Realistic**: Tests run in real Chromium, not mocked environment

This is the foundation for AI-driven Scittle development with full test automation.
