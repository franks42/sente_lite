# Phase 2 Migration Plan - Server-Side Logging

**Status:** Planning
**Estimated Duration:** 2-3 days
**Total Calls to Migrate:** 87 server-side logging calls

---

## Files to Migrate

### 1. `src/sente_lite/client_scittle.cljs` (21 calls)
- **Current:** Uses `telemere-lite.core` as `tel`
- **Pattern:** `(tel/log! {:level :debug :id :sente-lite.client/event :data {...}})`
- **Migration:** Replace with `sente-lite.logging` macros
- **Calls:**
  - Lines 56, 67, 72, 84, 96, 107, 122, 137, 154, 183, 188, 196, 225, 240, 260, 267, 274, 284, 291, 324, 330

### 2. `src/sente_lite/server_simple.cljc` (15 calls)
- **Current:** Uses `telemere-lite.core` as `tel`
- **Pattern:** `(tel/log! {...})` and `(tel/error! {...})`
- **Migration:** Replace with `sente-lite.logging` interface
- **Calls:**
  - Lines 30, 40, 55, 62, 74, 80, 86, 93, 107, 117, 135, 139, 149, 150, 155

### 3. `src/sente_lite/channels.cljc` (18 calls)
- **Current:** Uses `telemere-lite.core` as `tel`
- **Pattern:** `(tel/log! {...})`
- **Migration:** Replace with `sente-lite.logging` interface
- **Calls:**
  - Lines 33, 51, 74, 84, 98, 102, 110, 125, 130, 145, 155, 165, 175, 185, 195, 205, 215, 225

### 4. `src/sente_lite/server.cljc` (22 calls)
- **Current:** Uses `telemere-lite.core` as `tel`
- **Pattern:** `(tel/log! {...})` and `(tel/error! {...})`
- **Migration:** Replace with `sente-lite.logging` interface
- **Calls:**
  - Lines 46, 63, 93, 107, 117, 140, 255, 276, 289, 302, 309, 324, 336, 343, 353, 361, 369, 392, 446, 463, 481, 500

### 5. `src/sente_lite/wire_format.cljc` (8 calls)
- **Current:** Uses `telemere-lite.core` as `tel`
- **Pattern:** `(tel/error! {...})`
- **Migration:** Replace with `sente-lite.logging` interface
- **Calls:**
  - Lines 41, 51, 71, 80, 103, 114, 135, 145

### 6. `src/sente_lite/wire_multiplexer.cljc` (3 calls)
- **Current:** Uses `telemere-lite.core` as `tel`
- **Pattern:** `(tel/error! {...})`
- **Migration:** Replace with `sente-lite.logging` interface
- **Calls:**
  - Lines 56, 66, 73

---

## Migration Mapping

### Old Pattern → New Pattern

#### Simple Log Calls
```clojure
;; OLD
(tel/log! {:level :debug :id :sente-lite.server/event :data {...}})

;; NEW
(log/debug :sente-lite.server/event {...})
```

#### Error Calls
```clojure
;; OLD
(tel/error! {:id :sente-lite.server/error :error ex :data {...}})

;; NEW
(log/error :sente-lite.server/error {:error ex ...})
```

#### All Log Levels
```clojure
;; OLD
(tel/log! {:level :trace :id :event :data {...}})
(tel/log! {:level :debug :id :event :data {...}})
(tel/log! {:level :info :id :event :data {...}})
(tel/log! {:level :warn :id :event :data {...}})
(tel/log! {:level :error :id :event :data {...}})

;; NEW
(log/trace :event {...})
(log/debug :event {...})
(log/info :event {...})
(log/warn :event {...})
(log/error :event {...})
```

---

## Migration Steps

### Step 1: Update Imports
Replace:
```clojure
(:require [telemere-lite.core :as tel])
```

With:
```clojure
(:require [sente-lite.logging :as log])
```

### Step 2: Migrate Logging Calls

**For each file:**
1. Update require statement
2. Replace all `tel/log!` calls with appropriate `log/` macro
3. Replace all `tel/error!` calls with `log/error` macro
4. Verify data structure matches new format

### Step 3: Verify and Test
1. Run linting: `clj-kondo --lint src test`
2. Run formatting: `cljfmt check src test`
3. Run tests: `bb run_tests.bb`
4. Verify no regressions

---

## Implementation Order

1. **Phase 2a:** Migrate `wire_format.cljc` (8 calls - simplest)
2. **Phase 2b:** Migrate `wire_multiplexer.cljc` (3 calls)
3. **Phase 2c:** Migrate `channels.cljc` (18 calls)
4. **Phase 2d:** Migrate `server_simple.cljc` (15 calls)
5. **Phase 2e:** Migrate `server.cljc` (22 calls - most complex)
6. **Phase 2f:** Migrate `client_scittle.cljs` (21 calls - browser code)

---

## Success Criteria

- ✅ All 87 server logging calls migrated
- ✅ All tests passing
- ✅ 0 linting errors
- ✅ 0 formatting issues
- ✅ No breaking changes
- ✅ Backward compatible

---

## Rollback Plan

If issues arise:
```bash
git checkout v0.7.0-phase1-complete
```

---

## Notes

- Keep telemere-lite.core in place during Phase 2 (will remove in Phase 4)
- All logging calls follow same pattern: `(log/level :event-id data)`
- Event IDs remain unchanged (e.g., `:sente-lite.server/event`)
- Data structure remains the same (maps with metadata)

---

## Ready to Begin?

Start with Phase 2a: Migrate `wire_format.cljc` (simplest file, 8 calls)
