(ns telemere-lite.improvements-test
  "Tests for telemere-lite improvements: shutdown hook, regex pre-compilation, error handler"
  (:require [telemere-lite.core :as tel]
            #?(:bb [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

;; =============================================================================
;; Shutdown Hook Tests
;; =============================================================================

#?(:bb
   (deftest shutdown-hook-installation-test
     (testing "Shutdown hook gets installed on first async handler"
       ;; Reset the shutdown hook state (double deref: var then atom)
       (reset! @#'tel/shutdown-hook-installed? false)
       (reset! tel/*handlers* {})

       ;; Add async handler
       (tel/add-handler! :test-async-1
                         (fn [signal] nil)
                         {:async {:mode :blocking :buffer-size 100}})

       ;; Hook should be installed (double deref: var then atom)
       (is (true? @@#'tel/shutdown-hook-installed?)
           "Shutdown hook should be installed after adding async handler"))))

#?(:bb
   (deftest shutdown-hook-idempotent-test
     (testing "Shutdown hook only installed once (idempotent)"
       ;; Reset the shutdown hook state (double deref: var then atom)
       (reset! @#'tel/shutdown-hook-installed? false)
       (reset! tel/*handlers* {})

       ;; Add multiple async handlers
       (tel/add-handler! :test-async-2
                         (fn [signal] nil)
                         {:async {:mode :blocking :buffer-size 100}})
       (tel/add-handler! :test-async-3
                         (fn [signal] nil)
                         {:async {:mode :blocking :buffer-size 100}})

       ;; Hook should still only be installed once (double deref: var then atom)
       (is (true? @@#'tel/shutdown-hook-installed?)
           "Shutdown hook should be installed")

       ;; Cleanup
       (tel/remove-handler! :test-async-2)
       (tel/remove-handler! :test-async-3))))

#?(:bb
   (deftest shutdown-hook-no-install-for-sync-test
     (testing "Shutdown hook NOT installed for sync handlers"
       ;; Reset the shutdown hook state (double deref: var then atom)
       (reset! @#'tel/shutdown-hook-installed? false)
       (reset! tel/*handlers* {})

       ;; Add sync handler (no async opts)
       (tel/add-handler! :test-sync-1
                         (fn [signal] nil)
                         {})

       ;; Hook should NOT be installed (double deref: var then atom)
       (is (false? @@#'tel/shutdown-hook-installed?)
           "Shutdown hook should NOT be installed for sync handler")

       ;; Cleanup
       (tel/remove-handler! :test-sync-1))))

#?(:bb
   (deftest shutdown-cleanup-test
     (testing "All async handlers get cleaned up on shutdown"
       ;; Reset and prepare
       (reset! tel/*handlers* {})

       ;; Track shutdown calls
       (def shutdown-calls (atom #{}))

       ;; Add multiple async handlers with custom shutdown tracking
       (tel/add-handler! :test-async-4
                         (fn [signal] nil)
                         {:async {:mode :blocking :buffer-size 100}})
       (tel/add-handler! :test-async-5
                         (fn [signal] nil)
                         {:async {:mode :blocking :buffer-size 100}})

       ;; Verify handlers are registered
       (is (= 2 (count @tel/*handlers*))
           "Two handlers should be registered")

       ;; Call shutdown manually
       (tel/shutdown-telemetry!)

       ;; Verify all handlers are removed
       (is (= 0 (count @tel/*handlers*))
           "All handlers should be removed after shutdown"))))

;; =============================================================================
;; Regex Pre-compilation Tests
;; =============================================================================

#?(:bb
   (deftest wildcard->regex-test
     (testing "Wildcard pattern conversion to regex"
       ;; Test simple wildcard
       (let [regex (@#'tel/wildcard->regex "foo.*")]
         (is (re-matches regex "foo.bar") "Should match 'foo.bar'")
         (is (re-matches regex "foo.baz.qux") "Should match 'foo.baz.qux'")
         (is (nil? (re-matches regex "bar.foo")) "Should not match 'bar.foo'"))

       ;; Test exact match (no wildcard)
       (let [regex (@#'tel/wildcard->regex "exact.ns")]
         (is (re-matches regex "exact.ns") "Should match exact namespace")
         (is (nil? (re-matches regex "exact.ns.child")) "Should not match child namespace"))

       ;; Test full wildcard
       (let [regex (@#'tel/wildcard->regex "*")]
         (is (re-matches regex "anything") "Should match anything")
         (is (re-matches regex "foo.bar.baz") "Should match any namespace")))))

#?(:bb
   (deftest ns-filter-correctness-test
     (testing "Namespace filtering with pre-compiled regexes - verifies filter structure"
       ;; Setup: Allow foo.* but deny foo.bar.*
       (tel/set-ns-filter! {:allow ["foo.*"]
                            :disallow ["foo.bar.*"]})

       ;; Verify filter structure contains pre-compiled regexes
       (let [filter @#'tel/*ns-filter*]
         (is (contains? filter :allow-re) "Filter should have :allow-re key")
         (is (contains? filter :deny-re) "Filter should have :deny-re key")
         (is (vector? (:allow-re filter)) "allow-re should be a vector")
         (is (vector? (:deny-re filter)) "deny-re should be a vector")
         (is (= 1 (count (:allow-re filter))) "Should have 1 allow pattern")
         (is (= 1 (count (:deny-re filter))) "Should have 1 deny pattern")
         ;; Verify they are actual Pattern objects
         (is (instance? java.util.regex.Pattern (first (:allow-re filter)))
             "allow pattern should be compiled regex"))

       ;; Cleanup
       (tel/set-ns-filter! {:allow ["*"] :disallow []}))))

#?(:bb
   (deftest event-id-filter-correctness-test
     (testing "Event-ID filtering with pre-compiled regexes - verifies filter structure"
       ;; Setup: Allow :app.* but deny :app.internal.*
       (tel/set-id-filter! {:allow [:app.*]
                            :disallow [:app.internal.*]})

       ;; Verify filter structure contains pre-compiled regexes
       (let [filter @#'tel/*event-id-filter*]
         (is (contains? filter :allow-re) "Filter should have :allow-re key")
         (is (contains? filter :deny-re) "Filter should have :deny-re key")
         (is (vector? (:allow-re filter)) "allow-re should be a vector")
         (is (vector? (:deny-re filter)) "deny-re should be a vector")
         (is (= 1 (count (:allow-re filter))) "Should have 1 allow pattern")
         (is (= 1 (count (:deny-re filter))) "Should have 1 deny pattern")
         ;; Verify they are actual Pattern objects
         (is (instance? java.util.regex.Pattern (first (:allow-re filter)))
             "allow pattern should be compiled regex"))

       ;; Cleanup
       (tel/set-id-filter! {:allow ["*"] :disallow []}))))

#?(:bb
   (deftest regex-precompilation-performance-test
     (testing "Pre-compiled regexes are significantly faster"
       (let [iterations 10000
             test-ns "foo.bar.baz.qux.test"

             ;; Benchmark OLD approach (runtime compilation)
             old-start (System/nanoTime)
             _ (dotimes [_ iterations]
                 (let [pattern "foo.*"
                       regex (re-pattern (clojure.string/replace pattern "*" ".*"))]
                   (re-matches regex test-ns)))
             old-time (/ (- (System/nanoTime) old-start) 1000000.0)

             ;; Benchmark NEW approach (pre-compiled)
             compiled-regex (re-pattern "foo\\..*")
             new-start (System/nanoTime)
             _ (dotimes [_ iterations]
                 (re-matches compiled-regex test-ns))
             new-time (/ (- (System/nanoTime) new-start) 1000000.0)

             speedup (/ old-time new-time)]

         (println (str "\nRegex Pre-compilation Performance:"))
         (println (str "  Old (runtime): " (format "%.2f" old-time) "ms"))
         (println (str "  New (pre-compiled): " (format "%.2f" new-time) "ms"))
         (println (str "  Speedup: " (format "%.1fx" speedup) " faster\n"))

         ;; Verify significant performance improvement (at least 2x)
         ;; Note: Modern JVMs optimize regex compilation, so 2x is reasonable
         (is (>= speedup 2.0)
             (str "Pre-compiled regexes should be at least 2x faster (actual: "
                  (format "%.1fx" speedup) ")"))))))

#?(:bb
   (deftest ns-filter-behavior-test
     (testing "Namespace filtering actually blocks/allows signals"
       ;; Track what signals reach the handler
       (def received-signals (atom []))

       ;; Add test handler
       (tel/add-handler! :ns-filter-test
                         (fn [signal]
                           (swap! received-signals conj signal))
                         {})

       ;; Test 1: Allow this namespace (telemere-lite.improvements-test)
       (tel/set-ns-filter! {:allow ["telemere-lite.*"]
                            :disallow []})

       (tel/log! :info "Should be allowed - matching namespace")

       ;; Wait for async processing
       (Thread/sleep 100)

       ;; Verify signal was allowed
       (is (= 1 (count @received-signals))
           "Signal from allowed namespace should reach handler")

       ;; Reset
       (reset! received-signals [])

       ;; Test 2: Deny this namespace
       (tel/set-ns-filter! {:allow ["*"]
                            :disallow ["telemere-lite.improvements-test"]})

       (tel/log! :info "Should be denied - explicit deny")

       ;; Wait for async processing
       (Thread/sleep 100)

       ;; Verify signal was blocked
       (is (= 0 (count @received-signals))
           "Signal from denied namespace should be blocked")

       ;; Cleanup
       (tel/remove-handler! :ns-filter-test)
       (tel/set-ns-filter! {:allow ["*"] :disallow []}))))

#?(:bb
   (deftest event-id-filter-behavior-test
     (testing "Event-ID filtering actually blocks/allows events"
       ;; Track what events reach the handler
       (def received-events (atom []))

       ;; Add test handler
       (tel/add-handler! :event-id-filter-test
                         (fn [signal]
                           (swap! received-events conj signal))
                         {})

       ;; Set filter: Allow :app.* but deny :app.internal.*
       (tel/set-id-filter! {:allow [:app.*]
                            :disallow [:app.internal.*]})

       ;; Emit events with different IDs
       (tel/event! :app.user-action {:action "click"})
       (tel/event! :app.internal.debug {:debug "info"})
       (tel/event! :system.startup {:system "init"})

       ;; Wait for async processing
       (Thread/sleep 100)

       ;; Verify: Should only have 1 event (app.user-action)
       (is (= 1 (count @received-events))
           "Only events with allowed IDs should reach handler")

       (when (= 1 (count @received-events))
         (let [event (first @received-events)]
           (is (= :app.user-action (:event-id event))
               "Event should have :app.user-action ID")))

       ;; Cleanup
       (tel/remove-handler! :event-id-filter-test)
       (tel/set-id-filter! {:allow ["*"] :disallow []}))))

;; =============================================================================
;; Error Handler Tests
;; =============================================================================

#?(:bb
   (deftest error-handler-custom-handler-test
     (testing "Custom error handler can be set and receives correct arguments"
       ;; Track what the custom handler receives
       (def error-calls (atom []))

       ;; Set custom error handler
       (tel/set-error-handler!
        (fn [error context]
          (swap! error-calls conj {:error error :context context})))

       ;; Trigger an error via handle-telemetry-error!
       (def test-error (Exception. "Test error"))
       (def test-context {:type :test :handler-id :test-handler})
       (#'tel/handle-telemetry-error! test-error test-context)

       ;; Verify custom handler was called
       (is (= 1 (count @error-calls))
           "Custom error handler should be called once")

       (let [call (first @error-calls)]
         (is (= test-error (:error call))
             "Error object should be passed to handler")
         (is (= test-context (:context call))
             "Context map should be passed to handler"))

       ;; Restore default error handler
       (tel/set-error-handler!
        (fn [error context]
          (binding [*out* *err*]
            (println "Telemetry error:" context)
            (.printStackTrace error *err*)))))))

#?(:bb
   (deftest error-handler-default-handler-structure-test
     (testing "Default error handler is callable and accepts error and context"
       ;; Verify default handler exists and is a function
       (is (fn? @@#'tel/error-handler)
           "Default error handler should be a function")

       ;; Verify it can be called without throwing (output goes to stderr)
       (def test-error (Exception. "Test error with stack"))
       (is (nil? (#'tel/handle-telemetry-error! test-error {:type :test}))
           "Error handler should complete without throwing"))))

#?(:bb
   (deftest error-handler-context-verification-test
     (testing "Error handler receives correct context for different error types"
       (def contexts-received (atom []))

       ;; Set custom handler to track contexts
       (tel/set-error-handler!
        (fn [error context]
          (swap! contexts-received conj context)))

       ;; Test different error types
       (#'tel/handle-telemetry-error! (Exception. "handler error")
                                      {:type :handler-dispatch
                                       :handler-id :my-handler
                                       :signal {:level :info}})

       (#'tel/handle-telemetry-error! (Exception. "file error")
                                      {:type :file-write
                                       :file-path "/tmp/test.log"})

       ;; Verify contexts
       (is (= 2 (count @contexts-received))
           "Should have received 2 error contexts")

       (is (= :handler-dispatch (:type (first @contexts-received)))
           "First context should be handler-dispatch")
       (is (= :my-handler (:handler-id (first @contexts-received)))
           "First context should include handler-id")

       (is (= :file-write (:type (second @contexts-received)))
           "Second context should be file-write")
       (is (= "/tmp/test.log" (:file-path (second @contexts-received)))
           "Second context should include file-path")

       ;; Restore default
       (tel/set-error-handler!
        (fn [error context]
          (binding [*out* *err*]
            (println "Telemetry error:" context)
            (.printStackTrace error *err*)))))))

#?(:bb
   (deftest error-handler-fallback-test
     (testing "Fallback when error handler itself fails"
       (def fallback-triggered (atom false))
       (def fallback-completed (atom false))

       (try
         ;; Set error handler that throws
         (tel/set-error-handler!
          (fn [error context]
            (reset! fallback-triggered true)
            (throw (Exception. "Error handler failed"))))

         ;; Trigger error - should not throw despite handler failure
         (#'tel/handle-telemetry-error! (Exception. "Original error") {:type :test})
         (reset! fallback-completed true)

         ;; Verify fallback was triggered and completed
         (is (true? @fallback-triggered)
             "Failing error handler should have been called")
         (is (true? @fallback-completed)
             "Fallback should complete without throwing")

         (finally
           ;; Restore default handler
           (tel/set-error-handler!
            (fn [error context]
              (binding [*out* *err*]
                (println "Telemetry error:" context)
                (.printStackTrace error *err*)))))))))
