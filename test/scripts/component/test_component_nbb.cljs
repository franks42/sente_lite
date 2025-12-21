#!/usr/bin/env nbb
;;; Test component system on nbb (Node Babashka)
;;; Run: nbb test/scripts/component/test_component_nbb.cljs

(ns component-test
  (:require ["path" :as path]
            ["process" :as process]))

;; Add src to classpath by loading component directly
(require '[nbb.core :refer [load-file]])

;; Load the component file
(load-file (path/resolve (.cwd process) "src/sente_lite/component.cljc"))

(require '[sente-lite.component :as c])

(def tests-passed (atom 0))
(def tests-failed (atom 0))

(defn test-result [name passed? & [msg]]
  (if passed?
    (do (swap! tests-passed inc)
        (println (str "  [PASS] " name)))
    (do (swap! tests-failed inc)
        (println (str "  [FAIL] " name (when msg (str " - " msg)))))))

(println "=== Component System Tests (nbb) ===\n")

;;; Test 1: make-component creates valid structure
(println "Test 1: make-component")
(let [comp (c/make-component :test/basic {:port 8080})]
  (test-result "has :component/type" (= :test/basic (:component/type comp)))
  (test-result "has :config" (= {:port 8080} (:config comp)))
  (test-result "has :state atom" (some? (:state comp)))
  (test-result "initial status is :stopped" (= :stopped (:status @(:state comp)))))

;;; Test 2: Default implementations
(println "\nTest 2: Default implementations")
(let [comp (c/make-component :test/defaults {})]
  (test-result "status returns :stopped" (= :stopped (c/status comp)))
  (test-result "stopped? returns true" (c/stopped? comp))
  (test-result "running? returns false" (not (c/running? comp)))
  (test-result "health returns unhealthy" (not (:healthy? (c/health comp))))
  (test-result "stop! default returns :ok" (= :ok (c/stop! comp)))
  (test-result "stats returns empty map" (= {} (c/stats comp))))

;;; Test 3: start! throws for unimplemented type
(println "\nTest 3: start! throws for unimplemented type")
(let [comp (c/make-component :test/no-impl {})]
  (try
    (c/start! comp)
    (test-result "throws on missing impl" false "did not throw")
    (catch :default e
      (test-result "throws on missing impl" true)
      (test-result "error has component type"
                   (= :test/no-impl (:component/type (ex-data e)))))))

;;; Test 4: Custom component implementation
(println "\nTest 4: Custom component implementation")

;; Define a test component
(defmethod c/start! :test/custom
  [comp]
  (let [config (get comp :config)]
    (when (c/stopped? comp)
      (c/set-started! comp)
      (println "    -> Custom component started with" config)))
  :ok)

(defmethod c/stop! :test/custom
  [comp]
  (when (c/running? comp)
    (c/set-stopped! comp)
    (println "    -> Custom component stopped"))
  :ok)

(defmethod c/stats :test/custom
  [comp]
  (let [state (get comp :state)
        config (get comp :config)
        base-stats (c/stats {:state state})]
    (merge base-stats
           {:custom-metric 42
            :config-keys (keys config)})))

(let [comp (c/make-component :test/custom {:port 3000 :host "localhost"})]
  (test-result "starts as stopped" (c/stopped? comp))

  (c/start! comp)
  (test-result "running after start!" (c/running? comp))
  (test-result "healthy after start!" (c/healthy? comp))
  (test-result "has started-at" (some? (:started-at @(:state comp))))

  (let [stats (c/stats comp)]
    (test-result "custom stats includes metric" (= 42 (:custom-metric stats)))
    (test-result "custom stats includes uptime" (number? (:uptime-ms stats))))

  (c/stop! comp)
  (test-result "stopped after stop!" (c/stopped? comp))
  (test-result "not healthy after stop!" (not (c/healthy? comp))))

;;; Test 5: State transition helpers
(println "\nTest 5: State transition helpers")
(let [comp (c/make-component :test/helpers {})]
  (c/set-status! comp :starting)
  (test-result "set-status! to :starting" (c/starting? comp))

  (c/set-started! comp)
  (test-result "set-started! sets :running" (c/running? comp))
  (test-result "set-started! sets timestamp" (some? (:started-at @(:state comp))))

  (c/set-error! comp {:reason "test error"})
  (test-result "set-error! sets :error" (c/error? comp))
  (test-result "set-error! stores error info" (= {:reason "test error"} (:error @(:state comp))))

  (c/set-stopped! comp)
  (test-result "set-stopped! sets :stopped" (c/stopped? comp)))

;;; Test 6: System management (start-all!, stop-all!)
(println "\nTest 6: System management")

;; Components for system test
(defmethod c/start! :test/sys-a
  [comp]
  (c/set-started! comp)
  (println "    -> sys-a started")
  :ok)

(defmethod c/stop! :test/sys-a
  [comp]
  (c/set-stopped! comp)
  (println "    -> sys-a stopped")
  :ok)

(defmethod c/start! :test/sys-b
  [comp]
  (c/set-started! comp)
  (println "    -> sys-b started")
  :ok)

(defmethod c/stop! :test/sys-b
  [comp]
  (c/set-stopped! comp)
  (println "    -> sys-b stopped")
  :ok)

(let [comp-a (c/make-component :test/sys-a {})
      comp-b (c/make-component :test/sys-b {})
      system [comp-a comp-b]]

  ;; Start all
  (let [result (c/start-all! system)]
    (test-result "start-all! returns :ok" (= :ok result))
    (test-result "all running after start-all!"
                 (every? c/running? system)))

  ;; Status all
  (let [statuses (c/status-all system)]
    (test-result "status-all returns all :running"
                 (= {:test/sys-a :running :test/sys-b :running} statuses)))

  ;; Health all
  (let [health (c/health-all system)]
    (test-result "health-all :healthy? true" (:healthy? health))
    (test-result "health-all has all components"
                 (= 2 (count (:components health)))))

  ;; Stop all
  (let [result (c/stop-all! system)]
    (test-result "stop-all! returns :ok" (= :ok result))
    (test-result "all stopped after stop-all!"
                 (every? c/stopped? system))))

;;; Test 7: start-all! rollback on failure
(println "\nTest 7: start-all! rollback on failure")

(defmethod c/start! :test/fail-start
  [_]
  (throw (ex-info "Intentional failure" {})))

(let [comp-a (c/make-component :test/sys-a {})
      comp-fail (c/make-component :test/fail-start {})
      system [comp-a comp-fail]]

  (try
    (c/start-all! system)
    (test-result "throws on failure" false "did not throw")
    (catch :default e
      (test-result "throws on failure" true)
      (test-result "error has failed component"
                   (= :test/fail-start (:failed-component (ex-data e))))
      (test-result "error has started components"
                   (= [:test/sys-a] (:started-components (ex-data e))))
      ;; Check rollback happened
      (test-result "rolled back comp-a" (c/stopped? comp-a)))))

;;; Summary
(println "\n=== Results ===")
(println (str "Passed: " @tests-passed))
(println (str "Failed: " @tests-failed))
(println (str "Total:  " (+ @tests-passed @tests-failed)))

(if (zero? @tests-failed)
  (do (println "\nAll tests passed!")
      (.exit process 0))
  (do (println "\nSome tests failed!")
      (.exit process 1)))
