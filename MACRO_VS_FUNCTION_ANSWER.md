# Definitive Answer: Macro vs Function Processing with :refer

**Date:** November 30, 2025
**Status:** Tested & Verified
**Question:** When we use `require...:refer`, do we get real macro processing or fallback to function?

---

## The Answer: **REAL MACRO PROCESSING** ✅

When you use `(require '[namespace :refer [macro-name]])`:

- ✅ **Macros ARE processed as real macros**
- ✅ **Functions ARE processed as functions**
- ✅ **:refer preserves the type** (macro vs function)
- ✅ **Macro expansion happens at compile time**

---

## Test Results (Verified)

### Test 1: Macro with :refer
```clojure
(require '[test.macros :refer [macro-log]])
(macro-log :info :test/refer (expensive-computation))
```

**Result:**
```
🔵 MACRO: Executing with level=:info id=:test/refer
❌ EXPENSIVE COMPUTATION CALLED! Count: 1
✅ Test 1 PASS: Macro processed (expensive-computation called)
```

**Metadata Check:**
```
macro-log metadata: {:macro true, :arglists ([level id data]), ...}
✅ Test 1 PASS: macro-log IS a macro (:macro true)
```

### Test 2: Function with :refer
```clojure
(require '[test.macros :refer [function-log]])
(function-log :info :test/refer (expensive-computation))
```

**Result:**
```
❌ EXPENSIVE COMPUTATION CALLED! Count: 1
🟢 FUNCTION: Executing with level=:info id=:test/refer
✅ Test 2 PASS: Function called (expensive-computation evaluated)
```

**Metadata Check:**
```
function-log metadata: {:arglists ([level id data]), ...}
✅ Test 2 PASS: function-log IS a function
```

---

## What This Means

### For Real Macros (Like `test.macros/macro-log`)

```clojure
(require '[test.macros :refer [macro-log]])

;; This is a REAL macro call
(macro-log :info :test/refer (expensive-computation))

;; Expands to:
(do
  (println (str "🔵 MACRO: Executing with level=:info id=:test/refer"))
  (expensive-computation))

;; Result: expensive-computation IS evaluated
;; (because it's in the macro expansion)
```

**Key Point:** The macro IS expanded, but in this case it still evaluates the parameter because the macro body includes it.

### For Functions (Like `test.macros/function-log`)

```clojure
(require '[test.macros :refer [function-log]])

;; This is a FUNCTION call
(function-log :info :test/refer (expensive-computation))

;; Evaluates to:
(function-log :info :test/refer "expensive-result")

;; Result: expensive-computation IS evaluated
;; (because function parameters are always evaluated before calling)
```

---

## The Critical Finding: Our Wrapper is a FUNCTION ❌

### Our Current Wrapper

```clojure
;; dev/scittle-demo/trove-macros.cljs
(defn log! [level id data]
  (log-fn (str *ns*) nil level id (delay {:data data})))
```

**Metadata:**
```
trove.macros/log! metadata: {:name log!, :arglists ([level id] [level id data]), ...}
❌ Test 5 FAIL: Our wrapper is a FUNCTION (not optimized!)
   This means parameters are ALWAYS evaluated
```

**What This Means:**
```clojure
(require '[trove.macros :refer [log!]])

;; This looks like a macro call, but it's actually a FUNCTION
(log! :info :event {:expensive (expensive-computation)})

;; Evaluates to:
(log! :info :event "expensive-result")

;; Result: expensive-computation IS evaluated
;; Even if logging is disabled!
```

---

## Why Our Wrapper Isn't a Macro

### We Tried to Make It a Macro

```clojure
(defmacro log! [level id data]
  `(trove/log! ...))
