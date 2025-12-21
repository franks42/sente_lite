# FQN Registry API Design

**Created:** 2025-12-20
**Status:** Design Draft
**Context:** MVP for process registry using Clojure namespace primitives

---

## Overview

A minimal registry API for managing named resources across processes. Built on Clojure's `intern`/`resolve` primitives, verified to work on all sente-lite runtimes (Babashka, Scittle, nbb).

**Key principle:** Users work with short, relative names. Implementation details (atoms, vars, namespaces) are hidden.

---

## Naming Convention

### Structure

```
<category>/<name>
<category>.<subcategory>/<name>

Examples:
state/user-prefs
state/session
config/theme
sync/shared-counter
ui.forms/login
```

### Rules

- Category: lowercase, dots for hierarchy
- Name: lowercase, hyphens allowed
- No special characters except `.` (category) and `-` (name)

### Internal Mapping

User-facing names are prefixed with a hidden root namespace:

```
User writes:     "state/user-prefs"
Internal FQN:    sente-lite.registry.state/user-prefs
Wire protocol:   "state/user-prefs"
```

The root follows the pattern `<project-root>.registry`:
- Default: `sente-lite.registry`
- Configurable per project: `my-app.registry`
- Hidden from users
- Same relative name maps to local namespace on each process

---

## API

### Configuration

```clojure
(get-reg-root)
;; => "sente-lite.registry" (default)

(set-reg-root! "my-app.registry")
;; Set project-specific root
```

### Registration

```clojure
(register! name initial-value)
;; Create resource with initial value
;; Returns: the reference (for caching)
;; Example: (register! "state/user-prefs" {:theme "dark"})

(ensure! name)
;; Create if not exists (nil initial value), return reference
;; Idempotent - safe to call multiple times
;; Example: (ensure! "state/counter")
```

### Read

```clojure
(get-value name)
;; Get current value
;; Example: (get-value "state/user-prefs") => {:theme "dark"}

(get-ref name)
;; Get the reference itself (for caching in hot paths)
;; Returns nil if not registered
;; Example: (let [ref (get-ref "state/counter")]
;;            (fn [v] (set-ref! ref v)))  ;; cached, no lookup
```

### Write

```clojure
(set-value! name new-value)
;; Replace value
;; Example: (set-value! "state/user-prefs" {:theme "light"})

(swap-value! name update-fn & args)
;; Update via function (like swap!)
;; Example: (swap-value! "state/counter" inc)
;; Example: (swap-value! "state/user-prefs" assoc :theme "light")
```

### Write (with reference)

For hot paths where lookup overhead matters:

```clojure
(set-ref! ref new-value)
;; Set value using cached reference
;; Example: (set-ref! (get-ref "state/counter") 42)

(swap-ref! ref update-fn & args)
;; Update using cached reference
;; Example: (swap-ref! (get-ref "state/counter") inc)
```

### Discovery

```clojure
(registered? name)
;; Check if name exists
;; Example: (registered? "state/user-prefs") => true

(list-registered)
;; List all registered names (relative)
;; Example: => #{"state/user-prefs" "state/counter" "config/theme"}
```

### Cleanup

```clojure
(unregister! name)
;; Clear value and remove from tracking
;; Note: underlying var cannot be removed, only cleared
;; Example: (unregister! "state/old-data")
```

### Watch (Reactive Updates)

```clojure
(watch! name key callback)
;; Add watch for value changes
;; callback: (fn [key name old-value new-value] ...)
;; Example: (watch! "state/counter" :my-watch
;;            (fn [k n old new] (println "Changed:" old "->" new)))

(unwatch! name key)
;; Remove watch
;; Example: (unwatch! "state/counter" :my-watch)
```

---

## Wire Protocol

For cross-process sync (atom-sync module), messages carry **relative names**:

```clojure
{:fqn "state/user-prefs"
 :value {:theme "dark"}
 :timestamp 1703084400000}
```

Each process translates to its own internal namespace. The relative name is the shared identity.

---

## Usage Examples

### Basic Usage

```clojure
(ns my-app.core
  (:require [sente-lite.registry :as reg]))

;; Register with initial value
(reg/register! "state/user" {:name "Alice" :role :admin})

;; Read
(reg/get-value "state/user")
;; => {:name "Alice" :role :admin}

;; Update
(reg/swap-value! "state/user" assoc :last-seen (System/currentTimeMillis))

;; Check
(reg/registered? "state/user")  ;; => true
(reg/list-registered)           ;; => #{"state/user"}
```

### Hot Path with Cached Reference

```clojure
;; Cache reference at startup
(def counter-ref (reg/ensure! "metrics/request-count"))

;; Use in hot path (no lookup overhead)
(defn handle-request [req]
  (reg/swap-ref! counter-ref inc)
  (process-request req))
```

### Cross-Process Sync

```clojure
;; Publisher (server)
(reg/register! "sync/shared-state" {:count 0})
(reg/watch! "sync/shared-state" :sync-watch
  (fn [_ name _ new-val]
    (broadcast! [:sync/update {:fqn name :value new-val}])))

;; Subscriber (browser)
(on! client {:event-id :sync/update
             :callback (fn [{:keys [fqn value]}]
                         (reg/set-value! fqn value))})
```

---

## Implementation Notes

### Code Location

```
src/sente_lite/registry.cljc    ;; All API functions
```

**Namespace structure:**
```
sente-lite.registry/register!           ← API function (root ns)
sente-lite.registry/get-value           ← API function (root ns)
sente-lite.registry.state/user-prefs    ← registered resource (child ns)
sente-lite.registry.config/theme        ← registered resource (child ns)
```

