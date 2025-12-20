# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-12-20 (Process Registry doc trimmed)

## CURRENT STATUS

**Last Commit**: `v2.8.0-internal-unification` - Unified handler registry refactor
**Branch**: `main`
**Status**: ✅ Modules Phase 1 complete, Process Registry design in progress

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

### In Progress: Process Registry Design

Created `doc/process-registry.md` (1422 lines - needs trimming)

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
   - **Babashka**: 8/8 tests pass (SCI/Clojure/GraalVM)
   - **Scittle**: 8/8 tests pass (SCI/ClojureScript/Browser) - Playwright verified
   - **nbb**: 8/8 tests pass (SCI/ClojureScript/Node.js)
   - `ensure-atom!` pattern works identically on all three
3. Prototype FQN-based atom-sync (all runtimes support it!)
4. atom-sync Phase 2: Two-way sync with conflict resolution

---

## KEY FILES

```
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
├── process-registry.md                # Design discussion (needs trimming)
└── plan.md                            # Main project plan
```

---

## COMMANDS

```bash
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

### Process Registry Insight
```clojure
;; The "registry" is just Clojure:
(defonce my-resource (atom nil))        ;; stable identity (FQN)
(reset! my-resource new-instance)       ;; swap ephemeral instance
@my-resource                            ;; get current instance
(resolve 'ns/my-resource)               ;; lookup by FQN string
(intern 'ns 'name (atom {}))            ;; dynamic creation
```

No framework needed. Just Clojure.
