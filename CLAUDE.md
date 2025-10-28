# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

### Current Project Status (2025-10-26)
- **BRANCH**: `main` - ready for sente-lite HTTP/1.1 + WebSocket development
- **LAST TAG**: `v0.5.2-cleanup` - fully organized, linted, tested, committed
- **ALL COMPLETED**:
  - ✅ Project structure reorganized (doc/, dev/, test/scripts/)
  - ✅ All 70KB of sente-lite source code committed and tracked
  - ✅ Zero linting errors (0 errors, 10 cljs warnings - expected)
  - ✅ All tests passing (run_tests.bb: 10 tests, 0 failures)
  - ✅ Pre-commit hooks working and enforcing quality
  - ✅ Clean working tree, all files properly tracked
  - ✅ HTTP/2 investigation complete (see doc/http2-investigation-2025-10.md)
- **NEXT**: Continue sente-lite core implementation (HTTP/1.1 + WebSocket)

### Recent Tags History
1. `v0.3.0-sente-lite` - Recovered 70KB sente-lite source (was untracked!)
2. `v0.4.0-project-structure` - Proper Clojure project organization
3. `v0.5.0-lint-clean` - Clean linting, formatting, tests passing
4. `v0.5.1-tooling-update` - Claude Code tooling configuration
5. `v0.5.2-cleanup` - Removed log files from tracking (CURRENT on main)

### HTTP/2 Investigation (October 2025)
- **Status**: ON HOLD - Investigation complete, HTTP/2 efforts paused
- **Decision**: Continue with HTTP/1.1 + WebSocket implementation
- **Reason**: Babashka (prime target) has no viable HTTP/2 path without excessive complexity
- **Full Documentation**: See `doc/http2-investigation-2025-10.md` for complete findings
- **Key Finding**: ring-jetty9-adapter requires JVM classes not available in babashka's SCI interpreter
- **Options Evaluated**: Pod (too complex), reverse proxy (limited benefit), split architecture (doubles maintenance)
- **Branch Preserved**: `jetty9-migration` kept for historical reference
- **Future**: Can revisit if babashka ecosystem evolves or HTTP/2 becomes critical for JVM deployments

### Current Focus: HTTP/1.1 + WebSocket Implementation
- **Platform**: Babashka as prime target
- **Server**: http-kit (HTTP/1.1 + WebSocket)
- **Protocol**: WebSocket provides full-duplex communication
- **Philosophy**: Lightweight, native capabilities, ~500 LOC simplicity

### Key Files and Context
- `src/sente_lite/server.cljc` - Current http-kit based server (17KB, ~441 lines)
- `src/sente_lite/server_simple.cljc` - Simplified server foundation (6.5KB, ~161 lines)
- `src/sente_lite/channels.cljc` - WebSocket channel management (12KB)
- `src/sente_lite/transit_multiplexer.cljc` - Transit envelope pattern (13KB)
- `src/sente_lite/wire_format.cljc` - Message serialization (11KB)
- `src/sente_lite/wire_multiplexer.cljc` - Message multiplexing (11KB)
- `doc/plan.md` - 785-line comprehensive implementation plan
- `doc/http2-investigation-2025-10.md` - Complete HTTP/2 investigation (Oct 2025, on hold)
- `doc/ring-jetty9-adapter-migration-plan.md` - Original 7-phase migration strategy (superseded)
- `.clj-kondo/config.edn` - Linting configuration for zero-warning compliance
- `test/scripts/run_all_tests.bb` - Main test runner (11 test scripts)

### Project Structure (Proper Clojure Conventions)
```
sente_lite/
├── src/                    # Source code
│   ├── sente_lite/        # Main WebSocket library (70KB total)
│   └── telemere_lite/     # Telemetry implementation
├── test/                   # Test namespaces
│   ├── scripts/           # Test runner scripts (11 scripts)
│   └── telemere_lite/     # Test files
├── doc/                    # Documentation (singular, Clojure convention)
├── dev/                    # Development tools (linting, editing scripts)
├── deps.edn                # Clojure dependencies
└── run_tests.bb            # Main test runner (convenience)
```

### Memory Context
- **CRITICAL RECOVERY**: On 2025-10-25, discovered 70KB of sente-lite source was untracked!
- All source files were in src/sente_lite/ but never committed (telemere-lite was committed instead)
- Immediately committed, pushed, tagged as v0.3.0-sente-lite
- Then reorganized entire project structure to Clojure best practices
- **HTTP/2 Investigation**: On 2025-10-26, investigated jetty9 migration for HTTP/2 support
- Found jetty9 incompatible with babashka (requires JVM classes unavailable in SCI)
- Decision: Pause HTTP/2 efforts, continue with http-kit (HTTP/1.1 + WebSocket)
- User expects proper snapshots (commit/push/tag) throughout work

## Important Implementation Details

### Serialization Strategy
- Uses Transit for all message serialization
- Supports multiplexing different formats (bencode for nREPL) inside Transit envelopes
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