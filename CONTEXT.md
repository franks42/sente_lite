# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-11-30 (Session 9 - Logging Refactoring Planning)

## CURRENT STATUS

**Last Commit**: `846020a` - "docs: Add refactoring plan and update context"
**Branch**: `main` - clean working tree
**Current Task**: Logging refactoring (planning phase complete, ready for implementation)

### What Was Accomplished (Session 8 - 2025-11-08)

✅ **COMPLETED Phase 2 + 2b - Trove Event ID Migration**
- Migrated all 108 logging calls to Trove `:namespace.component/event` format
- Server (Phase 2): 6 files, 87 calls migrated (11% reduction from 98)
- Client (Phase 2b): 1 file, 21 calls migrated
- Browser verified: All 9 Trove event IDs tested in Playwright
- Quality: 0 linting errors, all tests passing
- Committed: `be11868` (Phase 2) + `2581940` (Phase 2b)
- Tagged: `v0.6.0-phase2b-complete`

✅ **FIXED CRITICAL: Location Metadata Capture Bug**
- **Problem**: Location fields (file, line, ns) were nil in all test output
- **Impact**: Unable to debug - no source location information
- **Root Cause**: Incorrect structure in signal! macro
  - signal! created flat structure with location at top level
  - Tests expected nested structure with location inside context map
  - :msg was string instead of `[message context]` vector
  - dispatch-signal! passed entire msg vector to Timbre, causing double-wrapping
- **Fix Applied**:
  - Modified signal! macro (lines 268-286) to build context map with nested location
  - Modified signal! macro to create :msg as `[message context]` vector
  - Modified dispatch-signal! (lines 185-195) to destructure msg vector before Timbre
  - Ensures proper structure: `{"msg": ["message", {"location": {...}, "data": {...}}]}`
- **Result**: All 19 failing location tests now passing ✅
- **Verification**:
  - Tests: Exit code 0, 0 failures, 0 errors
  - clj-kondo: 0 errors, 0 warnings
  - cljfmt: All files formatted correctly
- **Committed**: `176488b` - "fix: Properly structure msg field and capture location metadata in telemetry"
- **Tagged**: `v0.6.1-location-metadata-fix`
- **Pushed**: ✅ Both commit and tag pushed to GitHub

### Event ID Distribution (Trove Migration)

**Server (87 total across 6 files):**
- `:sente-lite.server/*` - 25 events (connection, messages, lifecycle, broadcast)
- `:sente-lite.heartbeat/*` - 5 events (lifecycle, timeout, errors)
- `:sente-lite.pubsub/*` - 8 events (subscriptions, publishing)
- `:sente-lite.rpc/*` - 4 events (request/response handling)
- `:sente-lite.mux/*` - 6 events (multiplexing, format detection)
- `:sente-lite.tmux/*` - 9 events (transit multiplexing)
- `:sente-lite.format/*` - 6 events (wire format handling)

**Client (21 total in 1 file):**
- `:sente-lite.client/*` - 21 events (connection, messages, lifecycle, reconnection)
  - Connection: `creating`, `connected`, `reconnected`, `disconnected`
  - Messages: `msg-sent`, `msg-recv`, `parse-failed`, `send-failed`
  - Callbacks: `callback-on-open`, `callback-on-reconnect`
  - Reconnection: `reconnect-scheduled`, `reconnect-attempt`, `reconnect-initiated`, `reconnect-failed`, `reconnect-retry`
  - Control: `closing`, `reconnect-setting-updated`
  - Errors: `ws-error`, `invalid-client-id`, `close-failed`, `reconnect-setting-failed`
  - Internal: `handlers-attached`

### Log Level Philosophy (from Sente v1.21.0)
- **:trace** (40%): Internal flow, request/message handling, serialization
- **:debug** (30%): Lifecycle events, connections, subscriptions, broadcasts
- **:info** (20%): Server/heartbeat lifecycle, important events
- **:warn** (5%): Anomalies, timeouts, rejections
- **:error** (5%): Failures, parse errors, WebSocket errors

## 🎯 CURRENT SESSION (Nov 30, 2025): Logging Refactoring

**Status**: Planning phase - Ready for implementation

