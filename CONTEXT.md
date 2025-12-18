# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-12-17 (Session - v2 Wire Format Migration)

## CURRENT STATUS

**Last Commit**: `44478f6` - "nbb platform support: server + client modules"
**Tag**: `v2.0.0-nbb`
**Branch**: `main` - clean working tree
**Current Task**: Porting multiprocess tests to v2 (IN PROGRESS)

### What Was Accomplished This Session (2025-12-17)

#### v2 Wire Format Migration - COMPLETED
✅ **Server v2 (server.cljc)**
- Switched to wire_format_v2.cljc for encoding/decoding
- Updated message routing to use event-ids instead of :type
- Sends Sente-compatible handshake [:chsk/handshake [uid csrf-token data first?]]
- Updated heartbeat and broadcast to v2 event formats

✅ **BB Client v2 (NEW: client_bb.clj)**
- Created complete BB client module with same API as browser client
- CharBuffer → String conversion (documented gotcha for BB websocket)
- Handshake with uid extraction
- Auto ping/pong, subscribe/publish helpers
- Auto-reconnect with exponential backoff
- 15 tests passing

✅ **Browser Client v2 (client_scittle.cljs)**
- Added subscribe!/unsubscribe!/publish!/get-uid helpers
- Fixed on-open called twice issue (now only after handshake with uid)
- Fixed close! race condition (removes client before ws.close())

#### nbb Platform Support - COMPLETED (UNIQUE FEATURE!)
**Official Sente does NOT support nbb - this is a sente-lite exclusive!**

✅ **nbb Server (NEW: server_nbb.cljs)**
- WebSocket server using Node's 'ws' package
- Full v2 wire format support
- Channel/pub-sub support
- Heartbeat with ping/pong
- Same API as server.cljc
- 14 tests passing

✅ **nbb Client**
- client_scittle.cljs works UNCHANGED in nbb
- js/WebSocket available in nbb (polyfilled by ws package)

### Platform Matrix (Current)

```
Platform      | Server           | Client              | Tests
--------------|------------------|---------------------|--------
BB (JVM-like) | server.cljc      | client_bb.clj       | ✓ 15 passing
nbb (Node.js) | server_nbb.cljs  | client_scittle.cljs | ✓ 14 passing
Browser       | (N/A)            | client_scittle.cljs | pending Scittle test
```

### Files Created This Session

**Source files:**
- `src/sente_lite/client_bb.clj` - BB v2 client
- `src/sente_lite/server_nbb.cljs` - nbb v2 server

**Test files:**
- `test/scripts/test_v2_bb_to_bb.bb` - raw websocket v2 test (14 tests)
- `test/scripts/test_v2_client_bb.bb` - BB client module test (15 tests)
- `test/nbb/test_server_nbb_module.cljs` - nbb server + client (14 tests)
- `test/nbb/test_scittle_client.cljs` - client_scittle.cljs in nbb
- `test/nbb/test_nbb_full_stack.cljs` - inline server + client
- `test/nbb/test_v2_nbb_client.cljs` - raw nbb client test
- `test/nbb/test_ws_basic.cljs` - ws module exploration
- `test/nbb/test_browser_api.cljs` - browser API test

**Multiprocess v2 (IN PROGRESS):**
- `test/scripts/multiprocess_v2/mp_server_v2.bb` - v2 server for mp tests
- `test/scripts/multiprocess_v2/mp_client_v2.bb` - v2 client using client_bb.clj

### Current Work: Multiprocess Test Porting

**Status:** IN PROGRESS - basic infrastructure created

**What exists (v1 - still works but uses JSON format):**
- `test/scripts/multiprocess/01_basic_multiprocess.bb`
- `test/scripts/multiprocess/02_ephemeral_reconnection.bb`
- `test/scripts/multiprocess/03_reconnection.bb`
- `test/scripts/multiprocess/04_concurrent_startup.bb`
- `test/scripts/multiprocess/05_process_failure.bb`
- `test/scripts/multiprocess/06_stress_test.bb`

These use `ws_client_managed.clj` with JSON format and `{:type ...}` messages.

**What needs to be created (v2):**
- Port all 6 tests to use `client_bb.clj` with EDN/v2 format
- Created `multiprocess_v2/` directory with:
  - `mp_server_v2.bb` ✓
  - `mp_client_v2.bb` ✓
  - Need: `01_basic_v2.bb`, stress tests, reconnection tests

**Approach:** Rewrite using client_bb.clj (not just port)

### TODO List (Remaining)

1. ⏳ Port multiprocess tests to v2 (IN PROGRESS)
   - Created mp_server_v2.bb, mp_client_v2.bb
   - Need: 01_basic_v2.bb, stress tests, reconnection tests
2. ⬜ Add nbb tests to official test suite
3. ⬜ Document nbb as supported platform
4. ⬜ Test browser client in actual Scittle (SCI limitations!)
5. ⬜ Test sente-lite client connecting to real Sente server
6. ⬜ Document and release v2

### Key Technical Discoveries

**BB WebSocket CharBuffer Issue:**
```clojure
;; babashka.http-client.websocket passes java.nio.HeapCharBuffer, NOT String
;; Must convert: (str raw-data) before edn/read-string
```

**nbb has js/WebSocket:**
- Node's `ws` package provides browser-compatible WebSocket API
- client_scittle.cljs works unchanged in nbb
- `onopen`, `onmessage`, etc. all work

**SCI/Scittle Limitations (for browser testing):**
- NO vector destructuring in function params or let bindings
- Use `(first x)`, `(second x)`, `nth` instead
- See `doc/plan.md` section "⚠️ CRITICAL: SCI/Scittle Limitations"

### Git Tags Created

- `v2.0.0-bb-client` - BB client module + v2 tests
- `v2.0.0-nbb` - nbb platform support

### How to Run Tests

```bash
# BB-to-BB v2 tests
bb test/scripts/test_v2_bb_to_bb.bb
bb test/scripts/test_v2_client_bb.bb

# nbb tests (requires: cd test/nbb && npm install)
cd test/nbb && nbb --classpath ../../src test_server_nbb_module.cljs

# All existing tests (still work, use v1 format)
bb run_tests.bb
```

### Previous Thread

Continue from thread: T-019b300e-da2a-716e-ad2e-b5bcffa01288

Key context from that thread:
- v2 wire format migration plan
- Decided to drop v1 backward compatibility
- Server v2 implementation details
