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

## Actual Telemere Implementation Review

**Date**: 2025-10-31
**Source**: `taoensso.telemere.impl/signal!` macro (200+ lines)

### Key Discovery: Three-Stage Lazy Evaluation

After reviewing Telemere's actual source code, here's how they achieve lazy evaluation:

#### Stage 1: Filter FIRST (Compile-time + Runtime)

```clojure
(defmacro signal! [base-opts opts]
  (let [{:keys [elide? allow?]}
        (sigs/filter-call
          {:ct-call-filter     ct-call-filter    ; ← Compile-time filtering
           :*rt-call-filter* `*rt-call-filter*}  ; ← Runtime filtering
          opts)]

    (if elide?
      run-form  ; ← If filtered at compile-time, return JUST the run-form

      ;; Otherwise proceed to Stage 2...
      )))
```

**Key insight**: Telemere has TWO levels of filtering:
- **Compile-time**: Via `ct-call-filter` - removes code entirely if configured
- **Runtime**: Via `*rt-call-filter*` - checks dynamic vars at runtime

For telemere-lite, we only need **runtime filtering** (simpler).

#### Stage 2: Wrap Everything in Delay

```clojure
(let [signal-delay-form
      `(enc/bound-delay  ; ← Special Encore delay wrapper
         ~do-form

         ;; Stage 3 happens INSIDE this delay...
         (let [~@let-form  ; ← :let bindings evaluated HERE
               signal# ~signal-form]

           ;; Transform and return signal
           (if-let [xfn# ~xfn-form]
             (xfn# signal#)
             (do   signal#))))]

  ;; Dispatch to handlers (forces delay)
  (dispatch-signal! @signal-delay-form))
```

**Key insight**: The ENTIRE signal construction is wrapped in `enc/bound-delay`:
- `:do` form runs
- `:let` bindings evaluated
- `:data` and `:msg` built using :let bindings
- Signal record created
- Transform function applied

**Nothing evaluates until the delay is forced** (when dispatching to handlers).

#### Stage 3: Lazy :let, :data, :msg Inside Delay

```clojure
;; Inside the delay (Stage 2), this is what actually runs:
(let [~@let-form  ; ← :let bindings from user (e.g., [x (expensive-fn)])

      ;; Build signal record using :let bindings
      signal#
      (Signal.
        ~'__inst ~'__uid ~'__ns ~coords ~host-form ~'__thread
        ~sample-form ~'__kind ~'__id ~'__level
        ~ctx-form ~parent-form ~'__root1
        ~data-form   ; ← Can reference :let bindings!
        ~kvs-form
        ~msg-form    ; ← Can reference :let bindings!
        ~error-form
        ...)]

  ;; Return signal (possibly transformed)
  signal#)
```

**Key insight**: `:let` bindings are available when building `:data` and `:msg`.

### Telemere Complexity Analysis

**What makes Telemere complex (200+ lines):**

1. **Compile-time elision** - Removes entire signal calls at compile-time
2. **OpenTelemetry integration** - Span creation, trace context propagation
3. **`:run` form support** - Execute code and capture result/error/timing
4. **CLJ/CLJS differences** - Different signal constructors
5. **Tracing support** - Parent/root tracking, span hierarchies
6. **Advanced transforms** - `:xfn` for signal transformation
7. **`encore.signals` framework** - Dependency on large library

**What telemere-lite needs (much simpler):**

1. ✅ Runtime filtering only (no compile-time elision)
2. ✅ Standard Clojure `delay` (not `enc/bound-delay`)
3. ✅ Simple :let → :data → :msg flow
4. ✅ No tracing, OpenTelemetry, or :run forms
5. ✅ Same code for BB/Scittle (CLJC)
6. ✅ No external framework dependencies

**Estimate**: ~50 lines vs Telemere's 200+ lines

### Can We "Borrow It Almost Literally"?

**Honest answer: NO** - we cannot copy Telemere's code literally.

**Why:**
- Uses `encore.signals` framework (not available in telemere-lite)
- Uses `enc/bound-delay` (custom delay implementation)
- 200+ lines handling tracing, OpenTelemetry, compile-time elision
- Complex signal construction with multiple constructors

**What we CAN borrow:**
- ✅ The PATTERN: filter → delay → :let → :data/:msg
- ✅ The CONCEPT: wrap everything in delay, evaluate :let inside
- ✅ The API: `:let` bindings as vector, same syntax

**What we must implement ourselves:**
- Simple runtime filtering (no compile-time elision needed)
- Standard Clojure `delay` wrapper
- Straightforward signal map construction
- No dependencies on external frameworks

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

## Detailed Implementation Plan

### Step 1: Update `signal!` Macro (Core Change)

Replace the current `signal!` implementation with lazy evaluation:

```clojure
(defmacro signal!
  "Core signal macro with lazy :let evaluation.

  Usage:
    (signal! {:level :info
              :event-id ::my-event
              :let [x (expensive-fn)]  ; ← Only evaluated if signal passes filtering
              :data {:result x}})"
  [opts]
  (let [;; Extract compile-time values
        level     (get opts :level :info)
        event-id  (get opts :event-id)
        ns-str    (str *ns*)

        ;; Extract forms that need lazy evaluation
        let-bindings (get opts :let [])
        data-form    (get opts :data)
        msg-form     (get opts :msg "Event")

        ;; Build filtering check (compile-time, cheap to evaluate)
        should-signal?
        `(and *telemetry-enabled*
              #?(:bb (and (ns-allowed? ~ns-str)
                         (event-id-allowed? ~event-id))
                 :scittle true))]

    ;; CRITICAL: Check filter BEFORE evaluating :let/:data/:msg
    `(when ~should-signal?
       ;; Wrap everything in delay for lazy evaluation
       (let [signal-delay#
             (delay
               ;; INSIDE DELAY: Evaluate :let bindings FIRST
               (let [~@let-bindings

                     ;; Then build signal map using :let bindings
                     signal#
                     {:timestamp (System/currentTimeMillis)
                      :level     ~level
                      :event-id  ~event-id
                      :ns        ~ns-str
                      :file      ~*file*
                      :line      ~(:line (meta &form))
                      :data      ~data-form  ; ← Can use :let bindings
                      :msg       ~msg-form}] ; ← Can use :let bindings

                 signal#))]

         ;; Force delay and dispatch to handlers
         (dispatch-signal! @signal-delay#)))))
```

