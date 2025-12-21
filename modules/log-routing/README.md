# log-routing Module

**Purpose:** Route Trove log messages through sente-lite to a remote collector.
**Source:** `doc/sente-lite-modules.md` Remote Logging section
**Status:** Phase 1 Complete

---

## Status

- [x] Phase 1: BB-to-BB basic routing (COMPLETE - 2025-12-19)
- [ ] Phase 2: Filtering and buffering
- [ ] Phase 3: Scittle browser support

---

## Quick Start

```bash
# From project root
bb modules/log-routing/test/test_bb_to_bb.bb
```

Expected output:
```
=== log-routing BB-to-BB Test ===
...
SUCCESS: All 3 logs received!
```

---

## Architecture

```
[Sender Process]                    [Receiver Process]
     Trove                               custom handler
       |                                       ^
       v                                       |
  wrapped log-fn --> sente-lite pub/sub --> on! handler
  (local + publish)   (WebSocket channel)   (receives log entries)
```

**Key Design Decisions:**
1. Uses **pub/sub channels** for message routing (not direct send)
2. Both sender and receiver are **clients** of a shared server
3. **Namespace filtering** prevents infinite recursion from sente-lite's internal logging
4. **Re-entrant guard** protects against logging loops

---

## Files

```
modules/log-routing/
├── README.md                     # This file
├── src/log_routing/
│   ├── sender.cljc               # Wraps Trove log-fn, publishes to channel
│   └── receiver.cljc             # Subscribes to channel, handles log entries
└── test/
    └── test_bb_to_bb.bb          # Integration test (passing)
```

---

## API

### Sender

```clojure
(require '[log-routing.sender :as sender])
(require '[sente-lite.client-bb :as client])
(require '[taoensso.trove :as trove])

;; Wrap Trove's log-fn to also publish remotely
(def original-log-fn trove/*log-fn*)
(trove/set-log-fn!
  (sender/make-remote-log-fn original-log-fn client-id
    {:source-id "my-client"                    ; Identifies source in logs
     :exclude-ns-prefixes #{"sente-lite."}}))  ; Optional: namespaces to skip

;; Logs now go to both local and remote channel
(trove/log! {:level :info :data {:msg "Hello!"}})
```

**Options:**
- `:source-id` - String to identify log source (default: "unknown")
- `:exclude-ns-prefixes` - Set of namespace prefixes to not send remotely
  (default: `#{"sente-lite." "log-routing."}`)

### Receiver

```clojure
(require '[log-routing.receiver :as receiver])

;; Start receiving with custom handler
(def handler-id
  (receiver/start! client-id
    {:handler (fn [log-entry]
                (println "Remote:" log-entry))}))

;; Or use default handler (prints to stdout)
(def handler-id (receiver/start! client-id {}))

;; Stop receiving
(receiver/stop! client-id handler-id)
```

**Log Entry Format:**
```clojure
{:ns "my.namespace"           ; Source namespace
 :level :info                 ; Log level keyword
 :id :my.namespace/event-id   ; Optional event ID
 :data {:msg "Hello"}         ; Log data map
 :coords [42 3]               ; Optional [line col]
 :timestamp 1766200992881     ; Unix timestamp ms
 :source "my-client"}         ; Source identifier
```

---

## Implementation Notes

### Pub/Sub Pattern
Unlike the original design that used direct `send!`, the implementation uses
sente-lite's pub/sub channels:

1. Sender calls `publish!` to the "log-routing" channel
2. Server broadcasts to all channel subscribers
3. Receiver has `subscribe!` and `on!` handler for `:sente-lite/channel-msg`

This allows multiple receivers to collect logs from multiple senders.

### Preventing Infinite Recursion
Two mechanisms prevent logging loops:

1. **Namespace filtering**: By default, logs from `sente-lite.*` and
   `log-routing.*` namespaces are not sent remotely.

2. **Re-entrant guard**: A dynamic var `*sending?*` prevents the log-fn
   from calling itself when sente-lite's internal logging is triggered.

### Wire Format
Channel messages arrive as:
```clojure
{:event-id :sente-lite/channel-msg
 :data {:channel-id "log-routing"
        :data {...log-entry...}
        :from "conn-123"}}
```

---

## Registry-Based Handler Configuration (NEW)

Uses the registry indirection pattern for configurable log handlers:

