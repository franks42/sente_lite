#!/usr/bin/env bb
;;
;; Receive Queue Unit Tests for Babashka
;;
;; Tests the recv_queue implementation with simulated time.
;; All timing-dependent tests use controlled time advancement.
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[sente-lite.recv-queue :as rq]
         '[sente-lite.test-helpers :as th])

(println "=== sente-lite Receive Queue Unit Tests (BB) ===\n")

(def passed (atom 0))
(def failed (atom 0))

(defn test-pass [name detail]
  (swap! passed inc)
  (println (str "  \u2713 " name))
  (when detail (println (str "    " detail))))

(defn test-fail [name expected actual]
  (swap! failed inc)
  (println (str "  \u2717 " name))
  (println (str "    expected: " expected))
  (println (str "    actual:   " actual)))

(defn check [name expected actual]
  (if (= expected actual)
    (test-pass name nil)
    (test-fail name expected actual)))

(defn check-pred [name pred actual detail]
  (if (pred actual)
    (test-pass name detail)
    (test-fail name (str "predicate to pass for: " actual) actual)))

;; ============================================================================
;; Test 1: Basic put/take matching
;; ============================================================================

(println "Test 1: Basic put/take matching")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        result (atom nil)
        cancel (rq/take! queue {:pred (fn [msg] (= (get msg :id) "123"))
                                :callback #(reset! result %)})]
    ;; Waiter registered
    (check-pred "waiter registered" some? cancel "cancel fn returned")
    (check "no result yet" nil @result)

    ;; Put matching message
    (rq/put! queue {:id "123" :data "hello"})
    (check "result received" {:id "123" :data "hello"} @result)

    ;; Stats
    (let [s (rq/stats queue)]
      (check "stats: received=1" 1 (get s :received))
      (check "stats: matched=1" 1 (get s :matched))
      (check "stats: waiter-count=0" 0 (get s :waiter-count)))))

;; ============================================================================
;; Test 2: Waiter timeout
;; ============================================================================

(println "\nTest 2: Waiter timeout")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        result (atom nil)
        _cancel (rq/take! queue {:pred (fn [_] true)
                                 :timeout-ms 5000
                                 :callback #(reset! result %)})]
    ;; No result yet
    (check "no result at t=0" nil @result)

    ;; Advance time but not past timeout
    (th/advance-time! 4999)
    (check "no result at t=4999" nil @result)

    ;; Advance past timeout
    (th/advance-time! 2)
    (check "timeout at t=5001" {:error :timeout} @result)

    ;; Stats
    (let [s (rq/stats queue)]
      (check "stats: timeouts=1" 1 (get s :timeouts)))))

;; ============================================================================
;; Test 3: Cancel waiter before timeout
;; ============================================================================

(println "\nTest 3: Cancel waiter before timeout")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        result (atom nil)
        cancel (rq/take! queue {:pred (fn [_] true)
                                :timeout-ms 5000
                                :callback #(reset! result %)})]
    ;; Cancel the waiter
    (cancel)

    ;; Advance past timeout
    (th/advance-time! 6000)

    ;; Should NOT have received timeout (cancelled)
    (check "no result after cancel" nil @result)

    ;; Stats
    (let [s (rq/stats queue)]
      (check "stats: waiter-count=0" 0 (get s :waiter-count)))))

;; ============================================================================
;; Test 4: Multiple waiters with different predicates
;; ============================================================================

