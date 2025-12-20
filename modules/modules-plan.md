# sente-lite Modules Plan

**Created:** 2025-12-20
**Purpose:** Real-world use cases to validate sente-lite APIs
**Source:** Extracted from `doc/sente-lite-modules.md`

---

## Overview

Independent modules that use sente-lite for specific functionality. Each module:
- Lives in its own directory under `modules/`
- Is a standalone project with its own README.md
- Tests BB-to-BB first, then adds Scittle/browser support
- Documents implementation steps and learnings

---

## Module 1: log-routing

**Goal:** Route Trove log messages through sente-lite channel to remote collector.

**Status:** Phase 1 MVP
**Complexity:** Low (~50-100 LOC)
**Dependencies:** Trove, sente-lite core

**Architecture:**
```
[Process A]                    [Process B]
   Trove                         Trove
     |                             ^
     v                             |
 log-sender  ---> sente-lite ---> log-receiver
```

**Wire Format:**
```clojure
;; Client sends
[:log-routing/entry
 {:ns "my.namespace"
  :level :info
  :id :my.namespace/event-id
  :data {:key "value"}
  :timestamp 1766108726643}]

;; Server receives
{:event-id :log-routing/entry
 :data {:ns "my.namespace"
        :level :info
        ...}
 :uid "conn-123"}
```

**Phase 1 MVP Code Sketch:**
```clojure
;; Client - wrap Trove's log-fn
(defn make-remote-log-fn [local-log-fn sente-client]
  (fn [ns coords level id lazy_]
    (local-log-fn ns coords level id lazy_)
    (try
      (sente/send! sente-client
        [:log-routing/entry
         {:ns ns :level level :id id :data (force lazy_)}])
      (catch Exception _ nil))))

;; Server - handle log entries
(on! server-client-id
  {:event-id :log-routing/entry
   :callback (fn [msg]
               (let [data (get msg :data)
                     uid (get msg :uid)]
                 (trove/log! (assoc data :client-uid uid))))})
```

**Test Configurations:**
1. BB server -> BB server (Phase 1)
2. Scittle browser -> BB server (Phase 3)

---

## Module 2: atom-sync

**Goal:** Synchronize atom state across processes.

**Status:** Phase 1 Complete (One-way sync)
**Complexity:** Low-Medium
**Dependencies:** sente-lite core, uses log-routing for observability

### Pattern 1: One-Way Atom Syncing (Simple)

**Use Cases:** Server→Client state push, read-only client state

**Architecture:**
```
Server Atom
    ↓ add-watch
    ↓ (on change)
    ↓ sente send
    ↓
Client Atom
    ↓ receive message
    ↓ reset!
    ↓ triggers watchers
```

**Code Pattern:**
```clojure
;; Server side
(def app-state (atom {:users [] :count 0}))

(add-watch app-state :sync
  (fn [key ref old-state new-state]
    ;; Send to all connected clients
    (doseq [uid (get-connected-uids)]
      (sente/send-to-client! uid [:state/update new-state]))))

;; Client side
(def app-state (atom {}))

(on! client-id
  {:event-id :state/update
   :callback (fn [msg]
               (reset! app-state (get msg :data)))})
```

### Pattern 2: Two-Way Atom Syncing

**Use Cases:** Shared editable state, real-time collaboration

**Architecture:**
```
Client A        Server          Client B
   |               |                |
   |--[update]---->|                |
   |               |---[broadcast]->|
   |<--[broadcast]-|                |
```

**Conflict Resolution:** Last-write-wins (LWW) with timestamps
- Each update carries timestamp
- Server compares timestamps
- Higher timestamp wins

---

## Module 3: nrepl-bridge (Future)

**Goal:** nREPL protocol over sente-lite WebSocket.

**Use Cases:**
- Browser REPL without separate nREPL port
- Remote REPL through firewalls
- Unified connection for app + REPL

**Deferred:** Start with modules 1 & 2 first.

---

## Development Order

1. **log-routing** - Simplest, one-way data flow
   - Learn sente-lite send/receive patterns
   - Establish message format conventions
   - Test reconnection behavior

2. **atom-sync** - Bidirectional, uses log-routing
   - Learn bidirectional patterns
   - Test with log-routing for visibility
   - Handle edge cases (conflicts, reconnects)

3. **nrepl-bridge** - Complex protocol
   - Build on learnings from 1 & 2

---

## Conventions

### Message Format
```clojure
;; All module messages use namespaced event-ids
[:log-routing/entry {:level :info :msg "..." :ns "..." :timestamp ...}]
[:atom-sync/update {:atom-id :my-atom :value {...} :version 42}]
[:atom-sync/request-full {:atom-id :my-atom}]
```

### Directory Structure
```
modules/
  modules-plan.md          # This file
  log-routing/
    README.md              # Implementation docs
    src/
    test/
  atom-sync/
    README.md
    src/
    test/
```

### Testing Strategy
- BB-to-BB first (fast, easy debugging)
- Add Scittle after BB works
- Use log-routing to observe other modules

---

## Progress Log

### 2025-12-20
- Created modules directory and plan
- Extracted designs from doc/sente-lite-modules.md
- **log-routing Phase 1 Complete**: BB-to-BB test passing (3 logs received)
- **atom-sync Phase 1 Complete**: One-way sync working (3 changes synced)

