(ns sente-lite.wire-format-test
  "Tests for sente-lite wire format (Sente-compatible)"
  (:require [clojure.test :refer [deftest testing is]]
            [sente-lite.wire-format :as wf]))

;; ============================================================================
;; Event Encoding Tests
;; ============================================================================

(deftest encode-event-test
  (testing "Simple event encoding"
    (is (= [:my-app/hello nil] (wf/encode-event :my-app/hello)))
    (is (= [:my-app/hello {:msg "hi"}] (wf/encode-event :my-app/hello {:msg "hi"}))))

  (testing "System event encoding"
    (is (= [:chsk/ws-ping nil] (wf/encode-event :chsk/ws-ping)))))

(deftest encode-event-with-callback-test
  (testing "Event with explicit callback UUID"
    (let [event (wf/encode-event-with-callback :my-app/get-user {:id 123} "cb-123")]
      (is (= [[:my-app/get-user {:id 123}] "cb-123"] event))))

  (testing "Event with auto-generated callback UUID"
    (let [event (wf/encode-event-with-callback :my-app/get-user {:id 123})]
      (is (vector? event))
      (is (= 2 (count event)))
      (is (vector? (first event)))
      (is (string? (second event))))))

;; ============================================================================
;; Event Decoding Tests
;; ============================================================================

(deftest decode-event-test
  (testing "Simple event decoding"
    (let [result (wf/decode-event [:my-app/hello {:msg "hi"}])]
      (is (= :my-app/hello (:event-id result)))
      (is (= {:msg "hi"} (:data result)))
      (is (nil? (:cb-uuid result)))))

  (testing "Event with nil data"
    (let [result (wf/decode-event [:my-app/ping])]
      (is (= :my-app/ping (:event-id result)))
      (is (nil? (:data result)))))

  (testing "Event with callback"
    (let [result (wf/decode-event [[:my-app/get-user {:id 123}] "cb-456"])]
      (is (= :my-app/get-user (:event-id result)))
      (is (= {:id 123} (:data result)))
      (is (= "cb-456" (:cb-uuid result)))))

  (testing "Invalid event - not a vector"
    (let [result (wf/decode-event {:type "message"})]
      (is (= :not-vector (:error result)))))

  (testing "Invalid event - empty vector"
    (let [result (wf/decode-event [])]
      (is (= :empty-vector (:error result)))))

  (testing "Invalid event - non-namespaced keyword"
    (let [result (wf/decode-event [:hello {:msg "hi"}])]
      (is (= :invalid-event-id (:error result))))))

;; ============================================================================
;; System Events Tests
;; ============================================================================

(deftest system-events-test
  (testing "Handshake event"
    (let [event (wf/make-handshake "user-123" "csrf-token" {:version "2.0"} true)]
      (is (= :chsk/handshake (first event)))
      (is (= ["user-123" "csrf-token" {:version "2.0"} true] (second event)))))

  (testing "Parse handshake"
    (let [data ["user-123" "csrf-token" {:version "2.0"} true]
          parsed (wf/parse-handshake data)]
      (is (= "user-123" (:uid parsed)))
      (is (= "csrf-token" (:csrf-token parsed)))
      (is (= {:version "2.0"} (:handshake-data parsed)))
      (is (true? (:first? parsed)))))

  (testing "Reply event"
    (let [event (wf/make-reply "cb-123" {:name "Alice"})]
      (is (= :chsk/reply (first event)))
      (is (= "cb-123" (get-in event [1 :cb-uuid])))
      (is (= {:name "Alice"} (get-in event [1 :data])))))

  (testing "Parse reply"
    (let [data {:cb-uuid "cb-123" :data {:name "Alice"}}
          parsed (wf/parse-reply data)]
      (is (= "cb-123" (:cb-uuid parsed)))
      (is (= {:name "Alice"} (:data parsed)))))

  (testing "Ping/Pong events"
    (is (= [:chsk/ws-ping] (wf/make-ws-ping)))
    (is (= [:chsk/ws-pong] (wf/make-ws-pong))))

  (testing "State change event"
    (let [event (wf/make-state-change {:open? false} {:open? true})]
      (is (= :chsk/state (first event)))
      (is (= [{:open? false} {:open? true}] (second event)))))

  (testing "Recv event (server push)"
    (let [event (wf/make-recv :my-app/notification {:msg "Hello!"})]
      (is (= :chsk/recv (first event)))
      (is (= [:my-app/notification {:msg "Hello!"}] (second event))))))