**Key points:**
1. Filter check happens OUTSIDE the delay (cheap, always runs)
2. ALL expensive work happens INSIDE the delay (only if filter passes)
3. `:let` bindings evaluated BEFORE `:data`/`:msg` (inside delay)
4. Standard Clojure `delay` - no special framework needed

### Step 2: Add Helper Function for Filtering

```clojure
(defn- should-create-signal?
  "Runtime filtering check. Called at compile-time from macro."
  [level event-id ns-str]
  (and *telemetry-enabled*
       #?(:bb (and (ns-allowed? ns-str)
                  (event-id-allowed? event-id))
          :scittle true)))
```

**Note**: This is called at macro expansion time, not runtime. The macro generates code with the check inlined.

### Step 3: Update `event!` Macro (Backward Compatible)

Add new arity with `:let` support while keeping old API:

```clojure
(defmacro event!
  "Event logging macro with lazy evaluation support.

  Two-arg form (OLD - still works, but eager evaluation):
    (event! ::my-event {:data 123})

  Three-arg form (NEW - lazy evaluation):
    (event! ::my-event
      [data (expensive-fn)]  ; :let bindings
      {:result data})        ; :data using bindings"

  ([event-id]
   ;; Event ID only
   `(signal! {:level :info
              :event-id ~event-id}))

  ([event-id data-map]
   ;; OLD STYLE (backward compatible, but eager)
   ;; TODO: Add deprecation warning in future version
   `(signal! {:level :info
              :event-id ~event-id
              :data ~data-map}))

  ([event-id let-bindings data-map]
   ;; NEW STYLE (lazy evaluation via :let)
   `(signal! {:level :info
              :event-id ~event-id
              :let ~let-bindings
              :data ~data-map})))
