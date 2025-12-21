# Component Catalog

Bottom-up analysis of sente-lite components to identify common patterns.

---

## Layer 1: Core Components

### 1.1 Server (sente-lite.server)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `start-server!` | Initialize and start |
| `stop-server!` | Shutdown |
| `get-server-stats` | Status/health |
| `get-server-port` | Runtime state query |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:port` | int | `3000` | TCP port (0 = ephemeral) |
| `:host` | string | `nil` | Bind address |
| `:wire-format` | keyword | `:edn` | Serialization |
| `:on-message` | fn | `nil` | User event handler |
| `:wrap-recv-evs?` | bool | `false` | Sente compat wrapping |
| `[:websocket :max-connections]` | int | `1000` | Connection limit |
| `[:websocket :max-message-bytes]` | int | `1048576` | Message size limit |
| `[:heartbeat :enabled]` | bool | `true` | Ping/pong enabled |
| `[:heartbeat :interval-ms]` | int | `30000` | Ping interval |
| `[:heartbeat :timeout-ms]` | int | `60000` | Pong timeout |
| `[:telemetry :enabled]` | bool | `true` | Telemetry on/off |
| `[:telemetry :handler-id]` | keyword | `:sente-lite-server` | Handler identifier |

**State:**
- `server-state` atom: `{:running?, :config, :http-server, :connections, :start-time}`

**Dependencies:** None (foundation layer)

---

### 1.2 Client (sente-lite.client-bb / client_scittle)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `make-client!` | Create and connect |
| `close!` | Disconnect |
| `get-status` | Status query |
| `get-stats` | Health/metrics |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:url` | string | *required* | WebSocket URL |
| `:on-open` | fn | `nil` | Connect callback |
| `:on-message` | fn | `nil` | Message callback |
| `:on-close` | fn | `nil` | Disconnect callback |
| `:on-reconnect` | fn | `nil` | Reconnect callback |
| `:auto-reconnect?` | bool | `true` | Auto-reconnect enabled |
| `:reconnect-delay` | int | `1000` | Initial delay ms |
| `:max-reconnect-delay` | int | `30000` | Max delay ms |
| `:wrap-recv-evs?` | bool | `false` | Sente compat |
| `[:send-queue :max-depth]` | int | `1000` | Queue size |

**State:**
- Client state atom: `{:status, :uid, :ws, :reconnect-enabled?, :reconnect-delay, :handlers}`

**Dependencies:** None (foundation layer)

---

## Layer 2: Channel Components

### 2.1 Channels (sente-lite.channels)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `create-channel!` | Create channel |
| `subscribe!` | Add subscriber |
| `unsubscribe!` | Remove subscriber |
| `unsubscribe-all!` | Remove all for conn |
| `list-channels` | Status query |
| `cleanup-expired-rpc-requests!` | Maintenance |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `[:channels :auto-create]` | bool | `false` | Auto-create on subscribe |
| `[:channels :default-config :max-subscribers]` | int | `1000` | Per-channel limit |
| `[:channels :default-config :max-subscriptions-per-conn]` | int | `100` | Per-conn limit |

**Per-operation Config:**
| Operation | Parameter | Default | Description |
|-----------|-----------|---------|-------------|
| `send-rpc-request!` | `:timeout-ms` | `5000` | RPC timeout |

**State:**
- `channels` atom: `{channel-id -> {:config, :subscribers, :message-history}}`
- `conn-subscriptions` atom: `{conn-id -> #{channel-ids}}`
- `rpc-requests` atom: `{request-id -> {:conn-id, :timeout}}`

**Dependencies:** Server (for connection management)

---

## Layer 3: General Module Components

### 3.1 Registry (sente-lite.registry)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `set-reg-root!` | Configure namespace root |
| `register!` | Create entry |
| `unregister!` | Remove entry |
| `unregister-prefix!` | Bulk remove |
| `list-registered` | Status query |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `reg-root` | string | `"sente-lite.registry"` | Namespace prefix |

**State:**
- `reg-root` atom: namespace prefix string
- `registered-names` atom: set of registered names

**Dependencies:** None (utility layer)

---

### 3.2 config-discovery (config-discovery.handlers)

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `discover-from-hardcoded!` | Initialize defaults |
| `discover-from-html!` | Discover from DOM |
| `discover-from-json-script!` | Discover from JSON |
| `build-ws-url` | State query |

**Config Parameters (sets registry values):**
| Registry Key | Type | Description |
|--------------|------|-------------|
| `config.server/ws-host` | string | WebSocket host |
| `config.server/ws-port` | int | WebSocket port |
| `config.server/ws-path` | string | WebSocket path |
| `config.server/api-base` | string | API base URL |
| `config.env/mode` | keyword | Environment mode |

**Handler-specific Config:**
| Handler | Parameter | Default | Description |
|---------|-----------|---------|-------------|
| `discover-from-json-script!` | `:element-id` | `"sente-config"` | Script tag ID |
| `discover-from-json-script!` | `:register?` | `true` | Create vs update |

**State:** Uses registry (no internal state)

**Dependencies:** Registry

---

### 3.3 log-routing.registry-handlers

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `init!` | Initialize defaults |
| `init-with-sente!` | Initialize with sente handler |
| `register-impl!` | Add implementation |
| `use-handler!` | Switch handler |
| `get-handler` | Get current handler |
| `list-implementations` | Status query |

