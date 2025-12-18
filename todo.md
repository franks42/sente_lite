# sente-lite TODOs

## Completed (v2.1.0)

### v2.1.0 - Scittle Browser Client (2025-12-18)
- [x] Fix SCI/Scittle compatibility in wire_format_v2.cljc
- [x] Fix vector destructuring (use first/second/nth)
- [x] Fix macro usage (trove/log! → log! with :refer)
- [x] Fix cljs.reader import (use read-string directly)
- [x] Create browser test HTML with 16 tests
- [x] Create Playwright automated test runner
- [x] Add Scittle test to cross-platform test runner
- [x] Update documentation (README, plan.md)
- [x] Tag and release v2.1.0

### v2.0.0 - Wire Format & Cross-Platform (2025-12-11)
- [x] Sente-compatible v2 wire format
- [x] nbb server + client support
- [x] Cross-platform test matrix (BB, nbb)
- [x] Sente JVM server interop (BB client)

---

## Future Work

### Priority: Cross-Platform Testing

#### Scittle Client ↔ Sente JVM Server
**Goal:** Verify browser client works with official Sente server

**Tasks:**
- [ ] Create test HTML that connects Scittle client to Sente JVM server
- [ ] Test handshake compatibility (Sente uses buffered events format)
- [ ] Test echo/ping-pong
- [ ] Test subscription/channel messages (if Sente supports)
- [ ] Add to Playwright automated tests
- [ ] Add to cross-platform test runner

**Notes:**
- Sente server runs on JVM Clojure
- May need to handle Sente's specific wire format quirks
- CSRF token handling may be different

#### Scittle Client ↔ nbb Server
**Goal:** Verify browser client works with nbb WebSocket server

**Tasks:**
- [ ] Create test HTML that connects Scittle client to nbb server
- [ ] Test handshake
- [ ] Test echo/subscribe/publish
- [ ] Add to Playwright automated tests
- [ ] Add to cross-platform test runner

**Notes:**
- nbb server uses `server_nbb.cljs`
- Should be simpler than Sente since both use sente-lite wire format

### Platform Matrix Target

```
  Servers        | BB Client | nbb Client | Scittle | Sente
  ---------------+-----------+------------+---------+------
  BB Server      |    [x]    |    [x]     |   [x]   |  [ ]
  nbb Server     |    [x]    |    [x]     |   [ ]   |  [ ]
  Sente Server   |    [x]    |    [ ]     |   [ ]   |  N/A

Legend: [x] = tested & passing, [ ] = not yet implemented
```

### Future Enhancements

#### Legacy/experimental (archived under `src_legacy/`)
- [ ] Evaluate whether any multiplexer work should be ported into the canonical implementation
- [ ] Review `sente-lite.legacy.wire-multiplexer` and decide whether to port a supported envelope-based negotiation format
- [ ] Review `sente-lite.legacy.transit-multiplexer` and decide whether to port Transit tagged-value multiplexing (and if so, how it should integrate with `sente-lite.serialization`)
- [ ] Add tests and docs for any ported multiplexer functionality

#### Wire Format
- [ ] Message compression (for large payloads)
- [ ] Message batching (for high throughput)
- [ ] Binary message support

#### Features
- [ ] Request-response pattern with callbacks
- [ ] Server-initiated push with acknowledgment
- [ ] Connection pooling for BB client

#### Documentation
- [ ] API reference docs
- [ ] Migration guide from Sente
- [ ] Performance benchmarks
