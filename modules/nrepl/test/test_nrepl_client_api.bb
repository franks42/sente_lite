#!/usr/bin/env bb
;;
;; Test: nREPL-over-sente Client API
;; Tests the client.clj API for connection discovery, probing, and eval.
;;
;; This validates:
;; - Connection discovery via registry
;; - get-nrepl-connection! with probe
;; - Capability caching
;; - eval! and load-file! through client API
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "modules/nrepl/src")

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn]
         '[sente-lite.server :as server]
         '[sente-lite.registry :as registry]
         '[nrepl-sente.protocol :as proto]
         '[nrepl-sente.server :as nrepl-server]
         '[nrepl-sente.client :as client])

(println "=== Test: nREPL Client API ===")
(println)

;; Test state
(def test-results (atom {:passed 0 :failed 0 :tests []}))
(def received-messages (atom []))

;; Response handler for routing responses to pending requests
(def response-handler-server (client/make-response-handler))

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

;; WebSocket handlers for the "remote" BB client
(def ws-opened (promise))
(def handshake-received (promise))

(defn on-open [ws]
  (deliver ws-opened true))

;; Response handler that routes to client's pending requests
(def response-handler (client/make-response-handler))

(defn on-message [_ws raw-data _last?]
  (let [parsed (parse-message raw-data)]
    (swap! received-messages conj parsed)

    (case (:event-id parsed)
      :chsk/handshake
      (deliver handshake-received parsed)

      :chsk/ws-ping
      nil  ; Ignore pings in test

      :nrepl/response
      ;; Route to client's response handler
      (response-handler nil (:event-id parsed) (:data parsed))

      nil)))

