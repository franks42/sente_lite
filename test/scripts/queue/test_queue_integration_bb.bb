#!/usr/bin/env bb
;;
;; BB-to-BB integration test for send queue
;;
;; Tests queue functionality via server echo responses.
;; The server echoes back all unknown events with :sente-lite/echo.
;;

(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs])

(def project-root (-> *file* fs/parent fs/parent fs/parent fs/parent str))
(cp/add-classpath (str project-root "/src"))

(require '[sente-lite.server :as server]
         '[sente-lite.client-bb :as client])

(println "=== sente-lite Queue Integration Test (BB-to-BB) ===\n")

(def test-results (atom {:passed 0 :failed 0}))

(defn pass! [msg]
  (swap! test-results update :passed inc)
  (println "  ✓" msg))

(defn fail! [msg]
  (swap! test-results update :failed inc)
  (println "  ✗" msg))

(defn test= [expected actual msg]
  (if (= expected actual)
    (pass! msg)
    (fail! (str msg " - expected: " expected ", got: " actual))))

(defn test-true [actual msg]
  (if actual
    (pass! msg)
    (fail! (str msg " - expected truthy, got: " actual))))

(defn test>= [threshold actual msg]
  (if (>= actual threshold)
    (pass! (str msg " - " actual " >= " threshold))
    (fail! (str msg " - expected >= " threshold ", got: " actual))))

;; ============================================================================
;; Test Setup
;; ============================================================================

(println "1. Starting server...")
(def server-port (+ 50000 (rand-int 10000)))

(def server
  (server/start-server!
   {:port server-port}))

(Thread/sleep 100)
(test-true (some? server) (str "Server started on port " server-port))

;; ============================================================================
;; Test 1: Client with queue - basic send and echo response
;; ============================================================================

(println "\nTest 1: Client with queue - basic send and echo")

(def echo-responses-1 (atom []))

(def client-1
  (client/make-client!
   {:url (str "ws://localhost:" server-port "/")
    :send-queue {:max-depth 100
                 :flush-interval-ms 5}
    :on-message (fn [event-id data]
                  (when (= event-id :sente-lite/echo)
                    (swap! echo-responses-1 conj data)))}))

;; Wait for connection
(Thread/sleep 200)
(test= :connected (client/get-status client-1) "Client connected")

;; Send messages through queue
(test= :ok (client/send! client-1 [:test/msg1 {:n 1}]) "send! returns :ok (queued)")
(test= :ok (client/send! client-1 [:test/msg2 {:n 2}]) "send! returns :ok (queued)")
(test= :ok (client/send! client-1 [:test/msg3 {:n 3}]) "send! returns :ok (queued)")

;; Wait for flush and echo responses
(Thread/sleep 300)

;; Check echo responses received
(let [echoes @echo-responses-1
      test-echoes (filter #(#{:test/msg1 :test/msg2 :test/msg3} (:original-event-id %)) echoes)]
  (test= 3 (count test-echoes) "Received 3 echo responses"))

;; Check queue stats
(let [stats (client/queue-stats client-1)]
  (test-true (some? stats) "queue-stats returns stats")
  (test= 3 (:enqueued stats) "stats: 3 enqueued")
  (test= 3 (:sent stats) "stats: 3 sent")
  (test= 0 (:depth stats) "stats: depth is 0")
  (test= 0 (:dropped stats) "stats: 0 dropped")
  (test= 0 (:errors stats) "stats: 0 errors"))

(client/close! client-1)
(Thread/sleep 50)

;; ============================================================================
;; Test 2: Queue backpressure
;; ============================================================================

(println "\nTest 2: Queue backpressure")

(def client-2
  (client/make-client!
   {:url (str "ws://localhost:" server-port "/")
    :send-queue {:max-depth 5           ; Small queue
                 :flush-interval-ms 10000}  ; Long flush (to fill queue)
    :on-message (fn [event-id data])}))

;; Wait for connection
(Thread/sleep 200)
(test= :connected (client/get-status client-2) "Client 2 connected")

;; Fill the queue
(test= :ok (client/send! client-2 [:fill/1 {}]) "fill 1 ok")
(test= :ok (client/send! client-2 [:fill/2 {}]) "fill 2 ok")
(test= :ok (client/send! client-2 [:fill/3 {}]) "fill 3 ok")
(test= :ok (client/send! client-2 [:fill/4 {}]) "fill 4 ok")
(test= :ok (client/send! client-2 [:fill/5 {}]) "fill 5 ok")

;; Queue should be full now
(test= :rejected (client/send! client-2 [:overflow/1 {}]) "overflow 1 rejected")
(test= :rejected (client/send! client-2 [:overflow/2 {}]) "overflow 2 rejected")

;; Check stats show drops
(let [stats (client/queue-stats client-2)]
  (test= 5 (:enqueued stats) "stats: 5 enqueued")
  (test= 2 (:dropped stats) "stats: 2 dropped"))

(client/close! client-2)
(Thread/sleep 50)

;; ============================================================================
;; Test 3: High throughput with echo verification
;; ============================================================================

(println "\nTest 3: High throughput with echo verification")

(def echo-count-3 (atom 0))

(def client-3
  (client/make-client!
   {:url (str "ws://localhost:" server-port "/")
    :send-queue {:max-depth 5000
                 :flush-interval-ms 1}
    :on-message (fn [event-id data]
                  (when (= event-id :sente-lite/echo)
                    (swap! echo-count-3 inc)))}))

;; Wait for connection
(Thread/sleep 200)
(test= :connected (client/get-status client-3) "Client 3 connected")

;; Send many messages quickly
(let [n 1000
      start (System/currentTimeMillis)]
  (dotimes [i n]
    (client/send! client-3 [:throughput/msg {:i i}]))
  (let [enqueue-time (- (System/currentTimeMillis) start)]
    (test-true (< enqueue-time 500) (str "Enqueue " n " in " enqueue-time "ms"))))

;; Wait for flush and echo responses
(Thread/sleep 1000)

;; Check stats
(let [stats (client/queue-stats client-3)]
  (test= 1000 (:enqueued stats) "stats: 1000 enqueued")
  (test>= 990 (:sent stats) "stats: >= 990 sent")  ; Allow some in-flight
  (test= 0 (:dropped stats) "stats: 0 dropped")
  (test= 0 (:errors stats) "stats: 0 errors"))

;; Check echo responses - allow some network variance
(test>= 900 @echo-count-3 "Received >= 900 echo responses")

(client/close! client-3)
(Thread/sleep 50)

;; ============================================================================
;; Test 4: Graceful close with pending messages
;; ============================================================================

(println "\nTest 4: Graceful close with pending messages")

(def client-4
  (client/make-client!
   {:url (str "ws://localhost:" server-port "/")
    :send-queue {:max-depth 100
                 :flush-interval-ms 10000}  ; Long interval - won't auto-flush
    :on-message (fn [event-id data])}))

;; Wait for connection
(Thread/sleep 200)
(test= :connected (client/get-status client-4) "Client 4 connected")

;; Queue messages (won't flush due to long interval)
(test= :ok (client/send! client-4 [:drain/1 {}]) "drain 1 queued")
(test= :ok (client/send! client-4 [:drain/2 {}]) "drain 2 queued")
(test= :ok (client/send! client-4 [:drain/3 {}]) "drain 3 queued")

;; Verify messages are pending in queue before close
(let [stats-before (client/queue-stats client-4)]
  (test= 3 (:enqueued stats-before) "stats before close: 3 enqueued")
  (test= 0 (:sent stats-before) "stats before close: 0 sent (not flushed yet)")
  (test= 3 (:depth stats-before) "stats before close: depth 3"))

;; Close should drain queue (drain happens synchronously in close!)
;; Note: Can't verify echoes because WebSocket closes after drain
(let [close-result (client/close! client-4)]
  (test-true close-result "close! returns true"))

;; Client is now closed - verify it's gone
(test-true (nil? (client/get-status client-4)) "Client removed after close")

;; ============================================================================
;; Cleanup
;; ============================================================================

(println "\n5. Cleanup...")
(server/stop-server!)
(pass! "Server stopped")

;; ============================================================================
;; Summary
;; ============================================================================

(println "\n=== Summary ===")
(let [{:keys [passed failed]} @test-results]
  (println (str "Passed: " passed))
  (println (str "Failed: " failed))
  (println)
  (if (zero? failed)
    (do (println "✅ All tests passed!")
        (System/exit 0))
    (do (println "❌ Some tests failed!")
        (System/exit 1))))
