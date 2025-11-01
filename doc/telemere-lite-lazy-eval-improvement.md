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

## Implementation Roadmap (Living Document)

**Last Updated**: 2025-10-31
**Status**: ✅ Research & Design Complete → Ready for Implementation

### Phase 1: Unified CLJC Foundation (HIGH PRIORITY - Do Before Sente-Lite Refactoring!)

**Goal**: Single codebase for BB + browser with lazy evaluation

#### Step 1.1: Consolidate to SINGLE CLJC File ⏳ NEXT

**CRITICAL CONSTRAINT**: Browser needs ONE downloadable file!

Current setup:
```
dev/scittle-demo/telemere-lite.cljs -> ../../src/telemere_lite/scittle.cljs
                                      (symlink to single file)
```

Browser loads via:
```html
<script src="telemere-lite.cljs" type="application/x-scittle"></script>
```

**Strategy**: Build from scratch, copy useful code back in!

**IMPORTANT**: DO NOT edit existing files directly (error-prone). Instead:

1. [ ] Read `src/telemere_lite/core.cljc` (BB version)
2. [ ] Read `src/telemere_lite/scittle.cljs` (browser version)
3. [ ] Create NEW EMPTY file: `src/telemere_lite/core_new.cljc`
4. [ ] Build unified implementation in `core_new.cljc`:
   - [ ] Start with namespace declaration
   - [ ] Add platform-agnostic code (works everywhere)
   - [ ] Add BB-specific code with `#?(:bb ...)`
   - [ ] Add browser-specific code with `#?(:cljs ...)`
   - [ ] Copy useful functions from old files
5. [ ] Test `core_new.cljc` in BB
6. [ ] Test `core_new.cljc` in browser (via symlink)
7. [ ] When verified working:
   - [ ] Rename `core.cljc` → `core_old.cljc` (backup)
   - [ ] Rename `core_new.cljc` → `core.cljc`
   - [ ] Delete `scittle.cljs` (replaced)
   - [ ] Update symlink: `dev/scittle-demo/telemere-lite.cljs -> ../../src/telemere_lite/core.cljc`
   - [ ] Commit working version
   - [ ] Delete `core_old.cljc` (cleanup)

**Files:**
- `src/telemere_lite/core_new.cljc` - BUILD from scratch (SAFER!)
- `src/telemere_lite/core.cljc` - Keep as reference, rename later
- `src/telemere_lite/scittle.cljs` - Keep as reference, delete later
- `dev/scittle-demo/telemere-lite.cljs` - UPDATE symlink after testing

