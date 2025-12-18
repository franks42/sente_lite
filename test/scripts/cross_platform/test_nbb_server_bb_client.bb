#!/usr/bin/env bb
;;
;; Cross-Platform Test: nbb Server ↔ BB Client
;;
;; Tests sente-lite nbb server (server_nbb.cljs) with BB client (client_bb.clj)
;; Validates wire format interoperability between platforms.
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[babashka.process :as p]
         '[babashka.fs :as fs]
         '[sente-lite.client-bb :as client]
         '[clojure.java.io :as io])

(println "=== Cross-Platform Test: nbb Server ↔ BB Client ===")
(println)

(def port 9879)
(def port-file "/tmp/sente-lite-nbb-server-port.txt")
(def script-dir (-> *file* fs/parent str))

;; Copy nbb server script to test/nbb directory so it can find ws module
(def nbb-script-content (slurp (str script-dir "/nbb_server_script.cljs")))
(spit "test/nbb/temp_server.cljs" nbb-script-content)

;; Start nbb server in background
(println "[nbb-server] Starting nbb server on port" port "...")
(def nbb-server-process
  (p/process {:dir "test/nbb"
              :out :inherit
              :err :inherit}
             "nbb" "--classpath" "../../src" 
             "temp_server.cljs" 
             (str port)))

;; Wait for server to start and write port file
(println "[nbb-server] Waiting for server to start...")
(Thread/sleep 2000)

;; Verify server is running
(when-not (.exists (io/file port-file))
  (println "[ERROR] nbb server failed to start - port file not found")
  (p/destroy nbb-server-process)
  (System/exit 1))

(println "[nbb-server] Server started on port" port)
(println)

;; Test state
(def test-results (atom {:handshake nil :echo nil :subscribed nil :channel-msg nil}))
(def handshake-promise (promise))
(def echo-promise (promise))
(def subscribed-promise (promise))
(def channel-msg-promise (promise))

;; Create BB client
(println "[bb-client] Connecting to nbb server...")
(def client-id
  (client/make-client!
    {:url (str "ws://localhost:" port "/")
     :auto-reconnect? false
     :on-open (fn [uid]
                (println "[bb-client] Connected with uid:" uid)
                (swap! test-results assoc :handshake uid)
                (deliver handshake-promise uid))
     :on-message (fn [event-id data]
                   (println "[bb-client] Received:" event-id)
                   (case event-id
                     :sente-lite/echo 
                     (do (swap! test-results assoc :echo data)
                         (deliver echo-promise data))
                     :sente-lite/subscribed 
                     (do (swap! test-results assoc :subscribed data)
                         (deliver subscribed-promise data))
                     :sente-lite/channel-msg 
                     (do (swap! test-results assoc :channel-msg data)
                         (deliver channel-msg-promise data))
                     nil))
     :on-close (fn [code reason]
                 (println "[bb-client] Disconnected:" code))}))

;; Wait for handshake
(println "[bb-client] Waiting for handshake...")
(def uid (deref handshake-promise 5000 nil))
(when-not uid
  (println "[ERROR] Handshake timeout")
  (client/close! client-id)
  (p/destroy nbb-server-process)
  (io/delete-file port-file true)
  (System/exit 1))

;; Send echo
(println "[bb-client] Sending echo...")
(client/send! client-id [:test/echo {:msg "Hello from BB client!"}])
(deref echo-promise 3000 nil)

;; Subscribe
(println "[bb-client] Subscribing to channel...")
(client/subscribe! client-id "cross-platform-channel")
(deref subscribed-promise 3000 nil)

;; Publish (to self)
(println "[bb-client] Publishing message...")
(client/publish! client-id "cross-platform-channel" {:msg "Published from BB!"})
(deref channel-msg-promise 3000 nil)

;; Results
(println)
(println "=== BB Client Results ===")
(println "Handshake:" (if (:handshake @test-results) "✓" "✗") (:handshake @test-results))
(println "Echo:" (if (:echo @test-results) "✓" "✗"))
(println "Subscribed:" (if (:subscribed @test-results) "✓" "✗"))
(println "Channel-msg:" (if (:channel-msg @test-results) "✓" "✗"))
(println)

(def passed (count (filter some? (vals @test-results))))
(println "Passed:" passed "/4")

;; Cleanup
(println)
(println "[cleanup] Closing client and stopping nbb server...")
(client/close! client-id)
(Thread/sleep 500)
(p/destroy nbb-server-process)
(io/delete-file port-file true)
(io/delete-file "test/nbb/temp_server.cljs" true)

(if (= 4 passed)
  (do (println "✅ All cross-platform tests passed!")
      (System/exit 0))
  (do (println "❌ Some tests failed")
      (System/exit 1)))
