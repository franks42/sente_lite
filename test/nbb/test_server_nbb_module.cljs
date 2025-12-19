(ns test-server-nbb-module
  "Test the server_nbb.cljs module with client_scittle.cljs"
  (:require [sente-lite.server-nbb :as server]
            [sente-lite.client-scittle :as client]))

(println "=== Test: server_nbb.cljs + client_scittle.cljs ===")
(println)

;; Test state
(def test-results (atom {:passed 0 :failed 0}))
(def handshake-uid (atom nil))
(def echo-received (atom nil))
(def subscribed-received (atom nil))
(def channel-msg-received (atom nil))
(def client-id (atom nil))

(defn record! [name pass? details]
  (swap! test-results update (if pass? :passed :failed) inc)
  (println (if pass? "  ✓" "  ✗") name (when details (str "- " details))))

;; Start server
(println "1. Starting nbb server...")
(def server-instance (server/start-server! {:port 9093
                                            :heartbeat {:enabled false}
                                            :wrap-recv-evs? true}))
(println "   Server started on port" (server/get-server-port))

;; Wait for server to be ready
(js/setTimeout
 (fn []
   ;; Create client
   (println)
   (println "2. Creating client...")
   (reset! client-id
           (client/make-client!
            {:url "ws://localhost:9093/"
             :auto-reconnect? false
             :on-open (fn [uid]
                        (println "   on-open uid:" uid)
                        (reset! handshake-uid uid))
             :on-message (fn [event-id data]
                           (println "   on-message:" event-id)
                           (case event-id
                             :sente-lite/echo (reset! echo-received data)
                             :sente-lite/subscribed (reset! subscribed-received data)
                             :sente-lite/channel-msg (reset! channel-msg-received data)
                             nil))
             :on-close (fn [_] nil)}))
   (println "   Client:" @client-id)

    ;; Run tests after handshake
   (js/setTimeout
    (fn []
      (println)
      (println "3. Running tests...")

        ;; Handshake tests
      (record! "Handshake received" (some? @handshake-uid) (str "uid=" @handshake-uid))
      (record! "get-uid works" (= @handshake-uid (client/get-uid @client-id)) nil)
      (record! "Status is :connected" (= :connected (client/get-status @client-id)) nil)

        ;; Server stats
      (let [stats (server/get-server-stats)]
        (record! "Server stats available" (some? stats) nil)
        (record! "Server shows 1 connection" (= 1 (get-in stats [:connections :active])) nil))

        ;; Echo test
      (client/send! @client-id [:test/echo {:msg "Hello nbb server!"}])

      (js/setTimeout
       (fn []
         (record! "Echo received" (some? @echo-received) nil)
         (record! "Echo has original event" (= :test/echo (:original-event-id @echo-received)) nil)

            ;; Subscribe test
         (client/subscribe! @client-id "nbb-channel")

         (js/setTimeout
          (fn []
            (record! "Subscribe confirmation" (get @subscribed-received :success) nil)

                ;; Publish test (to self)
            (client/publish! @client-id "nbb-channel" {:msg "Published!"})

            (js/setTimeout
             (fn []
               (record! "Channel message received" (some? @channel-msg-received) nil)
               (record! "Channel message has data" (= "Published!" (get-in @channel-msg-received [:data :msg])) nil)

                    ;; Final stats
               (let [client-stats (client/get-stats @client-id)]
                 (record! "Messages sent > 0" (> (:messages-sent client-stats) 0)
                          (str "sent=" (:messages-sent client-stats)))
                 (record! "Messages received > 0" (> (:messages-received client-stats) 0)
                          (str "recv=" (:messages-received client-stats))))

                    ;; Summary
               (println)
               (println "=== Summary ===")
               (println "Passed:" (:passed @test-results))
               (println "Failed:" (:failed @test-results))

                    ;; Cleanup
               (println)
               (println "4. Cleanup...")
               (client/close! @client-id)
               (record! "Client closed" (nil? (client/get-status @client-id)) nil)
               (server/stop-server!)
               (record! "Server stopped" (nil? (server/get-server-port)) nil)

               (println)
               (if (zero? (:failed @test-results))
                 (println "All nbb server module tests passed!")
                 (println "Some tests failed!")))
             500))
          500))
       500))
    1500))
 500)
