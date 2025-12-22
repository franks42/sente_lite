# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-12-22 (Pure CLJS browser adapter - Phase 4 complete)

## CURRENT STATUS

**Last Commit**: `2365866` - feat: add pure CLJS browser adapter for nREPL-over-sente (Phase 4)
**Tag**: `v0.4.0-browser-adapter`
**Branch**: `main`
**Status**: ✅ nREPL-over-sente module Phases 1-4 complete

---

## TODAY'S SESSION (2025-12-22)

### Completed: Pure CLJS Browser Adapter (Phase 4)

**Major milestone**: Converted JavaScript FakeWebSocket to pure ClojureScript!

**The Challenge**: Scittle CLJS scripts are queued and evaluated AFTER all synchronous scripts load. This meant our CLJS FakeWebSocket was created AFTER `scittle.nrepl.js` - too late!

**Solution Found**: Use `scittle.core.eval_script_tags()` to force immediate evaluation:

```html
<script src="scittle.js"></script>
<script type="application/x-scittle">
  ;; FakeWebSocket code INLINE here (not external file!)
</script>
<script>scittle.core.eval_script_tags();</script>  <!-- Force eval NOW! -->
<script src="scittle.nrepl.js"></script>           <!-- Finds ws_nrepl ready -->
```

**Key insight**: External files (with `src` attribute) use async XHR even with `eval_script_tags()`. Only INLINE scripts execute synchronously.

**Files created**:
- `modules/nrepl/src/nrepl_sente/browser_adapter.cljs` - Scittle integration
- `modules/nrepl/src/nrepl_sente/fake_websocket.cljs` - Reference (use inline)
- `modules/nrepl/src/nrepl_sente/browser_adapter.js` - Deprecated JS version
- `modules/nrepl/test/test_browser_adapter.bb` - Playwright integration test
- `modules/nrepl/test/test_browser_adapter.html` - Working example

**Test results** (all pass with state persistence):
- `(+ 1 2 3)` → `"6"` ✓
- `(def x 42)` → `"#'user/x"` ✓
- `(* x 2)` → `"84"` ✓ (x=42 persisted!)
- `(+ x y)` → `"52"` ✓

**Benefits**:
1. **100% Clojure/Script** - No JavaScript files needed
2. **Zero changes to scittle.nrepl.js** - No forking upstream
3. **Reuses proven code** - sci.nrepl is battle-tested
4. **Clean separation** - FakeWebSocket and adapter are pure CLJS

---

## PREVIOUS SESSION (2025-12-21)

### Completed: nREPL Module (Phases 1-3)

Location: `modules/nrepl/`

**Layer 1 - Protocol & Server** (`src/nrepl_sente/server.cljc`):
- EDN message format for nREPL ops (eval, load-file, describe, clone)
- Platform-specific eval: BB uses `binding [*ns* ...] + reader + eval`, Scittle uses `scittle.core/eval_string`
- Namespace persistence fixed using `!last-ns` atom pattern
- 22 tests passing

**Layer 2 - Client API** (`src/nrepl_sente/client.clj`):
- Registry-based connection discovery
- eval!, load-file!, eval-latest!, load-file-latest! API
- 24 tests passing

**Layer 3 - Bencode Proxy** (`src/nrepl_sente/proxy.clj`):
- Bridges editors/nREPL-MCP to sente-lite peers
- 19 tests passing

**Namespace Persistence Tests**: 9 tests passing

**Total: 74+ tests**

### Investigated: sci.nrepl Cannot Load in Scittle via CDN

sci.nrepl uses internal SCI APIs (`sci/eval-string*`, `sci/binding`, etc.) that Scittle doesn't expose. Our implementation correctly uses `scittle.core/eval_string`.

---

## PREVIOUS SESSION (2025-12-20)

### Completed: Modules Phase 1

Created `modules/` directory with two working modules:

1. **log-routing** - Route Trove logs via sente-lite pub/sub
   - `modules/log-routing/src/log_routing/sender.cljc`
   - `modules/log-routing/src/log_routing/receiver.cljc`
   - Test: `bb modules/log-routing/test/test_bb_to_bb.bb` ✅ 3 logs received

2. **atom-sync** - One-way atom sync across processes
   - `modules/atom-sync/src/atom_sync/publisher.cljc`
   - `modules/atom-sync/src/atom_sync/subscriber.cljc`
   - Test: `bb modules/atom-sync/test/test_one_way.bb` ✅ 3 changes synced

### Completed: FQN Registry Implementation

Implemented `src/sente_lite/registry.cljc` with full API:

```clojure
;; Registration
(register! "state/user-prefs" {:theme "dark"})
(ensure! "state/counter")

;; Read/Write
(get-value "state/user-prefs")
(set-value! "state/user-prefs" new-value)
(swap-value! "state/counter" inc)

;; Hot paths (cached ref)
(let [ref (get-ref "state/counter")]
  (swap-ref! ref inc))

;; Discovery & Cleanup
(registered? "state/user-prefs")
(list-registered)
(unregister! "state/user-prefs")
(unregister-prefix! "sync/")

;; Reactive
(watch! "state/counter" :my-watch callback)
(unwatch! "state/counter" :my-watch)
```

**Tested on all runtimes:**
- Babashka: 19/19 ✓
- Scittle: 19/19 ✓
- nbb: 19/19 ✓

**KEY CONCLUSIONS:**

