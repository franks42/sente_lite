# Debug Data Readers with Trove Integration

## Overview

Data readers like `#p` provide a lightweight, AI-friendly debugging mechanism. Combining them with Trove logging creates a powerful, unified debugging experience.

## Why `#p` is AI-Agent Friendly

Traditional debugging:
```clojure
;; Before
(+ (* x 2) (* y 3))

;; After - must match parens correctly
(+ (do (println "(* x 2) =>" (* x 2)) (* x 2)) (* y 3))
```

With `#p`:
```clojure
;; Before
(+ (* x 2) (* y 3))

;; After - just insert "#p " before the form
(+ #p (* x 2) (* y 3))
```

**Benefits for AI agents:**
- No paren matching required
- Insert 3 chars to add, remove 3 chars to undo
- No risk of breaking code structure
- Idempotent - `#p #p form` could just work
- Grep-able - easy to find/remove all: `grep -r "#p "`

## Basic `#p` Implementation

```clojure
(defmacro p [form]
  `(let [v# ~form]
     (js/console.log (str "#p[" *ns* "] " '~form " ⇒") v#)
     v#))
```

## Trove-Integrated `#p`

Route through Trove for unified logging:

```clojure
(defmacro p [form]
  `(let [v# ~form]
     (log! {:level :debug
            :id    :hashp/debug
            :msg   (str '~form " ⇒ " (pr-str v#))
            :data  {:form '~form :value v#}})
     v#))
```

### Benefits of Trove Integration

| Feature | Standalone `#p` | Trove-integrated `#p` |
|---------|-----------------|----------------------|
| Output destination | Fixed (console) | Configurable (console, file, WebSocket) |
| Disable globally | Remove from code | Set min-level to `:info` |
| Filter in logs | Manual grep | Query by `:id :hashp/debug` |
| Structured data | No | Yes - `:data {:form ... :value ...}` |
| Timestamps | No | Yes |
| Unified stream | No | Yes - with all other logs |

### Example Output

```clojure
(defn calculate [x y]
  (+ #p (* x 2) #p (* y 3)))

(calculate 5 10)
```

Console:
```
2025-12-03T21:00:00.000Z :debug user :hashp/debug (* x 2) ⇒ 10
  data: {:form (* x 2), :value 10}
2025-12-03T21:00:00.000Z :debug user :hashp/debug (* y 3) ⇒ 30
  data: {:form (* y 3), :value 30}
```

### Production Safety

```clojure
;; Development - see all debug output
(set-log-fn! (console/get-log-fn {:min-level :debug}))

;; Production - #p calls silently return value, no logging
(set-log-fn! (console/get-log-fn {:min-level :info}))
```

## Dynamic Addition via Re-eval

While `#p` is read-time, you can add it dynamically by re-evaluating source:

```clojure
;; In editor/REPL, modify and re-eval:
(defn my-fn [x]
  #p (process x))  ;; Added #p, re-eval this defn

;; The new definition now includes debugging
;; No restart required - just re-eval the form
```

This works well with:
- nREPL connected to running process
- Scittle's browser REPL
- Hot-reload workflows

## Extended Data Reader Family

Beyond `#p`, other useful debug readers could integrate with Trove:

### `#t` - Timing

```clojure
(defmacro t [form]
  `(let [start# (js/performance.now)
         v#     ~form
         ms#    (- (js/performance.now) start#)]
     (log! {:level :debug
            :id    :hashp/timing
            :msg   (str '~form " took " (.toFixed ms# 2) "ms")
            :data  {:form '~form :value v# :ms ms#}})
     v#))
```

Usage:
```clojure
#t (expensive-operation)
;; logs: (expensive-operation) took 234.56ms
```

### `#spy` - Watch State Changes

```clojure
(defmacro spy [form]
  `(let [before# (when (instance? Atom ~(second form)) @~(second form))
         v#      ~form
         after#  (when (instance? Atom ~(second form)) @~(second form))]
     (log! {:level :debug
            :id    :hashp/spy
            :msg   (str '~form)
            :data  {:before before# :after after# :result v#}})
     v#))
```

Usage:
```clojure
(swap! state #spy update :counter inc)
;; logs: before → after
```

### `#assert` - Inline Validation

```clojure
(defmacro assert-pred [pred form]
  `(let [v# ~form]
     (when-not (~pred v#)
       (log! {:level :error
              :id    :hashp/assert
              :msg   (str "Assertion failed: " '~pred " on " '~form)
              :data  {:form '~form :value v# :predicate '~pred}}))
     v#))
```

Usage:
```clojure
#assert pos? (calculate-amount)
;; logs error if not positive, but doesn't throw
```

### `#sample` - Probabilistic Logging

```clojure
(defmacro sample [rate form]
  `(let [v# ~form]
     (when (< (rand) ~rate)
       (log! {:level :debug
              :id    :hashp/sample
              :msg   (str '~form " ⇒ " (pr-str v#))
              :data  {:form '~form :value v# :rate ~rate}}))
     v#))
```

Usage:
```clojure
#sample 0.01 (high-frequency-op)
;; logs 1% of calls - useful for hot paths
```

### `#trace` - Function Entry/Exit

```clojure
(defmacro trace [form]
  `(do
     (log! {:level :trace
            :id    :hashp/trace-enter
            :msg   (str "→ " '~form)})
     (let [v# ~form]
       (log! {:level :trace
              :id    :hashp/trace-exit
              :msg   (str "← " '~form " ⇒ " (pr-str v#))
              :data  {:form '~form :value v#}})
       v#)))
```

Usage:
```clojure
#trace (my-fn arg1 arg2)
;; logs: → (my-fn arg1 arg2)
;; logs: ← (my-fn arg1 arg2) ⇒ result
```

## Filtering Debug Output

With all debug readers using Trove, filtering is easy:

```clojure
;; Custom log-fn that filters by id
(defn filtered-log-fn [allowed-ids]
  (let [base-fn (console/get-log-fn)]
    (fn [ns coords level id lazy_]
      (when (or (nil? allowed-ids)
                (contains? allowed-ids id))
        (base-fn ns coords level id lazy_)))))

;; Only show timing, hide #p
(set-log-fn! (filtered-log-fn #{:hashp/timing}))

;; Show all debug readers
(set-log-fn! (filtered-log-fn #{:hashp/debug :hashp/timing :hashp/spy}))
```

## Platform Support

| Syntax | JVM | Babashka | Scittle |
|--------|-----|----------|---------|
| `#p` data reader | ✅ | ✅ | ❌ |
| `(p ...)` macro | ✅ | ✅ | ✅ |

**Scittle limitation:** SCI's data readers are configured at context creation time and cannot be modified at runtime. The `#p` syntax is not available, but the `(p ...)` macro works perfectly.

## Installation

### JVM/Babashka: Data Reader Registration

Create `data_readers.cljc` in your classpath root:
```clojure
{p      my.debug/p
 t      my.debug/t
 spy    my.debug/spy
 sample my.debug/sample
 trace  my.debug/trace}
```

Then use `#p`, `#t`, etc. anywhere in your code.

### Scittle: Macro-only

Use macros directly (no `#` prefix):
```clojure
(require '[my.debug :refer [p t spy]])

;; Use as macro call
(p (* 2 3))        ;; prints: #p (* 2 3) => 6
(t (slow-fn))      ;; prints: (slow-fn) took 234ms
```

One extra pair of parens, but still much simpler than `(println "x=" x)`.

## Use Cases

### 1. Debugging Data Pipelines

```clojure
(->> users
     #p (filter :active)
     #p (map :email)
     #t (send-notifications))
```

### 2. Performance Investigation

```clojure
(defn process-request [req]
  #t (let [user #t (fetch-user (:user-id req))
           data #t (fetch-data user)]
       #t (transform data)))
```

### 3. Intermittent Bug Hunting

```clojure
(defn flaky-operation [x]
  (let [result #p (compute x)]
    (when (anomaly? result)
      #p {:anomaly true :input x :output result})
    result))
```

### 4. AI-Assisted Debugging

AI agent can:
1. Add `#p` to suspicious expressions
2. Re-eval the modified code
3. Observe output in logs
4. Remove `#p` when done
5. No risk of breaking code structure

## Summary

- `#p` is simple, safe, and AI-friendly
- Trove integration provides unified logging, filtering, and routing
- Extended readers (`#t`, `#spy`, `#assert`, `#sample`, `#trace`) cover common debug scenarios
- All output goes through same configurable backend
- Production-safe with log level controls
