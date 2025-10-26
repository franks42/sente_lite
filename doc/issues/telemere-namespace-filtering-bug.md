# Telemere-Lite JSON Serialization Bug (FIXED)

## Status: ✅ RESOLVED

## Issue
Telemetry events from `sente-lite.*` namespaces were not appearing in logs. Initially thought to be a namespace filtering bug, but root cause was JSON serialization failure in file handler.

## Evidence
1. Phase 2 and Phase 3 BB client tests show NO server-side telemetry events
2. Server IS functioning correctly (connections established, stats update)
3. Only `user` and `telemere-lite.core` namespace events appear in logs
4. No `sente-lite.server` events (websocket-opened, websocket-message-received, etc.)

## Impact
- **Cannot verify server-side behavior** in tests
- Server IS working but we're blind to its operation
- Makes debugging server issues impossible

## Root Cause (Found via Diagnostics)
1. **Namespace filtering was working correctly** - `ns-allowed?` passed for sente-lite namespaces
2. **Event-id filtering was working correctly** - `event-id-allowed?` passed for server events
3. **File handler was configured** - Server adds file handler during startup, so Timbre never ran
4. **JSON serialization was failing** - `serialize-for-json` couldn't handle Class objects

When server logged events with Class objects (e.g., `{:server-type (type server)}`), the file handler failed:
```
Error writing to log file: Cannot JSON encode object of class: class java.lang.Class: class clojure.lang.AFunction$1
```

The error was caught silently, so logs were lost without indication.

## Reproduction
```bash
bb test/scripts/bb_client_tests/02_connection_test.bb 2>&1 | grep "sente-lite"
# Returns nothing - no sente-lite namespace logs appear

bb test/scripts/bb_client_tests/02_connection_test.bb 2>&1 | grep "websocket-"
# Returns nothing - no websocket events logged
```

Yet the test PASSES and shows:
```
Server registered the connection
Active connections: 1
```

## Fix (Implemented)
Enhanced `serialize-for-json` in `src/telemere_lite/core.cljc` to handle all non-JSON types:

```clojure
(defn- serialize-for-json
  "Convert objects to JSON-serializable form"
  [obj]
  (cond
    (nil? obj) nil
    (or (string? obj) (number? obj) (boolean? obj)) obj  ; Primitives
    (instance? Throwable obj) {...}                       ; Exceptions
    (instance? Class obj) (str obj)                       ; ✨ NEW: Class objects
    (fn? obj) (str obj)                                   ; ✨ NEW: Functions
    (instance? clojure.lang.IDeref obj) {...}             ; ✨ NEW: Atoms/refs
    (keyword? obj) (str obj)                              ; ✨ NEW: Keywords
    (symbol? obj) (str obj)                               ; ✨ NEW: Symbols
    (map? obj) (into {} (map ...))                        ; Recursive
    (sequential? obj) (mapv ...)                          ; Recursive
    (set? obj) (mapv ... (vec obj))                       ; ✨ NEW: Sets
    :else (str obj)))                                     ; ✨ NEW: Fallback
```

Now handles all Clojure types safely, converting non-JSON values to strings.

## Verification
After fix:
- `sente-lite-server.log` shows server events: websocket-opened, connection-added, etc.
- No more "Cannot JSON encode" errors
- File handler successfully writes all event types
- Phase 2 test shows connection lifecycle events in logs

## Lessons Learned
1. **Silent failures are dangerous** - Handler exceptions should be more visible
2. **Comprehensive serialization is critical** - All Clojure types need handling
3. **Diagnostic logging found the issue** - Added debug output to trace execution
4. **Multiple systems interact** - Namespace filtering + file handler + JSON serialization
