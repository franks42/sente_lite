#!/usr/bin/env bb
;; Test namespace persistence in nrepl-sente server
;; Verifies that namespace changes persist across requests (session-based)

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "modules/nrepl/src")

(require '[nrepl-sente.server :as server])

(def passed (atom 0))
(def failed (atom 0))

(defn test-pass [msg]
  (swap! passed inc)
  (println "  [PASS]" msg))

(defn test-fail [msg]
  (swap! failed inc)
  (println "  [FAIL]" msg))

(defn check [description pred actual]
  (if pred
    (test-pass (str description " - " actual))
    (test-fail (str description " - got: " actual))))

(println "=== nREPL Namespace Persistence Tests ===\n")

;; Test 1: Initial namespace should be 'user
(println "1. Testing initial namespace...")
(let [result (server/eval-code "(str *ns*)" "test-session-1")]
  (check "Initial namespace is user"
         (and (:success result) (= "\"user\"" (:value result)))
         (:value result)))

;; Test 2: Switch namespace
(println "\n2. Testing namespace switch...")
(let [result (server/eval-code "(ns nrepl.test.myns)" "test-session-1")]
  (check "Namespace switch succeeds"
         (:success result)
         (:ns result))
  (check "Reported ns is nrepl.test.myns"
         (= "nrepl.test.myns" (:ns result))
         (:ns result)))

;; Test 3: Namespace persists on next request
(println "\n3. Testing namespace persistence...")
(let [result (server/eval-code "(str *ns*)" "test-session-1")]
  (check "Namespace persisted"
         (and (:success result) (= "\"nrepl.test.myns\"" (:value result)))
         (:value result)))

;; Test 4: Define a var
(println "\n4. Testing var definition...")
(let [result (server/eval-code "(def my-test-var 42)" "test-session-1")]
  (check "Var definition succeeds"
         (:success result)
         (:value result)))

;; Test 5: Access var without qualification
(println "\n5. Testing unqualified var access...")
(let [result (server/eval-code "my-test-var" "test-session-1")]
  (check "Unqualified access succeeds"
         (and (:success result) (= "42" (:value result)))
         (:value result)))

;; Test 6: Switch to another namespace
(println "\n6. Testing switch to different namespace...")
(let [result (server/eval-code "(ns nrepl.test.other)" "test-session-1")]
  (check "Switch to nrepl.test.other"
         (= "nrepl.test.other" (:ns result))
         (:ns result)))

;; Test 7: Original var not accessible
(println "\n7. Testing var isolation...")
(let [result (server/eval-code "my-test-var" "test-session-1")]
  (check "Var not accessible in new ns (error expected)"
         (not (:success result))
         (:error result)))

;; Test 8: Qualified access works
(println "\n8. Testing qualified var access...")
(let [result (server/eval-code "nrepl.test.myns/my-test-var" "test-session-1")]
  (check "Qualified access succeeds"
         (and (:success result) (= "42" (:value result)))
         (:value result)))

;; Print summary
(println "\n=== Test Summary ===")
(println "Passed:" @passed)
(println "Failed:" @failed)
(println "Total: " (+ @passed @failed))
(println)

(if (zero? @failed)
  (println "All tests passed!")
  (do
    (println "SOME TESTS FAILED!")
    (System/exit 1)))
