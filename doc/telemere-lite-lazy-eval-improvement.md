# telemere-lite Lazy Evaluation Improvement

**Date Created**: 2025-10-31
**Status**: Design proposal (not yet implemented)
**Related**: TODO item for performance improvement

## Problem

Current telemere-lite implementation evaluates ALL arguments BEFORE checking if telemetry is enabled/filtered:

```clojure
;; CURRENT IMPLEMENTATION (NOT lightweight when disabled):
(defmacro event! [event-id data]
  `(signal! {:level :info
             :msg "Event"
             :event-id ~event-id
             :data ~data}))  ; ← data evaluated BEFORE filtering check!

;; Example call:
(tel/event! ::message-sent
  {:conn-id conn-id           ; ← ALWAYS evaluated
   :event-id (first event)    ; ← ALWAYS evaluated
   :size-bytes (count msg)})  ; ← ALWAYS evaluated (expensive!)
```

**Performance overhead when disabled**: ~300-850ns per call
- Map creation: ~200-500ns
- Function calls (count, first, etc.): ~50-200ns
- Check + return: ~50-150ns

**This is significant** for high-frequency calls (message routing, dispatch).

## How Official Telemere Solves This

Telemere uses **lazy evaluation with :let bindings**:

### Key Pattern from Telemere:

```clojure
;; ✅ Telemere's approach - lazy evaluation via :let
(tel/signal!
  {:level :info
   :let [expensive (reduce * (range 1 12))]  ; ← Only eval if signal passes filtering!
   :data {:my-metric expensive}
   :msg ["Message with metric:" expensive]})
```

**How it works:**
1. **Filtering happens FIRST** - Check level, namespace, event-id filters
2. **:let bindings evaluated SECOND** - Only if signal passes filtering
3. **:data and :msg built THIRD** - Using :let bindings
4. **Handlers called LAST** - With fully constructed signal

### Telemere Benefits:

- ✅ **Zero overhead when filtered** - Only pay for filtering check (~50-100ns)
- ✅ **Lazy :let bindings** - Expensive computations deferred
- ✅ **Lazy :data construction** - Map building deferred
- ✅ **Lazy :msg construction** - String formatting deferred
- ✅ **Post-filter evaluation** - After sampling, rate limiting, etc.

**From Telemere docs:**
> "Signal messages are always lazy (as are a signal's :let and :data options), so you only pay the cost of arg prep and message building **if/when a signal is actually created** (i.e. after filtering, sampling, rate limiting, etc.)."

## Proposed Solution for telemere-lite

### Option 1: Adopt Telemere's :let Pattern (Recommended)

```clojure
;; New API - matches Telemere style
(defmacro event!
  "Event logging with lazy evaluation via :let bindings"
  ([event-id]
   `(signal! {:level :info
              :event-id ~event-id}))
  ([event-id let-bindings data]
   `(signal! {:level :info
              :event-id ~event-id
              :let ~let-bindings  ; ← Deferred until after filtering
              :data ~data})))

