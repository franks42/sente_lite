# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**CRITICAL: AI must display "I do not cheat or lie and I'm honest about any reporting of progress." at start of every response**

## Context Recovery After Compacting or New Instance

**CRITICAL: When starting a new session or after context compacting:**

1. **Check most recently changed files FIRST** to understand current work:
   ```bash
   # Find recently modified files across entire project
   find . -type f -name "*.md" -o -name "*.clj*" -o -name "*.bb" | \
     xargs ls -lt | head -20

   # Or more specifically for documentation
   find . -name "*.md" -type f -exec ls -lt {} + | head -20
   ```

2. **Read the most recent files** to understand:
   - What was being worked on
   - Current state of implementation
   - Any blockers or issues discovered

3. **Check git status** for uncommitted changes:
   ```bash
   git status
   git diff --stat
   ```

4. **NEVER assume** context from old documentation or distant commits
5. **NEVER read files based on guesses** - let timestamps guide you

**Rationale**: The most recently modified files reveal the actual current work, not what we planned to work on or what's documented in older files.

## CRITICAL: Testing Strategy - BB-to-BB First, Always

**FUNDAMENTAL RULE: Test BB-to-BB before touching browser code**

When implementing or debugging any feature (especially message handling):

1. **Create COMPLETE BB-to-BB test FIRST**
   - Test the ENTIRE feature flow end-to-end
   - Multiple clients if needed (e.g., pub/sub needs 2+ subscribers)
   - Don't stop at partial tests (e.g., only subscribe without publish/receive)
   - Make absolutely sure it works 100%

2. **Extract common code to shared CLJC functions**
   - Identify reusable message handlers
   - Put shared code in `.cljc` files
   - Use the SAME code between BB and browser

3. **ONLY THEN go to browser**
   - Use the proven BB approach
   - Use the shared CLJC functions
   - Any bugs found affect both, fix in shared code

**Why this matters:**
- BB tests are 10x faster to run and debug
- No browser environment complexity
- Proves the server works correctly
- Ensures code reuse between BB and browser
- Prevents wasted effort debugging browser-specific issues when the real problem is server-side

**Never skip this. Never test browser first. Never create incomplete BB tests.**

## Project Overview

sente-lite is a lightweight WebSocket library for Babashka (bb), Node Babashka (nbb), and Scittle/SCI environments that provides Sente-like functionality without the heavy dependencies. It's designed for constrained environments where core.async and JVM/ClojureScript features aren't available.

## Key Architecture Concepts

### Design Philosophy
- **Native capability first**: Uses environment-native features (browser WebSocket API, babashka.http-client.websocket)
- **~85% Sente API compatibility**: Matches Sente's surface API to ease migration
- **core.async.flow philosophy without implementation**: Uses callbacks/promises instead of channels
- **Declarative system topology**: Components defined as data structures with lifecycle management

### Core Components
1. **Connection Manager**: Handles WebSocket lifecycle, reconnection, and state management
2. **Message Router**: Event dispatch and user-based routing
3. **Step Functions**: Pure data transformations for business logic
4. **Transit Envelope Pattern**: Multiplexes different message types (nREPL bencode, app messages) over single WebSocket

### Target Environments
- **Browser (Scittle)**: Native WebSocket API
- **Babashka Server**: babashka.http-client.websocket
- **Node.js (nbb)**: ws npm package
- **JVM Clojure**: http-kit or Aleph
- **BB-to-BB communication**: Direct Babashka WebSocket connections

## Commands

Since this is a library without build configuration files, there are no standard build/test commands. Implementation would depend on the target environment:

- **Babashka scripts**: Would use `bb` command
- **Scittle/Browser**: Would be included via script tags
- **Node/nbb**: Would use `nbb` command

### Code Quality Commands

- **Linting**: `clj-kondo --lint <file>` - Run on every file change, resolve all errors before proceeding
- **Formatting**: `cljfmt fix <file>` - Format files after any changes
- **Testing**: `./run_tests.bb` - Run comprehensive test suite
- **Demo**: `./demo_startup.bb` - Test telemere-lite functionality

## Coding Best Practices

### Required for Every File Change
1. **ALWAYS run clj-kondo** on any changed/edited file and resolve ALL linting errors before proceeding
2. **ALWAYS use cljfmt** to format the file after any change
3. **Paren mismatch recovery**: When changes result in mismatched parens:
   - Copy the top-level form to a tmp file
   - Make the change in the tmp file
   - Copy the corrected form back to the code file

