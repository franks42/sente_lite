# MANDATORY LINTING ENFORCEMENT PROTOCOL

## The Problem
We keep accumulating technical debt by not linting immediately after changes. Pre-commit hooks are TOO LATE.

## The Solution: Real-Time Enforcement

### 1. WRAPPER FUNCTIONS FOR ALL EDITS
Instead of using `Edit` or `Write` tools directly, we MUST use these wrapper scripts:

```bash
# For editing files
./lint-safe-edit.bb <file> <changes>

# For creating new files
./lint-safe-create.bb <file> <content>
```

### 2. AUTOMATED REVERSION
If ANY edit introduces linting errors:
- Changes are IMMEDIATELY reverted
- Error report is generated
- Cannot proceed until fixed

### 3. CONTINUOUS MONITORING
```bash
# Run in background during all development
./continuous-lint-monitor.bb
```

This watches all .cljc/.clj files and alerts on any linting degradation.

### 4. ENFORCEMENT CHECKLIST
Before ANY file operation:
- [ ] Is clj-kondo installed?
- [ ] Is the file already lint-clean?
- [ ] Will my change maintain lint-cleanliness?

After EVERY file operation:
- [ ] Run `clj-kondo --lint <file>`
- [ ] Zero errors?
- [ ] Zero/minimal warnings?

## Proposed Process Change for Claude

1. **NEVER use Edit/Write tools directly on Clojure files**
2. **ALWAYS wrap edits in lint-checking code**
3. **IMMEDIATELY verify after each change**
4. **REVERT if linting fails**

## Example Enforcement Pattern

```clojure
;; WRONG - Direct edit without verification
(edit-file "server.cljc" changes)

;; RIGHT - Lint-wrapped edit
(let [original (slurp "server.cljc")]
  (spit "server.cljc" changes)
  (if (lint-clean? "server.cljc")
    :success
    (do (spit "server.cljc" original)
        (throw "Lint failed - reverted!"))))
```

## Current State Requirements

Before we can proceed with ANY new work:
1. Fix ALL 31 errors in existing files
2. Fix ALL 36 warnings (or document why they're acceptable)
3. Verify all files pass cljfmt
4. Create automated enforcement scripts

## Commitment

I acknowledge that proceeding without clean linting is UNACCEPTABLE and creates compounding technical debt that becomes harder to fix over time.