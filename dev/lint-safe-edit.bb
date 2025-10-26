#!/usr/bin/env bb

;; MANDATORY LINT-SAFE EDIT WRAPPER
;; This script MUST be used for ALL Clojure file edits
;; It prevents any edit that would introduce linting errors

(require '[babashka.process :as p]
         '[clojure.string :as str])

(defn lint-check [file-path]
  "Returns {:errors n :warnings n :clean? bool}"
  (let [result (p/shell {:out :string :err :string :continue true}
                        "clj-kondo" "--lint" file-path)
        output (:err result)
        last-line (last (str/split-lines output))
        errors (if-let [match (re-find #"errors: (\d+)" last-line)]
                (Integer/parseInt (second match))
                0)
        warnings (if-let [match (re-find #"warnings: (\d+)" last-line)]
                  (Integer/parseInt (second match))
                  0)]
    {:errors errors
     :warnings warnings
     :clean? (zero? errors)
     :output output}))

(defn safe-edit-file!
  "Edit file with immediate lint verification and automatic reversion"
  [file-path edit-fn]
  (println (format "🔒 LINT-SAFE EDIT: %s" file-path))

  ;; Check current state
  (let [before-lint (lint-check file-path)]
    (println (format "📊 Before: %d errors, %d warnings"
                     (:errors before-lint)
                     (:warnings before-lint)))

    ;; Save original content
    (let [original-content (slurp file-path)]
      (try
        ;; Apply the edit
        (edit-fn file-path)

        ;; Check new state
        (let [after-lint (lint-check file-path)]
          (println (format "📊 After: %d errors, %d warnings"
                          (:errors after-lint)
                          (:warnings after-lint)))

          ;; Enforce: NO NEW ERRORS
          (cond
            ;; New errors introduced - REVERT
            (> (:errors after-lint) (:errors before-lint))
            (do
              (println "❌ EDIT REJECTED: New errors introduced!")
              (println "🔄 Reverting file...")
              (spit file-path original-content)
              (println (:output after-lint))
              (System/exit 1))

            ;; Errors increased - REVERT
            (and (pos? (:errors after-lint))
                 (not (pos? (:errors before-lint))))
            (do
              (println "❌ EDIT REJECTED: File was clean, now has errors!")
              (println "🔄 Reverting file...")
              (spit file-path original-content)
              (System/exit 1))

            ;; Success - but warn about existing issues
            :else
            (do
              (when (pos? (:errors after-lint))
                (println (format "⚠️  Warning: File still has %d pre-existing errors"
                                (:errors after-lint))))
              (println "✅ Edit accepted - no new linting errors introduced")
              true)))

        (catch Exception e
          (println (format "❌ Error during edit: %s" (.getMessage e)))
          (println "🔄 Reverting file...")
          (spit file-path original-content)
          (System/exit 1))))))

;; CLI usage
(when (= *file* (System/getProperty "babashka.file"))
  (if (< (count *command-line-args*) 1)
    (do
      (println "Usage: ./lint-safe-edit.bb <file-path>")
      (println "Then modify the file, and this script will verify it")
      (System/exit 1))
    (let [file-path (first *command-line-args*)]
      (println "Edit the file and press Enter when done...")
      (read-line)
      (let [result (lint-check file-path)]
        (if (:clean? result)
          (println "✅ File is lint-clean!")
          (do
            (println "❌ File has linting errors:")
            (println (:output result))
            (System/exit 1)))))))