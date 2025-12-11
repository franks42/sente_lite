(ns sente-lite.wire-format-v2-test
  "Tests for sente-lite v2 wire format (Sente-compatible)"
  (:require [clojure.test :refer [deftest testing is]]
            [sente-lite.wire-format-v2 :as v2]))

;; ============================================================================
;; Event Encoding Tests
;; ============================================================================

(deftest encode-event-test
  (testing "Simple event encoding"
    (is (= [:my-app/hello nil] (v2/encode-event :my-app/hello)))
    (is (= [:my-app/hello {:msg "hi"}] (v2/encode-event :my-app/hello {:msg "hi"}))))

  (testing "System event encoding"
    (is (= [:chsk/ws-ping nil] (v2/encode-event :chsk/ws-ping)))))

(deftest encode-event-with-callback-test
  (testing "Event with explicit callback UUID"
    (let [event (v2/encode-event-with-callback :my-app/get-user {:id 123} "cb-123")]
      (is (= [[:my-app/get-user {:id 123}] "cb-123"] event))))

  (testing "Event with auto-generated callback UUID"
    (let [event (v2/encode-event-with-callback :my-app/get-user {:id 123})]
      (is (vector? event))
      (is (= 2 (count event)))
      (is (vector? (first event)))
      (is (string? (second event))))))

;; ============================================================================
;; Event Decoding Tests
;; ============================================================================

(deftest decode-event-test
  (testing "Simple event decoding"
    (let [result (v2/decode-event [:my-app/hello {:msg "hi"}])]
      (is (= :my-app/hello (:event-id result)))
      (is (= {:msg "hi"} (:data result)))
      (is (nil? (:cb-uuid result)))))

  (testing "Event with nil data"
    (let [result (v2/decode-event [:my-app/ping])]
      (is (= :my-app/ping (:event-id result)))
      (is (nil? (:data result)))))

  (testing "Event with callback"
    (let [result (v2/decode-event [[:my-app/get-user {:id 123}] "cb-456"])]
      (is (= :my-app/get-user (:event-id result)))
      (is (= {:id 123} (:data result)))
      (is (= "cb-456" (:cb-uuid result)))))

  (testing "Invalid event - not a vector"
    (let [result (v2/decode-event {:type "message"})]
      (is (= :not-vector (:error result)))))

  (testing "Invalid event - empty vector"
    (let [result (v2/decode-event [])]
      (is (= :empty-vector (:error result)))))

  (testing "Invalid event - non-namespaced keyword"
    (let [result (v2/decode-event [:hello {:msg "hi"}])]
      (is (= :invalid-event-id (:error result))))))

;; ============================================================================
;; System Events Tests
;; ============================================================================

(deftest system-events-test
  (testing "Handshake event"
    (let [event (v2/make-handshake "user-123" "csrf-token" {:version "2.0"} true)]
      (is (= :chsk/handshake (first event)))
      (is (= ["user-123" "csrf-token" {:version "2.0"} true] (second event)))))

  (testing "Parse handshake"
    (let [data ["user-123" "csrf-token" {:version "2.0"} true]
          parsed (v2/parse-handshake data)]
      (is (= "user-123" (:uid parsed)))
      (is (= "csrf-token" (:csrf-token parsed)))
      (is (= {:version "2.0"} (:handshake-data parsed)))
      (is (true? (:first? parsed)))))

  (testing "Reply event"
    (let [event (v2/make-reply "cb-123" {:name "Alice"})]
      (is (= :chsk/reply (first event)))
      (is (= "cb-123" (get-in event [1 :cb-uuid])))
      (is (= {:name "Alice"} (get-in event [1 :data])))))

  (testing "Parse reply"
    (let [data {:cb-uuid "cb-123" :data {:name "Alice"}}
          parsed (v2/parse-reply data)]
      (is (= "cb-123" (:cb-uuid parsed)))
      (is (= {:name "Alice"} (:data parsed)))))

  (testing "Ping/Pong events"
    (is (= [:chsk/ws-ping] (v2/make-ws-ping)))
    (is (= [:chsk/ws-pong] (v2/make-ws-pong))))

  (testing "State change event"
    (let [event (v2/make-state-change {:open? false} {:open? true})]
      (is (= :chsk/state (first event)))
      (is (= [{:open? false} {:open? true}] (second event)))))

  (testing "Recv event (server push)"
    (let [event (v2/make-recv :my-app/notification {:msg "Hello!"})]
      (is (= :chsk/recv (first event)))
      (is (= [:my-app/notification {:msg "Hello!"}] (second event))))))