```clojure
(require '[log-routing.registry-handlers :as rh])
(require '[sente-lite.registry :as reg])

;; Initialize with built-in handlers
(rh/init!)

;; Registry layout:
;;   telemetry.impl/console  ->  (fn [entry] ...)   ; prints to stdout
;;   telemetry.impl/silent   ->  (fn [entry] nil)   ; discards
;;   telemetry/log-handler   ->  "telemetry.impl/console"  ; config pointer
```

### Switching Handlers at Runtime

```clojure
;; Get current handler
(let [handler (rh/get-handler)]
  (handler {:level :info :ns "my.ns" :data {:msg "Hello"}}))

;; Switch to silent (by name, not function!)
(rh/use-handler! "telemetry.impl/silent")

;; Register custom handler
(rh/register-impl! "my-handler"
  (fn [entry] (swap! my-store conj entry)))
(rh/use-handler! "telemetry.impl/my-handler")
```

### Reactive Handler Switching

```clojure
;; Watch for handler changes (receives resolved functions, not strings!)
(rh/on-handler-change! :my-watch
  (fn [old-handler new-handler]
    (println "Handler switched!")))

;; Later...
(rh/remove-handler-watch! :my-watch)
```

### Integration with Trove

```clojure
(require '[taoensso.trove :as trove])

;; Create Trove-compatible log-fn that uses registry handler
(rh/init!)
(trove/set-log-fn! (rh/make-trove-log-fn))

;; Now switch handler via registry - Trove automatically uses new handler!
(rh/use-handler! "telemetry.impl/silent")
```

### With Sente Routing

```clojure
;; Initialize with sente handler available
(rh/init-with-sente!
  (fn [channel data] (client/publish! client-id channel data))
  {:source-id "my-client"})

;; Now you can switch between console and sente:
(rh/use-handler! "telemetry.impl/console")  ; local only
(rh/use-handler! "telemetry.impl/sente")    ; route to server
```

**Test:**
```bash
bb modules/log-routing/test/test_registry_handlers.bb
# => 15 passed, 0 failed
```

---

## Remote Logging Demo (Module Composition)

Demonstrates composing modules together:
- **config-discovery**: Ephemeral port discovery via JSON script tag
- **log-routing**: Registry-based handlers for log routing

```bash
# Terminal 1: Start server (ephemeral port)
bb modules/log-routing/examples/remote_logging.bb

# Terminal 2: Run Playwright tests
node modules/log-routing/examples/test_remote_logging.mjs
# => 9/9 tests pass
```

**Key pattern**: Server uses `:on-message` callback to handle custom events:

```clojure
(server/start-server!
  {:port 0  ; ephemeral port
   :on-message (fn [conn-id event-id data]
                 (when (= event-id :log/entry)
                   ;; Route through registry handler
                   (when-let [handler (rh/get-handler)]
                     (handler (:log data)))
                   [:log/received {:status :ok}]))})
```

**Browser imports from both modules:**
```clojure
(discovery/discover-from-json-script!)  ; from config-discovery
(rh/init!)                              ; from log-routing
(let [handler (rh/get-handler)]         ; switch at runtime
  (handler {:level :info :msg "test"}))
```

---

## Phase 2: Filtering and Buffering (Future)

**Planned Features:**
- Configurable minimum level to send
- Buffer logs during disconnect
- Flush on reconnect
- Max buffer size (drop oldest)
- Batch sending

---

## Phase 3: Scittle Browser Support (Future)

**Planned Features:**
- Verify Trove works in Scittle
- Browser to server log routing
- Console log-fn as local logger

---

## Progress Log

### 2025-12-19
- **Phase 1 Complete!**
- Implemented sender.cljc with namespace filtering and re-entrant guard
- Implemented receiver.cljc with pub/sub pattern
- Created and verified test_bb_to_bb.bb (all 3 logs received)
- Discovered and fixed infinite recursion issue (sente-lite internal logging)
- Uses `sente-lite.client-bb` for BB environment

### 2025-12-20
- Created module structure
- Defined architecture and API
- Researched Trove handler API (`*log-fn*` signature)
- Extracted design from doc/sente-lite-modules.md
- **Registry-based handlers** (registry_handlers.cljc)
- **Remote logging demo** with module composition (examples/remote_logging.bb)
- Uses **config-discovery module** for ephemeral port discovery
- Server **:on-message callback** for custom event handling
