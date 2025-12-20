# atom-sync Module

**Purpose:** Synchronize atom state across processes via sente-lite.
**Source:** `doc/sente-lite-modules.md` State Synchronization section
**Status:** Phase 1 Complete

---

## Status

- [x] Phase 1: One-way sync (server→client) BB-to-BB (COMPLETE - 2025-12-20)
- [ ] Phase 2: Two-way sync with conflict resolution
- [ ] Phase 3: Scittle browser support

---

## Quick Start

```bash
# From project root
bb modules/atom-sync/test/test_one_way.bb
```

---

## Architecture

### Pattern 1: One-Way Sync (Phase 1)

```
Server Atom
    ↓ add-watch
    ↓ (on change)
    ↓ sente publish
    ↓
Client Atom
    ↓ receive message
    ↓ reset!
    ↓ triggers watchers
```

**Use Cases:** Server→Client state push, read-only client state

### Pattern 2: Two-Way Sync (Phase 2)

```
Client A        Server          Client B
   |               |                |
   |--[update]---->|                |
   |               |---[broadcast]->|
   |<--[broadcast]-|                |
```

**Conflict Resolution:** Last-write-wins (LWW) with timestamps

---

## Files

```
modules/atom-sync/
├── README.md                     # This file
├── src/atom_sync/
│   ├── publisher.cljc            # Server-side: watch atom, publish changes
│   └── subscriber.cljc           # Client-side: receive updates, reset atom
└── test/
    └── test_one_way.bb           # One-way sync integration test
```

---

## API

### Publisher (Server-side)

```clojure
(require '[atom-sync.publisher :as pub])
(require '[sente-lite.client-bb :as client])

;; Create atom and start publishing changes
(def app-state (atom {:users [] :count 0}))

;; Start publishing atom changes to a channel
(def watch-key
  (pub/start! client-id app-state
    {:atom-id :app-state              ; Unique ID for this atom
     :channel "atom-sync"}))          ; Optional: defaults to "atom-sync"

;; Changes are automatically published
(swap! app-state assoc :count 1)      ; Publishes {:atom-id :app-state :value {...}}

;; Stop publishing
(pub/stop! app-state watch-key)
```

### Subscriber (Client-side)

```clojure
(require '[atom-sync.subscriber :as sub])

;; Create local atom to sync
(def app-state (atom {}))

;; Start receiving updates
(def handler-id
  (sub/start! client-id app-state
    {:atom-id :app-state              ; Must match publisher's atom-id
     :channel "atom-sync"             ; Optional: defaults to "atom-sync"
     :on-update (fn [old new]         ; Optional: callback on updates
                  (println "Updated:" old "->" new))}))

;; Atom is automatically updated when server changes
@app-state  ; => {:users [] :count 1}

;; Stop receiving
(sub/stop! client-id handler-id)
```

---

## Wire Format

```clojure
;; Publisher sends to channel
{:atom-id :app-state               ; Atom identifier
 :value {:users [] :count 1}       ; Current atom value
 :version 42                       ; Monotonic version (for conflict detection)
 :timestamp 1766200992881}         ; Unix timestamp ms

;; Channel message received by subscriber
{:event-id :sente-lite/channel-msg
 :data {:channel-id "atom-sync"
        :data {...atom-sync-message...}
        :from "conn-123"}}
```

---

## Implementation Notes

### One-Way Sync (Phase 1)
- Uses `add-watch` to observe atom changes
- Publishes to sente-lite channel on every change
- Subscriber receives and `reset!`s local atom
- No conflict resolution needed (server is authoritative)

### Two-Way Sync (Phase 2 - Future)
- Each update includes version number
- Server compares versions
- Higher version/timestamp wins (LWW)
- Clients receive broadcasts of all changes

---

## Progress Log

### 2025-12-20
- **Phase 1 Complete!**
- Created module structure with publisher.cljc and subscriber.cljc
- Implemented one-way sync: publisher watches atom → publishes → subscriber receives → resets local atom
- Uses sente-lite pub/sub channels for message routing
- Both publisher and subscriber are clients of a shared server
- Version numbering for future conflict detection
- Integration test passing (3 changes synced successfully)
- Also uses log-routing module for observability in tests

**Test Output:**
```
=== atom-sync One-Way Test ===
...
8. Making changes to source atom...
   - Setting :count to 1...
   [SYNC] Updated: {} -> {:count 1, :items []}
   - Adding item "apple"...
   [SYNC] Updated: {:count 1, :items []} -> {:count 1, :items [apple]}
   - Adding item "banana" and setting :count to 2...
   [SYNC] Updated: {:count 1, :items [apple]} -> {:count 2, :items [apple banana]}
...
SUCCESS: Source and target atoms are in sync!
```

