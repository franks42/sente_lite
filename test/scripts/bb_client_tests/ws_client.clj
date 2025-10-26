(ns ws-client
  "Simple WebSocket client for Babashka using Java 11+ WebSocket API"
  (:require [telemere-lite.core :as tel])
  (:import [java.net.http HttpClient WebSocket$Listener]
           [java.net URI]
           [java.time Duration]))

(defn create-listener
  "Create a WebSocket listener with callbacks"
  [{:keys [uri on-open on-message on-close on-error]}]
  (reify WebSocket$Listener
    (onOpen [this ws]
      (tel/event! ::ws-open {:uri uri})
      (when on-open (on-open ws))
      nil)

    (onText [this ws data last]
      (tel/event! ::ws-message-received
                  {:uri uri
                   :data-length (count data)
                   :last last})
      (when on-message (on-message ws data last))
      nil)

    (onClose [this ws status-code reason]
      (tel/event! ::ws-close
                  {:uri uri
                   :status-code status-code
                   :reason reason})
      (when on-close (on-close ws status-code reason))
      nil)

    (onError [this ws error]
      (tel/error! "WebSocket error" {:error error :uri uri})
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
  (tel/event! ::ws-connecting {:uri uri})
  (try
    (let [client (-> (HttpClient/newBuilder)
                     (.connectTimeout (Duration/ofSeconds 10))
                     (.build))
          listener (create-listener opts)
          ws-builder (.newWebSocketBuilder client)
          ws (.buildAsync ws-builder
                          (URI/create uri)
                          listener)]
      ;; Wait for connection to complete (blocking)
      (let [result (.join ws)]
        (tel/event! ::ws-connected {:uri uri :ws (str result)})
        result))
    (catch Exception e
      (tel/error! "Failed to connect WebSocket" {:error e :uri uri})
      (throw e))))

(defn send!
  "Send text message to WebSocket"
  [ws message]
  (tel/event! ::ws-send {:message-length (count message)})
  ;; sendText returns CompletableFuture - must wait for completion
  (let [send-future (.sendText ws message true)]
    (.join send-future)  ; Block until message is actually sent
    (tel/event! ::ws-send-complete {:message-length (count message)})))

(defn close!
  "Close WebSocket connection"
  ([ws]
   (close! ws 1000 "Normal closure"))
  ([ws status-code reason]
   (tel/event! ::ws-closing {:status-code status-code
                             :reason reason})
   (.sendClose ws status-code reason)))

(defn wait-for-close
  "Wait for WebSocket to be closed (blocking)"
  [ws timeout-ms]
  (try
    (.get (.join ws) timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
    (catch Exception e
      nil)))
