# Wrapper Design Question: Why the Level-Specific Helpers?

**Date:** November 30, 2025
**Question:** Why implement `trace`, `debug`, `info`, etc. when `log!` is sufficient?

---

## What We Actually Implemented

### In trove-macros.cljs (Browser/Scittle)

```clojure
(defn log!
  ([level id] (log! level id nil))
  ([level id data]
   (log-fn (str *ns*) nil level id (delay {:data data}))))

;; ❌ NO level-specific helpers
;; ❌ NO trace, debug, info, warn, error, fatal functions
```

### In sente-lite.logging.cljc (JVM/Babashka/Browser)

```clojure
(defn log! [level id data]
  ;; Core function
  ...)

(defmacro trace [id & [data]] `(log! :trace ~id ~data))
(defmacro debug [id & [data]] `(log! :debug ~id ~data))
(defmacro info [id & [data]] `(log! :info ~id ~data))
(defmacro warn [id & [data]] `(log! :warn ~id ~data))
(defmacro error [id & [data]] `(log! :error ~id ~data))
(defmacro fatal [id & [data]] `(log! :fatal ~id ~data))

;; ✅ Level-specific helpers (but they're MACROS!)
```

---

## The Problem

### These Macros DON'T Work in Scittle!

```clojure
;; In Scittle
(require '[sente-lite.logging :as log])

;; ❌ This fails
(log/info :event {})
;; Error: Could not resolve symbol: info

;; ✅ This works
(log/log! :info :event {})
```

**Why?**
- The level-specific helpers are **macros**
- Macros don't work in Scittle
- Same problem as Trove!

---

## Why We Implemented Them Anyway

### For JVM/Babashka (Where They Work)

```clojure
;; ✅ Works on JVM/Babashka
(require '[sente-lite.logging :as log])
(log/info :event {})
(log/debug :event {})
(log/error :event {})
```

**Why macros?**
- Compile-time optimization
- Only evaluate parameters if logging enabled
- Zero overhead when logging disabled
- Consistent with Trove's design

### For Browser/Scittle (Where They DON'T Work)

```clojure
;; ❌ Doesn't work in Scittle
(require '[sente-lite.logging :as log])
(log/info :event {})
;; Error: Could not resolve symbol: info

;; ✅ Have to use the function
(log/log! :info :event {})
```

---

## The Inconsistency

### What We Have

**JVM/Babashka:**
```clojure
(log/info :event {})      ;; Macro - works
(log/debug :event {})     ;; Macro - works
(log/error :event {})     ;; Macro - works
(log/log! :info :event {}) ;; Function - also works
```

**Browser/Scittle:**
```clojure
(log/info :event {})      ;; ❌ Macro - DOESN'T work
(log/debug :event {})     ;; ❌ Macro - DOESN'T work
(log/error :event {})     ;; ❌ Macro - DOESN'T work
(log/log! :info :event {}) ;; ✅ Function - works
```

### The Problem

We have **different APIs** for different platforms:
- JVM: Use `(log/info :event {})`
- Scittle: Use `(log/log! :info :event {})`

This is **inconsistent and confusing!**

---

## What We Should Have Done

### Option 1: Function-Based Helpers (Current Approach, But Not Implemented)

```clojure
;; In trove-macros.cljs (for Scittle)
(defn trace [id & [data]] (log! :trace id (first data)))
(defn debug [id & [data]] (log! :debug id (first data)))
(defn info [id & [data]] (log! :info id (first data)))
(defn warn [id & [data]] (log! :warn id (first data)))
(defn error [id & [data]] (log! :error id (first data)))
(defn fatal [id & [data]] (log! :fatal id (first data)))
```

**Result:**
```clojure
;; ✅ Works in Scittle
(log/info :event {})
(log/debug :event {})
(log/error :event {})
```

**Pros:**
- ✅ Consistent API across platforms
- ✅ Works in Scittle
- ✅ Cleaner code

**Cons:**
- ❌ No macro optimization in Scittle
- ❌ Parameters always evaluated

### Option 2: Keep Macros, Document the Limitation

```clojure
;; In sente-lite.logging.cljc
(defmacro info [id & [data]] `(log! :info ~id ~data))
;; ... etc

;; In documentation:
;; "Note: Level-specific helpers are macros and only work on JVM/Babashka.
;;  In Scittle, use (log/log! :info :event {}) instead."
```

**Result:**
```clojure
;; JVM/Babashka
(log/info :event {})  ;; ✅ Works

