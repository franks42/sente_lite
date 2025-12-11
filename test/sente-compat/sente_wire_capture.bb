#!/usr/bin/env bb
;; Capture actual Sente wire format by connecting via WebSocket
;; Uses http-kit's WebSocket client (available in Babashka)

(require '[babashka.http-client :as http]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[taoensso.trove :as trove]
         '[org.httpkit.server :as hk-server])

(import '[java.net URI]
        '[org.java_websocket.client WebSocketClient]
        '[org.java_websocket.handshake ServerHandshake])

(def host "localhost")
(def port 8090)

(trove/log! {:level :info :id :sente-compat/start
             :data {:host host :port port}})

;; Step 1: Get CSRF token
(println "=== Sente Wire Format Capture ===")
(println)
(println "1. Fetching CSRF token...")

(def csrf-response
  (try
    (http/get (str "http://" host ":" port "/csrf-token"))
    (catch Exception e
      (trove/log! {:level :error :id :sente-compat/csrf-failed
                   :data {:error (.getMessage e)}})
      (println "ERROR: Could not connect to Sente server")
      (println "Make sure server is running: bb sente-server")
      (System/exit 1))))

(def csrf-data (edn/read-string (:body csrf-response)))
(def csrf-token (:csrf-token csrf-data))
(println "   CSRF Token:" (subs csrf-token 0 20) "...")

(trove/log! {:level :info :id :sente-compat/csrf-obtained
             :data {:token-length (count csrf-token)}})

;; Step 2: Connect via WebSocket using java-websocket
(println)
(println "2. Connecting to WebSocket...")

(def ws-url (str "ws://" host ":" port "/chsk?csrf-token=" csrf-token))
(def messages (atom []))
(def connected (promise))

(def ws-client
  (proxy [WebSocketClient] [(URI. ws-url)]
    (onOpen [^ServerHandshake handshake]
      (trove/log! {:level :info :id :sente-compat/ws-open :data {}})
      (println "   Connected!")
      (deliver connected true))

    (onMessage [^String msg]
      (trove/log! {:level :debug :id :sente-compat/ws-recv
                   :data {:size (count msg) :preview (subs msg 0 (min 100 (count msg)))}})
      (println "   RECV:" msg)
      (swap! messages conj {:direction :recv
                            :raw msg
                            :parsed (try (edn/read-string msg) (catch Exception _ nil))}))

    (onClose [code reason remote]
      (trove/log! {:level :info :id :sente-compat/ws-close
                   :data {:code code :reason reason}})
      (println "   Closed:" code reason))

    (onError [^Exception ex]
      (trove/log! {:level :error :id :sente-compat/ws-error
                   :data {:error (.getMessage ex)}})
      (println "   Error:" (.getMessage ex)))))

;; Connect
(.connect ws-client)

;; Wait for connection
(deref connected 5000 false)

(when-not (realized? connected)
  (println "ERROR: WebSocket connection timeout")
  (System/exit 1))

;; Step 3: Wait for handshake
(println)
(println "3. Waiting for handshake (2 seconds)...")
(Thread/sleep 2000)

;; Step 4: Send a test event
(println)
(println "4. Sending test event...")

(def test-event "[:test/echo {:msg \"Hello from Babashka!\"}]")
(println "   SEND:" test-event)
(.send ws-client test-event)

(trove/log! {:level :debug :id :sente-compat/ws-send
             :data {:event test-event}})

;; Wait for response
(Thread/sleep 2000)

;; Step 5: Send ping
(println)
(println "5. Sending ping...")
(def ping-event "[:chsk/ws-ping]")
(println "   SEND:" ping-event)
(.send ws-client ping-event)
(Thread/sleep 1000)

;; Step 6: Analyze captured messages
(println)
(println "=== Captured Wire Format ===")
(println)

(doseq [{:keys [direction raw parsed]} @messages]
  (println (str "  " (name direction) ":"))
  (println "    Raw:" raw)
  (when parsed
    (println "    Parsed:" (pr-str parsed))
    (when (vector? parsed)
      (println "    Event ID:" (first parsed))
      (when (second parsed)
        (println "    Data:" (second parsed)))))
  (println))

;; Step 7: Summary
(println "=== Wire Format Summary ===")
(println)
(println "Messages captured:" (count @messages))

(let [handshakes (filter #(and (vector? (:parsed %))
                               (= :chsk/handshake (first (:parsed %))))
                         @messages)]
  (when (seq handshakes)
    (println)
    (println "Handshake format:")
    (let [hs (first handshakes)
          [_ [uid csrf hs-data first?]] (:parsed hs)]
      (println "  [:chsk/handshake [uid csrf-token handshake-data first?]]")
      (println "  UID:" uid)
      (println "  First?:" first?))))

;; Cleanup
(println)
(println "Closing connection...")
(.close ws-client)

(trove/log! {:level :info :id :sente-compat/complete
             :data {:messages-captured (count @messages)}})

(println "Done!")
