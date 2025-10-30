# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-10-29 (Session 5 - SCI Limitation Documented ✅)

## CURRENT STATUS

**Last Commit**: `3e04d9e` - "docs: Document critical SCI destructuring limitation and fix"
**Last Tag**: `v0.11.1-sci-limitation-documented`
**Branch**: `main` - clean working tree, all changes committed and pushed

### What Was Accomplished (Session 5)

✅ **Discovered and documented critical SCI limitation**
- SCI (Scittle interpreter) does NOT support vector destructuring
- Affects function parameters AND let bindings
- Error: "nth not supported on this type function(...)"

✅ **Fixed the blocker**
- Changed from destructuring to explicit `first`/`second` calls
- Browser nREPL client now working correctly
- All tests passing (0 failures, 0 errors)

✅ **Comprehensive documentation**
- Added 135 lines to `doc/plan.md` (section: "⚠️ CRITICAL: SCI/Scittle Limitations")
- Added 41 lines to `CLAUDE.md` (coding best practices)
- Updated `CONTEXT.md` (this file)
- Includes code examples, coding rules, historical context

✅ **Quality checks**
- Linting: 0 errors, 0 warnings
- Formatting: All files correctly formatted
- Tests: All passing (10 unit tests, 6 multi-process scenarios)
- Pre-commit hooks: All passing

### Files Modified/Added
- `dev/scittle-demo/examples/sente-nrepl-client.cljs` - Fixed destructuring (NEW)
- `src/sente_lite/client_scittle.cljs` - Removed debug logging
- `dev/scittle-demo/sente-nrepl-gateway.clj` - nREPL gateway (NEW, WIP)
- `dev/scittle-demo/load-sente-nrepl-gateway.bb` - Gateway loader (NEW)
- `doc/plan.md` - Added SCI limitation documentation
- `CLAUDE.md` - Added coding best practices for Scittle

## ⚠️ CRITICAL: SCI/Scittle Limitation (Don't Forget!)

**THE RULE**: NEVER use destructuring in Scittle code

❌ **BROKEN in SCI:**
```clojure
(defn f [[a b]] ...)              ; Parameter destructuring
(let [[a b] msg] ...)             ; Let destructuring
```

✅ **WORKS in SCI:**
```clojure
(let [a (first msg) b (second msg)] ...)  ; Explicit accessors
```

**Why this matters:**
- Code works in BB-to-BB tests but fails in browser
- Error message is cryptic: "nth not supported"
- Silent failure - no warning, just crashes

**Full documentation**: See `doc/plan.md` lines 157-245 and `CLAUDE.md` lines 154-193

## Project State

### What's Working
- ✅ sente-lite core (BB-to-BB and Browser)
- ✅ Auto-reconnect with exponential backoff (BB and Browser)
- ✅ Pub/sub (all 4 scenarios: BB↔BB, Browser↔Browser, BB↔Browser)
- ✅ All 16 tests passing
- ✅ Zero linting errors
- ✅ Browser client connects to gateway via sente-lite

### Work In Progress
- 🚧 nREPL gateway - Basic connection working, needs end-to-end testing
  - Gateway running on ports 1346 (sente) and 1347 (nREPL bencode)
  - Browser connects and receives messages
  - Next: Test full eval flow (editor → gateway → browser → back)

## Key Project Info

### Port Architecture
**BB Dev Services** (ports 1338-1341):
- 1338: BB direct nREPL
- 1339: Browser nREPL proxy
- 1340: Browser WebSocket
- 1341: HTTP static server

**nREPL Gateway** (ports 1346-1347):
- 1346: Sente WebSocket (EDN to browser)
- 1347: nREPL bencode (editor connections)

### Quick Start Commands
```bash
# Run tests
./run_tests.bb

# Start BB dev environment
cd dev/scittle-demo && bb dev

# Start nREPL gateway
cd /Users/franksiebenlist/Development/sente_lite
bb -cp src dev/scittle-demo/sente-nrepl-gateway.clj

# Start browser
cd dev/scittle-demo && npm run interactive
```

