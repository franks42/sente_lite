(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "modules/nrepl/src")

(ns test-nrepl-ns-isolation
  (:require [clojure.test :refer [deftest is testing run-tests]]
            [nrepl-sente.server :as nrepl-server]
            [sente-lite.server :as server]
            [sente-lite.client-bb :as client]))

;; Test setup
(def port 8090)
(def ws-url (str "ws://localhost:" port "/ws"))

(defn start-test-server! []
  (let [nrepl-handler (nrepl-server/make-nrepl-handler server/send-event-to-connection!)]
    (server/start-server! {:port port
                           :on-message nrepl-handler})))

(defn stop-test-server! []
  (server/stop-server!))

(defn make-client []
  (let [prom (promise)
        client (client/make-client!
                {:url ws-url
                 :on-open (fn [uid] (deliver prom uid))})]
    (deref prom 2000 nil)
    client))

(defn eval! [client code]
  (let [prom (promise)
        req-id (str (java.util.UUID/randomUUID))]
    (client/on! client
                {:pred (fn [msg] (= (get-in msg [:data :id]) req-id))
                 :once? true
                 :callback (fn [msg] (deliver prom (:data msg)))})

    (client/send! client [:nrepl/request {:op "eval" :code code :id req-id}])
    (deref prom 2000 {:error :timeout})))

(deftest test-ns-isolation
  (testing "Namespace isolation between sessions"

    (let [client-a (make-client)
          client-b (make-client)]

      (is client-a "Client A connected")
      (is client-b "Client B connected")

      ;; 1. Client A switches to ns-a
      (let [res (eval! client-a "(in-ns 'test-ns-a)")]
        (is (= "test-ns-a" (:ns res)) "Client A switched to test-ns-a"))

      ;; 2. Client B switches to ns-b
      (let [res (eval! client-b "(in-ns 'test-ns-b)")]
        (is (= "test-ns-b" (:ns res)) "Client B switched to test-ns-b"))

      ;; 3. Client A defines var in what it thinks is ns-a
      ;; If bug exists, global !last-ns is test-ns-b, so this happens in b
      (eval! client-a "(def val-a :from-a)")

      ;; 4. Client B defines var in what it thinks is ns-b
      (eval! client-b "(def val-b :from-b)")

      ;; 5. Verify Client B sees its own value
      (let [res (eval! client-b "val-b")]
        (is (= ":from-b" (:value res)) "Client B sees its own value"))

      ;; 6. THIS IS THE CRITICAL CHECK:
      ;; Does Client A still see test-ns-a as its current NS?
      (let [res (eval! client-a "(str *ns*)")]
        ;; With the BUG, Client A's next eval calls (binding [*ns* @!last-ns]...)
        ;; Since global !last-ns was set by B to 'test-ns-b', Client A is now in 'test-ns-b'!
        (is (= "\"test-ns-a\"" (:value res))
            (str "Client A should still be in test-ns-a, but was: " (:value res))))

      ;; 7. Does Client A see its variable?
      (let [res (eval! client-a "val-a")]
        (is (= ":from-a" (:value res))
            (str "Client A should find val-a in its namespace")))

      (client/close! client-a)
      (client/close! client-b))))

(defn run []
  (println "\n=== Starting NS Isolation Test ===")
  (start-test-server!)
  (Thread/sleep 500)
  (try
    (let [results (run-tests 'test-nrepl-ns-isolation)]
      (System/exit (if (zero? (+ (:fail results) (:error results))) 0 1)))
    (finally
      (stop-test-server!)
      (shutdown-agents))))

(run)
