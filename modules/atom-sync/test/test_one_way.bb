#!/usr/bin/env bb

;; =============================================================================
;; atom-sync One-Way Sync Test (BB-to-BB)
;; =============================================================================
;;
;; Tests one-way atom synchronization: Publisher → Subscriber via sente-lite.
;;
;; Architecture:
;;   Publisher Client --[publish]--> Server --[channel-msg]--> Subscriber Client
;;
;; Run from project root:
;;   bb modules/atom-sync/test/test_one_way.bb
;;
;; =============================================================================

;; Add our source paths to classpath
(require '[babashka.classpath :refer [add-classpath]])
(add-classpath "src")
(add-classpath "modules/atom-sync/src")
(add-classpath "modules/log-routing/src")

(require '[sente-lite.server :as server]
         '[sente-lite.client-bb :as client]
         '[atom-sync.publisher :as pub]
         '[atom-sync.subscriber :as sub]
         '[log-routing.sender :as log-sender]
         '[log-routing.receiver :as log-receiver]
         '[taoensso.trove :as trove])

(println "=== atom-sync One-Way Test ===\n")

;; =============================================================================
;; Setup
;; =============================================================================

(def server-port 19998)
(def updates-received (atom []))

;; 1. Start server
(println "1. Starting server on port" server-port "...")
(server/start-server! {:port server-port})
(Thread/sleep 500)
(println "   Server started.\n")

;; 2. Connect publisher client
(println "2. Connecting publisher client...")
(def publisher-client
  (client/make-client!
    {:url (str "ws://localhost:" server-port "/ws")
     :client-id "publisher"}))
(Thread/sleep 500)
(println "   Publisher connected.\n")

;; 3. Connect subscriber client
(println "3. Connecting subscriber client...")
(def subscriber-client
  (client/make-client!
    {:url (str "ws://localhost:" server-port "/ws")
     :client-id "subscriber"}))
(Thread/sleep 500)
(println "   Subscriber connected.\n")

;; 4. Set up log-routing for observability
(println "4. Setting up log-routing for observability...")
(def log-handler-id
  (log-receiver/start! subscriber-client
    {:handler (fn [log-entry]
                (println "   [LOG]" (:level log-entry) (:ns log-entry)
                         ":" (pr-str (:data log-entry))))}))
(Thread/sleep 100)

;; Wrap Trove on publisher to send logs to subscriber
(def original-log-fn trove/*log-fn*)
(trove/set-log-fn!
  (log-sender/make-remote-log-fn original-log-fn publisher-client
    {:source-id "publisher"}))
(println "   Log routing configured.\n")

;; 5. Create atoms
(println "5. Creating source and target atoms...")
(def source-atom (atom {:count 0 :items []}))
(def target-atom (atom {}))
(println "   Source atom:" @source-atom)
(println "   Target atom:" @target-atom "\n")

;; 6. Start subscriber
(println "6. Starting subscriber...")
(def sub-handler-id
  (sub/start! subscriber-client target-atom
    {:atom-id :app-state
     :on-update (fn [old new]
                  (swap! updates-received conj {:old old :new new})
                  (println "   [SYNC] Updated:" old "->" new))}))
(Thread/sleep 200)
(println "   Subscriber started, handler-id:" sub-handler-id "\n")

;; 7. Start publisher
(println "7. Starting publisher...")
(def pub-watch-key
  (pub/start! publisher-client source-atom
    {:atom-id :app-state}))
(Thread/sleep 200)
(println "   Publisher started, watch-key:" pub-watch-key "\n")

;; =============================================================================
;; Test
;; =============================================================================

(println "8. Making changes to source atom...")
(Thread/sleep 200)

;; Change 1: Update count
(println "   - Setting :count to 1...")
(swap! source-atom assoc :count 1)
(Thread/sleep 300)

;; Change 2: Add item
(println "   - Adding item \"apple\"...")
(swap! source-atom update :items conj "apple")
(Thread/sleep 300)

;; Change 3: Add another item and update count
(println "   - Adding item \"banana\" and setting :count to 2...")
(swap! source-atom #(-> % (update :items conj "banana") (assoc :count 2)))
(Thread/sleep 300)

(println "   3 changes made.\n")

;; Wait for sync
(println "9. Waiting for sync...")
(Thread/sleep 500)

;; =============================================================================
;; Verify
;; =============================================================================

(println "\n=== Results ===")
(println "Source atom:" @source-atom)
(println "Target atom:" @target-atom)
(println "Updates received:" (count @updates-received))

(def source-value @source-atom)
(def target-value @target-atom)
(def expected-count 3)

(def atoms-match? (= source-value target-value))
(def updates-match? (= expected-count (count @updates-received)))
(def success? (and atoms-match? updates-match?))

(println)
(println "Atoms match?" atoms-match?)
(println "Updates received?" updates-match? "(expected" expected-count ", got" (count @updates-received) ")")

(println)
(if success?
  (println "SUCCESS: Source and target atoms are in sync!")
  (do
    (println "FAILED!")
    (when (not atoms-match?)
      (println "  - Atoms don't match"))
    (when (not updates-match?)
      (println "  - Wrong number of updates"))))

;; =============================================================================
;; Cleanup
;; =============================================================================

(println "\n10. Cleaning up...")
(trove/set-log-fn! original-log-fn)
(pub/stop! source-atom pub-watch-key)
(sub/stop! subscriber-client sub-handler-id)
(client/close! publisher-client)
(client/close! subscriber-client)
(server/stop-server!)
(println "    Done.\n")

;; Exit with appropriate code
(System/exit (if success? 0 1))
