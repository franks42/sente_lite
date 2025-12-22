# HTTP/2 Investigation and Decision - October 2025

## Executive Summary

**Decision**: Pausing HTTP/2 efforts for sente-lite. Continuing with HTTP/1.1 + WebSocket implementation.

**Primary Reason**: Babashka is the prime target platform for sente-lite, and there is no viable path to HTTP/2 support in babashka without significant architectural complexity that undermines the project's "lightweight" design philosophy.

**Status**: HTTP/2 efforts on hold. Core sente-lite development continues with HTTP/1.1 + WebSocket.

---

## Original Motivation

### Why HTTP/2?

The initial plan was to migrate from http-kit to ring-jetty9-adapter to gain:

1. **HTTP/2 Protocol Support**
   - Multiplexing: Multiple streams over single connection
   - Server Push: Proactive resource delivery
   - Header Compression: HPACK compression
   - Binary Framing: More efficient than HTTP/1.1 text

2. **Modern Protocol Features**
   - Better performance for multiple resource requests
   - Reduced latency for modern web applications
   - Future-proofing the implementation

3. **Maintained Dependency**
   - Active development and security updates
   - Better long-term support

### Initial Assessment

http-kit was identified as lacking HTTP/2 support:
- GitHub Issue #299 confirmed no plans for HTTP/2
- Recommendation: Use reverse proxy (nginx) for HTTP/2
- Limitation: Internal traffic remains HTTP/1.1

---

## Migration Plan Created

A comprehensive 7-phase migration plan was developed in `doc/ring-jetty9-adapter-migration-plan.md`:

### Phase 1: Environment Setup
- Add jetty9 dependencies to bb.edn
- Verify dependency resolution
- Test namespace loading

### Phase 2: Server Implementation
- Create new `server_jetty9.cljc` implementation
- Implement Ring handler with WebSocket upgrade
- Maintain API compatibility with existing server

### Phase 3: Compatibility Testing
- Run existing test suite against jetty9 implementation
- Verify all WebSocket functionality works
- Compare behavior with http-kit implementation

### Phase 4: HTTP/2 Testing
- Test HTTP/2 protocol negotiation
- Verify multiplexing functionality
- Test server push capabilities

### Phase 5: Performance Comparison
- Benchmark HTTP/1.1 vs HTTP/2
- Compare with http-kit baseline
- Measure connection handling and throughput

### Phase 6: Integration Validation
- Test with real sente-lite applications
- Verify Transit multiplexer compatibility
- Test nREPL integration

### Phase 7: Migration Assessment
- Evaluate HTTP/2 benefits in practice
- Decide on default implementation
- Document migration path for users

**Branch Created**: `jetty9-migration` (based on main v0.5.2-cleanup)

---

## Investigation Findings

### Discovery 1: Jetty9 Incompatible with Babashka

**Attempted**: Load ring-jetty9-adapter in babashka

```clojure
bb -e "(require 'ring.adapter.jetty9)"
```

**Result**: FAILED

```
Type:     java.lang.Exception
Message:  Unable to resolve classname: org.eclipse.jetty.server.Server
Location: ring/adapter/jetty9.clj:4:3
```

**Root Cause**:
- ring-jetty9-adapter requires JVM Java classes (`org.eclipse.jetty.server.Server`)
- Babashka uses SCI (Small Clojure Interpreter) which only has Java classes explicitly included in binary
- Jetty classes are NOT included in babashka binary
- Cannot dynamically load arbitrary Java classes in babashka

**Impact**: Jetty9 migration is JVM-only, cannot work in babashka

### Discovery 2: No HTTP/2 Server in Babashka Ecosystem

**Investigated**:

