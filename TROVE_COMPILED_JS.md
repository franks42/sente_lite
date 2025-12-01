# Creating a Compiled JavaScript Version of Trove

**Date:** November 30, 2025
**Status:** Feasibility Analysis
**Question:** Could we create a compiled JS version of Trove? Easy?

---

## Short Answer

**Not particularly easy, but definitely doable.** Here's why:

---

## Current Trove Build Setup

### What Trove Has
```
project.clj          # Leiningen build config
bb.edn              # Babashka tasks
cljsbuild config    # ClojureScript compilation
```

### Current Compilation Targets
1. **Clojure** (JVM) - Primary target
2. **ClojureScript** (Node.js) - Test target via cljsbuild
3. **Babashka** - Supported via bb.edn

### What It Doesn't Have
- ❌ Browser/UMD build target
- ❌ npm package configuration
- ❌ Shadow-cljs setup
- ❌ CDN distribution

---

## What Would Be Needed

### Step 1: Create Build Configuration

**Option A: Use shadow-cljs (Recommended)**
```clojure
;; shadow-cljs.edn
{:source-paths ["src"]
 :dependencies [[org.clojure/clojurescript "1.12.42"]]
 :builds
 {:browser
  {:target :browser
   :output-dir "dist"
   :asset-path "/dist"
   :modules {:main {:entries [taoensso.trove]}}}
  
  :umd
  {:target :npm-module
   :output-dir "dist"
   :entries [taoensso.trove]}}}
```

**Option B: Use standard ClojureScript compiler**
```clojure
;; In project.clj
:cljsbuild
{:builds
 [{:id :browser
   :source-paths ["src"]
   :compiler
   {:output-to "dist/trove.js"
    :optimizations :advanced
    :output-wrapper true}}]}
```

### Step 2: Create npm Package Configuration

```json
{
  "name": "trove-logging",
  "version": "1.1.0",
  "description": "Modern logging facade for JavaScript",
  "main": "dist/trove.js",
  "browser": "dist/trove.browser.js",
  "module": "dist/trove.esm.js",
  "files": ["dist"],
  "scripts": {
    "build": "shadow-cljs release browser",
    "watch": "shadow-cljs watch browser",
    "publish": "npm publish"
  }
}
```

### Step 3: Handle Macros

**Challenge:** Trove uses macros extensively
- `trove/log!` - Main logging macro
- `trove/set-log-fn!` - Configuration macro

**Solutions:**

#### Option A: Pre-expand Macros
```clojure
;; Compile with macro expansion
;; Result: JavaScript functions instead of macros
;; Pro: Smaller bundle
;; Con: Less flexible
```

#### Option B: Include SCI Runtime
```clojure
;; Bundle SCI (Small Clojure Interpreter) with Trove
;; Result: Macros work in browser
;; Pro: Full functionality
;; Con: Larger bundle (~500KB+)
```

#### Option C: Provide Function API Only
```clojure
;; Export only direct API functions
;; Result: No macros, just functions
;; Pro: Smallest bundle, works with Scittle
;; Con: Different API than Clojure version
```

### Step 4: Create Distribution

**For npm:**
```bash
npm publish
```

**For CDN:**
- Upload to jsDelivr
- Upload to unpkg
- Both auto-index npm packages

---

## Effort Estimate

### Minimal Version (Function API Only)
**Effort:** 2-4 hours
**Complexity:** Low

**What you get:**
- Compiled JavaScript file
- npm package
- CDN availability
- No macros (use functions instead)

**Example:**
```html
<script src="https://cdn.jsdelivr.net/npm/trove-logging@1.1.0/dist/trove.js"></script>
<script>
  const trove = window.TroveLogging;
  trove.log({level: 'info', id: 'event', data: {}});
</script>
```

### Full Version (With Macros via SCI)
**Effort:** 1-2 days
**Complexity:** Medium

**What you get:**
- Compiled JavaScript with SCI runtime
- Full macro support
- Larger bundle size (~500KB+)
- Works exactly like Clojure version

**Example:**
```html
<script src="https://cdn.jsdelivr.net/npm/trove-logging@1.1.0/dist/trove-with-sci.js"></script>
<script type="application/x-scittle">
  (require '[taoensso.trove :as trove])
  (trove/log! {:level :info :id :event})
</script>
```

### Scittle Plugin Version (Recommended)
**Effort:** 4-8 hours
**Complexity:** Medium

**What you get:**
- Proper Scittle plugin
- Macro support via SCI
- Clean integration
- Reusable across projects

**Example:**
```html
<script src="https://cdn.jsdelivr.net/npm/scittle.trove@1.1.0/dist/scittle.trove.js"></script>
<script type="application/x-scittle">
  (require '[taoensso.trove :as trove])
  (trove/log! {:level :info :id :event})
</script>
```

---

## Step-by-Step Implementation

