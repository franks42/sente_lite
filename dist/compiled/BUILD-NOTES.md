# Scittle Plugin Compilation Notes

**Date:** 2025-12-22

## Current Status

**Build works but output is too large (876KB vs target ~50KB)**

The issue is that shadow-cljs bundles all of SCI because we import `sci.core` for `sci/copy-var`. Official scittle plugins (9-120KB) work because they're built in the scittle monorepo with code splitting via `:depends-on #{:scittle}`.

## Official Plugin Sizes (for reference)
- scittle.nrepl.js: 9.68 KB
- scittle.reagent.js: 74.66 KB
- scittle.re-frame.js: 123.27 KB
- scittle.promesa.js: 91.78 KB

## Options to Fix

### Option A: Contribute to scittle repo (Recommended for production)
Add sente-lite as a plugin in the official scittle repository:
1. Fork babashka/scittle
2. Add src/scittle/sente_lite.cljs
3. Add to shadow-cljs.edn with `:depends-on #{:scittle}`
4. Submit PR

**Pros:** Proper code splitting, small output, official distribution via CDN
**Cons:** External dependency on scittle repo, slower iteration

### Option B: Use scittle's runtime registration (avoids sci/copy-var)
Instead of compile-time SCI namespace registration, use Scittle's runtime API:
```clojure
;; Instead of (sci/copy-var ...), just pass functions directly
(def config {:namespaces {'my.ns {'my-fn my-fn}}})
(scittle.core/register-plugin! "my-plugin" config)
```

**Pros:** No SCI dependency at compile time
**Cons:** May not work with all SCI features

### Option C: Keep source bundle (current working solution)
The 70KB source bundle works fine for development. Consider compiled version only when:
- Publishing to CDN for external users
- Load time becomes critical
- Bundle size matters for mobile

## Build Commands

```bash
cd dist/compiled
npm install          # One-time setup
npm run build        # Compile with shadow-cljs
npm run clean        # Reset build state
```

## Files Structure

```
dist/compiled/
├── package.json       # npm deps (shadow-cljs)
├── shadow-cljs.edn    # Build config
├── deps.edn           # Clojure deps
├── scittle_externs.js # Closure externs
├── src/scittle/
│   └── sente_lite.cljs  # Plugin entry point
└── js/                # Build output
    └── scittle.sente-lite.js
```

## Why Source Bundle Works Better (For Now)

1. **No build step** - Just concatenate source files
2. **70KB is reasonable** - Scittle parses fast
3. **No dependency management** - Source bundle has no external deps
4. **Easy iteration** - Change source, reload browser
5. **Tests pass** - Playwright verified all namespaces work

## When to Revisit Compiled Approach

- [ ] When sente-lite is stable enough to contribute to scittle repo
- [ ] When bundle size becomes a user complaint
- [ ] When Scittle adds better external plugin support
