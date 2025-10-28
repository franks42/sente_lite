# BB nREPL + http-kit Diagnosis (2025-10-28)

## Problem
WebSocket server code failed to load via `bb load-bb` (nREPL eval). Execution stopped silently without errors, and the server never started listening on the configured port.

## Investigation Method
Used structured telemetry logging (`log!` calls) to trace exactly where code execution stopped during nREPL evaluation.

## Root Cause
**http-kit's deprecated `with-channel` + separate `on-receive` API causes BB nREPL evaluation to fail silently.**

### Failing Pattern
```clojure
(defn ws-handler [req]
  (http/with-channel req channel
    (http/send! channel "Welcome!")

    ;; This pattern causes nREPL to stop evaluating
    (http/on-receive channel
      (fn [msg]
        (http/send! channel (str "Echo: " msg))))))
```

**Telemetry showed:**
- ✅ Code before `defn ws-handler` executed
- ❌ Code after `defn ws-handler` never executed
- ❌ No error messages in `:ex` or `:err` fields

### Working Pattern
```clojure
(defn ws-handler [req]
  (http/as-channel req
    {:on-open (fn [ch]
                (http/send! ch "Welcome!"))
     :on-receive (fn [ch msg]
                   (http/send! ch (str "Echo: " msg)))
     :on-close (fn [ch status]
                 (println "Closed"))}))
```

**Telemetry showed:**
- ✅ All code executed successfully
- ✅ Server listening on port
- ✅ Bidirectional communication working

## Solution
**Use `http/as-channel` (http-kit v2.4.0+ API) instead of deprecated `with-channel` + `on-receive`.**

The newer API:
- Passes all callbacks in a single options map
- Works correctly with BB nREPL evaluation
- Is the recommended approach (old API deprecated since 2020-07-30)

## Diagnosis Techniques Used
1. **Incremental telemetry**: Added `log!` calls at key points to trace execution
2. **Binary search**: Tested simpler versions to isolate the exact failing construct
3. **API comparison**: Tested old vs new http-kit APIs to find working solution
4. **Port verification**: Used `lsof` to confirm server actually listening

## Files
- **Working**: `examples/ws-telemetry-server-fixed.clj` (uses `as-channel`)
- **Broken**: `examples/ws-telemetry-server.clj` (uses `with-channel` + `on-receive`)

## Impact
This affects any http-kit WebSocket server code loaded via nREPL in Babashka. Use `as-channel` for compatibility.
