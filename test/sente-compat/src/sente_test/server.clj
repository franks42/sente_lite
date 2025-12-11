(ns sente-test.server
  "Real Sente server for wire format compatibility testing.
   This server uses the official taoensso/sente library to verify
   that sente-lite can communicate with real Sente servers."
  (:require [clojure.core.async :as async :refer [go-loop <!]]
            [compojure.core :refer [defroutes GET POST]]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.anti-forgery :refer [*anti-forgery-token*]]
            [org.httpkit.server :as http-kit]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [get-sch-adapter]]
            [taoensso.timbre :as log]))

;; ============================================================================
;; Sente Channel Socket Setup
;; ============================================================================

(let [{:keys [ch-recv send-fn connected-uids
              ajax-post-fn ajax-get-or-ws-handshake-fn]}
      (sente/make-channel-socket! (get-sch-adapter)
                                  {:packer :edn
                                   :csrf-token-fn nil  ; Disable CSRF for testing
                                   :user-id-fn (fn [ring-req]
                                                 (get-in ring-req [:params :client-id]
                                                         (str (java.util.UUID/randomUUID))))})]

  (def ring-ajax-post ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk ch-recv)
  (def chsk-send! send-fn)
  (def connected-uids connected-uids))

;; ============================================================================
;; Event Handlers
;; ============================================================================

(defmulti event-handler :id)

(defmethod event-handler :default
  [{:keys [id ?data ring-req ?reply-fn send-fn] :as event}]
  (log/info "Unhandled event:" id)
  (log/debug "Event data:" ?data)
  (when ?reply-fn
    (?reply-fn {:echo id :data ?data :status :unhandled})))

(defmethod event-handler :chsk/ws-ping [_]
  ;; Ignore ping events (handled by Sente internally)
  nil)

(defmethod event-handler :chsk/uidport-open
  [{:keys [uid] :as event}]
  (log/info "Client connected:" uid))

(defmethod event-handler :chsk/uidport-close
  [{:keys [uid] :as event}]
  (log/info "Client disconnected:" uid))

(defmethod event-handler :test/echo
  [{:keys [?data ?reply-fn uid] :as event}]
  (log/info "Echo request from" uid ":" ?data)
  (when ?reply-fn
    (?reply-fn {:echo ?data :timestamp (System/currentTimeMillis)})))

(defmethod event-handler :test/ping
  [{:keys [?data ?reply-fn uid] :as event}]
  (log/info "Ping from" uid)
  (when ?reply-fn
    (?reply-fn {:pong true :server-time (System/currentTimeMillis)})))

(defmethod event-handler :test/broadcast
  [{:keys [?data uid] :as event}]
  (log/info "Broadcast request from" uid ":" ?data)
  (doseq [other-uid (:any @connected-uids)]
    (chsk-send! other-uid [:test/broadcast-msg {:from uid :data ?data}])))

;; ============================================================================
;; Event Router
;; ============================================================================

(defn start-event-router! []
  (go-loop []
    (when-let [event (<! ch-chsk)]
      (try
        (event-handler event)
        (catch Exception e
          (log/error e "Error handling event:" (:id event))))
      (recur))))

;; ============================================================================
;; Ring Routes
;; ============================================================================

(defroutes ring-routes
  (GET "/" req
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str "<!DOCTYPE html>
<html>
<head><title>Sente Test Server</title></head>
<body>
<h1>Sente Test Server</h1>
<p>This is a real Sente server for wire format testing.</p>
<p>CSRF Token: " *anti-forgery-token* "</p>
<p>WebSocket endpoint: /chsk</p>
<h2>Test Events</h2>
<ul>
<li><code>[:test/echo {:msg \"hello\"}]</code> - Echo back data</li>
<li><code>[:test/ping]</code> - Ping/pong test</li>
<li><code>[:test/broadcast {:msg \"hi all\"}]</code> - Broadcast to all clients</li>
</ul>
</body>
</html>")})

  (GET "/csrf-token" req
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str {:csrf-token *anti-forgery-token*})})

  (GET "/chsk" req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (ring-ajax-post req))

  (route/not-found "Not Found"))

;; ============================================================================
;; Middleware
;; ============================================================================

(def ring-handler
  (-> ring-routes
      (wrap-defaults (-> site-defaults
                         (assoc-in [:security :anti-forgery] false)
                         (assoc-in [:static :resources] "public")))))

;; ============================================================================
;; Server Lifecycle
;; ============================================================================

(defonce server (atom nil))

(defn stop-server! []
  (when-let [s @server]
    (s :timeout 100)
    (reset! server nil)
    (log/info "Server stopped")))

(defn start-server! [port]
  (stop-server!)
  (start-event-router!)
  (reset! server (http-kit/run-server ring-handler {:port port}))
  (log/info "Sente test server started on port" port)
  (log/info "WebSocket endpoint: ws://localhost:" port "/chsk")
  (log/info "CSRF token endpoint: http://localhost:" port "/csrf-token"))

(defn -main [& args]
  (let [port (Integer/parseInt (or (first args) "8090"))]
    (start-server! port)
    ;; Keep the server running
    @(promise)))
