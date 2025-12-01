# Current State Clarification: Trove + Scittle Integration

**Date:** November 30, 2025
**Status:** Clarifying what we actually have
**Question:** Do we have both functional and macro interfaces?

---

## Short Answer

**Not quite - we have a HYBRID approach:**

1. ✅ **Functional interface** - Works everywhere (JVM, Babashka, Browser)
2. ⚠️ **Macro interface** - Works on JVM/Babashka, but NOT on Browser
3. ✅ **Workaround for Browser** - Function-based wrapper in `trove-macros.cljs`

---

## What We Actually Have

### Layer 1: Core Logging Interface (`src/sente_lite/logging.cljc`)

```clojure
;; JVM/Babashka/Browser - All platforms
(defn log! [level id data]
  #?(:bb (println ...)
     :clj-jvm (trove/log! ...)
     :cljs (trove/log! ...)))

;; JVM/Babashka only - Macros
(defmacro trace [id & [data]] `(log! :trace ~id ~data))
(defmacro debug [id & [data]] `(log! :debug ~id ~data))
(defmacro info [id & [data]] `(log! :info ~id ~data))
;; ... etc
```

**Status:**
- ✅ `log!` function works everywhere
- ✅ Macros work on JVM/Babashka
- ❌ Macros DON'T work in Browser (SCI limitation)

### Layer 2: Browser Wrapper (`dev/scittle-demo/trove-macros.cljs`)

```clojure
(ns trove.macros
  (:require [taoensso.trove.console :as console]))

(def ^:private log-fn (console/get-log-fn))

(defn log! [level id data]
  (log-fn (str *ns*) nil level id (delay {:data data})))
```

**Status:**
- ✅ Function-based API for Browser
- ✅ Works with Scittle
- ❌ NOT a macro (can't avoid parameter evaluation)

---

## The Problem You Identified: Macro Importance

### Why Macros Matter for Logging

You're absolutely right - macros are CRITICAL for logging:

```clojure
;; With MACRO (good - parameters not evaluated if log level disabled)
(info :expensive-operation {:result (expensive-computation)})
;; Expands to:
;; (when (log-enabled? :info)
;;   (log! :info :expensive-operation {:result (expensive-computation)}))

