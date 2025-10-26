#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[telemere-lite.core :as tel])

(println "=== Testing Official Telemere API Compatibility ===")

;; Initialize telemetry
(tel/startup!)

(println "\n1. Testing log! macro with official signatures:")

;; Official Telemere log! examples:
(tel/log! {:level :info :msg "Full options map style"})
(tel/log! :info "Level and message style")
(tel/log! :debug "Debug message")
(tel/log! {:level :warn :msg "Warning with data" :user-id 123 :action "login"})

(println "\n2. Testing error! macro with official signatures:")

;; Official Telemere error! examples:
(tel/error! (Exception. "Something went wrong"))
(tel/error! {:msg "Network failure" :error-type "timeout" :host "localhost"})
(tel/error! "Custom error message" {:severity "high"})

(println "\n3. Testing backwards compatibility:")

;; Our original API should still work:
(tel/log! :info "Original style" {:legacy "supported"})
(tel/error! "Original error" {:error-type "legacy"})

(println "\n4. Testing edge cases:")

;; Single level (unusual but should work)
(tel/log! :info)

;; Empty message cases
(tel/log! {:level :info})

(println "\n✅ All API compatibility tests completed!")
(println "Check logs/telemetry.jsonl for structured output")