**Platform differences (all in ONE file with #?):**
```clojure
;; Timestamp
(defn now []
  #?(:bb  (System/currentTimeMillis)
     :cljs (.toISOString (js/Date.))))

;; JSON (browser only)
#?(:cljs
   (defn signal->json [signal]
     (js/JSON.stringify (clj->js signal))))

;; BB: Multiple handlers in atom
#?(:bb
   (defonce *handlers* (atom {})))

;; Browser: THREE default event sinks
#?(:cljs
   (do
     ;; Sink 1: Console (development/debugging)
     (defonce *console-enabled* (atom true))

     ;; Sink 2: Atom (testing/programmatic access)
     (defonce *events* (atom []))
     (defonce *atom-sink-enabled* (atom false))

     ;; Sink 3: WebSocket to server (centralized telemetry)
     (defonce *send-fn* (atom nil))  ; Set by sente-lite client
     (defonce *remote-sink-enabled* (atom false))

     (defn dispatch-signal! [signal]
       ;; Sink 1: Console
       (when @*console-enabled*
         (js/console.log (signal->json signal)))

       ;; Sink 2: Atom (for testing)
       (when @*atom-sink-enabled*
         (swap! *events* conj signal))

       ;; Sink 3: WebSocket (centralized telemetry)
       (when (and @*remote-sink-enabled* @*send-fn*)
         (@*send-fn* [:telemetry/event (assoc signal :source :browser)])))

     ;; Public API for browser sink control
     (defn enable-console-sink! [] (reset! *console-enabled* true))
     (defn disable-console-sink! [] (reset! *console-enabled* false))

     (defn enable-atom-sink! [] (reset! *atom-sink-enabled* true))
     (defn disable-atom-sink! [] (reset! *atom-sink-enabled* false))
     (defn get-events [] @*events*)
     (defn clear-events! [] (reset! *events* []))

     (defn enable-remote-sink! [send-fn]
       (reset! *send-fn* send-fn)
       (reset! *remote-sink-enabled* true))
     (defn disable-remote-sink! [] (reset! *remote-sink-enabled* false))))
```

**Browser Sink Usage Examples:**

```clojure
;; Development: Console only (default)
(tel/event! ::user-clicked {:button "submit"})
;; → Browser console: [info] Event {...}

;; Testing: Enable atom sink
(tel/enable-atom-sink!)
(tel/event! ::test-event {:data 123})
(count (tel/get-events))  ; → 1
(tel/clear-events!)

;; Production: Send to server via WebSocket
(let [send-fn (:send-fn @chsk)]  ; From sente-lite client
  (tel/enable-remote-sink! send-fn)
  (tel/disable-console-sink!)  ; Reduce browser noise

  ;; All telemetry now goes to server
  (tel/event! ::user-login {:user-id "abc123"}))
;; → Server receives: [:telemetry/event {:source :browser :event-id ::user-login ...}]

;; All three sinks simultaneously (e.g., debugging production)
(tel/enable-console-sink!)
(tel/enable-atom-sink!)
(tel/enable-remote-sink! send-fn)
(tel/event! ::debug-this {:complex "data"})
;; → Console: logged
;; → Atom: collected
;; → Server: received
```

**Benefits:**
- ✅ ONE file for both platforms
- ✅ Browser can download as single .cljs
- ✅ BB can use same file
- ✅ No duplication
- ✅ Lazy eval works identically everywhere
- ✅ THREE browser sinks: console (dev), atom (test), websocket (production)
- ✅ Centralized telemetry ready out-of-the-box

#### Step 1.2: Implement Lazy `signal!` Macro ⏳
- [ ] Add filtering check OUTSIDE delay
- [ ] Wrap signal construction in `delay`
- [ ] Put `:let` bindings INSIDE delay
- [ ] Add BB dispatch (handlers in atom)
- [ ] Add browser dispatch (THREE sinks)
  - [ ] Console sink (enabled by default)
  - [ ] Atom sink (for testing)
  - [ ] WebSocket sink (for centralized telemetry)
- [ ] Test macro expansion in both BB and Scittle

**Code location**: `src/telemere_lite/core.cljc` (lines ~150-200)

**Browser sink controls to implement:**
```clojure
;; Enable/disable individual sinks
(enable-console-sink!) / (disable-console-sink!)
(enable-atom-sink!) / (disable-atom-sink!)
(enable-remote-sink! send-fn) / (disable-remote-sink!)

;; Atom sink helpers
(get-events)  ; → vector of all collected events
(clear-events!)  ; → reset to []
```

**Acceptance criteria:**
- ✅ Macro expands correctly in BB
- ✅ Macro expands correctly in Scittle
- ✅ Args NOT evaluated when `*telemetry-enabled*` = false
- ✅ Args ARE evaluated when `*telemetry-enabled*` = true

#### Step 1.3: Update High-Level Macros ⏳
- [ ] Add 3-arg arity to `event!` with :let support
- [ ] Keep 2-arg arity for backward compatibility
- [ ] Add 2-arg arity to `log!` with :let support
- [ ] Update `debug!`, `info!`, `warn!`, `error!`

**Code location**: `src/telemere_lite/core.cljc` (lines ~250-350)

**New API:**
```clojure
;; Old (still works, backward compatible)
(event! ::msg-sent {:size (count msg)})

;; New (lazy evaluation)
(event! ::msg-sent [size (count msg)] {:size size})
```

### Phase 2: Testing & Validation ⏳

#### Step 2.1: Unit Tests (Lazy Evaluation)
- [ ] Test: :let NOT evaluated when disabled (BB)
- [ ] Test: :let NOT evaluated when disabled (browser)
- [ ] Test: :let IS evaluated when enabled (BB)
- [ ] Test: :let IS evaluated when enabled (browser)
- [ ] Test: Backward compatibility (old API still works)

**Test file**: `test/telemere_lite/lazy_eval_test.clj`

#### Step 2.2: Performance Benchmarks
- [ ] Measure current overhead when disabled
- [ ] Measure new overhead when disabled
- [ ] Confirm 3-14x speedup target
- [ ] Test with realistic message routing workload

**Benchmark file**: `test/scripts/benchmark_lazy_eval.bb`

**Target metrics:**
- Current: ~300-850ns when disabled
- New: ~60-120ns when disabled
- Speedup: 3-14x

#### Step 2.3: Cross-Platform Integration Tests
- [ ] Test BB-to-BB telemetry with lazy eval
- [ ] Test browser console output with lazy eval
- [ ] Test centralized telemetry (browser → BB server)
- [ ] Verify no regressions in existing demos

**Demo files to test:**
- `dev/scittle-demo/` - Browser telemetry
- Sente demos (once we integrate)

### Phase 3: Integration with Sente-Lite 🎯 CRITICAL

**Timing**: Do Phase 1 & 2 BEFORE next sente-lite refactoring

#### Step 3.1: Bake Telemetry into Sente-Lite
- [ ] Add lazy telemetry calls to connection lifecycle
- [ ] Add lazy telemetry to message dispatch
- [ ] Add lazy telemetry to event routing
- [ ] Add lazy telemetry to heartbeat/health

**Strategy**: Use new 3-arg `event!` form with :let for ALL calls

**Example:**
```clojure
;; In sente-lite message dispatch
(tel/event! ::message-received
  [size (count msg)
   event-id (first msg)]
  {:conn-id conn-id
   :event-id event-id
   :size-bytes size})
```

#### Step 3.2: Update Documentation
- [ ] Add migration guide (old → new API)
- [ ] Document when to use :let vs simple data
- [ ] Add performance tips
- [ ] Update API documentation

**Doc file**: `doc/telemere-lite-usage.md` (create)

### Phase 4: Future Enhancements (Optional) 🔮

#### Step 4.1: Advanced Filtering (Later)
- [ ] Add sampling support
- [ ] Add rate limiting
- [ ] Add dynamic filter configuration

#### Step 4.2: Deprecation Path (Next Major Version)
- [ ] Add deprecation warnings to 2-arg eager forms
- [ ] Plan timeline for removal
- [ ] Consider compile-time warnings

---

## Current Status Summary

**Completed:**
- ✅ Research Telemere source code (2025-10-31)
- ✅ Design unified CLJC approach (2025-10-31)
- ✅ Verify SCI delay support (2025-10-31)
- ✅ Document implementation plan (2025-10-31)

**Next Task (In Order):**
1. 🎯 Step 1.1: Consolidate to CLJC (~1 hour)
2. 🎯 Step 1.2: Implement lazy signal! macro (~2 hours)
3. 🎯 Step 1.3: Update high-level macros (~1 hour)
4. 🎯 Step 2.1-2.3: Testing & validation (~2 hours)

**Estimated Total Effort**: ~6 hours
**Priority**: HIGH - Do before next sente-lite refactoring
**Risk**: LOW - Well-defined pattern, proven in Telemere

## References

- [Telemere FAQ - Lazy Evaluation](https://github.com/taoensso/telemere/wiki/6-FAQ)
- [Telemere Examples - :let usage](https://github.com/taoensso/telemere/blob/master/examples.cljc)
- [Telemere Source - `impl/signal!` macro](https://github.com/taoensso/telemere/blob/master/main/src/taoensso/telemere/impl.cljc)
- Official Telemere: "Signal messages are always lazy (as are a signal's :let and :data options)"

## Unified CLJC Implementation (BB + Browser)

**CRITICAL DISCOVERY** (2025-10-31): Both BB and Scittle/browser support macros + delay!

### Current State Analysis

**BB implementation** (`src/telemere_lite/core.cljc`):
- ✅ Already uses macros
- ❌ Eager evaluation (evaluates args before filtering)
- Current: `event!`, `log!`, `debug!`, `info!`, `warn!`, `error!`

**Browser implementation** (`src/telemere_lite/scittle.cljs`):
- ✅ Already has `log!` macro (lines 29-49)
- ❌ Also has eager function versions (lines 59-62)
- ❌ Same eager evaluation problem

**Key insight**: We can use IDENTICAL lazy eval pattern for both!

### Why CLJC Works

1. **Macros**: Both BB and Scittle support macro expansion
2. **Delay**: SCI (Small Clojure Interpreter) supports `delay` primitive
3. **Reader conditionals**: Use `#?(:bb ... :cljs ...)` for platform differences

### CLJC Implementation Strategy

**Single source of truth**: `src/telemere_lite/core.cljc`

```clojure
(ns telemere-lite.core
  "Unified telemetry for BB and browser with lazy evaluation")

;; Platform-specific helpers
(defn now []
  #?(:bb  (System/currentTimeMillis)
     :cljs (.toISOString (js/Date.))))

(defn dispatch-signal! [signal]
  #?(:bb  (call-handlers! @*handlers* signal)
     :cljs (js/console.log (signal->json signal))))

;; SHARED lazy eval macro (works on both platforms!)
(defmacro signal!
  "Core signal macro with lazy :let evaluation.
  Works identically in BB and browser/Scittle."
  [opts]
  (let [level     (get opts :level :info)
        event-id  (get opts :event-id)
        ns-str    (str *ns*)

        let-bindings (get opts :let [])
        data-form    (get opts :data)
        msg-form     (get opts :msg "Event")

        ;; Platform-specific filtering
        should-signal?
        `(and *telemetry-enabled*
              #?(:bb (and (ns-allowed? ~ns-str)
                         (event-id-allowed? ~event-id))
                 :cljs true))]  ; ← Browser: simpler filtering

    `(when ~should-signal?
       (let [signal-delay#
             (delay
               (let [~@let-bindings
                     signal#
                     {:timestamp (now)
                      :level     ~level
                      :event-id  ~event-id
                      :ns        ~ns-str
                      :data      ~data-form
                      :msg       ~msg-form}]
                 signal#))]

         (dispatch-signal! @signal-delay#)))))

;; SHARED high-level macros
(defmacro event!
  ([event-id]
   `(signal! {:level :info :event-id ~event-id}))
  ([event-id data-map]
   ;; Old style (backward compatible)
   `(signal! {:level :info :event-id ~event-id :data ~data-map}))
  ([event-id let-bindings data-map]
   ;; New style (lazy)
   `(signal! {:level :info :event-id ~event-id
              :let ~let-bindings :data ~data-map})))