### For Minimal Version (Recommended for Now)

#### 1. Create shadow-cljs.edn
```clojure
{:source-paths ["src"]
 :dependencies [[org.clojure/clojurescript "1.12.42"]]
 :builds
 {:browser
  {:target :browser
   :output-dir "dist"
   :modules {:main {:entries [taoensso.trove]}}}}}
```

#### 2. Install shadow-cljs
```bash
npm install --save-dev shadow-cljs
```

#### 3. Compile
```bash
npx shadow-cljs release browser
```

#### 4. Create package.json
```json
{
  "name": "trove-logging",
  "version": "1.1.0",
  "main": "dist/main.js",
  "files": ["dist"]
}
```

#### 5. Publish to npm
```bash
npm publish
```

#### 6. Available on CDN
```
https://cdn.jsdelivr.net/npm/trove-logging@1.1.0/dist/main.js
https://unpkg.com/trove-logging@1.1.0/dist/main.js
```

---

## Challenges & Solutions

### Challenge 1: Macro Expansion
**Problem:** Macros don't compile to JavaScript

**Solution:** 
- Use function API only (simplest)
- Pre-expand macros (medium)
- Include SCI (most complete)

### Challenge 2: Bundle Size
**Problem:** Including SCI adds ~500KB

**Solution:**
- Offer two versions: minimal + full
- Minimal: functions only (~20KB)
- Full: with SCI (~500KB+)

### Challenge 3: API Compatibility
**Problem:** JavaScript doesn't have macros

**Solution:**
- Provide function wrappers
- Document the API difference
- Keep Clojure version unchanged

### Challenge 4: Maintenance
**Problem:** Need to rebuild when Trove updates

**Solution:**
- Automate with CI/CD
- Publish to npm automatically
- Keep in sync with Clojure version

---

## Comparison: Current vs Compiled

| Aspect | Current (Source) | Compiled (Minimal) | Compiled (Full) |
|--------|------------------|-------------------|-----------------|
| **Setup** | Clone + copy | npm install | npm install |
| **Size** | ~100KB source | ~20KB compiled | ~500KB compiled |
| **Macros** | ✅ Yes | ❌ No | ✅ Yes |
| **API** | Clojure-like | Function-based | Clojure-like |
| **Elegance** | ❌ Low | ✅ High | ✅ High |
| **Effort** | ✅ Done | ⚠️ 2-4 hours | ⚠️ 1-2 days |
| **Maintenance** | ❌ High | ✅ Low | ✅ Low |

---

## Recommendation

### For Your Project Now
**Keep current approach** - it works and is documented

### For Future
**Create minimal compiled version** (2-4 hours)
- Publish to npm
- Available on CDN
- Solves the "not elegant" problem
- Can be done in a separate PR

### Long-Term
**Create Scittle plugin** (4-8 hours)
- Proper integration with Scittle
- Full macro support
- Benefits whole community

---

## Who Should Do This?

### Option 1: You (sente-lite maintainer)
- **Pro:** You control the version
- **Pro:** Can customize for your needs
- **Con:** Maintenance burden
- **Effort:** 2-4 hours for minimal version

### Option 2: Trove Author (Peter Taoensso)
- **Pro:** Official support
- **Pro:** Maintained by author
- **Con:** Depends on author availability
- **Effort:** 2-4 hours (author's time)

### Option 3: Community Contribution
- **Pro:** Benefits everyone
- **Pro:** Can be maintained by community
- **Con:** Requires coordination
- **Effort:** 2-4 hours + review

---

## Next Steps

### Immediate
1. Keep current approach (works)
2. Document it well (done ✅)
3. Move to Phase 4

### Short-Term (This Week)
1. Decide if you want to create compiled version
2. If yes, start with minimal version
3. Publish to npm
4. Test with Scittle

### Long-Term (This Month)
1. Create Scittle plugin
2. Contribute back to ecosystem
3. Share with community

---

## Conclusion

**Is it easy?** Not trivial, but definitely doable.

**Effort:** 2-4 hours for minimal version (functions only)

**Value:** Huge - solves the "not elegant" problem

**Recommendation:** Worth doing, but not urgent. Current approach works fine for now.

**Best Path:** 
1. Use current approach for Phase 4
2. Create compiled version as follow-up
3. Contribute to Scittle ecosystem

---

## Resources

- [shadow-cljs Documentation](https://shadow-cljs.github.io/)
- [ClojureScript Compiler](https://clojurescript.org/)
- [npm Publishing Guide](https://docs.npmjs.com/packages-and-modules/contributing-packages-to-the-registry)
- [jsDelivr CDN](https://www.jsdelivr.com/)
- [Trove GitHub](https://github.com/taoensso/trove)

---

**Last Updated**: November 30, 2025
**Status**: Feasibility Analysis Complete
**Recommendation**: Doable, but not urgent. Current approach works well.
