(ns test-nbb-full-stack
  "Test: nbb client (client_scittle.cljs) -> nbb server
   Full nbb stack test!"
  (:require ["ws" :as ws-mod]
            [clojure.edn :as edn]))

(println "=== Test: nbb Full Stack (Client + Server) ===")
(println)

;; ============================================================================
;; Server Setup (inline minimal server)
;; ============================================================================

(def WebSocketServer (.-WebSocketServer ws-mod))

(def event-handshake :chsk/handshake)
(def event-ws-ping :chsk/ws-ping)
(def event-ws-pong :chsk/ws-pong)
(def event-subscribe :sente-lite/subscribe)
(def event-subscribed :sente-lite/subscribed)

(def connections (atom {}))
(def conn-counter (atom 0))

(defn generate-conn-id []
  (str "conn-" (.now js/Date) "-" (swap! conn-counter inc)))

(defn send-event! [ws event]
  (.send ws (pr-str event)))

(defn parse-message [raw-data]
  (try
    (let [parsed (edn/read-string (str raw-data))]
      (if (vector? parsed)
        {:event-id (first parsed) :data (second parsed)}
        {:error :invalid-format}))
    (catch :default e {:error :parse-failed})))

(defn handle-connection [ws]
  (let [conn-id (generate-conn-id)]
    (swap! connections assoc ws {:id conn-id})
    (send-event! ws [event-handshake [conn-id nil {:sente-lite-version "2.0.0-nbb"} true]])

    (.on ws "message"
         (fn [raw-data]
           (let [{:keys [event-id data]} (parse-message raw-data)]
             (cond
               (= event-id event-ws-ping)
               (send-event! ws [event-ws-pong])

               (= event-id event-subscribe)
               (send-event! ws [event-subscribed {:channel-id (:channel-id data) :success true}])

               :else
               (send-event! ws [:sente-lite/echo {:original-event-id event-id
                                                  :original-data data
                                                  :conn-id conn-id}])))))
    (.on ws "close" #(swap! connections dissoc ws))))

(def port 9092)
(def server (WebSocketServer. #js {:port port}))
(.on server "connection" handle-connection)

(println "1. Server started on port" port)

;; ============================================================================
;; Client Test (using client_scittle.cljs)
;; ============================================================================

(require '[sente-lite.client-scittle :as client])

(def test-results (atom {:passed 0 :failed 0}))
(def handshake-uid (atom nil))
(def echo-received (atom nil))
(def subscribed-received (atom nil))

(defn record! [name pass?]
  (swap! test-results update (if pass? :passed :failed) inc)
  (println (if pass? "  ✓" "  ✗") name))

(println)
(println "2. Creating client...")

(def client-id
  (client/make-client!
   {:url (str "ws://localhost:" port "/")
    :auto-reconnect? false
    :on-open (fn [uid]
               (reset! handshake-uid uid))
    :on-message (fn [event-id data]
                  (case event-id
                    :sente-lite/echo (reset! echo-received data)
                    :sente-lite/subscribed (reset! subscribed-received data)
                    nil))
    :on-close (fn [_] nil)}))

;; Run tests after connection
(js/setTimeout
 (fn []
   (println)
   (println "3. Running tests...")

    ;; Test handshake
   (record! "Handshake received" (some? @handshake-uid))
   (record! "get-uid works" (= @handshake-uid (client/get-uid client-id)))
   (record! "Status is :connected" (= :connected (client/get-status client-id)))

    ;; Send echo
   (client/send! client-id [:test/echo {:msg "Hello nbb!"}])

   (js/setTimeout
    (fn []
      (record! "Echo received" (some? @echo-received))
      (record! "Echo has original event" (= :test/echo (:original-event-id @echo-received)))

        ;; Subscribe
      (client/subscribe! client-id "test-channel")

      (js/setTimeout
       (fn []
         (record! "Subscribe confirmation" (get @subscribed-received :success))

            ;; Summary
         (println)
         (println "=== Summary ===")
         (println "Passed:" (:passed @test-results))
         (println "Failed:" (:failed @test-results))

            ;; Cleanup
         (client/close! client-id)
         (.close server)

         (if (zero? (:failed @test-results))
           (println "All nbb full-stack tests passed!")
           (println "Some tests failed!")))
       500))
    500))
 1500)
