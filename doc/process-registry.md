# Process Registry Design

**Created:** 2025-12-20
**Status:** Design Complete - No Implementation Needed
**Context:** Emerged from atom-sync and log-routing module implementation

---

## TL;DR - The Conclusion

**No separate registry needed.** Use Clojure primitives:

```clojure
Var (defonce) = stable identity (FQN)
Atom          = mutable container
@/reset!      = get/set ephemeral instance
resolve       = lookup by FQN string
intern        = dynamic creation
```

The "registry" is just Clojure namespaces. No framework required.

---

## Problem Statement

Current sente-lite usage requires hard-coded references to:
- WebSocket port numbers
- Channel names
- Atom instances
- Client-id instances

**Core issue:** Instance references are not transferable over the wire.

```clojure
;; Process A - has actual atom instance
(def my-atom (atom {:count 0}))
(pub/start! client-id my-atom {:atom-id :app-state})

;; Process B - needs local atom instance
(def my-local-atom (atom {}))  ;; ← Where does this come from?
(sub/start! client-id my-local-atom {:atom-id :app-state})

;; Process B application code
;; How does it FIND my-local-atom without hard-coded reference?
```

The receiving process needs a way to:
1. Create instances dynamically on demand
2. Register them with discoverable names
3. Let application code find them by name

---

## Key Design Decisions

### 1. GUID Required for Instance ID

**Decision:** Ephemeral processes (browsers, bb workers) need guaranteed unique IDs.
Human-readable names are *attributes*, not identifiers.

```clojure
{:instance-id "550e8400-e29b-41d4-a716-446655440000"  ;; GUID, always unique

 ;; Attributes (descriptive, not unique)
 :process-type :scittle      ;; or :bb, :nbb, :jvm
 :app-name "my-dashboard"    ;; user-provided
 :role :worker}              ;; or :server, :client
```

### 2. Hierarchical Namespacing

**Decision:** Flat doesn't scale. Use hierarchical paths.

```clojure
:process-id/atoms/app-state
:process-id/atoms/user-prefs
:process-id/handlers/on-click
:process-id/channels/log-routing
```

### 3. Three-Layer Model

```
┌─────────────────────────────────────────────────────────────┐
│                    APP-TYPE LAYER                            │
│  Schema/Contract: what resources exist in this type         │
│                                                              │
│  app-type: "dashboard-server"                               │
│  resources: :app-state (atom), :user-prefs (atom)           │
│                                                              │
│  "All dashboard-servers have :app-state"                    │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ instantiated as
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    IDENTITY LAYER                            │
│  Which one: stable name for a logical process               │
│                                                              │
│  identity-id: "dashboard-prod"                              │
│  app-type: "dashboard-server"                               │
│                                                              │
│  "dashboard-prod is a dashboard-server"                     │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ maps to (current)
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    INSTANCE LAYER                            │
│  Ephemeral, per-run, GUID                                   │
│                                                              │
│  instance-id: "550e8400-e29b-41d4-a716-446655440000"        │
│  started-at: 1766201278888                                  │
│                                                              │
│  "This specific running process"                            │
└─────────────────────────────────────────────────────────────┘
```

| Concept | OOP Analogy | Docker Analogy | K8s Analogy |
|---------|-------------|----------------|-------------|
| App-Type | Class | Image | Deployment spec |
| Identity | Variable name | Container name | Service name |
| Instance | Object | Running container | Pod |

---

## Prior Art Research