(defn run-tests []
  ;; Start server with combined handler:
  ;; - nREPL request handler (for requests FROM peers)
  ;; - Response handler (for responses TO our client API calls)
  (println "1. Starting sente-lite server with nREPL handler...")
  (let [nrepl-handler (nrepl-server/make-nrepl-handler server/send-event-to-connection!)
        combined-handler (fn [conn-id event-id data]
                           ;; Route :nrepl/request to nREPL server
                           (nrepl-handler conn-id event-id data)
                           ;; Route :nrepl/response to client's pending requests
                           (response-handler-server conn-id event-id data))]
    (server/start-server! {:port 0
                           :wire-format :edn
                           :heartbeat {:enabled false}
                           :on-message combined-handler}))
  (Thread/sleep 500)
  (def server-port (server/get-server-port))
  (println "   Server started on port" server-port)
  (record-test! "Server started" (some? server-port) (str "port=" server-port))

  ;; Test: No connections yet
  (println)
  (println "2. Testing connection discovery (no connections)...")
  (let [conns (client/get-connections)]
    (record-test! "No connections initially" (empty? conns) nil))

  (let [latest (client/get-latest-connection)]
    (record-test! "Latest connection is nil" (nil? latest) nil))

  ;; Test: get-nrepl-connection! throws when no connections
  (try
    (client/get-nrepl-connection!)
    (record-test! "Throws when no connections" false "did not throw")
    (catch Exception e
      (record-test! "Throws when no connections" true
                    (str "type=" (:type (ex-data e))))))

  ;; Connect client
  (println)
  (println "3. Connecting client...")
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

  (Thread/sleep 200)  ; Let server register connection

  ;; Test: Connection discovery
  (println)
  (println "4. Testing connection discovery (with connection)...")
  (let [conns (client/get-connections)]
    (record-test! "Has one connection" (= 1 (count conns))
                  (str "count=" (count conns)))
    (when (seq conns)
      (record-test! "Connection has conn-id" (some? (:conn-id (first conns)))
                    (str "conn-id=" (:conn-id (first conns))))))

  (let [latest (client/get-latest-connection)]
    (record-test! "Latest connection found" (some? latest)
                  (str "conn-id=" latest)))

  ;; Test: Registry entries
  (println)
  (println "5. Testing registry entries...")
  (let [registered (registry/list-registered-prefix "connections/")]
    (record-test! "Registry has connection entry" (= 1 (count registered))
                  (str "entries=" registered)))

  ;; Test: get-nrepl-connection! with skip-probe
  (println)
  (println "6. Testing get-nrepl-connection! (skip probe)...")
  (let [conn (client/get-nrepl-connection! {:skip-probe? true})]
    (record-test! "get-nrepl-connection! returns conn-id" (some? conn)
                  (str "conn-id=" conn)))

  ;; Test: Probe and caching (NOTE: This won't work in BB-to-BB because
  ;; the "client" side needs to respond to :nrepl/request. In this test,
  ;; the client IS the one sending requests, so probe would timeout.
  ;; We test this pattern with a second BB that acts as nREPL server.)

  ;; For now, test with skip-probe
  (println)
  (println "7. Testing eval! via client API...")

  ;; We need to set up the second BB as the nREPL "server" side
  ;; For simplicity, we'll test the plumbing by sending directly
  ;; and checking the response handling works

  ;; Create a second connection that acts as nREPL server
  (def ws-opened-2 (promise))
  (def handshake-received-2 (promise))
  (def nrepl-requests (atom []))

  (def ws-client-2
    (ws/websocket {:uri (str "ws://localhost:" server-port "/?client-id=nrepl-server-peer")
                   :on-open (fn [ws] (deliver ws-opened-2 true))
                   :on-message (fn [ws raw-data last?]
                                 (let [parsed (parse-message raw-data)]
                                   (case (:event-id parsed)
                                     :chsk/handshake
                                     (deliver handshake-received-2 parsed)

                                     :nrepl/request
                                     ;; Handle as nREPL server - evaluate and respond
                                     (let [request (:data parsed)
                                           response (nrepl-server/dispatch-request request)]
                                       (swap! nrepl-requests conj request)
                                       ;; Send response back through the WebSocket
                                       (ws/send! ws (pr-str (proto/wrap-response response))))

                                     nil)))
                   :on-close (fn [ws code reason] nil)
                   :on-error (fn [ws err] (println "ERROR-2:" (.getMessage err)))}))

  (deref ws-opened-2 3000 nil)
  (deref handshake-received-2 3000 nil)
  (Thread/sleep 200)

  ;; Now we have two connections - the nREPL server peer is the newest one
  (let [conns (client/get-connections)
        _ (println "   Connections:" (mapv :conn-id conns))
        ;; get-connections returns sorted by opened-at desc, so first is newest
        nrepl-peer (first conns)]
    (if nrepl-peer
      (let [peer-conn-id (:conn-id nrepl-peer)]
        (println "   Found nREPL peer:" peer-conn-id)

        ;; Test: eval! through client API
        (let [result (client/eval! peer-conn-id "(+ 10 20 30)")]
          (record-test! "eval! returns result" (some? result) nil)
          (record-test! "eval! value is correct" (= "60" (:value result))
                        (str "value=" (:value result)))
          (record-test! "eval! status is :done"
                        (contains? (set (:status result)) :done) nil))

        ;; Test: eval! with error
        (let [result (client/eval! peer-conn-id "(/ 1 0)")]
          (record-test! "eval! error has :err" (some? (:err result))
                        (str "err=" (:err result))))

        ;; Test: load-file!
        (let [result (client/load-file! peer-conn-id
                                        "(ns test.loaded) (def x 999)"
                                        "test/loaded.clj"
                                        "loaded.clj")]
          (record-test! "load-file! succeeds" (not (:err result))
                        (str "value=" (:value result))))

        ;; Verify the def worked
        (let [result (client/eval! peer-conn-id "test.loaded/x")]
          (record-test! "load-file! def is accessible" (= "999" (:value result))
                        (str "value=" (:value result))))

        ;; Test: probe-nrepl-capable?
        (println)
        (println "8. Testing probe and caching...")
        (let [result (client/probe-nrepl-capable? peer-conn-id)]
          (record-test! "probe returns result" (some? result) nil)
          (record-test! "probe says nrepl capable" (:nrepl? result) nil)
          (record-test! "probe has ops" (contains? (:ops result) "eval")
                        (str "ops=" (:ops result))))

        ;; Test: get-nrepl-connection! with probe (should use cache)
        (let [conn (client/get-nrepl-connection! peer-conn-id {})]
          (record-test! "get-nrepl-connection! returns verified conn" (= peer-conn-id conn)
                        nil))

        ;; Test: eval-latest! (convenience)
        (println)
        (println "9. Testing convenience functions...")
        ;; This will pick the latest connection, which might not be the nREPL peer
        ;; For reliable testing, use explicit conn-id
        (let [result (client/eval! peer-conn-id "(* 7 8)")]
          (record-test! "Convenience eval works" (= "56" (:value result))
                        (str "value=" (:value result)))))

      (record-test! "Found nREPL peer" false "peer not found")))

  ;; Test: Connection cleanup on close
  (println)
  (println "10. Testing registry cleanup on disconnect...")
  (ws/close! ws-client-2)
  (Thread/sleep 300)

  (let [conns (client/get-connections)]
    (record-test! "Connection removed after close" (= 1 (count conns))
                  (str "remaining=" (count conns))))

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
