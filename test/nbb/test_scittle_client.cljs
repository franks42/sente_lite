(ns test-scittle-client
  "Test the actual client_scittle.cljs in nbb environment")

(println "=== Test: client_scittle.cljs in nbb ===")
(println)
(println "js/WebSocket available:" (some? js/WebSocket))

;; Now load the client - need to add src to classpath
;; In nbb we use :require directly
(require '[sente-lite.client-scittle :as client])

(println "Client module loaded!")
(println)

;; Test state
(def handshake-received (atom nil))
(def echo-received (atom nil))

(println "1. Creating client...")
(def client-id
  (client/make-client!
    {:url "ws://localhost:9090/"
     :auto-reconnect? false
     :on-open (fn [uid]
                (println "   on-open called, uid:" uid)
                (reset! handshake-received uid))
     :on-message (fn [event-id data]
                   (println "   on-message:" event-id)
                   (when (= event-id :sente-lite/echo)
                     (reset! echo-received data)))
     :on-close (fn [event]
                 (println "   on-close called"))}))

(println "   Client created:" client-id)

;; Wait for handshake then run tests
(js/setTimeout
  (fn []
    (println)
    (if @handshake-received
      (do
        (println "2. Handshake OK, uid:" @handshake-received)
        
        (println)
        (println "3. Sending echo...")
        (client/send! client-id [:test/echo {:msg "Hello from nbb via client_scittle!"}])
        
        (js/setTimeout
          (fn []
            (println)
            (if @echo-received
              (println "4. Echo received OK!")
              (println "4. Echo NOT received"))
            
            (println)
            (println "5. Testing subscribe...")
            (client/subscribe! client-id "test-channel")
            
            (js/setTimeout
              (fn []
                (println)
                (println "=== Summary ===")
                (println "Handshake:" (if @handshake-received "✓" "✗"))
                (println "Echo:" (if @echo-received "✓" "✗"))
                (println "Status:" (client/get-status client-id))
                (println "Stats:" (client/get-stats client-id))
                
                (println)
                (println "Closing...")
                (client/close! client-id)
                (println "Done!"))
              1500))
          1000))
      (println "2. Handshake FAILED - no uid received")))
  2000)