;; ============================================================================
;; sente-lite Extension Events Tests
;; ============================================================================

(deftest sente-lite-events-test
  (testing "Subscribe event"
    (let [event (wf/make-subscribe "chat-room-1")]
      (is (= :sente-lite/subscribe (first event)))
      (is (= "chat-room-1" (get-in event [1 :channel-id])))))

  (testing "Subscribe with data"
    (let [event (wf/make-subscribe "chat-room-1" :data {:user "alice"})]
      (is (= {:channel-id "chat-room-1" :data {:user "alice"}} (second event)))))

  (testing "Unsubscribe event"
    (let [event (wf/make-unsubscribe "chat-room-1")]
      (is (= :sente-lite/unsubscribe (first event)))
      (is (= {:channel-id "chat-room-1"} (second event)))))

  (testing "Subscribed confirmation"
    (let [event (wf/make-subscribed "chat-room-1" true)]
      (is (= :sente-lite/subscribed (first event)))
      (is (= {:channel-id "chat-room-1" :success true} (second event)))))

  (testing "Subscribed with error"
    (let [event (wf/make-subscribed "chat-room-1" false :error "Not authorized")]
      (is (= {:channel-id "chat-room-1" :success false :error "Not authorized"}
             (second event)))))

  (testing "Publish event"
    (let [event (wf/make-publish "chat-room-1" {:msg "Hello!"})]
      (is (= :sente-lite/publish (first event)))
      (is (= {:channel-id "chat-room-1" :data {:msg "Hello!"}} (second event)))))

  (testing "Channel message event"
    (let [event (wf/make-channel-msg "chat-room-1" {:msg "Hello!"} "user-123")]
      (is (= :sente-lite/channel-msg (first event)))
      (is (= {:channel-id "chat-room-1" :data {:msg "Hello!"} :from "user-123"}
             (second event))))))

;; ============================================================================
;; v1 Format Rejection Tests
;; ============================================================================

(deftest v1-format-rejection-test
  (testing "v1 format is rejected with error"
    (let [result (wf/parse-message "{:type \"ping\"}" :edn)]
      (is (= :v1-format-not-supported (:error result)))
      (is (some? (:message result)))))

  (testing "v1 JSON format is also rejected"
    (let [result (wf/parse-message "{\"type\":\"ping\"}" :edn)]
      (is (= :v1-format-not-supported (:error result))))))

;; ============================================================================
;; Wire Format Detection Tests
;; ============================================================================

(deftest detect-wire-version-test
  (testing "Detect vector event format"
    (is (= :v2 (wf/detect-wire-version "[:my-app/hello {:msg \"hi\"}]")))
    (is (= :v2 (wf/detect-wire-version "[[:my-app/get {:id 1}] \"cb-123\"]"))))

  (testing "Detect map format"
    (is (= :v1 (wf/detect-wire-version "{:type \"ping\"}")))
    (is (= :v1 (wf/detect-wire-version "{\"type\":\"ping\"}"))))

  (testing "Unknown format"
    (is (= :unknown (wf/detect-wire-version nil)))
    (is (= :unknown (wf/detect-wire-version "")))
    (is (= :unknown (wf/detect-wire-version "hello")))))

;; ============================================================================
;; Event Predicates Tests
;; ============================================================================

