# sente-lite-nrepl Browser Bundle

Pre-built bundle of sente-lite + nREPL module for Scittle/browser environments.

## Files

| File | Description |
|------|-------------|
| `sente-lite-nrepl.cljs` | 70KB concatenated source bundle |
| `test-bundle.html` | Test page to verify bundle loads |
| `test-bundle.mjs` | Playwright test runner |
| `serve-bundle.bb` | Simple HTTP server for testing |
| `build-bundle.bb` | Script to regenerate the bundle |

## Usage

### In HTML

```html
<!-- 1. Scittle core -->
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.js"></script>

<!-- 2. FakeWebSocket (required before scittle.nrepl.js) -->
<script>
  scittle.core.eval_string(`
    ;; See test-bundle.html for full FakeWebSocket implementation
    ;; Creates window.ws_nrepl for scittle.nrepl.js to find
  `);
</script>

<!-- 3. Scittle nREPL -->
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.nrepl.js"></script>

<!-- 4. sente-lite-nrepl bundle -->
<script src="sente-lite-nrepl.cljs" type="application/x-scittle"></script>

<!-- 5. Eval all script tags -->
<script>scittle.core.eval_script_tags();</script>
```

### In Clojure(Script)

```clojure
(ns my.app
  (:require [sente-lite.client-scittle :as client]
            [sente-lite.registry :as reg]
            [nrepl-sente.browser-adapter :as adapter]
            [nrepl-sente.protocol :as proto]))

;; Create a sente-lite client
(def my-client (client/make-client! {:url "ws://localhost:8080/ws"}))

;; Connect nREPL adapter
(adapter/connect! {:client my-client})

;; Now you can use scittle.nrepl through sente-lite!
```

## Namespaces Included

- `sente-lite.packer` - EDN serialization
- `sente-lite.queue-scittle` - Message queue
- `sente-lite.client-scittle` - WebSocket client
- `sente-lite.registry` - Registry for state management
- `nrepl-sente.protocol` - nREPL message protocol
- `nrepl-sente.server` - nREPL server (shared eval code)
- `nrepl-sente.browser-adapter` - Browser-side adapter

## Testing

```bash
# Start the test server
bb serve-bundle.bb

# In another terminal, run Playwright tests
node test-bundle.mjs
```

## Regenerating the Bundle

```bash
bb build-bundle.bb
```

This reads source files from `../src/` and `../modules/nrepl/src/` and concatenates them.
