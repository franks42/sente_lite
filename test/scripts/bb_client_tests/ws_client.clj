(ns ws-client
  "Simple WebSocket client for Babashka using Java 11+ WebSocket API"
  (:import [java.net.http HttpClient WebSocket WebSocket$Listener]
           [java.net URI]
           [java.time Duration]
           [java.util.concurrent CompletableFuture CompletionStage]))

(defn create-listener
  "Create a WebSocket listener with callbacks"
  [{:keys [on-open on-message on-close on-error]}]
  (reify WebSocket$Listener
    (onOpen [this ws]
      (when on-open (on-open ws))
      nil)

    (onText [this ws data last]
      (when on-message (on-message ws data last))
      nil)

    (onClose [this ws status-code reason]
      (when on-close (on-close ws status-code reason))
      nil)

    (onError [this ws error]
      (when on-error (on-error ws error))
      nil)))

(defn connect!
  "Connect to WebSocket server

  Options:
    :uri - WebSocket URI (ws://...)
    :on-open - Called when connection opens
    :on-message - Called when message received (fn [ws data last])
    :on-close - Called when connection closes
    :on-error - Called on error"
  [{:keys [uri] :as opts}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))
        listener (create-listener opts)
        ws-builder (.newWebSocketBuilder client)
        ws (.buildAsync ws-builder
                       (URI/create uri)
                       listener)]
    ;; Wait for connection to complete (blocking)
    (.join ws)))

(defn send!
  "Send text message to WebSocket"
  [ws message]
  (.sendText ws message true))

(defn close!
  "Close WebSocket connection"
  ([ws]
   (close! ws 1000 "Normal closure"))
  ([ws status-code reason]
   (.sendClose ws status-code reason)))

(defn wait-for-close
  "Wait for WebSocket to be closed (blocking)"
  [ws timeout-ms]
  (try
    (.get (.join ws) timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
    (catch Exception e
      nil)))
