# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-12-22

## CURRENT STATUS

**Last Commit**: `ea82c19` - chore: cleanup stale files and archive old docs
**Branch**: `main`
**Test Suite**: 12 tests passing (`./test/scripts/run_all_tests.bb`)

---

## WHAT'S WORKING

### Core Library (Production-Ready)
- **Server**: `src/sente_lite/server.cljc` - BB/JVM WebSocket server
- **Clients**:
  - `client_bb.clj` - Babashka client
  - `client_scittle.cljs` - Browser/Scittle client
  - `client_nbb.cljs` - Node.js (nbb) client
- **Features**: Auto-reconnect, heartbeat, pub/sub, on!/off! handlers, send queues
- **Registry**: `src/sente_lite/registry.cljc` - FQN-based state registry

### Modules
| Module | Status | Description |
|--------|--------|-------------|
| `modules/nrepl/` | Complete | nREPL-over-sente (74 tests) |
| `modules/log-routing/` | Phase 1 | Remote log routing via pub/sub |
| `modules/config-discovery/` | Complete | Ephemeral port discovery |
| `modules/atom-sync/` | Phase 1 | One-way atom sync |
| `modules/nrepl-nbb/` | Complete | nbb-specific nREPL docs |

### Browser Bundle
- `dist/sente-lite-nrepl.cljs` - 70KB source bundle for Scittle
- Tested with Playwright (4/4 namespaces verified)

---

## TEST SUITE

```bash
# Run all tests (12 tests)
./test/scripts/run_all_tests.bb

# Phase 1: Wire Format
# Phase 2: Server Foundation
# Phase 3: Channel Integration
# Phase 4: nREPL Module (5 test files)
# Phase 5: Browser Bundle
```

---

## KEY FILES

```
src/sente_lite/
├── server.cljc              # WebSocket server
├── client_bb.clj            # Babashka client
├── client_scittle.cljs      # Browser/nbb client
├── registry.cljc            # FQN state registry
├── packer.cljc              # EDN serialization
└── queue_*.clj/cljs         # Send queues

modules/
├── nrepl/                   # nREPL-over-sente
│   ├── src/nrepl_sente/
│   │   ├── server.cljc      # Protocol & eval
│   │   ├── client.clj       # Client API
│   │   ├── proxy.clj        # Bencode proxy
│   │   └── browser_adapter.cljs
│   └── test/                # 5 test files
├── log-routing/             # Remote logging
├── config-discovery/        # Config discovery
├── atom-sync/               # State sync
└── nrepl-nbb/               # nbb nREPL docs

dist/
├── sente-lite-nrepl.cljs    # 70KB browser bundle
├── build-bundle.bb          # Bundle generator
├── test-bundle.mjs          # Playwright test
└── compiled/                # shadow-cljs setup (experimental)

test/scripts/
├── run_all_tests.bb         # Main test runner
├── component/               # Component tests (37 each runtime)
├── registry/                # Registry tests (19 each runtime)
└── multiprocess/            # Multiprocess tests

doc/
├── plan.md                  # Project plan & status
├── api-design.md            # API documentation
├── fqn-registry-api.md      # Registry design
└── archive/                 # Historical docs
```

---

## COMMANDS

```bash
# Full test suite
./test/scripts/run_all_tests.bb

# Individual tests
bb test/scripts/test_wire_formats.bb
bb test/scripts/test_server_foundation.bb
bb test/scripts/test_channel_integration.bb
bb modules/nrepl/test/test_nrepl_bb_to_bb.bb
bb modules/nrepl/test/test_nrepl_ns_persistence.bb

# Browser bundle
cd dist && bb build-bundle.bb
cd dist && bb serve-bundle.bb  # then: node test-bundle.mjs

# Module tests
bb modules/log-routing/test/test_bb_to_bb.bb
bb modules/atom-sync/test/test_one_way.bb
```

---

## CRITICAL REMINDERS

### SCI/Scittle Limitations
```clojure
;; ❌ BROKEN in SCI - vector destructuring
(let [[event-id data] msg] ...)

;; ✅ WORKS in SCI
(let [event-id (first msg)
      data (second msg)] ...)
```

### Scittle Script Loading
External x-scittle files use async XHR. For synchronous execution:
```html
<script src="scittle.js"></script>
<script type="application/x-scittle">
  ;; INLINE code executes synchronously
</script>
<script>scittle.core.eval_script_tags();</script>
```

### Registry API
```clojure
(require '[sente-lite.registry :as reg])

(reg/register! "state/user" {:name "Alice"})
(reg/get-value "state/user")
(reg/set-value! "state/user" {:name "Bob"})
(reg/swap-value! "state/counter" inc)
(reg/watch! "state/user" :key callback)
```

---

## RECENT CHANGES (2025-12-22)

### Cleanup Completed
- Removed 45+ stale test files (~5,500 lines)
- Archived 23 old docs to `doc/archive/`
- Test suite reduced to 12 focused tests

### Browser Bundle Added
- `dist/sente-lite-nrepl.cljs` - 70KB source bundle
- Automated in test suite (Phase 5)
- Local Maven distribution: `clojure -T:build install`

### nREPL Module Complete (74 tests)
- Layer 1: Protocol & Server
- Layer 2: Client API
- Layer 3: Bencode Proxy
- Layer 4: Browser Adapter
