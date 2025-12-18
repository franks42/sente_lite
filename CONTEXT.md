# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-12-18 (Handoff for Scittle Work)

## CURRENT STATUS

**Last Commit**: `cbbc8a9` - "Add nbb tests to run_tests.bb + comprehensive README"
**Tag**: `v2.0.0` - Full v2 release, all platforms working
**Branch**: `main` - clean, pushed to origin
**Next Task**: **Test client_scittle.cljs in actual browser (Scittle/SCI)**

## WHAT'S WORKING (v2.0.0)

All cross-platform tests pass:
```
[PASS] BB Server <-> BB Client
[PASS] nbb Server <-> nbb Client  
[PASS] BB Server <-> nbb Client
[PASS] nbb Server <-> BB Client
[PASS] Sente Server <-> BB Client
```

### Platform Matrix

| Platform | Server | Client | Status |
|----------|--------|--------|--------|
| BB | `server.cljc` | `client_bb.clj` | ✅ Working |
| nbb | `server_nbb.cljs` | `client_scittle.cljs` | ✅ Working |
| Browser | N/A | `client_scittle.cljs` | ⚠️ **NEEDS TESTING** |

### Key Source Files

```
src/sente_lite/
├── server.cljc           # BB/JVM server (http-kit)
├── server_nbb.cljs       # nbb server (ws package)
├── client_bb.clj         # BB client
├── client_scittle.cljs   # Browser/nbb client ← TEST THIS IN BROWSER
├── wire_format_v2.cljc   # Sente-compatible v2 format
├── channels.cljc         # Pub/sub channel management
└── wire_format.cljc      # Serialization (EDN/JSON/Transit)
```

---

## NEXT TASK: Scittle Browser Testing

### Goal
Verify `client_scittle.cljs` works correctly when loaded via Scittle (SCI) in a real browser, connecting to a BB server.

### Why This Matters
- nbb tests pass because nbb uses full ClojureScript, not SCI
- SCI has limitations (especially destructuring) that can cause silent failures
- Browser is a critical target platform

### Detailed TODO

#### 1. Verify client_scittle.cljs Has No SCI Issues
**Check the file for forbidden patterns:**

❌ **FORBIDDEN in SCI:**
```clojure
;; Vector destructuring in function params
(defn foo [[a b]] ...)

;; Vector destructuring in let
(let [[x y] some-vec] ...)
```

✅ **REQUIRED pattern:**
```clojure
(defn foo [v]
  (let [a (first v)
        b (second v)]
    ...))
```

**File to check:** `src/sente_lite/client_scittle.cljs`

#### 2. Create Browser Test HTML

Create `dev/scittle-demo/test-client-scittle-v2.html`:
- Load Scittle
- Load Trove (vendored at `dev/scittle-demo/taoensso/trove.cljs`)
- Load `client_scittle.cljs`
- Connect to BB server on port 1345
- Run tests: handshake, echo, subscribe, publish, channel-msg
- Display pass/fail results

**Reference:** `dev/scittle-demo/test-wire-format-v2.html` shows how to load .cljc files in Scittle

#### 3. Create Playwright Automated Test

Create `dev/scittle-demo/playwright-client-test.mjs`:
```javascript
// 1. Start BB server (port 1345)
// 2. Start static file server for HTML
// 3. Launch Playwright browser
// 4. Navigate to test page
// 5. Capture console output
// 6. Wait for tests to complete
// 7. Check pass/fail, exit accordingly
```

**Existing scripts to reference:**
- `dev/scittle-demo/playwright-interactive.mjs`
- `dev/scittle-demo/playwright-test.mjs`

#### 4. Add to Cross-Platform Test Runner

Update `test/scripts/cross_platform/run_all_cross_platform_tests.bb` to include:
- BB Server <-> Scittle Client (browser)

---

## TROVE-SCITTLE FORK (IMPORTANT!)

For Scittle/SCI compatibility, use the **trove-scittle fork** instead of vendored files:

