#!/usr/bin/env bb
;;
;; Test: BB Client <-> sente-lite BB Server
;; Tests the complete wire format flow in a single process.
;;
;; This validates:
;; - Handshake (:chsk/handshake)
;; - Ping/Pong (:chsk/ws-ping, :chsk/ws-pong)
;; - Subscribe/Publish (:sente-lite/subscribe, :sente-lite/publish)
;; - Echo responses
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn]
         '[sente-lite.server :as server]
         '[taoensso.trove :as trove])

(println "=== Test: BB Client <-> sente-lite BB Server ===")
(println)

;; Event IDs
(def event-handshake :chsk/handshake)
(def event-ws-ping :chsk/ws-ping)
(def event-ws-pong :chsk/ws-pong)
(def event-subscribe :sente-lite/subscribe)
(def event-subscribed :sente-lite/subscribed)
(def event-publish :sente-lite/publish)
(def event-channel-msg :sente-lite/channel-msg)

;; Test state
(def test-results (atom {:passed 0 :failed 0 :tests []}))
(def received-messages (atom []))
(def handshake-received (promise))
(def subscribed-received (promise))
(def channel-msg-received (promise))
(def echo-received (promise))

(defn record-test! [name passed? details]
  (swap! test-results update (if passed? :passed :failed) inc)
  (swap! test-results update :tests conj {:name name :passed passed? :details details})
  (println (if passed? "  ✅" "  ❌") name (when details (str "- " details))))

;; Parse message
;; IMPORTANT: Babashka's babashka.http-client.websocket passes a java.nio.HeapCharBuffer
;; to on-message, NOT a String like JVM's org.java-websocket/Java-WebSocket does.
;; Must convert with (str raw-data) before parsing.
(defn parse-message [raw-data]
  (try
    (let [data-str (str raw-data)  ; CharBuffer → String (required for BB websocket)
          parsed (edn/read-string data-str)]
      (if (vector? parsed)
        {:event-id (first parsed)
         :data (second parsed)
         :raw data-str}
        {:error :invalid-format :raw data-str}))
    (catch Exception e
      {:error :parse-failed :raw (str raw-data) :message (.getMessage e)})))

;; WebSocket handlers
(def ws-opened (promise))

(defn on-open [ws]
  (deliver ws-opened true))

(defn on-message [ws raw-data last?]
  (let [parsed (parse-message raw-data)]
    (swap! received-messages conj parsed)
    (trove/log! {:level :debug :id :test/msg-recv :data {:event-id (:event-id parsed)}})
    
    (case (:event-id parsed)
      :chsk/handshake
      (deliver handshake-received parsed)
      
      :chsk/ws-ping
      (do
        (trove/log! {:level :debug :id :test/auto-pong :data {}})
        (ws/send! ws (pr-str [event-ws-pong])))
      
      :sente-lite/subscribed
      (deliver subscribed-received parsed)
      
      :sente-lite/channel-msg
      (deliver channel-msg-received parsed)
      
      :sente-lite/echo
      (deliver echo-received parsed)
      
      ;; default - just log
      (trove/log! {:level :trace :id :test/other-msg :data parsed}))))

