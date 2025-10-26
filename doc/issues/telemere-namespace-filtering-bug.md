# Telemere-Lite Namespace Filtering Bug

## Issue
Telemetry events from `sente-lite.*` namespaces are being filtered out and not appearing in logs, even though the default filter is `{:allow #{"*"} :deny #{}}` which should allow all namespaces.

## Evidence
1. Phase 2 and Phase 3 BB client tests show NO server-side telemetry events
2. Server IS functioning correctly (connections established, stats update)
3. Only `user` and `telemere-lite.core` namespace events appear in logs
4. No `sente-lite.server` events (websocket-opened, websocket-message-received, etc.)

## Impact
- **Cannot verify server-side behavior** in tests
- Server IS working but we're blind to its operation
- Makes debugging server issues impossible

## Root Cause (Suspected)
Namespace filtering logic in `src/telemere_lite/core.cljc` lines 68-74:

```clojure
(defn- ns-allowed?
  "Check if namespace is allowed by current filter"
  [ns-str]
  (let [{:keys [allow deny]} *ns-filter*
        denied? (some #(re-matches (re-pattern (clojure.string/replace % "*" ".*")) ns-str) deny)
        allowed? (some #(re-matches (re-pattern (clojure.string/replace % "*" ".*")) ns-str) allow)]
    (and (not denied?) allowed?)))
```

The pattern "*" is converted to ".*" regex, then used with `re-matches`. This should work, but clearly doesn't for `sente-lite.*` namespaces.

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

## Workaround
None currently. Tests must rely on `server/get-server-stats` instead of telemetry to verify server behavior.

## Priority
**HIGH** - Blocks observability for entire server implementation

## Next Steps
1. Debug regex matching logic in `ns-allowed?`
2. Add test cases for namespace filtering
3. Consider simpler filtering implementation (string prefix matching?)
4. Ensure all namespaces can log by default
