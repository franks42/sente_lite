#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[telemere-lite.core :as tel])

(println "=== Testing Simple Filtering ===")

;; Initialize telemetry
(tel/startup!)

(println "\n1. Basic level filtering test:")
(println "Current min level:" (tel/get-min-level))

;; Test all levels at debug (should see everything)
(tel/set-min-level! :debug)
(println "\nSet to :debug - should see all:")
(tel/log! :debug "Debug message")
(tel/log! :info "Info message")
(tel/log! :warn "Warn message")

;; Test at warn level (should only see warn+)
(tel/set-min-level! :warn)
(println "\nSet to :warn - should only see warn+:")
(tel/log! :debug "Debug should be filtered")
(tel/log! :info "Info should be filtered")
(tel/log! :warn "Warn should appear")
(tel/log! :error "Error should appear")

(println "\n2. Testing namespace filtering:")
(tel/set-ns-filter! {:allow #{"user"} :disallow #{}})
(println "Allowed only 'user' namespace")
(tel/log! :warn "This should appear (user ns)")

(tel/set-ns-filter! {:allow #{} :disallow #{"user"}})
(println "Disallowed 'user' namespace")
(tel/log! :warn "This should be filtered (user ns)")

;; Reset
(tel/set-ns-filter! {:allow #{"*"} :disallow #{}})
(tel/log! :info "This should appear (reset)")

(println "\n3. Testing global enable/disable:")
(tel/set-enabled! false)
(tel/log! :error "This should not appear")

(tel/set-enabled! true)
(tel/log! :info "This should appear")

(println "\n✅ Simple filtering tests completed!")
(println "Check logs/telemetry.jsonl for output")