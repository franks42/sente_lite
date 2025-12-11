(ns sente-test.client
  "Raw WebSocket client for testing Sente wire format.
   Connects to a Sente server and logs all wire-level messages."
  (:require [taoensso.timbre :as log]
            [clojure.edn :as edn])
  (:import [java.net URI]
           [org.java_websocket.client WebSocketClient]
           [org.java_websocket.handshake ServerHandshake]))

;; ============================================================================
;; Wire Format Logging
;; ============================================================================

(defn log-wire-message [direction msg]
  (log/info "=== WIRE" direction "===")
  (log/info "Raw:" (pr-str msg))
  (log/info "Type:" (type msg))
  (when (vector? msg)
    (log/info "Event ID:" (first msg))
    (log/info "Event Data:" (second msg))))

;; ============================================================================
;; CSRF Token
;; ============================================================================

(defn get-csrf-token [host port]
  (let [url (str "http://" host ":" port "/csrf-token")]
    (log/info "Fetching CSRF token from" url)
    (try
      (let [response (slurp url)
            data (edn/read-string response)]
        (log/info "Got CSRF token:" (:csrf-token data))
        (:csrf-token data))
      (catch Exception e
        (log/error "Failed to get CSRF token:" (.getMessage e))
        nil))))

;; ============================================================================
;; WebSocket Client
;; ============================================================================

(def messages (atom []))

(defn create-ws-client [ws-url on-connected]
  (proxy [WebSocketClient] [(URI. ws-url)]
    (onOpen [^ServerHandshake handshake]
      (log/info "=== WebSocket Connected ===")
      (on-connected this))

    (onMessage [^String msg]
      (log/info "=== RECV ===")
      (log/info "Raw:" msg)
      (let [parsed (try (edn/read-string msg) (catch Exception _ nil))]
        (when parsed
          (log-wire-message "PARSED" parsed))
        (swap! messages conj {:direction :recv :raw msg :parsed parsed})))

    (onClose [code reason remote]
      (log/info "=== WebSocket Closed ===" code reason))

    (onError [^Exception ex]
      (log/error "=== WebSocket Error ===" (.getMessage ex)))))

;; ============================================================================
;; Test Functions
;; ============================================================================

(defn send-event! [ws event]
  (let [msg (pr-str event)]
    (log/info "=== SEND ===")
    (log/info "Raw:" msg)
    (log-wire-message "EVENT" event)
    (.send ws msg)))

(defn run-tests [ws]
  (log/info "Waiting for handshake...")
  (Thread/sleep 2000)

  (log/info "=== TEST: Echo ===")
  (send-event! ws [:test/echo {:msg "Hello from JVM client!" :timestamp (System/currentTimeMillis)}])
  (Thread/sleep 2000)

  (log/info "=== TEST: Ping ===")
  (send-event! ws [:chsk/ws-ping])
  (Thread/sleep 1000)

  (log/info "=== TEST: Event with callback ===")
  (send-event! ws [[:test/echo {:data "with callback"}] "cb-uuid-123"])
  (Thread/sleep 2000))

;; ============================================================================
;; Main
;; ============================================================================

(defn -main [& args]
  (let [host (or (first args) "localhost")
        port (Integer/parseInt (or (second args) "8090"))
        client-id (str (java.util.UUID/randomUUID))
        ws-url (str "ws://" host ":" port "/chsk?client-id=" client-id)
        connected (promise)
        ws (create-ws-client ws-url #(deliver connected %))]

    (log/info "Connecting to" ws-url)
    (.connect ws)

    (if-let [ws-conn (deref connected 5000 nil)]
      (do
        (run-tests ws-conn)

        (log/info "")
        (log/info "=== Wire Format Summary ===")
        (log/info "Messages captured:" (count @messages))
        (doseq [{:keys [raw parsed]} @messages]
          (log/info "")
          (log/info "Raw:" raw)
          (when (vector? parsed)
            (log/info "Event ID:" (first parsed))
            (log/info "Data:" (second parsed))))

        (.close ws)
        (log/info "Done!"))

      (do
        (log/error "Connection timeout")
        (System/exit 1)))))
