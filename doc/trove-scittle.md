# Trove in Scittle/SCI

## Problem

The Trove `log!` macro is defined with `#?(:clj ...)` only:

```clojure
#?(:clj
   (defmacro log! [opts] ...))
```

This works for normal ClojureScript compilation (the CLJS compiler runs on JVM and uses the `:clj` branch), but **not for Scittle/SCI** which interprets ClojureScript directly in the browser—there's no JVM at runtime.

## Solution

Added a `:cljs` branch using SCI's macro convention: a function with `^:sci/macro` metadata that receives `&form` and `&env` as first two arguments.

```clojure
#?(:clj
   (defmacro log! [opts] ...)
   
   :cljs
   (defn ^:sci/macro log!
     [_&form _&env opts]
     ;; Return quoted code for SCI to evaluate
     ...))
```

SCI treats functions with `:sci/macro` metadata as macros and expands them at interpretation time.

## Changes to `src/taoensso/trove.cljc`

1. **Added `:cljs` branch for `log!`** - SCI-compatible macro that:
   - Validates opts is a map at expansion time
   - Wraps `:msg`, `:data`, `:error` in `delay` for lazy evaluation
   - Returns quoted code that calls `*log-fn*`

2. **Added `:cljs` branch for `set-log-fn!`** - Same pattern

## Limitation: No Namespace Qualification

SCI does not support qualified macro calls. You **must** use `:refer`:

```clojure
;; ✅ Works
(require '[taoensso.trove :refer [log!]])
(log! {:level :info :msg "Hello"})

;; ❌ Does NOT work in SCI
(require '[taoensso.trove :as trove])
(trove/log! {:level :info :msg "Hello"})
```

This is a known SCI limitation, not specific to Trove.

## Usage in Scittle

Load Trove source files via script tags:

```html
<script src="path/to/taoensso/trove/utils.cljc" type="application/x-scittle"></script>
<script src="path/to/taoensso/trove/console.cljc" type="application/x-scittle"></script>
<script src="path/to/taoensso/trove.cljc" type="application/x-scittle"></script>

<script type="application/x-scittle">
  (require '[taoensso.trove :refer [log!]])
  (log! {:level :info :id :my/event :data {:user-id 123}})
</script>
```

## What Works

- ✅ All log levels (`:trace`, `:debug`, `:info`, `:warn`, `:error`, `:fatal`)
- ✅ Lazy evaluation of `:msg`, `:data`, `:error` (wrapped in `delay`)
- ✅ Compile-time validation (opts must be a literal map)
- ✅ Custom `:log-fn` option
- ✅ Console backend with level-appropriate methods

## What Doesn't Work

- ❌ Namespace-qualified calls (`trove/log!`)
- ❌ `&form` metadata (no line/column coords in SCI)
- ❌ `:let` option (simplified implementation)

## Test Results

**JVM** (`clj -M:test`): 1 test, 9 assertions, 0 failures  
**Browser** (Playwright): 7/7 tests passed

## Files

- `src/taoensso/trove.cljc` - Modified with `:cljs` SCI macro branches
- `dev/scittle-demo/test-trove-sci-macro.html` - Browser test suite
- `test/taoensso/trove_tests.cljc` - Official Trove tests (from upstream)