1. **No separate registry needed** - use Clojure primitives:
   ```clojure
   Var (defonce) = stable identity (FQN)
   Atom          = mutable container
   @/reset!      = get/set ephemeral instance
   ```

2. **SPKI/SDSI insight** - Local naming eliminates global naming authority:
   - Global identity = UUID (self-generated)
   - Local names = your own mappings
   - Within trusted world, no signing/auth needed

3. **Three layers**: App-Type (schema) → Identity (which one) → Instance (current GUID)

4. **FQN vars eliminate registry**:
   - Same FQN on both sides = same logical resource
   - `resolve`/`intern` for lookup/creation
   - Wire protocol carries FQN string
   - Atom provides mutable indirection for ephemeral instances

5. **Flexible atom value shapes**: map, vector, whatever needed

### Prior Art Researched
- Erlang gproc: structured keys
- Akka: path ≠ reference (exactly our identity ≠ instance!)
- NATS: hierarchical subjects, request-reply
- DNS-SD: type in name, metadata separate
- SPKI/SDSI: local naming, global identity

---

## NEXT STEPS

1. ~~Trim `doc/process-registry.md` to be concise~~ ✅ Done (1423→430 lines)
2. ~~Verify `intern`/`resolve` work across all runtimes~~ ✅ Done (2025-12-20)
3. ~~Implement FQN Registry~~ ✅ Done (v2.10.0-fqn-registry)
4. Update atom-sync module to use registry API
5. atom-sync Phase 2: Two-way sync with conflict resolution

---

## KEY FILES

```
src/sente_lite/
└── registry.cljc                      # FQN Registry ✅ (v2.10.0)

modules/
├── modules-plan.md                    # Overview
├── nrepl/                             # nREPL-over-sente ✅ (v0.4.0)
│   ├── nrepl-sente-design.md          # Architecture & decisions
│   ├── src/nrepl_sente/
│   │   ├── server.cljc                # Layer 1: Protocol & Server (22 tests)
│   │   ├── client.clj                 # Layer 2: Client API (24 tests)
│   │   ├── proxy.clj                  # Layer 3: Bencode Proxy (19 tests)
│   │   ├── browser_adapter.cljs       # Layer 4: Scittle integration
│   │   ├── fake_websocket.cljs        # Reference (use INLINE in HTML!)
│   │   └── browser_adapter.js         # DEPRECATED - use CLJS instead
│   └── test/
│       ├── test_browser_adapter.html  # Working example with inline CLJS
│       ├── test_browser_adapter.bb    # Playwright integration test
│       ├── test_nrepl_bb_to_bb.bb     # Server tests
│       ├── test_nrepl_client_api.bb   # Client API tests
│       ├── test_nrepl_proxy.bb        # Proxy tests
│       └── test_nrepl_ns_persistence.bb # Namespace persistence tests
├── log-routing/                       # Phase 1 Complete ✅
│   ├── README.md
│   ├── src/log_routing/sender.cljc
│   ├── src/log_routing/receiver.cljc
│   └── test/test_bb_to_bb.bb
└── atom-sync/                         # Phase 1 Complete ✅
    ├── README.md
    ├── src/atom_sync/publisher.cljc
    ├── src/atom_sync/subscriber.cljc
    └── test/test_one_way.bb

doc/
├── fqn-registry-api.md                # Registry API design
├── process-registry.md                # Design discussion
└── plan.md                            # Main project plan

test/scripts/registry/
├── test_registry_bb.bb                # Babashka tests (19/19)
├── test_registry_scittle.html         # Scittle tests (19/19)
├── test_registry_nbb.cljs             # nbb tests (19/19)
└── test_registry_playwright.mjs       # Playwright runner
```

---

## COMMANDS

```bash
# Run registry tests
bb test/scripts/registry/test_registry_bb.bb
nbb test/scripts/registry/test_registry_nbb.cljs
node test/scripts/registry/test_registry_playwright.mjs

# Run nREPL module tests
bb modules/nrepl/test/test_nrepl_bb_to_bb.bb
bb modules/nrepl/test/test_nrepl_client_api.bb
bb modules/nrepl/test/test_nrepl_proxy.bb
bb modules/nrepl/test/test_nrepl_ns_persistence.bb
bb modules/nrepl/test/test_browser_adapter.bb  # Requires server + Playwright

# Run other module tests
bb modules/log-routing/test/test_bb_to_bb.bb
bb modules/atom-sync/test/test_one_way.bb

# Run full test suite
bb run_tests.bb
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

### Scittle Script Loading Order (eval_script_tags)
External x-scittle files use async XHR - order not guaranteed!
Use INLINE scripts + `eval_script_tags()` for synchronous execution:

```html
<script src="scittle.js"></script>
<script type="application/x-scittle">
  ;; Code that MUST run first - INLINE only!
  (aset js/window "my_thing" (create-thing))
</script>
<script>scittle.core.eval_script_tags();</script>  <!-- Force NOW -->
<script src="lib-that-expects-my_thing.js"></script>
```

### FQN Registry API
```clojure
(require '[sente-lite.registry :as reg])

(reg/register! "state/user" {:name "Alice"})   ;; create with value
(reg/get-value "state/user")                    ;; read value
(reg/set-value! "state/user" {:name "Bob"})    ;; update value
(reg/swap-value! "state/counter" inc)          ;; atomic update
(reg/watch! "state/user" :key callback)        ;; reactive
(reg/unregister-prefix! "sync/")               ;; cleanup
```

Names are relative (`state/user`), internal FQNs hidden.
