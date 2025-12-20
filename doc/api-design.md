# sente-lite API Design

**Created:** 2025-12-19
**Updated:** 2025-12-20
**Status:** IMPLEMENTED - v2.8.0-internal-unification

## Overview

This document describes:
1. Sente's original API (the reference)
2. sente-lite's current API
3. Key differences and rationale
4. Unified subscription API design

**Goal:** Keep sente-lite's API as close to Sente as possible, while adapting to callback-based (no core.async) environments.

---

## Part 1: Sente's API (Reference)

### Client Creation

```clojure
(require '[taoensso.sente :as sente])

(let [{:keys [chsk ch-recv send-fn state]}
      (sente/make-channel-socket-client!
        "/chsk"
        {:type :auto
         :packer :edn})]

  ;; chsk     - The channel socket object
  ;; ch-recv  - core.async channel for receiving messages
  ;; send-fn  - Function to send messages
  ;; state    - Atom with connection state
  )
```

### Receiving Messages

Sente uses a **single core.async channel** (`ch-recv`) for all incoming messages:

```clojure
(go-loop []
  (when-let [{:keys [event id ?data]} (<! ch-recv)]
    (let [[event-id event-data] event]
      ;; Route manually
      (case event-id
        :chsk/state     (handle-state-change event-data)
        :chsk/handshake (handle-handshake event-data)
        :chsk/recv      (handle-server-push event-data)
        :my/response    (handle-my-response event-data)
        (println "Unhandled:" event-id)))
    (recur)))
```

**Key characteristics:**
- Single channel, single consumer (or mult for multiple)
- Manual routing via `case`, `cond`, or multimethod
- No built-in predicate matching or timeouts
- RPC requires manual correlation ID tracking

### Sending Messages

```clojure
;; Fire-and-forget
(send-fn [:my/event {:data "here"}])

;; With reply callback (RPC)
(send-fn [:my/request {:query "..."}]
         5000  ; timeout-ms
         (fn [reply]
           (if (sente/cb-success? reply)
             (println "Got:" reply)
             (println "Failed/timeout"))))
```

### Server-Side Push

```clojure
;; Server broadcasts to specific user
(chsk-send! user-id [:server/notification {:msg "Hello"}])
```

---

## Part 2: sente-lite's Current API

### Client Creation

```clojure
(require '[sente-lite.client :as sente])

(def client
  (sente/make-client!
    {:url "ws://localhost:3000/ws"

     ;; Lifecycle callbacks
     :on-open    (fn [uid] ...)
     :on-close   (fn [code reason] ...)
     :on-error   (fn [error] ...)

     ;; Message callback (ALL messages)
     :on-message (fn [event-id data] ...)

     ;; Reconnection
     :auto-reconnect? true
     :on-reconnect    (fn [] ...)
     :on-channel-ready (fn [client-id] ...)  ; Fresh handler registration

     ;; Optional send queue
     :send-queue {:max-depth 1000}}))
```

**Returns:** `client-id` (string handle)

### Receiving Messages - Two Mechanisms

#### 1. `:on-message` Callback (Subscription-style)

```clojure
;; Fires for EVERY message
(sente/make-client!
  {:on-message (fn [event-id data]
                 (case event-id
                   :my/response (handle-response data)
                   :server/push (handle-push data)
                   nil))})
```

#### 2. `take!` with Predicate (RPC-style)

```clojure
;; One-shot waiter with predicate matching
(sente/take! client
  {:pred       #(= (:event-id %) :my/response)
   :timeout-ms 5000
   :callback   (fn [msg]
                 (if (:error msg)
                   (println "Error:" (:error msg))
                   (println "Got:" (:data msg))))})

;; Returns cancel function
```

### Sending Messages

```clojure
;; Send event
(sente/send! client [:my/event {:data "here"}])
;; Returns :ok, :rejected (queue full), or true/false (direct)
```

### Pub/Sub (sente-lite extension)

```clojure
;; Subscribe to channel
(sente/subscribe! client "my-channel")

;; Publish to channel
(sente/publish! client "my-channel" {:msg "Hello"})

;; Unsubscribe
(sente/unsubscribe! client "my-channel")
```

---

## Part 3: Key Differences

### The 20,000-Foot View

Both libraries provide the same conceptual model:

