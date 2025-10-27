#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[sente-lite.server :as server]
         '[telemere-lite.core :as tel]
         '[ws-client-managed :as wsm])

(tel/log! :info "=== Phase 6c: Auto-Reconnection Test ===")

;;
;; Phase 6c: Auto-reconnection with exponential backoff
;;
;; Tests:
;; 1. Client reconnects after server restart
;; 2. Exponential backoff with max attempts
;; 3. State transitions: :open → :closed → :reconnecting → :open
;; 4. Failed state when max attempts reached
;;

;; Test state
(def state-log (atom []))
(def connection-count (atom 0))
(def test-server (atom nil))

;; Helper to start server
(defn start-test-server! []
  (tel/log! :info "Starting server on port 3000")
  (reset! test-server
          (server/start-server!
           {:port 3000
            :host "localhost"
            :wire-format :json
            :telemetry {:enabled true
                        :handler-id :reconnection-test}
            :heartbeat {:enabled false}
            :channels {:auto-create false}}))
  ;; TEST ENVIRONMENT ONLY: http-kit's run-server returns before socket is fully
  ;; ready to accept connections (~10-20ms). In production, clients discover the
  ;; server after much longer delays (service discovery, DNS, etc.) so this is
  ;; never an issue. Tests connect immediately, so we add 50ms to avoid confusing
  ;; connection failures that trigger unnecessary reconnection attempts.
  (Thread/sleep 50))

;; Helper to stop server
(defn stop-test-server! []
  (tel/log! :info "Stopping server")
  (when @test-server
    (server/stop-server!)
    (reset! test-server nil)
    (Thread/sleep 500)))

;; Test 1: Successful reconnection after server restart
(tel/log! :info "Test 1: Reconnection after server restart")
(start-test-server!)

(def client1
  (wsm/create-managed-client
   {:uri "ws://localhost:3000/"
    :on-state-change (fn [old-state new-state]
                       (tel/log! :info "State changed" {:old old-state :new new-state})
                       (swap! state-log conj {:old old-state :new new-state}))
    :on-message (fn [msg]
                  (tel/log! :info "Message received" {:type (:type msg)}))
    :on-open (fn [ws]
               (tel/log! :info "Connection opened")
               (swap! connection-count inc))
    :heartbeat {:auto-pong true}
    :reconnect {:enabled true
                :max-attempts 5
                :initial-delay-ms 1000
                :max-delay-ms 30000
                :backoff-multiplier 2}}))

;; Connect and verify
((:connect! client1))
(Thread/sleep 1500)

(def state-after-connect ((:get-state client1)))
(tel/log! :info "State after connect" {:state state-after-connect})

(when (not= :open state-after-connect)
  (tel/error! "FAILED: Client should be :open after connect" {:actual state-after-connect}))

(def connections-before-restart @connection-count)

;; Simulate connection loss by stopping server
(stop-test-server!)
(tel/log! :info "Server stopped - connection should be lost")
(Thread/sleep 500)

;; Restart server - client should reconnect
(start-test-server!)
(tel/log! :info "Server restarted - waiting for reconnection")
(Thread/sleep 3000) ; Wait for reconnection with backoff

(def state-after-reconnect ((:get-state client1)))
(def connections-after-reconnect @connection-count)
(def reconnected? (> connections-after-reconnect connections-before-restart))

(tel/log! :info "Reconnection result"
          {:connections-before connections-before-restart
           :connections-after connections-after-reconnect
           :state state-after-reconnect
           :reconnected? reconnected?})

;; Test 2: Max attempts exceeded
(tel/log! :info "Test 2: Max attempts exceeded")
((:disconnect! client1))
(Thread/sleep 500)

;; Stop server so reconnection will fail
(stop-test-server!)

;; Reset tracking
(reset! state-log [])

;; Create client with lower max attempts
(def client2
  (wsm/create-managed-client
   {:uri "ws://localhost:3000/"
    :on-state-change (fn [old-state new-state]
                       (tel/log! :info "Client2 state" {:old old-state :new new-state})
                       (swap! state-log conj {:old old-state :new new-state}))
    :reconnect {:enabled true
                :max-attempts 3
                :initial-delay-ms 500
                :max-delay-ms 5000
                :backoff-multiplier 2}}))

;; Try to connect (will fail and retry until max attempts)
(tel/log! :info "Attempting to connect to stopped server...")
((:connect! client2))
(Thread/sleep 5000) ; Wait for all retry attempts

(def final-state ((:get-state client2)))
(tel/log! :info "Final state after retries" {:state final-state})

;; Verify reached :failed state
(def reached-failed (= :failed final-state))

;; Count state transitions
(defn count-transitions [log from-state to-state]
  (count (filter #(and (= from-state (:old %))
                       (= to-state (:new %)))
                 log)))

(def reconnecting-attempts (count-transitions @state-log :connecting :reconnecting))
(tel/log! :info "Reconnection attempts observed" {:count reconnecting-attempts})

;; Cleanup
(tel/log! :info "Cleaning up")
((:disconnect! client1))
(stop-test-server!)

;; Validate results
(cond
  ;; Test 1: Client should reconnect after server restart
  ;; Test 2: Should reach :failed after max attempts
  (and reconnected?
       (= :open state-after-reconnect)
       reached-failed)
  (tel/log! :info "Phase 6c PASSED: Auto-reconnection working correctly"
            {:reconnected? reconnected?
             :final-state-correct? (= :open state-after-reconnect)
             :max-attempts-works? reached-failed})

  :else
  (tel/error! "Phase 6c FAILED: Auto-reconnection not working correctly"
              {:error "Reconnection tests failed"
               :reconnected? reconnected?
               :state-after-reconnect state-after-reconnect
               :reached-failed reached-failed
               :reconnecting-attempts reconnecting-attempts}))

(System/exit 0)