### Fresh Start Sequence (From DEPLOYMENT-PROTOCOL.md)
1. **Kill everything**: `pkill -9 bb && pkill -9 node`
2. **Verify ports free**: `lsof -i tcp:1338` etc.
3. **Start BB dev**: `cd dev/scittle-demo && bb dev`
4. **Start gateway**: `bb -cp src dev/scittle-demo/sente-nrepl-gateway.clj`
5. **Start browser**: `npm run interactive`
6. **Load code**: Use `bb load-browser` for each file

## Important Files

**Core Implementation**:
- `src/sente_lite/server.cljc` - Server (21KB, ~441 lines)
- `src/sente_lite/client_scittle.cljs` - Browser client (12KB)
- `src/telemere_lite/core.cljc` - Telemetry

**nREPL Gateway** (Work in Progress):
- `dev/scittle-demo/sente-nrepl-gateway.clj` - Gateway server
- `dev/scittle-demo/examples/sente-nrepl-client.cljs` - Browser handler
- `dev/scittle-demo/load-sente-nrepl-gateway.bb` - Loader script

**Documentation**:
- `CLAUDE.md` - AI instructions (includes SCI limitation warning)
- `CONTEXT.md` - This file
- `doc/plan.md` - Implementation plan (includes SCI limitation section)
- `dev/scittle-demo/DEPLOYMENT-PROTOCOL.md` - 5-step deployment protocol

## Critical Rules (Always Follow)

**From CLAUDE.md:**

1. **HONESTY ABOVE ALL**
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
   - NEVER use println - use telemere-lite `(tel/info! ...)` etc.

6. **SCI/SCITTLE CODE**
   - NEVER use destructuring (parameters or let)
   - ALWAYS use explicit `first`, `second`, `nth`, `get`
   - ALWAYS test in actual browser - BB tests aren't enough

7. **TESTING STRATEGY**
   - Test BB-to-BB FIRST, always
   - Create complete end-to-end tests
   - Extract common code to shared CLJC functions
   - ONLY THEN test in browser

## Next Steps (For Next Session)

**Immediate**:
- Test end-to-end nREPL eval flow (editor → gateway → browser → back)
- Create test script that sends nREPL eval request to port 1347
- Verify browser evaluates code and returns result
- Fix any message format issues

**After nREPL Gateway Works**:
- Create snapshot (commit/tag/push)
- Update plan.md with completion status
- Consider additional features or move to next phase

## Common Issues & Solutions

**Issue**: Browser code not updating
- **Solution**: Always kill everything and restart (5-step sequence)

**Issue**: "nth not supported" error
- **Solution**: Check for destructuring, use `first`/`second` instead

**Issue**: Port already in use
- **Solution**: `pkill -9 bb && pkill -9 node`, verify with `lsof`

**Issue**: Tests fail after changes
- **Solution**: Run `./run_tests.bb`, fix all errors before committing

## Recovery Checklist (Use This After Compacting)

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

### Session 5 (2025-10-29) - SCI Limitation Discovery & Documentation
- **Discovered**: SCI vector destructuring limitation
- **Fixed**: Browser nREPL client (use first/second)
- **Documented**: Comprehensive docs in plan.md, CLAUDE.md, CONTEXT.md
- **Committed**: 3e04d9e "docs: Document critical SCI destructuring limitation and fix"
- **Tagged**: v0.11.1-sci-limitation-documented
- **Tests**: All passing (0 failures, 0 errors)
- **Status**: Clean, committed, pushed to GitHub

### Session 4 (2025-10-29) - nREPL Gateway Work (BLOCKED, then resolved in Session 5)
- Encountered "nth not supported" blocker
- Root cause discovered: SCI destructuring limitation

### Session 3 (2025-10-28/29) - Auto-Reconnect Complete
- **Tag**: v0.11.0-browser-reconnect-tested
- BB and browser auto-reconnect working
- All pub/sub scenarios tested
- Manual browser testing completed

---

**Remember**: This file is your guide after context compacting. Read it carefully, check timestamps on files, and verify actual state before making assumptions. The most recently modified files tell the truth about what was actually being worked on.