**Why no conflicts:**
1. API functions live in root namespace: `sente-lite.registry`
2. Registered resources live in child namespaces: `sente-lite.registry.<category>/*`
3. Naming convention requires `<category>/<name>` format
4. Validation rejects invalid names (no category, special chars)

A user cannot register something that shadows API functions because:
- `"register!"` - invalid (no category, has `!`)
- `"get-value"` - invalid (no category)
- `"state/user-prefs"` - valid, creates `sente-lite.registry.state/user-prefs`

### Underlying Mechanism

```clojure
;; Internal (hidden from users)
(defn- absolute-fqn [relative-name]
  (str @reg-root "." relative-name))

;; register! internally does:
(let [fqn (absolute-fqn name)
      sym (symbol fqn)
      ns-sym (symbol (namespace sym))
      name-sym (symbol (clojure.core/name sym))]
  (create-ns ns-sym)
  (intern ns-sym name-sym (atom initial-value)))
```

### Cross-Runtime Compatibility

Verified on all sente-lite targets:
- Babashka (SCI/Clojure/GraalVM)
- Scittle (SCI/ClojureScript/Browser)
- nbb (SCI/ClojureScript/Node.js)

### Performance

| Operation | Cost | Recommendation |
|-----------|------|----------------|
| `get-value` | ~10-50x vs direct | OK for normal use |
| `get-ref` + `set-ref!` | ~1x (cached) | Use for hot paths |
| `register!` | Expensive | Do once at startup |

### Error Handling

```clojure
;; Invalid name format
(register! "Invalid Name!" {})
;; => throws: "Invalid registry name format"

;; Name validation regex
#"[a-z][a-z0-9]*(\.[a-z][a-z0-9]*)*/[a-z][a-z0-9-]*"
```

---

## Design Rationale

### Why Hide the Root Namespace?

1. **Simplicity** - Users don't need to know implementation details
2. **No pollution** - Lives under project namespace (`sente-lite.registry.*`)
3. **Flexibility** - Root can be changed without affecting user code
4. **Local naming** - Each process has its own namespace tree
5. **Convention** - Pattern `<project>.registry` is clear and predictable

### Why Relative Names on Wire?

1. **Decoupled** - Processes don't need same root config
2. **Shorter** - Less bandwidth
3. **SPKI/SDSI principle** - Names are local, identity is the relative path

### Why Atoms Underneath?

1. **Mutable state** - Values change
2. **Watch support** - Built-in reactivity
3. **Thread-safe** - swap! semantics
4. **Hidden** - Users don't need to know

---

## Lifecycle Management

### Principle: App Owns Cleanup

The registry does not automatically clean up resources. **The app developer must decide what to clean up and when** - typically in the `:on-disconnect` handler.

This matches sente-lite's design philosophy:
- Infrastructure provides mechanisms (register, unregister)
- App provides policy (what to keep, what to clean)

### Why App-Controlled?

Only the app knows:
- Which resources are tied to a connection
- Which should persist across reconnects
- Which are shared vs connection-specific

```
Channel closes:
├── sync/shared-counter    → app decides: cleanup
├── handlers/on-update     → app decides: cleanup
├── state/user-prefs       → app decides: KEEP
└── state/session          → app decides: KEEP
```

### Cleanup API

```clojure
;; Individual
(unregister! "sync/shared-counter")

;; Bulk by prefix (convenience)
(unregister-prefix! "sync/")

;; List for inspection
(list-registered)                    ;; all
(list-registered-prefix "sync/")     ;; filtered
```

### Recommended Pattern

```clojure
(def client
  (make-client
    {:url "ws://..."

     :on-connect
     (fn [client-id]
       ;; Register sync resources
       (register! "sync/shared-state" {})
       (watch! "sync/shared-state" :sync-watch sync-to-remote!))

     :on-disconnect
     (fn [client-id reason]
       ;; Clean up sync resources
       (unwatch! "sync/shared-state" :sync-watch)
       (unregister-prefix! "sync/")
       ;; Leave state/* alone - app still using
       )}))
```

### Naming Convention for Lifecycle

Suggested prefixes to help organize cleanup:

| Prefix | Lifecycle | Cleanup on disconnect? |
|--------|-----------|------------------------|
| `state/` | Persistent | No - app state |
| `config/` | Persistent | No - configuration |
| `sync/` | Transient | Yes - tied to connection |
| `handlers/` | Transient | Yes - tied to connection |
| `cache/` | Optional | Maybe - depends on app |

This is convention, not enforcement. App decides.

### Future Options (Not in MVP)

If apps need more sophisticated lifecycle management:

**Option: Scoped Registration**
```clojure
(register! "sync/counter" value {:scope client-id})
(unregister-scope! client-id)  ;; bulk cleanup by scope
```

**Option: Tagged Registration**
```clojure
(register! "sync/counter" value {:tags #{:transient}})
(unregister-tagged! :transient)
```

These add complexity - start simple, add if needed.

---

## Future Considerations

Not in MVP, but possible extensions:

- **Metadata** - `(get-meta name)`, `(set-meta! name meta)`
- **Validation** - Schema per name, validate on set
- **History** - Track previous values
- **TTL** - Auto-expire unused registrations
- **Namespacing** - `(with-prefix "myapp" ...)` for scoped operations

---

## Summary

| Aspect | Design |
|--------|--------|
| Source file | `src/sente_lite/registry.cljc` |
| API namespace | `sente-lite.registry` |
| User-facing names | Relative: `state/user-prefs` |
| Internal storage | `sente-lite.registry.state/user-prefs` |
| Wire protocol | Relative names |
| Root namespace | `<project>.registry` (hidden, configurable) |
| Default root | `sente-lite.registry` |
| Underlying | Atoms in dynamically created vars |
| Runtimes | BB, Scittle, nbb |
| No conflicts | API in root ns, resources in child ns |