**Config Parameters (registry keys):**
| Registry Key | Type | Description |
|--------------|------|-------------|
| `telemetry/log-handler` | string | Points to current impl |
| `telemetry.impl/console` | fn | Console handler |
| `telemetry.impl/silent` | fn | Silent handler |
| `telemetry.impl/sente` | fn | Remote handler |

**Handler-creation Config:**
| Function | Parameter | Default | Description |
|----------|-----------|---------|-------------|
| `make-sente-handler` | `:channel` | `"log-routing"` | Pub/sub channel |
| `make-sente-handler` | `:source-id` | `"unknown"` | Source identifier |

**State:** Uses registry (no internal state)

**Dependencies:** Registry

---

### 3.4 atom-sync.publisher

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `start!` | Start publishing |
| `stop!` | Stop publishing |
| `publish-current!` | Force publish |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:atom-id` | keyword | *required* | Unique atom identifier |
| `:channel` | string | `"atom-sync"` | Pub/sub channel |

**State:**
- `versions` atom: `{atom-id -> version-number}`

**Dependencies:** Client (for pub/sub)

---

### 3.5 atom-sync.subscriber

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `start!` | Start receiving |
| `stop!` | Stop receiving |
| `request-current!` | Request initial sync (future) |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:atom-id` | keyword | *required* | Atom to sync |
| `:channel` | string | `"atom-sync"` | Pub/sub channel |
| `:on-update` | fn | `nil` | Update callback |

**State:** Uses target atom (no internal state)

**Dependencies:** Client (for pub/sub)

---

### 3.6 log-routing.sender

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `make-remote-log-fn` | Create wrapped log-fn |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:source-id` | string | `"unknown"` | Log source identifier |
| `:exclude-ns-prefixes` | set | `#{"sente-lite." "log-routing."}` | Namespaces to skip |

**State:** Re-entrant guard (`*sending?*` dynamic var)

**Dependencies:** Client (for pub/sub)

---

### 3.7 log-routing.receiver

**Lifecycle:**
| Function | Purpose |
|----------|---------|
| `start!` | Start receiving |
| `stop!` | Stop receiving |

**Config Parameters:**
| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `:handler` | fn | `nil` | Log entry handler |
| `:channel` | string | `"log-routing"` | Pub/sub channel |

**State:** Uses client handlers (no internal state)

**Dependencies:** Client (for pub/sub)

---

## Common Patterns Identified

### Pattern 1: Lifecycle Functions

Almost every component has:
```
init!/start!    -> Create/configure
stop!           -> Cleanup
get-*/list-*    -> Status query
```

### Pattern 2: Config Parameter Categories

| Category | Examples |
|----------|----------|
| **Limits** | max-connections, max-depth, max-subscribers |
| **Timing** | interval-ms, timeout-ms, delay |
| **Toggles** | enabled, auto-create, auto-reconnect? |
| **Identity** | handler-id, source-id, atom-id |
| **Routing** | channel, port, host, path |
| **Callbacks** | on-message, on-open, on-update, handler |

### Pattern 3: State Management

| Approach | Used By |
|----------|---------|
| Internal atom | Server, Client, Channels |
| Registry | config-discovery, log-routing |
| External atom | atom-sync (user's atom) |
| Dynamic var | log-routing.sender (re-entrancy) |

### Pattern 4: Dependencies

```
Layer 4: Use Cases
    └── Layer 3: General Modules
            └── Layer 2: Channels
                    └── Layer 1: Core (Server/Client)
                            └── Layer 0: Registry (utility, no deps)
```

### Pattern 5: Health/Status Queries

| Component | Status Function | Returns |
|-----------|-----------------|---------|
| Server | `get-server-stats` | `{:running?, :connections, :uptime-ms}` |
| Client | `get-status` | `:connected`, `:disconnected`, etc. |
| Client | `get-stats` | `{:messages-sent, :messages-received}` |
| Registry | `list-registered` | Set of registered names |
| Channels | `list-channels` | Map of channel info |

---

## Proposed Component Protocol

Based on patterns observed:

```clojure
(defprotocol IComponent
  ;; Lifecycle
  (start! [this config])
  (stop! [this])

  ;; Status
  (status [this])       ; => :stopped | :starting | :running | :error
  (health [this])       ; => {:healthy? bool :details {...}}

  ;; Optional
  (config [this])       ; => current config map
  (stats [this]))       ; => metrics map
```

### Standard Config Keys (conventions)

```clojure
;; Limits
:max-*            ; Maximum values
:min-*            ; Minimum values

;; Timing
:*-ms             ; Milliseconds
:*-interval-ms    ; Recurring intervals
:*-timeout-ms     ; Timeouts
:*-delay          ; Delays

;; Toggles
:enabled          ; Boolean on/off
:auto-*           ; Automatic behaviors

;; Identity
:*-id             ; Identifiers

;; Callbacks (not serializable)
:on-*             ; Event callbacks
:handler          ; Processing function
```

---

## Summary: Config Parameter Count by Component

| Component | Config Params | Lifecycle Fns | Has State |
|-----------|--------------|---------------|-----------|
| Server | 12 | 3 | Yes |
| Client | 10 | 3 | Yes |
| Channels | 4 | 6 | Yes |
| Registry | 1 | 5 | Yes |
| config-discovery | 2 | 4 | No (uses registry) |
| log-routing.handlers | 3 | 6 | No (uses registry) |
| atom-sync.publisher | 2 | 3 | Yes (versions) |
| atom-sync.subscriber | 3 | 3 | No |
| log-routing.sender | 2 | 1 | Yes (dynamic var) |
| log-routing.receiver | 2 | 2 | No |

**Total: ~40 unique config parameters across all components**