```

### Migration Plan

**Phase 1: Consolidation**
1. ✅ Verify SCI delay support (DONE)
2. Keep `core.cljc` for BB implementation
3. Delete `scittle.cljs` (replace with CLJC)
4. Add platform-specific helpers with `#?` reader conditionals

**Phase 2: Implementation**
- Add lazy eval to unified `signal!` macro
- Test in BB environment
- Test in Scittle/browser environment
- Verify identical behavior on both platforms

**Phase 3: Benefits**
- ✅ ONE implementation (no duplication)
- ✅ Guaranteed consistency (same API everywhere)
- ✅ Same performance gains (3-14x speedup when disabled)
- ✅ Easier maintenance (single codebase)

### Platform Differences (Minimal)

**What's platform-specific:**
```clojure
;; Timestamp
#?(:bb  (System/currentTimeMillis)
   :cljs (.toISOString (js/Date.)))

;; Output
#?(:bb  (call-handlers! @*handlers* signal)
   :cljs (js/console.log (signal->json signal)))

;; JSON conversion (browser only)
#?(:cljs
   (defn signal->json [signal]
     (js/JSON.stringify (clj->js signal))))
```

**What's shared (90%+ of code):**
- All macro implementations
- Lazy evaluation logic
- Filtering logic
- :let binding evaluation
- Signal construction
- API surface (event!, log!, debug!, etc.)