### Logging and Observability
**NEVER use println for logging** - We have invested significant effort in telemere-lite for structured observability.

- Use `(tel/log! level message data)` for structured logging
- Use `(tel/event! event-id data)` for event tracking
- Use `(tel/error! message error-data)` for error logging
- Use `(tel/debug! ...)`, `(tel/info! ...)`, `(tel/warn! ...)` for level-specific logging

**Rationale**: telemere-lite provides:
- Structured data with every log
- Async handlers for performance
- Filtering by level, namespace, and event ID
- Integration with Timbre for formatting
- Full observability across client and server

### Memory Storage (MCP)
**ALWAYS use proper array format for tags** - String format will fail and waste tokens.

**CORRECT FORMAT:**
```clojure
(mcp__memory__store_memory
  {:content "Your content here"
   :metadata {:tags ["tag1", "tag2", "tag3"]}})
```

**INCORRECT FORMAT (WILL FAIL):**
```clojure
;; ❌ WRONG - This will fail with "is not of type 'array'" error
(mcp__memory__store_memory
  {:content "Your content here"
   :metadata {:tags "tag1,tag2,tag3"}})
```

**Common mistake**: Using comma-separated string instead of array
**Fix**: Always use `["tag1", "tag2"]` NOT `"tag1,tag2"`

### ⚠️ CRITICAL: SCI/Scittle Destructuring Limitation
**DISCOVERED:** 2025-10-29
**SEVERITY:** HIGH - Silent production failures

**SCI (Small Clojure Interpreter) does NOT support vector destructuring** - neither in function parameters nor in `let` bindings.

❌ **NEVER DO THIS in Scittle code:**
```clojure
;; ❌ BROKEN - Function parameter destructuring
(defn handle-message [[event-type event-data]] ...)

;; ❌ BROKEN - Let binding destructuring
(defn handle-message [msg]
  (let [[event-type event-data] msg] ...))
```

✅ **ALWAYS DO THIS in Scittle code:**
```clojure
;; ✅ WORKS - Use explicit first/second/nth
(defn handle-message [msg]
  (let [event-type (first msg)
        event-data (second msg)]
    ...))
```

**Error symptom**: `"nth not supported on this type function(...)"`

**Why this is dangerous:**
1. Code works in BB-to-BB tests (no SCI)
2. Code fails mysteriously in browser (uses SCI)
3. Error message doesn't mention destructuring
4. Causes complete application crash

**ALWAYS:**
- Use `first`, `second`, `nth` for vectors
- Use explicit `get` or keyword access for maps
- Test browser code in actual browser - BB tests are insufficient
- Check working demos: `sente-heartbeat-demo-client.cljs`, `sente-pubsub-demo-client.cljs`

**Full documentation:** See `doc/plan.md` section "⚠️ CRITICAL: SCI/Scittle Limitations"

### Terminology
- **"snapshot"**: When mentioned, this means to commit, push, and tag the current changes to the repository

## Project Planning & Task Management

**USE `doc/plan.md` AS THE SINGLE SOURCE OF TRUTH FOR:**
- Project status and current state
- Completed phases and features
- In-progress work
- Future enhancements and ideas
- Task priorities and estimates
- Architecture decisions
- Updates log (date-stamped entries)

**NEVER create separate planning documents** like FUTURE-ENHANCEMENTS.md, TODO.md, ROADMAP.md, etc.
**ALWAYS update plan.md** when:
- Completing features or phases
- Planning new work
- Recording architecture decisions
- Documenting ideas for future work
- Tracking progress

Keep plan.md up-to-date and comprehensive. It's the authoritative source for project state.

## FUNDAMENTAL RULE: HONESTY ABOVE ALL

**NEVER EVER lie or cheat.**

- If something doesn't work, SAY IT DOESN'T WORK
- If tests fail, SAY THEY FAIL
- If code is untested, SAY IT'S UNTESTED
- If you don't know, SAY YOU DON'T KNOW

**Pleasing the user is completely irrelevant. Working code is what matters.**

Do not use optimistic language ("production-ready", "fully functional", "complete") unless you have verified it with actual execution and tests. Do not write documentation for features that don't work. Do not commit with positive messages when things are broken.

## CRITICAL CONTEXT - DO NOT LOSE

### Chat History Location
- **Full conversation history**: `/Users/franksiebenlist/.claude/projects/-Users-franksiebenlist-Development-sente-lite/efb105ef-14ea-4aad-bbf6-0de3434b1c5c.jsonl`
- Use this file to recover lost context from previous sessions
- Contains detailed discussions about migration plans and architecture decisions