**Repository:** https://github.com/franks42/trove-scittle (branch: `scittle`)
**Tag:** `v1.1.0-scittle`

**CDN URLs (use these in Scittle HTML):**
```html
<script src="https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/utils.cljc" type="application/x-scittle"></script>
<script src="https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove/console.cljc" type="application/x-scittle"></script>
<script src="https://cdn.jsdelivr.net/gh/franks42/trove-scittle@v1.1.0-scittle/src/taoensso/trove.cljc" type="application/x-scittle"></script>
```

**Usage in Scittle code:**
```clojure
(ns my-app
  (:require [taoensso.trove :as trove :refer [log!]]))

;; IMPORTANT: SCI requires macros with :refer - namespace-qualified calls don't work!
(log! {:level :info :id :my-app/started :data {:foo "bar"}})
```

**Why the fork?** SCI doesn't expose `cljs.core/Cons`, so the upstream Trove fails. The fork adds a `:scittle` reader conditional workaround.

---

## INFRASTRUCTURE AVAILABLE

### dev/scittle-demo/
```
dev/scittle-demo/
├── taoensso/trove.cljs          # OLD vendored Trove - USE CDN INSTEAD
├── test-wire-format-v2.html     # Example: loading .cljc in Scittle
├── playwright-interactive.mjs   # Existing Playwright script
├── static-server.bb             # Static file server
├── examples/
│   ├── sente-pubsub-demo-client.cljs   # Demo (uses OLD v1 format)
│   └── sente-heartbeat-demo-client.cljs # Demo (uses OLD v1 format)
└── package.json                 # Node deps (playwright)
```

### How to Start Dev Environment

```bash
# Terminal 1: Start BB server
cd dev/scittle-demo
bb examples/sente-heartbeat-demo-server.clj  # Or write new v2 server

# Terminal 2: Start static file server
cd dev/scittle-demo
bb static-server.bb

# Terminal 3: Run Playwright
cd dev/scittle-demo
node playwright-interactive.mjs
```

---

## CRITICAL REMINDERS

### SCI/Scittle Destructuring Bug
```clojure
;; ❌ BROKEN in SCI - causes "nth not supported on this type function"
(let [[event-id data] msg] ...)

;; ✅ WORKS in SCI
(let [event-id (first msg)
      data (second msg)]
  ...)
```

### BB WebSocket CharBuffer
```clojure
;; BB websocket passes HeapCharBuffer, not String
;; Always convert: (str raw-data) before parsing
```

### v2 Wire Format
```clojure
;; Events are vectors, not maps
[:event-id {:data "here"}]

;; NOT the old v1 format
{:type :event-id :data "here"}  ; ❌ OLD
```

---

## GIT STATE

```
cbbc8a9 Add nbb tests to run_tests.bb + comprehensive README (TAG: v2.0.0)
392adb8 Update CONTEXT.md with cross-platform test status
2facaf8 Fix cross-platform test paths + Sente compat CharBuffer fix
25f8bd4 v2 multiprocess tests + cross-platform tests + client_bb.clj reconnect fix
44478f6 nbb platform support: server + client modules (TAG: v2.0.0-nbb)
```

---

## COMMANDS REFERENCE

```bash
# Run all tests
bb run_tests.bb

# Run cross-platform tests
bb test/scripts/cross_platform/run_all_cross_platform_tests.bb

# Run specific v2 tests
bb test/scripts/test_v2_client_bb.bb
bb test/scripts/multiprocess_v2/01_basic_v2.bb

# nbb tests
cd test/nbb && nbb --classpath ../../src test_server_nbb_module.cljs
```

---

## PREVIOUS THREADS

- T-019b3056-1620-724e-a420-32a7f6248ab5 (this thread - cross-platform fixes)
- T-019b3036-deeb-7555-8b98-778f6b057782 (multiprocess v2 tests)
- T-019b300e-da2a-716e-ad2e-b5bcffa01288 (v2 wire format migration)
