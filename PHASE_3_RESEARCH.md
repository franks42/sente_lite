# Phase 3 Research: Trove in Browser with Scittle

**Date:** November 30, 2025
**Status:** Research Complete

---

## Key Findings

### 1. Trove is a Clojure/ClojureScript Library
- **Not a JavaScript library** - Trove is written in Clojure/ClojureScript
- **Requires compilation** - Must be compiled with ClojureScript build tools
- **No UMD build** - There is no pre-built UMD/browser distribution on CDN
- **Clojars dependency** - Distributed as a Clojure dependency, not npm

### 2. Trove Architecture
- **Tiny library** - ~100 lines of code, 0 dependencies
- **Macro-based** - Uses `trove/log!` macro
- **Pluggable backends** - Supports multiple logging backends
- **Default backend** - Prints to console (browser) or stdout (JVM)

### 3. Browser Usage Patterns
For browser use, Trove must be:
1. **Included as a dependency** in `deps.edn` or `package.json`
2. **Compiled with ClojureScript** using shadow-cljs or similar
3. **Required in ClojureScript code** - `(require '[taoensso.trove :as trove])`
4. **NOT loaded from CDN** - No JavaScript distribution available

### 4. Scittle Limitations
- **Scittle is a ClojureScript runtime** - runs ClojureScript in browser
- **Can't load Clojure libraries from CDN** - Scittle needs pre-compiled code
- **Requires compilation step** - Can't dynamically load Clojure source files
- **Works with pre-compiled JS** - Only works with compiled ClojureScript

### 5. Why the CDN URL Failed
```
https://cdn.jsdelivr.net/npm/com.taoensso/trove@1.1.0/dist/trove.umd.js
```
- This URL doesn't exist - Trove doesn't publish a UMD build
- Trove is distributed on Clojars, not npm
- No JavaScript distribution available

---

## Solution Options

### Option 1: Use Trove in Compiled ClojureScript (Recommended)
**Pros:**
- Proper Trove integration
- Full feature support
- Type-safe

**Cons:**
- Requires build step
- More complex setup

**Steps:**
1. Add Trove to `deps.edn`
2. Compile ClojureScript with shadow-cljs
3. Include compiled JS in HTML
4. Use `(require '[taoensso.trove :as trove])`

### Option 2: Use Console Logging Directly (Current Approach)
**Pros:**
- No dependencies
- Works with Scittle immediately
- Simple

**Cons:**
- Not using Trove
- Limited structure
- No logging facade

**Implementation:**
```clojure
(println "Log message")
(js/console.log "Log message")
```

### Option 3: Use Telemere in Browser (Alternative)
**Pros:**
- Full-featured logging
- Structured logging support
- Browser support

**Cons:**
- Different library
- Larger dependency
- More complex

---

## Recommendation

### For Phase 3 Browser Testing:
1. **Skip Trove CDN approach** - It doesn't exist
2. **Use console logging directly** - Simpler for Scittle
3. **Test sente-lite.logging in compiled ClojureScript** - For production

### For Production Browser Use:
1. **Compile sente-lite with ClojureScript** - Use shadow-cljs
2. **Include Trove in deps.edn** - As a dependency
3. **Build and deploy** - Include compiled JS in HTML

---

## Technical Details

### Trove Dependency
```clojure
; deps.edn
{:deps {com.taoensso/trove {:mvn/version "1.1.0"}}}
```

### Trove Usage in ClojureScript
```clojure
(ns my-app
  (:require [taoensso.trove :as trove]))

; Log a message
(trove/log! {:level :info
             :id :app/started
             :data {:version "1.0.0"}})
```

### Default Backend
- Browser: Logs to `js/console`
- JVM: Logs to `*out*`
- Babashka: Logs to stdout

---

## Conclusion

**Trove is not available as a browser library via CDN.** It's a Clojure library that must be compiled with ClojureScript. For Scittle-based browser testing, we should:

1. Use console logging directly for Phase 3 testing
2. Compile sente-lite with ClojureScript for production use
3. Include Trove as a dependency in the compiled build

This is the correct approach and aligns with how Trove is designed to be used.

---

## References

- [Trove GitHub](https://github.com/taoensso/trove)
- [Trove on Clojars](https://clojars.org/com.taoensso/trove)
- [Scittle GitHub](https://github.com/babashka/scittle)
- [Trove Documentation](https://cljdoc.org/d/com.taoensso/trove)