```

### Step 4: Update Other Logging Macros

Apply same pattern to `log!`, `debug!`, `info!`, `warn!`, `error!`:

```clojure
(defmacro debug!
  "Debug logging with lazy evaluation.

  Two forms:
    (debug! \"Message\")                    ; Simple message
    (debug! [x (expensive)] {:data x})    ; Lazy evaluation"

  ([msg]
   `(signal! {:level :debug :msg ~msg}))

  ([let-bindings data-map]
   `(signal! {:level :debug
              :let ~let-bindings
              :data ~data-map})))
```

### Step 5: Example Migration

**Before (eager evaluation):**
```clojure
(tel/event! ::message-sent
  {:conn-id conn-id
   :event-id (first event)       ; ← ALWAYS evaluated
   :size-bytes (count msg)})     ; ← ALWAYS evaluated
```

**After (lazy evaluation):**
```clojure
(tel/event! ::message-sent
  [event-id' (first event)       ; ← Only if telemetry enabled
   size' (count msg)]            ; ← Only if telemetry enabled
  {:conn-id conn-id              ; conn-id is cheap, no need for :let
   :event-id event-id'
   :size-bytes size'})
```

### Step 6: Performance Characteristics

**When telemetry ENABLED:**
- Filter check: ~50-100ns
- Delay creation: ~20-30ns
- Force delay: ~10-20ns
- :let evaluation: depends on code
- Signal construction: ~100-200ns
- Handler dispatch: depends on handlers
- **Total**: ~180-350ns + :let + handlers (same as before)

**When telemetry DISABLED:**
- Filter check: ~50-100ns
- Early return: ~10-20ns
- **Total**: ~60-120ns (vs current ~300-850ns)

**Speedup when disabled: 3-14x faster!**

### Step 7: Testing Strategy

```clojure
(deftest test-lazy-evaluation
  ;; Track whether expensive-fn was called
  (def expensive-called? (atom false))

  (defn expensive-fn []
    (reset! expensive-called? true)
    (Thread/sleep 100)  ; Simulate expensive work
    42)

  ;; Test 1: When enabled, :let should be evaluated
  (binding [*telemetry-enabled* true]
    (reset! expensive-called? false)
    (tel/event! ::test [x (expensive-fn)] {:result x})
    (is (true? @expensive-called?) "Should evaluate :let when enabled"))

  ;; Test 2: When disabled, :let should NOT be evaluated
  (binding [*telemetry-enabled* false]
    (reset! expensive-called? false)
    (tel/event! ::test [x (expensive-fn)] {:result x})
    (is (false? @expensive-called?) "Should NOT evaluate :let when disabled"))

  ;; Test 3: Backward compatibility (old API still works)
  (binding [*telemetry-enabled* true]
    (reset! expensive-called? false)
    (tel/event! ::test {:result (expensive-fn)})
    (is (true? @expensive-called?) "Old API should still work")))
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

## Implementation Roadmap

### Phase 1: Core Implementation (High Priority)
1. ✅ **Research Telemere's approach** - COMPLETED (2025-10-31)
   - Analyzed actual source code from `taoensso.telemere.impl`
   - Identified three-stage lazy evaluation pattern
   - Confirmed we can adapt the PATTERN but not copy code literally

2. ✅ **Create detailed implementation design** - COMPLETED (2025-10-31)
   - 7-step implementation plan with concrete code
   - Performance analysis (3-14x speedup when disabled)
   - Testing strategy with lazy evaluation verification

3. ⏳ **Implement :let support in `signal!` macro** - NEXT
   - Update core macro with delay wrapper
   - Add filtering BEFORE delay evaluation
   - Keep backward compatibility

4. ⏳ **Add new `event!` arities**
   - Three-arg form with :let support
   - Keep old two-arg form (backward compatible)
   - Add deprecation note for future

5. ⏳ **Update other logging macros**
   - `debug!`, `info!`, `warn!`, `error!`
   - Same pattern as `event!`

### Phase 2: Testing & Validation
6. ⏳ **Test lazy evaluation behavior**
   - Verify :let NOT evaluated when disabled
   - Verify :let IS evaluated when enabled
   - Backward compatibility tests

7. ⏳ **Performance benchmarks**
   - Before/after overhead when disabled
   - Confirm 3-14x speedup target
   - Test with real workloads

### Phase 3: Migration
8. ⏳ **Update high-frequency call sites**
   - Message routing events
   - Dispatch events
   - Connection lifecycle events

9. ⏳ **Document migration guide**
   - Examples of old → new API
   - When to use :let vs simple data
   - Performance tips

### Phase 4: Future (Next Major Version)
10. ⏳ **Deprecate old API** (optional)
    - Add deprecation warnings to eager evaluation forms
    - Plan removal timeline
    - Consider compile-time warnings

## References

- [Telemere FAQ - Lazy Evaluation](https://github.com/taoensso/telemere/wiki/6-FAQ)
- [Telemere Examples - :let usage](https://github.com/taoensso/telemere/blob/master/examples.cljc)
- [Telemere Source - `impl/signal!` macro](https://github.com/taoensso/telemere/blob/master/main/src/taoensso/telemere/impl.cljc)
- Official Telemere: "Signal messages are always lazy (as are a signal's :let and :data options)"

## Summary

**Key Finding**: Telemere uses a three-stage lazy evaluation pattern:
1. **Filter FIRST** - Cheap runtime check (compile-time optional)
2. **Delay wrapper** - Wrap all expensive work in standard `delay`
3. **Lazy :let/:data/:msg** - Evaluated inside delay, only if filter passes

**Implementation complexity**:
- ✅ Telemere: 200+ lines (tracing, OpenTelemetry, compile-time elision, encore.signals)
- ✅ telemere-lite: ~50 lines (simple runtime filtering, standard delay, no dependencies)

**We CAN borrow**:
- ✅ The PATTERN (filter → delay → :let → data/msg)
- ✅ The API (`:let [x (expensive-fn)]` syntax)
- ✅ The CONCEPT (lazy post-filter evaluation)

**We CANNOT borrow**:
- ❌ The literal CODE (uses encore.signals framework)
- ❌ Compile-time elision (too complex for our needs)
- ❌ OpenTelemetry integration (out of scope)

**Performance impact**:
- When disabled: 3-14x faster (~60-120ns vs ~300-850ns)
- When enabled: ~same as current (~180-350ns + handlers)

**Backward compatibility**: ✅ Old API continues to work (eager evaluation)

---

**Status**: ✅ Research & Design Complete (2025-10-31)
**Next**: Implementation (Phase 1, Steps 3-5)
**Priority**: High (enables production telemetry with minimal overhead)
**Estimated effort**: 4-6 hours (implementation + testing)