(deftest event-predicates-test
  (testing "System event detection"
    (is (wf/system-event? :chsk/handshake))
    (is (wf/system-event? :chsk/ws-ping))
    (is (not (wf/system-event? :my-app/hello)))
    (is (not (wf/system-event? :sente-lite/subscribe))))

  (testing "sente-lite event detection"
    (is (wf/sente-lite-event? :sente-lite/subscribe))
    (is (wf/sente-lite-event? :sente-lite/publish))
    (is (not (wf/sente-lite-event? :chsk/handshake)))
    (is (not (wf/sente-lite-event? :my-app/hello))))

  (testing "Specific event predicates"
    (is (wf/ping-event? :chsk/ws-ping))
    (is (not (wf/ping-event? :chsk/ws-pong)))
    (is (wf/pong-event? :chsk/ws-pong))
    (is (wf/reply-event? :chsk/reply))
    (is (wf/handshake-event? :chsk/handshake))))

;; ============================================================================
;; Callback Registry Tests
;; ============================================================================

(deftest callback-registry-test
  (testing "Register and invoke callback"
    (let [result (atom nil)
          cb-uuid (wf/register-callback! "test-cb-1" #(reset! result %) 5000)]
      (is (= "test-cb-1" cb-uuid))
      (is (= 1 (wf/pending-callbacks)))
      (is (true? (wf/invoke-callback! "test-cb-1" {:success true})))
      (is (= {:success true} @result))
      (is (= 0 (wf/pending-callbacks)))))

  (testing "Invoke non-existent callback"
    (is (nil? (wf/invoke-callback! "non-existent" {:data "ignored"})))))

;; ============================================================================
;; Sente Wire Format Compatibility Tests
;; ============================================================================

(deftest sente-wire-format-test
  (testing "Parse 2-element handshake (Sente wire format)"
    (let [data ["user-123" nil]
          parsed (wf/parse-handshake data)]
      (is (= "user-123" (:uid parsed)))
      (is (nil? (:csrf-token parsed)))
      (is (nil? (:handshake-data parsed)))
      (is (true? (:first? parsed)))))

  (testing "Parse 3-element handshake"
    (let [data ["user-123" "csrf-token" {:version "2.0"}]
          parsed (wf/parse-handshake data)]
      (is (= "user-123" (:uid parsed)))
      (is (= "csrf-token" (:csrf-token parsed)))
      (is (= {:version "2.0"} (:handshake-data parsed)))
      (is (true? (:first? parsed)))))

  (testing "Wire reply format"
    (let [reply (wf/make-wire-reply "cb-123" {:name "Alice"})]
      (is (= [{:name "Alice"} "cb-123"] reply))))

  (testing "Parse wire reply"
    (let [wire-data [{:echo "data"} "cb-456"]
          parsed (wf/parse-wire-reply wire-data)]
      (is (= "cb-456" (:cb-uuid parsed)))
      (is (= {:echo "data"} (:data parsed)))))

  (testing "Buffered events detection"
    (is (wf/buffered-events? [[:chsk/handshake ["uid" nil]]]))
    (is (wf/buffered-events? [[:my-app/event1 {:a 1}] [:my-app/event2 {:b 2}]]))
    (is (not (wf/buffered-events? [:my-app/event {:a 1}])))
    (is (not (wf/buffered-events? [[:my-app/event {:a 1}] "cb-uuid"]))))

  (testing "Unwrap buffered events"
    (let [buffered [[:chsk/handshake ["uid" nil]]]
          unwrapped (wf/unwrap-buffered-events buffered)]
      (is (= [[:chsk/handshake ["uid" nil]]] unwrapped)))
    (let [single [:my-app/event {:a 1}]
          unwrapped (wf/unwrap-buffered-events single)]
      (is (= [[:my-app/event {:a 1}]] unwrapped))))

  (testing "Wrap buffered events"
    (let [event [:my-app/event {:a 1}]
          wrapped (wf/wrap-buffered-events event)]
      (is (= [[:my-app/event {:a 1}]] wrapped)))
    (let [already-wrapped [[:my-app/event {:a 1}]]
          wrapped (wf/wrap-buffered-events already-wrapped)]
      (is (= [[:my-app/event {:a 1}]] wrapped)))))