(defn run-tests []
  ;; Start server with ephemeral port
  (println "1. Starting sente-lite server...")
  (server/start-server! {:port 0 :wire-format :edn :heartbeat {:enabled false}})
  (Thread/sleep 500)
  (def server-port (server/get-server-port))
  (println "   Server started on port" server-port)
  (record-test! "Server started" (some? server-port) (str "port=" server-port))
  
  ;; Connect client
  (println)
  (println "2. Connecting client...")
  (def client-id (str "bb-test-" (System/currentTimeMillis)))
  (def ws-url (str "ws://localhost:" server-port "/?client-id=" client-id))
  
  (def ws-client
    (ws/websocket {:uri ws-url
                   :on-open on-open
                   :on-message on-message
                   :on-close (fn [ws code reason] (trove/log! {:level :debug :id :test/closed :data {:code code}}))
                   :on-error (fn [ws err] (trove/log! {:level :error :id :test/error :data {:error (.getMessage err)}}))}))
  
  ;; Wait for connection
  (let [opened (deref ws-opened 3000 nil)]
    (record-test! "WebSocket connected" (boolean opened) nil))
  
  ;; Test handshake
  (println)
  (println "3. Testing handshake...")
  (let [handshake (deref handshake-received 3000 nil)]
    (if handshake
      (let [uid (first (:data handshake))]
        (record-test! "Received :chsk/handshake" true (str "uid=" uid))
        (record-test! "Handshake has uid" (some? uid) nil)
        (record-test! "Handshake format is event vector" (vector? (:data handshake)) nil))
      (record-test! "Received :chsk/handshake" false "timeout")))
  
  ;; Test echo
  (println)
  (println "4. Testing echo...")
  (ws/send! ws-client (pr-str [:test/echo {:msg "Hello!" :timestamp (System/currentTimeMillis)}]))
  (let [echo (deref echo-received 2000 nil)]
    (if echo
      (do
        (record-test! "Received :sente-lite/echo" true nil)
        (record-test! "Echo contains original event-id" 
                      (= :test/echo (get-in echo [:data :original-event-id])) nil))
      (record-test! "Received :sente-lite/echo" false "timeout")))
  
  ;; Test subscribe
  (println)
  (println "5. Testing subscribe...")
  (ws/send! ws-client (pr-str [event-subscribe {:channel-id "test-channel"}]))
  (let [subscribed (deref subscribed-received 2000 nil)]
    (if subscribed
      (do
        (record-test! "Received :sente-lite/subscribed" true nil)
        (record-test! "Subscription success" 
                      (get-in subscribed [:data :success]) 
                      (str "channel=" (get-in subscribed [:data :channel-id]))))
      (record-test! "Received :sente-lite/subscribed" false "timeout")))
  
  ;; Test publish (to self since we're the only subscriber)
  (println)
  (println "6. Testing publish...")
  (ws/send! ws-client (pr-str [event-publish {:channel-id "test-channel" 
                                               :data {:msg "Published message!"}}]))
  (let [channel-msg (deref channel-msg-received 2000 nil)]
    (if channel-msg
      (do
        (record-test! "Received :sente-lite/channel-msg" true nil)
        (record-test! "Channel message has correct channel" 
                      (= "test-channel" (get-in channel-msg [:data :channel-id])) nil)
        (record-test! "Channel message has data" 
                      (= "Published message!" (get-in channel-msg [:data :data :msg])) nil))
      (record-test! "Received :sente-lite/channel-msg" false "timeout")))
  
  ;; Test ping/pong (manual)
  (println)
  (println "7. Testing manual ping...")
  (ws/send! ws-client (pr-str [event-ws-ping]))
  (Thread/sleep 500)
  ;; Server should respond with pong - check received messages
  (let [pong-found (some #(= :chsk/ws-pong (:event-id %)) @received-messages)]
    (record-test! "Received :chsk/ws-pong in response to ping" (boolean pong-found) nil))
  
  ;; Cleanup
  (println)
  (println "8. Cleanup...")
  (ws/close! ws-client)
  (Thread/sleep 200)
  (server/stop-server!)
  (record-test! "Server stopped" true nil)
  
  ;; Summary
  (println)
  (println "=== Test Summary ===")
  (let [{:keys [passed failed tests]} @test-results]
    (println "Passed:" passed)
    (println "Failed:" failed)
    (println)
    (when (seq (filter #(not (:passed %)) tests))
      (println "Failed tests:")
      (doseq [t (filter #(not (:passed %)) tests)]
        (println "  -" (:name t) (when (:details t) (str ": " (:details t))))))
    
    (if (zero? failed)
      (do (println "✅ All tests passed!")
          (System/exit 0))
      (do (println "❌ Some tests failed!")
          (System/exit 1)))))

(run-tests)
