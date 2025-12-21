#!/usr/bin/env bb
;;
;; Test: Server :on-message callback
;; Tests that the server correctly invokes user-provided message handlers.
;;
;; This validates:
;; - Custom event handling via :on-message config option
;; - Callback receives (conn-id event-id data)
;; - Callback return value is sent back to client
;; - nil return means no response
;; - Falls back to echo when no callback provided
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn]
         '[sente-lite.server :as server])

(println "=== Test: Server :on-message callback ===")
(println)

;; Test state
(def test-results (atom {:passed 0 :failed 0 :tests []}))
(def received-messages (atom []))
(def server-received (atom []))
(def current-client (atom nil))

(defn record-test! [name passed? details]
  (swap! test-results update (if passed? :passed :failed) inc)
  (swap! test-results update :tests conj {:name name :passed passed? :details details})
  (println (if passed? "  ✅" "  ❌") name (when details (str "- " details))))

;; Parse message from WebSocket
(defn parse-message [raw-data]
  (try
    (let [data-str (str raw-data)
          parsed (edn/read-string data-str)]
      (if (vector? parsed)
        {:event-id (first parsed)
         :data (second parsed)
         :raw data-str}
        {:error :invalid-format :raw data-str}))
    (catch Exception e
      {:error :parse-failed :raw (str raw-data) :message (.getMessage e)})))

;; Create WebSocket client
(defn make-client [port on-message-fn]
  (let [opened (promise)
        client (ws/websocket
                {:uri (str "ws://localhost:" port "/")
                 :on-open (fn [_ws] (deliver opened true))
                 :on-message (fn [_ws data _last?]
                               (let [parsed (parse-message data)]
                                 (swap! received-messages conj parsed)
                                 (when on-message-fn (on-message-fn parsed))))
                 :on-close (fn [_ws _code _reason] nil)
                 :on-error (fn [_ws err] (println "Error:" (.getMessage err)))})]
    (deref opened 3000 nil)
    client))