```
┌─────────────────────────────────────────────────────────────────┐
│                    WebSocket Communication                       │
├─────────────────────────────────────────────────────────────────┤
│  Client                              Server                      │
│  ──────                              ──────                      │
│  send! ─────────────────────────────► receive                   │
│  receive ◄───────────────────────── broadcast/reply             │
│                                                                  │
│  Connection lifecycle: open → messages → close                   │
│  Auto-reconnect with backoff                                     │
│  Handshake with UID assignment                                   │
└─────────────────────────────────────────────────────────────────┘
```

**From 20,000 feet, they're identical:**
- WebSocket with EDN serialization
- Event vectors: `[:event-id {:data}]`
- Handshake assigns UID
- Auto-reconnect
- Send/receive messages

### The Implementation Difference

| Aspect | Sente | sente-lite |
|--------|-------|------------|
| **Receive mechanism** | `ch-recv` (core.async channel) | `:on-message` callback |
| **Consumer model** | Pull (take from channel) | Push (callback invoked) |
| **Multiple handlers** | `mult`/`tap` or manual | Multiple callbacks + `take!` |
| **RPC pattern** | `send-fn` with callback | `send!` + `take!` |
| **Timeouts** | Manual or send-fn callback | Built-in `take!` timeout |
| **Predicate matching** | Manual | Built-in `take!` pred |

### Why the Difference?

**Sente requires core.async** because:
- ClojureScript's single-threaded nature needs CSP for coordination
- Channels provide backpressure and buffering
- `go` blocks enable sequential-looking async code

**sente-lite avoids core.async** because:
- Babashka doesn't have core.async
- Scittle/SCI has limited macro support
- Callbacks are universally supported
- Simpler mental model for most use cases

### API Mapping

| Sente | sente-lite | Notes |
|-------|------------|-------|
| `make-channel-socket-client!` | `make-client!` | Similar options |
| `ch-recv` | `:on-message` callback | Push vs pull |
| `send-fn` | `send!` | Same semantics |
| `send-fn` with callback | `send!` + `take!` | Two-step in sente-lite |
| `chsk-send!` (server) | `broadcast!` | Same concept |
| `:chsk/state` | `:on-open`/`:on-close` | Callbacks vs events |
| `:chsk/handshake` | `:on-open` with uid | Merged |

---

## Part 4: Unified Subscription API Design

### The Problem

Currently sente-lite has **two parallel receive mechanisms**:

1. **`:on-message`** - Subscription-style, fires for ALL messages
2. **`take!`** - One-shot waiter with predicate