;; ============================================================================
;; sente-lite Extension Events Tests
;; ============================================================================

(deftest sente-lite-events-test
  (testing "Subscribe event"
    (let [event (v2/make-subscribe "chat-room-1")]
      (is (= :sente-lite/subscribe (first event)))
      (is (= "chat-room-1" (get-in event [1 :channel-id])))))

  (testing "Subscribe with data"
    (let [event (v2/make-subscribe "chat-room-1" :data {:user "alice"})]
      (is (= {:channel-id "chat-room-1" :data {:user "alice"}} (second event)))))

  (testing "Unsubscribe event"
    (let [event (v2/make-unsubscribe "chat-room-1")]
      (is (= :sente-lite/unsubscribe (first event)))
      (is (= {:channel-id "chat-room-1"} (second event)))))

  (testing "Subscribed confirmation"
    (let [event (v2/make-subscribed "chat-room-1" true)]
      (is (= :sente-lite/subscribed (first event)))
      (is (= {:channel-id "chat-room-1" :success true} (second event)))))

  (testing "Subscribed with error"
    (let [event (v2/make-subscribed "chat-room-1" false :error "Not authorized")]
      (is (= {:channel-id "chat-room-1" :success false :error "Not authorized"}
             (second event)))))

  (testing "Publish event"
    (let [event (v2/make-publish "chat-room-1" {:msg "Hello!"})]
      (is (= :sente-lite/publish (first event)))
      (is (= {:channel-id "chat-room-1" :data {:msg "Hello!"}} (second event)))))

  (testing "Channel message event"
    (let [event (v2/make-channel-msg "chat-room-1" {:msg "Hello!"} "user-123")]
      (is (= :sente-lite/channel-msg (first event)))
      (is (= {:channel-id "chat-room-1" :data {:msg "Hello!"} :from "user-123"}
             (second event))))))

;; ============================================================================
;; v1 Format Rejection Tests
;; ============================================================================

(deftest v1-format-rejection-test
  (testing "v1 format is rejected with error"
    (let [result (v2/parse-message "{:type \"ping\"}" :edn)]
      (is (= :v1-format-not-supported (:error result)))
      (is (some? (:message result)))))

  (testing "v1 JSON format is also rejected"
    (let [result (v2/parse-message "{\"type\":\"ping\"}" :edn)]
      (is (= :v1-format-not-supported (:error result))))))

;; ============================================================================
;; Wire Format Detection Tests
;; ============================================================================

(deftest detect-wire-version-test
  (testing "Detect v2 (vector)"
    (is (= :v2 (v2/detect-wire-version "[:my-app/hello {:msg \"hi\"}]")))
    (is (= :v2 (v2/detect-wire-version "[[:my-app/get {:id 1}] \"cb-123\"]"))))

  (testing "Detect v1 (map)"
    (is (= :v1 (v2/detect-wire-version "{:type \"ping\"}")))
    (is (= :v1 (v2/detect-wire-version "{\"type\":\"ping\"}"))))

  (testing "Unknown format"
    (is (= :unknown (v2/detect-wire-version nil)))
    (is (= :unknown (v2/detect-wire-version "")))
    (is (= :unknown (v2/detect-wire-version "hello")))))

;; ============================================================================
;; Event Predicates Tests
;; ============================================================================

