;; Telemetry Configuration for Browser
;;
;; Loaded after telemere-lite.cljs to configure telemetry behavior.
;; NOTE: As of v0.6.1, telemetry is DISABLED BY DEFAULT in the library itself.
;;
;; This file serves two purposes:
;;   1. Documents how to enable and configure telemetry
;;   2. Provides runtime control functions (window.telemetry*())
;;
;; To enable telemetry for development/debugging:
;;   1. Uncomment the (set-enabled! true) line below
;;   2. Uncomment desired sink configurations
;;   3. Reload the page

(ns telemetry-config
  (:require [telemere-lite.core :as tel]))

(println "📊 Configuring telemetry...")

;; ============================================================================
;; DEFAULT: Telemetry DISABLED (no configuration needed)
;; ============================================================================
;;
;; The library defaults to DISABLED for maximum performance.
;; No action required - telemetry is already off.
;; Performance: 60-120ns per call (3-14x faster than enabled)
;; :let parameters: NOT evaluated

(println "✅ Telemetry: DISABLED by default (library setting)")
(println "   Performance: ~60-120ns per call")
(println "   :let parameters: NOT evaluated")

;; ============================================================================
;; DEVELOPMENT MODE (uncomment to enable)
;; ============================================================================

;; Enable telemetry for development/debugging
;; (tel/set-enabled! true)
;; (println "✅ Telemetry: ENABLED (development mode)")

;; Configure sinks as needed:

;; Sink 1: Browser Console (good for interactive development)
;; (tel/enable-console-sink!)
;; (println "   - Console sink: ENABLED")

;; Sink 2: Event Collection (good for testing)
;; (tel/enable-atom-sink!)
;; (println "   - Atom sink: ENABLED")
;;
;; To retrieve events later:
;; (tel/get-events)         ; Get all events
;; (tel/clear-events!)      ; Clear event buffer

;; Sink 3: WebSocket to Server (good for production observability)
;; NOTE: Requires sente-lite client to set *send-fn*
;; (tel/enable-remote-sink!)
;; (println "   - Remote sink: ENABLED")

;; ============================================================================
;; PRODUCTION MODE (example)
;; ============================================================================

;; For production, you might want:
;; - Telemetry: ENABLED
;; - Console: DISABLED
;; - Atom: DISABLED
;; - Remote: ENABLED (send to server for centralized logging)
;;
;; (tel/set-enabled! true)
;; (tel/disable-console-sink!)
;; (tel/disable-atom-sink!)
;; (tel/enable-remote-sink!)
;; (println "✅ Telemetry: PRODUCTION MODE (remote sink only)")

;; ============================================================================
;; RUNTIME CONTROL (for production debugging from JS console)
;; ============================================================================

;; Expose telemetry controls as global JS functions
;; Usage from browser console:
;;   window.telemetryEnable()         - Enable telemetry
;;   window.telemetryDisable()        - Disable telemetry
;;   window.telemetryConsoleOn()      - Enable console sink
;;   window.telemetryConsoleOff()     - Disable console sink
;;   window.telemetryStatus()         - Show current status

(set! (.-telemetryEnable js/window)
      (fn []
        (tel/set-enabled! true)
        (println "✅ Telemetry ENABLED")))

(set! (.-telemetryDisable js/window)
      (fn []
        (tel/set-enabled! false)
        (println "✅ Telemetry DISABLED")))

(set! (.-telemetryConsoleOn js/window)
      (fn []
        (tel/enable-console-sink!)
        (println "✅ Console sink ENABLED")))

(set! (.-telemetryConsoleOff js/window)
      (fn []
        (tel/disable-console-sink!)
        (println "✅ Console sink DISABLED")))

(set! (.-telemetryStatus js/window)
      (fn []
        (println "📊 Telemetry Status:")
        (println "  Enabled:      " (deref #'tel/*telemetry-enabled*))
        (println "  Console sink: " (deref tel/*console-enabled*))
        (println "  Atom sink:    " (deref tel/*atom-sink-enabled*))
        (println "  Remote sink:  " (deref tel/*remote-sink-enabled*))))

(println "📊 Telemetry configuration complete")
(println "   Runtime controls available:")
(println "   - window.telemetryEnable()")
(println "   - window.telemetryDisable()")
(println "   - window.telemetryConsoleOn()")
(println "   - window.telemetryConsoleOff()")
(println "   - window.telemetryStatus()")
