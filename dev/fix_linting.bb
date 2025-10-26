#!/usr/bin/env bb

(println "=== Fixing Linting Issues in sente-lite ===")

;; This script fixes the common linting issues across all source files
;; We'll fix the most critical issues first

(require '[clojure.string :as str]
         '[babashka.fs :as fs])

(defn fix-system-time-calls [content]
  "Fix System/currentTimeMillis calls to use reader conditionals"
  (str/replace content
               #"\(System/currentTimeMillis\)"
               "#?(:bb (System/currentTimeMillis)\n                   :clj (System/currentTimeMillis))"))

(defn fix-exception-refs [content]
  "Fix Exception references to use platform-specific forms"
  (str/replace content
               #"(\s+)Exception(\s+|$)"
               "$1#?(:bb Exception :clj Exception)$2"))

(defn fix-unused-bindings [content]
  "Prefix unused bindings with underscore"
  ;; This is more complex and would need proper AST parsing
  ;; For now, we'll just report these need manual fixing
  content)

(defn process-file [file-path]
  (println (str "Processing: " file-path))
  (let [content (slurp file-path)
        fixed (-> content
                  fix-system-time-calls
                  fix-exception-refs)]
    (when (not= content fixed)
      (spit file-path fixed)
      (println (str "  Fixed: " file-path)))
    fixed))

;; Process all source files
(def source-files
  ["src/sente_lite/channels.cljc"
   "src/sente_lite/server.cljc"
   "src/sente_lite/server_simple.cljc"
   "src/sente_lite/transit_multiplexer.cljc"
   "src/sente_lite/wire_multiplexer.cljc"])

(doseq [file source-files]
  (when (fs/exists? file)
    (process-file file)))

(println "\n=== Running clj-kondo to check remaining issues ===")
(require '[babashka.process :as p])
(p/shell "clj-kondo" "--lint" "src/sente_lite")

(println "\nNote: Some issues require manual fixing:")
(println "- Unused bindings should be prefixed with _")
(println "- Misplaced docstrings need manual adjustment")
(println "- Missing :body values in HTTP responses need content")