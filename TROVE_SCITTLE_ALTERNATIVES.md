# Trove + Scittle: Analysis of Current Approach & Better Alternatives

**Date:** November 30, 2025
**Status:** Research & Analysis
**Context:** Current workaround (cloning source) is not elegant; exploring better solutions

---

## The Problem with Current Approach

### What We're Doing Now ❌
1. Clone Trove from GitHub
2. Copy source files to `src/taoensso/`
3. Create symlink for HTTP serving
4. Load `.cljc` files via `<script type="application/x-scittle">`
5. Use `:refer` to import functions

### Why It's Not Ideal
- **Manual Process**: Requires cloning and copying
- **Maintenance Burden**: Need to update when Trove updates
- **Not Elegant**: Feels like a workaround
- **Scalability**: Doesn't scale if using multiple libraries
- **Distribution**: Can't easily share with others

---

## What Would Be Ideal

### The Dream Solution
```html
<!-- Load Trove from CDN -->
<script src="https://cdn.jsdelivr.net/npm/trove@1.1.0/dist/trove.js"></script>

<!-- Use directly in Scittle -->
<script type="application/x-scittle">
  (require '[taoensso.trove :as trove])
  (trove/log! {:level :info :id :event})
</script>
```

**Requirements for this to work:**
1. Trove published to npm
2. Compiled to JavaScript (UMD or ESM)
3. Macros included and working
4. Available on CDN (jsDelivr, unpkg, etc.)

---

## Current Reality: Why This Doesn't Exist

### Why Trove Isn't on npm
1. **Clojure Library First**: Trove is designed for Clojure/ClojureScript
2. **Clojars Distribution**: Published to Clojars (Clojure package repo), not npm
3. **Macro Complexity**: Macros don't compile to JavaScript easily
4. **Low Browser Demand**: Most Clojure users use compiled ClojureScript, not Scittle
5. **Author Priority**: Peter Taoensso focuses on Clojure/ClojureScript ecosystem

### The Macro Problem
- Trove uses macros extensively (`trove/log!`, `trove/set-log-fn!`)
- Macros are compile-time constructs
- JavaScript doesn't have macros
- Would need to either:
  - Pre-expand all macros (loses flexibility)
  - Include SCI runtime (adds bloat)
  - Use a different approach entirely

---

## Alternative Solutions

### Option 1: Use Scittle Plugins (Recommended) ✅

**What**: Create a Scittle plugin for Trove

**How It Works**:
```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.js"></script>
<script src="https://cdn.jsdelivr.net/npm/scittle.trove@1.0.0/dist/scittle.trove.js"></script>

<script type="application/x-scittle">
  (require '[taoensso.trove :as trove])
  (trove/log! {:level :info :id :event})
</script>
```

**Advantages**:
- ✅ Elegant and clean
- ✅ No source code copying needed
- ✅ Distributed via npm/CDN
- ✅ Macros work properly
- ✅ Follows Scittle plugin pattern
- ✅ Reusable across projects

**Disadvantages**:
- ❌ Requires creating and maintaining plugin
- ❌ Requires Trove author approval/contribution
- ❌ Adds to Scittle ecosystem maintenance

**How to Implement**:
1. Create `scittle.trove` npm package
2. Bundle Trove + SCI context setup
3. Publish to npm
4. Add to Scittle plugins list

**Effort**: Medium (1-2 days for initial version)

**Example Structure**:
```javascript
// scittle.trove/dist/scittle.trove.js
(function() {
  // Load Trove into SCI context
  // Configure macros
  // Export to global
  window['scittle.trove'] = {
    // Trove API
  };
})();
```

---

### Option 2: Use glogi (Alternative Library) ✅

**What**: Switch to glogi, a ClojureScript logging library designed for browser

**Advantages**:
- ✅ Already works in browser
- ✅ Designed for ClojureScript
- ✅ No macro issues
- ✅ Available on npm/CDN
- ✅ Mature and stable

**Disadvantages**:
- ❌ Different API than Trove
- ❌ Would need to migrate code
- ❌ Less modern than Trove

**Usage**:
```clojure
(require '[lambdaisland.glogi :as log])
(log/info :event-id {:data "value"})
```

**Effort**: Low (just use it)

---

### Option 3: Keep Current Approach (Pragmatic) ✅

**What**: Continue with source code inclusion but optimize it

**Improvements**:
1. **Automate Cloning**:
   ```bash
   # Add to build script
   ./scripts/update-trove.sh
   ```

2. **Version Pinning**:
   ```bash
   # Clone specific version
   git clone --branch v1.1.0 https://github.com/taoensso/trove.git
   ```

3. **Documentation**:
   - Document the process
   - Provide update script
   - Include in CI/CD

4. **Monorepo Approach**:
   - Keep Trove source in repo
   - Version it with your code
   - No external dependencies

**Advantages**:
- ✅ Works today
- ✅ Full control
- ✅ No external dependencies
- ✅ Can customize if needed

**Disadvantages**:
- ❌ Not elegant
- ❌ Maintenance burden
- ❌ Harder to share

**Effort**: Low (already done)

---

### Option 4: Use Direct JavaScript Logging ✅

**What**: Skip Trove entirely, use native JavaScript

**Advantages**:
- ✅ No dependencies
- ✅ Simple and direct
- ✅ Works everywhere

