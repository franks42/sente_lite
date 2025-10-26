#!/usr/bin/env bb

;; Fix broken multi-line reader conditionals that are causing parse errors

(require '[clojure.string :as str])

(defn fix-multiline-reader-conditionals [content]
  "Fix multi-line #?(:bb ... :clj ...) that break map/vector structures"
  (-> content
      ;; Fix the broken multi-line conditionals - make them single line
      (str/replace #"#\?\(:bb \(System/currentTimeMillis\)\s+:clj \(System/currentTimeMillis\)\)"
                   "(System/currentTimeMillis)")
      ;; We'll handle platform differences at runtime if needed
      ))

(defn fix-file [file-path]
  (println "Fixing:" file-path)
  (let [content (slurp file-path)
        fixed (fix-multiline-reader-conditionals content)]
    (spit file-path fixed)))

;; Fix all affected files
(doseq [file ["src/sente_lite/channels.cljc"
              "src/sente_lite/server.cljc"
              "src/sente_lite/server_simple.cljc"
              "src/sente_lite/transit_multiplexer.cljc"
              "src/sente_lite/wire_multiplexer.cljc"]]
  (fix-file file))

(println "Fixed reader conditionals. Running kondo check...")
(require '[babashka.process :as p])
(p/shell "clj-kondo" "--lint" "src/sente_lite")