# Trove Architecture Clarified

**Date:** November 30, 2025
**Question:** Does Trove export a function equivalent of the `log!` macro?

---

## Trove's Architecture

### The Facade Pattern

Trove is a **logging facade** (like SLF4J in Java):

```
Your Code
    ↓
log! macro (facade)
    ↓
*log-fn* (backend dispatcher)
    ↓
Actual Backend (console, timbre, telemere, etc.)
```

### The log! Macro

```clojure
#?(:clj
   (defmacro log! [opts]
     ;; Compile-time processing
     ;; - Validates opts
     ;; - Extracts coordinates
     ;; - Builds lazy form
     
     ;; Expands to:
     `(let [~lfn ~log-fn]
        (when ~lfn
          (~lfn ~ns ~coords ~level ~id ~lazy-form))
        nil)))
```

**What it does:**
1. Takes a map of options: `{:level :info :id :event :data {...}}`
2. Extracts: level, id, msg, data, error, etc.
3. Calls `*log-fn*` with: `(ns coords level id lazy-form)`

### The Backend (*log-fn*)

```clojure
(def ^:dynamic *log-fn*
  "The value of this var determines the Trove backend.
   
   Should be a (fn [ns coords level id lazy_]) with:
     ns ------- String namespace of log! callsite
     coords --- [line column] of log! callsite
     level ---- Keyword :level from log! call
     id ------- Keyword :id from log! call
     lazy_ ---- {:keys [msg data error kvs]} (may be delayed)
   
   Default: (console/get-log-fn)"
  (console/get-log-fn))
```

**What it does:**
1. Receives structured logging data
2. Processes it (filtering, formatting, etc.)
3. Sends to actual backend (console, timbre, etc.)

---

## What Trove Exports

### Exported Symbols

```clojure
;; taoensso.trove namespace
*log-fn*           ;; Dynamic var holding the backend function
set-log-fn!        ;; Macro to set the backend (JVM only)
log!               ;; Macro to log (JVM only)

;; taoensso.trove.console namespace
get-log-fn         ;; Function that returns console backend
```

### NOT Exported

```clojure
;; ❌ NO function version of log!
;; ❌ NO log function
;; ❌ NO facade function
```

---

## The Gap: Why We Need the Wrapper

### What Trove Provides

```clojure
;; 1. Macro (JVM only)
(log! {:level :info :id :event :data {...}})

;; 2. Low-level backend API
(let [log-fn *log-fn*]
  (log-fn "my-ns" [1 1] :info :event (delay {:data {...}})))
```

### What Trove Does NOT Provide

```clojure
;; ❌ NO function that takes the same arguments as log!
;; ❌ NO (log-function {:level :info :id :event :data {...}})
;; ❌ NO high-level function API for runtime interpretation
```

### Why This Gap Exists

Trove is designed for **compiled ClojureScript**:
- Macros work fine in compiled code
- Compile-time processing is possible
- No need for a function equivalent

Trove is NOT designed for **runtime interpretation** (Scittle):
- Macros don't work
- No function equivalent provided
- Users must use low-level API

---

## Our Wrapper: Filling the Gap

### What Our Wrapper Does

```clojure
(ns trove.macros
  (:require [taoensso.trove.console :as console]))

(def ^:private log-fn (console/get-log-fn))

(defn log!
  "Function version of Trove's log! macro"
  ([level id] (log! level id nil))
  ([level id data]
   (log-fn (str *ns*) nil level id (delay {:data data}))))

(defn trace [id & [data]] (log! :trace id data))
(defn debug [id & [data]] (log! :debug id data))
(defn info [id & [data]] (log! :info id data))
(defn warn [id & [data]] (log! :warn id data))
(defn error [id & [data]] (log! :error id data))
(defn fatal [id & [data]] (log! :fatal id data))
```

### How It Works

1. Gets the backend function: `(console/get-log-fn)`
2. Creates a function that takes the same arguments as the macro
3. Calls the backend with properly formatted data
4. Provides level-specific helpers

### The Result

```clojure
;; Clean, high-level API
(log! :info :event {:user-id 123})
(trace :event {})
(debug :event {})
(info :event {})
(warn :event {})
(error :event {})
(fatal :event {})
```

---

## Why We MUST Have the Wrapper

### Trove's Design

Trove intentionally provides:
1. **A macro** for compile-time environments (JVM)
2. **A backend dispatcher** (`*log-fn*`) for the actual logging
3. **NO function equivalent** of the macro

### The Reason

Trove's author (Peter Taoensso) designed it this way because:
- Macros are the right tool for compiled code
- Runtime interpretation is an edge case (Scittle)
- Users of runtime interpretation should provide their own wrapper

### Our Solution

We provide the wrapper that Trove intentionally doesn't:
- A function that acts like the `log!` macro
- A clean, high-level API for Scittle
- Consistency with the JVM version

---

## Summary

### Trove's Architecture

```
log! macro (JVM only)
    ↓
*log-fn* (backend dispatcher)
    ↓
Actual Backend (console, timbre, etc.)
```

### What Trove Exports

```
✅ *log-fn* - the backend dispatcher
✅ console/get-log-fn - default console backend
❌ NO function version of log!
❌ NO high-level function API
```

### What We Provide

```
✅ log! function - acts like the macro
✅ trace, debug, info, warn, error, fatal - level helpers
✅ Clean, high-level API for Scittle
✅ Consistency with JVM version
```

---

## Conclusion

**The wrapper is absolutely necessary because:**

1. Trove intentionally doesn't provide a function version of `log!`
2. The low-level `*log-fn*` API is too verbose
3. We need a high-level API for Scittle
4. We want consistency with the JVM version

**The wrapper is the right solution because:**

1. It fills the gap Trove intentionally left
2. It provides a clean, intuitive API
3. It's minimal and elegant
4. It's exactly what users of runtime interpretation need

**We absolutely need the wrapper!** ✅

It's not a workaround - it's the intended way to use Trove in runtime interpretation environments.
