# Logging Refactoring Plan

**Objective:** Replace all level-specific logging macros with single `log/log!` function calls.

**Status:** Planning phase

---

## Summary

Migrate from:
```clojure
(log/trace {:level :debug :id :event :data {...}})
(log/debug {:id :event :data {...}})
(log/info {:id :event :data {...}})
```

To:
```clojure
(log/log! {:level :debug :id :event :data {...}})
(log/log! {:level :debug :id :event :data {...}})
(log/log! {:level :info :id :event :data {...}})
```

Then remove the convenience macros entirely.

---

## Files to Refactor (in order)

| File | Calls | Status |
|------|-------|--------|
| server.cljc | 29 | pending |
| client_scittle.cljs | 21 | pending |
| channels.cljc | 15 | pending |
| server_simple.cljc | 13 | pending |
| wire_format.cljc | 9 | pending |
| wire_multiplexer.cljc | 9 | pending |
| logging/bb.cljc | 1 | pending |

**Total calls to refactor:** 97

---

## Refactoring Strategy

### Per-file approach:

1. Open file
2. Find all `log/trace`, `log/debug`, `log/info`, `log/warn`, `log/error`, `log/fatal` calls
3. Replace with `log/log!` and add explicit `:level` parameter
4. Verify syntax
5. Move to next file

### Pattern replacements:

```clojure
;; trace
(log/trace {:id :event :data {...}})
→ (log/log! {:level :trace :id :event :data {...}})

;; debug
(log/debug {:id :event :data {...}})
→ (log/log! {:level :debug :id :event :data {...}})

;; info
(log/info {:id :event :data {...}})
→ (log/log! {:level :info :id :event :data {...}})

;; warn
(log/warn {:id :event :data {...}})
→ (log/log! {:level :warn :id :event :data {...}})

;; error
(log/error {:id :event :data {...}})
→ (log/log! {:level :error :id :event :data {...}})

;; fatal
(log/fatal {:id :event :data {...}})
→ (log/log! {:level :fatal :id :event :data {...}})
```

---

## Final Steps

1. Remove convenience macros from `logging.cljc`:
   - `trace`, `debug`, `info`, `warn`, `error`, `fatal`

2. Verify no broken references:
   ```bash
   grep -r "log/trace\|log/debug\|log/info\|log/warn\|log/error\|log/fatal" src/
   ```

3. Run linting and formatting:
   ```bash
   clj-kondo --lint src
   cljfmt check src
   ```

4. Commit with message:
   ```
   refactor: Replace level-specific logging macros with log/log!
   
   - Refactored 97 logging calls across 7 files
   - Replaced log/trace, log/debug, etc. with log/log! + explicit :level
   - Removed convenience macros from logging.cljc
   - Maintains same functionality, simpler API
   ```

---

## Notes

- All calls already have the correct structure (map with :id, :data, etc.)
- Just need to add `:level` parameter and change macro name to `log/log!`
- No logic changes, purely mechanical refactoring
- Should be safe to do file-by-file
