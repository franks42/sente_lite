# Review: Sente-Lite Modules Proposal

**Date**: 2025-12-19
**Reviewer**: Claude (Opus 4.5)
**Document Reviewed**: `doc/sente-lite-modules.md` (4,738 lines)

---

## Executive Summary

The modules proposal is a comprehensive design document covering 5 proposed extensions to sente-lite. The research is thorough with good phased implementation plans, but the scope risks overwhelming the core library. **Recommendation: Prioritize ruthlessly—implement ONE module first (Remote Logging), convert others to documented patterns.**

---

## Document Overview

### Proposed Modules

| Module | Status | Complexity | LOC Estimate |
|--------|--------|------------|--------------|
| Remote Logging via Sente | Proposed | Low-Medium | 50-500+ |
| nREPL-over-Sente | Proposed | Medium | 300-700+ |
| HTTP Blob Transfer | Proposed | Medium | 200-600+ |
| LSP over Sente | Proposed | Medium-High | 300-700+ |
| State Synchronization | Proposed | Low-High | Varies |

**Total potential LOC**: 1,500-3,000+
**Current core LOC**: ~5,100

---

## Strengths

### 1. Comprehensive Analysis
Each module includes:
- Phased implementation (MVP → Production → Enterprise)
- Wire format specifications
- Pros/cons analysis
- File structure proposals
- Configuration examples

### 2. Practical Async Patterns
The nREPL module documents 4 async patterns well:
- Promise-based (simple, but blocking on deref)
- Callback-based (fully async, callback hell risk)
- Channel-based (composable, requires core.async)
- **Persistent Queue in Atom** (recommended for sente-lite)

The queue pattern recommendation is correct given sente-lite's no-core.async constraint.

### 3. Realistic Scope Estimates
LOC estimates appear reasonable:
- Phase 1 MVP: 50-300 LOC per module
- Phase 2 Production: 200-600 LOC
- Phase 3 Enterprise: 500-700+ LOC

### 4. Good Integration Points
Each module shows how to integrate with sente-lite lifecycle hooks:
```clojure
{:on-open (fn [uid] ...)
 :on-close (fn [code reason] ...)
 :on-reconnect (fn [uid] ...)}
```

---

## Concerns

### 1. Scope Creep Risk

**Issue**: 5 modules totaling potentially 3,000+ LOC would nearly double the codebase size.

**Impact**:
- Maintenance burden increases significantly
- Testing surface area expands
- Documentation requirements multiply
- Bug surface area grows

**Recommendation**:
- Keep core library lean (~5K LOC)
- Implement modules as separate, optional packages
- Consider `sente-lite-modules/` as separate namespace

### 2. nREPL Module: Already Solved?

**Issue**: The existing scittle-nrepl-server infrastructure already provides nREPL-over-WebSocket functionality.

**Questions to answer before implementing**:
- What's wrong with the current dedicated nREPL WebSocket?
- Is multiplexing really needed?
- The "head-of-line blocking" concern is real—long evals WILL block other messages

**Recommendation**: Document the existing nREPL approach as the "recommended pattern" rather than building another layer. If multiplexing is truly needed, document the trade-offs clearly.

### 3. HTTP Blob Transfer: Overengineered

**Issue**: The proposal includes SHA256 verification, progress tracking via sente, CDN support, and resumable transfers. Browsers already do most of this natively with `fetch()`.

**The directive pattern adds coordination overhead**:
```clojure
;; Proposed (complex)
[:blob/fetch-and-process
 {:url "..."
  :handler :my/handler
  :metadata {:sha256 "..." :size 12345}
  :options {:retry-count 3 :timeout-ms 30000}}]
```

**Recommendation**: Simplify Phase 1 to just:
```clojure
[:blob/url {:url "..." :handler :my/handler}]
```
Let the browser handle fetch/cache/retry natively. Skip progress-over-sente complexity for MVP.

### 4. LSP Module: Wrong Layer

**Issue**: LSP-over-Sente could work, but:
- LSP is inherently complex (JSON-RPC, initialization, capabilities negotiation)
- Babashka pods provide a simpler integration path
- Existing `lsp-ws-proxy` projects already solve this well

**Better approaches**:
1. Use clojure-lsp as Babashka pod directly
2. Document integration pattern, don't build infrastructure
3. Reference existing tools rather than reinventing

**Recommendation**: Use pods directly. Document the pod pattern as an integration example instead of building LSP tunneling.

### 5. State Sync: Multiple Overlapping Patterns

**Issue**: The module describes 4 different sync patterns without clear guidance on which to use:
- One-way atom syncing
- Two-way syncing
- DataScript instances
- Shadow DOM integration

**Recommendation**: Pick ONE pattern as the "blessed" approach:
- For Reagent users: Reagent atoms (already great, just document)
- For non-Reagent: Plain atoms with watches
- Mark others as "advanced" or defer to Phase 2

---

## Architectural Recommendations

### 1. Keep Core Lean

The current ~5K LOC core is a strength. Don't bloat it with modules.

**Proposed structure**:
```
sente-lite/           # Core library (stable, small)
  src/sente_lite/     # ~5K LOC max

sente-lite-modules/   # Optional add-ons (separate namespace/package)
  src/sente_lite/logging/
  src/sente_lite/blob/
  etc.
```

### 2. Test-Driven Module Development

Apply the "BB-to-BB first, always" rule from CLAUDE.md to modules:
- Remote Logging Phase 1: Write BB test first, then browser
- If it can't be tested in BB alone, question whether it belongs in the library