**What We Did**:
1. Investigated Trove compatibility with Scittle
2. Discovered Trove macros don't work in Scittle (by design - runtime interpreter limitation)
3. Created function-based Trove implementation for Scittle (`trove-scittle.cljs`)
4. Removed telemere-lite dependencies from `logging.cljc`
5. Identified that convenience macros (trace, debug, info, etc.) are still being used

**What We're Doing Now**:
- Refactoring all logging calls to use single `log/log!` function
- Replacing 97 logging calls across 7 files
- Removing convenience macros after refactoring

**What's Next**:
1. Refactor server.cljc (29 calls)
2. Refactor client_scittle.cljs (21 calls)
3. Refactor channels.cljc (15 calls)
4. Refactor server_simple.cljc (13 calls)
5. Refactor wire_format.cljc (9 calls)
6. Refactor wire_multiplexer.cljc (9 calls)
7. Refactor logging/bb.cljc (1 call)
8. Remove convenience macros from logging.cljc
9. Verify, lint, format, commit

**See**: `REFACTORING_PLAN.md` for detailed plan

## ⚠️ CRITICAL: Implementation Constraints

### 1. Browser Single File Constraint
- Browser loads: `dev/scittle-demo/telemere-lite.cljs` (symlink)
- Points to: `src/telemere_lite/core.cljc`
- **MUST be ONE downloadable file** - no requires, no splits

### 2. SCI/Scittle Limitation (Still Applies!)
❌ **NEVER use destructuring in Scittle code**
```clojure
(defn f [[a b]] ...)              ; BROKEN in SCI
(let [[a b] msg] ...)             ; BROKEN in SCI
```

✅ **ALWAYS use explicit accessors**
```clojure
(let [a (first msg) b (second msg)] ...)  ; WORKS in SCI
```

**Error symptom**: `"nth not supported on this type function(...)"`

**Why dangerous**:
1. Code works in BB-to-BB tests (no SCI)
2. Code fails mysteriously in browser (uses SCI)
3. Error message doesn't mention destructuring
4. Causes complete application crash

**Full docs**: `doc/plan.md` lines 157-245, `CLAUDE.md` lines 154-193

## Project State

### What's Working
- ✅ sente-lite core (BB-to-BB and Browser)
- ✅ Auto-reconnect with exponential backoff (BB and Browser)
- ✅ Pub/sub (all 4 scenarios: BB↔BB, Browser↔Browser, BB↔Browser)
- ✅ All tests passing (exit code 0)
- ✅ Zero linting errors
- ✅ Trove logging facade (migrated from telemere-lite)
- ✅ Trove function implementation for Scittle (`trove-scittle.cljs`)
- ⏳ Logging refactoring in progress (97 calls across 7 files)

### What's Documented (For Future)
- 📋 Compression feature (gzip + none, 1KB threshold)
- 📋 Capability negotiation (3-tier system)
- 📋 nREPL event handlers (BB-to-BB via babashka.nrepl.client)

## Key Project Info

### Port Architecture
**BB Dev Services** (ports 1338-1341):
- 1338: BB direct nREPL
- 1339: Browser nREPL proxy
- 1340: Browser WebSocket
- 1341: HTTP static server

### Quick Start Commands
```bash
# Run tests
./run_tests.bb

# Start BB dev environment
cd dev/scittle-demo && bb dev

# Start browser
cd dev/scittle-demo && npm run interactive
```

### Fresh Start Sequence (From DEPLOYMENT-PROTOCOL.md)
1. **Kill everything**: `pkill -9 bb && pkill -9 node`
2. **Verify ports free**: `lsof -i tcp:1338` etc.
3. **Start BB dev**: `cd dev/scittle-demo && bb dev`
4. **Start browser**: `npm run interactive`
5. **Load code**: Use `bb load-browser` for each file

## Important Files

**Core Implementation**:
- `src/sente_lite/server.cljc` - Server (21KB, ~441 lines)
- `src/sente_lite/server_simple.cljc` - Simplified server (6.5KB, ~161 lines)
- `src/sente_lite/client_scittle.cljs` - Browser client (12KB, 21 Trove events)
- `src/sente_lite/channels.cljc` - Channel management (12KB)
- `src/sente_lite/transit_multiplexer.cljc` - Transit envelope (13KB)
- `src/sente_lite/wire_format.cljc` - Message serialization (11KB)
- `src/sente_lite/wire_multiplexer.cljc` - Message multiplexing (11KB)
- `src/telemere_lite/core.cljc` - Telemetry (842 lines, unified BB + browser)

