#!/usr/bin/env bb
;;; Test dependency order validation in build-bundle.bb
;;;
;;; Tests:
;;; 1. Correct order should pass validation
;;; 2. Wrong order should fail with clear error

(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.edn :as edn])

(println "🧪 Testing Bundle Dependency Order Validation")
(println)

;; =============================================================================
;; Copy validation functions from build-bundle.bb
;; =============================================================================

(def external-namespaces
     #{'clojure.core 'cljs.core 'clojure.string 'clojure.edn 'clojure.set
       'taoensso.trove 'taoensso.trove.utils 'taoensso.trove.console})

(defn extract-ns-form [source]
  (let [lines (str/split-lines source)
        in-ns? (atom false)
        paren-depth (atom 0)
        ns-lines (atom [])]
    (doseq [line lines
            :while (or (not @in-ns?) (> @paren-depth 0))]
           (let [trimmed (str/trim line)]
             (cond
               (str/starts-with? trimmed "(ns ")
               (do (reset! in-ns? true)
                   (swap! paren-depth + (count (filter #(= % \() trimmed)))
                   (swap! paren-depth - (count (filter #(= % \)) trimmed)))
                   (swap! ns-lines conj line))
               @in-ns?
               (do (swap! paren-depth + (count (filter #(= % \() trimmed)))
                   (swap! paren-depth - (count (filter #(= % \)) trimmed)))
                   (swap! ns-lines conj line)))))
    (when (seq @ns-lines)
      (str/join "\n" @ns-lines))))

(defn strip-reader-conditionals [s]
  (-> s
      (str/replace #"#\?\s*\(:cljs\s+" "")
      (str/replace #"#\?\s*\(:clj[^\)]*\)" "")
      (str/replace #":bb\s+\([^\)]*\)" "")
      (str/replace #":clj\s+\([^\)]*\)" "")))

(defn parse-ns-form [ns-form-str]
  (when ns-form-str
    (try
     (let [cleaned (strip-reader-conditionals ns-form-str)
           form (edn/read-string cleaned)
           ns-name (second form)
           require-clause (->> form (drop 2)
                               (filter #(and (sequential? %) (= :require (first %))))
                               first)
           requires (when require-clause
                      (->> (rest require-clause)
                           (map (fn [req]
                                  (cond (symbol? req) req
                                        (vector? req) (first req)
                                        :else nil)))
                           (remove nil?) set))]
       {:ns ns-name :requires (or requires #{})})
     (catch Exception _e nil))))

(defn extract-dependencies [file source]
  (let [ns-form (extract-ns-form source)
        parsed (parse-ns-form ns-form)]
    (merge {:file file} parsed)))

(defn validate-dependency-order
  "Returns {:valid? bool :errors [...]}"
  [file-deps]
  (let [available (atom external-namespaces)
        errors (atom [])]
    (doseq [{:keys [file ns requires]} file-deps]
           (let [missing (clojure.set/difference requires @available)]
             (when (seq missing)
               (swap! errors conj {:file file :ns ns :missing missing}))
             (swap! available conj ns)))
    {:valid? (empty? @errors)
     :errors @errors}))

(defn read-source [file]
  (when (fs/exists? file) (slurp file)))

(defn analyze-files [files]
  (for [file files]
       (when-let [src (read-source file)]
                 (assoc (extract-dependencies file src) :source src))))

;; =============================================================================
;; Test Cases
;; =============================================================================

(def correct-order
     ["../src/sente_lite/packer.cljc"
      "../src/sente_lite/queue_scittle.cljs"
      "../src/sente_lite/client_scittle.cljs"
      "../src/sente_lite/registry.cljc"
      "../modules/nrepl/src/nrepl_sente/protocol.cljc"
      "../modules/nrepl/src/nrepl_sente/server.cljc"
      "../modules/nrepl/src/nrepl_sente/browser_adapter.cljs"])

(def wrong-order
     [;; browser_adapter FIRST - but it needs client, registry!
      "../modules/nrepl/src/nrepl_sente/browser_adapter.cljs"
      "../src/sente_lite/packer.cljc"
      "../src/sente_lite/queue_scittle.cljs"
      "../src/sente_lite/client_scittle.cljs"
      "../src/sente_lite/registry.cljc"
      "../modules/nrepl/src/nrepl_sente/protocol.cljc"
      "../modules/nrepl/src/nrepl_sente/server.cljc"])

(def test-results (atom {:passed 0 :failed 0}))

(defn run-test [name test-fn]
  (print (str "  " name "... "))
  (flush)
  (try
   (if (test-fn)
     (do (println "✅ PASSED")
         (swap! test-results update :passed inc))
     (do (println "❌ FAILED")
         (swap! test-results update :failed inc)))
   (catch Exception e
          (println (str "❌ ERROR: " (.getMessage e)))
          (swap! test-results update :failed inc))))

;; =============================================================================
;; Run Tests
;; =============================================================================

(println "Test 1: Correct order should validate")
(run-test "Correct dependency order"
          (fn []
            (let [deps (remove nil? (analyze-files correct-order))
                  result (validate-dependency-order deps)]
              (:valid? result))))

(println)
(println "Test 2: Wrong order should fail with specific errors")
(run-test "Wrong order detected"
          (fn []
            (let [deps (remove nil? (analyze-files wrong-order))
                  result (validate-dependency-order deps)]
              (not (:valid? result)))))

(run-test "Error identifies browser_adapter.cljs"
          (fn []
            (let [deps (remove nil? (analyze-files wrong-order))
                  result (validate-dependency-order deps)
                  error-files (set (map :file (:errors result)))]
              (contains? error-files "../modules/nrepl/src/nrepl_sente/browser_adapter.cljs"))))

(run-test "Error identifies missing sente-lite.client-scittle"
          (fn []
            (let [deps (remove nil? (analyze-files wrong-order))
                  result (validate-dependency-order deps)
                  all-missing (apply clojure.set/union (map :missing (:errors result)))]
              (contains? all-missing 'sente-lite.client-scittle))))

(run-test "Error identifies missing sente-lite.registry"
          (fn []
            (let [deps (remove nil? (analyze-files wrong-order))
                  result (validate-dependency-order deps)
                  all-missing (apply clojure.set/union (map :missing (:errors result)))]
              (contains? all-missing 'sente-lite.registry))))

;; =============================================================================
;; Summary
;; =============================================================================

(println)
(println "=== Summary ===")
(let [{:keys [passed failed]} @test-results
      total (+ passed failed)]
  (println (format "Tests: %d passed, %d failed, %d total" passed failed total))
  (if (zero? failed)
    (do (println "✅ All dependency order tests passed!")
        (System/exit 0))
    (do (println "❌ Some tests failed!")
        (System/exit 1))))
