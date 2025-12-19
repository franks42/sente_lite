#!/usr/bin/env bb

(require '[babashka.fs :as fs])

(println "🧪 === Running Complete Sente-lite Test Suite ===")
(println)

(def test-results (atom {:total 0 :passed 0 :failed 0}))

;; Get the directory where this script is located
(def script-dir (-> *file* fs/parent str))

(defn run-test [test-name test-file]
  (println (format "🔄 Running %s..." test-name))
  (swap! test-results update :total inc)

  (try
    (let [full-path (str script-dir "/" test-file)
          result (babashka.process/shell {:out :string :err :string}
                                        "bb" full-path)]
      (if (zero? (:exit result))
        (do
          (println (format "✅ %s PASSED" test-name))
          (swap! test-results update :passed inc))
        (do
          (println (format "❌ %s FAILED (exit code: %d)" test-name (:exit result)))
          (println "STDERR:" (:err result))
          (swap! test-results update :failed inc))))
    (catch Exception e
      (println (format "❌ %s ERROR: %s" test-name (str e)))
      (swap! test-results update :failed inc)))

  (println))

(println "📋 Test Plan:")
(println "  Phase 1: Wire Format Tests")
(println "  Phase 2: Server Foundation Tests")
(println "  Phase 3: Channel Integration Tests")
(println)

;; Phase 1: Wire format tests
(println "🔧 === Phase 1: Wire Format Tests ===")
(run-test "Wire Formats" "test_wire_formats.bb")
(run-test "Timbre Functions" "test_timbre_functions.bb")

;; Phase 2: Server foundation
(println "🌐 === Phase 2: Server Foundation Tests ===")
(run-test "WebSocket Foundation" "test_websocket_foundation.bb")
(run-test "Server Foundation" "test_server_foundation.bb")

;; Phase 3: Channel integration
(println " === Phase 3: Channel Integration Tests ===")
(run-test "Channel Integration" "test_channel_integration.bb")

;; Summary
(println " === Test Summary ===")
(let [results @test-results]
  (println (format "Total tests: %d" (:total results)))
  (println (format "Passed: %d" (:passed results)))
  (println (format "Failed: %d" (:failed results)))
  (println)

  (if (zero? (:failed results))
    (do
      (println " ALL TESTS PASSED!")
      (println)
      (println " Ready for Production:")
      (println "  Wire Formats: JSON, EDN, Transit+JSON")
      (println "  WebSocket Foundation: Production-ready server")
      (println "  Channel System: Complete pub/sub messaging with RPC")
      (println "  Logging: Trove integration")
      (println)
      (println " Sente-lite with Trove logging!")
      (System/exit 0))
    (do
      (println "  ❌ SOME TESTS FAILED!")
      (println (format "Fix %d failing test(s) before proceeding" (:failed results)))
      (System/exit 1))))