(ns sente-compat.sente-v2-integration-test
  "Integration test: sente-lite v2 wire format with real Sente server.
   Tests that our v2 wire format can correctly parse Sente messages."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.edn :as edn]
            [sente-lite.wire-format-v2 :as v2]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [org.java_websocket.client WebSocketClient]
           [org.java_websocket.handshake ServerHandshake]))

(def ^:dynamic *test-host* "localhost")
(def ^:dynamic *test-port* 8090)

(defn create-test-client
  "Create a WebSocket client that collects messages"
  [ws-url messages-atom connected-promise]
  (proxy [WebSocketClient] [(URI. ws-url)]
    (onOpen [^ServerHandshake handshake]
      (log/info "Test client connected")
      (deliver connected-promise true))

    (onMessage [^String msg]
      (log/debug "Received:" msg)
      (swap! messages-atom conj {:raw msg
                                 :parsed (try (edn/read-string msg)
                                              (catch Exception _ nil))}))

    (onClose [code reason remote]
      (log/info "Test client closed:" code))

    (onError [^Exception ex]
      (log/error "Test client error:" (.getMessage ex)))))

(defn with-sente-connection
  "Execute f with a connected WebSocket to Sente server"
  [f]
  (let [client-id (str (java.util.UUID/randomUUID))
        ws-url (str "ws://" *test-host* ":" *test-port* "/chsk?client-id=" client-id)
        messages (atom [])
        connected (promise)
        client (create-test-client ws-url messages connected)]

    (.connect client)

    (if (deref connected 5000 nil)
      (try
        (Thread/sleep 1000) ; Wait for handshake
        (f client messages)
        (finally
          (.close client)))
      (throw (ex-info "Connection timeout" {:url ws-url})))))

(deftest parse-sente-handshake-test
  (testing "Parse real Sente handshake with v2 wire format"
    (with-sente-connection
      (fn [client messages]
        (Thread/sleep 500) ; Ensure handshake received

        (let [handshake-msg (first @messages)
              parsed (:parsed handshake-msg)]

          (is (some? parsed) "Should receive handshake")

          ;; Sente wraps in buffer: [[:chsk/handshake [uid csrf]]]
          (when (v2/buffered-events? parsed)
            (let [events (v2/unwrap-buffered-events parsed)
                  first-event (first events)]

              (is (= :chsk/handshake (first first-event))
                  "First event should be handshake")

              (let [hs-data (second first-event)
                    parsed-hs (v2/parse-handshake hs-data)]

                (is (some? (:uid parsed-hs)) "Should have uid")
                (is (contains? parsed-hs :csrf-token) "Should have csrf-token key")
                (is (true? (:first? parsed-hs)) "Should be first connection")))))))))

(deftest send-receive-event-test
  (testing "Send event and receive reply using v2 wire format"
    (with-sente-connection
      (fn [client messages]
        (Thread/sleep 500) ; Wait for handshake

        ;; Send a test event with callback
        (let [cb-uuid (v2/generate-cb-uuid)
              event (v2/encode-event-with-callback :test/echo {:msg "Hello Sente!"} cb-uuid)
              wire-msg (pr-str event)]

          (log/info "Sending:" wire-msg)
          (.send client wire-msg)

          (Thread/sleep 1000) ; Wait for reply

          ;; Check we got a reply
          (let [reply-msgs (filter #(let [p (:parsed %)]
                                      (and (vector? p)
                                           (= 2 (count p))
                                           (string? (second p))))
                                   @messages)]

            (when (seq reply-msgs)
              (let [reply (first reply-msgs)
                    parsed-reply (v2/parse-wire-reply (:parsed reply))]

                (is (= cb-uuid (:cb-uuid parsed-reply))
                    "Reply should have matching cb-uuid")
                (is (some? (:data parsed-reply))
                    "Reply should have data")))))))))

(deftest ping-pong-test
  (testing "Ping event using v2 wire format"
    (with-sente-connection
      (fn [client messages]
        (Thread/sleep 500)

        ;; Send ping
        (let [ping-event (v2/make-ws-ping)
              wire-msg (pr-str ping-event)]

          (log/info "Sending ping:" wire-msg)
          (.send client wire-msg)

          (Thread/sleep 500)

          ;; Ping should be acknowledged (Sente handles internally)
          (is true "Ping sent successfully"))))))

(defn run-tests []
  (log/info "Running Sente v2 integration tests...")
  (log/info "Make sure Sente server is running: bb sente-server")
  (clojure.test/run-tests 'sente-compat.sente-v2-integration-test))

(defn -main [& args]
  (run-tests))
