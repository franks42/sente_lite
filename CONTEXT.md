# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-12-18 (Session - Cross-Platform Tests Fixed)

## CURRENT STATUS

**Last Commit**: `2facaf8` - "Fix cross-platform test paths + Sente compat CharBuffer fix"
**Branch**: `main` - 2 commits ahead of origin
**Current Task**: Cross-platform tests - ALL PASSING

### What Was Accomplished This Session (2025-12-18)

#### Cross-Platform Test Runner Fixed
✅ Fixed relative path issues in all cross-platform test scripts
✅ Tests now work when run from any directory
✅ Fixed CharBuffer→String conversion in Sente compat test
✅ Handle Sente buffered event format `[[event1] [event2] ...]`

#### Test Results - ALL 6 PASSING
```
  [PASS] BB Server <-> BB Client (unit test)
  [PASS] BB Server <-> BB Client (multiprocess)
  [PASS] nbb Server <-> nbb Client
  [PASS] BB Server <-> nbb Client
  [PASS] nbb Server <-> BB Client
  [PASS] Sente Server <-> BB Client (sente-lite)
```

### Platform Matrix (Complete)

```
Platform      | Server           | Client              | Tests
--------------|------------------|---------------------|--------
BB (JVM-like) | server.cljc      | client_bb.clj       | ✓ 15 passing
nbb (Node.js) | server_nbb.cljs  | client_scittle.cljs | ✓ 14 passing
Browser       | (N/A)            | client_scittle.cljs | pending Scittle test

Cross-Platform Interoperability:
  BB Server    <-> nbb Client    : ✓ PASSING
  nbb Server   <-> BB Client     : ✓ PASSING
  Sente Server <-> BB Client     : ✓ PASSING (handshake works)
```

### Files Modified This Session

- `test/scripts/cross_platform/run_all_cross_platform_tests.bb` - fixed paths
- `test/scripts/cross_platform/test_bb_server_nbb_client.bb` - fixed paths
- `test/scripts/cross_platform/test_nbb_server_bb_client.bb` - fixed paths
- `test/sente-compat/test_sente_lite_client_to_sente_server.bb` - CharBuffer fix + Sente format

### Source Files (Complete v2 Implementation)

**Server:**
- `src/sente_lite/server.cljc` - BB/JVM server
- `src/sente_lite/server_nbb.cljs` - nbb server (UNIQUE - Sente doesn't support nbb!)

**Client:**
- `src/sente_lite/client_bb.clj` - BB client
- `src/sente_lite/client_scittle.cljs` - Browser/nbb client

**Wire Format:**
- `src/sente_lite/wire_format_v2.cljc` - Sente-compatible v2 format

### TODO List (Remaining)

1. ✅ Cross-platform tests all passing
2. ⬜ Test browser client in actual Scittle (SCI limitations!)
3. ⬜ Add nbb tests to official test suite (run_tests.bb)
4. ⬜ Document nbb as supported platform
5. ⬜ Push commits and tag v2.0.0 release

### How to Run Cross-Platform Tests

```bash
# Run from any directory:
bb test/scripts/cross_platform/run_all_cross_platform_tests.bb

# Individual tests:
bb test/scripts/test_v2_client_bb.bb
bb test/scripts/multiprocess_v2/01_basic_v2.bb
cd test/nbb && nbb --classpath ../../src test_server_nbb_module.cljs
```

### Key Technical Discoveries

**BB WebSocket CharBuffer Issue:**
```clojure
;; babashka.http-client.websocket passes java.nio.HeapCharBuffer, NOT String
;; Must convert: (str raw-data) before edn/read-string
```

**Sente Buffered Events Format:**
```clojure
;; Sente wraps events: [[event1] [event2] ...]
;; Not just [event-id data]
;; Parse needs: (ffirst parsed) for event-id when first element is vector
```

**nbb has js/WebSocket:**
- Node's `ws` package provides browser-compatible WebSocket API
- client_scittle.cljs works unchanged in nbb

### Git History

```
2facaf8 Fix cross-platform test paths + Sente compat CharBuffer fix
25f8bd4 v2 multiprocess tests + cross-platform tests + client_bb.clj reconnect fix
d1d1814 WIP: v2 multiprocess test infrastructure + context update
44478f6 nbb platform support: server + client modules (TAG: v2.0.0-nbb)
455be2d v2 wire format: BB client module and tests (TAG: v2.0.0-bb-client)
```

### Previous Threads

- T-019b3036-deeb-7555-8b98-778f6b057782 (multiprocess v2 tests)
- T-019b300e-da2a-716e-ad2e-b5bcffa01288 (v2 wire format migration)
