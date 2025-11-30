# CDN and Library Versions - November 30, 2025

**Date:** 2025-11-30
**Status:** ✅ All versions are current and up-to-date

---

## Browser/CDN Libraries

### Scittle (ClojureScript in Browser)
- **Current Version:** 0.7.28
- **Latest Version:** 0.7.28 ✅
- **Status:** UP TO DATE
- **Release Date:** 2025-09-13
- **CDN:** https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/

**Available Modules:**
- `scittle.js` - Core
- `scittle.nrepl.js` - nREPL support
- `scittle.js-interop.js` - JS interop
- `scittle.reagent.js` - Reagent support
- `scittle.re-frame.js` - Re-frame support
- `scittle.pprint.js` - Pretty print
- And more...

### SCI (Scittle's Runtime)
- **Current Version:** 0.10.49
- **Latest Version:** 0.10.49 ✅
- **Status:** UP TO DATE
- **Release Date:** 2025-08-22
- **Note:** Bundled with Scittle, no separate CDN needed

---

## JVM/Clojure Dependencies

### Trove (Logging Facade)
- **Current Version:** 1.1.0
- **Latest Version:** 1.1.0 ✅
- **Status:** UP TO DATE
- **Release Date:** 2025-10-11
- **Dependencies:** 0 (pure facade)
- **Maven:** `com.taoensso/trove {:mvn/version "1.1.0"}`

### http-kit (WebSocket Server)
- **Current Version:** 2.9.0-beta3
- **Latest Version:** 2.9.0-beta3 ✅
- **Status:** UP TO DATE
- **Maven:** `http-kit/http-kit {:mvn/version "2.9.0-beta3"}`

### Cheshire (JSON)
- **Current Version:** 6.1.0
- **Latest Version:** 6.1.0 ✅
- **Status:** UP TO DATE
- **Maven:** `cheshire/cheshire {:mvn/version "6.1.0"}`

### Clojure
- **Current Version:** 1.12.3
- **Latest Version:** 1.12.3 ✅
- **Status:** UP TO DATE
- **Maven:** `org.clojure/clojure {:mvn/version "1.12.3"}`

---

## Development Tools

### clj-kondo (Linter)
- **Current Version:** 2025.10.23
- **Latest Version:** 2025.10.23 ✅
- **Status:** UP TO DATE
- **Maven:** `clj-kondo/clj-kondo {:mvn/version "2025.10.23"}`

### cljfmt (Formatter)
- **Current Version:** 0.15.6
- **Latest Version:** 0.15.6 ✅
- **Status:** UP TO DATE
- **Maven:** `dev.weavejester/cljfmt {:mvn/version "0.15.6"}`

### tools.namespace
- **Current Version:** 1.5.0
- **Latest Version:** 1.5.0 ✅
- **Status:** UP TO DATE
- **Maven:** `org.clojure/tools.namespace {:mvn/version "1.5.0"}`

### tools.reader
- **Current Version:** 1.5.2
- **Latest Version:** 1.5.2 ✅
- **Status:** UP TO DATE
- **Maven:** `org.clojure/tools.reader {:mvn/version "1.5.2"}`

---

## Summary

### Overall Status
✅ **ALL VERSIONS ARE CURRENT**

| Category | Count | Up to Date |
|----------|-------|-----------|
| Browser/CDN | 2 | ✅ 2/2 |
| JVM/Clojure | 4 | ✅ 4/4 |
| Dev Tools | 4 | ✅ 4/4 |
| **TOTAL** | **10** | **✅ 10/10** |

### Key Points
1. Scittle 0.7.28 is the latest stable version
2. SCI 0.10.49 is bundled with Scittle
3. Trove 1.1.0 is the latest (just added to project)
4. All development tools are current
5. No breaking changes in any dependencies

---

## HTML File Status

**File:** `/dev/scittle-demo/index.html`
- Scittle version: 0.7.28 ✅
- nREPL module: 0.7.28 ✅
- Status: UP TO DATE

---

## Recommendations

1. **No immediate updates needed** - all versions are current
2. **Monitor Scittle releases** - check quarterly for updates
3. **Monitor Trove releases** - important for logging migration
4. **Keep development tools updated** - they improve code quality

---

## Next Steps

1. ✅ Dependencies updated to latest versions
2. ✅ CDN libraries verified as current
3. ✅ HTML file confirmed up to date
4. Ready to proceed with Trove migration Phase 1