**Design Documents** (CRITICAL - Read These!):
- `REFACTORING_PLAN.md` - Current logging refactoring plan (97 calls, 7 files)
- `TROVE_SCITTLE_USAGE.md` - How to use Trove in Scittle
- `TROVE_SCITTLE_FINAL_VERDICT.md` - Why Trove doesn't work in Scittle
- `doc/trove-event-id-mapping.md` - Phase 2 + 2b completion status, event mapping
- `doc/plan.md` - Implementation plan (includes SCI limitation section)
- `CLAUDE.md` - AI instructions (includes all coding rules)
- `CONTEXT.md` - This file

**Logging Implementation**:
- `src/sente_lite/logging.cljc` - Trove facade (core `log!` function + convenience macros)
- `dev/scittle-demo/trove-scittle.cljs` - Trove function implementation for Scittle
- `dev/scittle-demo/taoensso/trove.cljs` - Duplicate (same as trove-scittle.cljs)

**Other Important**:
- `dev/scittle-demo/DEPLOYMENT-PROTOCOL.md` - 5-step deployment protocol
- `test/telemere_lite/core_test.cljc` - Location metadata tests

## Critical Rules (Always Follow)

**From CLAUDE.md:**

1. **HONESTY ABOVE ALL**
   - Display "I do not cheat or lie and I'm honest about any reporting of progress." at start
   - If something doesn't work, SAY IT DOESN'T WORK
   - If tests fail, SAY THEY FAIL
   - If you don't know, SAY YOU DON'T KNOW

2. **"COMPACTING IS LOBOTOMY"**
   - Check recently modified files FIRST: `find . -type f \( -name "*.md" -o -name "*.clj*" \) | xargs ls -lt | head -20`
   - Read CONTEXT.md and CLAUDE.md before making assumptions
   - Check git status: `git status && git log --oneline -5`

3. **ALWAYS START FROM PROJECT ROOT**
   - Use full absolute paths: `/Users/franksiebenlist/Development/sente_lite/...`

4. **VERIFY, DON'T ASSUME**
   - Check actual console output
   - Check actual log files
   - Check actual test results

5. **CODE QUALITY**
   - ALWAYS run clj-kondo on changed files: `clj-kondo --lint <file>`
   - ALWAYS run cljfmt: `cljfmt fix <file>`
   - NEVER use println - use telemere-lite `(tel/log! ...)` etc.

6. **SCI/SCITTLE CODE**
   - NEVER use destructuring (parameters or let)
   - ALWAYS use explicit `first`, `second`, `nth`, `get`
   - ALWAYS test in actual browser - BB tests aren't enough

7. **TESTING STRATEGY**
   - Test BB-to-BB FIRST, always
   - Create complete end-to-end tests
   - Extract common code to shared CLJC functions
   - ONLY THEN test in browser

## Technical Details - Location Metadata Fix

**Problem Structure** (BEFORE):
```clojure
;; signal! macro created flat structure
{:timestamp (now)
 :level :info
 :ns "..."
 :file "..."      ; ← Top level (wrong!)
 :line 123        ; ← Top level (wrong!)
 :msg "message"   ; ← String (wrong!)
 :data {...}}
```

**Expected Structure** (AFTER):
```clojure
;; signal! macro creates nested structure
{:timestamp (now)
 :level :info
 :ns "..."
 :msg ["message" {:location {:file "..."    ; ← Nested (correct!)
                             :line 123      ; ← Nested (correct!)
                             :ns "..."}     ; ← Nested (correct!)
                  :data {...}}]}            ; ← Vector [message, context] (correct!)
```

**dispatch-signal! Fix**:
```clojure
;; BEFORE: Passed entire msg vector to Timbre
(timbre/info (:msg signal))  ; Timbre sees: [message context]

;; AFTER: Destructure before passing to Timbre
(let [msg-vec (:msg signal ["" {}])
      message (first msg-vec)
      msg-context (second msg-vec)]
  (timbre/info message msg-context))  ; Timbre sees: "message", {:location ...}
```

## Next Steps (For Next Session)

**No specific task pending** - All current work completed:
- ✅ Phase 2 + 2b Trove migration
- ✅ Location metadata fix
- ✅ All tests passing
- ✅ Code committed, tagged, pushed