### Erlang gproc
**Source:** [gproc - Extended Process Registry](https://github.com/uwiger/gproc)

Names are **structured terms** `{Type, Scope, Key}`, not just atoms:
```erlang
gproc:reg({n, l, {my_app, worker, 1}}).  %% Register
gproc:lookup_pid({n, l, {my_app, worker, 1}}).  %% Lookup
```

**Key insight:** Structured keys similar to our hierarchical paths.

### Akka Actor References
**Source:** [Akka Addressing](https://doc.akka.io/libraries/akka-core/current/general/addressing.html)

Akka separates **actor path** (name) from **actor reference** (live handle):
```
Actor Path:  "akka://my-system/user/service-a/worker-1"  (name, may not exist)
Actor Ref:   ActorRef pointing to actual actor           (live handle)
```

**Key insight:** Path ≠ Reference maps exactly to our Identity ≠ Instance distinction!

### NATS Subject-Based Messaging
**Source:** [NATS Subjects](https://docs.nats.io/nats-concepts/subjects)

Dot-separated hierarchical subjects with wildcards:
```
time.us.east.atlanta
market.data.stock.AAPL
time.*.east    # Single token wildcard
time.us.>      # Multi-token wildcard
```

**Key insight:** Semantic namespaces via dot hierarchy. Request-reply with auto-inbox.

### DNS-SD / mDNS (Zeroconf)
**Source:** [DNS-SD.org](https://dns-sd.org/)

Service discovery using structured names:
```
"Living Room Printer._ipp._tcp.local"
 <Instance>.<Service>.<Protocol>.<Domain>
```

**Key insight:** Service type in name, metadata separate (SRV + TXT records).

### Synthesis

| System | Key Concept | Applied |
|--------|-------------|---------|
| gproc | Structured keys `{Type, Scope, Key}` | `[process-id category name]` |
| Akka | Path ≠ Reference | Identity ≠ Instance |
| Akka | Hierarchical paths `/user/service/worker` | `process/atoms/state` |
| NATS | Dot-separated subjects | `process.atoms.state` |
| DNS-SD | Type in name, metadata separate | Instance + attributes |

---

## The SPKI/SDSI Insight

**Key insight from SPKI/SDSI (1996):**

> "The public key IS the identity. Human-readable names are only valid locally
> to the person who defines them."

This sidesteps the entire global naming problem.

### The Problem with Global Names

X.509 tried to make human-readable names globally unique via central Certificate Authorities:
- Complexity (CA hierarchy, revocation, renewal)
- Trust problems (who trusts which CA?)
- Single points of failure

### SPKI's Solution: Local Names

```
Global Identity = Public Key (or UUID)
  - Self-generated, unforgeable, globally unique
  - No authority needed
  - The key IS the identity

Local Names = Your own mappings
  - "prod-server" means instance-abc TO ME
  - Someone else might call it "main"
  - No coordination needed
```

### Applied to sente-lite

```clojure
;; GLOBAL: Instance ID (like public key hash)
;; Self-generated UUID, globally unique, no authority
(def my-instance-id (str (random-uuid)))
;; => "550e8400-e29b-41d4-a716-446655440000"

;; LOCAL: My names for remote processes
;; Only meaningful within MY process
(def my-names
  {"prod-server" "a1b2c3d4-..."   ;; I call this instance "prod-server"
   "staging"     "e5f6g7h8-..."}) ;; I call this instance "staging"

;; Wire protocol uses GLOBAL identity (instance-id)
{:from "550e8400-e29b-41d4-a716-446655440000"
 :to   "a1b2c3d4-..."
 :resource :app-state
 :value {...}}
```

**What this gives us:**
1. **No global naming authority** - each process names others locally
2. **No collision problems** - UUIDs don't collide, local names are local
3. **Simple implementation** - just a local map

---

## The Breakthrough: FQN Vars Eliminate the Registry

**Key insight:** If both sides use the same FQN for corresponding resources,
the var itself IS the registration. No separate registry needed.

```clojure
;; Browser
(ns ui.buttons)
(defonce button-423 (atom {:pressed false}))
;; FQN: ui.buttons/button-423

;; Server (same namespace convention)
(ns ui.buttons)
(defonce button-423 (atom {}))  ;; Created dynamically via intern
;; FQN: ui.buttons/button-423

;; Wire protocol just carries the FQN string
{:fqn "ui.buttons/button-423" :value {:pressed true}}

;; Both sides resolve to their local atom
(deref (resolve 'ui.buttons/button-423))
```

**What Clojure gives us for free:**

| Need | Clojure provides |
|------|------------------|
| Unique identifier | FQN (namespace/name) |
| Hierarchy | Namespace structure |
| Lookup | `resolve`, `find-var` |
| Dynamic creation | `create-ns`, `intern` |
| Metadata | `alter-meta!` |

**The entire "registry" is:**
```clojure
(resolve (symbol fqn-string))
```

**Dynamic creation on receiver side:**
```clojure
(defn ensure-synced-atom! [fqn-string]
  (let [sym (symbol fqn-string)
        ns-sym (symbol (namespace sym))
        name-sym (symbol (name sym))]
    (create-ns ns-sym)
    (or (find-var sym)
        (intern ns-sym name-sym (atom {})))))

;; Server receives sync request for "ui.buttons/button-423"
(ensure-synced-atom! "ui.buttons/button-423")
;; → Creates ns if needed, creates var if needed, returns var
```

---

## Atoms as Indirection for Ephemeral Instances

The atom provides mutable indirection - the var is stable identity,
the atom's value is the ephemeral instance:

```clojure
;; Stable identity (var/FQN)
(ns remote.connections)
(defonce server (atom nil))
;; FQN: remote.connections/server  ← never changes

;; Ephemeral instance (atom value)
(reset! server (connect! "ws://localhost:8080"))
@server  ;; → connection-abc123

;; Server restarts, reconnect
(reset! server (connect! "ws://localhost:8080"))
@server  ;; → connection-def456 (new instance, same identity!)

;; User code uses stable identity
(send! @remote.connections/server msg)
```

**The three layers solved with Clojure primitives:**

| Layer | Mechanism | Lifecycle |
|-------|-----------|-----------|
| Identity | Var (FQN) | Stable, survives restarts |
| Indirection | Atom | Mutable container |
| Instance | `@atom` (deref) | Ephemeral, replaceable |

### Flexible Atom Value Shapes

The atom value is just data - shape it as needed:

```clojure
;; Simple: single instance
(defonce server (atom connection))

;; With properties: map
(defonce server (atom {:connection conn
                       :connected-at 1234567890
                       :status :connected}))

;; Multiple instances: vector
(defonce clients (atom [conn-1 conn-2 conn-3]))

;; Named instances: map
(defonce connections (atom {"primary" conn-1
                            "backup"  conn-2}))
```

| Need | Atom value shape |
|------|-----------------|
| Single instance | `value` |
| Instance + metadata | `{:instance val :meta {...}}` |
| Multiple instances | `[val1 val2 val3]` |
| Named instances | `{"name1" val1 "name2" val2}` |

---

## Trust Boundaries

Within a trusted world, you don't need signing to assert a name:

```
┌─────────────────────────────────────────────┐
│           TRUSTED WORLD                      │
│                                              │
│  Server ←──→ Browser (code from server)     │
│    ↕                                         │
│  BB Worker (same deployment)                 │
│                                              │
│  No signing needed.                          │
│  Names are just conventions.                 │
│  Server is implicit authority.               │
└─────────────────────────────────────────────┘
```

For sente-lite's typical use cases:
- Server + its browsers = trusted (code comes from server)
- Internal BB services = trusted (same deployment)
- Same organization = trusted (shared secrets/config)

**Browser-Server Simplification:**
```clojure
;; Server assigns instance-id on connect
[:welcome {:instance-id "browser-abc123"}]

;; Browser learns its own identity from server
;; Server is the authority (it already is anyway)
```

Crossing trust boundaries (federation, third-party) is a different problem
entirely - and not sente-lite's problem to solve.

---

## Summary

**The "registry" is just Clojure:**

```clojure
(defonce my-resource (atom nil))        ;; stable identity (FQN)
(reset! my-resource new-instance)       ;; swap ephemeral instance
@my-resource                            ;; get current instance
(resolve 'ns/my-resource)               ;; lookup by FQN string
(intern 'ns 'name (atom {}))            ;; dynamic creation
```

No framework needed. Just Clojure.

---

## Cross-Runtime Verification (2025-12-20)

**VERIFIED: All core functions work across ALL deployment targets!**

| Runtime | Host Stack | Status |
|---------|------------|--------|
| Babashka | SCI / Clojure / GraalVM | ✅ 8/8 tests pass |
| Scittle | SCI / ClojureScript / Browser JS | ✅ 8/8 tests pass |
| nbb | SCI / ClojureScript / Node.js | ✅ 8/8 tests pass |

Functions verified on each runtime:

| Function | BB | Scittle | nbb | Notes |
|----------|:--:|:-------:|:---:|-------|
| `create-ns` | ✅ | ✅ | ✅ | Creates namespace dynamically |
| `intern` | ✅ | ✅ | ✅ | Requires namespace to exist first |
| `resolve` | ✅ | ✅ | ✅ | Returns var from FQN symbol |
| `find-var` | ✅ | ✅ | ✅ | Alternative to resolve |
| `deref` (@@) | ✅ | ✅ | ✅ | Double-deref for atom value |

**The `ensure-atom!` pattern works on all three runtimes:**

```clojure
(defn ensure-atom! [fqn-str]
  (let [sym (symbol fqn-str)
        ns-sym (symbol (namespace sym))
        name-sym (symbol (name sym))]
    (create-ns ns-sym)
    (or (find-var sym)
        (intern ns-sym name-sym (atom {})))))

;; Usage - works identically in BB and browser
(ensure-atom! "ui.buttons/button-1")
(reset! @(resolve 'ui.buttons/button-1) {:pressed true})
@@(resolve 'ui.buttons/button-1)  ;; => {:pressed true}
```

**Implication:** The FQN-based registry approach is viable across all sente-lite deployment targets:
- Server: Babashka
- Browser client: Scittle
- Node.js client/worker: nbb

---

## Next Steps

1. ~~Verify `intern`/`resolve` work in SCI/Scittle~~ ✅ Done (2025-12-20)
2. **Prototype FQN-based atom-sync** - SCI supports it!
3. **atom-sync Phase 2** - Two-way sync with conflict resolution

---

## References

- [gproc - Extended Process Registry for Erlang](https://github.com/uwiger/gproc)
- [Akka Actor References, Paths and Addresses](https://doc.akka.io/libraries/akka-core/current/general/addressing.html)
- [NATS Subject-Based Messaging](https://docs.nats.io/nats-concepts/subjects)
- [DNS-SD - DNS Service Discovery](https://dns-sd.org/)
- [RFC 2692/2693 - SPKI Requirements and Certificate Theory](https://www.rfc-editor.org/rfc/rfc2692)
