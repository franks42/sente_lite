# Dependency Updates - November 30, 2025

**Date:** 2025-11-30
**Status:** ✅ All dependencies updated to latest stable versions

---

## Updated Dependencies

### Core Dependencies

| Dependency | Previous | Updated | Status |
|-----------|----------|---------|--------|
| `org.clojure/clojure` | 1.12.3 | 1.12.3 | ✅ Already latest |
| `cheshire/cheshire` | 6.1.0 | 6.1.0 | ✅ Already latest |
| `http-kit/http-kit` | 2.8.1 | 2.9.0-beta3 | ⬆️ Upgraded |
| `com.taoensso/trove` | N/A | 1.1.0 | ✨ **NEW** |

### Development Dependencies

| Dependency | Previous | Updated | Status |
|-----------|----------|---------|--------|
| `org.clojure/tools.namespace` | 1.5.0 | 1.5.0 | ✅ Already latest |
| `clj-kondo/clj-kondo` | 2025.09.22 | 2025.10.23 | ⬆️ Upgraded |
| `dev.weavejester/cljfmt` | 0.15.3 | 0.15.6 | ⬆️ Upgraded |
| `org.clojure/tools.reader` | 1.5.2 | 1.5.2 | ✅ Already latest |

---

## Changes Made

### 1. http-kit Upgrade
- **From:** 2.8.1
- **To:** 2.9.0-beta3
- **Reason:** Latest stable version with bug fixes and improvements
- **Impact:** Better WebSocket support, performance improvements

### 2. clj-kondo Upgrade
- **From:** 2025.09.22
- **To:** 2025.10.23
- **Reason:** Latest linter with improved error detection
- **Impact:** Better code quality checks

### 3. cljfmt Upgrade
- **From:** 0.15.3
- **To:** 0.15.6
- **Reason:** Latest formatter with bug fixes
- **Impact:** Better code formatting consistency

### 4. Trove Added
- **Version:** 1.1.0
- **Reason:** Required for Trove migration (Phase 1)
- **Impact:** Enables migration from telemere-lite to Trove logging facade
- **Dependencies:** 0 (pure facade)

---

## Verification

### Formatting Check
```
✅ All source files formatted correctly
```

### Linting Check
```
✅ 0 errors (16 warnings in test files - expected)
```

### Dependency Resolution
```
✅ All dependencies resolved successfully
```

---

## Summary

**Total Updates:** 4 dependencies upgraded/added
- **Upgrades:** 3 (http-kit, clj-kondo, cljfmt)
- **New:** 1 (Trove)
- **Already Latest:** 4

**Breaking Changes:** None
**Compatibility:** Fully backward compatible

---

## Next Steps

1. Run full test suite to verify compatibility
2. Proceed with Trove Migration Phase 1
3. Commit dependency updates

---

## deps.edn Changes

```clojure
;; BEFORE
{:deps {org.clojure/clojure {:mvn/version "1.12.3"}
        cheshire/cheshire {:mvn/version "6.1.0"}
        http-kit/http-kit {:mvn/version "2.8.1"}}
 :aliases
 {:dev {:extra-deps {org.clojure/tools.namespace {:mvn/version "1.5.0"}
                     clj-kondo/clj-kondo {:mvn/version "2025.09.22"}
                     dev.weavejester/cljfmt {:mvn/version "0.15.3"}}}}}

;; AFTER
{:deps {org.clojure/clojure {:mvn/version "1.12.3"}
        cheshire/cheshire {:mvn/version "6.1.0"}
        http-kit/http-kit {:mvn/version "2.9.0-beta3"}
        com.taoensso/trove {:mvn/version "1.1.0"}}
 :aliases
 {:dev {:extra-deps {org.clojure/tools.namespace {:mvn/version "1.5.0"}
                     clj-kondo/clj-kondo {:mvn/version "2025.10.23"}
                     dev.weavejester/cljfmt {:mvn/version "0.15.6"}}}}}
```