### 3. Document Patterns, Don't Implement Everything

Some "modules" are better as **documented recipes**:
- "How to do nREPL over sente" (show the pattern, don't add code)
- "How to sync atoms" (examples, not infrastructure)
- "How to use LSP with Babashka pods" (reference existing tools)

**Why?**
- Less code to maintain
- Users can customize to their needs
- Patterns are more flexible than frameworks

### 4. Priority Ordering

| Priority | Module | Rationale |
|----------|--------|-----------|
| 1 | **Remote Logging Phase 1** | Low effort (~100 LOC), high utility, validates sente for observability |
| 2 | State Sync (one-way atoms) | Already a known pattern, just document it well |
| 3 | HTTP Blob (minimal) | Simple URL-only directives, let browser handle the rest |
| 4 | nREPL | Only if existing nREPL infra proves insufficient |
| 5 | LSP | Defer—use pods or external proxies |

---

## Specific Module Recommendations

### Remote Logging (IMPLEMENT FIRST)

**Why first?**
- Smallest scope (Phase 1 is ~50-100 LOC)
- Immediately useful for debugging
- Validates sente for telemetry use cases
- Low risk

**Simplified Phase 1**:
```clojure
;; Client (~20 LOC)
(defn make-remote-log-fn [local-log-fn sente-client]
  (fn [ns coords level id lazy_]
    (local-log-fn ns coords level id lazy_)
    (try
      (sente/send! sente-client
        [:telemetry/log {:ns ns :level level :id id :data (force lazy_)}])
      (catch :default _ nil))))

;; Server (~15 LOC)
(defmethod handle-event :telemetry/log
  [{:keys [data uid]}]
  (trove/log! (assoc data :remote-uid uid)))
```

**Skip for Phase 1**:
- Batching
- Filtering
- Backpressure
- Aggregation

### nREPL-over-Sente (DOCUMENT AS PATTERN)

**Instead of implementing**, create a recipe document:

```markdown
# Recipe: nREPL over Sente

## When to Use
- Need REPL + application messaging on same connection
- Connection count is constrained

## When NOT to Use
- Dedicated nREPL connection is available (simpler)
- Long-running evals would block other messages

## Pattern
[Show existing scittle-nrepl-server approach]
```

### HTTP Blob Transfer (SIMPLIFY)

**Phase 1 should be just**:
```clojure
;; Server sends directive
(send! client [:blob/fetch {:url "..." :handler :my/handler}])

;; Browser handles fetch natively
(defmethod handle-message :blob/fetch [{:keys [data]}]
  (-> (js/fetch (:url data))
      (.then #(.arrayBuffer %))
      (.then #(dispatch-to-handler (:handler data) %))))
```

**Skip for Phase 1**:
- Progress tracking via sente
- SHA256 verification
- Retry logic (browser fetch has this)
- CDN support

### LSP (DON'T IMPLEMENT)

**Instead**, document:
```markdown
# Using LSP with Babashka

## Recommended: Babashka Pods

Load clojure-lsp as a pod:
(pods/load-pod 'com.github.clojure-lsp/clojure-lsp "2022.11.03-00.14.57")

Call LSP API directly from sente handlers.
No proxying needed.

## Alternative: Existing Proxies
- lsp-ws-proxy (Rust, general-purpose)
- codemirror/lsp-client (Browser)
```

### State Sync (DOCUMENT PATTERNS)

**Create a recipes document** with examples:
```markdown
# State Synchronization Patterns

## Pattern 1: Server → Browser (Recommended)
[Show add-watch + sente send example]

## Pattern 2: Reagent Integration
[Show Reagent atom + defmethod handler]

## Pattern 3: Two-Way Sync (Advanced)
[Show conflict resolution approach]
```

---

## Implementation Roadmap

### Phase 1: Foundation (2-3 weeks)
- [ ] Implement Remote Logging MVP (~100 LOC)
- [ ] Create "Recipes" documentation structure
- [ ] Document State Sync patterns (no code, just examples)
- [ ] Test Remote Logging BB-to-BB, then browser

### Phase 2: Documentation (1-2 weeks)
- [ ] nREPL recipe document
- [ ] HTTP Blob recipe document
- [ ] LSP + pods recipe document
- [ ] State Sync patterns document

### Phase 3: Evaluate (ongoing)
- [ ] Gather feedback on Remote Logging
- [ ] Identify if other modules are truly needed
- [ ] Only implement if recipes prove insufficient

---

## Risk Mitigation

### Risk: Scope Creep
**Mitigation**: Commit to "one module at a time" rule. No new module until previous is shipped and stable.

### Risk: Design Paralysis
**Mitigation**: Ship Remote Logging MVP within 2 weeks. Perfect is the enemy of good.

### Risk: Maintenance Burden
**Mitigation**: Keep modules optional. Core library stays at ~5K LOC.

### Risk: Breaking Changes
**Mitigation**: Modules should only ADD to API, never change core behavior.

---

## Summary

The modules proposal demonstrates excellent research and planning. The risk is "design paralysis" from too many options.

**Key recommendations**:
1. **Ship something small and iterate** - Remote Logging Phase 1 first
2. **Document patterns, don't implement everything** - Recipes > Frameworks
3. **Keep core lean** - Modules in separate namespace
4. **Prioritize ruthlessly** - One module at a time

The cousin-AI has done excellent work. Now it's time to execute on the smallest valuable piece.
