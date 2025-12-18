# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-12-18 (Scittle Browser Testing Complete)

## CURRENT STATUS

**Last Commit**: `v2.2.2` - Canonical wire format + legacy archival + naming cleanup
**Tag**: `v2.2.2`
**Branch**: `main`
**Status**: ✅ **SCITTLE BROWSER TESTING COMPLETE**

## WHAT'S WORKING

All cross-platform tests pass (7 total):
```
[PASS] BB Server <-> BB Client (unit test)
[PASS] BB Server <-> BB Client (multiprocess)
[PASS] nbb Server <-> nbb Client
[PASS] BB Server <-> nbb Client
[PASS] nbb Server <-> BB Client
[PASS] BB Server <-> Scittle Client (browser) ← NEW
[PASS] Sente Server <-> BB Client
```

### Platform Matrix

| Platform | Server | Client | Status |
|----------|--------|--------|--------|
| BB | `server.cljc` | `client_bb.clj` | ✅ Working |
| nbb | `server_nbb.cljs` | `client_scittle.cljs` | ✅ Working |
| Browser | N/A | `client_scittle.cljs` | ✅ **WORKING** |

### Key Source Files

```
src/sente_lite/
├── server.cljc           # BB/JVM server (http-kit)
├── server_nbb.cljs       # nbb server (ws package)
├── client_bb.clj         # BB client
├── client_scittle.cljs   # Browser/nbb client ✅ TESTED IN BROWSER
├── channels.cljc         # Pub/sub channel management
├── wire_format.cljc      # Sente-compatible wire format (SCI-compatible)
└── serialization.cljc    # Pluggable serialization (EDN/JSON/Transit)
```

---

## CHANGES MADE FOR SCITTLE/SCI COMPATIBILITY

### 1. Fixed Trove Macro Usage
SCI/Scittle requires macros to be referred directly, not namespace-qualified.

**Before:** `(trove/log! {...})`
**After:** `(log! {...})` with `(:require [taoensso.trove :refer [log!]])`

Files changed:
- `src/sente_lite/wire_format.cljc`
- `src/sente_lite/client_scittle.cljs`

### 2. Fixed Vector Destructuring
SCI does NOT support vector destructuring in let bindings or function params.

**Before:**
```clojure
(let [[uid csrf-token] data] ...)
```

**After:**
```clojure
{:uid (first data)
 :csrf-token (second data)}
```

File changed: `src/sente_lite/wire_format.cljc`

### 3. Fixed cljs.reader Import
Scittle doesn't have `cljs.reader` - use `read-string` directly.

**Before:**
```clojure
#?(:cljs [cljs.reader :as reader])
...
(reader/read-string raw-message)
```

**After:**
```clojure
;; No cljs.reader import needed
#_{:clj-kondo/ignore [:unresolved-symbol]}
(read-string raw-message)
```

### 4. Fixed Scittle Version
Scittle 0.6.21 has ES6 module issues. Use 0.7.28.

---

## NEW TEST INFRASTRUCTURE

### Browser Test Files
```
dev/scittle-demo/
├── test-client-scittle.html       # Browser test page (16 tests)
├── playwright-client-test.mjs     # Automated Playwright test
├── test-server.bb                 # Simple BB server for testing
└── test-wire-format.html          # Updated to Scittle 0.7.28
```

### Running Browser Tests

**Manual (for debugging):**
```bash
# Terminal 1: Start server
cd dev/scittle-demo
bb test-server.bb

# Terminal 2: Start static server
bb static-server.bb

# Terminal 3: Open browser
open http://localhost:8080/test-client-scittle.html
```

**Automated (via Playwright):**
```bash
cd dev/scittle-demo
node playwright-client-test.mjs
```

**Full test suite:**
```bash
bb test/scripts/cross_platform/run_all_cross_platform_tests.bb
```

---

## CRITICAL REMINDERS

### SCI/Scittle Limitations
```clojure
;; ❌ BROKEN in SCI - vector destructuring
(let [[event-id data] msg] ...)

;; ✅ WORKS in SCI
(let [event-id (first msg)
      data (second msg)]
  ...)

;; ❌ BROKEN in SCI - namespace-qualified macros
(trove/log! {...})

;; ✅ WORKS in SCI - referred macros
(log! {...})  ; with :refer [log!]
```

### Scittle Version
Use `scittle@0.7.28`, not `0.6.21` (has ES6 module issues)

### BB WebSocket CharBuffer
```clojure
;; BB websocket passes HeapCharBuffer, not String
;; Always convert: (str raw-data) before parsing
```

### Wire Format
```clojure
;; Events are vectors, not maps
[:event-id {:data "here"}]

;; NOT the old v1 format
{:type :event-id :data "here"}  ; ❌ OLD
```

---

## COMMANDS REFERENCE

```bash
# Run all tests
bb run_tests.bb

# Run cross-platform tests (includes Scittle browser)
bb test/scripts/cross_platform/run_all_cross_platform_tests.bb

# Run Scittle browser test only
cd dev/scittle-demo && node playwright-client-test.mjs

# Run specific tests
bb test/scripts/test_client_bb.bb
bb test/scripts/multiprocess/01_basic.bb

# nbb tests
cd test/nbb && nbb --classpath ../../src test_server_nbb_module.cljs
```

---

## NEXT STEPS (Optional)

1. **Commit changes** - All Scittle browser work is complete and tested
2. **Tag v2.1.0** - If you want to mark Scittle browser support
3. **nbb Server <-> Scittle Client** - Could add this cross-platform test
4. **Documentation** - Update README with Scittle usage examples
