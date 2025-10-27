#!/usr/bin/env bb

;;
;; Multi-Process Test Utilities
;;
;; Provides coordination mechanisms for distributed testing:
;; - Port discovery (server → clients)
;; - Process synchronization (ready signals)
;; - Result aggregation (JSON files)
;; - Cleanup (PID tracking, shutdown)
;;

(ns mp-utils
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [babashka.fs :as fs]
            [babashka.process]))

;;
;; Port Discovery
;;

(defn port-file-path
  "Get path to port discovery file for a test run"
  [test-id]
  (str "/tmp/sente-lite-port-" test-id ".txt"))

(defn write-port!
  "Server writes its actual port to discovery file"
  [test-id port]
  (let [path (port-file-path test-id)]
    (spit path (str port))
    path))

(defn read-port
  "Client reads server port from discovery file (with timeout)"
  [test-id timeout-ms]
  (let [path (port-file-path test-id)
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (.exists (io/file path))
        (Integer/parseInt (slurp path))
        (if (< (System/currentTimeMillis) deadline)
          (do
            (Thread/sleep 100)
            (recur))
          (throw (ex-info "Timeout waiting for port file"
                          {:test-id test-id :timeout-ms timeout-ms})))))))

(defn cleanup-port-file!
  "Remove port discovery file"
  [test-id]
  (let [path (port-file-path test-id)]
    (when (.exists (io/file path))
      (fs/delete path))))

;;
;; Process Synchronization
;;

(defn ready-file-path
  "Get path to ready signal file for a process"
  [test-id process-id]
  (str "/tmp/sente-lite-ready-" test-id "-" process-id ".txt"))

(defn signal-ready!
  "Process signals it's ready"
  [test-id process-id]
  (let [path (ready-file-path test-id process-id)]
    (spit path "ready")
    path))

(defn wait-for-ready
  "Wait for a process to signal ready (with timeout)"
  [test-id process-id timeout-ms]
  (let [path (ready-file-path test-id process-id)
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (.exists (io/file path))
        true
        (if (< (System/currentTimeMillis) deadline)
          (do
            (Thread/sleep 100)
            (recur))
          (throw (ex-info "Timeout waiting for ready signal"
                          {:test-id test-id
                           :process-id process-id
                           :timeout-ms timeout-ms})))))))

(defn wait-for-all-ready
  "Wait for multiple processes to signal ready"
  [test-id process-ids timeout-ms]
  (doseq [pid process-ids]
    (wait-for-ready test-id pid timeout-ms)))

(defn cleanup-ready-file!
  "Remove ready signal file"
  [test-id process-id]
  (let [path (ready-file-path test-id process-id)]
    (when (.exists (io/file path))
      (fs/delete path))))

;;
;; Result Aggregation
;;

(defn result-file-path
  "Get path to result file for a process"
  [test-id process-id]
  (str "/tmp/sente-lite-result-" test-id "-" process-id ".json"))

(defn write-result!
  "Process writes its test result"
  [test-id process-id result]
  (let [path (result-file-path test-id process-id)
        result-with-meta (assoc result
                                :process-id process-id
                                :timestamp (System/currentTimeMillis))]
    (spit path (json/generate-string result-with-meta))
    path))

(defn read-result
  "Read result from a process (with timeout)"
  [test-id process-id timeout-ms]
  (let [path (result-file-path test-id process-id)
        deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (.exists (io/file path))
        (json/parse-string (slurp path) true)
        (if (< (System/currentTimeMillis) deadline)
          (do
            (Thread/sleep 100)
            (recur))
          (throw (ex-info "Timeout waiting for result file"
                          {:test-id test-id
                           :process-id process-id
                           :timeout-ms timeout-ms})))))))

(defn read-all-results
  "Read results from multiple processes"
  [test-id process-ids timeout-ms]
  (mapv #(read-result test-id % timeout-ms) process-ids))

(defn cleanup-result-file!
  "Remove result file"
  [test-id process-id]
  (let [path (result-file-path test-id process-id)]
    (when (.exists (io/file path))
      (fs/delete path))))

;;
;; Full Cleanup
;;

(defn cleanup-test-files!
  "Remove all test coordination files for a test run"
  [test-id process-ids]
  (cleanup-port-file! test-id)
  (doseq [pid process-ids]
    (cleanup-ready-file! test-id pid)
    (cleanup-result-file! test-id pid)))

;;
;; PID Tracking
;;

(defn pid-file-path
  "Get path to PID tracking file"
  [test-id]
  (str "/tmp/sente-lite-pids-" test-id ".txt"))

(defn write-pid!
  "Add a PID to the tracking file"
  [test-id pid]
  (let [path (pid-file-path test-id)]
    (spit path (str pid "\n") :append true)))

(defn read-pids
  "Read all PIDs for a test run"
  [test-id]
  (let [path (pid-file-path test-id)]
    (when (.exists (io/file path))
      (->> (slurp path)
           (clojure.string/split-lines)
           (filter seq)
           (mapv #(Long/parseLong %))))))

(defn cleanup-pid-file!
  "Remove PID tracking file"
  [test-id]
  (let [path (pid-file-path test-id)]
    (when (.exists (io/file path))
      (fs/delete path))))

(defn kill-pids!
  "Kill all processes in the PID tracking file"
  [test-id]
  (when-let [pids (read-pids test-id)]
    (doseq [pid pids]
      (try
        ;; Use shell command to kill - works cross-platform
        (babashka.process/shell {:continue true} "kill" (str pid))
        (catch Exception e
          ;; Process may already be dead
          nil))))
  (cleanup-pid-file! test-id))

;;
;; Test Result Validation
;;

(defn all-passed?
  "Check if all results indicate test passed"
  [results]
  (every? #(and (= :passed (:status %))
                (zero? (:failures % 0))) results))

(defn aggregate-results
  "Aggregate multiple test results into summary"
  [results]
  {:total (count results)
   :passed (count (filter #(= :passed (:status %)) results))
   :failed (count (filter #(= :failed (:status %)) results))
   :errors (reduce + (map #(get % :failures 0) results))
   :all-passed (all-passed? results)
   :results results})