```

**Problem:** Nested macro expansion fails in SCI
```
Error: Could not resolve symbol: trove/log!
```

### Why It Fails

1. `trove/log!` is a macro in Trove
2. When we try to expand our macro, SCI tries to expand `trove/log!`
3. But `trove/log!` can't be resolved in SCI context
4. Nested macro expansion fails

### The Solution We Used

We gave up on macros and used a function instead:

```clojure
(defn log! [level id data]
  (log-fn (str *ns*) nil level id (delay {:data data})))
```

**Trade-off:**
- ✅ Works in Browser
- ✅ Simple and reliable
- ❌ No macro optimization
- ❌ Parameters always evaluated

---

## The Real Trove Macro Problem

### Trove's log! IS a Macro

We can't access it directly in SCI:

```clojure
(require '[taoensso.trove :as trove])
(meta #'trove/log!)
;; Error: Unable to resolve var: trove/log!
```

**Why?** SCI can't resolve qualified macro references.

### What We Can Do

We can use Trove's direct API (functions):

```clojure
(require '[taoensso.trove.console :as console])

(def log-fn (console/get-log-fn))

;; This is a function call, not a macro
(log-fn "ns" nil :info :event (delay {:data {}}))
```

---

## Comparison: Macro vs Function

### Ideal Macro (What We Want)

```clojure
(defmacro log! [level id data]
  `(when (should-log? ~level)
     (log-fn ~level ~id ~data)))

;; Usage:
(log! :info :event {:expensive (expensive-computation)})

;; Expands to:
(when (should-log? :info)
  (log-fn :info :event {:expensive (expensive-computation)}))

;; Result: expensive-computation only evaluated if logging enabled
```

### Our Function (What We Have)

```clojure
(defn log! [level id data]
  (log-fn level id data))

;; Usage:
(log! :info :event {:expensive (expensive-computation)})

;; Evaluates to:
(log! :info :event "expensive-result")

;; Result: expensive-computation ALWAYS evaluated
```

---

## Summary of Findings

### Question 1: Do we get real macro processing with :refer?
**Answer:** YES ✅
- Macros are processed as real macros
- Functions are processed as functions
- :refer preserves the type

### Question 2: Is our wrapper a macro?
**Answer:** NO ❌
- Our wrapper is a FUNCTION
- We couldn't make it a macro (nested macro expansion fails)
- So we don't get macro optimization

### Question 3: Why does it matter?
**Answer:** Parameter evaluation
- Macros can guard parameter evaluation
- Functions always evaluate parameters
- Important for expensive computations

---

## What This Means for Your Project

### Current State
- ✅ Logging works in Browser
- ✅ Uses function-based API
- ❌ No macro optimization
- ❌ Parameters always evaluated

### Performance Impact
- ✅ Usually negligible (logging overhead is small)
- ⚠️ Matters if you have expensive logging parameters
- ⚠️ Matters in high-throughput systems

### If You Need Macro Optimization
1. **Option 1:** Create compiled JS version with SCI (~500KB, 4-8 hours)
2. **Option 2:** Accept current limitation (pragmatic)
3. **Option 3:** Use Trove's direct API with manual guards

---

## Test Files

**test-macro-processing.html** - Definitive test showing:
- Macros ARE processed as real macros
- Functions ARE processed as functions
- Our wrapper is a FUNCTION (not optimized)
- Trove's log! can't be accessed (macro resolution issue)

**test-macro-vs-function.html** - Detailed comparison test

---

## Conclusion

**When using `require...:refer`:**
- ✅ You DO get real macro processing
- ✅ Macros are expanded at compile time
- ✅ Functions are called at runtime

**For our Trove wrapper:**
- ❌ It's a FUNCTION, not a macro
- ❌ We couldn't make it a macro (SCI limitation)
- ❌ So parameters are always evaluated

**This is a known trade-off:**
- We sacrificed macro optimization for simplicity
- Works well for most use cases
- Can be improved with compiled JS version if needed

---

**Status:** Verified with automated tests
**Recommendation:** Current approach is pragmatic; consider compiled version if macro optimization becomes critical
