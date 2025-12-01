# Trove + Scittle: Final Verdict

**Date:** November 30, 2025
**Status:** Complete Understanding Achieved

---

## The Bottom Line

**Trove does NOT work in Scittle.**

Trove ONLY works in environments where macros are **pre-compiled** by a ClojureScript compiler (like shadow-cljs).

---

## Why Trove Doesn't Work in Scittle

### Trove's Design

Trove is built on **macros**:

```clojure
#?(:clj
   (defmacro log! [opts]
     ;; Complex compile-time processing
     ;; - Validates options
     ;; - Extracts source coordinates (&form)
     ;; - Calls helper functions
     ;; - Builds lazy evaluation
     ...))
```

### Scittle's Limitation

Scittle is a **runtime interpreter** (SCI):
- ❌ Macros don't work in runtime interpretation
- ❌ Macro special forms like `&form` don't work
- ❌ Compile-time processing is impossible
- ❌ Helper functions for macro expansion don't work

### The Result

```clojure
;; ❌ In Scittle
(require '[taoensso.trove :refer [log!]])
(log! {:level :info :id :event})
;; Error: Could not resolve symbol: log!
;; (or) Error: Macro not found
;; (or) Error: &form not available
```

---

## Where Trove DOES Work

### 1. Clojure (JVM)

```clojure
;; ✅ Works perfectly
(require '[taoensso.trove :as trove])
(trove/log! {:level :info :id :event})
```

**Why:**
- JVM compiles macros at compile time
- Macro special forms work
- Helper functions available
- Full metaprogramming capabilities

### 2. Compiled ClojureScript (shadow-cljs, etc.)

```clojure
;; ✅ Works perfectly
(require '[taoensso.trove :as trove])
(trove/log! {:level :info :id :event})
```

**Why:**
- shadow-cljs pre-compiles macros
- Macros are expanded at compile time
- Macro special forms work
- Helper functions available
- Compiled to JavaScript

### 3. Scittle (Runtime Interpreter)

```clojure
;; ❌ Does NOT work
(require '[taoensso.trove :as trove])
(trove/log! {:level :info :id :event})
;; Error: Macro not available
```

**Why:**
- Scittle interprets code at runtime
- Macros can't be interpreted
- Macro special forms don't work
- Helper functions don't work
- No compile-time processing

---

## What We Discovered

### The Reader Conditional

```clojure
#?(:clj
   (defmacro log! [...] ...))
```

**This means:**
- ✅ Load in Clojure (JVM)
- ✅ Load in compiled ClojureScript
- ❌ **DO NOT load in ClojureScript runtime** (Scittle)

**This is intentional!**

Trove's author (Peter Taoensso) knew that:
- Macros don't work in runtime interpretation
- Scittle is a runtime interpreter
- Therefore, don't even try to load the macro

### The Macro Complexity

Trove's `log!` macro uses advanced features:
- `&form` - macro special form
- `utils/callsite-coords` - extracts source coordinates
- `utils/assoc-some` - compile-time map building
- `utils/const?` - checks compile-time constants
- `delay` - lazy evaluation
- Complex conditional logic

**None of these work in Scittle!**

---

## Our Wrapper: A Workaround, Not a Solution

### What Our Wrapper Does

```clojure
(defn log! [level id data]
  (log-fn (str *ns*) nil level id (delay {:data data})))
```

**This is:**
- ✅ A function (not a macro)
- ✅ Works in Scittle
- ❌ NOT the same as Trove's macro
- ❌ NOT what Trove was designed for

### What Our Wrapper Loses

```clojure
;; Trove's macro (compile-time optimization)
(log! {:level :info :id :event :data (expensive-computation)})
;; Expands to:
(when (log-enabled? :info)
  (log-fn :info :event (expensive-computation)))
;; Result: expensive-computation only called if logging enabled

;; Our wrapper (no optimization)
(log! :info :event (expensive-computation))
;; Always evaluates expensive-computation
;; Then calls: (log-fn :info :event result)
;; Result: expensive-computation ALWAYS called
```

### The Trade-off

We get:
- ✅ Something that works in Scittle
- ✅ A clean API
- ✅ Level-specific helpers

We lose:
- ❌ Macro optimization
- ❌ Compile-time processing
- ❌ Lazy evaluation of parameters
- ❌ True Trove compatibility

---

