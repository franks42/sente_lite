# Trove for Scittle - User Guide

## Quick Start

Load the wrapper in your HTML:

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js"></script>
<script src="dev/scittle-demo/trove-macros.cljs" type="application/x-scittle"></script>

<script type="application/x-scittle">
  (require '[taoensso.trove :refer [log!]])
  
  ;; Log something
  (log! {:level :info :id :app/started})
</script>
```

## Basic Usage

### Simple log

```clojure
(log! {:level :info :id :event/name})
```

### With message

```clojure
(log! {:level :warn :id :auth/failed :msg "Invalid credentials"})
```

### With data

```clojure
(log! {:level :info :id :user/login :data {:user-id 123 :email "test@example.com"}})
```

### All log levels

```clojure
(log! {:level :trace :id :event})
(log! {:level :debug :id :event})
(log! {:level :info :id :event})
(log! {:level :warn :id :event})
(log! {:level :error :id :event})
(log! {:level :fatal :id :event})
```

## Advanced Usage

### Custom backend

```clojure
(require '[taoensso.trove :refer [log! *log-fn*]])

(set! *log-fn*
  (fn [ns coords level id lazy-form]
    (let [data (if lazy-form (force lazy-form) {})]
      ;; Send to server, analytics, etc.
      (js/console.log (str "LOG: " level " " id)))))

(log! {:level :info :id :event})
```

### Lazy evaluation (delay)

The `:data` is wrapped in a `delay`, so it's only evaluated when the backend accesses it:

```clojure
(log! {:level :info :id :event :data (expensive-computation)})
;; expensive-computation is NOT called if *log-fn* is nil
```

This lets backends skip expensive operations:

```clojure
(set! *log-fn*
  (fn [ns coords level id lazy-form]
    (when (= level :error)  ;; Only log errors
      (let [data (force lazy-form)]  ;; Only force if needed
        (send-to-server data)))))
```

### Disable logging

```clojure
(require '[taoensso.trove :refer [*log-fn*]])

(set! *log-fn* nil)
;; All log! calls now noop
```

## Signature Reference

```clojure
(log! opts)
```

**opts** - Map with keys:
- `:level` - Log level keyword (`:trace`, `:debug`, `:info`, `:warn`, `:error`, `:fatal`)
- `:id` - Event ID keyword (e.g., `:app/started`, `:user/login`)
- `:msg` - Optional message string
- `:data` - Optional data map
- `:error` - Optional error object
- `:ns` - Optional namespace override (default: current namespace)
- `:coords` - Optional [line column] override
- `:log-fn` - Optional backend override (default: `*log-fn*`)

## Default Backend

Logs to browser console with timestamp:

```
2025-12-01T05:11:26.603Z info taoensso.trove  :app/started {}
```

## Migration from Trove Macro

When Trove's `log!` macro becomes available in SCI, just replace the wrapper file - the API is identical!

```clojure
;; Current (function-based)
(log! {:level :info :id :event :data {...}})

;; Future (macro-based, same API)
(log! {:level :info :id :event :data {...}})
```

## Notes

- Parameters are eagerly evaluated (unlike the macro)
- Use `delay` for expensive computations if needed
- `*log-fn*` is dynamic, can be changed with `set!`
- Nil backend causes all logs to noop (no error)
