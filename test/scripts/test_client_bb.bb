#!/usr/bin/env bb
;;
;; Test: sente-lite BB Client (client_bb.clj) <-> sente-lite BB Server
;; Tests the complete wire format using the official BB client module.
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[sente-lite.server :as server]
         '[sente-lite.client-bb :as client]
         '[taoensso.trove :as trove])

(println "=== Test: sente-lite BB Client Module <-> BB Server ===")
(println)

;; Test state
(def test-results (atom {:passed 0 :failed 0 :tests []}))
(def handshake-uid (promise))
(def echo-received (promise))
(def subscribed-received (promise))
(def channel-msg-received (promise))

(defn record-test! [name passed? details]
  (swap! test-results update (if passed? :passed :failed) inc)
  (swap! test-results update :tests conj {:name name :passed passed? :details details})
  (println (if passed? "  ✓" "  ✗") name (when details (str "- " details))))

(defn run-tests []
  ;; Start server
  (println "1. Starting sente-lite server...")
  (server/start-server! {:port 0 :wire-format :edn :heartbeat {:enabled false}})
  (Thread/sleep 500)
  (def server-port (server/get-server-port))
  (println "   Server started on port" server-port)
  (record-test! "Server started" (some? server-port) (str "port=" server-port))

  ;; Create client with callbacks
  (println)
  (println "2. Creating BB client...")
  (def client-id
    (client/make-client!
     {:url (str "ws://localhost:" server-port "/")
      :auto-reconnect? false
      :on-open (fn [uid]
                 (println "   on-open called with uid:" uid)
                 (deliver handshake-uid uid))
      :on-message (fn [event-id data]
                    (println "   on-message:" event-id)
                    (case event-id
                      :sente-lite/echo (deliver echo-received {:event-id event-id :data data})
                      :sente-lite/subscribed (deliver subscribed-received {:event-id event-id :data data})
                      :sente-lite/channel-msg (deliver channel-msg-received {:event-id event-id :data data})
                      nil))
      :on-close (fn [code reason]
                  (println "   on-close:" code reason))}))

  (println "   Client created:" client-id)
  (Thread/sleep 500)

  ;; Test handshake
  (println)
  (println "3. Testing handshake...")
  (let [uid (deref handshake-uid 3000 nil)]
    (record-test! "Handshake received" (some? uid) (str "uid=" uid))
    (record-test! "get-uid returns uid" (= uid (client/get-uid client-id)) nil)
    (record-test! "Status is :connected" (= :connected (client/get-status client-id)) nil))

  ;; Test echo
  (println)
  (println "4. Testing echo...")
  (client/send! client-id [:test/echo {:msg "Hello from BB client!"}])
  (let [echo (deref echo-received 2000 nil)]
    (record-test! "Echo received" (some? echo) nil)
    (when echo
      (record-test! "Echo has original event-id" 
                    (= :test/echo (get-in echo [:data :original-event-id])) nil)))

  ;; Test subscribe
  (println)
  (println "5. Testing subscribe...")
  (client/subscribe! client-id "test-channel")
  (let [subscribed (deref subscribed-received 2000 nil)]
    (record-test! "Subscribed confirmation received" (some? subscribed) nil)
    (when subscribed
      (record-test! "Subscription successful" 
                    (get-in subscribed [:data :success]) nil)))

  ;; Test publish
  (println)
  (println "6. Testing publish...")
  (client/publish! client-id "test-channel" {:msg "Published from BB!"})
  (let [channel-msg (deref channel-msg-received 2000 nil)]
    (record-test! "Channel message received" (some? channel-msg) nil)
    (when channel-msg
      (record-test! "Message has correct data" 
                    (= "Published from BB!" (get-in channel-msg [:data :data :msg])) nil)))

  ;; Test stats
  (println)
  (println "7. Testing stats...")
  (let [stats (client/get-stats client-id)]
    (record-test! "Stats available" (some? stats) nil)
    (record-test! "Messages sent > 0" (> (:messages-sent stats) 0) 
                  (str "sent=" (:messages-sent stats)))
    (record-test! "Messages received > 0" (> (:messages-received stats) 0)
                  (str "recv=" (:messages-received stats))))

  ;; Cleanup
  (println)
  (println "8. Cleanup...")
  (client/close! client-id)
  (Thread/sleep 200)
  (record-test! "Client closed" (nil? (client/get-status client-id)) nil)
  (server/stop-server!)
  (record-test! "Server stopped" true nil)

  ;; Summary
  (println)
  (println "=== Test Summary ===")
  (let [{:keys [passed failed tests]} @test-results]
    (println "Passed:" passed)
    (println "Failed:" failed)

    (when (seq (filter #(not (:passed %)) tests))
      (println)
      (println "Failed tests:")
      (doseq [t (filter #(not (:passed %)) tests)]
        (println "  -" (:name t) (when (:details t) (str ": " (:details t))))))

    (println)
    (if (zero? failed)
      (do (println "All tests passed!")
          (System/exit 0))
      (do (println "Some tests failed!")
          (System/exit 1)))))

(run-tests)