These work independently and serve different use cases:
- `:on-message` for persistent handlers (like Sente's `ch-recv` loop)
- `take!` for RPC-style request/response

### Design Goals

1. **Unified mental model** - One way to register handlers
2. **Cover both patterns** - Persistent subscriptions AND one-shot waiters
3. **Closer to Sente** - Feel like routing from `ch-recv`
4. **Backward compatible** - Existing code continues to work

### Primary Pattern: Multimethods (Same as Sente!)

Both Sente and sente-lite use **multimethods for event routing**. This is the idiomatic Clojure approach:

```clojure
;; Define the multimethod (dispatch on event-id)
(defmulti handle-event
  "Dispatch events by event-id"
  (fn [[event-id _data] _context] event-id))

;; Handle specific events
(defmethod handle-event :chat/message
  [[_ data] {:keys [uid send-fn]}]
  (println "Chat from" uid ":" (:text data))
  (send-fn uid [:chat/ack {:received true}]))

(defmethod handle-event :user/login
  [[_ credentials] {:keys [?reply-fn]}]
  (let [result (authenticate credentials)]
    (when ?reply-fn
      (?reply-fn result))))

;; Catch-all for unknown events
(defmethod handle-event :default
  [[event-id _] _]
  (println "Unhandled event:" event-id))
```

**This is identical to Sente's pattern!**

The only difference is HOW the multimethod gets called:
- **Sente:** You pull from `ch-recv` in a `go-loop` and call the multimethod
- **sente-lite:** The `:on-message` callback calls the multimethod

```clojure
;; SENTE: Pull from channel, dispatch to multimethod
(go-loop []
  (when-let [{:keys [event] :as msg} (<! ch-recv)]
    (handle-event event msg)  ; ← call multimethod
    (recur)))

;; SENTE-LITE: Callback dispatches to multimethod
(make-client!
  {:on-message (fn [event-id data]
                 (handle-event [event-id data] context))})  ; ← call multimethod
```

**From 20,000 feet: IDENTICAL.** Both use multimethods for routing.

---

### Proposed Enhancement: `on!` / `off!`

For cases where multimethods aren't suitable (dynamic handlers, one-shot waiters), add `on!`/`off!`:

```clojure
;; Register a handler
(sente/on! client
  {:event-id :my/response              ; Match specific event (or :pred fn)
   :callback (fn [msg] ...)            ; Handler function
   :once?    false                     ; Persistent (default) or one-shot
   :timeout-ms nil})                   ; Optional timeout (for :once? true)

;; Returns handler-id for removal
```

#### Pattern 1: Persistent Handler (like ch-recv loop)

```clojure
;; Handle all :server/notification events
(sente/on! client
  {:event-id :server/notification
   :callback (fn [{:keys [data]}]
               (show-notification data))})

;; Handle ALL events (catch-all)
(sente/on! client
  {:event-id :*                        ; or omit :event-id
   :callback (fn [{:keys [event-id data]}]
               (println "Got:" event-id data))})
```

#### Pattern 2: One-Shot Waiter (RPC)

```clojure
;; Wait for specific response
(sente/on! client
  {:event-id  :my/response
   :once?     true
   :timeout-ms 5000
   :callback  (fn [msg]
                (if (:error msg)
                  (handle-error msg)
                  (handle-response msg)))})

;; With correlation ID
(let [req-id (random-uuid)]
  (sente/send! client [:my/request {:id req-id :query "..."}])
  (sente/on! client
    {:pred      #(= (get-in % [:data :id]) req-id)
     :once?     true
     :timeout-ms 5000
     :callback  handle-response}))
```

#### Pattern 3: Complex Predicates

```clojure
;; Match by predicate function
(sente/on! client
  {:pred     (fn [{:keys [event-id data]}]
               (and (= event-id :order/update)
                    (= (:order-id data) my-order-id)))
   :callback handle-order-update})
```

#### Removing Handlers

```clojure
;; on! returns handler-id
(def handler-id
  (sente/on! client {:event-id :foo :callback ...}))

;; Remove specific handler
(sente/off! client handler-id)

;; Remove all handlers for event-id
(sente/off! client {:event-id :foo})

;; Remove all handlers
(sente/off! client :all)
```

### Full API Specification

```clojure
(defn on!
  "Register a message handler.

  Options:
    :event-id   - Event ID to match (keyword), or :* for all events
    :pred       - Predicate function (fn [msg] -> bool), alternative to :event-id
    :callback   - Handler function (fn [msg] ...), receives {:event-id :data}
    :once?      - If true, handler removed after first match (default: false)
    :timeout-ms - For :once? handlers, timeout in ms. Callback receives {:error :timeout}

  Returns handler-id (for removal with off!)

  Matching priority:
    1. :pred function (if provided)
    2. :event-id exact match
    3. :event-id :* matches everything

  Examples:
    ;; Persistent handler
    (on! client {:event-id :server/push :callback handle-push})

    ;; One-shot with timeout (RPC)
    (on! client {:event-id :my/response :once? true :timeout-ms 5000 :callback ...})

    ;; Predicate matching
    (on! client {:pred #(= (:id (:data %)) req-id) :once? true :callback ...})
  "
  [client-id opts]
  ...)

(defn off!
  "Remove message handler(s).

  Forms:
    (off! client handler-id)           ; Remove specific handler
    (off! client {:event-id :foo})     ; Remove all handlers for event-id
    (off! client :all)                 ; Remove all handlers
  "
  [client-id id-or-opts]
  ...)
```

### Implementation Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     Message Flow                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  WebSocket ──► Deserialize ──► Handler Registry ──► Callbacks    │
│                                      │                           │
│                                      ▼                           │
│                           ┌─────────────────┐                    │
│                           │  Handler List   │                    │
│                           ├─────────────────┤                    │
│                           │ {:id "h1"       │                    │
│                           │  :event-id :foo │                    │
│                           │  :pred nil      │                    │
│                           │  :callback fn   │                    │
│                           │  :once? false}  │                    │
│                           │                 │                    │
│                           │ {:id "h2"       │                    │
│                           │  :pred (fn ...) │                    │
│                           │  :once? true    │                    │
│                           │  :timeout-id ..}│                    │
│                           └─────────────────┘                    │
│                                                                  │
│  For each message:                                               │
│    1. Find matching handlers (pred or event-id)                  │
│    2. Call all matching callbacks                                │
│    3. Remove :once? handlers after match                         │
│    4. Timeout handlers get removed + callback({:error :timeout}) │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Backward Compatibility

The existing APIs continue to work:

| Current API | Status | Mapping |
|-------------|--------|---------|
| `:on-message` callback | **Kept** | Equivalent to `(on! {:event-id :* ...})` |
| `take!` | **Kept** | Equivalent to `(on! {:pred ... :once? true ...})` |
| New `on!`/`off!` | **Added** | Unified interface |

Internally, `:on-message` and `take!` would use the same handler registry.

### Comparison: Before and After

#### Before (current)

```clojure
;; Subscription style - in config
(make-client! {:on-message (fn [event-id data]
                             (case event-id
                               :foo (handle-foo data)
                               :bar (handle-bar data)
                               nil))})

;; RPC style - separate API
(send! client [:my/request {:id req-id}])
(take! client {:pred #(= (:id (:data %)) req-id)
               :timeout-ms 5000
               :callback handle-response})
```

#### After (unified)

```clojure
;; All handlers use same API
(make-client! {:url "..."})

;; Subscription style
(on! client {:event-id :foo :callback handle-foo})
(on! client {:event-id :bar :callback handle-bar})

;; RPC style (same API!)
(send! client [:my/request {:id req-id}])
(on! client {:pred #(= (:id (:data %)) req-id)
             :once? true
             :timeout-ms 5000
             :callback handle-response})
```

### Sente Equivalence

With the unified API, the mental model matches Sente more closely:

```clojure
;; SENTE: ch-recv with routing
(go-loop []
  (when-let [{[event-id data] :event} (<! ch-recv)]
    (case event-id
      :foo (handle-foo data)
      :bar (handle-bar data))
    (recur)))

;; SENTE-LITE: on! with routing
(on! client {:event-id :foo :callback handle-foo})
(on! client {:event-id :bar :callback handle-bar})
;; Equivalent behavior, different syntax
```

The key insight: **Sente's `ch-recv` loop with `case` routing is equivalent to multiple `on!` registrations.**

---

## Part 5: Implementation Status

### Phase 1: Add `on!`/`off!` (Additive) ✅ COMPLETE

- ✅ Implemented unified handler registry (atom-based)
- ✅ Added `on!` and `off!` functions (both BB and Scittle)
- ✅ Added `handler-count` function
- ✅ Keep `:on-message` and `take!` working (backward compatible)
- ✅ Documented as preferred API going forward

**Implementation Details (v2.8.0-internal-unification):**
- Handler registry: `{:handlers (atom {})}` in client state
- Single dispatch path: all messages dispatch to unified handler registry
- `:on-message` registered as catch-all handler (`:event-id :*`) in `make-client!`
- `take!` is thin wrapper: `(on! client-id (assoc opts :once? true))`
- recv-queue removed - no longer part of client architecture
- Timeout: BB uses `future`/`future-cancel`, Scittle uses `js/setTimeout`/`js/clearTimeout`
- 30 tests passing in `test/scripts/test_on_off_api.bb`

### ~~Phase 2: Internal Unification~~ ✅ COMPLETE (v2.8.0)

- ✅ Refactored `:on-message` to use handler registry internally
- ✅ Refactored `take!` to use handler registry internally
- ✅ Single code path for all message routing
- ✅ Removed recv-queue (obsolete after unification)

### Phase 3: Documentation Update (Future)

- Update all examples to use `on!`/`off!`
- Document `:on-message` as "legacy convenience"
- Show Sente migration patterns

---

## Summary

### What Stays the Same (20,000-foot view)

- WebSocket with EDN events
- Event format: `[:event-id {:data}]`
- Handshake with UID
- Auto-reconnect
- Send/receive semantics

### What's Different (Implementation)

| Sente | sente-lite |
|-------|------------|
| `ch-recv` channel | `on!` handlers |
| Pull model | Push model |
| `go-loop` consumer | Callback invocations |
| Manual routing | Declarative routing |

### The Unified API

```clojure
;; One API for all receive patterns
(on! client {:event-id :foo :callback fn})           ; Persistent
(on! client {:pred fn :once? true :callback fn})     ; One-shot
(on! client {:event-id :* :callback fn})             ; Catch-all

(off! client handler-id)                              ; Remove
```

This brings sente-lite closer to Sente's mental model while leveraging callback-based simplicity.
