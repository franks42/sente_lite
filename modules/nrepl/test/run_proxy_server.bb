#!/usr/bin/env bb
;;
;; Start nREPL proxy test server
;;
;; This starts:
;; 1. Sente-lite server with nREPL handlers
;; 2. A peer that acts as nREPL server (evaluates code)
;; 3. Bencode proxy on port 1347
;;
;; Connect with: nrepl://localhost:1347
;;

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "modules/nrepl/src")

(require '[babashka.http-client.websocket :as ws]
         '[clojure.edn :as edn]
         '[sente-lite.server :as server]
         '[nrepl-sente.server :as nrepl-server]
         '[nrepl-sente.client :as client]
         '[nrepl-sente.proxy :as proxy]
         '[nrepl-sente.protocol :as proto])

(println "=== nREPL Proxy Test Server ===")
(println)

;; Response handler for routing responses
(def response-handler (client/make-response-handler))

;; Parse WebSocket messages
(defn parse-message [raw-data]
  (try
    (let [parsed (edn/read-string (str raw-data))]
      (if (vector? parsed)
        {:event-id (first parsed) :data (second parsed)}
        {:error :invalid-format}))
    (catch Exception e
      {:error :parse-failed})))

;; 1. Start sente-lite server (ephemeral port)
(println "1. Starting sente-lite server...")
(let [nrepl-handler (nrepl-server/make-nrepl-handler server/send-event-to-connection!)
      combined-handler (fn [conn-id event-id data]
                         (nrepl-handler conn-id event-id data)
                         (response-handler conn-id event-id data))]
  (server/start-server! {:port 0  ; Let OS assign port
                         :wire-format :edn
                         :heartbeat {:enabled false}
                         :on-message combined-handler}))
(Thread/sleep 500)
(def sente-port (server/get-server-port))
(println "   Sente server started on port" sente-port)

;; 2. Connect peer as nREPL server
(println)
(println "2. Connecting nREPL peer (acts as eval server)...")
(def peer-opened (promise))
(def peer-handshake (promise))

(def peer-ws
  (ws/websocket
    {:uri (str "ws://localhost:" sente-port "/")
     :on-open (fn [ws] (deliver peer-opened true))
     :on-message (fn [ws raw-data last?]
                   (let [parsed (parse-message raw-data)]
                     (case (:event-id parsed)
                       :chsk/handshake
                       (deliver peer-handshake true)

                       :nrepl/request
                       ;; Evaluate and respond
                       (let [request (:data parsed)
                             response (nrepl-server/dispatch-request request)]
                         (ws/send! ws (pr-str (proto/wrap-response response))))

                       nil)))
     :on-close (fn [ws code reason]
                 (println "Peer disconnected:" reason))
     :on-error (fn [ws err]
                 (println "Peer error:" (.getMessage err)))}))

(deref peer-opened 3000 nil)
(deref peer-handshake 3000 nil)
(Thread/sleep 200)
(println "   nREPL peer connected")

;; 3. Start proxy (ephemeral port, discovered via registry)
(println)
(println "3. Starting bencode proxy...")
(proxy/start! {:port 0})  ; Let OS assign port
(def proxy-url (proxy/get-proxy-url))
(println "   Proxy started:" proxy-url)

(println)
(println "========================================")
(println "  nREPL proxy ready!")
(println "  Connect with:" proxy-url)
(println "========================================")
(println)
(println "Press Ctrl+C to stop")

;; Keep running
@(promise)
