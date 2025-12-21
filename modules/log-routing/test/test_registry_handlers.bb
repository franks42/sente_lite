#!/usr/bin/env bb
;; Test registry-based log handler configuration
;;
;; Usage: bb modules/log-routing/test/test_registry_handlers.bb

(ns test-registry-handlers)

;; Load dependencies
(load-file "src/sente_lite/registry.cljc")
(load-file "modules/log-routing/src/log_routing/registry_handlers.cljc")

(require '[sente-lite.registry :as reg]
         '[log-routing.registry-handlers :as rh])

(println "\n=== Registry-Based Log Handler Tests ===\n")

(def tests-passed (atom 0))
(def tests-failed (atom 0))

(defn test-case [name test-fn]
  (print (str "  " name "... "))
  (flush)
  (try
    (if (test-fn)
      (do (println "✓") (swap! tests-passed inc))
      (do (println "✗ FAILED") (swap! tests-failed inc)))
    (catch Exception e
      (println (str "✗ ERROR: " (.getMessage e)))
      (swap! tests-failed inc))))

;; Initialize
(rh/init!)

;; Test 1: Console handler registered
(test-case "console handler registered"
  #(fn? (reg/get-value "telemetry.impl/console")))

;; Test 2: Silent handler registered
(test-case "silent handler registered"
  #(fn? (reg/get-value "telemetry.impl/silent")))

;; Test 3: Default points to console
(test-case "default config points to console"
  #(= "telemetry.impl/console" (reg/get-value "telemetry/log-handler")))

;; Test 4: get-handler returns console function
(test-case "get-handler returns console function"
  #(fn? (rh/get-handler)))

;; Test 5: Console handler produces output
(def console-output (atom nil))
(with-redefs [println (fn [& args] (reset! console-output (apply str args)))]
  (let [handler (rh/get-handler)]
    (handler {:level :info :ns "test" :data {:msg "hello"}})))
(test-case "console handler produces output"
  #(and @console-output
        (clojure.string/includes? @console-output "info")
        (clojure.string/includes? @console-output "hello")))

;; Test 6: Switch to silent handler
(rh/use-handler! "telemetry.impl/silent")
(test-case "switch to silent handler"
  #(= "telemetry.impl/silent" (reg/get-value "telemetry/log-handler")))

;; Test 7: get-handler now returns silent
(test-case "get-handler returns silent after switch"
  #(= rh/silent-handler (rh/get-handler)))

;; Test 8: Silent handler returns nil
(test-case "silent handler returns nil"
  #(nil? ((rh/get-handler) {:level :info :data {}})))

;; Test 9: Watch for handler changes
(def watch-triggered (atom false))
(def old-handler-atom (atom nil))
(def new-handler-atom (atom nil))
(rh/on-handler-change! :test-watch
  (fn [old-h new-h]
    (reset! watch-triggered true)
    (reset! old-handler-atom old-h)
    (reset! new-handler-atom new-h)))

;; Switch back to console
(rh/use-handler! "telemetry.impl/console")

(test-case "watch triggered on handler change"
  #(true? @watch-triggered))

(test-case "watch received old handler (silent)"
  #(= rh/silent-handler @old-handler-atom))

(test-case "watch received new handler (console)"
  #(= rh/console-handler @new-handler-atom))

(rh/remove-handler-watch! :test-watch)

;; Test 10: List implementations
(test-case "list-implementations shows both"
  #(let [impls (rh/list-implementations)]
     (and (contains? impls "telemetry.impl/console")
          (contains? impls "telemetry.impl/silent"))))

;; Test 11: Register custom handler
(def custom-calls (atom []))
(rh/register-impl! "custom" (fn [entry] (swap! custom-calls conj entry)))
(rh/use-handler! "telemetry.impl/custom")
((rh/get-handler) {:level :debug :data {:test true}})

(test-case "custom handler receives calls"
  #(= 1 (count @custom-calls)))

(test-case "custom handler receives correct data"
  #(= :debug (:level (first @custom-calls))))

;; Test 12: make-trove-log-fn
(rh/use-handler! "telemetry.impl/console")
(def trove-fn (rh/make-trove-log-fn))
(test-case "make-trove-log-fn returns function"
  #(fn? trove-fn))

;; Summary
(println (str "\n=== Results: " @tests-passed " passed, " @tests-failed " failed ===\n"))

(when (pos? @tests-failed)
  (System/exit 1))
