#!/usr/bin/env bb
;;; Build a concatenated source bundle for Scittle
;;; Creates sente-lite-nrepl.cljs that can be loaded as a single script
;;;
;;; Features:
;;; - Validates dependency order before building
;;; - Fails fast if files are in wrong order
;;; - Reports exactly which dependency is missing

(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.edn :as edn]
         '[clojure.set :as set])

(def output-file "sente-lite-nrepl.cljs")

;; =============================================================================
;; IMPORTANT: LOADING ORDER MATTERS!
;; =============================================================================
;; Scittle evaluates the concatenated source top-to-bottom. Files must be
;; ordered so that dependencies are defined BEFORE they are used.
;;
;; The build script now VALIDATES dependency order automatically by parsing
;; ns forms and checking that all :require'd namespaces appear earlier in
;; the file list.
;;
;; External dependencies (not in bundle) are listed in external-namespaces.
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
      "../modules/nrepl/src/nrepl_sente/browser_adapter.cljs" ; depends on: client, registry, protocol, server
      ])

;; Namespaces provided by external scripts (Scittle, CDN libs, etc.)
;; These don't need to be in our bundle
(def external-namespaces
     #{'clojure.core 'cljs.core 'clojure.string 'clojure.edn 'clojure.set
       'taoensso.trove 'taoensso.trove.utils 'taoensso.trove.console})

;; =============================================================================
;; Dependency Extraction
;; =============================================================================

(defn extract-ns-form
  "Extract the ns form from source code as a string.
   Returns nil if no ns form found."
  [source]
  (let [lines (str/split-lines source)
        in-ns? (atom false)
        paren-depth (atom 0)
        ns-lines (atom [])]
    (doseq [line lines
            :while (or (not @in-ns?) (> @paren-depth 0))]
           (let [trimmed (str/trim line)]
             (cond
          ;; Start of ns form
               (str/starts-with? trimmed "(ns ")
               (do (reset! in-ns? true)
                   (swap! paren-depth + (count (filter #(= % \() trimmed)))
                   (swap! paren-depth - (count (filter #(= % \)) trimmed)))
                   (swap! ns-lines conj line))

          ;; Inside ns form
               @in-ns?
               (do (swap! paren-depth + (count (filter #(= % \() trimmed)))
                   (swap! paren-depth - (count (filter #(= % \)) trimmed)))
                   (swap! ns-lines conj line)))))
    (when (seq @ns-lines)
      (str/join "\n" @ns-lines))))

(defn strip-reader-conditionals
  "Remove #?(...) reader conditionals, keeping :cljs branch if present."
  [s]
  (-> s
      ;; Simple approach: remove #? and the reader conditional wrapper
      ;; This is a simplification - real parsing would be more complex
      (str/replace #"#\?\s*\(:cljs\s+" "")
      (str/replace #"#\?\s*\(:clj[^\)]*\)" "")
      (str/replace #":bb\s+\([^\)]*\)" "")
      (str/replace #":clj\s+\([^\)]*\)" "")))

(defn parse-ns-form
  "Parse ns form string to extract namespace name and requires.
   Returns {:ns symbol :requires #{symbols}}"
  [ns-form-str]
  (when ns-form-str
    (try
      ;; Strip reader conditionals for parsing
     (let [cleaned (strip-reader-conditionals ns-form-str)
            ;; Parse the ns form
           form (edn/read-string cleaned)
           ns-name (second form)
            ;; Find :require clause
           require-clause (->> form
                               (drop 2) ; skip 'ns and name
                               (filter #(and (sequential? %)
                                             (= :require (first %))))
                               first)
            ;; Extract required namespaces
           requires (when require-clause
                      (->> (rest require-clause)
                           (map (fn [req]
                                  (cond
                                    (symbol? req) req
                                    (vector? req) (first req)
                                    :else nil)))
                           (remove nil?)
                           set))]
       {:ns ns-name
        :requires (or requires #{})})
     (catch Exception e
            (println "  WARNING: Could not parse ns form:" (.getMessage e))
            nil))))

(defn extract-dependencies
  "Extract namespace and dependencies from a source file.
   Returns {:file path :ns symbol :requires #{symbols}}"
  [file source]
  (let [ns-form (extract-ns-form source)
        parsed (parse-ns-form ns-form)]
    (merge {:file file} parsed)))

;; =============================================================================
;; Dependency Validation
;; =============================================================================

(defn validate-dependency-order!
  "Validate that all files are in correct dependency order.
   Throws exception with details if order is wrong.
   Returns true if valid."
  [file-deps]
  (println "\nValidating dependency order...")
  (let [available (atom external-namespaces)
        errors (atom [])]
    (doseq [{:keys [file ns requires]} file-deps]
           (let [missing (set/difference requires @available)]
             (when (seq missing)
               (swap! errors conj {:file file
                                   :ns ns
                                   :missing missing
                                   :available @available}))
        ;; Add this namespace to available set
             (swap! available conj ns)))

    (if (seq @errors)
      (do
       (println "\n❌ DEPENDENCY ORDER ERROR!")
       (println "=" (apply str (repeat 60 "=")))
       (doseq [{:keys [file ns missing]} @errors]
              (println "\nFile:" file)
              (println "  Namespace:" ns)
              (println "  Missing dependencies:" (str/join ", " (map str missing)))
              (println "  These namespaces must appear EARLIER in source-files list"))
       (println "\n" (apply str (repeat 60 "=")))
       (throw (ex-info "Dependency order validation failed"
                       {:errors @errors})))
      (do
       (println "✓ All dependencies satisfied")
       true))))

;; =============================================================================
;; Bundle Building
;; =============================================================================

(defn read-source [file]
  (let [path (str (fs/parent *file*) "/" file)]
    (if (fs/exists? path)
      (slurp path)
      (do (println "WARNING: File not found:" path)
          nil))))

(defn build-bundle []
  (println "Building sente-lite-nrepl bundle...")
  (println)

  ;; Step 1: Read all sources and extract dependencies
  (println "Step 1: Analyzing dependencies...")
  (let [sources-with-deps
        (for [file source-files]
             (when-let [src (read-source file)]
                       (let [deps (extract-dependencies file src)]
                         (println "  " file "-> ns:" (:ns deps)
                                  "requires:" (count (:requires deps)))
                         (assoc deps :source src))))

        valid-sources (remove nil? sources-with-deps)]

    ;; Step 2: Validate dependency order
    (validate-dependency-order! valid-sources)

    ;; Step 3: Build the bundle
    (println "\nStep 2: Building bundle...")
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
                      ";;;\n"
                      ";;; Dependency order validated at build time.\n"
                      ";;;\n\n")

          bundle-parts (for [{:keys [file source]} valid-sources]
                            (do
                             (println "  Adding:" file)
                             (str "\n;;; ============================================================\n"
                                  ";;; Source: " file "\n"
                                  ";;; ============================================================\n\n"
                                  source)))

          bundle (str header (str/join "\n" bundle-parts))]

      (spit output-file bundle)
      (println)
      (println "✓ Created:" output-file)
      (println "  Size:" (count bundle) "bytes")
      (println "  Files:" (count valid-sources)))))

(build-bundle)
