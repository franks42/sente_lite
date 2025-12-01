(ns ws-client
  "Simple WebSocket client for Babashka using native babashka.http-client.websocket"
  (:require [babashka.http-client.websocket :as ws]))

;; Stub for removed telemere-lite
(def tel-event! (fn [& _] nil))
(def tel-error! (fn [& _] nil))
(def tel-log! (fn [& _] nil))

(defn connect!
  "Connect to WebSocket server using Babashka's native WebSocket client

  Options:
    :uri - WebSocket URI (ws://...)
    :on-open - Called when connection opens (fn [ws])
    :on-message - Called when message received (fn [ws data last])
    :on-close - Called when connection closes (fn [ws status reason])
    :on-error - Called on error (fn [ws error])"
  [{:keys [uri on-open on-message on-close on-error]}]
  (tel-event! ::ws-connecting {:uri uri})
  (try
    (let [ws-client (ws/websocket
                     {:uri uri
                      :on-open (fn [ws]
                                 (tel-event! ::ws-open {:uri uri})
                                 (when on-open (on-open ws)))
                      :on-message (fn [ws data last]
                                    (tel-event! ::ws-message-received
                                                {:uri uri
                                                 :data-length (count data)
                                                 :last last})
                                    (when on-message (on-message ws data last)))
                      :on-close (fn [ws status reason]
                                  (tel-event! ::ws-close
                                              {:uri uri
                                               :status-code status
                                               :reason reason})
                                  (when on-close (on-close ws status reason)))
                      :on-error (fn [ws error]
                                  (tel-error! "WebSocket error" {:error error :uri uri})
                                  (when on-error (on-error ws error)))})]
      ;; Give connection time to establish
      (Thread/sleep 100)
      (tel-event! ::ws-connected {:uri uri :ws (str ws-client)})
      ws-client)
    (catch Exception e
      (tel-error! "Failed to connect WebSocket" {:error e :uri uri})
      (throw e))))

(defn send!
  "Send text message to WebSocket"
  [ws message]
  (tel-event! ::ws-send {:message-length (count message)})
  (ws/send! ws message)
  (tel-event! ::ws-send-complete {:message-length (count message)}))

(defn close!
  "Close WebSocket connection"
  ([ws]
   (close! ws 1000 "Normal closure"))
  ([ws status-code reason]
   (tel-event! ::ws-closing {:status-code status-code
                             :reason reason})
   (ws/close! ws status-code reason)))
