#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[telemere-lite.core :as tel]
         '[clojure.java.io :as io])

(println "=== Testing Event! Macro and ID-based Filtering ===")

;; Initialize telemetry
(tel/startup!)

;; Clean up test files
(doseq [file ["test-events.log"]]
  (when (.exists (io/file file))
    (io/delete-file file)))

(println "\n1. Testing event! macro variations:")
(tel/add-file-handler! :test-events "test-events.log")

;; Test different event! signatures
(tel/event! ::user-login)
(tel/event! ::user-login {:user-id 123 :ip "192.168.1.1"})
(tel/event! :debug ::ws-ping {:conn-id "abc123" :timestamp (System/currentTimeMillis)})

(println "✅ Event! macro calls completed")

(Thread/sleep 100) ; Give it a moment

(println "\n2. Testing event output format:")
(if (.exists (io/file "test-events.log"))
  (do
    (println "Events logged:")
    (doseq [line (clojure.string/split-lines (slurp "test-events.log"))]
      (when (clojure.string/includes? line "event-id")
        (println "  📡" (subs line 0 (min 100 (count line))) "..."))))
  (println "❌ Events file was not created"))

(println "\n3. Testing event-id filtering:")
(println "Current filters:" (tel/get-filters))

;; Test allowing only specific event IDs
(tel/set-id-filter! {:allow #{":user/login" ":user/logout"}})
(println "Set filter to allow only :user/login and :user/logout")

;; These should be filtered out
(tel/event! ::ws-ping {:should-be "filtered"})
(tel/event! ::database-query {:should-be "filtered"})

;; These should pass through
(tel/event! :user/login {:should-be "allowed"})
(tel/event! :user/logout {:should-be "allowed"})

(Thread/sleep 100)

(println "\n4. Testing filter effectiveness:")
(let [recent-lines (when (.exists (io/file "test-events.log"))
                     (clojure.string/split-lines (slurp "test-events.log")))]
  (if recent-lines
    (let [filtered-events (filter #(clojure.string/includes? % "should-be") recent-lines)]
      (println "Events after filtering:")
      (doseq [event filtered-events]
        (cond
          (clojure.string/includes? event "allowed")
          (println "  ✅ ALLOWED:" (subs event 0 (min 80 (count event))))

          (clojure.string/includes? event "filtered")
          (println "  ❌ SHOULD BE FILTERED:" (subs event 0 (min 80 (count event))))

          :else
          (println "  ❓ UNKNOWN:" (subs event 0 (min 80 (count event))))))

      (let [allowed-count (count (filter #(clojure.string/includes? % "allowed") filtered-events))
            filtered-count (count (filter #(clojure.string/includes? % "filtered") filtered-events))]
        (if (and (> allowed-count 0) (= filtered-count 0))
          (println "✅ ID filtering works correctly!")
          (println "❌ ID filtering may not be working as expected"))))
    (println "❌ Could not read events file")))

(println "\n5. Testing mixed event and log calls:")
(tel/clear-filters!) ; Reset filters
(tel/add-stdout-handler! :test-stdout)

(println "Sending mixed signals...")
(tel/event! ::sente-message-sent {:type :ping :conn-id "conn-1"})
(tel/log! :info "Regular log message" {:context "mixed-test"})
(tel/event! ::sente-message-received {:type :pong :conn-id "conn-1"})
(tel/error! "Test error" {:context "mixed-test"})

(println "✅ Mixed signals sent")

;; Clean up
(tel/remove-handler! :test-events)
(tel/remove-handler! :test-stdout)
(when (.exists (io/file "test-events.log"))
  (io/delete-file "test-events.log"))

(println "\n✅ Event correlation tests completed!")
(println "Event! macro ready for WebSocket correlation in sente-lite")