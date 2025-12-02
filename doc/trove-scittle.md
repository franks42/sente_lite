# Trove in Scittle/SCI

## Summary

Trove works in Scittle with standard `defmacro` - no special `^:sci/macro` workaround needed. The only required change is removing `#?(:clj ...)` wrappers from two helper functions in `utils.cljc`.

## The Fix (for upstream Trove)

In `src/taoensso/trove/utils.cljc`, remove the `#?(:clj ...)` wrappers from:

- `const?` - no JVM-specific code
- `callsite-coords` - no JVM-specific code

That's it. Peter's commit (68a29fa) already made `defmacro` unconditional in `trove.cljc`. With the utils fix, Trove works in Scittle out of the box.

## Why It Works

SCI/Scittle supports `defmacro` natively. The issue was never the macro itself - it was that the helper functions the macro calls were wrapped in `#?(:clj ...)` and thus unavailable in the `:cljs` branch.

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

## Usage in Scittle (after upstream fix)

Load directly from jsdelivr CDN (no local copies needed):

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.js"></script>
<script src="https://cdn.jsdelivr.net/gh/taoensso/trove@vX.X.X/src/taoensso/trove/utils.cljc" 
        type="application/x-scittle"></script>
<script src="https://cdn.jsdelivr.net/gh/taoensso/trove@vX.X.X/src/taoensso/trove/console.cljc" 
        type="application/x-scittle"></script>
<script src="https://cdn.jsdelivr.net/gh/taoensso/trove@vX.X.X/src/taoensso/trove.cljc" 
        type="application/x-scittle"></script>

<script type="application/x-scittle">
  (require '[taoensso.trove :refer [log!]])
  (log! {:level :info :id :my/event :data {:user-id 123}})
</script>
```

Pin to a version tag for stability. jsdelivr has proper CORS headers.

## What Works

- ✅ All log levels (`:trace`, `:debug`, `:info`, `:warn`, `:error`, `:fatal`)
- ✅ Lazy evaluation of `:msg`, `:data`, `:error` (wrapped in `delay`)
- ✅ Compile-time validation (opts must be a literal map)
- ✅ Custom `:log-fn` option
- ✅ Console backend with level-appropriate methods
- ✅ `:let` option
- ✅ Line/column coords (when `&form` has metadata)

## What Doesn't Work

- ❌ Namespace-qualified calls (`trove/log!`) - SCI limitation

## Future: Compiled JS Plugin

For a cleaner single-script solution, Trove could be added as an official Scittle plugin (see `scittle-trove-js-cdn.md`).
