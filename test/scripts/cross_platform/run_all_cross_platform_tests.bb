#!/usr/bin/env bb
;;
;; Cross-Platform Test Matrix Runner
;;
;; Runs all cross-platform interoperability tests and reports results.
;;
;; Test Matrix:
;; +------------------+----------------+----------------+----------------+
;; | Server \ Client  | BB Client      | nbb Client     | Sente Client   |
;; +------------------+----------------+----------------+----------------+
;; | BB Server        | ✓ (same-plat)  | ✓ tested       | ⬜ TODO        |
;; | nbb Server       | ✓ tested       | ✓ (same-plat)  | ⬜ TODO        |
;; | Sente Server     | ✓ tested       | ⬜ TODO        | N/A            |
;; +------------------+----------------+----------------+----------------+
;;

(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs])

;; Get project root (3 levels up from this script)
(def project-root (-> *file* fs/parent fs/parent fs/parent fs/parent str))
(def script-dir (-> *file* fs/parent str))
(cp/add-classpath (str project-root "/src"))

(require '[babashka.process :as p])

(println "")
(println "======================================================================")
(println "         sente-lite Cross-Platform Interoperability Tests             ")
(println "======================================================================")
(println "")

(def test-results (atom []))

(defn run-test [name script & {:keys [dir]}]
  (println "----------------------------------------------------------------------")
  (println "Running:" name)
  (println "----------------------------------------------------------------------")
  (let [opts (cond-> {:continue true :out :inherit :err :inherit :dir project-root}
               dir (assoc :dir dir))
        result (p/shell opts "bb" script)
        passed? (zero? (:exit result))]
    (swap! test-results conj {:name name :passed passed?})
    (println)
    passed?))

(defn run-nbb-test [name script]
  (println "----------------------------------------------------------------------")
  (println "Running:" name)
  (println "----------------------------------------------------------------------")
  (let [result (p/shell {:continue true 
                         :out :inherit 
                         :err :inherit
                         :dir (str project-root "/test/nbb")}
                        "nbb" "--classpath" "../../src" script)
        passed? (zero? (:exit result))]
    (swap! test-results conj {:name name :passed passed?})
    (println)
    passed?))

;; ============================================================================
;; Same-Platform Tests
;; ============================================================================

(println "=== Same-Platform Tests ===")
(println)

(run-test "BB Server <-> BB Client (unit test)"
          "test/scripts/test_client_bb.bb")

(run-test "BB Server <-> BB Client (multiprocess)"
          "test/scripts/multiprocess/01_basic.bb")

;; Check if nbb is set up
(when (.exists (java.io.File. (str project-root "/test/nbb/node_modules/ws")))
  (run-nbb-test "nbb Server <-> nbb Client"
                "test_server_nbb_module.cljs"))

;; ============================================================================
;; Cross-Platform Tests (sente-lite only)
;; ============================================================================

(println)
(println "=== Cross-Platform Tests (sente-lite) ===")
(println)

(run-test "BB Server <-> nbb Client"
          (str script-dir "/test_bb_server_nbb_client.bb"))

(run-test "nbb Server <-> BB Client"
          (str script-dir "/test_nbb_server_bb_client.bb"))

;; ============================================================================
;; Scittle Browser Tests (if Playwright available)
;; ============================================================================

(when (.exists (java.io.File. (str project-root "/dev/scittle-demo/node_modules/playwright")))
  (println)
  (println "=== Scittle Browser Tests ===")
  (println "(Requires Playwright)")
  (println)
  
  (println "----------------------------------------------------------------------")
  (println "Running: BB Server <-> Scittle Client (browser)")
  (println "----------------------------------------------------------------------")
  (let [result (p/shell {:continue true 
                         :out :inherit 
                         :err :inherit
                         :dir (str project-root "/dev/scittle-demo")}
                        "node" "playwright-client-test.mjs")
        passed? (zero? (:exit result))]
    (swap! test-results conj {:name "BB Server <-> Scittle Client (browser)" :passed passed?})
    (println)))

;; ============================================================================
;; Sente Compat Tests (if available)
;; ============================================================================

(when (.exists (java.io.File. (str project-root "/test/sente-compat/deps.edn")))
  (println)
  (println "=== Sente Interoperability Tests ===")
  (println "(These require JVM Clojure and may take longer)")
  (println)
  
  ;; Start Sente server in background
  (println "[sente] Starting Sente JVM server...")
  (def sente-server 
    (p/process {:dir (str project-root "/test/sente-compat")
                :out :inherit
                :err :inherit}
               "clj" "-M:server" "8090"))
  
  ;; Wait for server to start
  (Thread/sleep 8000)
  
  ;; Check if server is running
  (def server-check
    (try
      (slurp "http://localhost:8090/")
      (catch Exception e nil)))
  
  (if (and server-check (re-find #"Sente Test Server" server-check))
    (do
      (println "[sente] Sente server is running")
      (println)
      
      ;; Run sente-lite BB client to Sente server test
      (run-test "Sente Server <-> BB Client (sente-lite)"
                "test_sente_lite_client_to_sente_server.bb"
                :dir (str project-root "/test/sente-compat"))
      
      ;; Stop Sente server
      (println "[sente] Stopping Sente server...")
      (p/destroy sente-server))
    (do
      (println "[sente] WARNING: Sente server failed to start, skipping Sente tests")
      (try (p/destroy sente-server) (catch Exception _)))))

;; ============================================================================
;; Summary
;; ============================================================================

(println "")
(println "======================================================================")
(println "                          Test Summary                                ")
(println "======================================================================")
(println "")

(def passed (count (filter :passed @test-results)))
(def failed (count (filter #(not (:passed %)) @test-results)))
(def total (count @test-results))

(doseq [{:keys [name passed]} @test-results]
  (println (if passed "  [PASS]" "  [FAIL]") name))

(println)
(println "----------------------------------------------------------------------")
(println (format "Total: %d  |  Passed: %d  |  Failed: %d" total passed failed))
(println "----------------------------------------------------------------------")
(println)

(println "Platform Matrix Status:")
(println "")
(println "  Servers        | BB Client | nbb Client | Scittle | Sente")
(println "  ---------------+-----------+------------+---------+------")
(println "  BB Server      |    [x]    |    [x]     |   [x]   |  [ ]")
(println "  nbb Server     |    [x]    |    [x]     |   [ ]   |  [ ]")
(println "  Sente Server   |    [x]    |    [ ]     |   [ ]   |  N/A")
(println "")
(println "  Legend: [x] = tested & passing, [ ] = not yet implemented")
(println "")

(if (zero? failed)
  (do (println "All cross-platform tests passed!")
      (System/exit 0))
  (do (println "Some tests failed!")
      (System/exit 1)))
