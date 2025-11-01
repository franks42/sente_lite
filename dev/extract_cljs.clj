;; EXPERIMENTAL - DOES NOT WORK: Extract CLJS-only code using clojure.tools.reader
;;
;; Status: ABANDONED - Expands macros, corrupts source code
;; Problem: tools.reader + pprint expands syntax-quoted macros into unreadable mess
;;
;; Example failure:
;; - Input: (defmacro signal! [opts] `(when ~should-signal? ...))
;; - Output: (clojure.core/sequence (clojure.core/seq (clojure.core/concat ...)))
;; - Result: 100+ lines of garbage instead of original macro
;;
;; Decision: This approach is NOT VIABLE for preserving source code
;; See: CONTEXT.md Session 7, doc/plan.md "Future Enhancements"
;;
;; Would need: rewrite-clj or similar AST-preserving tool
;;
;; This file kept for reference only - DO NOT USE

(require '[clojure.java.io :as io]
         '[clojure.tools.reader :as reader]
         '[clojure.tools.reader.reader-types :as reader-types]
         '[clojure.pprint :as pprint])

(defn extract-cljs-forms
  "Extract CLJS-only forms from CLJC file using tools.reader"
  [input-file]
  (with-open [rdr (io/reader input-file)]
    (let [push-back-reader (reader-types/indexing-push-back-reader rdr)
          opts {:read-cond :allow
                :features #{:cljs}
                :eof ::eof}]
      (loop [forms []]
        (let [form (reader/read opts push-back-reader)]
          (if (= form ::eof)
            forms
            (recur (conj forms form))))))))

(defn write-cljs-file
  "Write forms to CLJS file with pretty printing"
  [output-file forms]
  (with-open [w (io/writer output-file)]
    (binding [*out* w
              pprint/*print-right-margin* 100]
      (doseq [form forms]
        (pprint/pprint form)
        (.write w "\n")))))

(let [input "/Users/franksiebenlist/Development/sente_lite/src/telemere_lite/core.cljc"
      output "/Users/franksiebenlist/Development/sente_lite/dev/scittle-demo/telemere-lite.cljs"]

  (println "🔍 Extracting CLJS-only code using clojure.tools.reader...")
  (println "Input: " input)
  (println "Output:" output)
  (println)

  (try
    (let [forms (extract-cljs-forms input)
          _ (write-cljs-file output forms)
          original-size (.length (io/file input))
          extracted-size (.length (io/file output))
          savings (- original-size extracted-size)
          percent (int (* 100 (/ savings original-size)))]

      (println "📊 Results:")
      (println "  Forms extracted:" (count forms))
      (println "  Original size:  " original-size "bytes")
      (println "  Extracted size: " extracted-size "bytes")
      (println "  Savings:       " savings "bytes")
      (println "  Percent saved: " percent "%")
      (println)
      (println "✅ Extracted file written to:" output)
      (println)
      (println "This extraction properly handles:")
      (println "  ✅ Nested reader conditionals")
      (println "  ✅ Splicing reader conditionals (#?@)")
      (println "  ✅ Default branches")
      (println "  ✅ Multiple features")
      (println "  ✅ Reader conditionals in all positions"))

    (catch Exception e
      (println "❌ Error:" (.getMessage e))
      (.printStackTrace e)
      (System/exit 1))))