### Testing Strategy (Cross-Platform)

**BB Tests:**
```clojure
(deftest test-lazy-eval-bb
  ;; Test in BB
  (binding [*telemetry-enabled* false]
    (def called? (atom false))
    (event! ::test [(x (reset! called? true))] {:x x})
    (is (false? @called?) "BB: Should not eval when disabled")))
```

**Browser Tests (Three Sinks):**
```clojure
(deftest test-lazy-eval-browser
  ;; Test 1: Lazy evaluation (same as BB)
  (binding [*telemetry-enabled* false]
    (def called? (atom false))
    (event! ::test [(x (reset! called? true))] {:x x})
    (is (false? @called?) "Browser: Should not eval when disabled")))

(deftest test-console-sink
  ;; Test 2: Console sink control
  (enable-console-sink!)
  (event! ::console-test {:data 123})
  ;; → Manual verification in browser console

  (disable-console-sink!)
  (event! ::should-not-appear {:data 456})
  ;; → Should NOT appear in console)

(deftest test-atom-sink
  ;; Test 3: Atom sink for programmatic access
  (clear-events!)
  (enable-atom-sink!)

  (event! ::event1 {:data 1})
  (event! ::event2 {:data 2})

  (is (= 2 (count (get-events))) "Should collect 2 events")
  (is (= ::event1 (:event-id (first (get-events)))))
  (is (= ::event2 (:event-id (second (get-events)))))

  (clear-events!)
  (is (= 0 (count (get-events))) "Should clear events"))

(deftest test-remote-sink
  ;; Test 4: WebSocket sink (requires mock send-fn)
  (def sent-events (atom []))
  (def mock-send-fn (fn [event] (swap! sent-events conj event)))

  (enable-remote-sink! mock-send-fn)
  (event! ::remote-test {:data 789})

  (is (= 1 (count @sent-events)) "Should send 1 event")
  (is (= :browser (:source (second (first @sent-events)))) "Should tag as :browser")
  (is (= ::remote-test (:event-id (second (first @sent-events))))))

(deftest test-multiple-sinks
  ;; Test 5: All three sinks simultaneously
  (clear-events!)
  (reset! sent-events [])

  (enable-console-sink!)
  (enable-atom-sink!)
  (enable-remote-sink! mock-send-fn)

  (event! ::triple-test {:data "all sinks"})

  ;; Console: manual verification
  ;; Atom: programmatic check
  (is (= 1 (count (get-events))))
  ;; WebSocket: programmatic check
  (is (= 1 (count @sent-events))))
```

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
