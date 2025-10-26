#!/usr/bin/env bb

;; MANDATORY VERIFICATION AFTER EVERY EDIT
;; This script MUST be run immediately after ANY change to Clojure files

(require '[babashka.process :as p]
         '[clojure.string :as str])

(defn run-verification [file-path]
  "Run complete verification: kondo + cljfmt"
  (println (format "🔍 VERIFYING: %s" file-path))

  ;; Step 1: clj-kondo check
  (println "📝 Running clj-kondo...")
  (let [kondo-result (p/shell {:out :string :err :string :continue true}
                              "clj-kondo" "--lint" file-path)
        kondo-output (:err kondo-result)
        kondo-lines (str/split-lines kondo-output)
        summary-line (last kondo-lines)

        errors (if-let [match (re-find #"errors: (\d+)" summary-line)]
                (Integer/parseInt (second match)) 0)
        warnings (if-let [match (re-find #"warnings: (\d+)" summary-line)]
                  (Integer/parseInt (second match)) 0)]

    (println (format "   Errors: %d, Warnings: %d" errors warnings))

    (when (pos? errors)
      (println "❌ LINTING ERRORS FOUND:")
      (println kondo-output)
      (println "\n🚨 FILE MUST BE FIXED BEFORE PROCEEDING!")
      (System/exit 1))

    (when (pos? warnings)
      (println "⚠️  WARNINGS FOUND:")
      (println kondo-output)
      (println "   Consider fixing warnings for clean code"))

    ;; Step 2: cljfmt check
    (println "📐 Running cljfmt check...")
    (let [cljfmt-result (p/shell {:out :string :err :string :continue true}
                                 "clojure" "-Sdeps"
                                 "{:deps {dev.weavejester/cljfmt {:mvn/version \"0.15.3\"}}}"
                                 "-M" "-m" "cljfmt.main" "check" file-path)]

      (if (= 0 (:exit cljfmt-result))
        (println "   ✅ Formatting OK")
        (do
          (println "❌ FORMATTING ISSUES FOUND:")
          (println (:out cljfmt-result))
          (println (:err cljfmt-result))
          (println "\n🚨 RUN: clojure -M:dev -m cljfmt.main fix " file-path)
          (System/exit 1))))

    (if (zero? errors)
      (println "✅ VERIFICATION PASSED - File is ready!")
      (println "⚠️  File has pre-existing warnings but no errors"))))

;; Usage
(when (= *file* (System/getProperty "babashka.file"))
  (if (empty? *command-line-args*)
    (do
      (println "Usage: ./VERIFY_AFTER_EDIT.bb <file-path>")
      (println "MANDATORY: Run this after EVERY edit!")
      (System/exit 1))
    (let [file-path (first *command-line-args*)]
      (run-verification file-path))))