(deftest event-predicates-test
  (testing "System event detection"
    (is (v2/system-event? :chsk/handshake))
    (is (v2/system-event? :chsk/ws-ping))
    (is (not (v2/system-event? :my-app/hello)))
    (is (not (v2/system-event? :sente-lite/subscribe))))

  (testing "sente-lite event detection"
    (is (v2/sente-lite-event? :sente-lite/subscribe))
    (is (v2/sente-lite-event? :sente-lite/publish))
    (is (not (v2/sente-lite-event? :chsk/handshake)))
    (is (not (v2/sente-lite-event? :my-app/hello))))

  (testing "Specific event predicates"
    (is (v2/ping-event? :chsk/ws-ping))
    (is (not (v2/ping-event? :chsk/ws-pong)))
    (is (v2/pong-event? :chsk/ws-pong))
    (is (v2/reply-event? :chsk/reply))
    (is (v2/handshake-event? :chsk/handshake))))

;; ============================================================================
;; Callback Registry Tests
;; ============================================================================

(deftest callback-registry-test
  (testing "Register and invoke callback"
    (let [result (atom nil)
          cb-uuid (v2/register-callback! "test-cb-1" #(reset! result %) 5000)]
      (is (= "test-cb-1" cb-uuid))
      (is (= 1 (v2/pending-callbacks)))
      (is (true? (v2/invoke-callback! "test-cb-1" {:success true})))
      (is (= {:success true} @result))
      (is (= 0 (v2/pending-callbacks)))))

  (testing "Invoke non-existent callback"
    (is (nil? (v2/invoke-callback! "non-existent" {:data "ignored"})))))

;; ============================================================================
;; Sente Wire Format Compatibility Tests
;; ============================================================================

(deftest sente-wire-format-test
  (testing "Parse 2-element handshake (Sente wire format)"
    (let [data ["user-123" nil]
          parsed (v2/parse-handshake data)]
      (is (= "user-123" (:uid parsed)))
      (is (nil? (:csrf-token parsed)))
      (is (nil? (:handshake-data parsed)))
      (is (true? (:first? parsed)))))

  (testing "Parse 3-element handshake"
    (let [data ["user-123" "csrf-token" {:version "2.0"}]
          parsed (v2/parse-handshake data)]
      (is (= "user-123" (:uid parsed)))
      (is (= "csrf-token" (:csrf-token parsed)))
      (is (= {:version "2.0"} (:handshake-data parsed)))
      (is (true? (:first? parsed)))))

  (testing "Wire reply format"
    (let [reply (v2/make-wire-reply "cb-123" {:name "Alice"})]
      (is (= [{:name "Alice"} "cb-123"] reply))))

  (testing "Parse wire reply"
    (let [wire-data [{:echo "data"} "cb-456"]
          parsed (v2/parse-wire-reply wire-data)]
      (is (= "cb-456" (:cb-uuid parsed)))
      (is (= {:echo "data"} (:data parsed)))))

  (testing "Buffered events detection"
    (is (v2/buffered-events? [[:chsk/handshake ["uid" nil]]]))
    (is (v2/buffered-events? [[:my-app/event1 {:a 1}] [:my-app/event2 {:b 2}]]))
    (is (not (v2/buffered-events? [:my-app/event {:a 1}])))
    (is (not (v2/buffered-events? [[:my-app/event {:a 1}] "cb-uuid"]))))

  (testing "Unwrap buffered events"
    (let [buffered [[:chsk/handshake ["uid" nil]]]
          unwrapped (v2/unwrap-buffered-events buffered)]
      (is (= [[:chsk/handshake ["uid" nil]]] unwrapped)))
    (let [single [:my-app/event {:a 1}]
          unwrapped (v2/unwrap-buffered-events single)]
      (is (= [[:my-app/event {:a 1}]] unwrapped))))

  (testing "Wrap buffered events"
    (let [event [:my-app/event {:a 1}]
          wrapped (v2/wrap-buffered-events event)]
      (is (= [[:my-app/event {:a 1}]] wrapped)))
    (let [already-wrapped [[:my-app/event {:a 1}]]
          wrapped (v2/wrap-buffered-events already-wrapped)]
      (is (= [[:my-app/event {:a 1}]] wrapped)))))
