(ns test-v2-nbb-client
  "Test nbb client connecting to sente-lite BB server.
   Run the server first: bb -e '(require [sente-lite.server :as s]) (s/start-server! {:port 8080})'"
  (:require ["ws" :as ws-mod]
            [clojure.edn :as edn]))

(println "=== Test: nbb v2 Client -> sente-lite BB Server ===")
(println)

;; Get WebSocket constructor from ws module
(def WebSocket (.-WebSocket ws-mod))

;; v2 event IDs
(def event-handshake :chsk/handshake)
(def event-ws-ping :chsk/ws-ping)
(def event-ws-pong :chsk/ws-pong)
(def event-subscribe :sente-lite/subscribe)

;; Test state
(def test-results (atom {:passed 0 :failed 0}))
(def received-messages (atom []))

(defn record-test! [name passed? details]
  (swap! test-results update (if passed? :passed :failed) inc)
  (println (if passed? "  ✓" "  ✗") name (when details (str "- " details))))

(defn parse-message [raw-data]
  (try
    (let [data-str (str raw-data)
          parsed (edn/read-string data-str)]
      (if (vector? parsed)
        {:event-id (first parsed)
         :data (second parsed)}
        {:error :invalid-format}))
    (catch :default e
      {:error :parse-failed :message (.-message e)})))

;; Server config - assumes server running on port 9090
(def server-url "ws://localhost:9090/")

(println "1. Connecting to" server-url)
(println "   (Make sure sente-lite server is running!)")
(println)

(def client (WebSocket. server-url))

;; Track promises for async testing
(def handshake-promise (js/Promise.
                         (fn [resolve reject]
                           (js/setTimeout #(reject "timeout") 5000)
                           (set! (.-_resolve-handshake client) resolve))))

(def echo-promise (js/Promise.
                    (fn [resolve reject]
                      (js/setTimeout #(reject "timeout") 5000)
                      (set! (.-_resolve-echo client) resolve))))

(.on client "open"
  (fn []
    (println "2. WebSocket connected!")
    (record-test! "WebSocket connected" true nil)))

(.on client "message"
  (fn [raw-data]
    (let [parsed (parse-message raw-data)]
      (swap! received-messages conj parsed)
      (println "   Received:" (:event-id parsed))
      
      (case (:event-id parsed)
        :chsk/handshake
        (do
          (println "3. Handshake received, uid:" (first (:data parsed)))
          (record-test! "Handshake received" true (str "uid=" (first (:data parsed))))
          (when-let [resolve (.-_resolve-handshake client)]
            (resolve parsed)))
        
        :chsk/ws-ping
        (do
          (println "   Auto-responding with pong")
          (.send client (pr-str [event-ws-pong])))
        
        :sente-lite/echo
        (do
          (println "4. Echo received!")
          (record-test! "Echo received" true nil)
          (when-let [resolve (.-_resolve-echo client)]
            (resolve parsed)))
        
        :sente-lite/subscribed
        (do
          (println "5. Subscribed confirmation received")
          (record-test! "Subscribe confirmation" (get-in parsed [:data :success]) nil))
        
        nil))))

(.on client "close"
  (fn [code reason]
    (println "   Closed:" code)))

(.on client "error"
  (fn [err]
    (println "   Error:" (.-message err))
    (record-test! "No connection errors" false (.-message err))))

;; Run test sequence after handshake
(-> handshake-promise
    (.then (fn [_]
             (println)
             (println "Sending test echo...")
             (.send client (pr-str [:test/echo {:msg "Hello from nbb!"}]))
             echo-promise))
    (.then (fn [_]
             (println)
             (println "Sending subscribe...")
             (.send client (pr-str [event-subscribe {:channel-id "test-channel"}]))
             ;; Wait a bit for subscribe response
             (js/Promise. (fn [resolve] (js/setTimeout resolve 1000)))))
    (.then (fn [_]
             (println)
             (println "=== Test Summary ===")
             (println "Passed:" (:passed @test-results))
             (println "Failed:" (:failed @test-results))
             (println "Messages received:" (count @received-messages))
             (.close client)
             (println)
             (if (zero? (:failed @test-results))
               (println "All tests passed!")
               (println "Some tests failed!"))))
    (.catch (fn [err]
              (println "Test failed:" err)
              (.close client))))