;; Wait for specific event
(defn wait-for-event [event-id timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (if-let [msg (first (filter #(= event-id (:event-id %)) @received-messages))]
        msg
        (if (< (- (System/currentTimeMillis) start) timeout-ms)
          (do (Thread/sleep 50) (recur))
          nil)))))

;; ============================================================================
;; Test 1: on-message callback receives events and can respond
;; ============================================================================

(defn test-callback-responds []
  (println "1. Testing :on-message callback with response...")
  (reset! received-messages [])
  (reset! server-received [])

  ;; Server with custom handler that responds to :custom/greet
  (server/start-server!
   {:port 0
    :heartbeat {:enabled false}
    :on-message (fn [conn-id event-id data]
                  (swap! server-received conj {:conn-id conn-id
                                               :event-id event-id
                                               :data data})
                  (when (= event-id :custom/greet)
                    [:custom/greeting {:message (str "Hello, " (:name data) "!")
                                       :from-server true}]))})
  (Thread/sleep 300)
  (let [port (server/get-server-port)]
    (record-test! "Server started with on-message" (some? port) (str "port=" port))

    ;; Connect client
    (let [client (make-client port nil)]
      (reset! current-client client)
      (Thread/sleep 500) ; Wait for handshake

      ;; Send custom event
      (ws/send! client (pr-str [:custom/greet {:name "Alice"}]))
      (Thread/sleep 500)

      ;; Check server received it
      (let [server-msg (first (filter #(= :custom/greet (:event-id %)) @server-received))]
        (record-test! "Server received :custom/greet" (some? server-msg) nil)
        (record-test! "Server got correct data" (= "Alice" (get-in server-msg [:data :name])) nil)
        (record-test! "Server got conn-id" (some? (:conn-id server-msg)) nil))

      ;; Check client received response
      (let [response (wait-for-event :custom/greeting 2000)]
        (record-test! "Client received :custom/greeting" (some? response) nil)
        (when response
          (record-test! "Response has correct message"
                        (= "Hello, Alice!" (get-in response [:data :message])) nil)
          (record-test! "Response marked from-server"
                        (get-in response [:data :from-server]) nil)))

      ;; Cleanup
      (ws/close! client)))
  (server/stop-server!)
  (Thread/sleep 200))

;; ============================================================================
;; Test 2: nil return means no response sent
;; ============================================================================

(defn test-callback-nil-response []
  (println)
  (println "2. Testing :on-message callback returning nil (no response)...")
  (reset! received-messages [])
  (reset! server-received [])

  ;; Server that handles :log/entry but returns nil (no response)
  (server/start-server!
   {:port 0
    :heartbeat {:enabled false}
    :on-message (fn [_conn-id event-id data]
                  (swap! server-received conj {:event-id event-id :data data})
                  ;; Return nil - no response to client
                  nil)})
  (Thread/sleep 300)
  (let [port (server/get-server-port)
        client (make-client port nil)]
    (reset! current-client client)
    (Thread/sleep 500)
    (reset! received-messages []) ; Clear handshake

    ;; Send event
    (ws/send! client (pr-str [:log/entry {:level :info :msg "test"}]))
    (Thread/sleep 500)

    ;; Server should have received it
    (let [server-msg (first (filter #(= :log/entry (:event-id %)) @server-received))]
      (record-test! "Server received :log/entry" (some? server-msg) nil))

    ;; Client should NOT have received any response (no echo, no custom response)
    (let [response (wait-for-event :sente-lite/echo 500)
          any-response (first (filter #(not= :chsk/ws-ping (:event-id %)) @received-messages))]
      (record-test! "No echo response (nil return)" (nil? response) nil)
      (record-test! "No response at all" (nil? any-response) nil))

    ;; Cleanup
    (ws/close! client))
  (server/stop-server!)
  (Thread/sleep 200))

;; ============================================================================
;; Test 3: Without callback, falls back to echo
;; ============================================================================

(defn test-fallback-to-echo []
  (println)
  (println "3. Testing fallback to echo (no callback)...")
  (reset! received-messages [])

  ;; Server WITHOUT on-message callback
  (server/start-server!
   {:port 0
    :heartbeat {:enabled false}})
  (Thread/sleep 300)
  (let [port (server/get-server-port)
        client (make-client port nil)]
    (reset! current-client client)
    (Thread/sleep 500)
    (reset! received-messages [])

    ;; Send custom event
    (ws/send! client (pr-str [:unknown/event {:foo "bar"}]))
    (Thread/sleep 500)

    ;; Should receive echo
    (let [echo (wait-for-event :sente-lite/echo 2000)]
      (record-test! "Received :sente-lite/echo" (some? echo) nil)
      (when echo
        (record-test! "Echo has original-event-id"
                      (= :unknown/event (get-in echo [:data :original-event-id])) nil)
        (record-test! "Echo has original-data"
                      (= {:foo "bar"} (get-in echo [:data :original-data])) nil)))

    ;; Cleanup
    (ws/close! client))
  (server/stop-server!)
  (Thread/sleep 200))

;; ============================================================================
;; Test 4: Callback can handle some events and let others echo
;; ============================================================================

(defn test-selective-handling []
  (println)
  (println "4. Testing selective handling (some handled, some echo)...")
  (reset! received-messages [])
  (reset! server-received [])

  ;; Server that handles :api/ping but lets others echo
  (server/start-server!
   {:port 0
    :heartbeat {:enabled false}
    :on-message (fn [_conn-id event-id data]
                  (swap! server-received conj {:event-id event-id})
                  (case event-id
                    :api/ping [:api/pong {:ts (System/currentTimeMillis)}]
                    ;; Return the echo format for unhandled events
                    [:sente-lite/echo {:original-event-id event-id
                                       :original-data data}]))})
  (Thread/sleep 300)
  (let [port (server/get-server-port)
        client (make-client port nil)]
    (reset! current-client client)
    (Thread/sleep 500)
    (reset! received-messages [])

    ;; Send handled event
    (ws/send! client (pr-str [:api/ping {}]))
    (Thread/sleep 300)

    (let [pong (wait-for-event :api/pong 1000)]
      (record-test! "Received :api/pong for handled event" (some? pong) nil))

    ;; Send unhandled event
    (reset! received-messages [])
    (ws/send! client (pr-str [:other/event {:data 123}]))
    (Thread/sleep 300)

    (let [echo (wait-for-event :sente-lite/echo 1000)]
      (record-test! "Received echo for unhandled event" (some? echo) nil)
      (when echo
        (record-test! "Echo has correct original-event-id"
                      (= :other/event (get-in echo [:data :original-event-id])) nil)))

    ;; Cleanup
    (ws/close! client))
  (server/stop-server!)
  (Thread/sleep 200))

;; ============================================================================
;; Run all tests
;; ============================================================================

(defn run-tests []
  (test-callback-responds)
  (test-callback-nil-response)
  (test-fallback-to-echo)
  (test-selective-handling)

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
