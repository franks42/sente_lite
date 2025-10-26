#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[telemere-lite.core :as tel])

(println "=== Testing Telemere-compatible Filtering API ===")

;; Initialize telemetry
(tel/startup!)

(println "\n1. Testing basic filtering state:")
(println "Current filters:" (tel/get-filters))
(println "Enabled?" (tel/get-enabled?))

(println "\n2. Testing level filtering:")
;; Set minimum level to :warn
(tel/set-min-level! :warn)
(println "Set min level to :warn")
(println "Current min level:" (tel/get-min-level))

;; These should be filtered out
(tel/log! :debug "This debug should be filtered")
(tel/log! :info "This info should be filtered")

;; These should pass through
(tel/log! :warn "This warning should appear")
(tel/log! :error "This error should appear")

(println "\n3. Testing namespace-specific filtering:")
;; Set stricter level for test namespace
(tel/set-min-level! :info "user" :error)
(println "Set min level for 'user' namespace to :error")

;; This should now be filtered even though it's :warn
(tel/log! :warn "This warning should now be filtered (user ns)")

;; This should still pass
(tel/log! :error "This error should still appear")

(println "\n4. Testing namespace allow/disallow:")
(tel/set-ns-filter! {:disallow #{"user"} :allow #{"*"}})
(println "Disabled logging for 'user' namespace")

;; This should be filtered by namespace
(tel/log! :error "This should be filtered by namespace")

;; Reset to allow all
(tel/set-ns-filter! {:allow #{"*"} :disallow #{}})
(tel/log! :info "This should appear after reset")

(println "\n5. Testing global enable/disable:")
(tel/set-enabled! false)
(println "Disabled all telemetry")
(tel/log! :error "This should not appear - telemetry disabled")

(tel/set-enabled! true)
(println "Re-enabled telemetry")
(tel/log! :info "This should appear - telemetry re-enabled")

(println "\n6. Clearing all filters:")
(tel/clear-filters!)
(println "Cleared all filters")
(println "Final filters:" (tel/get-filters))

(tel/log! :debug "Final debug message should appear")
(tel/log! :info "Final info message should appear")

(println "\n✅ Filtering API tests completed!")
(println "Check logs/telemetry.jsonl for filtered output")