# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

### Terminology
- **"snapshot"**: When mentioned, this means to commit, push, and tag the current changes to the repository

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