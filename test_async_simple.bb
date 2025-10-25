#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[telemere-lite.core :as tel]
         '[clojure.java.io :as io])

(println "=== Simple Async Implementation Test ===")

;; Initialize telemetry
(tel/startup!)

;; Clean up test files
(doseq [file ["sync-test.log" "async-test.log"]]
  (when (.exists (io/file file))
    (io/delete-file file)))

(println "\n1. Testing sync handler:")
(tel/clear-handlers!)
(tel/add-file-handler! :sync-test "sync-test.log" {:sync true})

(let [start (System/currentTimeMillis)]
  (dotimes [i 100]
    (tel/event! ::sync-test {:iteration i}))
  (let [duration (- (System/currentTimeMillis) start)]
    (println (format "Sync: 100 signals in %d ms (%.2f ms/signal)" duration (/ duration 100.0)))))

(Thread/sleep 100) ; Allow completion

(println "\n2. Testing async handler:")
(tel/clear-handlers!)
(tel/add-file-handler! :async-test "async-test.log"
                      {:async {:mode :dropping :buffer-size 1024 :n-threads 1}})

(let [start (System/currentTimeMillis)]
  (dotimes [i 100]
    (tel/event! ::async-test {:iteration i}))
  (let [duration (- (System/currentTimeMillis) start)]
    (println (format "Async: 100 signals in %d ms (%.2f ms/signal)" duration (/ duration 100.0)))))

(Thread/sleep 100) ; Allow async processing

(println "\n3. Verifying file outputs:")
(doseq [file ["sync-test.log" "async-test.log"]]
  (if (.exists (io/file file))
    (let [lines (clojure.string/split-lines (slurp file))
          count (count lines)]
      (println (format "%s: %d lines written" file count)))
    (println (format "%s: File not created" file))))

(println "\n4. Testing handler stats:")
(let [stats (tel/get-handler-stats)]
  (println "Handler statistics:")
  (doseq [[id stat] stats]
    (println (format "  %s: mode=%s, processed=%d, queued=%d, dropped=%d"
                     id (:mode stat) (:processed stat) (:queued stat) (:dropped stat)))))

(println "\n5. Testing health check:")
(let [health (tel/get-handler-health)]
  (println "System health:" health))

(println "\n6. Testing graceful shutdown:")
(println "Handlers before shutdown:" (count (tel/get-handlers)))
(tel/shutdown-telemetry!)
(println "Handlers after shutdown:" (count (tel/get-handlers)))

(println "\n7. Testing production defaults:")
(tel/clear-handlers!)
(tel/add-file-handler! "async-test.log")  ; Should default to async

(let [handlers (tel/get-handlers)]
  (doseq [[id {:keys [opts]}] handlers]
    (println (format "Handler %s: async=%s" id (boolean (:async opts))))))

;; Clean up
(doseq [file ["sync-test.log" "async-test.log"]]
  (when (.exists (io/file file))
    (io/delete-file file)))

(println "\n✅ Simple async test completed!")