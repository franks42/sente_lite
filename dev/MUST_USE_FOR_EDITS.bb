#!/usr/bin/env bb
;; MANDATORY SCRIPT FOR ALL FILE EDITS
;; This script MUST be used for any Clojure file modifications
;; It enforces immediate linting after EVERY change

(require '[babashka.process :as p]
         '[babashka.fs :as fs])

(defn lint-file [file-path]
  "Run kondo on a single file and return true if clean"
  (let [result (p/shell {:out :string :err :string :continue true}
                        "clj-kondo" "--lint" file-path)]
    (when (not= 0 (:exit result))
      (println "❌ Linting errors in" file-path ":")
      (println (:err result)))
    (= 0 (:exit result))))

(defn edit-with-lint [file-path edit-fn]
  "Edit a file and immediately lint it. Revert if linting fails."
  (println "📝 Editing:" file-path)

  ;; Backup original content
  (let [original-content (slurp file-path)]
    (try
      ;; Apply the edit
      (edit-fn file-path)

      ;; Immediately lint
      (println "🔍 Linting" file-path "...")
      (if (lint-file file-path)
        (do
          (println "✅ Edit successful and lint-clean!")
          true)
        (do
          (println "❌ Linting failed! Reverting changes...")
          (spit file-path original-content)
          (throw (ex-info "Edit rejected due to linting errors"
                          {:file file-path}))))

      (catch Exception e
        (println "❌ Error during edit:" (.getMessage e))
        (spit file-path original-content)
        false))))

;; Export for use in REPL or other scripts
(def edit-with-lint edit-with-lint)

(println "=== LINT-ENFORCED EDIT SYSTEM READY ===")
(println "Use: (edit-with-lint \"path/to/file.clj\" your-edit-fn)")
(println "ALL edits MUST pass linting or they will be REVERTED!")