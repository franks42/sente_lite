# Phase 3 Research: SCI/Scittle Macro Handling

**Date:** November 30, 2025
**Status:** Research Complete

---

## Key Findings

### 1. SCI Macro Support
SCI (Self-hosted Clojure Interpreter) supports macros through:

#### Option A: `:sci/macro` Metadata (Recommended for Scittle)
```clojure
(def my-macro ^:sci/macro 
  (fn [&form &env x]
    (list 'do x x)))
```

**Requirements:**
- Function must have `:sci/macro` metadata set to `true`
- First two arguments must be `&form` and `&env`
- Remaining arguments are the macro parameters

#### Option B: Clojure Var Reference (JVM Only)
```clojure
(defmacro my-macro [x] (list 'do x x))
(sci/eval-string "(my-macro (f))" 
  {:namespaces {'user {'my-macro #'my-macro}}})
```

**Limitation:** Only works in JVM environment, not in browser/Scittle

### 2. Scittle Macro Handling

**Current Behavior:**
- Scittle loads ClojureScript files via `<script type="application/x-scittle">`
- Macros defined with `defmacro` in loaded files are NOT automatically available
- Reason: `defmacro` is a macro itself that needs special handling

**Why `trove/log!` Failed:**
- `trove/log!` is defined as a macro in `trove.cljc`
- When loaded in Scittle, the macro definition doesn't expand properly
- SCI doesn't have the macro expansion context needed

### 3. Solutions for Scittle Macros

#### Solution 1: Use Direct API Calls (✅ WORKS - What We Did)
```clojure
;; Instead of macro:
(trove/log! {:level :info :id :test :data {}})

;; Use direct function call:
(let [log-fn (console/get-log-fn)]
  (log-fn "ns" [1 1] :info :test (delay {:data {}})))
```

**Pros:**
- Works immediately
- No macro expansion needed
- Full control over parameters

**Cons:**
- More verbose
- Less idiomatic

#### Solution 2: Wrap Macros with `:sci/macro` Metadata
Create wrapper functions with proper SCI macro metadata:

```clojure
(def log! ^:sci/macro
  (fn [&form &env opts]
    ;; Expand macro here
    `(let [lfn trove/*log-fn*]
       (when lfn
         (~lfn ~ns ~coords ~level ~id ~lazy-form)))))
```

**Pros:**
- Idiomatic macro usage
- Works in Scittle

**Cons:**
- Requires rewriting macros
- Complex macro expansion logic

#### Solution 3: Use Emmy's sci-macro Pattern
Emmy provides a utility for writing macros that work in SCI:

```clojure
;; In .cljc file
#?(:clj
   (defmacro my-macro [x] (list 'do x x))
   :cljs
   (def my-macro ^:sci/macro
     (fn [&form &env x]
       (list 'do x x))))
```

**Pros:**
- Works in both Clojure and ClojureScript
- Proper macro semantics
- Emmy provides utilities

**Cons:**
- Requires dual implementation
- More complex code

### 4. Current Status

**What Works:**
✅ Direct API calls to Trove functions
✅ All log levels working
✅ Complex data logging
✅ Error logging
✅ Namespaces loading properly

**What Doesn't Work:**
❌ `trove/log!` macro (can't expand in SCI context)
❌ `trove/set-log-fn!` macro (can't expand in SCI context)
❌ `sente-lite.logging` macros (depends on Trove macros)

### 5. Recommendation for Phase 3

**Use Direct API Approach:**
1. Keep using `console/get-log-fn` directly
2. Create wrapper functions in `sente-lite.logging` that use direct API
3. Avoid macros in browser context
4. Document the limitation

**Alternative for Future:**
If macro support is critical:
1. Create `:sci/macro` versions of Trove macros
2. Use Emmy's sci-macro pattern
3. Maintain dual implementations

---

## Implementation Strategy

### For sente-lite.logging in Browser:

```clojure
;; Browser version (no macros)
#?(:cljs
   (do
     (require '[taoensso.trove.console :as console])
     
     (def ^:private log-fn (console/get-log-fn))
     
     (defn log!
       "Direct API wrapper for Trove logging (no macros in browser)"
       [level id data]
       (log-fn (str *ns*) nil level id (delay {:data data})))))
```

### For JVM/Babashka:

```clojure
;; JVM version (with macros)
#?(:clj
   (do
     (require '[taoensso.trove :as trove])
     
     (defmacro log!
       "Macro-based logging for JVM"
       [level id data]
       `(trove/log! {:level ~level :id ~id :data ~data}))))
```

---

## Conclusion

**SCI/Scittle can support macros, but requires:**
1. Proper `:sci/macro` metadata
2. `&form` and `&env` parameters
3. Manual macro expansion logic

**For our use case:**
- Direct API calls are simpler and more reliable
- No need to rewrite Trove macros
- Maintains compatibility across platforms
- Clear separation between browser and JVM implementations

**Next Steps:**
1. Update `sente-lite.logging` to use direct API in browser
2. Keep macro-based approach for JVM/Babashka
3. Test all log levels in browser
4. Complete Phase 3 testing

---

## References

- [SCI README - Macros Section](https://github.com/babashka/sci/blob/master/README.md#macros)
- [Scittle Documentation](https://github.com/babashka/scittle)
- [Emmy sci-macro Pattern](https://github.com/mentat-collective/emmy)
- [SCI Issue #397 - Macro Namespaces](https://github.com/babashka/sci/issues/397)
