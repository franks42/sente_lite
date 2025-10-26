#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")

(require '[org.httpkit.server :as http]
         '[telemere-lite.core :as tel]
         '[clojure.data.json :as json])

(println "=== Testing WebSocket Foundation ===")

;; Initialize telemetry
(tel/startup!)
(tel/add-stdout-handler! :ws-events)

;; Test data
(def test-connections (atom {}))

(defn websocket-handler [request]
  (tel/event! ::websocket-request {:method (:request-method request)
                                   :uri (:uri request)
                                   :headers (select-keys (:headers request)
                                                       ["upgrade" "connection" "sec-websocket-key"])})

  (if-not (:websocket? request)
    {:status 426 :body "WebSocket required"}
    (http/as-channel request
      {:on-open (fn [ch]
                  (let [conn-id (str (gensym "conn-"))]
                    (swap! test-connections assoc ch {:id conn-id :opened-at (System/currentTimeMillis)})
                    (tel/event! ::connection-opened {:conn-id conn-id :channel-info (str ch)})
                    (http/send! ch (json/write-str {:type :welcome :conn-id conn-id}))))

       :on-message (fn [ch message]
                     (let [conn-info (get @test-connections ch)
                           conn-id (:id conn-info)]
                       (tel/event! ::message-received {:conn-id conn-id :message message})
                       (try
                         (let [parsed (json/read-str message :key-fn keyword)]
                           (tel/event! ::message-parsed {:conn-id conn-id :type (:type parsed)})
                           ;; Echo back with connection info
                           (http/send! ch (json/write-str {:type :echo
                                                          :original parsed
                                                          :conn-id conn-id
                                                          :timestamp (System/currentTimeMillis)})))
                         (catch Exception e
                           (tel/error! e "Failed to parse message" {:conn-id conn-id :raw-message message})
                           (http/send! ch (json/write-str {:type :error :error "Invalid JSON"}))))))

       :on-close (fn [ch status]
                   (let [conn-info (get @test-connections ch)
                         conn-id (:id conn-info)
                         duration (when (:opened-at conn-info)
                                   (- (System/currentTimeMillis) (:opened-at conn-info)))]
                     (tel/event! ::connection-closed {:conn-id conn-id :status status :duration-ms duration})
                     (swap! test-connections dissoc ch)))

       :on-error (fn [ch throwable]
                   (let [conn-info (get @test-connections ch)
                         conn-id (:id conn-info)]
                     (tel/error! throwable "WebSocket error" {:conn-id conn-id})
                     (swap! test-connections dissoc ch)))})))

;; Simple HTTP handler for non-WebSocket requests
(defn http-handler [request]
  (tel/event! ::http-request {:method (:request-method request) :uri (:uri request)})
  (cond
    (= (:uri request) "/")
    {:status 200
     :headers {"content-type" "text/html"}
     :body "<!DOCTYPE html>
<html>
<head><title>WebSocket Test</title></head>
<body>
  <h1>WebSocket Foundation Test</h1>
  <div id='output'></div>
  <input type='text' id='messageInput' placeholder='Enter message'>
  <button onclick='sendMessage()'>Send</button>

  <script>
    const ws = new WebSocket('ws://localhost:3000/ws');
    const output = document.getElementById('output');

    ws.onopen = () => {
      output.innerHTML += '<p>Connected!</p>';
    };

    ws.onmessage = (event) => {
      const data = JSON.parse(event.data);
      output.innerHTML += '<p>Received: ' + JSON.stringify(data) + '</p>';
    };

    ws.onclose = () => {
      output.innerHTML += '<p>Disconnected!</p>';
    };

    function sendMessage() {
      const input = document.getElementById('messageInput');
      const message = {type: 'test', data: input.value, timestamp: Date.now()};
      ws.send(JSON.stringify(message));
      input.value = '';
    }
  </script>
</body>
</html>"}

    (= (:uri request) "/ws")
    (websocket-handler request)

    :else
    {:status 404 :body "Not found"}))

;; Start server
(println "Starting WebSocket server on port 3000...")
(def server (http/run-server http-handler {:port 3000}))

(tel/event! ::server-started {:port 3000 :server-info (str server)})

(println "Server started! Test WebSocket at:")
(println "  HTTP: http://localhost:3000")
(println "  WebSocket: ws://localhost:3000/ws")
(println "")
(println "Press Ctrl+C to stop server...")

;; Monitor connections
(future
  (loop []
    (Thread/sleep 5000)
    (let [active-connections (count @test-connections)]
      (tel/event! ::connection-stats {:active-connections active-connections})
      (when (pos? active-connections)
        (println (format "Active connections: %d" active-connections))))
    (recur)))

;; Keep server running
(try
  (loop []
    (Thread/sleep 1000)
    (recur))
  (catch Exception e
    (tel/event! ::server-stopping {:reason "Exception" :error (str e)})
    (println "Stopping server...")))

;; Cleanup
(server)
(tel/event! ::server-stopped {})
(tel/shutdown-telemetry!)