# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-12-20 (FQN Registry implemented)

## CURRENT STATUS

**Last Commit**: `v2.10.0-fqn-registry` - FQN-based registry implementation
**Branch**: `main`
**Status**: ✅ Modules Phase 1 complete, ✅ FQN Registry implemented (19/19 tests)

---

## TODAY'S SESSION (2025-12-20)

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

# Run module tests
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