## The Real Situation

### Trove's Intended Use Cases

1. **Clojure (JVM)** - ✅ Perfect fit
2. **Compiled ClojureScript** - ✅ Perfect fit
3. **Scittle** - ❌ Not supported

### Why Scittle Isn't Supported

Trove is a **compile-time facade**:
- Designed for compiled languages
- Macros are the core mechanism
- Runtime interpretation is fundamentally incompatible

### What Scittle Needs

Scittle needs a **runtime logging facade**:
- Function-based, not macro-based
- No compile-time processing
- Simple, direct API

### What We Built

Our wrapper is a **runtime logging facade**:
- Function-based ✅
- No compile-time processing ✅
- Simple, direct API ✅
- NOT Trove ❌

---

## The Honest Assessment

### What We Have

```clojure
;; A function-based logging wrapper
(log! :info :event {:data value})

;; That works in Scittle
;; That provides a clean API
;; That provides level-specific helpers
```

### What We Don't Have

```clojure
;; Trove in Scittle
;; Macro-based logging
;; Compile-time optimization
;; True Trove compatibility
```

### What This Means

We have **created our own logging facade** for Scittle, inspired by Trove's design but fundamentally different because:
- Trove is macro-based
- Scittle requires function-based
- These are incompatible

---

## Alternatives

### Option 1: Accept the Wrapper (Current)

```clojure
;; Use our function-based wrapper
(log! :info :event {:data value})
```

**Pros:**
- ✅ Works in Scittle
- ✅ Clean API
- ✅ Level helpers

**Cons:**
- ❌ Not Trove
- ❌ No macro optimization
- ❌ Parameters always evaluated

### Option 2: Use Trove's Low-Level API

```clojure
;; Use the backend directly
(let [log-fn (taoensso.trove.console/get-log-fn)]
  (log-fn "ns" [1 1] :info :event (delay {:data value})))
```

**Pros:**
- ✅ Direct Trove access
- ✅ Lazy evaluation possible

**Cons:**
- ❌ Verbose
- ❌ Hard to remember
- ❌ No level helpers

### Option 3: Use a Different Logging Library

```clojure
;; Use a library designed for runtime interpretation
;; Examples: glogi, js/console, etc.
```

**Pros:**
- ✅ Designed for runtime
- ✅ No macro issues

**Cons:**
- ❌ Different API
- ❌ Different ecosystem

### Option 4: Create a Compiled Trove Plugin

```clojure
;; Pre-compile Trove with shadow-cljs
;; Distribute as scittle.trove plugin
;; Use in Scittle
```

**Pros:**
- ✅ True Trove in Scittle
- ✅ Macro optimization
- ✅ Full compatibility

**Cons:**
- ❌ Significant work (4-8 hours)
- ❌ Maintenance burden
- ❌ Large bundle (~500KB with SCI)

---

## The Verdict

### Trove + Scittle

**Trove does NOT work in Scittle.**

This is not a bug or limitation of Scittle.
This is by design - Trove is a compile-time facade, Scittle is a runtime interpreter.
They are fundamentally incompatible.

### Our Wrapper

Our wrapper is **NOT Trove in Scittle**.

It's a **separate logging facade** that:
- Uses Trove's backend infrastructure
- Provides a similar API
- Works in Scittle
- Is function-based instead of macro-based

### The Honest Name

We should call it:
- ✅ "sente-lite.logging" (what we have)
- ❌ NOT "Trove for Scittle"
- ❌ NOT "Trove in the browser"

It's a **logging facade inspired by Trove**, but fundamentally different because it's function-based.

---

## Conclusion

**The situation is:**

1. ❌ Trove macros don't work in Scittle
2. ❌ Trove functions don't exist
3. ✅ Trove's backend API works
4. ✅ We created a wrapper around the backend
5. ✅ The wrapper works in Scittle
6. ❌ But it's not really "Trove in Scittle"

**What we have is:**

A **function-based logging facade** that:
- Uses Trove's console backend
- Provides a clean API
- Works in Scittle
- Is NOT the same as Trove's macro-based facade

**This is fine!**

It's a pragmatic solution for logging in Scittle.
It's not Trove, but it works.
And that's what matters.

---

**Final Status:** ✅ Complete Understanding Achieved

Trove doesn't work in Scittle. We built something that does. It's not Trove, but it's good enough.
