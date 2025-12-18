# Project Review Record (2025-12-18)

## Snapshot

- **Repository:** `sente-lite`
- **Branch:** `main`
- **Assumption:** Current `main` matches what docs refer to as `v2.0.0`.
- **Goal:** Treat the current implementation as the **first and only Sente-compatible communication implementation** (remove “v2 wire format” framing), while preserving older/experimental work (scaling + binary/multiplexing) in a separate directory.

## What sente-lite does

`sente-lite` is a lightweight WebSocket library aimed at Clojure environments where official Sente is not practical:

- **Babashka (BB):** server + client
- **nbb (Node Babashka):** server + client
- **Browser (Scittle/SCI):** client (Playwright-tested)
- **JVM Clojure:** primarily for testing/interoperability with real Sente

Core design:

- Event messages are expressed as **Sente-compatible event vectors**: `[:event/id data]`
- Includes key Sente system events such as `:chsk/handshake`, `:chsk/ws-ping`, `:chsk/ws-pong`
- Provides a callback-style API (no `core.async` requirement)

## Main entry points (current)

- `src/sente_lite/server.cljc`
  - Primary BB server implementation.
  - Connection lifecycle, heartbeat ping/pong, message routing.
  - Integrates with channel system and wire format.

- `src/sente_lite/client_bb.clj`
  - Babashka client using `babashka.http-client.websocket`.

- `src/sente_lite/client_scittle.cljs`
  - Client for browser/Scittle and also used in nbb context.
  - Handles handshake, auto-pong, reconnection with backoff.

- `src/sente_lite/server_nbb.cljs`
  - nbb server implementation using the `ws` npm package.

- `src/sente_lite/channels.cljc`
  - Pub/sub channel system (subscriptions + publish), with some RPC request tracking.

## Wire format

The project currently uses a Sente-compatible wire encoding centered around `[:event-id data]` (plus callback form `[[event-id data] cb-uuid]`).

Key constraints for SCI/Scittle compatibility (already handled in the current code):

- No vector destructuring in bindings/params
- Macros must be referred (e.g. `(log! ...)` rather than `(trove/log! ...)`)
- Use `read-string` directly (SCI) instead of `cljs.reader`

## Logging / observability

- Project is migrated to **Trove** (`trove/log!` pattern), matching upstream Sente’s direction.
- Vendored Trove sources live under `src/taoensso/`.

### Linting policy (strict)

User requirement: **0 errors and 0 warnings** from `clj-kondo` and `cljfmt`.

To keep strictness while avoiding noise from vendored code:

- `.clj-kondo/config.edn` excludes:
  - `src/taoensso/`
  - `test/taoensso/`

This ensures clj-kondo warnings in vendored code do not block the project’s “0 warnings” requirement.

## Tests (what exists and how to run)

Primary tasks (top-level `bb.edn`):

- `bb lint` → `clj-kondo --lint src test`
- `bb fmt-check` → `cljfmt check src test`
- `bb check` → lint + fmt-check
- `bb test` → runs `run_tests.bb` (unit + integration + optional nbb tests)
- `bb test-wire` → `test/sente-compat/test_wire_format.bb`

`bb test` currently runs:

1. **Unit:** `taoensso.trove-tests`
2. **Integration:** multi-process script (currently named with “v2”)
3. **nbb tests:** executed if `test/nbb/node_modules/ws` exists

Browser/Scittle tests live in `dev/scittle-demo/` and include Playwright automation.

## Findings / current state

- Cross-platform functionality is implemented:
  - BB server/client
  - nbb server/client
  - Browser/Scittle client
- A significant body of test infrastructure exists (unit + multiprocess + nbb + browser).
- Some older/experimental modules exist for alternative wire formats and multiplexing.

## Risks / cleanup items observed

- Naming still heavily uses “v2” in files/tests/docs, despite being the canonical implementation.
- Legacy/experimental code paths (e.g. multiplexers, alternate serialization) should be separated so they do not complicate the canonical implementation.
- Large log artifacts may exist in the repo and should be ignored/rotated.

## Agreed roadmap (high level)

### Phase 1: Canonicalization (“remove v2” framing) + archive legacy work

- Remove “v2 wire format” wording from docs and tests.
- Rename/relocate canonical wire module so it is the only Sente-compatible implementation.
- Move legacy/scaling/binary/multiplexer-related code into a separate directory (not on main classpath).
- Add TODOs to port desired scaling/binary features into canonical implementation.

### Phase 2: Production hardening (priority)

- Enforce limits: max message size, max connections, channel limits.
- Harden lifecycle: heartbeat correctness, cleanup on disconnect, failure policies.
- Improve observability for production: actionable stats, consistent event IDs.
- Add soak/stress test tier as a release gate.

### Phase 3: Compatibility expansion

- Expand API surface and semantics toward more drop-in Sente compatibility.
- Expand Sente interop tests as acceptance criteria.

## Versioning strategy

- Keep release tags as-is (e.g. `v2.0.0`, `v2.0.1`), but remove “v2 wire format” language.
- Patch releases for hardening/cleanup; minor releases for feature/compat expansions.
