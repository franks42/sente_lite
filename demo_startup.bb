#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[telemere-lite.core :as tel])

(println "=== BB Startup Demo ===")

;; Initialize telemetry
(tel/startup!)

;; Simulate module loading
(tel/module-load! "demo-module")
(Thread/sleep 100) ;; Simulate loading time
(tel/module-loaded! "demo-module" 100)

;; Test various log levels
(tel/log! :info "Demo application started" {:version "0.1.0"})
(tel/log! :debug "Debug message" {:debug-data "test"})
(tel/log! :warn "Warning message" {:warning-type "test"})

;; Test performance logging
(tel/with-timing "demo-operation"
  (Thread/sleep 50)
  (println "Performed demo operation"))

;; Test error logging
(tel/error! "Demo error" {:error-type "simulation" :severity "low"})

(tel/log! :info "Demo completed" {:total-operations 4})

(println "Check logs/telemetry.jsonl for structured output")