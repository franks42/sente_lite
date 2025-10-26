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

### Current Project Status
- **Phase 0 COMPLETED**: Code quality cleanup - achieved zero clj-kondo errors and warnings
- **Zero tolerance policy**: ALL linting errors must be resolved before proceeding
- **Linting achievements**: Fixed 28 errors + 36 warnings across multiple files
- **Test verification**: All tests passing after quality fixes

### Ring Jetty9 Adapter Migration
- **CRITICAL**: Project requires migration from http-kit to `info.sunng/ring-jetty9-adapter` for HTTP/2 support
- **Documentation**: Complete migration plan saved in `docs/ring-jetty9-adapter-migration-plan.md`
- **Why needed**: http-kit lacks HTTP/2 support; jetty9 provides HTTP/2 + WebSocket capabilities
- **Research completed**: Identified jetty9 as the solution during HTTP/2 vs WebSocket analysis
- **Todo items**: 10-step migration plan currently in todo list

### Implementation Progress
- **Current focus**: Testing ring-jetty9-adapter compatibility with existing WebSocket implementation
- **Goal**: Maintain same test suite while gaining HTTP/2 capabilities
- **Next phase**: Phase 1 of jetty9 migration plan (environment setup)

### Key Files and Context
- `src/sente_lite/server.cljc` - Current http-kit based server (441 lines)
- `src/sente_lite/server_simple.cljc` - Simplified server foundation (161 lines)
- `docs/plan.md` - 785-line comprehensive implementation plan
- `docs/ring-jetty9-adapter-migration-plan.md` - Detailed 7-phase migration strategy
- `.clj-kondo/config.edn` - Linting configuration for zero-warning compliance

### Memory Context
- User expects migration plans to exist (previous discussion context)
- Chat history contains HTTP/2 research and jetty9 identification
- Migration from http-kit to jetty9 is architectural necessity, not preference

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