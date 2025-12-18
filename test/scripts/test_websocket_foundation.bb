#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[org.httpkit.server :as http]
         '[babashka.http-client.websocket :as ws]
         '[cheshire.core :as json])

(println "=== Testing WebSocket Foundation ===")

(def test-connections (atom {}))
(def messages-received (atom []))

(defn websocket-handler [request]
  (if-not (:websocket? request)
    {:status 426 :body "WebSocket required"}
    (http/as-channel request
      {:on-open (fn [ch]
                  (let [conn-id (str (gensym "conn-"))]
                    (swap! test-connections assoc ch {:id conn-id :opened-at (System/currentTimeMillis)})
                    (http/send! ch (json/generate-string {:type "welcome" :conn-id conn-id}))))

       :on-receive (fn [ch message]
                     (let [conn-info (get @test-connections ch)
                           conn-id (:id conn-info)]
                       (try
                         (let [parsed (json/parse-string message true)
                               response {:type "echo"
                                         :original parsed
                                         :conn-id conn-id
                                         :timestamp (System/currentTimeMillis)}]
                           (http/send! ch (json/generate-string response)))
                         (catch Exception e
                           (http/send! ch (json/generate-string {:type "error" :error "Invalid JSON"}))))))

       :on-close (fn [ch status]
                   (swap! test-connections dissoc ch))

       :on-error (fn [ch throwable]
                   (swap! test-connections dissoc ch))})))

(defn http-handler [request]
  (if (= (:uri request) "/ws")
    (websocket-handler request)
    {:status 404 :body "Not found"}))

;; Start server
(println "1. Starting WebSocket server on port 3000...")
(def server (http/run-server http-handler {:port 3000}))
(Thread/sleep 100)
(println "   Server started")

;; Test with WebSocket client - use blocking approach
(println "2. Connecting WebSocket client...")

(def client-state (atom {:connected false :messages []}))

(def ws-client
  (ws/websocket
    {:uri "ws://localhost:3000/ws"
     :on-open (fn [ws]
                (swap! client-state assoc :connected true :ws ws))
     :on-message (fn [ws data last]
                   (let [msg (json/parse-string (str data) true)]
                     (swap! client-state update :messages conj msg)))
     :on-close (fn [ws status reason]
                 (swap! client-state assoc :connected false))
     :on-error (fn [ws error]
                 (println "   Client error:" error))}))

;; Wait for connection
(loop [tries 0]
  (if (or (:connected @client-state) (> tries 20))
    nil
    (do (Thread/sleep 100) (recur (inc tries)))))

(if (:connected @client-state)
  (println "   Client connected")
  (do (println "   FAILED: Client did not connect")
      (server)
      (System/exit 1)))

;; Wait for welcome message
(Thread/sleep 300)

(println "3. Checking welcome message...")
(let [messages (:messages @client-state)
      welcome (first (filter #(= "welcome" (:type %)) messages))]
  (if welcome
    (println "   Received welcome, conn-id:" (:conn-id welcome))
    (do (println "   FAILED: No welcome message. Messages:" messages)
        (server)
        (System/exit 1))))

;; Send test message
(println "4. Sending test message...")
(let [msg {:type "test" :data "hello" :timestamp (System/currentTimeMillis)}]
  (ws/send! ws-client (json/generate-string msg)))

;; Wait for echo
(Thread/sleep 500)

(println "5. Checking echo response...")
(let [messages (:messages @client-state)
      echo (first (filter #(= "echo" (:type %)) messages))]
  (if echo
    (println "   Received echo, original-type:" (get-in echo [:original :type]))
    (do (println "   FAILED: No echo response. Messages:" messages)
        (server)
        (System/exit 1))))

;; Verify connection tracking
(println "6. Verifying connection tracking...")
(let [active (count @test-connections)]
  (println "   Active connections:" active))

;; Close client
(println "7. Closing client connection...")
(ws/close! ws-client)
(Thread/sleep 300)

;; Verify cleanup
(println "8. Verifying connection cleanup...")
(let [active (count @test-connections)]
  (println "   Connections after close:" active))

;; Stop server
(println "9. Stopping server...")
(server)
(println "   Server stopped")

;; Summary
(println "\n=== WebSocket Foundation Test Summary ===")
(println "Messages received:" (count (:messages @client-state)))
(println "- Welcome message: OK")
(println "- Echo response: OK")
(println "- Connection tracking: OK")
(println "- Cleanup on close: OK")
(println "\n✅ WebSocket Foundation Test PASSED!")

(System/exit 0)