;; With FUNCTION (bad - parameters ALWAYS evaluated)
(log! :info :expensive-operation {:result (expensive-computation)})
;; Always evaluates expensive-computation, even if logging disabled!
```

### Current Browser Situation

**We DON'T have this optimization in Browser!**

```clojure
;; In Browser (Scittle)
(require '[trove.macros :refer [log!]])
(log! :info :event {:expensive (expensive-computation)})
;; ❌ PROBLEM: expensive-computation is ALWAYS evaluated
;; ❌ No macro expansion to guard it
```

---

## Why Macros Don't Work in Browser (SCI Limitation)

### The Issue

When you load `trove-macros.cljs` in Scittle:

```clojure
(defn log! ...)  ;; This is a FUNCTION, not a macro
```

**Why?** Because:
1. SCI can't properly expand macros from external files
2. Namespace-qualified macro calls don't work in SCI
3. Would need special `:sci/macro` metadata setup
4. Would need to include SCI runtime (~500KB)

### What We Tried

We attempted to make macros work:

```clojure
;; This doesn't work in SCI:
(defmacro log! [level id data]
  `(trove/log! ...))

;; Error: Could not resolve symbol: trove/log!
;; (nested macro expansion fails)
```

---

## Current Architecture

### JVM/Babashka (Optimal) ✅

```
sente-lite.logging
├── (defn log! ...) - Function
├── (defmacro trace ...) - Macro
├── (defmacro debug ...) - Macro
└── (defmacro info ...) - Macro

Usage:
  (info :event {:data value})  ;; Macro - optimized!
  (log! :info :event {:data value})  ;; Function - fallback
```

**Result:**
- ✅ Macros provide optimization
- ✅ Parameters only evaluated if needed
- ✅ Zero overhead when logging disabled

### Browser/Scittle (Suboptimal) ⚠️

```
trove-macros.cljs
├── (defn log! ...) - Function only
├── (defn trace ...) - Function
├── (defn debug ...) - Function
└── (defn info ...) - Function

Usage:
  (info :event {:data value})  ;; Function - NOT optimized
  (log! :info :event {:data value})  ;; Function - NOT optimized
```

**Result:**
- ❌ All functions, no macros
- ❌ Parameters always evaluated
- ❌ Overhead even when logging disabled

---

## Why We Made This Trade-off

### Option 1: Use Macros (What We Wanted)
```clojure
(defmacro log! [level id data]
  `(when (should-log? ~level)
     (log-fn ~level ~id ~data)))
```

**Problem:** Macros don't expand in SCI context

### Option 2: Use Functions (What We Have)
```clojure
(defn log! [level id data]
  (log-fn level id data))
```

**Trade-off:** Parameters always evaluated, but it works

### Option 3: Include SCI Runtime (Not Done)
Bundle SCI (~500KB) to enable macro expansion

**Trade-off:** Huge bundle size, but full optimization

---

## The `:refer` Requirement (Why No FQN?)

### Why Namespace Qualification Doesn't Work

```clojure
;; ✅ This works
(require '[trove.macros :refer [log!]])
(log! :info :event)

;; ❌ This doesn't work
(require '[trove.macros :as tm])
(tm/log! :info :event)
;; Error: Could not resolve symbol: tm/log!
```

### Why?

SCI's namespace resolution doesn't support qualified macro calls the same way compiled ClojureScript does. This is a limitation of how SCI handles namespaces and macro resolution.

**It's weird**, but it's how SCI works. The `:refer` approach is the workaround.

---

## What We Should Do

### Option 1: Accept Current Limitation (Pragmatic) ✅

**Keep what we have:**
- ✅ Works in Browser
- ✅ Documented
- ✅ Good enough for most cases
- ⚠️ Slight performance cost (parameters always evaluated)

**Usage:**
```clojure
(require '[trove.macros :refer [log! trace debug info warn error fatal]])
(info :event {:data value})  ;; Function, not macro
```

### Option 2: Create Compiled JS Version (Better) ⭐

**Create `scittle.trove` plugin with SCI:**
- ✅ Full macro support
- ✅ Proper optimization
- ✅ Elegant integration
- ❌ Larger bundle (~500KB)
- ❌ 4-8 hours of work

**Usage:**
```clojure
(require '[taoensso.trove :as trove])
(trove/log! {:level :info :id :event :data {:value 1}})  ;; Macro!
```

### Option 3: Create Minimal Compiled JS Version (Compromise)

**Compile Trove without SCI:**
- ✅ Smaller bundle (~20KB)
- ✅ Works in Browser
- ❌ Still no macros
- ❌ 2-4 hours of work

---

## Recommendation

### For Phase 4 (Now)
**Use current approach** - it works and is documented

### For Production
**Consider Option 2 (Compiled with SCI)** if:
- You have expensive logging parameters
- Performance is critical
- You want true macro optimization

### For Most Cases
**Current approach is fine** because:
- Logging overhead is usually not the bottleneck
- Modern browsers are fast
- Parameter evaluation is typically cheap
- Simplicity is valuable

---

## Summary

**What we have:**
- ✅ Functional interface everywhere
- ✅ Macro interface on JVM/Babashka
- ⚠️ Function-based workaround in Browser

**The trade-off:**
- ✅ Browser logging works
- ⚠️ No macro optimization in Browser
- ⚠️ Parameters always evaluated

**Why this matters:**
- Macros prevent unnecessary parameter evaluation
- Important for expensive computations
- Less critical for simple logging

**What to do:**
- Now: Use current approach (works)
- Later: Consider compiled version if needed
- Ask Trove Slack for better solutions

---

## Key Insight

You identified something important: **macros matter for logging performance**. 

Our current Browser solution sacrifices this optimization for simplicity and elegance. This is a reasonable trade-off for now, but something to keep in mind if performance becomes an issue.

The ideal solution would be a compiled Trove with SCI support, which would give us:
- ✅ Full macro support
- ✅ Proper optimization
- ✅ Browser compatibility
- ❌ Larger bundle size

---

**Status:** Current approach is pragmatic and documented. Ideal solution would require compiled version with SCI.