1. **http-kit** (current implementation)
   - ❌ No HTTP/2 support
   - ❌ No plans to add HTTP/2 (Issue #299)
   - Recommendation: Use nginx reverse proxy
   - Limitation: Internal traffic remains HTTP/1.1

2. **babashka/http-server**
   - Static file server only
   - No WebSocket support
   - Not suitable for sente-lite

3. **babashka/http-client**
   - **Client-only** library (not a server)
   - Built on java.net.http
   - Supports HTTP/2 for client requests
   - No server functionality

4. **babashka built-in server**
   - Uses http-kit's `run-server` function
   - HTTP/1.1 only
   - No HTTP/2 capability

**Conclusion**: No native HTTP/2 server option exists for babashka

### Discovery 3: Babashka Server Constraints

Babashka's server capabilities are fundamentally limited to what's compiled into the binary:

- **Included**: http-kit server (HTTP/1.1 + WebSocket)
- **Not included**: Jetty, Netty, other server implementations
- **Cannot add**: Arbitrary JVM server libraries
- **Reason**: SCI interpreter limitations, binary size constraints

---

## Options Evaluated

### Option 1: Babashka Pod for Jetty9

**Architecture**:
```
┌─────────────────┐   bencode IPC   ┌──────────────────┐
│ Babashka Process│ <-------------> │ JVM Pod (Jetty9) │
│  (SCI runtime)  │                 │  (HTTP/2 server) │
└─────────────────┘                 └──────────────────┘
                                            │
                                       HTTP/2 clients
```

**How it works**:
- Separate JVM process runs Jetty9 server
- Babashka communicates via bencode over stdin/stdout
- Pod handles HTTP/2 connections, forwards to babashka
- Babashka processes business logic, returns via pod

**Pros**:
- ✅ Technically enables HTTP/2 in babashka deployments
- ✅ Uses existing babashka codebase
- ✅ Could be reusable across projects

**Cons**:
- ❌ **Complex**: Process management, lifecycle, error handling
- ❌ **Performance**: Every request crosses process boundary + serialization
- ❌ **Deployment**: Must package both babashka + JVM pod
- ❌ **Operational**: Two processes to monitor, two JVMs to tune
- ❌ **Philosophy**: If running JVM anyway, why not use JVM Clojure directly?
- ❌ **Maintenance**: Complex pod protocol, edge cases, debugging

**Verdict**: Too complex for lightweight library philosophy

---

### Option 2: Reverse Proxy Pattern

**Architecture**:
```
┌────────────────┐   HTTP/2   ┌─────────────────────┐   HTTP/1.1   ┌──────────────┐
│     Client     │ <--------> │ nginx/caddy/traefik │ <----------> │  Babashka    │
│  (Browser/App) │            │  (Reverse Proxy)    │              │  (http-kit)  │
└────────────────┘            └─────────────────────┘              └──────────────┘
```

**How it works**:
- nginx/caddy terminates HTTP/2 connections
- Proxies to babashka via HTTP/1.1
- Babashka remains unchanged

**Pros**:
- ✅ Simple, battle-tested pattern
- ✅ No code changes to sente-lite
- ✅ Reverse proxy handles TLS, compression, rate limiting
- ✅ Industry standard deployment pattern

**Cons**:
- ❌ **Cannot use HTTP/2 features**: No server push, no true multiplexing
- ❌ **Internal HTTP/1.1**: Backend still uses HTTP/1.1
- ❌ **Limited benefit**: Only helps clients negotiate HTTP/2
- ❌ **Additional component**: Deployment complexity

**Verdict**: Doesn't provide real HTTP/2 benefits, just protocol translation

---

### Option 3: Split Architecture

**Architecture**:
```
Deployment Option A (Babashka):
  http-kit → HTTP/1.1 + WebSocket

Deployment Option B (JVM Clojure):
  jetty9 → HTTP/2 + WebSocket
```

**Implementation**:
- `src/sente_lite/server.cljc` - Babashka (http-kit, HTTP/1.1)
- `src/sente_lite/server_jvm.clj` - JVM only (jetty9, HTTP/2)
- Shared core: wire formats, multiplexing, channel management

**Pros**:
- ✅ Clean separation of concerns
- ✅ Each platform uses native capabilities
- ✅ No exotic patterns or complexity
- ✅ HTTP/2 available where possible (JVM)

**Cons**:
- ❌ Maintain two server implementations
- ❌ Babashka users don't get HTTP/2
- ❌ Testing complexity (two code paths)

**Verdict**: Technically sound but splits development effort

---

### Option 4: Accept Limitation

**Approach**: HTTP/1.1 + WebSocket only, document limitation

**Rationale**:
- Babashka is the **prime target** for sente-lite
- WebSocket provides full-duplex communication
- HTTP/2 multiplexing less critical when using WebSocket
- Focus resources on core functionality

**Pros**:
- ✅ Simple, focused implementation
- ✅ Works across all target platforms
- ✅ WebSocket provides real-time communication needs
- ✅ Aligns with "lightweight" philosophy

**Cons**:
- ❌ No HTTP/2 protocol features
- ❌ May miss future protocol benefits

**Verdict**: Most pragmatic for babashka-focused library

---

## Decision Rationale

### Why Pause HTTP/2 Efforts

1. **Prime Target Platform**: Babashka is the primary use case for sente-lite
   - Lightweight scripting environment
   - Fast startup, low resource usage
   - No viable HTTP/2 path without compromising these benefits

2. **Complexity vs. Benefit**:
   - Pod approach: Too complex for lightweight library
   - Reverse proxy: Doesn't provide real HTTP/2 benefits
   - Split architecture: Doubles maintenance burden
   - All options add significant complexity

3. **WebSocket Sufficiency**:
   - WebSocket already provides full-duplex communication
   - HTTP/2 multiplexing less critical with WebSocket
   - Server push not needed for WebSocket-based architecture

4. **Design Philosophy**:
   - sente-lite aims for ~500 LOC simplicity
   - "Native capability first" - use what platform provides
   - HTTP/2 efforts conflict with lightweight goals

5. **Practical Priorities**:
   - Core functionality (channels, routing, wire formats) incomplete
   - HTTP/1.1 + WebSocket works well for use cases
   - Can revisit HTTP/2 when babashka ecosystem evolves

---

## What Was Built

### Files Created

1. **bb.edn** (jetty9-migration branch)
   - Added jetty9 dependencies
   - Successfully downloaded all jars
   - Verified dependency resolution works

2. **doc/ring-jetty9-adapter-migration-plan.md**
   - Comprehensive 7-phase migration plan
   - Detailed implementation strategy
   - Testing and validation approach

3. **This document** (doc/http2-investigation-2025-10.md)
   - Complete investigation findings
   - Options evaluation
   - Decision documentation

### Branch Status

- **Branch**: `jetty9-migration` (based on main v0.5.2-cleanup)
- **Commits**:
  - Created bb.edn with jetty9 dependencies
  - Updated CLAUDE.md with context
- **Status**: Ready to archive or delete
- **Recommendation**: Keep branch for historical reference

---

## Future Considerations

### When to Revisit HTTP/2

1. **Babashka Evolution**:
   - If babashka adds HTTP/2 server to binary
   - If pod protocol becomes more efficient
   - If babashka gains dynamic class loading

2. **Ecosystem Changes**:
   - New lightweight HTTP/2 server for babashka
   - http-kit adds HTTP/2 support
   - Better pod infrastructure emerges

3. **Use Case Changes**:
   - If JVM deployment becomes primary target
   - If HTTP/2 features (server push) become critical
   - If multiplexing proves essential

### How to Monitor

- Watch babashka release notes for server improvements
- Monitor http-kit issues for HTTP/2 discussions
- Track babashka pod ecosystem development
- Revisit when sente-lite reaches feature completeness

### Migration Path If Revived

1. Use the existing `doc/ring-jetty9-adapter-migration-plan.md`
2. Pick up from Phase 2 (Server Implementation)
3. Target JVM deployments only (document babashka limitation)
4. Or wait for babashka HTTP/2 solution

---

## Technical Artifacts Preserved

### Dependencies Verified (bb.edn)

```clojure
{:paths ["src" "test"]
 :deps {;; Jetty9 adapter for HTTP/2 + WebSocket support
        info.sunng/ring-jetty9-adapter {:mvn/version "0.37.6"}
        ring/ring-core {:mvn/version "1.9.6"}

        ;; Keep http-kit for comparison
        http-kit/http-kit {:mvn/version "2.8.0"}

        ;; Transit and JSON
        com.cognitect/transit-clj {:mvn/version "1.0.333"}
        com.cognitect/transit-cljs {:mvn/version "0.8.280"}
        org.clojure/data.json {:mvn/version "2.5.0"}
        cheshire/cheshire {:mvn/version "5.13.0"}}

 :min-bb-version "1.1.0"}
```

**Verification Results**:
- ✅ All dependencies downloaded successfully
- ✅ bb deps prep completed without errors
- ❌ Cannot load ring.adapter.jetty9 namespace in babashka
- ❌ Missing org.eclipse.jetty.server.Server class

### Error Message Reference

```
Type:     java.lang.Exception
Message:  Unable to resolve classname: org.eclipse.jetty.server.Server
Location: ring/adapter/jetty9.clj:4:3

Context:
  (ns ring.adapter.jetty9
    "Adapter for the Jetty 10 server, with websocket support."
    (:import [org.eclipse.jetty.server
              Server ServerConnector Connector Handler
              ^--- Unable to resolve classname
```

This confirms jetty9 cannot work in babashka's SCI environment.

---

## Lessons Learned

1. **Platform Constraints Matter**: Babashka's SCI environment has fundamental limitations
2. **Investigate Before Planning**: Should have verified jetty9 compatibility before creating migration plan
3. **Lightweight Philosophy**: Adding JVM pods contradicts "lightweight" design goals
4. **WebSocket Sufficiency**: HTTP/2 less critical when using WebSocket for real-time communication
5. **Pragmatic Decisions**: Sometimes the best path is to accept platform limitations

---

## Current Project Direction

**Focus**: Complete sente-lite HTTP/1.1 + WebSocket implementation

**Core Features to Build**:
1. Connection management and lifecycle
2. Message routing and dispatch
3. Channel system (pub/sub)
4. Transit multiplexing
5. Wire format handling
6. nREPL integration (bencode over WebSocket)

**Target Platforms** (unchanged):
- ✅ Babashka (server): http-kit + HTTP/1.1 + WebSocket
- ✅ Browser (Scittle): Native WebSocket API
- ✅ Node.js (nbb): ws npm package
- ✅ JVM Clojure: http-kit (can revisit jetty9 later if needed)

**Philosophy** (reaffirmed):
- Native capability first
- ~500 LOC simplicity
- ~85% Sente API compatibility
- Lightweight, minimal dependencies

---

## References

### Related Documents
- `doc/ring-jetty9-adapter-migration-plan.md` - Original 7-phase migration plan
- `doc/plan.md` - Overall sente-lite implementation plan
- `CLAUDE.md` - Project context and guidelines

### External Links
- [http-kit Issue #299: HTTP2 Support](https://github.com/http-kit/http-kit/issues/299)
- [babashka/http-client](https://github.com/babashka/http-client) - Client-only library
- [ring-jetty9-adapter](https://github.com/sunng87/ring-jetty9-adapter) - JVM-only server
- [Babashka Pods](https://github.com/babashka/pods) - Pod protocol documentation

### Tags
- **Investigation Date**: October 2025
- **Status**: On Hold
- **Decision**: Continue with HTTP/1.1 + WebSocket
- **Branch**: jetty9-migration (preserved for reference)

---

## Conclusion

HTTP/2 support for sente-lite is technically possible through exotic patterns (pods, proxies, split architecture), but none align with the project's lightweight, babashka-focused design philosophy.

The decision to pause HTTP/2 efforts and continue with HTTP/1.1 + WebSocket is pragmatic, focusing resources on completing core functionality that works across all target platforms.

This investigation provides a complete reference for future reconsideration if the babashka ecosystem evolves or project priorities change.

**Next Steps**: Return to main branch, continue sente-lite HTTP/1.1 + WebSocket implementation.
