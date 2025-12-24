# Trove in Scittle - Distribution Options

## Current Status

Trove works in Scittle once Peter removes `#?(:clj ...)` wrappers from `const?` and `callsite-coords` in `utils.cljc`. No other changes needed.

## Option 1: Load from CDN (Recommended)

Once the upstream fix is merged, load directly from jsdelivr:

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js"></script>
<script src="https://cdn.jsdelivr.net/gh/taoensso/trove@vX.X.X/src/taoensso/trove/utils.cljc" 
        type="application/x-scittle"></script>
<script src="https://cdn.jsdelivr.net/gh/taoensso/trove@vX.X.X/src/taoensso/trove/console.cljc" 
        type="application/x-scittle"></script>
<script src="https://cdn.jsdelivr.net/gh/taoensso/trove@vX.X.X/src/taoensso/trove.cljc" 
        type="application/x-scittle"></script>

<script type="application/x-scittle">
  (require '[taoensso.trove :refer [log!]])
  (log! {:level :info :id :my/event :msg "Hello!"})
</script>
```

**Pros:** No local copies, automatic updates with version pinning, CORS works  
**Cons:** Three script tags (ugly but functional)

## Option 2: Compiled JS Plugin (Future)

A compiled `scittle.trove.js` would be cleaner - single script tag:

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js"></script>
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.trove.js"></script>
```

This requires either:
- PR to `babashka/scittle` adding Trove as official plugin
- Peter publishing a separate `scittle-trove` npm package

---

# Building a Scittle Trove Plugin (Reference)

## What the Build Does

1. **Bundles all dependencies** - `trove.cljc`, `utils.cljc`, `console.cljc` → single JS file
2. **Dead-code eliminates** - removes `:clj`-only code paths
3. **Preserves macros** - `defmacro` works at runtime in SCI
4. **Minifies** - smaller download size

## Project Structure

```
scittle-trove/
├── bb.edn                              # Babashka tasks
├── deps.edn                            # Clojure dependencies
├── shadow-cljs.edn                     # Build configuration
├── package.json                        # npm package config
└── src/
    ├── sci/configs/taoensso/trove.cljs # SCI namespace config
    └── scittle/trove.cljs              # Plugin registration
```

## Key Files

### `deps.edn`

```clojure
{:paths ["src"]
 :deps {org.clojure/clojurescript {:mvn/version "1.11.132"}
        borkdude/sci {:mvn/version "0.8.43"}
        ;; Include Trove source (or use local path)
        com.taoensso/trove {:mvn/version "1.1.0"}}}
```

### `shadow-cljs.edn`

```clojure
{:deps {:aliases [:dev]}
 :builds
 {:trove
  {:target :browser
   :modules {:scittle.trove {:entries [scittle.trove]
                             :depends-on #{:scittle}}}
   :output-dir "dist"}}}
```

### `src/scittle/trove.cljs`

```clojure
(ns scittle.trove
  (:require [sci.core :as sci]
            [scittle.core :as scittle]
            [taoensso.trove :as trove]
            [taoensso.trove.console :as console]))

(def trove-ns
  {'*log-fn*    trove/*log-fn*
   'log!        (sci/copy-var trove/log! (the-ns 'taoensso.trove))
   'set-log-fn! (sci/copy-var trove/set-log-fn! (the-ns 'taoensso.trove))})

(def console-ns
  {'get-log-fn console/get-log-fn})

(def config
  {:namespaces {'taoensso.trove trove-ns
                'taoensso.trove.console console-ns}})

(scittle/register-plugin! ::trove config)
```

### `package.json`

```json
{
  "name": "scittle-trove",
  "version": "1.0.0",
  "description": "Trove logging plugin for Scittle",
  "main": "dist/scittle.trove.js",
  "files": ["dist"],
  "repository": "github:taoensso/trove",
  "license": "EPL-1.0"
}
```

### `bb.edn`

```clojure
{:tasks
 {clean {:task (do (babashka.fs/delete-tree "dist")
                   (babashka.fs/delete-tree ".shadow-cljs"))}
  
  build {:doc "Build production JS"
         :depends [clean]
         :task (shell "npx shadow-cljs release trove")}
  
  publish {:doc "Publish to npm"
           :depends [build]
           :task (do (shell "npm version patch")
                     (shell "npm publish"))}}}
```

## Build Steps

```bash
# 1. Install dependencies
npm install shadow-cljs

# 2. Build production JS
bb build
# or: npx shadow-cljs release trove

# 3. Output is in dist/scittle.trove.js
```

## Publishing to npm

```bash
# Login to npm (once)
npm login

# Publish
bb publish
# or: npm publish
```

## CDN Usage (after publishing)

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js"></script>
<script src="https://cdn.jsdelivr.net/npm/scittle-trove@1.0.0/dist/scittle.trove.js"></script>

<script type="application/x-scittle">
  (require '[taoensso.trove :refer [log!]])
  (log! {:level :info :id :my/event :msg "Hello from CDN!"})
</script>
```

## Scittle's Build Process

Looking at [babashka/scittle](https://github.com/babashka/scittle), their workflow is:

1. **`bb prod`** - Runs shadow-cljs release build
2. **`bb dist`** - Copies JS files to `dist/` folder
3. **`bb npm-publish`** - Bumps version, pushes git tag, publishes to npm

They don't use GitHub Actions for the build itself - it's manual via Babashka tasks. The CDN (jsdelivr) automatically picks up new npm versions.

## How Scittle Plugins Are Distributed

All official Scittle plugins are built together in the main `babashka/scittle` repo:

```clojure
;; From shadow-cljs.edn
:modules
{:scittle {:entries [scittle.core]}
 :scittle.nrepl {:entries [scittle.nrepl]}
 :scittle.promesa {:entries [scittle.promesa]}
 :scittle.js-interop {:entries [scittle.js-interop]}
 :scittle.pprint {:entries [scittle.pprint]}
 :scittle.reagent {:entries [scittle.reagent]}
 :scittle.replicant {:entries [scittle.replicant]}
 :scittle.re-frame {:entries [scittle.re-frame]}
 :scittle.cljs-ajax {:entries [scittle.cljs-ajax]}}
```

They are:
- Built in a single shadow-cljs build
- Published together under the `scittle` npm package
- Available at `cdn.jsdelivr.net/npm/scittle@VERSION/dist/scittle.PLUGIN.js`

## Plugin Architecture

Each plugin consists of three layers:

```
Library (promesa, reagent, trove, etc.)
    ↓
SCI Config (sci.configs.*/*)     ← wraps library for SCI compatibility
    ↓
Scittle Plugin (scittle/*)       ← thin registration wrapper
    ↓
CDN JS file                      ← built & published by babashka/scittle
```

**Example: scittle.promesa**

The plugin file is just ~6 lines:
```clojure
(ns scittle.promesa
  (:require [sci.configs.funcool.promesa :as p]
            [scittle.core :as scittle]))

(scittle/register-plugin! ::promesa p/config)
```

The actual work is in the **SCI config** (maintained in [babashka/sci.configs](https://github.com/babashka/sci.configs)), which defines how the library's namespaces and vars are exposed to SCI.

## What's Needed for Trove

1. **Trove with utils fix** ← pending (Peter needs to remove `#?(:clj ...)` from `const?` and `callsite-coords`)
2. **SCI config for Trove** ← defines namespace mappings for SCI
3. **Scittle plugin** ← thin wrapper calling `register-plugin!`

The SCI config could live in:
- `babashka/sci.configs` (if accepted upstream)
- `taoensso/trove` itself
- Inline in the scittle plugin

## Options for Trove Plugin

| Option | Effort | Maintenance |
|--------|--------|-------------|
| **PR to babashka/scittle** | Add `scittle.trove` module | Maintained by Scittle team |
| **PR to taoensso/trove** | Peter publishes separate `scittle-trove` | Maintained by Trove author |
| **Current approach** | 3 script tags loading `.cljc` | Works now, no build needed |

The cleanest solution is a PR to `babashka/scittle` adding Trove as an official plugin.

## Notes

- The `:sci/macro` metadata is preserved through compilation
- Macro expansion happens at Scittle runtime (in browser)
- jsdelivr CDN auto-updates when new npm versions are published
- No GitHub Actions needed - just `npm publish`
