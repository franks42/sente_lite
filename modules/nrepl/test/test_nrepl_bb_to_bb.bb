#!/usr/bin/env bb
;;
;; Test: nREPL-over-sente BB-to-BB
;; Tests Layer 1 (nREPL server) with sente-lite transport.
;;
;; This validates:
;; - :nrepl/request → :nrepl/response flow
;; - eval operation
;; - load-file operation
;; - describe operation
;; - clone operation
;; - Error handling
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "modules/nrepl/src")

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn]
         '[sente-lite.server :as server]
         '[nrepl-sente.protocol :as proto]
         '[nrepl-sente.server :as nrepl-server])

(println "=== Test: nREPL-over-sente BB-to-BB ===")
(println)

;; Test state
(def test-results (atom {:passed 0 :failed 0 :tests []}))
(def received-messages (atom []))
(def response-promises (atom {}))

(defn record-test! [name passed? details]
  (swap! test-results update (if passed? :passed :failed) inc)
  (swap! test-results update :tests conj {:name name :passed passed? :details details})
  (println (if passed? "  [PASS]" "  [FAIL]") name (when details (str "- " details))))

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

;; WebSocket handlers
(def ws-opened (promise))
(def handshake-received (promise))

(defn on-open [ws]
  (deliver ws-opened true))

(defn on-message [ws raw-data last?]
  (let [parsed (parse-message raw-data)]
    (swap! received-messages conj parsed)

    (case (:event-id parsed)
      :chsk/handshake
      (deliver handshake-received parsed)

      :chsk/ws-ping
      (ws/send! ws (pr-str [:chsk/ws-pong]))

      :nrepl/response
      ;; Deliver to waiting promise based on :id
      (let [id (get-in parsed [:data :id])]
        (when-let [p (get @response-promises id)]
          (deliver p (:data parsed))))

      nil)))

;; Helper to send nREPL request and wait for response
(defn send-request! [ws request timeout-ms]
  (let [id (or (:id request) (str (java.util.UUID/randomUUID)))
        request (assoc request :id id)
        p (promise)]
    (swap! response-promises assoc id p)
    (ws/send! ws (pr-str (proto/wrap-request request)))
    (let [result (deref p timeout-ms nil)]
      (swap! response-promises dissoc id)
      result)))

;; nREPL message handler for the server
;; sente-lite on-message is called as (on-message conn-id event-id data)
(defn make-server-handler []
  (nrepl-server/make-nrepl-handler server/send-event-to-connection!))

