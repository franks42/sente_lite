#!/usr/bin/env bb
;;
;; Cross-Platform Test: BB Server ↔ nbb Client
;;
;; Tests sente-lite BB server (server.cljc) with nbb client (client_scittle.cljs)
;; Validates wire format interoperability between platforms.
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[sente-lite.server :as server]
         '[clojure.java.io :as io])

(println "=== Cross-Platform Test: BB Server ↔ nbb Client ===")
(println)

;; Start BB server
(println "[server] Starting sente-lite BB server...")
(server/start-server! {:port 0 :wire-format :edn :heartbeat {:enabled false}})
(def port (server/get-server-port))
(println "[server] BB server started on port" port)

;; Write port to temp file for nbb client to read
(def port-file "/tmp/sente-lite-cross-platform-port.txt")
(spit port-file (str port))

;; Create nbb client test script inline
(def nbb-test-script "
(ns cross-platform-test
  (:require [sente-lite.client-scittle :as client]))

;; Read port from file via node fs
(def fs (js/require \"fs\"))
(def port-str (.readFileSync fs \"/tmp/sente-lite-cross-platform-port.txt\" \"utf8\"))
(def port (js/parseInt (.trim port-str) 10))

(println \"[nbb-client] Connecting to BB server on port\" port)

(def results (atom {:handshake nil :echo nil :subscribed nil :channel-msg nil}))
(def done-promise (js/Promise. (fn [resolve reject]
                                  (def resolve-fn resolve))))

(def client-id
  (client/make-client!
    {:url (str \"ws://localhost:\" port \"/\")
     :auto-reconnect? false
     :on-open (fn [uid]
                (println \"[nbb-client] Connected with uid:\" uid)
                (swap! results assoc :handshake uid)
                ;; Send echo test
                (client/send! client-id [:test/echo {:msg \"Hello from nbb!\"}])
                ;; Subscribe
                (client/subscribe! client-id \"cross-platform-channel\"))
     :on-message (fn [event-id data]
                   (println \"[nbb-client] Received:\" event-id)
                   (case event-id
                     :sente-lite/echo 
                     (do (swap! results assoc :echo data)
                         ;; Publish after echo
                         (client/publish! client-id \"cross-platform-channel\" {:msg \"Published from nbb!\"}))
                     :sente-lite/subscribed 
                     (swap! results assoc :subscribed data)
                     :sente-lite/channel-msg 
                     (do (swap! results assoc :channel-msg data)
                         ;; All tests done
                         (js/setTimeout #(resolve-fn @results) 500))
                     nil))
     :on-close (fn [event]
                 (println \"[nbb-client] Disconnected\"))}))

;; Timeout after 10 seconds
(js/setTimeout 
  (fn []
    (println \"[nbb-client] Timeout - results so far:\" @results)
    (resolve-fn @results))
  10000)

;; Wait for completion and print results
(.then done-promise
  (fn [results]
    (println)
    (println \"=== nbb Client Results ===\")
    (println \"Handshake:\" (if (:handshake results) \"✓\" \"✗\") (:handshake results))
    (println \"Echo:\" (if (:echo results) \"✓\" \"✗\"))
    (println \"Subscribed:\" (if (:subscribed results) \"✓\" \"✗\"))
    (println \"Channel-msg:\" (if (:channel-msg results) \"✓\" \"✗\"))
    (println)
    (let [passed (count (filter some? (vals results)))]
      (println \"Passed:\" passed \"/4\")
      (client/close! client-id)
      (if (= 4 passed)
        (do (println \"✅ All cross-platform tests passed!\")
            (js/process.exit 0))
        (do (println \"❌ Some tests failed\")
            (js/process.exit 1))))))
")

;; Write the nbb test script
(def nbb-script-file "/tmp/sente-lite-nbb-client-test.cljs")
(spit nbb-script-file nbb-test-script)

;; Run nbb client
(println)
(println "[nbb-client] Starting nbb client...")
(def nbb-result
  (p/shell {:dir "test/nbb"
            :out :string
            :err :string
            :continue true}
           "nbb" "--classpath" "../../src" nbb-script-file))

(println (:out nbb-result))
(when (seq (:err nbb-result))
  (println "[nbb-client] stderr:" (:err nbb-result)))

;; Cleanup
(println)
(println "[cleanup] Stopping BB server...")
(server/stop-server!)
(io/delete-file port-file true)
(io/delete-file nbb-script-file true)

;; Exit with nbb's exit code
(System/exit (:exit nbb-result))
