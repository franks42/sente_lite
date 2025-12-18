# sente-lite

A lightweight WebSocket library for Clojure environments where Sente can't run: **Babashka**, **nbb** (Node Babashka), and **Scittle/SCI**.

## Features

- **~85% Sente API compatibility** - Easy migration path
- **Sente-compatible v2 wire format** - `[event-id data]` vectors
- **Multi-platform support** - BB, nbb, Scittle, JVM Clojure
- **No core.async required** - Callback-based API
- **Built-in pub/sub** - Channel subscription and publishing
- **Auto-reconnect** - Exponential backoff on disconnect
- **Heartbeat/keepalive** - Automatic ping/pong

## Supported Platforms

| Platform | Server | Client | Notes |
|----------|--------|--------|-------|
| **Babashka** | ✅ `server.cljc` | ✅ `client_bb.clj` | Full support |
| **nbb** | ✅ `server_nbb.cljs` | ✅ `client_scittle.cljs` | **Unique to sente-lite!** |
| **Browser/Scittle** | N/A | ✅ `client_scittle.cljs` | Native WebSocket |
| **JVM Clojure** | ✅ `server.cljc` | Use Sente | http-kit backend |

**Note:** Official Sente does NOT support nbb - this is a sente-lite exclusive feature!

## Quick Start

### Babashka Server + Client

```clojure
;; Server (server.bb)
(require '[sente-lite.server :as server])

(server/start-server! {:port 3000})

;; Client (client.bb)
(require '[sente-lite.client-bb :as client])

(def my-client
  (client/make-client!
    {:url "ws://localhost:3000/"
     :on-open (fn [uid] (println "Connected as" uid))
     :on-message (fn [event-id data] (println "Received:" event-id data))}))

(client/send! my-client [:my/event {:msg "Hello!"}])
(client/subscribe! my-client "my-channel")
(client/publish! my-client "my-channel" {:msg "To all subscribers"})
```

### nbb Server + Client

```clojure
;; Server (server.cljs) - requires 'ws' npm package
(ns my-server
  (:require ["ws" :as ws]
            [sente-lite.server-nbb :as server]))

(server/start-server! {:port 3000})

;; Client (client.cljs)
(ns my-client
  (:require [sente-lite.client-scittle :as client]))

(def my-client
  (client/make-client!
    {:url "ws://localhost:3000/"
     :on-open (fn [uid] (println "Connected as" uid))
     :on-message (fn [event-id data] (println "Received:" event-id data))}))
```

## Installation

### Babashka

Add to your `bb.edn`:

```clojure
{:paths ["src"]
 :deps {io.github.franks42/sente-lite {:git/sha "LATEST"}}}
```

### nbb

```bash
cd your-project
npm install ws
```

Then add the sente-lite src to your classpath:

```bash
nbb --classpath path/to/sente-lite/src your-script.cljs
```

## API Reference

### Client API (same for BB and Scittle)

```clojure
;; Create client
(make-client! {:url "ws://..."
               :on-open (fn [uid] ...)
               :on-message (fn [event-id data] ...)
               :on-close (fn [code reason] ...)
               :on-reconnect (fn [] ...)
               :auto-reconnect? true
               :reconnect-delay 1000
               :max-reconnect-delay 30000})

;; Send events
(send! client-id [:event/name {:data "value"}])

;; Pub/Sub
(subscribe! client-id "channel-name")
(unsubscribe! client-id "channel-name")
(publish! client-id "channel-name" {:msg "Hello"})

;; State
(get-status client-id)  ; => :connected, :disconnected
(get-uid client-id)     ; => server-assigned user ID
(get-stats client-id)   ; => {:messages-sent N :messages-received M ...}

;; Control
(set-reconnect! client-id false)  ; disable auto-reconnect
(close! client-id)
```

### Server API

```clojure
;; Start/stop
(start-server! {:port 3000 :heartbeat {:enabled true}})
(stop-server!)
(get-server-port)  ; useful with :port 0 (ephemeral)
(get-server-stats)

;; Broadcasting
(broadcast-message! [:event/name {:data "to all"}])
(broadcast-to-channel! "channel-id" {:msg "to subscribers"} from-conn-id)
(send-event-to-connection! conn-id [:event/name data])
```

## Wire Format

sente-lite uses Sente-compatible v2 wire format:

```clojure
;; Simple event
[:event/name {:data "value"}]

;; System events (Sente-compatible)
[:chsk/handshake [uid csrf-token handshake-data first?]]
[:chsk/ws-ping]
[:chsk/ws-pong]

;; sente-lite extension events
[:sente-lite/subscribe {:channel-id "..."}]
[:sente-lite/subscribed {:channel-id "..." :success true}]
[:sente-lite/publish {:channel-id "..." :data {...}}]
[:sente-lite/channel-msg {:channel-id "..." :data {...} :from "conn-id"}]
```

## Testing

```bash
# Run all tests (requires nbb for full suite)
bb run_tests.bb

# Just BB tests
bb test/scripts/test_v2_client_bb.bb

# Cross-platform tests
bb test/scripts/cross_platform/run_all_cross_platform_tests.bb

# nbb tests only
cd test/nbb && npm install
nbb --classpath ../../src test_server_nbb_module.cljs
```

## Known Limitations

### SCI/Scittle
- **No vector destructuring** in function params or let bindings
- Use `(first x)`, `(second x)` instead of `[a b]`
- See `doc/plan.md` for details

### Babashka WebSocket
- `on-message` receives `java.nio.HeapCharBuffer`, not String
- Use `(str raw-data)` before parsing

## License

Eclipse Public License 2.0
