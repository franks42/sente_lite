#!/usr/bin/env bb

;; EXPERIMENTAL: Extract CLJS-only code from CLJC file
;;
;; Status: Research/proof-of-concept - NOT IN USE
;; Result: 58% size reduction proven (29KB → 12KB)
;;
;; Decision: Use full CLJC file for now (added to backlog)
;; See: doc/plan.md "Future Enhancements" and CONTEXT.md Session 7
;;
;; Limitations of this simple approach:
;; - Line-based filtering (not AST-aware)
;; - Doesn't handle nested reader conditionals
;; - Doesn't handle splicing reader conditionals (#?@)
;; - May fail on reader conditionals inside strings
;;
;; For production use, would need rewrite-clj or similar AST-aware tool.

;; Extract CLJS-only code from CLJC file
;; Demonstrates potential file size reduction

(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(defn extract-cljs
  "Read CLJC file and extract CLJS-only code using simpler string-based approach"
  [input-file output-file]
  (let [content (slurp input-file)
        ;; Use edn/read-string with CLJS features
        ;; This will process reader conditionals
        opts {:readers {'?
                        (fn [form]
                          ;; Custom reader for #? conditional
                          (if (map? form)
                            (or (get form :cljs)
                                (get form :default))
                            form))}}
        ;; For now, just do simple line-based filtering
        ;; Remove lines that are BB-only blocks
        lines (str/split-lines content)
        filtered-lines
        (loop [result []
               remaining lines
               in-bb-block false
               block-depth 0]
          (if (empty? remaining)
            result
            (let [line (first remaining)
                  trimmed (str/trim line)]
              (cond
                ;; Start of BB-only block
                (str/starts-with? trimmed "#?(:bb")
                (recur result (rest remaining) true 1)

                ;; In BB block - track depth
                in-bb-block
                (let [open-parens (count (re-seq #"\(" line))
                      close-parens (count (re-seq #"\)" line))
                      new-depth (+ block-depth open-parens (- close-parens))]
                  (if (<= new-depth 0)
                    ;; Block ended
                    (recur result (rest remaining) false 0)
                    ;; Still in block
                    (recur result (rest remaining) true new-depth)))

                ;; Normal line - keep it
                :else
                (recur (conj result line) (rest remaining) in-bb-block block-depth)))))
        filtered-content (str/join "\n" filtered-lines)]

    ;; Write extracted content
    (spit output-file filtered-content)

    ;; Report stats
    (let [original-size (.length (io/file input-file))
          extracted-size (.length (io/file output-file))
          savings (- original-size extracted-size)
          percent (int (* 100 (/ savings original-size)))]
      {:original-size original-size
       :extracted-size extracted-size
       :savings savings
       :percent-saved percent})))

(let [input "/Users/franksiebenlist/Development/sente_lite/src/telemere_lite/core.cljc"
      output "/tmp/telemere_lite_cljs_only.cljs"]

  (println "🔍 Extracting CLJS-only code from core.cljc...")
  (println "Input: " input)
  (println "Output:" output)
  (println)

  (try
    (let [stats (extract-cljs input output)]
      (println "📊 Results:")
      (println "  Original size: " (:original-size stats) "bytes")
      (println "  Extracted size:" (:extracted-size stats) "bytes")
      (println "  Savings:      " (:savings stats) "bytes")
      (println "  Percent saved:" (:percent-saved stats) "%")
      (println)
      (println "✅ Extracted file written to:" output)
      (println "   You can inspect it with: cat" output))
    (catch Exception e
      (println "❌ Error:" (.getMessage e))
      (.printStackTrace e))))