**Disadvantages**:
- ❌ Lose Trove's features
- ❌ Different API
- ❌ Less elegant

**Usage**:
```clojure
(defn log! [level id data]
  (js/console.log (str level) id data))
```

**Effort**: Minimal

---

### Option 5: Contribute to Trove ✅

**What**: Work with Peter Taoensso to add Scittle support

**Approach**:
1. Create Scittle plugin
2. Contribute to Trove repo
3. Get it added to official distribution

**Advantages**:
- ✅ Official support
- ✅ Maintained by author
- ✅ Elegant solution
- ✅ Benefits whole community

**Disadvantages**:
- ❌ Depends on author availability
- ❌ Longer timeline
- ❌ May not be priority

**Effort**: Medium (1-2 days + review time)

---

## Comparison Matrix

| Aspect | Current | Plugin | glogi | Optimized | JS Only | Contribute |
|--------|---------|--------|-------|-----------|---------|-----------|
| **Elegance** | ❌ Low | ✅ High | ✅ High | ⚠️ Medium | ⚠️ Medium | ✅ High |
| **Effort** | ✅ Done | ⚠️ Medium | ✅ Low | ✅ Low | ✅ Low | ⚠️ Medium |
| **Maintenance** | ❌ High | ✅ Low | ✅ Low | ⚠️ Medium | ✅ Low | ✅ Low |
| **Scalability** | ❌ Poor | ✅ Good | ✅ Good | ⚠️ OK | ✅ Good | ✅ Good |
| **Trove Features** | ✅ Full | ✅ Full | ❌ None | ✅ Full | ❌ None | ✅ Full |
| **Works Today** | ✅ Yes | ❌ No | ✅ Yes | ✅ Yes | ✅ Yes | ❌ No |
| **Community** | ❌ No | ✅ Yes | ✅ Yes | ❌ No | ❌ No | ✅ Yes |

---

## Recommendation

### For Your Project Now
**Use Option 3 (Optimized Current Approach)**
- Already working
- Add automation script
- Document well
- Move forward with Phase 4

### For Long-Term
**Pursue Option 1 (Scittle Plugin)**
- Create `scittle.trove` package
- Publish to npm
- Contribute back to Scittle
- Benefits whole community

### Alternative Path
**Consider Option 2 (glogi)**
- If you want less maintenance
- If you're open to API change
- If you want official browser support

---

## Why Trove Isn't on npm (Deeper Analysis)

### Technical Reasons
1. **Macro Expansion**: Clojure macros don't translate to JavaScript
2. **SCI Runtime**: Would need to bundle SCI (~500KB+)
3. **Compilation**: Would need ClojureScript compiler in build
4. **Complexity**: Not a simple transpilation

### Business Reasons
1. **Target Audience**: Clojure developers, not JavaScript developers
2. **Distribution**: Clojars is the standard for Clojure libraries
3. **Maintenance**: npm ecosystem has different expectations
4. **Priority**: Focus on Clojure/ClojureScript ecosystem

### Community Reasons
1. **Use Cases**: Most Clojure users compile ClojureScript
2. **Scittle Adoption**: Still relatively new
3. **Browser Logging**: Usually handled by JavaScript libraries
4. **Integration**: Easier to use existing JS logging in browser

---

## What Would Need to Happen for npm Distribution

### Scenario 1: Trove Author Creates npm Package
- **Timeline**: Unknown (not currently planned)
- **Effort**: 2-3 days for author
- **Likelihood**: Low (not priority)

### Scenario 2: Community Creates Plugin
- **Timeline**: 1-2 weeks
- **Effort**: 1-2 days development + review
- **Likelihood**: Medium (if someone volunteers)

### Scenario 3: Scittle Ecosystem Grows
- **Timeline**: 6-12 months
- **Effort**: Ongoing
- **Likelihood**: Possible (if Scittle adoption increases)

---

## Actionable Next Steps

### Immediate (This Week)
1. ✅ Keep current approach
2. ✅ Optimize with automation script
3. ✅ Document process
4. ✅ Move to Phase 4

### Short-Term (This Month)
1. Create `scittle.trove` plugin skeleton
2. Test with your project
3. Document plugin creation process
4. Share with Scittle community

### Long-Term (This Quarter)
1. Polish plugin
2. Publish to npm
3. Contribute to Scittle
4. Share with community

---

## Conclusion

**Current Reality**: Trove isn't designed for npm/browser distribution

**Why**: Macros, SCI runtime, target audience, maintenance burden

**Best Path Forward**:
1. **Now**: Optimize current approach
2. **Soon**: Create Scittle plugin
3. **Later**: Contribute to ecosystem

**This is Normal**: Many Clojure libraries follow this pattern

---

## References

- [Scittle Plugins](https://github.com/babashka/scittle/releases)
- [Trove GitHub](https://github.com/taoensso/trove)
- [glogi GitHub](https://github.com/lambdaisland/glogi)
- [Scittle JS Libraries](https://github.com/babashka/scittle/blob/main/doc/js-libraries.md)
- [Clojars vs npm](https://clojars.org/)

---

**Last Updated**: November 30, 2025
**Status**: Analysis Complete
**Recommendation**: Proceed with Phase 4 using current approach; plan plugin for future
