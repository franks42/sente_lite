# Telemere-lite: Lightweight Telemetry for Babashka

A lightweight telemetry library that provides structured logging with Telemere-compatible APIs, designed specifically for Babashka and Scittle environments.

## Overview

Telemere-lite is a streamlined implementation of telemetry functionality that:
- **Matches official Telemere API signatures** for easy migration
- **Leverages Babashka's built-in libraries** (Timbre, Cheshire, tools.logging)
- **Supports cross-platform development** (BB server + Scittle browser)
- **Provides AI-visible logging** through structured JSON output
- **Enables flexible routing** to multiple destinations

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
;; Add custom handler
(tel/add-handler! :my-handler
  (fn [signal] (println "Custom:" (:msg signal))))

;; Remove handler
(tel/remove-handler! :my-handler)

;; View all handlers
(tel/get-handlers)

;; Clear all handlers
(tel/clear-handlers!)
```

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

## Limitations

### Compared to Official Telemere

| Feature | Official Telemere | Telemere-lite | Status |
|---------|------------------|---------------|---------|
| **Signal-based API** | ✅ Full | ✅ Compatible | ✅ **Complete** |
| **Level Filtering** | ✅ Full | ✅ Via Timbre | ✅ **Complete** |
| **Namespace Filtering** | ✅ Built-in | ✅ Custom implementation | ✅ **Complete** |
| **ID-based Filtering** | ✅ Built-in | ❌ Not implemented | ⚠️ **Missing** |
| **Transform Functions** | ✅ `(set-xfn! fn)` | ❌ Not implemented | ⚠️ **Missing** |
| **Sampling/Rate Limiting** | ✅ Built-in | ❌ Not implemented | ⚠️ **Missing** |
| **Handler Management** | ✅ Full | ✅ Compatible subset | ✅ **Complete** |
| **File Handlers** | ✅ Advanced | ✅ Basic JSON Lines | ✅ **Complete** |
| **Console Handlers** | ✅ Full | ✅ stdout/stderr | ✅ **Complete** |
| **OpenTelemetry** | ✅ Built-in | ❌ Not implemented | ⚠️ **Missing** |
| **Email/Slack Handlers** | ✅ Built-in | ❌ Not implemented | ⚠️ **Missing** |
| **Async Dispatch** | ✅ Built-in | ❌ Synchronous only | ⚠️ **Missing** |

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
- ❌ **Synchronous only** - No async dispatch
- ❌ **No batching** - Each log is individual I/O
- ❌ **No compression** - Raw JSON output
- ❌ **No rotation** - Manual log file management

### Development Limitations

#### Missing Advanced Features
- **Transform Functions**: No `(set-xfn!)` for signal modification
- **Sampling**: No built-in percentage-based filtering
- **Rate Limiting**: No frequency-based throttling
- **Complex Routing**: No conditional routing based on signal content
- **External Integrations**: No built-in email/Slack/webhooks

#### Workarounds Available
```clojure
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

##### 3. Performance & Async Features
```clojure
;; Official Telemere - performance optimized
;; - Async handler dispatch with back-pressure
;; - Compile-time signal filtering
;; - Up to 4.2M filtered signals/sec
;; - Built-in sampling and rate limiting

;; Telemere-lite - synchronous only
;; - All handlers execute synchronously
;; - Runtime filtering only
;; - No automatic sampling/throttling
;; - Simple, lightweight approach
```

**Impact**: Lower throughput, potential blocking under heavy load.

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

#### **Phase 2: Remaining Enhancements**
1. ~~**`event!` macro with IDs**~~ ✅ **COMPLETED** ⭐
   ```clojure
   (tel/event! ::ws-message-sent {:conn-id "abc" :msg-type :ping})  ; ✅ Available now!
   ```

2. **`trace!` macro** - Automatic performance monitoring
   ```clojure
   (tel/trace! :database-query
     (jdbc/query db "SELECT * FROM users"))
   ```

3. **Basic sampling** - Production performance optimization
   ```clojure
   (tel/set-sampling! 0.1)  ; 10% sampling for high-volume events
   ```

4. **Async handlers** - Non-blocking telemetry processing
   ```clojure
   (tel/add-handler! :async-file (async-file-handler "app.log"))
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

#### **Enhancement Recommendations**
1. **Immediate**: Add `event!` macro for better event correlation
2. **Short-term**: Implement basic sampling for production use
3. **Medium-term**: Add async handler support for performance
4. **Long-term**: Consider official Telemere migration when available

The gap analysis shows that **telemere-lite provides solid foundation coverage** with clear upgrade paths for enhanced functionality as needs evolve.

## Future Enhancements

### Planned Features (Phase 2+)
- **Enhanced Signal Types**: `event!`, `trace!`, `spy!` macros
- **Advanced Filtering**: ID-based filtering, middleware transforms
- **Performance Features**: Async handlers, sampling, rate limiting
- **Handler Ecosystem**: OpenTelemetry, Slack, email integrations
- **WebSocket Routing**: Browser → Server telemetry pipeline
- **Session-based Logging**: Separate files per browser session

### Potential Official Telemere Migration
When Babashka supports official Telemere dependencies:
```clojure
;; Minimal code changes needed due to API compatibility
(require '[taoensso.telemere :as tel])  ; Just change require
;; All existing telemere-lite code continues to work
```

---

**Telemere-lite provides essential telemetry functionality with Telemere API compatibility, making it ideal for Babashka-based applications that need structured logging and flexible routing without the complexity of the full Telemere ecosystem.**