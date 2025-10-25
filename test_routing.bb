#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[telemere-lite.core :as tel]
         '[clojure.java.io :as io])

(println "=== Testing Basic Routing Options ===")

;; Initialize telemetry
(tel/startup!)

;; Clean up test files
(doseq [file ["test-file.log" "test-file2.log"]]
  (when (.exists (io/file file))
    (io/delete-file file)))

(println "\n1. Testing handler management:")
(println "Initial handlers:" (tel/get-handlers))

;; Add handlers
(tel/add-file-handler! :test-file "test-file.log")
(tel/add-stdout-handler! :test-stdout)
(tel/add-stderr-handler! :test-stderr)

(println "After adding handlers:" (count (tel/get-handlers)))

(println "\n2. Testing file output:")
(tel/log! :info "File test message" {:test-data "file-routing"})

;; Check if file was created
(Thread/sleep 100) ; Give it a moment
(if (.exists (io/file "test-file.log"))
  (do
    (println "✅ File created successfully")
    (println "File content:" (slurp "test-file.log")))
  (println "❌ File was not created"))

(println "\n3. Testing multiple file handlers:")
(tel/add-file-handler! :test-file2 "test-file2.log")
(tel/log! :warn "Multi-file test" {:destinations ["file1" "file2"]})

(Thread/sleep 100)
(doseq [file ["test-file.log" "test-file2.log"]]
  (if (.exists (io/file file))
    (let [lines (clojure.string/split-lines (slurp file))
          recent-line (last lines)]
      (if (clojure.string/includes? recent-line "Multi-file test")
        (println "✅" file "received the message")
        (println "❌" file "did not receive the message")))
    (println "❌" file "does not exist")))

(println "\n4. Testing handler removal:")
(tel/remove-handler! :test-file)
(tel/log! :error "After removal test")

(Thread/sleep 100)
(let [file1-lines (when (.exists (io/file "test-file.log"))
                    (clojure.string/split-lines (slurp "test-file.log")))
      file2-lines (when (.exists (io/file "test-file2.log"))
                    (clojure.string/split-lines (slurp "test-file2.log")))]
  (if (and file1-lines (not (clojure.string/includes? (last file1-lines) "After removal test")))
    (println "✅ Handler removal worked - test-file.log didn't get new message")
    (println "❌ Handler removal failed - test-file.log got new message"))

  (if (and file2-lines (clojure.string/includes? (last file2-lines) "After removal test"))
    (println "✅ Other handlers still work - test-file2.log got new message")
    (println "❌ Other handlers broken - test-file2.log didn't get new message")))

(println "\n5. Testing handler clearing:")
(tel/clear-handlers!)
(println "Handlers after clear:" (count (tel/get-handlers)))

(tel/log! :info "After clear test")
(println "✅ Logging still works (should only go to Timbre)")

;; Clean up
(doseq [file ["test-file.log" "test-file2.log"]]
  (when (.exists (io/file file))
    (io/delete-file file)))

(println "\n✅ Routing tests completed!")
(println "Check logs/telemetry.jsonl for Timbre output")