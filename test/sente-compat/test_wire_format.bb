#!/usr/bin/env bb
;; Test script to capture and analyze Sente wire format
;; This connects to a Sente server via raw WebSocket to see the actual wire format

(require '[babashka.http-client :as http]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def host "localhost")
(def port 8090)

(println "=== Sente Wire Format Analysis ===")
(println)

;; Step 1: Get CSRF token
(println "1. Fetching CSRF token...")
(def csrf-response
  (try
    (http/get (str "http://" host ":" port "/csrf-token"))
    (catch Exception e
      (println "ERROR: Could not connect to Sente server at" (str host ":" port))
      (println "Make sure to start the server first: bb sente-server")
      (System/exit 1))))

(def csrf-data (edn/read-string (:body csrf-response)))
(def csrf-token (:csrf-token csrf-data))
(println "   CSRF Token:" csrf-token)
(println)

;; Step 2: Analyze the handshake endpoint
(println "2. Analyzing WebSocket handshake endpoint...")
(def ws-url (str "ws://" host ":" port "/chsk?csrf-token=" csrf-token))
(println "   WebSocket URL:" ws-url)
(println)

;; Step 3: Document expected Sente wire format
(println "3. Expected Sente Wire Format:")
(println)
(println "   Event Structure: [event-id ?data]")
(println "   Examples:")
(println "     [:chsk/handshake [uid csrf-token handshake-data first?]]")
(println "     [:chsk/ws-ping]")
(println "     [:test/echo {:msg \"hello\"}]")
(println)
(println "   With callback: [event cb-uuid]")
(println "   Reply format: [:chsk/reply {:cb-uuid \"...\" :data ...}]")
(println)

;; Step 4: Show how to test manually
(println "4. Manual Testing:")
(println)
(println "   Start server:  bb sente-server")
(println "   Start client:  bb sente-client")
(println)
(println "   Or use websocat for raw WebSocket testing:")
(println "   websocat " ws-url)
(println)

;; Step 5: Document the wire format for sente-lite
(println "5. Sente Wire Format Summary for sente-lite:")
(println)
(println "   ┌─────────────────────────────────────────────────────────┐")
(println "   │ Sente Event Format                                      │")
(println "   ├─────────────────────────────────────────────────────────┤")
(println "   │ Basic event:     [event-id data]                        │")
(println "   │ With callback:   [[event-id data] cb-uuid]              │")
(println "   │ Reply:           [:chsk/reply {:cb-uuid X :data Y}]     │")
(println "   ├─────────────────────────────────────────────────────────┤")
(println "   │ System Events (server → client)                         │")
(println "   │   :chsk/handshake  [uid csrf handshake-data first?]     │")
(println "   │   :chsk/state      [old-state new-state]                │")
(println "   │   :chsk/recv       [event-id data] (wrapped push)       │")
(println "   │   :chsk/ws-ping                                         │")
(println "   ├─────────────────────────────────────────────────────────┤")
(println "   │ System Events (client → server)                         │")
(println "   │   :chsk/ws-ping                                         │")
(println "   │   :chsk/ws-pong                                         │")
(println "   ├─────────────────────────────────────────────────────────┤")
(println "   │ Serialization: EDN or Transit+JSON (via packers)        │")
(println "   └─────────────────────────────────────────────────────────┘")
(println)

(println "=== Analysis Complete ===")
(println)
(println "Next steps:")
(println "  1. Start Sente server: cd test/sente-compat && clojure -M:server")
(println "  2. Start Sente client: cd test/sente-compat && clojure -M:client")
(println "  3. Observe wire format in logs")
(println "  4. Create sente-lite wire format based on observations")
