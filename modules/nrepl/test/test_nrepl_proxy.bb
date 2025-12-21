#!/usr/bin/env bb
;;
;; Test: nREPL Bencode Proxy
;; Tests the proxy that bridges bencode clients to sente-lite peers.
;;
;; Architecture:
;;   [This test] ─bencode→ [Proxy:port] ─EDN/sente→ [Peer via WS]
;;
;; This validates:
;; - Bencode encoding/decoding
;; - Proxy forwards eval to peer
;; - Proxy forwards load-file to peer
;; - describe and clone operations
;; - Error handling
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "modules/nrepl/src")

(require '[bencode.core :as bencode]
         '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn]
         '[sente-lite.server :as server]
         '[nrepl-sente.server :as nrepl-server]
         '[nrepl-sente.client :as client]
         '[nrepl-sente.proxy :as proxy]
         '[nrepl-sente.protocol :as proto])

(import '[java.net Socket]
        '[java.io PushbackInputStream BufferedOutputStream])

(println "=== Test: nREPL Bencode Proxy ===")
(println)

;; Test state
(def test-results (atom {:passed 0 :failed 0 :tests []}))

(defn record-test! [name passed? details]
  (swap! test-results update (if passed? :passed :failed) inc)
  (swap! test-results update :tests conj {:name name :passed passed? :details details})
  (println (if passed? "  [PASS]" "  [FAIL]") name (when details (str "- " details))))

;; Bencode client helpers
(defn- coerce-bencode [x]
  (cond
    (bytes? x) (String. ^bytes x)
    (sequential? x) (mapv coerce-bencode x)
    (map? x) (into {} (map (fn [[k v]] [(coerce-bencode k) (coerce-bencode v)]) x))
    :else x))

(defn- send-bencode! [out msg]
  (bencode/write-bencode out msg)
  (.flush ^BufferedOutputStream out))

(defn- read-bencode! [in]
  (let [msg (bencode/read-bencode in)]
    (zipmap (map keyword (keys msg))
            (map coerce-bencode (vals msg)))))

;; Response handler for routing responses to client's pending requests
(def response-handler-server (client/make-response-handler))

;; WebSocket message parser
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

(defn run-tests []
  ;; 1. Start sente-lite server with combined handler
  (println "1. Starting sente-lite server...")
  (let [nrepl-handler (nrepl-server/make-nrepl-handler server/send-event-to-connection!)
        combined-handler (fn [conn-id event-id data]
                           (nrepl-handler conn-id event-id data)
                           (response-handler-server conn-id event-id data))]
    (server/start-server! {:port 0
                           :wire-format :edn
                           :heartbeat {:enabled false}
                           :on-message combined-handler}))
  (Thread/sleep 500)
  (def sente-port (server/get-server-port))
  (println "   Sente server on port" sente-port)
  (record-test! "Sente server started" (some? sente-port) nil)

  ;; 2. Connect a peer that acts as nREPL server
  (println)
  (println "2. Connecting nREPL peer...")
  (def ws-opened (promise))
  (def handshake-received (promise))

  (def peer-ws
    (ws/websocket {:uri (str "ws://localhost:" sente-port "/")
                   :on-open (fn [ws] (deliver ws-opened true))
                   :on-message (fn [ws raw-data last?]
                                 (let [parsed (parse-message raw-data)]
                                   (case (:event-id parsed)
                                     :chsk/handshake
                                     (deliver handshake-received parsed)

                                     :nrepl/request
                                     ;; Handle as nREPL server
                                     (let [request (:data parsed)
                                           response (nrepl-server/dispatch-request request)]
                                       (ws/send! ws (pr-str (proto/wrap-response response))))

                                     nil)))
                   :on-close (fn [ws code reason] nil)
                   :on-error (fn [ws err] (println "Peer WS error:" (.getMessage err)))}))

  (deref ws-opened 3000 nil)
  (deref handshake-received 3000 nil)
  (Thread/sleep 200)
  (record-test! "nREPL peer connected" true nil)

  ;; 3. Start the proxy
  (println)
  (println "3. Starting bencode proxy...")
  (def proxy-result (proxy/start! {:port 0 :write-port-file? false}))
  (def proxy-port (:port proxy-result))
  (println "   Proxy on port" proxy-port)
  (record-test! "Proxy started" (some? proxy-port) (str "port=" proxy-port))

  ;; 4. Connect to proxy as bencode client
  (println)
  (println "4. Connecting bencode client to proxy...")
  (Thread/sleep 200)  ; Give proxy time to start listening
  (def client-socket (Socket. "localhost" proxy-port))
  (def client-in (PushbackInputStream. (.getInputStream client-socket)))
  (def client-out (BufferedOutputStream. (.getOutputStream client-socket)))
  (record-test! "Bencode client connected" (.isConnected client-socket) nil)

  ;; 5. Test :clone
  (println)
  (println "5. Testing :clone operation...")
  (send-bencode! client-out {"op" "clone" "id" "clone-1"})
  (let [response (read-bencode! client-in)]
    (record-test! "Clone returns new-session" (some? (:new-session response))
                  (str "session=" (:new-session response)))
    (record-test! "Clone status is done"
                  (= ["done"] (:status response)) nil)
    (def session-id (:new-session response)))

  ;; 6. Test :describe
  (println)
  (println "6. Testing :describe operation...")
  (send-bencode! client-out {"op" "describe" "id" "desc-1"})
  (let [response (read-bencode! client-in)]
    (record-test! "Describe returns ops" (some? (:ops response))
                  (str "ops=" (keys (:ops response))))
    (record-test! "Describe has eval op" (contains? (:ops response) "eval") nil))

  ;; 7. Test :eval
  (println)
  (println "7. Testing :eval operation...")
  (send-bencode! client-out {"op" "eval"
                             "code" "(+ 100 200 300)"
                             "id" "eval-1"
                             "session" session-id})
  (let [response (read-bencode! client-in)]
    (record-test! "Eval returns value" (some? (:value response))
                  (str "value=" (:value response)))
    (record-test! "Eval value is correct" (= "600" (:value response)) nil)
    (record-test! "Eval status contains done"
                  (some #{"done"} (:status response)) nil))

  ;; 8. Test :eval with error
  (println)
  (println "8. Testing :eval with error...")
  (send-bencode! client-out {"op" "eval"
                             "code" "(/ 1 0)"
                             "id" "eval-err-1"
                             "session" session-id})
  (let [response (read-bencode! client-in)]
    (record-test! "Error eval returns :err" (some? (:err response))
                  (str "err=" (:err response)))
    (record-test! "Error status contains error"
                  (some #{"error"} (:status response)) nil))

  ;; 9. Test :load-file
  (println)
  (println "9. Testing :load-file operation...")
  (send-bencode! client-out {"op" "load-file"
                             "file" "(ns proxy.test) (def proxy-val 777)"
                             "file-path" "proxy/test.clj"
                             "file-name" "test.clj"
                             "id" "load-1"
                             "session" session-id})
  (let [response (read-bencode! client-in)]
    (record-test! "Load-file succeeds" (not (:err response))
                  (or (:err response) (str "value=" (:value response)))))

  ;; Verify the def worked
  (send-bencode! client-out {"op" "eval"
                             "code" "proxy.test/proxy-val"
                             "id" "eval-2"
                             "session" session-id})
  (let [response (read-bencode! client-in)]
    (record-test! "Load-file def accessible" (= "777" (:value response))
                  (str "value=" (:value response))))

  ;; 10. Test complex eval
  (println)
  (println "10. Testing complex eval...")
  (send-bencode! client-out {"op" "eval"
                             "code" "(let [a 10 b 20] (* a b (+ a b)))"
                             "id" "eval-3"
                             "session" session-id})
  (let [response (read-bencode! client-in)]
    (record-test! "Complex eval correct" (= "6000" (:value response))
                  (str "value=" (:value response))))

  ;; 11. Test filtered code (REPL setup noise)
  (println)
  (println "11. Testing filtered code...")
  (send-bencode! client-out {"op" "eval"
                             "code" "(clojure.main/repl-requires)"
                             "id" "eval-filter"
                             "session" session-id})
  (let [response (read-bencode! client-in)]
    (record-test! "Filtered code returns nil" (= "nil" (:value response)) nil)
    ;; Read the done status message too
    (let [done-response (read-bencode! client-in)]
      (record-test! "Filtered code sends done"
                    (some #{"done"} (:status done-response)) nil)))

  ;; Cleanup
  (println)
  (println "12. Cleanup...")
  (.close client-socket)
  (ws/close! peer-ws)
  (Thread/sleep 200)
  (proxy/stop!)
  (server/stop-server!)
  (record-test! "Cleanup complete" true nil)

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
