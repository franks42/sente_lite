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
(println "  Phase 4: nREPL Module Tests")
(println "  Phase 5: Browser Bundle Tests")
(println)

;; Phase 1: Wire format tests
(println "🔧 === Phase 1: Wire Format Tests ===")
(run-test "Wire Formats" "test_wire_formats.bb")
(run-test "Timbre Functions" "test_timbre_functions.bb")

;; Phase 2: Server foundation
(println "🌐 === Phase 2: Server Foundation Tests ===")
(run-test "WebSocket Foundation" "test_websocket_foundation.bb")
(run-test "Server Foundation" "test_server_foundation.bb")
(run-test "Server on-message Callback" "test_on_message_callback.bb")

;; Phase 3: Channel integration
(println " === Phase 3: Channel Integration Tests ===")
(run-test "Channel Integration" "test_channel_integration.bb")

;; Phase 4: nREPL module
(println "🔌 === Phase 4: nREPL Module Tests ===")
(run-test "nREPL BB-to-BB" "../../modules/nrepl/test/test_nrepl_bb_to_bb.bb")
(run-test "nREPL NS Persistence" "../../modules/nrepl/test/test_nrepl_ns_persistence.bb")
(run-test "nREPL NS Isolation" "../../modules/nrepl/test/test_nrepl_ns_isolation.bb")
(run-test "nREPL Client API" "../../modules/nrepl/test/test_nrepl_client_api.bb")
(run-test "nREPL Proxy" "../../modules/nrepl/test/test_nrepl_proxy.bb")

;; Phase 5: Browser bundle
(println "📦 === Phase 5: Browser Bundle Tests ===")

;; Get project root (two levels up from script-dir)
(def project-root (-> script-dir fs/parent fs/parent str))
(def dist-dir (str project-root "/dist"))

;; Test dependency order validation (runs from dist directory)
(println "🔄 Running Bundle Dependency Order...")
(swap! test-results update :total inc)
(try
 (let [result (babashka.process/shell {:out :string :err :string :dir dist-dir}
                                      "bb" "test-dependency-order.bb")]
   (if (zero? (:exit result))
     (do
      (println "✅ Bundle Dependency Order PASSED")
      (swap! test-results update :passed inc))
     (do
      (println "❌ Bundle Dependency Order FAILED")
      (println (:out result))
      (swap! test-results update :failed inc))))
 (catch Exception e
        (println (format "❌ Bundle Dependency Order ERROR: %s" (str e)))
        (swap! test-results update :failed inc)))
(println)

;; Regenerate bundle (this also validates dependency order)
(println "🔄 Regenerating browser bundle...")
(let [result (babashka.process/shell {:out :string :err :string :dir dist-dir}
                                     "bb" "build-bundle.bb")]
  (if (zero? (:exit result))
    (println "✅ Bundle regenerated (dependency order validated)")
    (do
     (println "❌ Bundle regeneration FAILED:")
     (println (:out result))
     (println (:err result))
     (swap! test-results update :failed inc))))
(println)

;; Run bundle test (starts server, runs Playwright, stops server)
(println "🔄 Running Browser Bundle Test...")
(swap! test-results update :total inc)
(try
 (let [;; Start server
       server (babashka.process/process {:out :inherit :err :inherit :dir dist-dir}
                                        "bb" "serve-bundle.bb")
       _ (Thread/sleep 2000)  ; Wait for server to start
        ;; Run Playwright test
       result (babashka.process/shell {:out :string :err :string :dir dist-dir}
                                      "node" "test-bundle.mjs")]
    ;; Stop server
   (.destroy (:proc server))

   (if (zero? (:exit result))
     (do
      (println "✅ Browser Bundle Test PASSED")
      (swap! test-results update :passed inc))
     (do
      (println "❌ Browser Bundle Test FAILED")
      (println (:out result))
      (swap! test-results update :failed inc))))
 (catch Exception e
        (println (format "❌ Browser Bundle Test ERROR: %s" (str e)))
        (swap! test-results update :failed inc)))
(println)

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