### Current Project Status (2025-10-31)
- **BRANCH**: `main` - clean working tree, all changes committed and pushed
- **LAST TAG**: `v0.15.0-lazy-eval-design` - telemere-lite lazy evaluation design complete
- **COMPLETED (Session 6 - 2025-10-31)**:
  - ✅ Researched official Telemere source code (~200 lines of impl/signal! macro)
  - ✅ Documented three-stage lazy eval pattern: filter → delay → :let → data/msg
  - ✅ Designed unified CLJC approach (ONE file for BB + browser)
  - ✅ Documented three-sink browser architecture (console, atom, websocket)
  - ✅ Created comprehensive living document: `doc/telemere-lite-lazy-eval-improvement.md` (700+ lines)
  - ✅ Performance analysis: 3-14x speedup when disabled (60-120ns vs 300-850ns)
  - ✅ Build-from-scratch implementation strategy (safer than in-place edits)
- **NEXT**: **Implement telemere-lite lazy evaluation** (HIGH PRIORITY - do BEFORE sente-lite refactoring!)

### What's Working Now
- ✅ sente-lite core (BB-to-BB and Browser) - fully functional
- ✅ Auto-reconnect with exponential backoff (BB and Browser)
- ✅ Pub/sub (all 4 scenarios: BB↔BB, Browser↔Browser, BB↔Browser)
- ✅ All 16 tests passing
- ✅ Zero linting errors
- ✅ telemere-lite (basic, eager evaluation) - **needs lazy eval improvement**

### Recent Tags History (Last 5)
1. `v0.11.0-browser-reconnect-tested` - Auto-reconnect working (Session 3)
2. `v0.11.1-sci-limitation-documented` - SCI destructuring limitation discovery (Session 5)
3. `v0.13.0-capability-negotiation-design` - Capability negotiation design
4. `v0.14.0-compression-analysis` - Compression feature analysis
5. `v0.15.0-lazy-eval-design` - Lazy evaluation design complete (CURRENT, Session 6)

### Current Focus: telemere-lite Lazy Evaluation Implementation
- **Living Document**: `doc/telemere-lite-lazy-eval-improvement.md` - Follow checkboxes for implementation
- **New File**: Create `src/telemere_lite/core_new.cljc` from scratch (DO NOT edit existing files)
- **Single File Constraint**: Browser loads ONE file via symlink - no requires, no splits
- **Pattern**: Three-stage lazy eval from Telemere (filter → delay → :let)
- **Performance**: 3-14x speedup when disabled (60-120ns overhead vs current 300-850ns)
- **Three Sinks (Browser)**: Console (default ON), Atom (default OFF), WebSocket (default OFF)

