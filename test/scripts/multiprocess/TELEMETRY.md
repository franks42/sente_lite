# Sente-lite Telemetry Guide

## Overview

Sente-lite uses **Trove** (`taoensso.trove`) for structured logging and telemetry. This guide covers how logging works across different platforms and how to configure it.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    taoensso.trove/log!                      │
│         (single API for all logging calls)                  │
└─────────────────────────────────────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
   ┌─────────────┐     ┌─────────────┐     ┌─────────────┐
   │  Babashka   │     │    JVM      │     │   Browser   │
   │   Timbre    │     │   Timbre    │     │   Console   │
   │  → File     │     │  → File     │     │  → DevTools │
   └─────────────┘     └─────────────┘     └─────────────┘
```

## Default Configuration

| Platform | Backend | Output | Min Level |
|----------|---------|--------|-----------|
| Babashka | Timbre  | `logs/trove.log` | `:info` |
| JVM      | Timbre  | `logs/trove.log` | `:info` |
| Browser  | Console | DevTools console | `:info` |

**Key point:** Babashka/JVM logging goes to **file only** (no stdout pollution).

## Log Levels

From least to most severe:
- `:trace` - Very detailed debugging
- `:debug` - Debugging information
- `:info` - Normal operational events (default)
- `:warn` - Warning conditions
- `:error` - Error conditions
- `:fatal` - Fatal errors

## Viewing Logs

### Babashka/JVM: File Logs

```bash
# View live logs
tail -f logs/trove.log

# View recent logs
tail -50 logs/trove.log

# Search for errors
grep ":error" logs/trove.log

# Search for specific event IDs
grep ":sente-lite.server/starting" logs/trove.log
```

### Browser: DevTools Console

Open browser DevTools (F12) → Console tab. Logs appear with appropriate console methods (info, warn, error).

## Log Format

Each log entry contains:

```
TIMESTAMP HOSTNAME LEVEL [NAMESPACE:LINE] - :event-id
  data: {...}
```

Example:
```
2025-12-01T06:09:04.114Z localhost INFO [sente-lite.server-simple:122] - :sente-lite.server/starting
  data: {:port 3001, :host "localhost"}
```

## Event IDs in Sente-lite

Sente-lite uses structured event IDs following the pattern `:namespace/event-name`:

**Server Events:**
- `:sente-lite.server/starting` - Server startup initiated
- `:sente-lite.server/started` - Server successfully started
- `:sente-lite.server/stopping` - Server shutdown initiated
- `:sente-lite.server/stopped` - Server stopped

**Connection Events:**
- `:sente-lite.server/connection-opened` - WebSocket connected
- `:sente-lite.server/connection-closed` - WebSocket disconnected

**Channel Events:**
- `:sente-lite.channels/subscribed` - Client subscribed to channel
- `:sente-lite.channels/unsubscribed` - Client unsubscribed
- `:sente-lite.channels/published` - Message published to channel

**Wire Format Events:**
- `:sente-lite.format/json-serial-failed` - JSON serialization error
- `:sente-lite.format/registered` - Custom format registered

## Configuring Logging

### Change Log Level (Runtime)

```clojure
(require '[taoensso.timbre :as timbre])

;; Set minimum level
(timbre/set-min-level! :debug)  ; More verbose
(timbre/set-min-level! :warn)   ; Less verbose
```

### Add Console Output (Babashka/JVM)

```clojure
(require '[taoensso.timbre :as timbre])

;; Enable console appender alongside file
(timbre/merge-config!
  {:appenders {:println {:enabled? true}}})
```

### Disable File Logging

```clojure
(require '[taoensso.timbre :as timbre])

(timbre/merge-config!
  {:appenders {:spit {:enabled? false}}})
```

### Custom Log File Path

```clojure
(require '[taoensso.timbre :as timbre])

(timbre/merge-config!
  {:appenders {:spit (timbre/spit-appender 
                       {:fname "my-custom-path.log"})}})
```

## Using Trove in Your Code

### Basic Logging

```clojure
(require '[taoensso.trove :as trove])

;; Structured log with event ID and data
(trove/log! {:level :info
             :id :my-app/user-login
             :data {:user-id 123 :ip "192.168.1.1"}})

;; With message
(trove/log! {:level :warn
             :id :my-app/rate-limit
             :msg "Rate limit exceeded"
             :data {:requests 100 :limit 50}})

;; Error with exception
(trove/log! {:level :error
             :id :my-app/db-error
             :error exception
             :data {:query "SELECT ..."}})
```

### Log Levels Shorthand

```clojure
;; These all work
(trove/log! {:level :trace :id ::my-event})
(trove/log! {:level :debug :id ::my-event})
(trove/log! {:level :info  :id ::my-event})
(trove/log! {:level :warn  :id ::my-event})
(trove/log! {:level :error :id ::my-event})
(trove/log! {:level :fatal :id ::my-event})
```

## Running Tests with Logging

### View Logs During Tests

```bash
# Terminal 1: Watch logs
tail -f logs/trove.log

# Terminal 2: Run tests
bb test/scripts/multiprocess/01_basic_multiprocess.bb
```

### Run All Multiprocess Tests

```bash
bb test/scripts/multiprocess/run_multiprocess_tests.bb
```

Tests available:
1. `01_basic_multiprocess.bb` - 1 server + 2 clients
2. `02_ephemeral_reconnection.bb` - Server port changes
3. `03_reconnection.bb` - Auto-reconnect after restart
4. `04_concurrent_startup.bb` - 10 simultaneous clients
5. `05_process_failure.bb` - Client crash recovery
6. `06_stress_test.bb` - 20 clients, high throughput

## Troubleshooting

### No logs appearing?

1. Check file exists: `ls -la logs/trove.log`
2. Check permissions: `touch logs/trove.log`
3. Verify Trove is loaded: `(require '[taoensso.trove])`

### Too verbose?

```clojure
(timbre/set-min-level! :warn)
```

### Need stdout for debugging?

```clojure
(timbre/merge-config!
  {:appenders {:println {:enabled? true}}})
```

### Clear old logs?

```bash
> logs/trove.log  # Truncate file
```

## Files Reference

| File | Purpose |
|------|---------|
| `src/taoensso/trove.cljc` | Trove facade (configures Timbre) |
| `src/taoensso/trove/timbre.cljc` | Timbre backend |
| `src/taoensso/trove/console.cljc` | Console backend (browser) |
| `logs/trove.log` | Default log output |

## See Also

- Find all log calls: `grep "trove/log!" src/sente_lite/*.cljc`
- Find event IDs: `grep ":sente-lite" src/sente_lite/*.cljc`
- Trove docs: https://github.com/taoensso/trove
- Timbre docs: https://github.com/taoensso/timbre