;; Usage:
(tel/event! ::message-sent
  [conn-id' conn-id               ; :let bindings
   event-id' (first event)
   size-bytes' (count msg)]
  {:conn-id conn-id'              ; :data using bindings
   :event-id event-id'
   :size-bytes size-bytes'})

;; When disabled/filtered: NO evaluation of first, count, map creation!
```

### Option 2: Runtime Check in Macro (Simpler, less powerful)

```clojure
;; Add early check in macro expansion
(defmacro event! [event-id data]
  `(when (and *telemetry-enabled*
              (ns-allowed? ~(str *ns*))
              (event-id-allowed? ~event-id))
     (signal! {:level :info
               :msg "Event"
               :event-id ~event-id
               :data ~data})))

;; Benefit: Simple change to existing API
;; Drawback: Still evaluates 'data' argument before check
```

### Option 3: Delay Wrapper (Middle ground)

```clojure
;; Wrap data in delay
(defmacro event! [event-id data]
  `(signal! {:level :info
             :event-id ~event-id
             :data (delay ~data)}))  ; ← Force in signal! after filtering

;; In signal!:
(when (and *telemetry-enabled* (filters-pass? ...))
  (let [actual-data (if (delay? data) @data data)]
    ...))

;; Benefit: Compatible with existing call sites
;; Drawback: Adds delay overhead (~50ns)
```

## Recommendation

**Use Option 1 - Adopt Telemere's :let Pattern**

**Why:**
- ✅ **Zero overhead when disabled** - No argument evaluation
- ✅ **Matches Telemere API** - Easier migration path if we ever switch
- ✅ **Most flexible** - Supports sampling, rate limiting, etc.
- ✅ **Explicit about cost** - Forces developer to think about expensive operations
- ✅ **Cleaner** - No delay wrappers, no extra runtime checks

**Migration strategy:**
1. Add new `:let` support to `signal!` macro
2. Create new `event!` arities with :let support
3. Keep old arities for backward compatibility (with deprecation warning)
4. Update high-frequency call sites first (message routing, dispatch)
5. Eventually remove old arities in next major version

## Implementation Sketch

```clojure
(defmacro signal!
  "Core signal macro with lazy :let evaluation"
  [opts]
  `(when (should-create-signal? ~opts)  ; ← Filter FIRST
     (let [~@(:let opts)]                ; ← Evaluate :let SECOND
       (create-and-send-signal!          ; ← Build + send THIRD
         (assoc ~opts :data ~(:data opts) :msg ~(:msg opts))))))

(defn should-create-signal?
  "Check if signal should be created (filtering, sampling, rate limiting)"
  [opts]
  (and *telemetry-enabled*
       (level-allowed? (:level opts))
       (ns-allowed? (::ns opts))
       (event-id-allowed? (:event-id opts))
       ;; Future: sampling, rate limiting, etc.
       ))

;; High-level API:
(defmacro event!
  ([event-id]
   `(signal! {:level :info :event-id ~event-id
              :ns ~(str *ns*) :file ~*file* :line ~(:line (meta &form))}))
  ([event-id data-map]
   ;; Old style - for backward compatibility (deprecated)
   `(signal! {:level :info :event-id ~event-id :data ~data-map
              :ns ~(str *ns*) :file ~*file* :line ~(:line (meta &form))}))
  ([event-id let-bindings data-map]
   ;; New style - with lazy :let
   `(signal! {:level :info :event-id ~event-id
              :let ~let-bindings :data ~data-map
              :ns ~(str *ns*) :file ~*file* :line ~(:line (meta &form))})))
```

## Performance Impact

**Before (current):**
```clojure
(tel/event! ::message-sent {:conn-id conn-id :size (count msg)})
;; When disabled: ~300-850ns (map creation + count + check)
```

**After (with :let):**
```clojure
(tel/event! ::message-sent
  [size (count msg)]
  {:conn-id conn-id :size size})
;; When disabled: ~50-100ns (just filtering check!)
```

**Speedup when disabled: 3-17x faster!**

## Next Steps

1. ✅ Research Telemere's approach (DONE)
2. Create detailed implementation design
3. Implement :let support in signal! macro
4. Add new event! arities
5. Test performance (before/after benchmarks)
6. Update high-frequency call sites
7. Document migration guide
8. Deprecate old API (future major version)

## References

- [Telemere FAQ - Lazy Evaluation](https://github.com/taoensso/telemere/wiki/6-FAQ)
- [Telemere Examples - :let usage](https://github.com/taoensso/telemere/blob/master/examples.cljc)
- Official Telemere: "Signal messages are always lazy (as are a signal's :let and :data options)"

---

**Status**: Design complete - awaiting implementation approval
**Priority**: Medium-High (impacts production performance)
**Estimated effort**: 4-8 hours (implementation + testing + migration)
