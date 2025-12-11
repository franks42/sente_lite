# Sente-lite v2 Wire Format Migration

## Overview
Migrate sente-lite to use Sente-compatible wire format for interoperability.

## Tasks

### Phase 1: Sente Test Server Setup
- [x] Create bb.edn with tasks for Sente server/client
- [x] Set up real Sente server (JVM Clojure) for testing
- [x] Create test client that connects to Sente server
- [ ] Verify Sente wire format by capturing messages

### Phase 2: Design Document
- [x] Document Sente wire format (from source analysis)
- [x] Design sente-lite v2 wire format (Sente-compatible)
- [x] Define migration strategy for existing users
- [x] Document backward compatibility approach

### Phase 3: Implementation
- [x] Create v2 wire format encoder/decoder
- [x] Implement Sente event format `[event-id data]`
- [x] Implement callback/reply system with UUID
- [x] Implement system events (`:chsk/*` namespace)
- [x] Add handshake protocol support
- [x] Add ping/pong compatibility
- [x] Create unit tests (9 tests, 82 assertions, all passing)

### Phase 4: Testing
- [x] Unit tests for v2 wire format (9 tests, 82 assertions)
- [ ] Integration tests: sente-lite client ↔ Sente server
- [ ] Integration tests: Sente client ↔ sente-lite server (if applicable)
- [ ] Backward compatibility tests

### Phase 5: Documentation & Release
- [ ] Update documentation
- [ ] Migration guide for existing users
- [ ] Tag and release v2.0

---

## Progress Log

### 2025-12-10
- Started project
- Reviewed existing sente-lite wire format
- Reviewed Sente wire format documentation
- Created todo.md
- Created bb.edn with tasks for Sente server/client
- Created test/sente-compat/ with:
  - deps.edn (Sente dependencies)
  - src/sente_test/server.clj (real Sente server)
  - src/sente_test/client.clj (Sente client for testing)
  - test_wire_format.bb (wire format analysis script)
- Created doc/sente-lite-v2-wire-format-design.md (design document)
- Created src/sente_lite/wire_format_v2.cljc (v2 wire format implementation)
- Created test/sente_lite/wire_format_v2_test.cljc (unit tests)
- All tests passing: 10 tests, 101 assertions
- Added comprehensive Trove telemetry to wire_format_v2.cljc
- Sente test server working with `:csrf-token-fn nil`
- Successfully connected JVM client to Sente server
- Captured actual Sente wire format (see design doc)
- All new code passes clj-kondo (0 errors, 0 warnings) and cljfmt

### Wire Format Research (2025-12-11)
- CSRF disabled at Sente level with `:csrf-token-fn nil`
- Client-id required in URL: `?client-id=UUID`
- Handshake format: `[[:chsk/handshake [uid csrf-token]]]` (wrapped, 2 elements)
- Reply format: `[reply-data cb-uuid]` (not wrapped in `:chsk/reply`)
- Server buffers events in outer vector: `[[event]]`

### V2 Wire Format Updates (2025-12-11)
- Added `parse-handshake` support for 2, 3, and 4 element formats
- Added `make-wire-reply` and `parse-wire-reply` for Sente wire format
- Added `buffered-events?`, `unwrap-buffered-events`, `wrap-buffered-events`
- Tests updated: 10 tests, 101 assertions, all passing
