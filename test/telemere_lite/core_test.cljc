(ns telemere-lite.core-test
  "Tests for telemere-lite core functionality"
  (:require [telemere-lite.core :as tel]
            #?(:bb [clojure.test :refer [deftest testing is use-fixtures]]
               :clj [clojure.test :refer [deftest testing is use-fixtures]])
            #?(:bb [clojure.java.io :as io]
               :clj [clojure.java.io :as io])
            #?(:bb [cheshire.core :as json]
               :clj [cheshire.core :as json])))

(def test-log-file "test-telemetry.jsonl")

(defn setup-test-logging []
  "Setup test environment"
  (alter-var-root #'tel/*output-file* (constantly test-log-file))
  (alter-var-root #'tel/*telemetry-enabled* (constantly true))
  #?(:bb (tel/ensure-log-dir!))
  ;; Clear test log file
  (when (.exists (io/file test-log-file))
    (io/delete-file test-log-file)))

(defn teardown-test-logging []
  "Clean up test environment"
  (when (.exists (io/file test-log-file))
    (io/delete-file test-log-file)))

(use-fixtures :each
  (fn [test-fn]
    (setup-test-logging)
    (test-fn)
    (teardown-test-logging)))

(defn read-log-entries []
  "Read all log entries from test file"
  (when (.exists (io/file test-log-file))
    (->> (slurp test-log-file)
         (clojure.string/split-lines)
         (remove empty?)
         (map json/parse-string))))

(deftest test-basic-logging
  (testing "Basic log functionality"
    (tel/log! :info "Test message" {:test-data "value"})  ; This is the logged line

    (let [entries (read-log-entries)]
      (is (= 1 (count entries)))
      (let [entry (first entries)
            msg (get entry "msg")
            context (second msg)
            location (get context "location")]
        (is (= "info" (get entry "level")))
        (is (= "Test message" (first msg)))
        (is (= "value" (get context "test-data")))
        (is (contains? location "file"))
        (is (number? (get location "line")))
        (is (= "telemere-lite.core-test" (get location "ns")))))))

(deftest test-log-levels
  (testing "Different log levels"
    (tel/log! :debug "Debug message")
    (tel/log! :info "Info message")
    (tel/log! :warn "Warning message")
    (tel/log! :error "Error message")

    (let [entries (read-log-entries)]
      (is (= 4 (count entries)))
      (is (= ["debug" "info" "warn" "error"]
             (map #(get % "level") entries))))))

(deftest test-error-macro
  (testing "Error macro with context"
    (tel/error! "Test error" {:error-type "test" :severity "low"})

    (let [entries (read-log-entries)]
      (is (= 1 (count entries)))
      (let [entry (first entries)]
        (is (= "error" (get entry "level")))
        (let [msg (get entry "msg")
              context (second msg)]
          (is (= "test" (get context "error-type")))
          (is (= "low" (get context "severity")))
          (is (contains? context "location")))))))

(deftest test-performance-timing
  (testing "Performance timing macro"
    (tel/with-timing "test-operation"
      (Thread/sleep 10))  ; Small delay

    (let [entries (read-log-entries)]
      (is (= 1 (count entries)))
      (let [entry (first entries)
            msg (get entry "msg")
            context (second msg)]
        (is (= "info" (get entry "level")))
        (is (= "Performance metric" (first msg)))
        (is (= "test-operation" (get context "operation")))
        (is (>= (get context "duration-ms") 10))
        (is (contains? context "location"))))))

(deftest test-source-location-capture
  (testing "Source location metadata is captured correctly"
    (tel/log! :info "Location test")

    (let [entries (read-log-entries)]
      (is (= 1 (count entries)))
      (let [entry (first entries)
            msg (get entry "msg")
            location (get (second msg) "location")]
        (is (contains? location "file"))
        (is (and (number? (get location "line"))
                 (> (get location "line") 100)))  ; Should be somewhere in this test
        (is (= "telemere-lite.core-test" (get location "ns")))))))

(deftest test-telemetry-disable
  (testing "Telemetry can be disabled"
    (alter-var-root #'tel/*telemetry-enabled* (constantly false))
    (tel/log! :info "Should not appear")

    (let [entries (read-log-entries)]
      (is (= 0 (count entries))))

    ;; Re-enable for other tests
    (alter-var-root #'tel/*telemetry-enabled* (constantly true))))

(deftest test-module-tracking
  (testing "Module loading tracking"
    (tel/module-load! "test-module")
    (tel/module-loaded! "test-module" 150)

    (let [entries (read-log-entries)]
      (is (= 2 (count entries)))
      (let [load-entry (first entries)
            loaded-entry (second entries)]
        ;; Check module loading entry
        (is (= "info" (get load-entry "level")))
        (is (= "Module loading" (first (get load-entry "msg"))))

        ;; Check module loaded entry
        (is (= "info" (get loaded-entry "level")))
        (is (= "Module loaded" (first (get loaded-entry "msg"))))
        (is (= 150 (get (second (get loaded-entry "msg")) "duration-ms")))))))

(deftest test-json-output-structure
  (testing "JSON output has correct structure"
    (tel/log! :info "Structure test" {:key "value"})

    (let [entries (read-log-entries)]
      (is (= 1 (count entries)))
      (let [entry (first entries)]
        ;; Check required fields
        (is (contains? entry "timestamp"))
        (is (contains? entry "level"))
        (is (contains? entry "ns"))
        (is (contains? entry "msg"))
        (is (contains? entry "context"))

        ;; Check msg structure [message, context]
        (let [msg (get entry "msg")]
          (is (vector? msg))
          (is (= 2 (count msg)))
          (is (string? (first msg)))
          (is (map? (second msg))))))))

(defn run-tests []
  "Run all tests and return summary"
  #?(:bb (clojure.test/run-tests 'telemere-lite.core-test)
     :clj (clojure.test/run-tests 'telemere-lite.core-test)))