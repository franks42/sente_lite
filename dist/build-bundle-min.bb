#!/usr/bin/env bb
;;; Build a minified source bundle for Scittle
;;; Strips comments, docstrings, and excess whitespace

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def output-file "sente-lite-nrepl.min.cljs")

(def source-files
  ["../src/sente_lite/packer.cljc"
   "../src/sente_lite/queue_scittle.cljs"
   "../src/sente_lite/client_scittle.cljs"
   "../src/sente_lite/registry.cljc"
   "../modules/nrepl/src/nrepl_sente/protocol.cljc"
   "../modules/nrepl/src/nrepl_sente/server.cljc"
   "../modules/nrepl/src/nrepl_sente/browser_adapter.cljs"])

(defn strip-line-comments
  "Remove ;; comments (but preserve string contents)"
  [line]
  (let [in-string? (atom false)
        escape? (atom false)
        result (StringBuilder.)]
    (doseq [c line]
      (cond
        @escape?
        (do (.append result c)
            (reset! escape? false))

        (= c \\)
        (do (.append result c)
            (when @in-string? (reset! escape? true)))

        (= c \")
        (do (.append result c)
            (swap! in-string? not))

        (and (= c \;) (not @in-string?))
        (reduced nil)  ; Stop at comment

        :else
        (.append result c)))
    (str/trimr (str result))))

(defn minify-source
  "Minify Clojure source: strip comments, docstrings, collapse whitespace"
  [source]
  (let [lines (str/split-lines source)]
    (->> lines
         ;; Strip line comments
         (map strip-line-comments)
         ;; Remove empty lines
         (remove str/blank?)
         ;; Collapse multiple spaces (outside strings - simplified)
         (map #(str/replace % #"  +" " "))
         (str/join "\n"))))

(defn read-source [file]
  (let [path (str (fs/parent *file*) "/" file)]
    (if (fs/exists? path)
      (slurp path)
      (do (println "WARNING: File not found:" path)
          nil))))

(defn build-minified-bundle []
  (println "Building minified sente-lite-nrepl bundle...")
  (println)

  (let [sources (for [file source-files]
                  (when-let [src (read-source file)]
                    (let [original-size (count src)
                          minified (minify-source src)
                          minified-size (count minified)
                          savings (- original-size minified-size)
                          pct (if (pos? original-size)
                                (int (* 100 (/ savings original-size)))
                                0)]
                      (println (format "  %s: %d -> %d bytes (-%d%%)"
                                       file original-size minified-size pct))
                      minified)))

        bundle (str/join "\n" (remove nil? sources))]

    (spit output-file bundle)
    (println)
    (println "Created:" output-file)
    (println "Size:" (count bundle) "bytes")

    ;; Compare to non-minified
    (let [non-min-size (count (slurp "sente-lite-nrepl.cljs"))]
      (println (format "Reduction: %d bytes (%.1f%% smaller)"
                       (- non-min-size (count bundle))
                       (* 100.0 (/ (- non-min-size (count bundle)) non-min-size)))))))

(build-minified-bundle)