### Key Files and Context
**Core Implementation**:
- `src/sente_lite/server.cljc` - Server (21KB, ~441 lines) - WORKING
- `src/sente_lite/client_scittle.cljs` - Browser client (12KB) - WORKING
- `src/telemere_lite/core.cljc` - Telemetry (BB) - **NEEDS LAZY EVAL** (reference only, don't edit)
- `src/telemere_lite/scittle.cljs` - Telemetry (browser) - **TO BE DELETED** (reference only)
- `src/telemere_lite/core_new.cljc` - **TO BE CREATED** (build from scratch)

**Design Documents (CRITICAL - Read These!)**:
- `doc/telemere-lite-lazy-eval-improvement.md` - **LIVING DOCUMENT** for implementation (700+ lines)
- `doc/final-sente-lite-design-implementation.md` - Sente-lite design + telemetry architecture
- `doc/sente-lite-compression-feature.md` - Compression analysis (future)
- `CONTEXT.md` - Updated with Session 6 details
- `CLAUDE.md` - This file

**Other Important**:
- `doc/plan.md` - Implementation plan (includes SCI limitation section)
- `dev/scittle-demo/DEPLOYMENT-PROTOCOL.md` - 5-step deployment protocol
- `.clj-kondo/config.edn` - Linting configuration

### Project Structure (Proper Clojure Conventions)
```
sente_lite/
├── src/                    # Source code
│   ├── sente_lite/        # Main WebSocket library (working)
│   └── telemere_lite/     # Telemetry (needs lazy eval)
├── test/                   # Test namespaces
│   ├── scripts/           # Test runner scripts
│   └── telemere_lite/     # Test files
├── doc/                    # Documentation (singular, Clojure convention)
│   └── telemere-lite-lazy-eval-improvement.md  # Living document for implementation
├── dev/                    # Development tools
│   └── scittle-demo/      # Browser development environment
├── deps.edn                # Clojure dependencies
└── run_tests.bb            # Main test runner
```

### Implementation Constraints (CRITICAL)
1. **Browser Single File Constraint**: Browser loads `telemere-lite.cljs` (symlink) - must be ONE downloadable file
2. **Build From Scratch**: DO NOT edit existing files (error-prone) - create `core_new.cljc`, test, then replace
3. **SCI/Scittle Limitation**: NEVER use destructuring in browser code (see section above)
4. **Test BB-to-BB First**: Always test in BB before browser (10x faster, easier debugging)

## Important Implementation Details

### Serialization Strategy
- **Primary format: EDN** - Default for Clojure-to-Clojure communication
- **Rationale**: Scittle nREPL uses EDN over WebSocket (not bencode), EDN performance acceptable
- Supports pluggable formats (JSON, Transit) via wire_format.cljc
- Event format: `[:event-id {:data}]`

### Telemetry Integration
- Designed to work with Telemere for structured telemetry
- Browser collects minimal signals, Babashka handles full processing
- WebSocket transport for real-time browser telemetry (errors, logs, metrics)

### API Differences from Sente
- `:ch-recv` returns callback registry instead of core.async channel
- No Ajax fallback (WebSockets only)
- No complex protocol negotiation or message batching
- ~500 LOC implementation vs Sente's ~1500 LOC

## CRITICAL: Scittle nREPL Deployment Protocol

**Location**: `dev/scittle-demo/`
**Complete Documentation**: `dev/scittle-demo/DEPLOYMENT-PROTOCOL.md`

### CRITICAL: Keep Documentation Current

**WHENEVER you discover:**
- A better way to perform any deployment step
- An edge case or failure mode not documented
- A verification method that works better
- Additional troubleshooting steps
- Any improvement or clarification

**YOU MUST immediately update `dev/scittle-demo/DEPLOYMENT-PROTOCOL.md`**

The deployment protocol is a living document that MUST stay current with actual working practices. Stale documentation is dangerous documentation.

### FUNDAMENTAL RULE: NO CHEATING, NO LYING

**WHEN TESTING DEPLOYMENT STEPS:**
- If something doesn't work → STOP AND SAY IT DOESN'T WORK
- If a port is still in use → SAY IT'S STILL IN USE
- If a process won't die → SAY IT WON'T DIE
- If a connection fails → SAY IT FAILED
- If you see an error → REPORT THE FULL ERROR
- If you're not sure → SAY YOU'RE NOT SURE

**DO NOT:**
- Pretend things work when they don't
- Skip verification steps
- Assume success without checking
- Move forward when something fails
- Use optimistic language ("should work", "probably fine") without proof

### The 5-Step Kill/Restart Sequence (Summary)

**See DEPLOYMENT-PROTOCOL.md for complete details, verification steps, and troubleshooting.**

1. **KILL EVERYTHING** → `pkill -9 bb && pkill -9 node`
   - Verify with: `ps aux | grep -E "bb|node"`
   - Must show NO processes

2. **VERIFY PORTS FREE** → `lsof -i :1338 :1339 :1340 :1341 :1342`
   - Must show NO output (all ports free)
   - If ports in use → GO BACK TO STEP 1

3. **START BB SERVER** → `cd dev/scittle-demo && bb dev`
   - Starts 4 services (ports 1338, 1339, 1340, 1341)
   - Verify with: `lsof -i :1338 :1339 :1340 :1341`
   - Must show all 4 ports listening

4. **START BROWSER** → `npm run interactive`
   - Opens Playwright browser with DevTools
   - Verify: Check console for WebSocket connection, no errors
   - Test: Eval `(+ 1 2 3)` via port 1339 nREPL

5. **RE-UPLOAD ALL CODE** (everything lost from memory!)
   - Upload server code to BB via port 1338
   - Upload client code to browser via port 1339
   - Start additional services (port 1342 if needed)
   - Verify each upload completes without errors

### When to Run This Sequence

**ALWAYS run full 5-step sequence when:**
- Starting fresh development session
- Something doesn't work
- Need to restart any service
- Port conflicts occur
- Browser loses connection

**NEVER try to:**
- Restart just one service
- "Fix" individual components
- Skip verification steps
- Proceed when something fails

### Rule: If It Doesn't Work → START FROM SCRATCH

**DO NOT TRY TO FIX IT. RUN THE 5-STEP SEQUENCE.**

Every time. No exceptions. No shortcuts.