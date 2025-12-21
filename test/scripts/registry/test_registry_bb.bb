#!/usr/bin/env bb

;; Test FQN Registry in Babashka
;; Run: bb test/scripts/registry/test_registry_bb.bb

(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")

(require '[sente-lite.registry :as reg])

(def tests-passed (atom 0))
(def tests-failed (atom 0))

(defn test-case [name test-fn]
  (print (str "  " name "... "))
  (flush)
  (try
    (if (test-fn)
      (do (println "✓")
          (swap! tests-passed inc))
      (do (println "✗ FAILED")
          (swap! tests-failed inc)))
    (catch Exception e
      (println (str "✗ ERROR: " (.getMessage e)))
      (swap! tests-failed inc))))

(println "\n=== FQN Registry Tests (Babashka) ===\n")

;; Test 1: get-reg-root
(test-case "get-reg-root returns default"
  #(= "sente-lite.registry" (reg/get-reg-root)))

;; Test 2: set-reg-root!
(test-case "set-reg-root! changes root"
  #(do (reg/set-reg-root! "test.registry")
       (let [result (= "test.registry" (reg/get-reg-root))]
         (reg/set-reg-root! "sente-lite.registry") ;; restore
         result)))

;; Test 3: register! and get-value
(test-case "register! creates resource, get-value reads it"
  #(do (reg/register! "state/user" {:name "Alice"})
       (= {:name "Alice"} (reg/get-value "state/user"))))

;; Test 4: set-value!
(test-case "set-value! updates value"
  #(do (reg/set-value! "state/user" {:name "Bob"})
       (= {:name "Bob"} (reg/get-value "state/user"))))

;; Test 5: swap-value!
(test-case "swap-value! applies function"
  #(do (reg/register! "state/counter" 0)
       (reg/swap-value! "state/counter" inc)
       (reg/swap-value! "state/counter" inc)
       (= 2 (reg/get-value "state/counter"))))

;; Test 6: swap-value! with args
(test-case "swap-value! with extra args"
  #(do (reg/swap-value! "state/user" assoc :role :admin)
       (= :admin (:role (reg/get-value "state/user")))))

;; Test 7: ensure! idempotent
(test-case "ensure! is idempotent"
  #(let [ref1 (reg/ensure! "state/temp")
         ref2 (reg/ensure! "state/temp")]
       (identical? ref1 ref2)))

;; Test 8: get-ref returns atom
(test-case "get-ref returns the atom"
  #(let [ref (reg/get-ref "state/counter")]
       (and (some? ref)
            (= 2 @ref))))

;; Test 9: set-ref! and swap-ref!
(test-case "set-ref!/swap-ref! work with cached ref"
  #(let [ref (reg/get-ref "state/counter")]
       (reg/set-ref! ref 100)
       (reg/swap-ref! ref + 5)
       (= 105 @ref)))

;; Test 10: registered?
(test-case "registered? returns true for existing"
  #(reg/registered? "state/user"))

(test-case "registered? returns false for non-existing"
  #(not (reg/registered? "state/nonexistent")))

;; Test 11: list-registered
(test-case "list-registered shows all names"
  #(let [names (reg/list-registered)]
       (and (contains? names "state/user")
            (contains? names "state/counter"))))

;; Test 12: list-registered-prefix
(test-case "list-registered-prefix filters by prefix"
  #(do (reg/register! "sync/shared" {})
       (let [sync-names (reg/list-registered-prefix "sync/")]
         (and (contains? sync-names "sync/shared")
              (not (contains? sync-names "state/user"))))))

;; Test 13: unregister!
(test-case "unregister! removes from tracking"
  #(do (reg/register! "state/todelete" {:temp true})
       (reg/unregister! "state/todelete")
       (and (not (reg/registered? "state/todelete"))
            (nil? (reg/get-value "state/todelete")))))

;; Test 14: unregister-prefix!
(test-case "unregister-prefix! removes multiple"
  #(do (reg/register! "temp/a" 1)
       (reg/register! "temp/b" 2)
       (reg/register! "temp/c" 3)
       (let [count (reg/unregister-prefix! "temp/")]
         (and (= 3 count)
              (empty? (reg/list-registered-prefix "temp/"))))))

;; Test 15: watch! and unwatch!
(test-case "watch! receives changes"
  #(let [changes (atom [])
         _ (reg/register! "watch/test" 0)
         _ (reg/watch! "watch/test" :test-watch
             (fn [k n old new]
               (swap! changes conj {:old old :new new})))
         _ (reg/set-value! "watch/test" 1)
         _ (reg/set-value! "watch/test" 2)
         _ (reg/unwatch! "watch/test" :test-watch)]
       (and (= 2 (count @changes))
            (= {:old 0 :new 1} (first @changes))
            (= {:old 1 :new 2} (second @changes)))))

;; Test 16: Invalid name validation
(test-case "Invalid name throws"
  #(try
     (reg/register! "Invalid Name!" {})
     false
     (catch Exception e
       (= "Invalid registry name format. Expected: category/name (e.g., state/user-prefs)"
          (.getMessage e)))))

;; Test 17: Name without category throws
(test-case "Name without category throws"
  #(try
     (reg/register! "nocat" {})
     false
     (catch Exception e
       true)))

;; Test 18: get-value on non-existent returns nil
(test-case "get-value on non-existent returns nil"
  #(nil? (reg/get-value "state/doesnotexist")))

;; Test 19: resolve-ref with direct value
(reg/register! "impl/console" (fn [x] (str "console:" x)))
(test-case "resolve-ref with direct value returns value"
  #(fn? (reg/resolve-ref "impl/console")))

;; Test 20: resolve-ref with reference
(reg/register! "config/log-fn" "impl/console")
(test-case "resolve-ref with reference resolves to target"
  #(fn? (reg/resolve-ref "config/log-fn")))

;; Test 21: resolve-ref returns actual function
(test-case "resolve-ref returns callable function"
  #(= "console:test" ((reg/resolve-ref "config/log-fn") "test")))

;; Test 22: resolve-ref with non-existent reference returns string
(reg/register! "config/broken-ref" "impl/nonexistent")
(test-case "resolve-ref with broken reference returns string"
  #(= "impl/nonexistent" (reg/resolve-ref "config/broken-ref")))

;; Test 23: watch-resolved! receives resolved values
(def resolved-old (atom nil))
(def resolved-new (atom nil))
(reg/register! "impl/sente" (fn [x] (str "sente:" x)))
(reg/watch-resolved! "config/log-fn" :test-resolved
  (fn [k n old new]
    (reset! resolved-old old)
    (reset! resolved-new new)))
(reg/set-value! "config/log-fn" "impl/sente")
(test-case "watch-resolved! receives old resolved value"
  #(fn? @resolved-old))
(test-case "watch-resolved! receives new resolved value"
  #(= "sente:x" (@resolved-new "x")))
(reg/unwatch! "config/log-fn" :test-resolved)

;; Cleanup
(reg/unregister! "state/user")
(reg/unregister! "state/counter")
(reg/unregister! "state/temp")
(reg/unregister! "sync/shared")
(reg/unregister! "watch/test")

;; Summary
(println (str "\n=== Results: " @tests-passed " passed, " @tests-failed " failed ===\n"))

(when (pos? @tests-failed)
  (System/exit 1))