;; Scittle
(log/log! :info :event {})  ;; ✅ Works (but different API)
```

**Pros:**
- ✅ Macro optimization on JVM
- ✅ Clear documentation

**Cons:**
- ❌ Inconsistent API
- ❌ Confusing for users

### Option 3: Dual Implementation (Best)

```clojure
;; In sente-lite.logging.cljc
(defn log! [level id data] ...)

#?(:clj
   ;; JVM/Babashka: Use macros for optimization
   (do
     (defmacro trace [id & [data]] `(log! :trace ~id ~data))
     (defmacro debug [id & [data]] `(log! :debug ~id ~data))
     (defmacro info [id & [data]] `(log! :info ~id ~data))
     (defmacro warn [id & [data]] `(log! :warn ~id ~data))
     (defmacro error [id & [data]] `(log! :error ~id ~data))
     (defmacro fatal [id & [data]] `(log! :fatal ~id ~data)))
   
   :cljs
   ;; Browser/Scittle: Use functions
   (do
     (defn trace [id & [data]] (log! :trace id (first data)))
     (defn debug [id & [data]] (log! :debug id (first data)))
     (defn info [id & [data]] (log! :info id (first data)))
     (defn warn [id & [data]] (log! :warn id (first data)))
     (defn error [id & [data]] (log! :error id (first data)))
     (defn fatal [id & [data]] (log! :fatal id (first data)))))
```

**Result:**
```clojure
;; JVM/Babashka
(log/info :event {})  ;; ✅ Macro - optimized

;; Scittle
(log/info :event {})  ;; ✅ Function - works
```

**Pros:**
- ✅ Consistent API across platforms
- ✅ Macro optimization on JVM
- ✅ Functions in Scittle
- ✅ Best of both worlds

**Cons:**
- ⚠️ Slightly more complex code

---

## The Answer to Your Question

### Why We Implemented Level-Specific Helpers

**We implemented them because:**
1. They provide a cleaner API than `(log! :info :event {})`
2. They're consistent with Trove's design
3. They work on JVM/Babashka (as macros)
4. They provide compile-time optimization on JVM

### Why We Didn't Implement Them in the Wrapper

**We didn't add them to trove-macros.cljs because:**
1. We only focused on the core `log!` function
2. We didn't realize the level-specific helpers are macros
3. We didn't implement function versions for Scittle

### The Problem

**The level-specific helpers don't work in Scittle because:**
1. They're implemented as macros
2. Macros don't work in Scittle
3. We need function versions for Scittle

---

## What We Should Do

### Immediate Fix

Add function-based level helpers to trove-macros.cljs:

```clojure
(defn trace [id & [data]] (log! :trace id (first data)))
(defn debug [id & [data]] (log! :debug id (first data)))
(defn info [id & [data]] (log! :info id (first data)))
(defn warn [id & [data]] (log! :warn id (first data)))
(defn error [id & [data]] (log! :error id (first data)))
(defn fatal [id & [data]] (log! :fatal id (first data)))
```

### Better Solution

Use dual implementation (macros for JVM, functions for Scittle):

```clojure
#?(:clj
   ;; JVM: Macros for optimization
   (defmacro info [id & [data]] `(log! :info ~id ~data))
   
   :cljs
   ;; Scittle: Functions that work
   (defn info [id & [data]] (log! :info id (first data))))
```

---

## Summary

### The Question

Why implement `trace`, `debug`, `info`, etc. when `log!` is sufficient?

### The Answer

1. **For JVM/Babashka:** Macros provide compile-time optimization
2. **For Scittle:** We should have implemented function versions
3. **Currently:** We have an inconsistency - macros that don't work in Scittle

### The Real Issue

We didn't fully think through the Scittle use case when designing the level-specific helpers.

We should either:
1. Add function versions to the wrapper (quick fix)
2. Use dual implementation (best solution)

### The Lesson

When designing APIs that span multiple platforms (JVM, Babashka, Scittle), we need to consider:
- What works on each platform
- Consistency across platforms
- Optimization opportunities
- Fallback implementations

We did this for `log!` but not for the level-specific helpers.

---

**Status:** ✅ Issue Identified

The level-specific helpers are macros that don't work in Scittle. We should add function versions or use dual implementation.
