#!/usr/bin/env bb
;;
;; Unit tests for sente-lite BB send queue
;;

(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs])

(def project-root (-> *file* fs/parent fs/parent fs/parent fs/parent str))
(cp/add-classpath (str project-root "/src"))

(require '[sente-lite.queue :as q]
         '[sente-lite.queue-bb :as qbb])

(println "=== sente-lite BB Send Queue Unit Tests ===\n")

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

;; ============================================================================
;; Test 1: Basic enqueue/dequeue
;; ============================================================================

(println "Test 1: Basic enqueue/dequeue")

(let [sent (atom [])
      queue (qbb/make-send-queue {:on-send #(swap! sent conj %)
                                   :max-depth 10
                                   :flush-interval-ms 5})]

  ;; Enqueue before start should still work
  (test= :ok (q/enqueue! queue {:msg 1}) "enqueue returns :ok")
  (test= :ok (q/enqueue! queue {:msg 2}) "enqueue second msg returns :ok")

  ;; Start the queue
  (q/start! queue)

  ;; Wait for flush
  (Thread/sleep 50)

  ;; Check messages were sent
  (test= 2 (count @sent) "2 messages sent")
  (test= {:msg 1} (first @sent) "first message correct")
  (test= {:msg 2} (second @sent) "second message correct")

  ;; Check stats
  (let [stats (q/queue-stats queue)]
    (test= 2 (:enqueued stats) "stats: 2 enqueued")
    (test= 2 (:sent stats) "stats: 2 sent")
    (test= 0 (:depth stats) "stats: depth is 0"))

  ;; Stop
  (q/stop! queue))

(println)

;; ============================================================================
;; Test 2: Backpressure when full
;; ============================================================================

(println "Test 2: Backpressure when queue full")

(let [sent (atom [])
      ;; Small queue, slow flush (to fill it up)
      queue (qbb/make-send-queue {:on-send #(swap! sent conj %)
                                   :max-depth 3
                                   :flush-interval-ms 1000})]

  ;; Fill the queue (don't start yet)
  (test= :ok (q/enqueue! queue {:msg 1}) "enqueue 1 ok")
  (test= :ok (q/enqueue! queue {:msg 2}) "enqueue 2 ok")
  (test= :ok (q/enqueue! queue {:msg 3}) "enqueue 3 ok")

  ;; Queue is full - should reject
  (test= :rejected (q/enqueue! queue {:msg 4}) "enqueue 4 rejected (full)")
  (test= :rejected (q/enqueue! queue {:msg 5}) "enqueue 5 rejected (full)")

  ;; Check stats show drops
  (let [stats (q/queue-stats queue)]
    (test= 3 (:enqueued stats) "stats: 3 enqueued")
    (test= 2 (:dropped stats) "stats: 2 dropped")
    (test= 3 (:depth stats) "stats: depth is 3"))

  ;; Start and drain
  (q/start! queue)
  (Thread/sleep 50)

  ;; Now queue should have room
  (test= :ok (q/enqueue! queue {:msg 6}) "enqueue 6 ok (after drain)")

  (q/stop! queue))

(println)

;; ============================================================================
;; Test 3: Graceful shutdown drains queue
;; ============================================================================

(println "Test 3: Graceful shutdown drains queue")

(let [sent (atom [])
      ;; Slow flush so messages stay in queue
      queue (qbb/make-send-queue {:on-send #(swap! sent conj %)
                                   :max-depth 100
                                   :flush-interval-ms 10000})]

  ;; Enqueue without starting
  (q/enqueue! queue {:msg "a"})
  (q/enqueue! queue {:msg "b"})
  (q/enqueue! queue {:msg "c"})

  (test= 0 (count @sent) "no messages sent before start")

  ;; Start queue
  (q/start! queue)

  ;; Don't wait for flush - stop immediately
  ;; Stop should drain remaining messages
  (let [final-stats (q/stop! queue)]
    (test= 3 (count @sent) "all 3 messages sent on stop")
    (test= 3 (:sent final-stats) "final stats: 3 sent")
    (test= 0 (:depth final-stats) "final stats: depth 0")))

(println)

;; ============================================================================
;; Test 4: High throughput
;; ============================================================================

(println "Test 4: High throughput stress test")

(let [sent (atom 0)
      queue (qbb/make-send-queue {:on-send (fn [_] (swap! sent inc))
                                   :max-depth 10000
                                   :flush-interval-ms 1})]

  (q/start! queue)

  ;; Send 5000 messages quickly
  (let [n 5000
        start (System/currentTimeMillis)]
    (dotimes [i n]
      (q/enqueue! queue {:i i}))
    (let [enqueue-time (- (System/currentTimeMillis) start)]
      (test-true (< enqueue-time 500) (str "enqueue " n " msgs in " enqueue-time "ms (< 500ms)"))))

  ;; Wait for flush
  (Thread/sleep 200)

  (let [stats (q/stop! queue)]
    (test= 5000 (:sent stats) "all 5000 messages sent")
    (test= 0 (:dropped stats) "no messages dropped")
    (test= 0 (:errors stats) "no errors")))

(println)

;; ============================================================================
;; Test 5: Error handling
;; ============================================================================

(println "Test 5: Error handling")

(let [errors (atom [])
      call-count (atom 0)
      queue (qbb/make-send-queue
             {:on-send (fn [msg]
                         (swap! call-count inc)
                         (when (= (:fail msg) true)
                           (throw (ex-info "Simulated error" {:msg msg}))))
              :on-error (fn [e msg] (swap! errors conj {:error e :msg msg}))
              :max-depth 10
              :flush-interval-ms 5})]

  (q/start! queue)

  ;; Send mix of good and bad messages
  (q/enqueue! queue {:msg 1})
  (q/enqueue! queue {:fail true})
  (q/enqueue! queue {:msg 2})
  (q/enqueue! queue {:fail true})
  (q/enqueue! queue {:msg 3})

  ;; Wait for flush
  (Thread/sleep 100)

  (let [stats (q/stop! queue)]
    (test= 5 @call-count "on-send called 5 times")
    (test= 2 (count @errors) "2 errors captured")
    (test= 2 (:errors stats) "stats: 2 errors")
    (test= 3 (:sent stats) "stats: 3 sent (successful only)")))

(println)

;; ============================================================================
;; Summary
;; ============================================================================

(println "=== Summary ===")
(let [{:keys [passed failed]} @test-results]
  (println (str "Passed: " passed))
  (println (str "Failed: " failed))
  (println)
  (if (zero? failed)
    (do (println "✅ All tests passed!")
        (System/exit 0))
    (do (println "❌ Some tests failed!")
        (System/exit 1))))
