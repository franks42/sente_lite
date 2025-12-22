#!/usr/bin/env bb
;;; Build a concatenated source bundle for Scittle
;;; Creates sente-lite-nrepl.cljs that can be loaded as a single script

(require '[babashka.fs :as fs]
         '[clojure.string :as str])

(def output-file "sente-lite-nrepl.cljs")

;; =============================================================================
;; IMPORTANT: LOADING ORDER MATTERS!
;; =============================================================================
;; Scittle evaluates the concatenated source top-to-bottom. Files must be
;; ordered so that dependencies are defined BEFORE they are used.
;;
;; Current dependency graph:
;;   packer.cljc           <- no deps (standalone EDN packing)
;;   queue_scittle.cljs    <- no deps (standalone queue impl)
;;   client_scittle.cljs   <- depends on: packer, queue
;;   registry.cljc         <- no deps (standalone registry)
;;   protocol.cljc         <- no deps (just constants)
;;   server.cljc           <- depends on: protocol
;;   browser_adapter.cljs  <- depends on: client, protocol, server
;;
;; If you add new files, analyze their requires and insert at the correct
;; position in this list. The test suite (Phase 5) will catch load order
;; errors via Playwright browser testing.
;; =============================================================================

(def source-files
  [;; Core sente-lite (order matters for dependencies)
   "../src/sente_lite/packer.cljc"           ; no deps
   "../src/sente_lite/queue_scittle.cljs"    ; no deps
   "../src/sente_lite/client_scittle.cljs"   ; depends on: packer, queue
   "../src/sente_lite/registry.cljc"         ; no deps

   ;; nREPL module
   "../modules/nrepl/src/nrepl_sente/protocol.cljc"       ; no deps
   "../modules/nrepl/src/nrepl_sente/server.cljc"         ; depends on: protocol
   "../modules/nrepl/src/nrepl_sente/browser_adapter.cljs" ; depends on: client, protocol, server
   ])

(defn strip-ns-form
  "Remove the ns form from source, keeping everything else."
  [source]
  (let [lines (str/split-lines source)
        ;; Find where ns form ends (simple heuristic: look for closing paren at start of line)
        in-ns? (atom false)
        paren-depth (atom 0)]
    (->> lines
         (drop-while
          (fn [line]
            (let [trimmed (str/trim line)]
              (cond
                ;; Start of ns form
                (str/starts-with? trimmed "(ns ")
                (do (reset! in-ns? true)
                    (swap! paren-depth + (count (filter #(= % \() trimmed)))
                    (swap! paren-depth - (count (filter #(= % \)) trimmed)))
                    true)

                ;; Inside ns form
                @in-ns?
                (do (swap! paren-depth + (count (filter #(= % \() trimmed)))
                    (swap! paren-depth - (count (filter #(= % \)) trimmed)))
                    (when (<= @paren-depth 0)
                      (reset! in-ns? false))
                    true)

                ;; Skip empty lines at start
                (str/blank? trimmed)
                true

                ;; Keep everything else
                :else false))))
         (str/join "\n"))))

(defn read-source [file]
  (let [path (str (fs/parent *file*) "/" file)]
    (if (fs/exists? path)
      (slurp path)
      (do (println "WARNING: File not found:" path)
          nil))))

(defn build-bundle []
  (println "Building sente-lite-nrepl bundle...")
  (println)

  (let [header (str ";;; sente-lite-nrepl.cljs - Bundled source for Scittle\n"
                    ";;; Generated: " (java.time.LocalDateTime/now) "\n"
                    ";;; \n"
                    ";;; Usage in HTML:\n"
                    ";;;   <script src=\"scittle.js\"></script>\n"
                    ";;;   <script src=\"scittle.nrepl.js\"></script>\n"
                    ";;;   <script src=\"sente-lite-nrepl.cljs\" type=\"application/x-scittle\"></script>\n"
                    ";;;\n"
                    ";;; Then use:\n"
                    ";;;   (require '[sente-lite.client-scittle :as client])\n"
                    ";;;   (require '[nrepl-sente.browser-adapter :as adapter])\n"
                    ";;;\n\n")

        sources (for [file source-files]
                  (when-let [src (read-source file)]
                    (println "  Adding:" file)
                    (str "\n;;; ============================================================\n"
                         ";;; Source: " file "\n"
                         ";;; ============================================================\n\n"
                         src)))

        bundle (str header (str/join "\n" (remove nil? sources)))]

    (spit output-file bundle)
    (println)
    (println "Created:" output-file)
    (println "Size:" (count bundle) "bytes")))

(build-bundle)
