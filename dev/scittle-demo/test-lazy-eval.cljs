;; Browser test for lazy evaluation in telemere-lite
;; Load this via: <script src="test-lazy-eval.cljs" type="application/x-scittle"></script>

(ns test-lazy-eval
  (:require [telemere-lite.core :as tel]))

(println "\n🧪 BROWSER TEST: Lazy Evaluation")

;; Test counter
(def eval-counter (atom 0))

(defn expensive-fn []
  (swap! eval-counter inc)
  (println "  ❌ ERROR: expensive-fn was called when telemetry disabled!")
  42)

;; TEST 1: Lazy eval when DISABLED
(println "\n✅ Test 1: :let NOT evaluated when disabled")
(tel/set-enabled! false)
(reset! eval-counter 0)

;; This should NOT call expensive-fn
(tel/event! ::lazy-test [result (expensive-fn)] {:result result})

(if (zero? @eval-counter)
  (println "  ✅ PASS: expensive-fn NOT called (count:" @eval-counter ")")
  (println "  ❌ FAIL: expensive-fn WAS called (count:" @eval-counter ")"))

;; TEST 2: Lazy eval when ENABLED
(println "\n✅ Test 2: :let IS evaluated when enabled")
(tel/set-enabled! true)
(reset! eval-counter 0)

;; This SHOULD call expensive-fn
(tel/event! ::lazy-test [result (expensive-fn)] {:result result})

(if (= 1 @eval-counter)
  (println "  ✅ PASS: expensive-fn called once (count:" @eval-counter ")")
  (println "  ❌ FAIL: expensive-fn call count wrong (count:" @eval-counter ")"))

;; TEST 3: Three-sink architecture
(println "\n✅ Test 3: Three-sink architecture")
(tel/enable-atom-sink!)
(tel/clear-events!)

(tel/info! "Test message" {:test true})

(let [events (tel/get-events)]
  (if (= 1 (count events))
    (println "  ✅ PASS: Event captured in atom sink")
    (println "  ❌ FAIL: Expected 1 event, got:" (count events))))

(println "\n🎉 Browser tests complete! Check results above.")
