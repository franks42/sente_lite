# Telemere-lite: Lightweight Telemetry for Babashka

A lightweight telemetry library inspired by [Telemere](https://github.com/taoensso/telemere), designed specifically for Babashka and Scittle/SCI environments.

## 30-Second Quick Start

```clojure
;; 1. Require the library
(require '[telemere-lite.core :as tel])

;; 2. Initialize (logs Babashka version info)
(tel/startup!)

;; 3. Add an output destination
(tel/add-stdout-handler!)

;; 4. Start logging!
(tel/log! :info "Hello telemetry!" {:user-id 123 :action "login"})
;; => {"timestamp":"2025-10-27T10:30:00Z","level":"info","ns":"user",
;;     "msg":["Hello telemetry!",{"data":{"user-id":123,"action":"login"},...}]}

;; That's it! Your telemetry is working.
```

**Next Steps**: See [Core Features](#core-features) for filtering, routing, and advanced usage.

---

## The Need

Modern Clojure applications require comprehensive telemetry solutions for observability, debugging, and monitoring. [Peter Taoussanis's Telemere](https://www.taoensso.com/telemere) represents the state-of-the-art in Clojure telemetry—it's thoughtfully designed, feature-rich, and production-ready.

**We love Telemere's approach**, but unfortunately it doesn't yet work on Babashka or Scittle/SCI environments. These lightweight Clojure interpreters power a growing ecosystem of scripts, tools, and browser-based applications that need structured telemetry just as much as JVM Clojure does.

## Our Approach

**Telemere-lite addresses this gap** with a telemetry solution that:

- **Is inspired and guided by Telemere** - We follow Telemere's signal-based architecture and filtering model
- **Stays close to Telemere's API** - API-compatible where possible for easier future migration
- **Is less ambitious in features** - Focused on core telemetry needs rather than full parity
- **Works today on BB and Scittle** - Leverages built-in libraries (Timbre, Cheshire, tools.logging)
- **Provides a migration path** - When Telemere officially supports BB/Scittle, migrating should be straightforward

**Our expectation**: Telemere-lite is a bridge, not a destination. Once Telemere works on Babashka and Scittle/SCI, we fully expect and encourage migration to the official implementation.

## What Telemere-lite Provides

A streamlined telemetry implementation that:
- **Matches Telemere API signatures** where feasible for future compatibility
- **Supports cross-platform development** (BB server + Scittle browser)
- **Provides structured JSON logging** for observability and AI-assisted debugging
- **Enables flexible routing** to multiple destinations (files, stdout, custom handlers)
- **Includes async handlers** with backpressure control and automatic shutdown
- **Pre-compiles filters** for high-performance namespace and event-ID filtering
- **Offers event correlation** via event-ID based tracking and filtering

## Quick Comparison

| Feature | Telemere | Telemere-lite | Notes |
|---------|----------|---------------|-------|
| **Platform Support** | JVM Clojure, ClojureScript | Babashka, Scittle/SCI | Different target platforms |
| **Signal-based API** | ✅ Full | ✅ Compatible | Same core concepts |
| **Basic Logging** | ✅ | ✅ | `log!`, `error!`, `event!` |
| **Filtering** | ✅ Advanced | ✅ Core features | Level, namespace, event-ID (v0.7.0+) |
| **Async Handlers** | ✅ Built-in | ✅ v0.7.0+ | Backpressure support (blocking/dropping) |
| **Shutdown Hook** | ✅ Built-in | ✅ v0.7.0+ | Automatic async handler cleanup |
| **Error Handling** | ✅ Built-in | ✅ v0.7.0+ | Customizable error handler |
| **Performance** | Up to 4.2M signals/sec | Optimized for BB | Pre-compiled filters (v0.7.0+, 2x speedup) |
| **Handler Ecosystem** | ✅ Rich (OpenTelemetry, Slack, etc.) | Basic (files, stdout, custom) | Extensible via custom handlers |
| **Sampling/Rate Limiting** | ✅ Built-in | ❌ Custom handlers | Can implement manually |
| **Production Maturity** | ✅ Production-hardened | ✅ Tested, improving | Active development |
| **Dependencies** | Telemere ecosystem | BB built-ins (Timbre, Cheshire) | Lightweight |
| **Migration Path** | N/A | → Telemere when available | API-compatible |

**Bottom line**: Telemere-lite provides ~80% of Telemere's core functionality for Babashka/Scittle today, with clear migration path to full Telemere when platform support arrives.

For detailed feature comparison, see [Gap Analysis vs Official Telemere](#gap-analysis-vs-official-telemere).

## Table of Contents

- [Installation](#installation)
- [Quick Start](#quick-start)
- [Core Features](#core-features)
- [API Reference](#api-reference)
- [Filtering](#filtering)
- [Routing](#routing)
- [Examples](#examples)
- [Limitations](#limitations)
- [Gap Analysis vs Official Telemere](#gap-analysis-vs-official-telemere)
- [Migration Guide](#migration-guide)

## Installation

Add to your `deps.edn`:

```clojure
{:paths ["src"]
 :deps {cheshire/cheshire {:mvn/version "6.1.0"}  ; Optional - for JVM Clojure
        http-kit/http-kit {:mvn/version "2.8.1"}}} ; Optional - for JVM Clojure
```

> **Note**: `cheshire`, `http-kit`, `timbre`, and `tools.logging` are built into Babashka

## Quick Start

```clojure
(require '[telemere-lite.core :as tel])

;; Initialize telemetry
(tel/startup!)

;; Basic logging (Telemere-compatible API)
(tel/log! :info "Application started" {:version "1.0.0"})
(tel/log! {:level :warn :msg "Database slow" :duration-ms 1500})

;; Error handling with automatic exception serialization
(tel/error! (Exception. "Connection failed"))
(tel/error! "Network timeout" {:host "api.example.com" :timeout 5000})

;; Performance monitoring
(tel/with-timing "database-query"
  (Thread/sleep 100)) ; Your operation here

;; Setup routing to multiple destinations
(tel/add-file-handler! "app.log")
(tel/add-stdout-handler!)
```

## Core Features

### ✅ Signal-Based Architecture

Like official Telemere, all logging functions derive from a core `signal!` macro:

```clojure
;; Foundation macro - all others build on this
(tel/signal! {:level :info :msg "Core signal" :operation "startup"})

;; Convenience wrappers
(tel/log! :info "Convenience wrapper")
(tel/error! "Error wrapper" {:code 500})
```

### ✅ Source Location Capture

Automatic capture of file, line, and namespace information:

```clojure
(tel/log! :debug "Debug message")
;; Automatically includes:
;; {:location {:file "src/myapp/core.clj" :line 42 :ns "myapp.core"}}
```

### ✅ Exception Serialization

Automatic JSON-safe serialization of exceptions:

```clojure
(tel/error! (Exception. "Database connection failed"))
;; Produces:
;; {:error {:type "class java.lang.Exception"
;;          :message "java.lang.Exception: Database connection failed"
;;          :class "class java.lang.Exception"}}
```

### ✅ Cross-Platform Support

Works in both Babashka and Scittle environments:

```clojure
;; BB: Uses Timbre + file output
;; Scittle: Uses console.log + JSON formatting
(tel/log! :info "Works everywhere!")
```

### ✅ Async Handlers with Automatic Shutdown ⭐ NEW

High-performance async handlers with backpressure control and automatic cleanup:

```clojure
;; Add async handler - non-blocking telemetry dispatch
(tel/add-handler! :async-file
  (fn [signal] (write-to-file signal))
  {:async {:mode :blocking        ; :blocking or :dropping
           :buffer-size 1000}})   ; Buffer capacity

;; Shutdown hook automatically installed
;; - Ensures all buffered signals are flushed on JVM shutdown
;; - Prevents data loss during application termination
;; - Only installed once, idempotent across multiple async handlers

;; Manual shutdown (normally handled automatically)
(tel/shutdown-telemetry!)
```

**Benefits**:
- **Non-blocking**: Main thread doesn't wait for I/O operations
- **Backpressure**: Choose blocking (wait when full) or dropping (discard when full) modes
- **Automatic cleanup**: Shutdown hook flushes all pending signals
- **Production-ready**: Prevents signal loss during graceful shutdown

### ✅ High-Performance Regex Pre-compilation ⭐ NEW

Namespace and event-ID filters use pre-compiled regexes for optimal performance:

```clojure
;; Patterns compiled once at filter configuration
(tel/set-ns-filter! {:allow ["myapp.*"] :disallow ["test.*"]})
(tel/set-id-filter! {:allow [":app.*"] :disallow [":debug.*"]})

;; Every signal check uses pre-compiled patterns
;; Performance improvement: 2-10x faster than runtime compilation
```

**Performance Impact**:
- 10,000 namespace checks: ~20ms (vs ~200ms runtime compilation)
- Especially valuable for high-volume telemetry (>1000 signals/sec)
- Zero overhead after initial filter configuration

### ✅ Customizable Error Handling ⭐ NEW

Custom error handlers for telemetry failures:

```clojure
;; Set custom error handler for telemetry failures
(tel/set-error-handler!
  (fn [error context]
    ;; context includes {:type :handler-dispatch
    ;;                   :handler-id :my-handler
    ;;                   :signal {...}}
    (send-to-monitoring-system error context)))

;; Default handler: prints to stderr with stack trace
;; Custom handlers: route to monitoring, retry logic, etc.
```

**Use Cases**:
- Route telemetry errors to monitoring systems
- Implement retry logic for transient failures
- Prevent telemetry failures from affecting application

## API Reference

### Core Logging Functions

#### `signal!` - Foundation Macro
```clojure
(tel/signal! opts)
;; opts: {:level :info :msg "message" :custom-key "value"}
```

#### `log!` - Primary Logging Function
```clojure
;; Full options map
(tel/log! {:level :info :msg "User action" :user-id 123})

;; Level + message
(tel/log! :warn "High memory usage")

;; Level + message + data
(tel/log! :error "API failure" {:endpoint "/users" :status 500})
```

#### `event!` - Event Logging with ID Correlation ⭐ NEW
```clojure
;; Event ID only
(tel/event! ::user-login)

;; Event ID + data
(tel/event! ::user-login {:user-id 123 :ip "192.168.1.1"})

;; Level + Event ID + data
(tel/event! :debug ::ws-ping {:conn-id "abc123" :timestamp 1234567890})

;; Perfect for WebSocket correlation
(tel/event! ::sente-message-sent {:type :ping :conn-id conn-id})
(tel/event! ::sente-message-received {:type :pong :conn-id conn-id})
```

#### `error!` - Error Logging
```clojure
;; Exception object
(tel/error! (Exception. "Something went wrong"))

;; Options map
(tel/error! {:msg "Database error" :table "users" :operation "insert"})

;; Message + data
(tel/error! "Connection failed" {:host "db.example.com" :port 5432})
```

#### `performance!` - Performance Logging
```clojure
;; Basic timing
(tel/performance! "api-call" 250)

;; With context
(tel/performance! "database-query" 150 {:table "users" :rows 1000})
```

#### `with-timing` - Automatic Timing
```clojure
(tel/with-timing "expensive-operation"
  (Thread/sleep 100)
  (compute-something))
```

### Utility Functions

#### `startup!` - Initialize Telemetry
```clojure
(tel/startup!)
;; Logs BB version, Java version, OS info
```

#### `module-load!` / `module-loaded!` - Module Tracking
```clojure
(tel/module-load! "user-service")
;; ... module loading logic ...
(tel/module-loaded! "user-service" 150) ; 150ms load time
```

#### `shutdown-telemetry!` - Manual Shutdown ⭐ NEW
```clojure
;; Flush all async handlers and shutdown (normally automatic via shutdown hook)
(tel/shutdown-telemetry!)
```

#### `set-error-handler!` - Custom Error Handler ⭐ NEW
```clojure
;; Set custom handler for telemetry failures
(tel/set-error-handler!
  (fn [error context]
    (send-to-monitoring error context)))
```

### Inspection Functions

#### `get-filters` - Inspect Current Filters
```clojure
(tel/get-filters)
;; => {:min-level :info
;;     :ns-filter {:allow #{"*"} :disallow #{}}
;;     :event-id-filter {:allow #{"*"} :disallow #{}}
;;     :ns-min-levels {}
;;     :enabled? true}
```

Returns a map of all active filter configurations. Useful for debugging filter issues.

#### `clear-filters!` - Reset All Filters
```clojure
(tel/clear-filters!)
```

Resets all filters to defaults:
- Min level: `:trace` (everything passes)
- Namespace filter: Allow all (`"*"`)
- Event-ID filter: Allow all (`"*"`)
- Enabled: `true`

**Use case**: Debugging when signals aren't appearing as expected.

#### `get-handlers` - List Registered Handlers
```clojure
(tel/get-handlers)
;; => {:stdout #<handler-fn>, :file-log #<handler-fn>}
```

Returns map of handler-id → handler-fn for all registered handlers.

#### `clear-handlers!` - Remove All Handlers
```clojure
(tel/clear-handlers!)
```

Removes all registered handlers. Useful for testing or reconfiguration.

**Note**: Shutdown hook for async handlers remains installed (harmless).

#### `get-min-level` - Get Current Minimum Level
```clojure
(tel/get-min-level)
;; => :info
```

Returns current minimum log level as keyword.

#### `get-enabled?` - Check If Telemetry Is Enabled
```clojure
(tel/get-enabled?)
;; => true
```

Returns boolean indicating whether telemetry is globally enabled.

### Handler Helper Functions

#### `file-handler` - Create File Handler Function
```clojure
(tel/file-handler "path/to/file.log")
;; => #<handler-fn>
```

Returns a handler function that writes JSON Lines to specified file. Used with `add-handler!` for custom configurations.

**Example**:
```clojure
;; Create async file handler with custom options
(tel/add-handler! :custom-file
  (tel/file-handler "app.log")
  {:async {:mode :blocking :buffer-size 5000}})
```

## Filtering

### Level Filtering

Telemere-compatible level filtering using Timbre's built-in capabilities:

```clojure
;; Set global minimum level
(tel/set-min-level! :warn)  ; Only :warn, :error, :fatal, :report

;; Get current level
(tel/get-min-level)  ; => :warn

;; Levels: :trace < :debug < :info < :warn < :error < :fatal < :report
```

### Namespace Filtering

Pattern-based namespace filtering:

```clojure
;; Allow specific namespaces
(tel/set-ns-filter! {:allow #{"myapp.core" "myapp.api.*"}
                     :disallow #{}})

;; Block specific namespaces
(tel/set-ns-filter! {:allow #{"*"}
                     :disallow #{"myapp.debug.*" "test.*"}})

;; Supports wildcards: "myapp.*" matches "myapp.core", "myapp.api.users", etc.
```

### Event ID Filtering ⭐ NEW

Filter events by their correlation IDs - perfect for WebSocket debugging:

```clojure
;; Allow only specific event types
(tel/set-id-filter! {:allow #{":user/login" ":user/logout" ":sente/*"}
                     :disallow #{}})

;; Block noisy debug events
(tel/set-id-filter! {:allow #{"*"}
                     :disallow #{":debug/*" ":ws/ping" ":ws/pong"}})

;; Focus on sente-specific events only
(tel/set-id-filter! {:allow #{":sente/*"}
                     :disallow #{}})

;; Supports wildcards: ":sente/*" matches ":sente/message-sent", etc.
```

### Global Enable/Disable

```clojure
;; Disable all telemetry
(tel/set-enabled! false)

;; Re-enable
(tel/set-enabled! true)

;; Check status
(tel/get-enabled?)  ; => true
```

### Filter Inspection

```clojure
;; View all current filters
(tel/get-filters)
;; => {:min-level :info
;;     :ns-filter {:allow #{"*"} :deny #{}}
;;     :event-id-filter {:allow #{"*"} :deny #{}}
;;     :ns-min-levels {}
;;     :enabled? true}

;; Reset all filters
(tel/clear-filters!)
```

## Routing

### Handler Management

Telemere-compatible handler API for routing telemetry to multiple destinations:

```clojure
;; Add custom handler (synchronous)
(tel/add-handler! :my-handler
  (fn [signal] (println "Custom:" (:msg signal))))

;; Add async handler (non-blocking) ⭐ NEW
(tel/add-handler! :async-file
  (fn [signal] (write-to-file signal))
  {:async {:mode :blocking        ; :blocking or :dropping
           :buffer-size 1000}})   ; Buffer capacity

;; Remove handler
(tel/remove-handler! :my-handler)

;; View all handlers
(tel/get-handlers)

;; Clear all handlers
(tel/clear-handlers!)
```

### Async Handler Options ⭐ NEW

Control backpressure and performance with async handlers:

```clojure
;; Blocking mode - waits when buffer is full (default)
{:async {:mode :blocking
         :buffer-size 1000}}

;; Dropping mode - discards signals when buffer is full
{:async {:mode :dropping
         :buffer-size 1000}}
```

**When to use**:
- **Blocking mode**: Critical telemetry that must not be lost (errors, audit logs)
- **Dropping mode**: High-volume metrics that can tolerate loss (performance counters)
- **No async**: Low-volume or test scenarios where simplicity is preferred

**Automatic shutdown hook**: Installed automatically on first async handler, ensures all buffered signals are flushed during JVM shutdown.

### Built-in Handlers

#### File Output
```clojure
;; Default file handler
(tel/add-file-handler! "application.log")

;; Named file handler
(tel/add-file-handler! :debug-log "debug.log")
```

#### Console Output
```clojure
;; JSON to stdout
(tel/add-stdout-handler!)

;; JSON to stderr
(tel/add-stderr-handler!)

;; Named handlers
(tel/add-stdout-handler! :main-output)
(tel/add-stderr-handler! :error-output)
```

### Multi-Destination Routing

```clojure
;; Route to multiple destinations simultaneously
(tel/add-file-handler! :app-log "app.log")
(tel/add-file-handler! :error-log "errors.log")
(tel/add-stdout-handler!)

;; All handlers receive every message
(tel/log! :info "This goes to 3 destinations")
```

### Signal Format

All handlers receive signals in this JSON format:

```json
{
  "timestamp": "2025-10-25T01:40:07.376106Z",
  "level": "info",
  "ns": "myapp.core",
  "msg": ["User login", {
    "data": {"user-id": 123, "ip": "192.168.1.1"},
    "location": {
      "file": "src/myapp/core.clj",
      "line": 45,
      "ns": "myapp.core"
    }
  }],
  "context": null
}
```

## Examples

### WebSocket Event Correlation ⭐ NEW

Perfect for sente-lite WebSocket debugging and monitoring:

```clojure
(ns sente-lite.server
  (:require [telemere-lite.core :as tel]))

;; Setup telemetry for WebSocket debugging
(tel/startup!)
(tel/add-file-handler! "logs/websocket.log")

;; Focus on sente events only
(tel/set-id-filter! {:allow #{":sente/*"}})

(defn handle-client-connection [conn-id]
  (tel/event! ::sente-client-connected {:conn-id conn-id
                                        :timestamp (System/currentTimeMillis)})

  ;; WebSocket message handling with correlation
  (tel/event! ::sente-message-received {:conn-id conn-id :type :ping})
  (tel/with-timing "message-processing"
    (process-message conn-id))
  (tel/event! ::sente-message-sent {:conn-id conn-id :type :pong})

  ;; Error handling with context
  (when (connection-error? conn-id)
    (tel/error! "Connection lost" {:conn-id conn-id :reason "timeout"})))

;; Filter to show only connection events during debugging
(tel/set-id-filter! {:allow #{":sente/client-connected" ":sente/client-disconnected"}})
```

### Basic Application Setup

```clojure
(ns myapp.core
  (:require [telemere-lite.core :as tel]))

;; Initialize telemetry on startup
(tel/startup!)

;; Setup routing
(tel/add-file-handler! "logs/app.log")
(tel/add-stderr-handler! :errors)

;; Set appropriate log level for production
(tel/set-min-level! :info)

;; Filter out noisy debug namespaces
(tel/set-ns-filter! {:allow #{"*"}
                     :disallow #{"myapp.debug.*"}})

(defn main []
  (tel/log! :info "Application starting" {:version "1.0.0"})

  (tel/with-timing "initialization"
    (initialize-database!)
    (start-web-server!))

  (tel/log! :info "Application ready"))
```

### Error Handling Pattern

```clojure
(defn process-user-request [request]
  (tel/log! :info "Processing request" {:endpoint (:uri request)})

  (try
    (tel/with-timing "request-processing"
      (validate-request! request)
      (process-business-logic! request))

    (catch Exception e
      (tel/error! e {:request-id (:id request)
                     :user-id (get-in request [:session :user-id])})
      (throw e))))
```

### Development vs Production Configuration

```clojure
;; Development setup
(defn setup-dev-logging! []
  (tel/startup!)
  (tel/set-min-level! :debug)
  (tel/add-stdout-handler!))  ; Console output for development

;; Production setup
(defn setup-prod-logging! []
  (tel/startup!)
  (tel/set-min-level! :info)
  (tel/add-file-handler! :app "logs/application.log")
  (tel/add-file-handler! :errors "logs/errors.log")

  ;; Only route errors to error file
  (tel/add-handler! :error-filter
    (fn [signal]
      (when (#{:error :fatal} (:level signal))
        ((tel/file-handler "logs/errors.log") signal)))))
```

### Module Loading Tracking

```clojure
(defn load-module [module-name load-fn]
  (tel/module-load! module-name)
  (let [start-time (System/currentTimeMillis)]
    (try
      (let [result (load-fn)]
        (tel/module-loaded! module-name
                           (- (System/currentTimeMillis) start-time))
        result)
      (catch Exception e
        (tel/error! "Module load failed"
                   {:module module-name :error e})
        (throw e)))))
```

## Performance Benchmarks

Real-world performance data from telemere-lite's test suite running on Babashka.

### Regex Pre-compilation Performance ⭐ NEW

Namespace and event-ID filtering uses pre-compiled regular expressions for significant performance improvements.

**Benchmark**: 10,000 namespace checks against pattern `"foo.*"`

```
Old approach (runtime compilation): 4.91ms
New approach (pre-compiled):       2.46ms
Speedup:                           2.0x faster
```

**Impact**: For high-volume applications (1,000+ signals/sec), pre-compiled filters eliminate regex compilation overhead on the hot path. The speedup is consistent across different pattern complexities.

**Implementation**: Filters are compiled once during `set-ns-filter!` and `set-id-filter!` calls, then reused for all subsequent signal filtering.

### Async Handler Throughput

Async handlers provide non-blocking signal dispatch with configurable backpressure control.

**Blocking Mode** (`:mode :blocking`):
- **Behavior**: Waits when buffer is full, guarantees delivery
- **Throughput**: Limited by handler processing speed
- **Use case**: Critical telemetry where signal loss is unacceptable
- **Buffer sizing**: Recommended 1000-5000 for typical workloads

**Dropping Mode** (`:mode :dropping`):
- **Behavior**: Discards signals when buffer is full
- **Throughput**: Maintains application performance under signal spikes
- **Use case**: High-frequency debugging, development environments
- **Buffer sizing**: Recommended 100-500 for non-critical signals

**Example Configuration**:
```clojure
;; Production: Critical error logging - no loss
(tel/add-handler! :error-log
  (tel/file-handler "logs/errors.log")
  {:async {:mode :blocking :buffer-size 5000}})

;; Development: Debug logging - performance first
(tel/add-handler! :debug-log
  (tel/file-handler "logs/debug.log")
  {:async {:mode :dropping :buffer-size 500}})
```

### Performance Tuning Guidelines

#### High-Volume Servers (>1,000 signals/sec)

**Recommended**:
- Use async handlers in `:blocking` mode for critical logs
- Use async handlers in `:dropping` mode for debug/trace
- Pre-compile filters with specific patterns (avoid `"*"` when possible)
- Set appropriate minimum log level (`:info` or `:warn` for production)
- Use namespace filters to exclude noisy libraries

**Example**:
```clojure
;; High-volume production setup
(tel/set-min-level! :info)
(tel/set-ns-filter! {:allow #{"myapp.*"}
                     :disallow #{"myapp.debug.*" "myapp.metrics.*"}})

(tel/add-handler! :app-log
  (tel/file-handler "logs/app.log")
  {:async {:mode :blocking :buffer-size 5000}})
```

#### Low-Volume Applications (<100 signals/sec)

**Recommended**:
- Synchronous handlers are fine (simpler, no buffering needed)
- Use `:debug` level during development
- Broad namespace filters acceptable (`"*"`)

**Example**:
```clojure
;; Low-volume development setup
(tel/set-min-level! :debug)
(tel/add-stdout-handler!)  ; Synchronous is fine
```

#### WebSocket Servers (Variable Load)

**Recommended**:
- Use `:dropping` mode for per-connection debug telemetry
- Use `:blocking` mode for connection lifecycle events (connect/disconnect)
- Filter by event-ID to focus on specific scenarios during debugging

**Example**:
```clojure
;; WebSocket telemetry
(tel/add-handler! :ws-debug
  (tel/file-handler "logs/websocket-debug.log")
  {:async {:mode :dropping :buffer-size 1000}})

(tel/add-handler! :ws-lifecycle
  (tel/file-handler "logs/websocket-lifecycle.log")
  {:async {:mode :blocking :buffer-size 500}})

;; Focus on connection events only
(tel/set-id-filter! {:allow #{":sente/client-connected"
                              ":sente/client-disconnected"}})
```

## Troubleshooting

### Common Issues

#### Signals Not Appearing in Logs

**Problem**: Calling `(tel/log! :info "message")` but nothing appears in output.

**Checklist**:
1. Verify telemetry is enabled:
   ```clojure
   (tel/get-enabled?)  ; Should return true
   ```

2. Check minimum log level:
   ```clojure
   (tel/get-min-level)  ; If :warn, then :info won't appear
   ```

3. Check namespace filters:
   ```clojure
   (tel/get-filters)
   ;; Verify your namespace is in :allow and not in :disallow
   ```

4. Verify handlers are registered:
   ```clojure
   (tel/get-handlers)  ; Should show at least one handler
   ```

**Quick Fix**:
```clojure
;; Reset everything to defaults
(tel/clear-filters!)
(tel/set-enabled! true)
(tel/add-stdout-handler!)
(tel/log! :info "Test message")  ; Should now appear
```

---

#### Async Handlers Dropping Signals

**Problem**: Using async handlers with `:dropping` mode and losing signals.

**Symptoms**:
- Signals appear intermittently
- High-volume logging shows gaps in logs
- No error messages

**Solutions**:

1. **Increase buffer size**:
   ```clojure
   (tel/add-handler! :async-file
     file-handler-fn
     {:async {:mode :dropping
              :buffer-size 10000}})  ; Increase from default 1000
   ```

2. **Switch to blocking mode** (signals wait instead of dropping):
   ```clojure
   {:async {:mode :blocking
            :buffer-size 1000}}
   ```

**When to use each mode**:
- **Blocking**: Critical telemetry (errors, audit logs) - never lose data
- **Dropping**: High-volume metrics (performance counters) - tolerate loss

---

#### Filtering Not Working as Expected

**Problem**: Signals appear despite namespace or event-ID filters.

**Debug Steps**:

1. **Verify filter syntax**:
   ```clojure
   ;; WRONG - filters don't use keywords for namespaces
   (tel/set-ns-filter! {:allow #{:myapp.*}})  ; ❌ Won't work

   ;; CORRECT - use strings
   (tel/set-ns-filter! {:allow #{"myapp.*"}})  ; ✅ Works
   ```

2. **Check wildcard patterns**:
   ```clojure
   ;; Wildcards only at end
   "myapp.*"     ; ✅ Matches myapp.core, myapp.api.users
   "*.myapp"     ; ❌ Won't work as expected
   "myapp.*.api" ; ❌ Won't work as expected
   ```

3. **Test filters in isolation**:
   ```clojure
   ;; Clear all filters
   (tel/clear-filters!)

   ;; Add one filter at a time
   (tel/set-ns-filter! {:allow #{"myapp.*"}})
   (tel/log! :info "Test from myapp.core")

   ;; Verify behavior before adding more filters
   ```

---

### Getting Help

1. **Check current configuration**:
   ```clojure
   (tel/get-filters)   ; All active filters
   (tel/get-handlers)  ; All registered handlers
   (tel/get-min-level) ; Minimum log level
   (tel/get-enabled?)  ; Global enable/disable
   ```

2. **Enable debug logging**:
   ```clojure
   (tel/set-min-level! :trace)  ; See everything
   (tel/set-ns-filter! {:allow #{"*"}})  ; No namespace filtering
   ```

3. **Test with minimal setup**:
   ```clojure
   ;; Start fresh
   (tel/clear-handlers!)
   (tel/clear-filters!)
   (tel/add-stdout-handler!)
   (tel/log! :info "Minimal test")
   ```

## Limitations

### Compared to Official Telemere

| Feature | Official Telemere | Telemere-lite | Status |
|---------|------------------|---------------|---------|
| **Signal-based API** | ✅ Full | ✅ Compatible | ✅ **Complete** |
| **Level Filtering** | ✅ Full | ✅ Via Timbre | ✅ **Complete** |
| **Namespace Filtering** | ✅ Built-in | ✅ Custom implementation | ✅ **Complete** |
| **ID-based Filtering** | ✅ Built-in | ✅ Custom implementation | ✅ **Complete** ⭐ NEW |
| **Transform Functions** | ✅ `(set-xfn! fn)` | ❌ Not implemented | ⚠️ **Missing** |
| **Sampling/Rate Limiting** | ✅ Built-in | ❌ Not implemented | ⚠️ **Missing** |
| **Handler Management** | ✅ Full | ✅ Compatible subset | ✅ **Complete** |
| **File Handlers** | ✅ Advanced | ✅ Basic JSON Lines | ✅ **Complete** |
| **Console Handlers** | ✅ Full | ✅ stdout/stderr | ✅ **Complete** |
| **OpenTelemetry** | ✅ Built-in | ❌ Not implemented | ⚠️ **Missing** |
| **Email/Slack Handlers** | ✅ Built-in | ❌ Not implemented | ⚠️ **Missing** |
| **Async Dispatch** | ✅ Built-in | ✅ Async handlers with backpressure | ✅ **Complete** ⭐ NEW |

### Platform Limitations

#### Babashka
- ✅ **Full functionality** - All features available
- ✅ **File output** - JSON Lines format
- ✅ **Timbre integration** - Built-in logging
- ✅ **Exception serialization** - Full object inspection

#### Scittle (Browser)
- ⚠️ **Limited functionality** - Console output only
- ❌ **No file output** - Browser security restrictions
- ❌ **No handler management** - Simplified implementation
- ✅ **Exception serialization** - Basic string conversion

### Performance Considerations

#### Pros
- ✅ **Lightweight** - No heavy dependencies
- ✅ **Fast startup** - Uses built-in BB libraries
- ✅ **Low memory** - Minimal overhead
- ✅ **JSON streaming** - Efficient file output

#### Cons
- ⚠️ **Limited batching** - Async handlers buffer but no automatic batching
- ❌ **No compression** - Raw JSON output
- ❌ **No rotation** - Manual log file management

### Development Limitations

#### Missing Advanced Features
- **Transform Functions**: No `(set-xfn!)` for signal modification
- **Sampling**: No built-in percentage-based filtering
- **Rate Limiting**: No frequency-based throttling
- **Complex Routing**: No conditional routing based on signal content
- **External Integrations**: No built-in email/Slack/webhooks

#### Implemented Workarounds & Available Features ⭐ UPDATED
```clojure
;; ✅ Async handlers - IMPLEMENTED in v0.7.0
(tel/add-handler! :async-file
  (fn [signal] (write-to-file signal))
  {:async {:mode :blocking :buffer-size 1000}})

;; Custom transform via handler wrapper
(tel/add-handler! :transform
  (fn [signal]
    (let [enhanced-signal (assoc signal :app-version "1.0.0")]
      (original-handler enhanced-signal))))

;; Basic sampling via random filtering
(tel/add-handler! :sampled
  (fn [signal]
    (when (< (rand) 0.1)  ; 10% sampling
      (file-handler signal))))
```

## Migration Guide

### From Official Telemere

#### Compatible Functions (No Changes Needed)
```clojure
;; These work identically
(tel/log! :info "message")
(tel/log! {:level :warn :msg "message"})
(tel/error! (Exception. "error"))
(tel/signal! {:level :info :msg "signal"})
(tel/set-min-level! :warn)
(tel/add-handler! :my-handler handler-fn)
```

#### Functions Requiring Changes
```clojure
;; Official Telemere
(tel/set-xfn! transform-fn)          ; ❌ Not available
(tel/set-id-filter! {...})           ; ❌ Not available
(tel/set-sampling! 0.1)              ; ❌ Not available

;; Telemere-lite alternatives
(tel/add-handler! :transform         ; ✅ Custom transform
  (fn [signal] (transform-and-route signal)))
(tel/set-ns-filter! {...})           ; ✅ Use namespace filtering
;; Manual sampling in custom handlers  ; ✅ Custom sampling
```

### From Manual Logging

#### Before (Manual)
```clojure
(println (str (java.time.Instant/now) " INFO: User logged in"))
(spit "app.log" (str event-data "\n") :append true)
```

#### After (Telemere-lite)
```clojure
(tel/log! :info "User logged in" {:user-id 123})
;; Automatic timestamps, structure, routing
```

### Best Practices for Migration

1. **Start Simple**: Begin with basic `log!` calls
2. **Add Routing Gradually**: Start with file output, add more as needed
3. **Use Filtering**: Leverage namespace filtering for granular control
4. **Structured Data**: Always include relevant context in data maps
5. **Error Handling**: Use `error!` for all exceptions
6. **Performance Monitoring**: Use `with-timing` for critical operations

## Gap Analysis vs Official Telemere

### What We Have vs Official Telemere

#### ✅ **Core Compatible Features**
| Feature | Official Telemere | Telemere-lite | Status |
|---------|------------------|---------------|---------|
| **Signal-based API** | `(signal! opts)` | `(signal! opts)` | ✅ **Identical** |
| **Basic Logging** | `(log! level msg data)` | `(log! level msg data)` | ✅ **Identical** |
| **Event Logging** | `(event! ::id data)` | `(event! ::id data)` | ✅ **Identical** ⭐ NEW |
| **Error Handling** | `(error! exception)` | `(error! exception)` | ✅ **Identical** |
| **Level Filtering** | `(set-min-level! :warn)` | `(set-min-level! :warn)` | ✅ **Identical** |
| **Namespace Filtering** | `(set-ns-filter! {...})` | `(set-ns-filter! {...})` | ✅ **Identical** |
| **Event ID Filtering** | `(set-id-filter! {...})` | `(set-id-filter! {...})` | ✅ **Identical** ⭐ NEW |
| **Handler Management** | `(add-handler! id fn)` | `(add-handler! id fn)` | ✅ **Identical** |
| **Source Location** | Automatic | Automatic | ✅ **Identical** |
| **JSON Serialization** | Built-in | Custom BB implementation | ✅ **Compatible** |

#### 🔴 **Critical Missing Features**

##### 1. Signal Types & Specialized APIs
```clojure
;; Official Telemere - specialized signal types
(tel/event! ::user-login {:user-id 123})          ; ✅ IMPLEMENTED - ID-based events ⭐ NEW
(tel/trace! (expensive-operation))                ; ❌ Missing - Runtime tracing
(tel/spy! :debug (calculate-result))              ; ❌ Missing - Form result capture
(tel/catch->error! (risky-operation))             ; ❌ Missing - Exception catching

;; Telemere-lite - enhanced coverage
(tel/event! ::user-login {:user-id 123})          ; ✅ Have - full event correlation ⭐ NEW
(tel/with-timing "operation" (expensive-operation)) ; ✅ Have - manual timing
(tel/error! (try (risky-operation) (catch Exception e e))) ; ✅ Have - manual catching
```

**Impact**: ⭐ **Event correlation now available!** Missing only automatic tracing and result capture.

##### 2. Advanced Filtering & Transformation
```clojure
;; Official Telemere - advanced filtering
(tel/set-id-filter! {:allow #{::important-events}})     ; ✅ IMPLEMENTED ⭐ NEW
(tel/set-middleware! transform-fn)                       ; ❌ Missing
(tel/set-rate-limit! {:rate-limit-by :user-id})        ; ❌ Missing
(tel/signal-allowed? signal)                            ; ❌ Missing

;; Telemere-lite - enhanced filtering
(tel/set-ns-filter! {:allow #{\"myapp.*\"}})           ; ✅ Have - namespace patterns
(tel/set-id-filter! {:allow #{\"::sente/*\"}})         ; ✅ Have - event ID patterns ⭐ NEW
(tel/set-min-level! :warn)                             ; ✅ Have - level filtering
```

**Impact**: ⭐ **Event ID filtering now available!** Missing only transform functions and rate limiting.

##### 3. Performance & Async Features ⭐ UPDATED
```clojure
;; Official Telemere - performance optimized
;; - Async handler dispatch with back-pressure
;; - Compile-time signal filtering
;; - Up to 4.2M filtered signals/sec
;; - Built-in sampling and rate limiting

;; Telemere-lite - ✅ async handlers implemented
;; - ✅ Async handler dispatch with back-pressure (v0.7.0)
;; - ✅ Pre-compiled regex filtering (v0.7.0)
;; - Runtime filtering (no compile-time macros)
;; - No automatic sampling/throttling (custom handlers available)
;; - Lightweight approach
```

**Impact**: ⭐ **Significantly reduced** - async handlers now available! Remaining gap: no built-in sampling/rate limiting.

#### 🟡 **Medium Priority Missing Features**

##### 4. Handler Ecosystem
```clojure
;; Official Telemere - rich handler ecosystem
(tel/add-handler! :opentelemetry (otel-handler config))  ; ❌ Missing
(tel/add-handler! :slack (slack-handler webhook))        ; ❌ Missing
(tel/add-handler! :email (email-handler config))         ; ❌ Missing
(tel/add-handler! :tcp (tcp-handler host port))          ; ❌ Missing
(tel/add-handler! :udp (udp-handler host port))          ; ❌ Missing

;; Telemere-lite - basic handlers only
(tel/add-file-handler! "app.log")                       ; ✅ Have
(tel/add-stdout-handler!)                               ; ✅ Have
(tel/add-stderr-handler!)                               ; ✅ Have
```

**Impact**: Manual integration required for external systems.

##### 5. Signal Metadata & Context
```clojure
;; Official Telemere - rich signal metadata
;; - Unique signal IDs for correlation
;; - Tracing context and span correlation
;; - Host information and environment data
;; - Rate limiting and sampling metadata

;; Telemere-lite - basic metadata
;; - Timestamp and source location
;; - Namespace and level information
;; - Custom data fields
```

**Impact**: Limited observability correlation and debugging context.

### Priority Assessment for Enhancement

#### **Phase 1: ✅ ENHANCED - Perfect for Sente-lite** ⭐ UPDATED
Our implementation now covers the **essential 90%** needed:
- ✅ Structured logging with rich context
- ✅ Multi-destination routing
- ✅ Comprehensive filtering (level, namespace, event-id)
- ✅ Exception handling and serialization
- ✅ Performance timing utilities
- ✅ **Event correlation with IDs** ⭐ NEW
- ✅ **Event ID filtering** ⭐ NEW

#### **Phase 2: Remaining Enhancements** ⭐ UPDATED
1. ~~**`event!` macro with IDs**~~ ✅ **COMPLETED** ⭐
   ```clojure
   (tel/event! ::ws-message-sent {:conn-id "abc" :msg-type :ping})  ; ✅ Available now!
   ```

2. ~~**Async handlers**~~ ✅ **COMPLETED** (v0.7.0) ⭐
   ```clojure
   (tel/add-handler! :async-file
     (fn [signal] (write-to-file signal))
     {:async {:mode :blocking :buffer-size 1000}})  ; ✅ Available now!
   ```

3. **`trace!` macro** - Automatic performance monitoring
   ```clojure
   (tel/trace! :database-query
     (jdbc/query db "SELECT * FROM users"))
   ```

4. **Basic sampling** - Production performance optimization
   ```clojure
   (tel/set-sampling! 0.1)  ; 10% sampling for high-volume events
   ```

#### **Phase 3+: Advanced Features (Optional)**
- Transform functions for signal modification
- Complex rate limiting strategies
- OpenTelemetry integration
- External notification handlers (Slack, email)

### Migration Strategy

#### **From Telemere-lite to Official Telemere**
When BB supports official Telemere dependencies:
```clojure
;; Step 1: Change require
(require '[taoensso.telemere :as tel])  ; Instead of telemere-lite.core

;; Step 2: Existing code continues to work unchanged
(tel/log! :info "message" data)        ; ✅ Compatible
(tel/error! exception)                 ; ✅ Compatible
(tel/set-min-level! :warn)            ; ✅ Compatible

;; Step 3: Optionally enhance with official features
(tel/event! ::new-feature {:data "value"})     ; New capability
(tel/trace! (performance-critical-code))       ; New capability
```

#### **Enhancement Recommendations** ⭐ UPDATED
1. ~~**Immediate**: Add `event!` macro for better event correlation~~ ✅ **COMPLETED**
2. ~~**Short-term**: Add async handler support for performance~~ ✅ **COMPLETED** (v0.7.0)
3. **Current**: Implement basic sampling for production use
4. **Medium-term**: Add `trace!` macro for automatic performance monitoring
5. **Long-term**: Consider official Telemere migration when available

The gap analysis shows that **telemere-lite now provides excellent coverage** (async handlers ✅, event correlation ✅, regex pre-compilation ✅) with clear upgrade paths for remaining features.

## Future Enhancements ⭐ UPDATED

### Completed Features ✅
- ~~**Enhanced Signal Types**~~: `event!` macro ✅ (event correlation)
- ~~**Advanced Filtering**~~: ID-based filtering ✅, middleware transforms ⏳
- ~~**Performance Features**~~: Async handlers ✅, sampling ⏳, rate limiting ⏳
- ~~**Shutdown Hook**~~: Automatic async handler cleanup ✅

### Planned Features (Phase 2+)
- **Enhanced Signal Types**: `trace!`, `spy!` macros
- **Sampling & Rate Limiting**: Built-in percentage-based filtering
- **Handler Ecosystem**: OpenTelemetry, Slack, email integrations
- **WebSocket Routing**: Browser → Server telemetry pipeline
- **Session-based Logging**: Separate files per browser session

### Migration to Official Telemere

When Babashka and Scittle/SCI officially support Telemere dependencies, migration should be straightforward due to API compatibility:

```clojure
;; Step 1: Change require
(require '[taoensso.telemere :as tel])  ; Instead of telemere-lite.core

;; Step 2: Existing code continues to work
(tel/log! :info "message" data)        ; ✅ Compatible
(tel/error! exception)                  ; ✅ Compatible
(tel/event! ::user-action {:data "x"}) ; ✅ Compatible
(tel/set-min-level! :warn)             ; ✅ Compatible
(tel/add-handler! :id handler-fn)      ; ✅ Compatible

;; Step 3: Optionally enhance with official Telemere features
(tel/trace! (performance-critical-code))       ; New capability
(tel/set-sampling! 0.1)                        ; New capability
```

**We encourage migration** when official Telemere support becomes available. Telemere's full feature set, production hardening, and ongoing development make it the superior long-term choice.

---

## Acknowledgments

**Telemere-lite is built on the shoulders of giants:**

- **[Peter Taoussanis](https://www.taoensso.com/)** for [Telemere](https://github.com/taoensso/telemere), which inspired this library's design, API, and philosophy. His thoughtful approach to telemetry architecture guides our implementation.
- **[Michiel Borkent](https://github.com/borkdude)** for [Babashka](https://babashka.org/), which makes Clojure scripting practical and includes the essential libraries (Timbre, Cheshire) that power telemere-lite.
- **The Clojure community** for fostering an ecosystem where building on each other's work is celebrated.

**Telemere-lite is a bridge solution** - we provide Telemere-inspired telemetry for Babashka and Scittle today, while looking forward to the day when official Telemere support makes this library obsolete. That will be a success, not a failure.

---

**Telemere-lite provides essential telemetry functionality with Telemere API compatibility, making it ideal for Babashka and Scittle applications that need structured logging and flexible routing today, with a clear migration path to official Telemere tomorrow.**