(println "\nTest 4: Multiple waiters with different predicates")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        result-a (atom nil)
        result-b (atom nil)
        result-c (atom nil)]
    ;; Register three waiters with different predicates
    (rq/take! queue {:pred #(= (get % :type) "A")
                     :callback #(reset! result-a %)})
    (rq/take! queue {:pred #(= (get % :type) "B")
                     :callback #(reset! result-b %)})
    (rq/take! queue {:pred #(= (get % :type) "C")
                     :callback #(reset! result-c %)})

    ;; Put type B message
    (rq/put! queue {:type "B" :data "bee"})
    (check "B received" {:type "B" :data "bee"} @result-b)
    (check "A not received" nil @result-a)
    (check "C not received" nil @result-c)

    ;; Put type A message
    (rq/put! queue {:type "A" :data "ayy"})
    (check "A now received" {:type "A" :data "ayy"} @result-a)

    ;; Stats
    (let [s (rq/stats queue)]
      (check "stats: matched=2" 2 (get s :matched))
      (check "stats: waiter-count=1" 1 (get s :waiter-count)))))

;; ============================================================================
;; Test 5: Buffer unmatched messages
;; ============================================================================

(println "\nTest 5: Buffer unmatched messages")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {:max-depth 3})
        result (atom nil)]
    ;; Put messages with no waiters
    (rq/put! queue {:id 1})
    (rq/put! queue {:id 2})
    (rq/put! queue {:id 3})

    ;; Stats
    (let [s (rq/stats queue)]
      (check "stats: buffered=3" 3 (get s :buffered))
      (check "stats: buffer-depth=3" 3 (get s :buffer-depth)))

    ;; Now register waiter for id=2 - should match from buffer
    (rq/take! queue {:pred #(= (get % :id) 2)
                     :callback #(reset! result %)})
    (check "matched from buffer" {:id 2} @result)

    ;; Buffer should have 2 remaining
    (let [s (rq/stats queue)]
      (check "stats: buffer-depth=2" 2 (get s :buffer-depth)))))

;; ============================================================================
;; Test 6: Buffer overflow calls on-unmatched
;; ============================================================================

(println "\nTest 6: Buffer overflow calls on-unmatched")

(th/with-simulated-time
  (let [dropped (atom [])
        queue (rq/make-recv-queue {:max-depth 2
                                   :on-unmatched #(swap! dropped conj %)})]
    ;; Fill buffer
    (rq/put! queue {:id 1})
    (rq/put! queue {:id 2})

    ;; Overflow
    (rq/put! queue {:id 3})
    (rq/put! queue {:id 4})

    (check "dropped 2 messages" [{:id 3} {:id 4}] @dropped)

    (let [s (rq/stats queue)]
      (check "stats: dropped=2" 2 (get s :dropped)))))

;; ============================================================================
;; Test 7: Close notifies all waiters
;; ============================================================================

(println "\nTest 7: Close notifies all waiters")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        results (atom [])]
    ;; Register multiple waiters
    (rq/take! queue {:pred (fn [_] true)
                     :callback #(swap! results conj [:a %])})
    (rq/take! queue {:pred (fn [_] true)
                     :callback #(swap! results conj [:b %])})
    (rq/take! queue {:pred (fn [_] true)
                     :callback #(swap! results conj [:c %])})

    ;; Close
    (let [close-result (rq/close! queue :disconnected)]
      (check "close returns stats" true (contains? close-result :stats))
      (check "close returns buffered" true (contains? close-result :buffered-messages)))

    ;; All waiters notified
    (check "3 waiters notified" 3 (count @results))
    (check "all got error"
           [[:a {:error :closed :reason :disconnected}]
            [:b {:error :closed :reason :disconnected}]
            [:c {:error :closed :reason :disconnected}]]
           @results)

    (let [s (rq/stats queue)]
      (check "stats: closed?=true" true (get s :closed?))
      (check "stats: waiters-notified=3" 3 (get s :waiters-notified)))))

;; ============================================================================
;; Test 8: Take from closed queue
;; ============================================================================

(println "\nTest 8: Take from closed queue")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        result (atom nil)]
    ;; Close first
    (rq/close! queue :shutdown)

    ;; Try to take - should call callback immediately with error
    (let [cancel (rq/take! queue {:pred (fn [_] true)
                                  :callback #(reset! result %)})]
      (check "take returns nil (no cancel fn)" nil cancel)
      (check "callback called with error"
             {:error :closed :reason :shutdown}
             @result))))

;; ============================================================================
;; Test 9: Put to closed queue is ignored
;; ============================================================================

(println "\nTest 9: Put to closed queue is ignored")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})]
    ;; Close
    (rq/close! queue :done)

    ;; Put - should be ignored
    (rq/put! queue {:id 1})

    (let [s (rq/stats queue)]
      (check "stats: received=0" 0 (get s :received))
      (check "stats: buffer-depth=0" 0 (get s :buffer-depth)))))

;; ============================================================================
;; Test 10: RPC helper
;; ============================================================================

(println "\nTest 10: RPC helper")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        result (atom nil)]
    ;; Use RPC helper
    (rq/take! queue (rq/rpc-waiter "req-abc" 5000 #(reset! result %)))

    ;; Send matching response
    (rq/put! queue {:request-id "req-abc" :result "success"})

    (check "rpc response received"
           {:request-id "req-abc" :result "success"}
           @result)))

;; ============================================================================
;; Test 11: RPC timeout
;; ============================================================================

(println "\nTest 11: RPC timeout")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        result (atom nil)]
    ;; Use RPC helper with short timeout
    (rq/take! queue (rq/rpc-waiter "req-xyz" 3000 #(reset! result %)))

    ;; Advance time past timeout
    (th/advance-time! 3001)

    (check "rpc timed out" {:error :timeout} @result)))

;; ============================================================================
;; Test 12: FIFO waiter matching
;; ============================================================================

(println "\nTest 12: FIFO waiter matching")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        results (atom [])]
    ;; Register 3 waiters that all match any message
    (rq/take! queue {:pred (fn [_] true)
                     :callback #(swap! results conj [:first %])})
    (rq/take! queue {:pred (fn [_] true)
                     :callback #(swap! results conj [:second %])})
    (rq/take! queue {:pred (fn [_] true)
                     :callback #(swap! results conj [:third %])})

    ;; Send 3 messages
    (rq/put! queue {:n 1})
    (rq/put! queue {:n 2})
    (rq/put! queue {:n 3})

    ;; Should be matched FIFO
    (check "FIFO matching"
           [[:first {:n 1}]
            [:second {:n 2}]
            [:third {:n 3}]]
           @results)))

;; ============================================================================
;; Test 13: Predicate exception handling
;; ============================================================================

(println "\nTest 13: Predicate exception handling")

(th/with-simulated-time
  (let [queue (rq/make-recv-queue {})
        result (atom nil)]
    ;; Register waiter with bad predicate
    (rq/take! queue {:pred (fn [msg]
                             (if (= (get msg :id) "bad")
                               (throw (ex-info "boom" {}))
                               (= (get msg :id) "good")))
                     :callback #(reset! result %)})

    ;; Put message that causes predicate exception
    (rq/put! queue {:id "bad"})
    ;; Should be buffered (predicate returned false due to exception)
    (check "bad message buffered" nil @result)

    ;; Put good message
    (rq/put! queue {:id "good"})
    (check "good message matched" {:id "good"} @result)))

;; ============================================================================
;; Summary
;; ============================================================================

(println "\n=== Summary ===")
(println (str "Passed: " @passed))
(println (str "Failed: " @failed))
(println)

(if (zero? @failed)
  (do
    (println "\u2705 All tests passed!")
    (System/exit 0))
  (do
    (println "\u274c Some tests failed!")
    (System/exit 1)))