(defn run-tests []
  ;; Start server with nREPL handler
  (println "1. Starting sente-lite server with nREPL handler...")
  (let [handler (make-server-handler)]
    (server/start-server! {:port 0
                           :wire-format :edn
                           :heartbeat {:enabled false}
                           :on-message handler}))
  (Thread/sleep 500)
  (def server-port (server/get-server-port))
  (println "   Server started on port" server-port)
  (record-test! "Server started" (some? server-port) (str "port=" server-port))

  ;; Connect client
  (println)
  (println "2. Connecting client...")
  (def client-id (str "nrepl-test-" (System/currentTimeMillis)))
  (def ws-url (str "ws://localhost:" server-port "/?client-id=" client-id))

  (def ws-client
    (ws/websocket {:uri ws-url
                   :on-open on-open
                   :on-message on-message
                   :on-close (fn [ws code reason] nil)
                   :on-error (fn [ws err] (println "ERROR:" (.getMessage err)))}))

  ;; Wait for connection
  (let [opened (deref ws-opened 3000 nil)]
    (record-test! "WebSocket connected" (boolean opened) nil))

  ;; Wait for handshake
  (let [handshake (deref handshake-received 3000 nil)]
    (record-test! "Received handshake" (boolean handshake) nil))

  ;; Test 1: Simple eval
  (println)
  (println "3. Testing :eval operation...")
  (let [response (send-request! ws-client (proto/eval-request "(+ 1 2 3)") 3000)]
    (if response
      (do
        (record-test! "Received eval response" true nil)
        (record-test! "Eval result is correct" (= "6" (:value response))
                      (str "value=" (:value response)))
        (record-test! "Status is :done" (contains? (set (:status response)) :done) nil))
      (record-test! "Received eval response" false "timeout")))

  ;; Test 2: Eval with def
  (println)
  (println "4. Testing :eval with def...")
  (let [response (send-request! ws-client (proto/eval-request "(def test-var 42)") 3000)]
    (if response
      (do
        (record-test! "Def succeeded" true nil)
        ;; Now read it back
        (let [response2 (send-request! ws-client (proto/eval-request "test-var") 3000)]
          (record-test! "Can read defined var" (= "42" (:value response2))
                        (str "value=" (:value response2)))))
      (record-test! "Def succeeded" false "timeout")))

  ;; Test 3: Eval with error
  (println)
  (println "5. Testing :eval with error...")
  (let [response (send-request! ws-client (proto/eval-request "(/ 1 0)") 3000)]
    (if response
      (do
        (record-test! "Received error response" true nil)
        (record-test! "Has error key" (some? (:err response))
                      (str "err=" (:err response)))
        (record-test! "Status contains :error"
                      (contains? (set (:status response)) :error) nil))
      (record-test! "Received error response" false "timeout")))

  ;; Test 4: Eval complex expression
  (println)
  (println "6. Testing complex eval...")
  (let [code "(let [x 10 y 20] (* x y))"
        response (send-request! ws-client (proto/eval-request code) 3000)]
    (if response
      (do
        (record-test! "Complex eval succeeded" true nil)
        (record-test! "Result is 200" (= "200" (:value response))
                      (str "value=" (:value response))))
      (record-test! "Complex eval succeeded" false "timeout")))

  ;; Test 5: load-file
  (println)
  (println "7. Testing :load-file operation...")
  (let [file-content "(ns test.myns) (def loaded-value 123)"
        response (send-request! ws-client
                                (proto/load-file-request file-content "test/myns.clj" "myns.clj")
                                3000)]
    (if response
      (do
        (record-test! "Load-file succeeded" (not (:err response))
                      (or (:err response) (str "value=" (:value response)))))
      (record-test! "Load-file succeeded" false "timeout")))

  ;; Test 6: describe
  (println)
  (println "8. Testing :describe operation...")
  (let [response (send-request! ws-client (proto/describe-request) 3000)]
    (if response
      (do
        (record-test! "Describe succeeded" true nil)
        (record-test! "Has versions" (some? (:versions response)) nil)
        (record-test! "Has ops" (some? (:ops response)) nil)
        (record-test! "Supports eval op" (contains? (:ops response) "eval")
                      (str "ops=" (keys (:ops response)))))
      (record-test! "Describe succeeded" false "timeout")))

  ;; Test 7: clone
  (println)
  (println "9. Testing :clone operation...")
  (let [response (send-request! ws-client (proto/clone-request) 3000)]
    (if response
      (do
        (record-test! "Clone succeeded" true nil)
        (record-test! "Has new-session" (some? (:new-session response))
                      (str "session=" (:new-session response))))
      (record-test! "Clone succeeded" false "timeout")))

  ;; Test 8: Multiple rapid evals
  (println)
  (println "10. Testing rapid sequential evals...")
  (let [results (atom [])
        codes ["(+ 1 1)" "(+ 2 2)" "(+ 3 3)" "(+ 4 4)" "(+ 5 5)"]]
    (doseq [code codes]
      (let [response (send-request! ws-client (proto/eval-request code) 2000)]
        (swap! results conj (:value response))))
    (let [expected ["2" "4" "6" "8" "10"]
          actual @results]
      (record-test! "All 5 rapid evals succeeded" (= expected actual)
                    (str "expected=" expected " actual=" actual))))

  ;; Cleanup
  (println)
  (println "11. Cleanup...")
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
    (println "Total: " (+ passed failed))
    (println)
    (when (seq (filter #(not (:passed %)) tests))
      (println "Failed tests:")
      (doseq [t (filter #(not (:passed %)) tests)]
        (println "  -" (:name t) (when (:details t) (str ": " (:details t))))))

    (if (zero? failed)
      (do (println)
          (println "All tests passed!")
          (System/exit 0))
      (do (println)
          (println "Some tests failed!")
          (System/exit 1)))))

(run-tests)
