# Sente-Lite Project Review (2025-12-21)

## 1. Executive Summary

The `sente-lite` project is a robust, lightweight WebSocket messaging library tailored for "hosted" Clojure environments (Babashka, Scittle, nbb) where the full JVM `core.async` machinery is too heavy or unavailable.

**Key Achievements:**
- **Cross-Platform Core:** Unified event-based messaging protocol working across BB, nbb, and Browser (Scittle/Standard).
- **Recent Innovation:** Successfully implemented an "nREPL over sente" module, allowing nREPL tooling to interact with restricted environments like Scittle via WebSockets.
- **Architecture:** Moved towards a modular design (`modules/` directory) and a loosely-coupled "FQN Registry" for distributed state management.
- **Quality:** High standard of testing (BB-to-BB first strategy, Playwright for browser) and strict linting (zero errors policy).

## 2. Code Functioning Analysis

### 2.1 Core System
- **Server (`server.cljc`)**: Implements a standard WebSocket server pattern with active connection management (`atoms`), heartbeat (ping/pong) to handle stale connections, and a message router.
  - **Connection Lifecycle**: Tracks `opened-at`, `last-activity`, and `last-pong`. Automatically aggressively cleans up dead connections.
  - **Routing**: Uses a standard Sente-compatible vector format `[event-id data]`. It handles system events (`:chsk/*`) internally and delegates others to a user-provided handler or the channel system.
  - **Channel System**: A simple Pub/Sub implementation using in-memory maps to track subscribers.

### 2.2 Client (Scittle/Browser)
- **`client_scittle.cljs`**: A pure CLJS implementation for the browser.
  - **Queueing**: Implements a send queue to handle network backpressure or disconnected states.
  - **Reconnection**: proven exponential backoff strategy for robust connectivity.
  - **Handler Registry**: A unified `on!` / `off!` API that allows registering callbacks for specific events or predicates, simplifying the "callback hell" often found in raw WebSocket handling.

### 2.3 FQN Registry (`sente-lite.registry`)
- A novel approach to state management across checking/processes.
- Uses **local FQNs** (`category/name`) mapped to Clojure primitives (`vars` and `atoms`).
- **Functioning**: Instead of a central "registry object", it uses the runtime's own namespace system (`create-ns`, `intern`) to hold references. This makes "looking up" a value as fast as resolving a Var, and "watching" a value uses standard Clojure `add-watch`.
- **Insight**: This decouples modules; they only need to agree on a naming convention string, not share an object reference.

### 2.4 nREPL Module (`modules/nrepl`)
- **Protocol**: Tunnels nREPL operations (`:op "eval"`, etc.) inside sente messages.
- **Server Functioning**:
  - Receives the message, extracts code.
  - **BB/CLJ**: Uses `clojure.tools.reader` and `eval`. It uses a **global atom** `!last-ns` to track the current namespace (`*ns*`) and applies it via `binding` before evaluation.
  - **Scittle**: Delegates to `scittle.core.eval_string`. It relies on Scittle's internal state to persist namespace changes.
- **Browser Adapter**: Clever workaround for Scittle's async loading. Uses an INLINE script (`type="application/x-scittle"`) to define a `FakeWebSocket` and forces immediate synchronous execution using `scittle.core.eval_script_tags()`. This ensures the fake socket exists before `scittle.nrepl.js` loads.

## 3. Recommendations & Improvements

### 3.1 Architecture / Design

#### ⚠️ Global Namespace State in nREPL Server (Critical)
**Issue**: In `modules/nrepl/src/nrepl_sente/server.cljc`, the `!last-ns` is a single global atom:
```clojure
(defonce !last-ns (atom (find-ns 'user)))
```
**Impact**: If multiple clients connect to the **same** BB server, they share the "current namespace".
- Client A switches to `(in-ns 'foo)`
- Client B executes `(def x 1)` -> This happens in `foo`, not `user`!
**Recommendation**: Move `!last-ns` into the `session` or `connection` context.
- The `handle-eval` function receives a `session` ID.
- Use a `sessions` atom mapping `session-id -> {:current-ns ...}`.
- Look up the correct NS based on the incoming message's session ID.

#### Server Monolith Refactor
**observation**: `src/sente_lite/server.cljc` is approaching 650 lines and handles mixed concerns (HttpKit/NBB adapter, Heartbeat logic, Connection Registry, Routing).
**Recommendation**: Split into:
- `sente_lite.server.core`: Protocol and routing.
- `sente_lite.server.heartbeat`: The background task logic.
- `sente_lite.server.adapters.*`: Platform specific adapters (HttpKit vs NBB).

### 3.2 Code Improvements

#### Scittle `eval_script_tags` Resilience
**Observation**: The browser adapter relies on `scittle.core.eval_script_tags()`.
**Risk**: If Scittle changes how it exposes `core` or `eval_script_tags`, this breaks.
**Recommendation**:
- Wrap this strictly in the documented "Snippet" or a helper function if possible.
- Add a "Canary" test that fails specifically if `eval_script_tags` is missing or behaves differently, with a clear error message (the current `test_browser_adapter.html` effectively serves as this, which is good).

#### Error Handler in Client
**Observation**: `client_scittle.cljs` catches errors in handlers, but sometimes a global "error reporter" is needed (e.g., to send error telemetry back to the server even if the handler crashed).
**Recommendation**: Add a `:on-handler-error` config option to `make-client!` to allow the host app to react to handler failures globally (e.g. show a UI toast).

### 3.3 Documentation

#### "Fake Web Socket" Pattern
The "Inline Script + Forced Eval" pattern is a crucial piece of "tribal knowledge" now.
**Recommendation**: Extract the explanation from `CONTEXT.md` into a permanent `doc/guides/scittle-nrepl-setup.md`. It is too valuable to live only in ephemeral context files.

#### FQN Registry "Intern" Side-Effects
The registry uses `intern` and `create-ns`.
**Recommendation**: Add a note in `doc/fqn-registry-api.md` clarifying that this creates *real* namespaces in the runtime. Users might be surprised to see `sente-lite.registry.state` appearing in `(all-ns)`.

## 4. Conclusion

The project is in excellent shape. The "Lite" philosophy is well-maintained while delivering powerful features like the nREPL tunnel. The most significant architectural actionable is fixing the **Global Namespace State** in the BB nREPL server to support multi-user/multi-session isolation properly.
