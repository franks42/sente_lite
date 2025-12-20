#!/usr/bin/env bb
;; Test script for on!/off! unified handler API
;; Tests the new on!, off!, and handler-count functions

(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs])

;; Add src to classpath (test/scripts -> test -> project_root)
(def project-root (-> *file* fs/parent fs/parent fs/parent str))
(cp/add-classpath (str project-root "/src"))

(require '[sente-lite.server :as server]
         '[sente-lite.client-bb :as client])

;; Note: Trove already logs to file only for BB (console disabled)

(def test-results (atom {:passed 0 :failed 0}))

(defn pass! [msg]
  (swap! test-results update :passed inc)
  (println "  ✓" msg))

(defn fail! [msg]
  (swap! test-results update :failed inc)
  (println "  ✗" msg))

(defn check [expected actual msg]
  (if (= expected actual)
    (pass! msg)
    (fail! (str msg " - expected: " expected ", got: " actual))))

(defn check-true [actual msg]
  (if actual
    (pass! msg)
    (fail! (str msg " - expected truthy, got: " actual))))

(println "=== sente-lite on!/off! API Tests (BB-to-BB) ===")
(println)

;; Start server
(println "1. Starting server...")
(def port (+ 50000 (rand-int 10000)))
(def srv (server/start-server! {:port port}))
(Thread/sleep 100)
(check-true (some? srv) (str "Server started on port " port))
(println)

;; Create client
(println "2. Creating client...")
(def received-messages (atom []))
(def client-id (client/make-client!
                {:url (str "ws://localhost:" port "/")
                 :auto-reconnect? false}))
(Thread/sleep 300)
(check :connected (client/get-status client-id) "Client connected")
(println)

;; Test 1: Register persistent handler
(println "Test 1: Persistent handler with :event-id")
(def h1 (client/on! client-id {:event-id :sente-lite/echo
                                :callback (fn [msg]
                                            (swap! received-messages conj msg))}))
(check-true (string? h1) "on! returns handler-id")
(check 1 (client/handler-count client-id) "handler-count is 1")

;; Trigger the handler
(client/send! client-id [:test/echo {:data "hello"}])
(Thread/sleep 200)
(check 1 (count @received-messages) "Handler received message")
(check :sente-lite/echo (:event-id (first @received-messages)) "Message has correct event-id")

;; Handler should still be registered (persistent)
(check 1 (client/handler-count client-id) "Handler still registered")
(println)

;; Test 2: Remove handler by id
(println "Test 2: Remove handler by id")
(def removed? (client/off! client-id h1))
(check-true removed? "off! returns true")
(check 0 (client/handler-count client-id) "handler-count is 0")
(println)

;; Test 3: Register once? handler
(println "Test 3: One-shot handler (:once? true)")
(reset! received-messages [])
(def h2 (client/on! client-id {:event-id :sente-lite/echo
                                :once? true
                                :callback (fn [msg]
                                            (swap! received-messages conj msg))}))
(check-true (string? h2) "on! with :once? returns handler-id")
(check 1 (client/handler-count client-id) "handler-count is 1")

;; Trigger the handler
(client/send! client-id [:test/echo {:data "once"}])
(Thread/sleep 200)
(check 1 (count @received-messages) "Handler received message")
(check 0 (client/handler-count client-id) "Handler auto-removed after match")
(println)

;; Test 4: Handler with :pred
(println "Test 4: Predicate-based handler")
(reset! received-messages [])
(def target-id "req-123")
(def h3 (client/on! client-id {:pred (fn [msg]
                                        (= target-id (get-in msg [:data :original-data :id])))
                                :callback (fn [msg]
                                            (swap! received-messages conj msg))}))
(check-true (string? h3) "on! with :pred returns handler-id")

;; Send non-matching message
(client/send! client-id [:test/echo {:id "req-456"}])
(Thread/sleep 200)
(check 0 (count @received-messages) "Non-matching message not received")

;; Send matching message
(client/send! client-id [:test/echo {:id target-id}])
(Thread/sleep 200)
(check 1 (count @received-messages) "Matching message received")

(client/off! client-id h3)
(println)

;; Test 5: Catch-all handler with :*
(println "Test 5: Catch-all handler (:event-id :*)")
(reset! received-messages [])
(def h4 (client/on! client-id {:event-id :*
                                :callback (fn [msg]
                                            (swap! received-messages conj msg))}))
(check-true (string? h4) "on! with :* returns handler-id")

;; Send different event types
(client/send! client-id [:test/echo {:data 1}])
(Thread/sleep 200)
(check 1 (count @received-messages) "Catch-all receives echo")

(client/off! client-id h4)
(println)

;; Test 6: Remove all handlers
(println "Test 6: Remove all handlers")
(client/on! client-id {:event-id :a :callback identity})
(client/on! client-id {:event-id :b :callback identity})
(client/on! client-id {:event-id :c :callback identity})
(check 3 (client/handler-count client-id) "3 handlers registered")
(def removed-all? (client/off! client-id :all))
(check-true removed-all? "off! :all returns true")
(check 0 (client/handler-count client-id) "handler-count is 0")
(println)

;; Test 7: Remove by event-id
(println "Test 7: Remove by event-id")
(client/on! client-id {:event-id :foo :callback identity})
(client/on! client-id {:event-id :foo :callback identity})
(client/on! client-id {:event-id :bar :callback identity})
(check 3 (client/handler-count client-id) "3 handlers registered")
(def removed-foo? (client/off! client-id {:event-id :foo}))
(check-true removed-foo? "off! {:event-id :foo} returns true")
(check 1 (client/handler-count client-id) "handler-count is 1 (only :bar)")
(client/off! client-id :all)
(println)

;; Test 8: Timeout on once? handler
(println "Test 8: Timeout on once? handler")
(reset! received-messages [])
(def timeout-received (atom nil))
(def h5 (client/on! client-id {:event-id :never/happens
                                :once? true
                                :timeout-ms 200
                                :callback (fn [msg]
                                            (reset! timeout-received msg))}))
(check-true (string? h5) "on! with timeout returns handler-id")
(check 1 (client/handler-count client-id) "handler-count is 1")

;; Wait for timeout
(Thread/sleep 350)
(check-true (some? @timeout-received) "Timeout callback received")
(check :timeout (:error @timeout-received) "Timeout has :error :timeout")
(check 0 (client/handler-count client-id) "Handler auto-removed after timeout")
(println)

;; Cleanup
(println "3. Cleanup...")
(client/close! client-id)
(Thread/sleep 100)
(server/stop-server!)
(check-true true "Server stopped")
(println)

;; Summary
(println "=== Summary ===")
(println "Passed:" (:passed @test-results))
(println "Failed:" (:failed @test-results))
(println)

(if (zero? (:failed @test-results))
  (do (println "✅ All tests passed!")
      (System/exit 0))
  (do (println "❌ Some tests failed!")
      (System/exit 1)))
