(ns test-queue-nbb
  "Unit tests for sente-lite browser send queue (running in nbb)"
  (:require [sente-lite.queue-scittle :as q]))

(println "=== sente-lite Browser Queue Unit Tests (nbb) ===\n")

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

;; Promisified sleep
(defn sleep [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

;; ============================================================================
;; Test 1: Basic enqueue/dequeue
;; ============================================================================

(defn test-basic []
  (println "Test 1: Basic enqueue/dequeue")
  (let [sent (atom [])
        queue (q/make-send-queue {:on-send #(swap! sent conj %)
                                   :max-depth 10
                                   :flush-interval-ms 5})]

    ;; Enqueue before start
    (test= :ok (q/enqueue! queue {:msg 1}) "enqueue returns :ok")
    (test= :ok (q/enqueue! queue {:msg 2}) "enqueue second msg returns :ok")

    ;; Start the queue
    (q/start! queue)

    ;; Return promise to wait for flush
    (-> (sleep 50)
        (.then (fn []
                 (test= 2 (count @sent) "2 messages sent")
                 (test= {:msg 1} (first @sent) "first message correct")
                 (test= {:msg 2} (second @sent) "second message correct")

                 (let [stats (q/queue-stats queue)]
                   (test= 2 (get stats :enqueued) "stats: 2 enqueued")
                   (test= 2 (get stats :sent) "stats: 2 sent")
                   (test= 0 (get stats :depth) "stats: depth is 0"))

                 (q/stop! queue)
                 (println))))))

;; ============================================================================
;; Test 2: Backpressure when full
;; ============================================================================

(defn test-backpressure []
  (println "Test 2: Backpressure when queue full")
  (let [sent (atom [])
        queue (q/make-send-queue {:on-send #(swap! sent conj %)
                                   :max-depth 3
                                   :flush-interval-ms 10000})]

    ;; Fill the queue
    (test= :ok (q/enqueue! queue {:msg 1}) "enqueue 1 ok")
    (test= :ok (q/enqueue! queue {:msg 2}) "enqueue 2 ok")
    (test= :ok (q/enqueue! queue {:msg 3}) "enqueue 3 ok")

    ;; Queue is full
    (test= :rejected (q/enqueue! queue {:msg 4}) "enqueue 4 rejected (full)")
    (test= :rejected (q/enqueue! queue {:msg 5}) "enqueue 5 rejected (full)")

    (let [stats (q/queue-stats queue)]
      (test= 3 (get stats :enqueued) "stats: 3 enqueued")
      (test= 2 (get stats :dropped) "stats: 2 dropped")
      (test= 3 (get stats :depth) "stats: depth is 3"))

    ;; Start and drain
    (q/start! queue)

    (-> (sleep 50)
        (.then (fn []
                 (test= :ok (q/enqueue! queue {:msg 6}) "enqueue 6 ok (after drain)")
                 (q/stop! queue)
                 (println))))))

;; ============================================================================
;; Test 3: Graceful shutdown drains queue
;; ============================================================================

(defn test-shutdown []
  (println "Test 3: Graceful shutdown drains queue")
  (let [sent (atom [])
        queue (q/make-send-queue {:on-send #(swap! sent conj %)
                                   :max-depth 100
                                   :flush-interval-ms 10000})]

    (q/enqueue! queue {:msg "a"})
    (q/enqueue! queue {:msg "b"})
    (q/enqueue! queue {:msg "c"})

    (test= 0 (count @sent) "no messages sent before start")

    (q/start! queue)

    ;; Stop immediately - should drain
    (let [final-stats (q/stop! queue)]
      (test= 3 (count @sent) "all 3 messages sent on stop")
      (test= 3 (get final-stats :sent) "final stats: 3 sent")
      (test= 0 (get final-stats :depth) "final stats: depth 0"))

    (println)
    (js/Promise.resolve nil)))

;; ============================================================================
;; Test 4: High throughput
;; ============================================================================

(defn test-throughput []
  (println "Test 4: High throughput")
  (let [sent (atom 0)
        queue (q/make-send-queue {:on-send (fn [_] (swap! sent inc))
                                   :max-depth 10000
                                   :flush-interval-ms 1})]

    (q/start! queue)

    (let [n 1000
          start (js/Date.now)]
      (dotimes [i n]
        (q/enqueue! queue {:i i}))
      (let [enqueue-time (- (js/Date.now) start)]
        (test-true (< enqueue-time 500) (str "enqueue " n " msgs in " enqueue-time "ms (< 500ms)"))))

    (-> (sleep 100)
        (.then (fn []
                 (let [stats (q/stop! queue)]
                   (test= 1000 (get stats :sent) "all 1000 messages sent")
                   (test= 0 (get stats :dropped) "no messages dropped"))
                 (println))))))

;; ============================================================================
;; Run all tests
;; ============================================================================

(defn print-summary []
  (println "=== Summary ===")
  (let [passed (get @test-results :passed)
        failed (get @test-results :failed)]
    (println (str "Passed: " passed))
    (println (str "Failed: " failed))
    (println)
    (if (zero? failed)
      (println "✅ All tests passed!")
      (println "❌ Some tests failed!"))))

(-> (test-basic)
    (.then test-backpressure)
    (.then test-shutdown)
    (.then test-throughput)
    (.then print-summary)
    (.catch (fn [e] (println "Error:" e))))
