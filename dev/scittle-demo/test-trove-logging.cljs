;; Test file for Trove logging in browser
;; This file tests the new sente-lite.logging interface

(ns test-trove-logging
  "Test suite for Trove logging in browser"
  (:require [sente-lite.logging :as log]))

;; Test 1: Basic logging at different levels
(println "🧪 Test 1: Basic logging at different levels")

(log/trace :test/trace-level {:message "Trace level test"})
(log/debug :test/debug-level {:message "Debug level test"})
(log/info :test/info-level {:message "Info level test"})
(log/warn :test/warn-level {:message "Warn level test"})
(log/error :test/error-level {:message "Error level test"})
(log/fatal :test/fatal-level {:message "Fatal level test"})

;; Test 2: Logging with complex data
(println "🧪 Test 2: Logging with complex data")

(log/debug :test/complex-data
  {:user {:id 123
          :name "Test User"
          :email "test@example.com"}
   :timestamp (js/Date.now)
   :nested {:level1 {:level2 {:level3 "deep value"}}}})

;; Test 3: Error logging with exception
(println "🧪 Test 3: Error logging with exception")

(try
  (throw (js/Error. "Test error for logging"))
  (catch js/Error e
    (log/error :test/error-with-exception
      {:error e
       :message (.-message e)
       :stack (.-stack e)})))

;; Test 4: Logging in a loop
(println "🧪 Test 4: Logging in a loop")

(doseq [i (range 3)]
  (log/debug :test/loop-iteration
    {:iteration i
     :timestamp (js/Date.now)}))

;; Test 5: Conditional logging
(println "🧪 Test 5: Conditional logging")

(let [debug-enabled? true]
  (when debug-enabled?
    (log/debug :test/conditional-log
      {:condition "debug-enabled?"
       :value debug-enabled?})))

;; Test 6: Logging with nil and empty values
(println "🧪 Test 6: Logging with nil and empty values")

(log/info :test/nil-values
  {:nil-value nil
   :empty-string ""
   :empty-array []
   :empty-object {}
   :zero 0
   :false false})

;; Test 7: WebSocket event logging (simulated)
(println "🧪 Test 7: WebSocket event logging")

(log/info :ws/connected
  {:url "ws://localhost:3000/ws"
   :protocol "websocket"
   :ready-state 1})

(log/debug :ws/message-received
  {:type :chat/message
   :from "user123"
   :content "Hello, world!"
   :timestamp (js/Date.now)})

(log/warn :ws/reconnecting
  {:attempt 1
   :delay-ms 1000
   :reason "connection-lost"})

;; Test 8: Performance logging
(println "🧪 Test 8: Performance logging")

(let [start-time (js/Date.now)
      _ (js/setTimeout #() 100)
      end-time (js/Date.now)]
  (log/debug :perf/operation-completed
    {:operation "simulated-task"
     :duration-ms (- end-time start-time)
     :timestamp end-time}))

;; Test 9: Batch logging
(println "🧪 Test 9: Batch logging")

(doseq [event [{:id :event/user-login :user-id 1}
               {:id :event/page-view :page "/home"}
               {:id :event/button-click :button-id "submit"}]]
  (log/info (:id event) (dissoc event :id)))

;; Test 10: Summary
(println "\n✅ All Trove logging tests completed!")
(println "Check the browser console for log output.")
