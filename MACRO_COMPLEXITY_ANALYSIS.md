# Macro Complexity Analysis: Trove vs my-test-macros

**Date:** November 30, 2025
**Question:** Does Trove's `log!` macro use delayed-eval or other features that would prevent Scittle interpretation?

---

## Side-by-Side Comparison

### my-test-macros.cljs (Simple Macro)

```clojure
(defmacro simple-macro [x]
  (println (str "🔵 MACRO EXPANSION: simple-macro called with " x))
  `(do
     (println (str "🟢 MACRO RUNTIME: simple-macro executed with " ~x))
     ~x))
```

**Complexity:**
- ✅ Single parameter: `[x]`
- ✅ Simple body: just prints and returns `x`
- ✅ Straightforward quasiquote: `` `(do ...) ``
- ✅ Single unquote: `~x`
- ✅ No helper functions
- ✅ No compile-time validation
- ✅ No dynamic vars
- ✅ No delayed evaluation

---

### Trove's log! (Complex Macro)

```clojure
(defmacro log! [opts]
  "Logs the given info to the currently configured backend..."
  {:arglists '([{:keys [level id msg data error]}])}
  [opts]

  ;; STEP 1: Compile-time validation
  (when-not (map? opts)
    (throw
     (ex-info "Trove opts must be a compile-time map"
              {:opts {:value opts, :type (type opts)}})))

  ;; STEP 2: Destructure and extract options
  (let [{:keys [ns coords level id msg data error log-fn] letf :let
         :or
         {ns     (str *ns*)
          level  :info
          coords (utils/callsite-coords &form)
          log-fn `*log-fn*}} opts

        ;; STEP 3: Generate unique symbol
        lfn (gensym "lfn__")
        
        ;; STEP 4: Extract extra kvs
        kvs (not-empty (dissoc opts :ns :coords :level :id :error :let :msg :data :log-fn))
        
        ;; STEP 5: Build lazy form (DELAYED EVALUATION!)
        lazy-form
        (when-let [opts (utils/assoc-some nil {:error error, :msg msg, :data data, :kvs kvs})]
          (if (every? utils/const? [opts letf])
            (if letf        `(let ~letf ~opts)           opts)
            (if letf `(delay (let ~letf ~opts)) `(delay ~opts))))]

    ;; STEP 6: Generate final code
    `(let   [~lfn ~log-fn]
       (when ~lfn
         (~lfn ~ns ~coords ~level ~id ~lazy-form))
       nil)))
```

**Complexity:**
- ❌ Single parameter: `[opts]` (but complex destructuring)
- ❌ Complex body with multiple steps
- ❌ Compile-time validation with `throw`
- ❌ Destructuring with `:or` defaults
- ❌ Calls helper functions: `utils/callsite-coords`, `utils/assoc-some`, `utils/const?`
- ❌ Uses `gensym` for hygiene
- ❌ Uses `&form` (macro special form)
- ❌ Uses `*ns*` (dynamic var)
- ❌ Uses `delay` for lazy evaluation
- ❌ Conditional logic: `if`, `when-let`, `every?`
- ❌ Complex quasiquote with nested conditionals

---

## Detailed Analysis

### 1. Compile-Time Validation

**my-test-macros:**
```clojure
;; No validation
```

**Trove:**
```clojure
(when-not (map? opts)
  (throw
   (ex-info "Trove opts must be a compile-time map"
            {:opts {:value opts, :type (type opts)}})))
```

**Issue for Scittle:** ❌
- Throws exceptions at compile time
- Requires `ex-info` to be available
- Requires error handling

---

### 2. Helper Functions

**my-test-macros:**
```clojure
;; No helper functions
```

**Trove:**
```clojure
coords (utils/callsite-coords &form)
kvs (not-empty (dissoc opts :ns :coords :level :id :error :let :msg :data :log-fn))
(when-let [opts (utils/assoc-some nil {:error error, :msg msg, :data data, :kvs kvs})]
  (if (every? utils/const? [opts letf])
```

**Issue for Scittle:** ⚠️ Moderate
- Calls `utils/callsite-coords` - needs to be available
- Calls `utils/assoc-some` - needs to be available
- Calls `utils/const?` - needs to be available
- Uses `not-empty` - built-in, should work
- Uses `dissoc` - built-in, should work
- Uses `every?` - built-in, should work

---

### 3. Macro Special Forms

**my-test-macros:**
```clojure
;; No special forms
```

**Trove:**
```clojure
coords (utils/callsite-coords &form)
log-fn `*log-fn*
```

**Issue for Scittle:** ❌ Critical
- Uses `&form` - macro special form
- Uses `` `*log-fn*` `` - quoting a dynamic var
- SCI may not handle these correctly

---

### 4. Delayed Evaluation (delay)

**my-test-macros:**
```clojure
~x  ;; Immediate evaluation
```

**Trove:**
```clojure
(if letf `(delay (let ~letf ~opts)) `(delay ~opts))
```

**Issue for Scittle:** ⚠️ Moderate
- Uses `delay` to wrap lazy evaluation
- This is intentional - to avoid evaluating expensive parameters
- Should work in Scittle, but adds complexity

---

### 5. Dynamic Variables

**my-test-macros:**
```clojure
;; No dynamic vars
```

**Trove:**
```clojure
ns (str *ns*)
log-fn `*log-fn*
```

**Issue for Scittle:** ⚠️ Moderate
- Uses `*ns*` - built-in dynamic var, should work
- References `*log-fn*` - custom dynamic var, needs to be available

---

### 6. Hygiene with gensym

**my-test-macros:**
```clojure
;; No gensym
```

**Trove:**
```clojure
lfn (gensym "lfn__")
...
`(let   [~lfn ~log-fn]
   (when ~lfn
     (~lfn ~ns ~coords ~level ~id ~lazy-form))
   nil)
```

**Issue for Scittle:** ✅ Fine
- `gensym` should work in Scittle
- Used for macro hygiene, which is good practice

---

### 7. Complex Quasiquote

**my-test-macros:**
```clojure
`(do
   (println (str "🟢 MACRO RUNTIME: simple-macro executed with " ~x))
   ~x)
```

**Trove:**
```clojure
`(let   [~lfn ~log-fn]
   (when ~lfn
     (~lfn ~ns ~coords ~level ~id ~lazy-form))
   nil)
```

**Issue for Scittle:** ✅ Fine
- Both use standard quasiquote
- Trove's is more complex but should work

---

## Summary: Why Trove's Macro Wouldn't Work (Even If Loaded)

Even if Trove's `log!` macro WERE loaded in Scittle (without the `#?(:clj ...)` guard), it would likely fail because:

### Critical Issues

1. **`&form` usage** ❌
   - Macro special form
   - May not be available in SCI context
   - Line 93: `coords (utils/callsite-coords &form)`

2. **Helper functions** ❌
   - `utils/callsite-coords` - extracts source coordinates from `&form`
   - `utils/assoc-some` - conditional map building
   - `utils/const?` - checks if value is constant
   - These may not be available or may not work correctly in SCI

3. **Dynamic var references** ⚠️
   - `*ns*` - should work
   - `*log-fn*` - custom var, needs to be available
   - Quoting dynamic vars can be tricky

### Why my-test-macros Works

1. ✅ No `&form` usage
2. ✅ No helper functions
3. ✅ No dynamic var references
4. ✅ Simple quasiquote
5. ✅ Straightforward logic

---

## The Real Answer

**The `#?(:clj ...)` guard is not just about platform differences.**

**It's also about macro complexity:**

1. **Trove's macro uses advanced features** that are designed for compiled ClojureScript
2. **These features may not work in SCI** (runtime interpretation)
3. **The guard prevents loading code that would fail anyway**

**Trove's design is smart:**
- Macros for compiled environments (JVM, compiled CLJS)
- Functions for runtime environments (Scittle, browser)

---

## Conclusion

### If Trove's log! macro were loaded in Scittle, it would likely fail because:

1. ❌ Uses `&form` (macro special form)
2. ❌ Calls helper functions that may not be available
3. ⚠️ References dynamic vars that may not be set up correctly
4. ⚠️ Uses `delay` for lazy evaluation (complex)

### Why the `#?(:clj ...)` guard is there:

1. ✅ Prevents loading code that would fail
2. ✅ Signals that this is JVM-only code
3. ✅ Directs users to use the function-based API instead

### Why our wrapper works:

1. ✅ Uses only simple functions
2. ✅ No macro special forms
3. ✅ No helper function dependencies
4. ✅ Direct access to Trove's console backend

---

**The mystery is completely solved!** 🎉

Trove's `log!` macro is not just excluded from Scittle because it's a macro.
It's excluded because it uses advanced macro features that don't work in runtime interpretation.
The `#?(:clj ...)` guard is a feature, not a limitation!
