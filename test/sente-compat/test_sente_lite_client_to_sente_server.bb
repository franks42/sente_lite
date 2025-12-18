#!/usr/bin/env bb
;;
;; Test: sente-lite (BB) client connecting to real Sente (JVM) server
;; This validates that our v2 client implementation is Sente-compatible.
;;
;; Usage:
;;   1. Start the Sente server: clj -M:server 8090
;;   2. Run this test: bb test_sente_lite_client_to_sente_server.bb
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "../../src")

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn]
         '[taoensso.trove :as trove])

(println "=== Test: sente-lite BB Client -> Real Sente JVM Server ===")
(println)

;; Configuration
(def server-host "localhost")
(def server-port 8090)
(def client-id (str "bb-client-" (System/currentTimeMillis)))

;; v2 event IDs (matching client_scittle.cljs)
(def event-handshake :chsk/handshake)
(def event-ws-ping :chsk/ws-ping)
(def event-ws-pong :chsk/ws-pong)

;; State
(def received-messages (atom []))
(def handshake-received (promise))
(def test-echo-received (promise))

;; Parse v2 message
(defn parse-message [raw-data]
  (try
    (let [parsed (edn/read-string raw-data)]
      (if (vector? parsed)
        {:event-id (first parsed)
         :data (second parsed)}
        {:error :invalid-format :raw raw-data}))
    (catch Exception e
      {:error :parse-failed :raw raw-data :message (.getMessage e)})))

;; WebSocket handlers
(defn on-open [ws]
  (println "[OPEN] WebSocket connected"))

(defn on-message [ws raw-data last?]
  (let [parsed (parse-message raw-data)]
    (swap! received-messages conj parsed)
    (println "[RECV]" (pr-str parsed))
    
    (cond
      ;; Handle handshake
      (= (:event-id parsed) event-handshake)
      (do
        (println "[HANDSHAKE] Received handshake, UID:" (first (:data parsed)))
        (deliver handshake-received parsed))
      
      ;; Handle ping - auto-respond with pong
      (= (:event-id parsed) event-ws-ping)
      (do
        (println "[PING] Auto-responding with pong")
        (ws/send! ws (pr-str [event-ws-pong])))
      
      ;; Handle echo response
      (= (:event-id parsed) :sente-lite/echo)
      (do
        (println "[ECHO] Received echo response")
        (deliver test-echo-received parsed)))))

(defn on-close [ws status-code reason]
  (println "[CLOSE] WebSocket closed:" status-code reason))

(defn on-error [ws error]
  (println "[ERROR]" (.getMessage error)))

;; Main test
(defn run-test []
  (let [ws-url (str "ws://" server-host ":" server-port "/chsk?client-id=" client-id)]
    (println "1. Connecting to Sente server at" ws-url)
    (println)
    
    (let [ws (ws/websocket {:uri ws-url
                            :headers {"Origin" (str "http://" server-host ":" server-port)}
                            :on-open on-open
                            :on-message on-message
                            :on-close on-close
                            :on-error on-error})]
      
      ;; Wait for handshake
      (println "2. Waiting for handshake...")
      (let [handshake (deref handshake-received 5000 nil)]
        (if handshake
          (do
            (println "   Handshake received!")
            (println "   UID:" (first (:data handshake)))
            (println))
          (do
            (println "   ERROR: No handshake received within 5 seconds!")
            (ws/close! ws)
            (System/exit 1))))
      
      ;; Send test event
      (println "3. Sending test event: [:test/echo {:msg \"Hello from BB!\"}]")
      (ws/send! ws (pr-str [:test/echo {:msg "Hello from BB!" :timestamp (System/currentTimeMillis)}]))
      (Thread/sleep 1000)
      
      ;; Send ping
      (println)
      (println "4. Sending ping")
      (ws/send! ws (pr-str [event-ws-ping]))
      (Thread/sleep 1000)
      
      ;; Send event with callback format
      (println)
      (println "5. Sending event with callback: [[:test/echo {:data \"with cb\"}] \"cb-123\"]")
      (ws/send! ws (pr-str [[:test/echo {:data "with callback"}] "cb-uuid-123"]))
      (Thread/sleep 1000)
      
      ;; Summary
      (println)
      (println "=== Test Summary ===")
      (println "Messages received:" (count @received-messages))
      (doseq [msg @received-messages]
        (println "  -" (:event-id msg) (when (:data msg) (pr-str (:data msg)))))
      
      ;; Close
      (println)
      (println "6. Closing connection...")
      (ws/close! ws)
      (Thread/sleep 500)
      (println "Done!"))))

;; Check if server is running
(println "Checking if Sente server is running on port" server-port "...")
(try
  (let [response (slurp (str "http://" server-host ":" server-port "/"))]
    (if (re-find #"Sente Test Server" response)
      (do
        (println "Sente server is running!")
        (println)
        (run-test))
      (do
        (println "ERROR: Server is running but doesn't look like Sente test server")
        (System/exit 1))))
  (catch Exception e
    (println)
    (println "ERROR: Cannot connect to Sente server on port" server-port)
    (println "Please start the server first:")
    (println "  cd test/sente-compat && clj -M:server" server-port)
    (System/exit 1)))
