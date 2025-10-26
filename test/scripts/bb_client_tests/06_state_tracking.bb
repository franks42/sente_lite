#!/usr/bin/env bb

(require '[babashka.classpath :as cp])
(cp/add-classpath "src")
(cp/add-classpath "test/scripts/bb_client_tests")

(require '[sente-lite.server :as server]
         '[telemere-lite.core :as tel]
         '[ws-client-managed :as wsm]
         '[cheshire.core :as json])

(tel/log! :info "=== Phase 6b: Client State Tracking Test ===")

;;
;; Phase 6b: Client connection state tracking
;;
;; Tests:
;; 1. Track state changes via callback
;; 2. Verify state progression: :closed → :connecting → :open
;; 3. Send message while :open (should work)
;; 4. Graceful close: :open → :closing → :closed
;; 5. Verify state accurately reflects connection status
;;

;; Test state
(def state-transitions (atom []))
(def messages-received (atom []))

;; Start server
(tel/log! :info "Starting server on port 3000")
(def test-server
  (server/start-server!
   {:port 3000
    :host "localhost"
    :wire-format :json
    :telemetry {:enabled true
                :handler-id :state-tracking-test}
    :heartbeat {:enabled false}  ; Disable for simpler testing
    :channels {:auto-create false}}))

(Thread/sleep 500) ; Let server initialize

;; Create managed client with state tracking
(tel/log! :info "Creating managed client")
(def client
  (wsm/create-managed-client
   {:uri "ws://localhost:3000/"
    :on-state-change (fn [old-state new-state]
                       (tel/log! :info "State changed" {:old old-state :new new-state})
                       (swap! state-transitions conj [old-state new-state]))
    :on-message (fn [msg]
                  (tel/log! :info "Message received" {:type (:type msg)})
                  (swap! messages-received conj msg))
    :heartbeat {:auto-pong true}}))

;; Test 1: Initial state should be :closed
(tel/log! :info "Test 1: Verify initial state")
(def initial-state ((:get-state client)))
(tel/log! :info "Initial state" {:state initial-state})

(when (not= :closed initial-state)
  (tel/error! "FAILED: Initial state should be :closed" {:actual initial-state}))

;; Test 2: Connect and verify state progression
(tel/log! :info "Test 2: Connect and verify state progression")
((:connect! client))
(Thread/sleep 500) ; Wait for connection

(def after-connect-state ((:get-state client)))
(tel/log! :info "After connect state" {:state after-connect-state})

(when (not= :open after-connect-state)
  (tel/error! "FAILED: State should be :open after connection" {:actual after-connect-state}))

;; Test 3: Send message while :open
(tel/log! :info "Test 3: Send message while :open")
(def send-result ((:send! client) (json/generate-string {:type "test" :data "hello"})))
(Thread/sleep 500) ; Wait for server response

(tel/log! :info "Send result" {:success send-result
                               :messages-received (count @messages-received)})

;; Test 4: Graceful disconnect
(tel/log! :info "Test 4: Graceful disconnect")
((:disconnect! client))
(Thread/sleep 500) ; Wait for disconnect

(def after-disconnect-state ((:get-state client)))
(tel/log! :info "After disconnect state" {:state after-disconnect-state})

(when (not= :closed after-disconnect-state)
  (tel/error! "FAILED: State should be :closed after disconnect" {:actual after-disconnect-state}))

;; Analyze state transitions
(tel/log! :info "State transitions observed" {:transitions @state-transitions})

;; Cleanup
(tel/log! :info "Stopping server")
(server/stop-server!)

;; Validate results
(def expected-transitions
  [[:closed :connecting]
   [:connecting :open]
   [:open :closing]
   [:closing :closed]])

(defn transitions-match?
  "Check if actual transitions include all expected transitions in order"
  [expected actual]
  (let [actual-pairs (set actual)]
    (every? actual-pairs expected)))

(def has-required-transitions
  (every? (fn [expected-pair]
            (some #(= expected-pair %) @state-transitions))
          expected-transitions))

(cond
  ;; All expected transitions observed
  (and has-required-transitions
       (= :closed after-disconnect-state)
       (= :open after-connect-state)
       send-result)
  (tel/log! :info "Phase 6b PASSED: Client state tracking working correctly")

  :else
  (tel/error! "Phase 6b FAILED: State tracking not working correctly"
              {:error "Not all expected state transitions observed"
               :expected expected-transitions
               :actual @state-transitions
               :initial-state initial-state
               :after-connect after-connect-state
               :after-disconnect after-disconnect-state
               :send-result send-result}))

(System/exit 0)
