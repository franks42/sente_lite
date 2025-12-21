# Config Discovery Module

Reusable discovery handlers for the Configuration Discovery pattern.

## The Pattern

```
┌─────────────────────────────────────────────────────────────┐
│                    DISCOVERY SOURCES                        │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           │
│  │Hardcode │ │  HTML   │ │  JSON   │ │  File   │  ...      │
│  └────┬────┘ └────┬────┘ └────┬────┘ └────┬────┘           │
│       └───────────┴───────────┴───────────┘                │
│                       ▼                                     │
│          ┌──────────────────────┐                          │
│          │  Discovery Handlers  │  (this module)           │
│          └──────────┬───────────┘                          │
│                     ▼                                       │
│      ┌───────────────────────────────────┐                 │
│      │           FQN REGISTRY            │                 │
│      │  "config.server/ws-port" → 8080   │                 │
│      └───────────────────────────────────┘                 │
│                     ▲                                       │
│          ┌──────────┴───────────┐                          │
│          │      APP CODE        │  (reads only)            │
│          └──────────────────────┘                          │
└─────────────────────────────────────────────────────────────┘
```

**Key insight**: App code never knows where config came from. Swap discovery mechanisms at deployment time.

## Available Handlers

| Handler | Runtime | Use Case |
|---------|---------|----------|
| `discover-from-hardcoded!` | All | Fallback defaults |
| `discover-from-html!` | Browser | HTML data attributes |
| `discover-from-json-script!` | Browser | Server-rendered config (ephemeral ports) |

## Usage

### Browser (Scittle)

```clojure
(ns my-app
  (:require [sente-lite.registry :as reg]
            [config-discovery.handlers :as discovery]))

;; Chain handlers in priority order (later overrides earlier)
(defn init! []
  ;; 1. Register defaults first
  (discovery/discover-from-hardcoded!
    {:ws-host "localhost"
     :ws-port 8080
     :ws-path "/"})

  ;; 2. Override from HTML if present
  ;; <body data-ws-port="9000">
  (discovery/discover-from-html!)

  ;; 3. Override from JSON script if present (ephemeral ports)
  ;; <script type="application/json" id="sente-config">{"wsPort": 51234}</script>
  (discovery/discover-from-json-script!)

  ;; Now connect using registry values
  (let [url (discovery/build-ws-url)]
    (client/make-client! {:url url ...})))
```

### Standard Config Names

```
config.server/ws-host      ;; WebSocket host
config.server/ws-port      ;; WebSocket port
config.server/ws-path      ;; WebSocket path (e.g., "/ws")
config.server/api-base     ;; API base path (e.g., "/api/v1")
config.env/mode            ;; Environment (:dev, :prod, :test)
```

## Examples

### Static HTML (hardcoded in HTML)

```html
<body data-ws-host="localhost" data-ws-port="8080" data-ws-path="/">
```

```clojure
(discovery/discover-from-hardcoded! {:ws-host "fallback" :ws-port 9999})
(discovery/discover-from-html!)  ; Overrides with 8080
(discovery/build-ws-url)  ; => "ws://localhost:8080/"
```

### Ephemeral Port (server-rendered)

Server (Babashka):
```clojure
(server/start-server! {:port 0})  ; OS assigns port
(let [port (server/get-server-port)]
  ;; Render HTML with port embedded
  (str "<script type=\"application/json\" id=\"sente-config\">"
       "{\"wsPort\": " port "}"
       "</script>"))
```

Client (Scittle):
```clojure
(discovery/discover-from-json-script!)
(discovery/build-ws-url)  ; => "ws://localhost:51234/"
```

See `examples/ephemeral_port.bb` for a complete working demo.

## Module Composition

This module is designed to be imported by other modules. See the **log-routing** module's `remote_logging.bb` demo for an example:

```clojure
;; In log-routing demo, imports from config-discovery
(require '[config-discovery.handlers :as discovery])

;; Uses discovery handlers to find ephemeral port
(discovery/discover-from-json-script!)
(let [url (discovery/build-ws-url)]
  (connect-to-server url))
```

**Key pattern**: Modules import from each other via standard `require` - no code copying needed.

## Files

```
modules/config-discovery/
├── README.md
├── src/config_discovery/
│   └── handlers.cljc       # Reusable handlers
└── examples/
    └── ephemeral_port.bb   # Complete ephemeral port demo
```

## Testing

```bash
# Run the ephemeral port example
bb modules/config-discovery/examples/ephemeral_port.bb

# Then in another terminal
node test/scripts/registry/test_ephemeral_port_playwright.mjs
```
