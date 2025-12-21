# sente-lite Configuration Reference

A layered configuration architecture that applies to any service.

## Configuration Layers

```
┌─────────────────────────────────────────────────────────────────┐
│  Layer 4: USE CASE MODULES (complete solutions)                 │
│  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐   │
│  │ remote-logging  │ │   dashboard     │ │  metrics-sink   │   │
│  │ (config+routing)│ │   (future)      │ │   (future)      │   │
│  └─────────────────┘ └─────────────────┘ └─────────────────┘   │
├─────────────────────────────────────────────────────────────────┤
│  Layer 3: GENERAL MODULES (reusable building blocks)           │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │config-discovery│ │ log-routing │ │  atom-sync   │           │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
│  ┌──────────────┐                                               │
│  │   registry   │ (FQN-based named resources)                   │
│  └──────────────┘                                               │
├─────────────────────────────────────────────────────────────────┤
│  Layer 2: SENTE-LITE CHANNELS (pub/sub messaging)              │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │  subscribe   │ │   publish    │ │     RPC      │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
├─────────────────────────────────────────────────────────────────┤
│  Layer 1: CORE/INTRINSIC (WebSocket foundation)                │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐            │
│  │  connection  │ │  heartbeat   │ │  wire-format │            │
│  └──────────────┘ └──────────────┘ └──────────────┘            │
│  ┌──────────────┐ ┌──────────────┐                              │
│  │  handshake   │ │    echo      │ (protocol-level)            │
│  └──────────────┘ └──────────────┘                              │
└─────────────────────────────────────────────────────────────────┘
```

## Configuration Namespaces by Layer

| Layer | Config Prefix | Scope |
|-------|---------------|-------|
| **Core** | `sente-lite.core/` | Protocol fundamentals |
| **Channels** | `sente-lite.channels/` | Pub/sub messaging |
| **General Modules** | `<module>/` | Reusable building blocks |
| **Use Case Modules** | `<use-case>/` | Complete solutions |

---

## Layer 1: Core/Intrinsic

Protocol-level settings. Single implementation, not swappable.

### Connection

| Registry Key | Config Key | Default | Description |
|--------------|------------|---------|-------------|
| `sente-lite.core/port` | `:port` | `3000` | TCP port (0 = ephemeral) |
| `sente-lite.core/host` | `:host` | `nil` | Bind address (nil = all) |
| `sente-lite.core/max-connections` | `[:websocket :max-connections]` | `1000` | Max concurrent connections |
| `sente-lite.core/max-message-bytes` | `[:websocket :max-message-bytes]` | `1048576` | Max message size (1MB) |

### Heartbeat (Ping/Pong)

| Registry Key | Config Key | Default | Description |
|--------------|------------|---------|-------------|
| `sente-lite.core/heartbeat-enabled` | `[:heartbeat :enabled]` | `true` | Enable ping/pong |
| `sente-lite.core/heartbeat-interval-ms` | `[:heartbeat :interval-ms]` | `30000` | Ping interval (30s) |
| `sente-lite.core/heartbeat-timeout-ms` | `[:heartbeat :timeout-ms]` | `60000` | Pong timeout (60s) |

### Wire Format

| Registry Key | Config Key | Default | Description |
|--------------|------------|---------|-------------|
| `sente-lite.core/wire-format` | `:wire-format` | `:edn` | Serialization format |

**Available formats:** `:edn` (lossless), `:json` (lossy), `:transit-json` (lossless)

### Echo (Fallback)

| Registry Key | Config Key | Default | Description |
|--------------|------------|---------|-------------|
| `sente-lite.core/echo-enabled` | *(not yet)* | `true` | Echo unknown events |

### Handshake

Fixed protocol - not configurable. Assigns uid, sends csrf token.

---

## Layer 2: Channels (Pub/Sub)

Built-in messaging layer on top of core WebSocket.

### Channel Defaults

| Registry Key | Config Key | Default | Description |
|--------------|------------|---------|-------------|
| `sente-lite.channels/auto-create` | `[:channels :auto-create]` | `false` | Auto-create on subscribe |
| `sente-lite.channels/max-subscribers` | `[:channels :default-config :max-subscribers]` | `1000` | Max per channel |
| `sente-lite.channels/max-subscriptions-per-conn` | `[:channels :default-config :max-subscriptions-per-conn]` | `100` | Max per connection |

### RPC

| Registry Key | Config Key | Default | Description |
|--------------|------------|---------|-------------|
| `sente-lite.channels/rpc-timeout-ms` | `:timeout-ms` | `5000` | RPC request timeout |

---

## Layer 3: General Modules

Reusable building blocks with their own configuration.

### Registry Module

The registry itself has minimal config - it's the foundation for other config.

| Registry Key | Description |
|--------------|-------------|
| `sente-lite.registry/namespace-prefix` | Default: `"sente-lite.registry."` |

### config-discovery Module

| Registry Key | Description |
|--------------|-------------|
| `config.server/ws-host` | WebSocket host |
| `config.server/ws-port` | WebSocket port |
| `config.server/ws-path` | WebSocket path (e.g., "/ws") |
| `config.server/api-base` | API base URL |
| `config.env/mode` | Environment (:dev, :prod, :test) |

### log-routing Module

