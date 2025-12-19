(ns sente-lite.sente-interop-test
  "Comprehensive Sente interoperability tests for Milestone 3B.
   Tests buffered events, callback reply/timeout, and ping/pong."
  (:require [clojure.test :refer [deftest testing is]]
            [sente-lite.wire-format :as wf]
            [sente-lite.serialization :as ser]
            #?(:clj [clojure.core.async :as async :refer [go <! >! timeout chan]]
               :cljs [cljs.core.async :as async :refer [<! >! timeout chan]])
            #?(:cljs [cljs.core.async.interop :refer-macros [<p!]])))

;;; Test helpers

(defn simulate-sente-server-message
  "Simulate a message as it would come from a real Sente server"
  [event-or-events]
  (if (and (vector? event-or-events)
           (vector? (first event-or-events))
           (keyword? (ffirst event-or-events)))
    ;; Already buffered format
    (pr-str event-or-events)
    ;; Single event - wrap in buffer
    (pr-str [event-or-events])))

(defn parse-client-message
  "Parse a message as a sente-lite client would"
  [raw-msg]
  (try
    (let [parsed (wf/deserialize raw-msg :edn)]
      (cond
        ;; Buffered events from Sente
        (wf/buffered-events? parsed)
        {:type :buffered
         :events (wf/unwrap-buffered-events parsed)}

        ;; Empty vector is treated as empty buffer
        (and (vector? parsed) (empty? parsed))
        {:type :buffered
         :events []}

        ;; Single event (including those with nil event-id)
        (vector? parsed)
        {:type :single
         :event parsed}

        :else
        {:type :unknown
         :raw parsed}))
    (catch #?(:clj Exception :cljs js/Error) e
      {:type :unknown
       :error (.getMessage e)
       :raw raw-msg})))

;;; Buffered Events Tests

(deftest buffered-events-test
  (testing "Parsing buffered events from Sente server"
    (let [events [[:chsk/handshake ["uid-123" "csrf-token"]]
                  [:my/event {:data "test"}]
                  [:chsk/ws-ping]]
          msg (simulate-sente-server-message events)
          parsed (parse-client-message msg)]

      (is (= :buffered (:type parsed)))
      (is (= 3 (count (:events parsed))))
      (is (= :chsk/handshake (first (first (:events parsed)))))
      (is (= :my/event (first (second (:events parsed)))))
      (is (= :chsk/ws-ping (first (nth (:events parsed) 2))))))

  (testing "Empty buffer"
    ;; When we pass [] to simulate-sente-server-message, it wraps it as [[]]
    ;; So we need to test with the actual wire format
    (let [msg (pr-str [])  ; Direct empty buffer
          parsed (parse-client-message msg)]

      (is (= :buffered (:type parsed)))
      (is (= 0 (count (:events parsed))))))

  (testing "Single event in buffer"
    (let [msg (simulate-sente-server-message [[:test/event {:value 42}]])
          parsed (parse-client-message msg)]

      (is (= :buffered (:type parsed)))
      (is (= 1 (count (:events parsed))))
      (is (= [:test/event {:value 42}] (first (:events parsed)))))))

;;; Callback Reply Tests

(deftest callback-reply-test
  (testing "Event with callback format"
    (let [cb-uuid (wf/generate-cb-uuid)
          event-with-cb (wf/encode-event-with-callback :test/echo {:msg "Hello"} cb-uuid)
          wire-msg (pr-str event-with-cb)]

      (is (vector? event-with-cb))
      (is (= 2 (count event-with-cb)))
      (is (vector? (first event-with-cb)))
      (is (string? (second event-with-cb)))
      (is (= cb-uuid (second event-with-cb)))))

  (testing "Parsing callback reply from server"
    (let [cb-uuid "test-cb-123"
          ;; Sente sends callback replies as: [:chsk/recv [cb-uuid data]]
          reply [:chsk/recv [cb-uuid {:result "success"}]]
          msg (simulate-sente-server-message reply)
          parsed (parse-client-message msg)]

      (is (= :buffered (:type parsed)))
      (let [event (first (:events parsed))]
        (is (= :chsk/recv (first event)))
        (is (vector? (second event)))
        (is (= cb-uuid (first (second event))))
        (is (= {:result "success"} (second (second event)))))))

  (testing "Callback timeout handling"
    ;; This tests the pattern, actual timeout would be handled by client
    (let [cb-uuid (wf/generate-cb-uuid)
          event (wf/encode-event-with-callback :test/slow {:delay 5000} cb-uuid)]

      (is (= cb-uuid (second event)))
      ;; In real usage, client would track this UUID and timeout after X ms
      (is (string? cb-uuid))
      (is (re-matches #"[a-f0-9-]+" cb-uuid)))))

;;; Ping/Pong Tests

(deftest ping-pong-test
  (testing "Ping event format"
    (let [ping (wf/make-ws-ping)]
      (is (= [:chsk/ws-ping] ping))))

  (testing "Pong event format"
    (let [pong (wf/make-ws-pong)]
      (is (= [:chsk/ws-pong] pong))))

  (testing "Ping from server"
    (let [msg (simulate-sente-server-message [:chsk/ws-ping])
          parsed (parse-client-message msg)]

      (is (= :buffered (:type parsed)))
      (is (= [:chsk/ws-ping] (first (:events parsed))))))

  (testing "Pong response wire format"
    (let [pong (wf/make-ws-pong)
          wire-msg (pr-str pong)]

      (is (= "[:chsk/ws-pong]" wire-msg)))))

;;; System Events Tests

(deftest system-events-test
  (testing "All Sente system events"
    (let [events [[:chsk/handshake ["uid" nil]]
                  [:chsk/state {:type :ws :open? true}]
                  [:chsk/recv [:my/event {:data 1}]]
                  [:chsk/ws-ping]
                  [:chsk/ws-pong]]
          msg (simulate-sente-server-message events)
          parsed (parse-client-message msg)]

      (is (= :buffered (:type parsed)))
      (is (= 5 (count (:events parsed))))

      ;; All should be recognized as system events
      (doseq [event (:events parsed)]
        (let [event-id (first event)]
          (is (keyword? event-id))
          (is (= "chsk" (namespace event-id))))))))

;;; Wire Format Compatibility Tests

(deftest wire-format-compatibility-test
  (testing "EDN format compatibility"
    (let [event [:test/event {:string "hello"
                              :number 42
                              :boolean true
                              :nil nil
                              :keyword :test
                              :vector [1 2 3]
                              :map {:a 1 :b 2}
                              :set #{1 2 3}}]
          wire-msg (simulate-sente-server-message event)
          parsed (parse-client-message wire-msg)]

      (is (= :buffered (:type parsed)))
      (is (= event (first (:events parsed))))))

  (testing "Special characters in strings"
    (let [event [:test/event {:msg "Hello \"world\" \n\t\\"}]
          wire-msg (simulate-sente-server-message event)
          parsed (parse-client-message wire-msg)]

      (is (= :buffered (:type parsed)))
      (is (= event (first (:events parsed))))))

  (testing "Large payload"
    (let [large-data (apply str (repeat 1000 "x"))
          event [:test/event {:data large-data}]
          wire-msg (simulate-sente-server-message event)
          parsed (parse-client-message wire-msg)]

      (is (= :buffered (:type parsed)))
      (is (= 1000 (count (get-in (first (:events parsed)) [1 :data])))))))

;;; :chsk/recv Wrapping Tests (for Milestone 3A integration)

(deftest chsk-recv-wrapping-test
  (testing "Server push with :chsk/recv wrapper"
    (let [;; Simulating server with :wrap-recv-evs? true
          wrapped-event [:chsk/recv [:my/push {:data "server push"}]]
          msg (simulate-sente-server-message wrapped-event)
          parsed (parse-client-message msg)]

      (is (= :buffered (:type parsed)))
      (let [event (first (:events parsed))]
        (is (= :chsk/recv (first event)))
        (is (= [:my/push {:data "server push"}] (second event))))))

  (testing "Multiple wrapped events in buffer"
    (let [events [[:chsk/recv [:event/one {:n 1}]]
                  [:chsk/recv [:event/two {:n 2}]]
                  [:chsk/ws-ping]]  ; System event not wrapped
          msg (simulate-sente-server-message events)
          parsed (parse-client-message msg)]

      (is (= :buffered (:type parsed)))
      (is (= 3 (count (:events parsed))))
      ;; First two are wrapped
      (is (= :chsk/recv (first (first (:events parsed)))))
      (is (= :chsk/recv (first (second (:events parsed)))))
      ;; Ping is not wrapped
      (is (= :chsk/ws-ping (first (nth (:events parsed) 2)))))))

;;; Error Handling Tests

(deftest error-handling-test
  (testing "Malformed message"
    (let [parsed (parse-client-message "not-valid-edn")]
      (is (= :unknown (:type parsed)))))

  (testing "Non-vector event"
    (let [parsed (parse-client-message (pr-str {:not "an event"}))]
      (is (= :unknown (:type parsed)))))

  (testing "Event without event-id"
    (let [parsed (parse-client-message (pr-str [nil {:data "test"}]))]
      ;; Should still parse but event-id is nil
      (is (= :single (:type parsed)))
      (is (nil? (first (:event parsed)))))))
