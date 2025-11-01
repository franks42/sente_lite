#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[telemere-lite.core :as tel])

(println "=== Testing Meta-Telemetry (Monitoring the Monitor) ===\n")

;; 1. Enable telemetry and add stdout handler - should generate meta-telemetry events
(println "1. Enabling telemetry...")
(tel/set-enabled! true)
(Thread/sleep 100)

(println "\n2. Adding stdout handler...")
(tel/add-stdout-handler!)
(Thread/sleep 100)

(println "\n3. Setting namespace filter...")
(tel/set-ns-filter! {:allow ["sente-lite.*" "telemere-lite.*"]})
(Thread/sleep 100)

(println "\n4. Setting event ID filter...")
(tel/set-id-filter! {:allow ["::*"]})
(Thread/sleep 100)

(println "\n5. Adding file handler...")
(tel/add-file-handler! :my-file "/tmp/test-telemetry.jsonl")
(Thread/sleep 100)

(println "\n6. Removing file handler...")
(tel/remove-handler! :my-file)
(Thread/sleep 100)

(println "\n7. Clearing all handlers...")
(tel/clear-handlers!)
(Thread/sleep 100)

(println "\n=== Meta-Telemetry Test Complete ===")
(println "All configuration changes should have been logged above!")