| Registry Key | Description |
|--------------|-------------|
| `telemetry/log-handler` | Points to impl FQN (indirection) |
| `telemetry.impl/console` | Console handler function |
| `telemetry.impl/silent` | Silent (no-op) handler |
| `telemetry.impl/sente` | Route to server handler |

### atom-sync Module

| Registry Key | Description |
|--------------|-------------|
| `atom-sync/channel` | Default channel name ("atom-sync") |
| `atom-sync/sync-interval-ms` | Debounce interval (future) |

---

## Layer 4: Use Case Modules

Complete solutions combining general modules.

### remote-logging (config-discovery + log-routing)

| Registry Key | Description |
|--------------|-------------|
| `remote-logging/enabled` | Enable remote log shipping |
| `remote-logging/destination` | Server endpoint |
| `remote-logging/buffer-size` | Local buffer before send |
| `remote-logging/min-level` | Minimum level to ship |

---

## Telemetry: A Layered Example

Telemetry itself follows the same layered pattern:

```
┌─────────────────────────────────────────────────────────────────┐
│  Context-Specific (per debugging session, per error)           │
│  - telemetry.context/correlation-id                             │
│  - telemetry.context/user-id                                    │
│  - telemetry.context/request-id                                 │
├─────────────────────────────────────────────────────────────────┤
│  Application-Specific (sente-lite's own telemetry)             │
│  - sente-lite.telemetry/enabled                                 │
│  - sente-lite.telemetry/handler-id                              │
│  - sente-lite.telemetry/min-level                               │
├─────────────────────────────────────────────────────────────────┤
│  Routing (where do logs go?)                                    │
│  - telemetry.routing/handler  (console, file, remote, silent)  │
│  - telemetry.routing/destinations                               │
│  - telemetry.routing/filters                                    │
├─────────────────────────────────────────────────────────────────┤
│  General Settings (global defaults)                             │
│  - telemetry/enabled                                            │
│  - telemetry/default-level                                      │
│  - telemetry/timestamp-format                                   │
└─────────────────────────────────────────────────────────────────┘
```

### Telemetry Use Cases

| Use Case | Layer | Config Keys |
|----------|-------|-------------|
| **Debugging** | Context | correlation-id, verbose level |
| **Monitoring** | Routing | metrics destination, sample rate |
| **Error Reporting** | Routing | error destination, stack traces |
| **Performance** | Application | timing enabled, slow-threshold-ms |
| **Audit** | Context | user-id, action, resource |

---

## Client Configuration

Clients have their own layer-1 equivalent settings.

### Core Client Settings

| Registry Key | Config Key | Default | Description |
|--------------|------------|---------|-------------|
| `sente-lite.client/url` | `:url` | *required* | WebSocket URL |
| `sente-lite.client/auto-reconnect` | `:auto-reconnect?` | `true` | Enable reconnection |
| `sente-lite.client/reconnect-delay-ms` | `:reconnect-delay` | `1000` | Initial delay |
| `sente-lite.client/max-reconnect-delay-ms` | `:max-reconnect-delay` | `30000` | Max delay (backoff cap) |

### Client Send Queue

| Registry Key | Config Key | Default | Description |
|--------------|------------|---------|-------------|
| `sente-lite.client/send-queue-max-depth` | `[:send-queue :max-depth]` | `1000` | Max queued messages |

### Client Callbacks

Not registry-configurable (functions, not data):
- `:on-open` - Connection established
- `:on-message` - Message received
- `:on-close` - Connection closed
- `:on-reconnect` - Before reconnect attempt

---

## Registry-Based Configuration Pattern

```clojure
(require '[sente-lite.registry :as reg])

;; Layer 1: Core settings
(reg/set-value! "sente-lite.core/port" 8080)
(reg/set-value! "sente-lite.core/heartbeat-interval-ms" 15000)

;; Layer 2: Channel settings
(reg/set-value! "sente-lite.channels/max-subscribers" 500)

;; Layer 3: Module settings
(reg/set-value! "config.server/ws-port" 8080)
(reg/set-value! "telemetry/log-handler" "telemetry.impl/console")

;; Layer 4: Use case settings
(reg/set-value! "remote-logging/min-level" :warn)

;; Read at startup
(server/start-server!
  {:port (reg/get-value "sente-lite.core/port" 3000)
   :heartbeat {:interval-ms (reg/get-value "sente-lite.core/heartbeat-interval-ms" 30000)}})
```

---

## Fixed vs Configurable

### Fixed (Protocol-Level, Single Implementation)
- Ping/pong message format
- Handshake sequence
- Event format `[event-id data]`
- Echo message format

### Configurable via Config Map
- All timing values (intervals, timeouts)
- All limits (connections, message size, queue depth)
- Feature flags (enabled/disabled)
- Wire format selection

### Configurable via Registry
- Same as config map, plus:
- Runtime switching (handler indirection)
- Cross-process discovery
- Reactive updates (watch!)

---

## Summary: What Goes Where

| Setting Type | Config Map | Registry | Notes |
|--------------|------------|----------|-------|
| Startup values | ✅ | ✅ | port, host, limits |
| Runtime switches | ❌ | ✅ | handler selection |
| Protocol handlers | ❌ | ❌ | fixed implementation |
| Callbacks (functions) | ✅ | ❌ | not serializable |
| Cross-process config | ❌ | ✅ | discovery pattern |
