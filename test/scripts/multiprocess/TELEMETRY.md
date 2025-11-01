# Telemetry Control for BB-to-BB Tests

## Overview

Telemetry is **disabled by default** for maximum performance. Enable it via environment variables or nREPL to see the 99 telemetry calls across sente-lite source code (56 unique event IDs).

## Quick Start: Enable Telemetry via Environment Variables

### 1. Run Tests with Telemetry

```bash
# Enable telemetry for all test processes
TELEMETRY=1 bb test/scripts/multiprocess/01_basic_multiprocess.bb

# Enable with specific level
TELEMETRY=1 TELEMETRY_LEVEL=debug bb test/scripts/multiprocess/01_basic_multiprocess.bb

# Run entire test suite with telemetry
TELEMETRY=1 ./run_tests.bb
```

### 2. Telemetry Levels

- `debug` - Show all events (very verbose)
- `info` - Show info, warn, error (default)
- `warn` - Show only warnings and errors
- `error` - Show only errors

### 3. Per-Process Control

```bash
# Server only
TELEMETRY=1 bb test/scripts/multiprocess/mp_server.bb test-001 30

# Client only
TELEMETRY=1 bb test/scripts/multiprocess/mp_client.bb test-001 client-1 channel-1 10
```

## Alternative: Enable via nREPL-MCP (Runtime Control)

### For BB Server (mp_server.bb)

Connect to the server's nREPL and eval:

```clojure
(require '[telemere-lite.core :as tel])

;; Enable telemetry globally
(tel/set-enabled! true)

;; Add stdout handler (shows all events in console)
(tel/add-stdout-handler!)

;; Verify it's on
(tel/get-enabled?)  ;=> true
```

### For BB Client (mp_client.bb)

Same commands in client's nREPL:

```clojure
(require '[telemere-lite.core :as tel])
(tel/set-enabled! true)
(tel/add-stdout-handler!)
```

### 3. For Browser (Scittle)

Telemetry is **already enabled** in `dev/scittle-demo/telemetry-config.cljs` with console sink.

You can also control it from browser console:

```javascript
// Runtime controls
window.telemetryEnable()
window.telemetryDisable()
window.telemetryStatus()
window.telemetryConsoleOn()
window.telemetryConsoleOff()
```

Or via browser's nREPL (port 1339):

```clojure
(require '[telemere-lite.core :as tel])
(tel/set-enabled! true)
(tel/enable-console-sink!)
```

## Filtering by Log Level

Control verbosity by filtering event levels:

```clojure
;; Show only errors and warnings
(tel/set-ns-filter! {:allow ["sente-lite.*"]})

;; Show specific event IDs
(tel/set-id-filter! {:allow ["::connection-added" "::message-sent"]})

;; Reset to show all
(tel/clear-filters!)
```

## What You'll See

With telemetry enabled, you'll see **56 different event types**:

**Connection Events:**
- `::connection-added`, `::connection-removed`
- `::websocket-opened`, `::websocket-closed`

**Message Events:**
- `::message-parsed`, `::message-sent`, `::message-routed`
- `::message-published`, `::message-unwrapped`

**Heartbeat Events:**
- `::heartbeat-check`, `::heartbeat-ping-sent`
- `::heartbeat-timeout`, `::heartbeat-cleanup-complete`

**Pub/Sub Events:**
- `::subscription-added`, `::subscription-removed`
- `::broadcast-start`, `::broadcast-complete`

**RPC Events:**
- `::rpc-request-sent`, `::rpc-response-sent`
- `::rpc-request-expired`

**Format Events:**
- `::format-negotiated`, `::format-detection`
- `::multiplex-serialize-complete`

...and 41 more!

## Handlers

### Stdout Handler (best for tests)
```clojure
(tel/add-stdout-handler!)  ;; See events in console
```

### File Handler (best for production)
```clojure
(tel/add-file-handler! "logs/test-telemetry.jsonl")
```

### Stderr Handler
```clojure
(tel/add-stderr-handler!)
```

### Check Handler Stats
```clojure
(tel/get-handler-stats)
(tel/get-handler-health)
```

## Performance Impact

- **Disabled**: ~60-120ns per call (just boolean check)
- **Enabled**: ~180-350ns per call (includes serialization)
- **99 telemetry calls** exist in source code
- **All use lazy evaluation** - `:let` params not evaluated when disabled

## Example Session

```clojure
;; 1. Start server with nREPL
(cd test/scripts/multiprocess && bb mp_server.bb test-001 30)

;; 2. Connect to nREPL via nrepl-mcp

;; 3. Enable telemetry
(require '[telemere-lite.core :as tel])
(tel/set-enabled! true)
(tel/add-stdout-handler!)

;; 4. Run client - you'll now see all events!
;; Connection events, message events, heartbeat events, etc.

;; 5. Disable when done
(tel/set-enabled! false)
```

## See Also

- Source code telemetry calls: `grep "tel/" src/sente_lite/*.{cljc,cljs}`
- Event IDs list: `grep "::event!" src/sente_lite/*.{cljc,cljs} | sed 's/.*::/::/;s/ .*//' | sort -u`
- Performance docs: `doc/telemere-lite-lazy-eval-improvement.md`