**User can choose next feature from**:
1. Sente-lite refactoring
2. Compression feature
3. Capability negotiation
4. Additional testing/documentation

## Common Issues & Solutions

**Issue**: Browser code not updating
- **Solution**: Always kill everything and restart (5-step sequence)

**Issue**: "nth not supported" error
- **Solution**: Check for destructuring, use `first`/`second` instead

**Issue**: Port already in use
- **Solution**: `pkill -9 bb && pkill -9 node`, verify with `lsof`

**Issue**: Tests fail after changes
- **Solution**: Run `./run_tests.bb`, fix all errors before committing

**Issue**: Location metadata is nil
- **Solution**: FIXED in v0.6.1 - :msg must be `[message context]` vector with nested location

## Recovery Checklist (Use This After Compacting/Reboot)

When starting a new session:

- [ ] Display "I do not cheat or lie..." at start of response
- [ ] Read CONTEXT.md (this file)
- [ ] Read CLAUDE.md for instructions
- [ ] Check recently modified files: `find . -type f \( -name "*.md" -o -name "*.clj*" \) | xargs ls -lt | head -20`
- [ ] Check git status: `git status && git log --oneline -5`
- [ ] Understand current state from timestamps, not assumptions
- [ ] Review SCI limitation (CRITICAL - causes silent failures)
- [ ] If working with browser code, remember: NO DESTRUCTURING

## Session Log

### Session 9 (2025-11-30) - Logging Refactoring Planning ⏳
- **INVESTIGATED**: Trove compatibility with Scittle
- **DISCOVERED**: Trove macros don't work in Scittle (runtime interpreter limitation)
- **CREATED**: Function-based Trove implementation (`trove-scittle.cljs`)
- **IDENTIFIED**: 97 logging calls across 7 files using convenience macros
- **PLANNING**: Refactor to single `log/log!` function
- **STATUS**: Planning phase complete, ready for implementation
- **NEXT**: Execute refactoring plan (see REFACTORING_PLAN.md)

### Session 8 (2025-11-08) - Trove Migration + Location Metadata Fix COMPLETE ✅
- **COMPLETED**: Phase 2 + 2b Trove event ID migration (108 calls total)
- **FIXED**: Critical location metadata capture bug (19 failing tests → all passing)
- **Files**: 7 files migrated (6 server + 1 client)
- **Event IDs**: Server 87, Client 21
- **Testing**: All tests passing (exit code 0)
- **Quality**: 0 linting errors, all formatted correctly
- **Commits**: 3 total (Phase 2, Phase 2b, location fix)
- **Tags**: v0.6.0-phase2b-complete, v0.6.1-location-metadata-fix
- **Status**: Production-ready, clean, committed, pushed to GitHub
- **Next**: Ready for user to choose next feature

### Session 7 (2025-10-31) - Lazy Eval IMPLEMENTED ✅
- **IMPLEMENTED**: Lazy evaluation with 3-14x speedup when disabled
- **Created**: `src/telemere_lite/core.cljc` (842 lines, unified BB + browser)
- **Pattern**: Three-stage lazy eval (filter → delay → :let → data/msg)
- **Testing**: BB tests ALL PASSED (lazy eval verified)
- **Performance**: 60-120ns disabled vs 300-850ns eager
- **Tagged**: v0.16.0-lazy-eval-implemented

### Session 6 (2025-10-31) - Lazy Eval Design Complete ✅
- **Researched**: Telemere source code
- **Designed**: Unified CLJC approach
- **Created**: `doc/telemere-lite-lazy-eval-improvement.md`
- **Tagged**: v0.15.0-lazy-eval-design

### Session 5 (2025-10-29) - SCI Limitation Discovery & Documentation
- **Discovered**: SCI vector destructuring limitation
- **Fixed**: Browser nREPL client (use first/second)
- **Documented**: Comprehensive docs in plan.md, CLAUDE.md, CONTEXT.md
- **Tagged**: v0.11.1-sci-limitation-documented

---

**Remember**: This file is your guide after context compacting or reboot. Read it carefully, check timestamps on files, and verify actual state before making assumptions. The most recently modified files tell the truth about what was actually being worked on.

**CURRENT STATE**: All Phase 2 + 2b work complete, location metadata fixed, all tests passing, ready for next feature.
