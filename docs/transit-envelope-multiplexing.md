# Transit Envelope Multiplexing Pattern

**Multiplexing nREPL (bencode) and Application Messages (Transit) over a Single WebSocket Connection**

---

## Overview

This document describes the **Transit Envelope** pattern for sending both bencode-encoded nREPL messages and Transit-encoded application messages over a single sente-lite WebSocket connection.

### The Problem

You want to:
1. Send **nREPL messages** (bencode format) from browser to Babashka's built-in nREPL server
2. Send **application messages** (Transit format) for real-time features (chat, notifications, etc.)
3. Use a **single WebSocket connection** (not two separate connections)

### The Solution

**Wrap bencode payloads inside Transit event envelopes:**

```
All messages use Transit for framing
├─ nREPL messages: [:nrepl/op <bencode-bytes>]
└─ App messages:   [:chat/message {:text "hi"}]
```

The Transit layer provides:
- Event structure `[event-id data]`
- Routing by event namespace
- WebSocket framing

The bencode payload preserves:
- Standard nREPL protocol compatibility
- Efficient encoding for nREPL
- Ability to proxy directly to BB's nREPL server

---

## Architecture

```
┌─────────────────────────────────────────────┐
│              Browser (Scittle)               │
│                                              │
│  ┌──────────────┐      ┌──────────────┐    │
│  │ nREPL Client │      │ Chat Client  │    │
│  │              │      │              │    │
│  │ bencode/     │      │ Transit      │    │
│  │ decode       │      │ only         │    │
│  └──────┬───────┘      └──────┬───────┘    │
│         │                     │             │
│         └──────────┬──────────┘             │
│                    │                        │
│         Single WebSocket Connection        │
│         (Transit serialization)            │
└────────────────────┴────────────────────────┘
                     │
         All messages use Transit
                     │
┌────────────────────┴────────────────────────┐
│          Babashka Server                     │
│                                              │
│  ┌──────────────────────────────────┐       │
│  │  sente-lite Event Router         │       │
│  │  (routes by event-id namespace)  │       │
│  └──────────┬───────────────┬───────┘       │
│             │               │                │
│      :nrepl/*        :chat/*, etc.          │
│             │               │                │
│  ┌──────────▼───────┐ ┌────▼──────────┐    │
│  │ Bencode Decoder  │ │ App Handlers  │    │
│  │      ↓           │ │               │    │
│  │ Proxy to nREPL   │ │ Normal logic  │    │
│  │ Server           │ │               │    │
│  └──────────────────┘ └───────────────┘    │
│                                              │
│  ┌──────────────────────────┐               │
│  │ BB's Built-in nREPL      │               │
│  │ (handles sessions, eval, │               │
│  │  namespaces, etc.)       │               │
│  └──────────────────────────┘               │
└──────────────────────────────────────────────┘
```

---

## Wire Format

### nREPL Messages

**Browser → Server:**
```clojure
;; Event envelope (Transit)
[:nrepl/op <bencode-bytes>]
    ↑           ↑
 keyword    byte array

;; bencode-bytes contains:
{"op" "eval"
 "code" "(+ 1 2)"
 "session" "abc-123"
 "id" "msg-456"}
```

**Server → Browser:**
```clojure
;; Event envelope (Transit)
[:nrepl/response <bencode-bytes>]

;; bencode-bytes contains:
{"id" "msg-456"
 "value" "3"
 "status" ["done"]}
```

### Application Messages

**Browser → Server:**
```clojure
;; Normal Transit (no bencode)
[:chat/message {:text "Hello" :room "general"}]
[:user/typing {:typing true}]
[:ping {:timestamp 1234567890}]
```

**Server → Browser:**
```clojure
;; Normal Transit responses
[:chat/message {:from "alice" :text "Hi!"}]
[:pong {:timestamp 1234567890}]
```

---

## Implementation

### Server (Babashka)

```clojure
(ns server
  (:require [sente-lite.server :as sente]
            [nrepl.server :as nrepl]
            [bencode.core :as bencode]))

;; ============ Setup ============

;; Start BB's built-in nREPL server (internal only)
(def nrepl-server (nrepl/start-server :port 0))
(def nrepl-conn (nrepl/connect :port (:port nrepl-server)))

;; Start WebSocket server (Transit serialization)
(def ws-server
  (sente/make-channel-socket-server!
    {:serialization :transit-json
     :user-id-fn #(or (get-in % [:params :uid]) 
                      (str (random-uuid)))}))

;; ============ Event Routing ============

(defmulti handle-event 
  "Route events by event-id"
  (fn [[event-id _] _] event-id))

;; nREPL Handler - decode bencode and proxy
(defmethod handle-event :nrepl/op
  [[_ bencode-bytes] {:keys [uid send-fn]}]
  (println "nREPL request from" uid)
  (future  ; Don't block event loop
    (try
      ;; Decode bencode payload
      (let [nrepl-msg (bencode/decode bencode-bytes)]
        (println "  nREPL message:" nrepl-msg)
        
        ;; Proxy to BB's nREPL server
        ;; (may return multiple responses)
        (doseq [response (nrepl/message nrepl-conn nrepl-msg)]
          (println "  nREPL response:" response)
          
          ;; Encode response and send back
          (send-fn uid [:nrepl/response (bencode/encode response)])))
      
      (catch Exception e
        (println "nREPL error:" (.getMessage e))
        ;; Send error response
        (send-fn uid [:nrepl/response 
                      (bencode/encode 
                        {"status" ["error"]
                         "error" (.getMessage e)})])))))

;; Application Handlers - normal Transit
(defmethod handle-event :chat/message
  [[_ data] {:keys [uid broadcast-fn]}]
  (println "Chat from" uid ":" (:text data))
  (let [msg (assoc data 
              :from uid 
              :timestamp (System/currentTimeMillis))]
    ;; Broadcast to all connected clients
    (broadcast-fn [:chat/message msg])))

(defmethod handle-event :ping
  [[_ data] {:keys [uid send-fn]}]
  (send-fn uid [:pong (assoc data :server-time (System/currentTimeMillis))]))

(defmethod handle-event :default
  [[event-id _] _]
  (println "Unknown event:" event-id))

;; ============ Start Router ============

(sente/start-router! (:ch-recv ws-server)
  (fn [event-msg]
    (handle-event (:event event-msg) event-msg)))

(println "Server ready!")
(println "- WebSocket on port 8080")
(println "- nREPL server on port" (:port nrepl-server))
@(promise)
```

### Client (Scittle Browser)

```clojure
(ns client
  (:require [sente-lite.client :as sente]
            [bencode.core :as bencode]))

;; ============ State ============

(def state 
  (atom {:nrepl-session nil       ; nREPL session ID
         :nrepl-pending {}        ; {msg-id callback-fn}
         :chat-messages []        ; Chat history
         :connected? false}))

;; ============ WebSocket Connection ============

(def socket
  (sente/make-channel-socket-client!
    "ws://localhost:8080/chsk"
    {:serialization :transit-json
     :on-event handle-event
     :on-state-change (fn [state]
                        (swap! state assoc :connected? (:open? state))
                        (js/console.log "Connected:" (:open? state)))}))

;; ============ Event Handler ============

(defn handle-event [[event-id data]]
  (case event-id
    
    ;; nREPL response - decode bencode
    :nrepl/response
    (let [nrepl-msg (bencode/decode data)
          msg-id (get nrepl-msg "id")]
      
      ;; Handle session creation
      (when-let [new-session (get nrepl-msg "new-session")]
        (swap! state assoc :nrepl-session new-session)
        (js/console.log "nREPL session created:" new-session))
      
      ;; Handle stdout
      (when-let [out (get nrepl-msg "out")]
        (js/console.log "stdout:" out))
      
      ;; Handle eval result
      (when-let [value (get nrepl-msg "value")]
        (js/console.log "result:" value))
      
      ;; Call pending callback if exists
      (when-let [callback (get-in @state [:nrepl-pending msg-id])]
        (callback nrepl-msg))
      
      ;; Clean up when eval is done
      (when (some #{"done"} (get nrepl-msg "status"))
        (swap! state update :nrepl-pending dissoc msg-id)))
    
    ;; Chat message - normal Transit
    :chat/message
    (do
      (swap! state update :chat-messages conj data)
      (js/console.log "Chat message:" data))
    
    ;; Pong response
    :pong
    (js/console.log "Pong received:" data)
    
    ;; Unknown event
    (js/console.log "Unknown event:" event-id data)))

;; ============ API Functions ============

;; nREPL Functions (bencode encoding)

(defn nrepl-create-session! 
  "Create a new nREPL session"
  [callback]
  (let [msg-id (str (random-uuid))
        msg {"op" "clone"
             "id" msg-id}]
    (swap! state assoc-in [:nrepl-pending msg-id] callback)
    ;; Encode as bencode, wrap in Transit envelope
    ((:send! socket) [:nrepl/op (bencode/encode msg)])))

(defn nrepl-eval! 
  "Evaluate code in current nREPL session"
  [code callback]
  (let [msg-id (str (random-uuid))
        msg {"op" "eval"
             "code" code
             "session" (:nrepl-session @state)
             "id" msg-id}]
    (swap! state assoc-in [:nrepl-pending msg-id] callback)
    ;; Encode as bencode, wrap in Transit envelope
    ((:send! socket) [:nrepl/op (bencode/encode msg)])))

(defn nrepl-interrupt! 
  "Interrupt a running eval"
  [eval-msg-id]
  (let [msg-id (str (random-uuid))
        msg {"op" "interrupt"
             "session" (:nrepl-session @state)
             "interrupt-id" eval-msg-id
             "id" msg-id}]
    ((:send! socket) [:nrepl/op (bencode/encode msg)])))

;; Application Functions (normal Transit)

(defn chat-send! 
  "Send a chat message"
  [text]
  ;; Normal Transit message (no bencode)
  ((:send! socket) [:chat/message {:text text}]))

(defn ping! 
  "Send a ping"
  []
  ((:send! socket) [:ping {:client-time (js/Date.now)}]))

;; ============ Initialization ============

;; Create nREPL session on connection
(add-watch state :init-nrepl
  (fn [_ _ old new]
    (when (and (:connected? new) 
               (not (:connected? old))
               (nil? (:nrepl-session new)))
      (js/console.log "Creating nREPL session...")
      (nrepl-create-session! 
        (fn [msg]
          (js/console.log "Session initialized!"))))))

;; ============ Usage Examples ============

;; Evaluate code
(defn example-eval []
  (nrepl-eval! "(+ 1 2 3)"
    (fn [msg]
      (when-let [result (get msg "value")]
        (js/alert (str "Result: " result))))))

;; Send chat message
(defn example-chat []
  (chat-send! "Hello from browser!"))

;; Ping server
(defn example-ping []
  (ping!))
```

### Minimal Bencode Implementation (Browser)

If you don't want to add a full bencode library, here's a minimal implementation:

```clojure
(ns bencode.core
  "Minimal bencode implementation for browser")

;; ============ Encoding ============

(defn- encode-int [n]
  (str "i" n "e"))

(defn- encode-string [s]
  (let [bytes (.encode (js/TextEncoder.) s)]
    (str (.-length bytes) ":" s)))

(defn- encode-list [lst]
  (str "l" (apply str (map encode lst)) "e"))

(defn- encode-dict [m]
  (str "d"
       (apply str
         (mapcat (fn [[k v]]
                   [(encode-string (name k))
                    (encode v)])
           (sort-by first m)))
       "e"))

(defn encode 
  "Encode Clojure data to bencode string"
  [data]
  (cond
    (integer? data) (encode-int data)
    (string? data) (encode-string data)
    (sequential? data) (encode-list data)
    (map? data) (encode-dict data)
    :else (throw (js/Error. (str "Cannot encode: " data)))))

;; ============ Decoding ============

(defn- read-until [s start end-char]
  (let [idx (.indexOf s end-char start)]
    (when (= idx -1)
      (throw (js/Error. "Unexpected end of string")))
    [(.substring s start idx) (inc idx)]))

(defn decode 
  "Decode bencode string to Clojure data"
  [s]
  (letfn [(decode-from [s pos]
            (let [ch (.charAt s pos)]
              (case ch
                "i" (let [[num-str new-pos] (read-until s (inc pos) "e")]
                      [(js/parseInt num-str) new-pos])
                
                "l" (loop [pos (inc pos)
                          items []]
                      (if (= (.charAt s pos) "e")
                        [items (inc pos)]
                        (let [[item new-pos] (decode-from s pos)]
                          (recur new-pos (conj items item)))))
                
                "d" (loop [pos (inc pos)
                          m {}]
                      (if (= (.charAt s pos) "e")
                        [m (inc pos)]
                        (let [[key key-pos] (decode-from s pos)
                              [val val-pos] (decode-from s key-pos)]
                          (recur val-pos (assoc m key val)))))
                
                ;; String (starts with digit)
                (if (and (>= (.charCodeAt ch 0) 48)
                        (<= (.charCodeAt ch 0) 57))
                  (let [[len-str colon-pos] (read-until s pos ":")
                        len (js/parseInt len-str)
                        str-val (.substring s colon-pos (+ colon-pos len))]
                    [str-val (+ colon-pos len)])
                  (throw (js/Error. (str "Unknown type: " ch)))))))]
    (first (decode-from s 0))))
```

---

## Usage Patterns

### Pattern 1: Standard nREPL Operations

```clojure
;; Browser
(nrepl-eval! "(def x 42)"
  (fn [msg]
    (when (some #{"done"} (get msg "status"))
      (js/console.log "Variable defined!"))))

(nrepl-eval! "(+ x 10)"
  (fn [msg]
    (when-let [result (get msg "value")]
      (js/console.log "x + 10 =" result))))

;; Load a file
(nrepl-eval! "(load-file \"src/myapp/core.clj\")"
  (fn [msg]
    (when-let [out (get msg "out")]
      (js/console.log "Output:" out))))
```

### Pattern 2: Long-Running Evaluation with Output

```clojure
;; Browser - collect all output
(defn eval-with-output [code]
  (let [output (atom [])
        msg-id (str (random-uuid))]
    
    (nrepl-eval! code
      (fn [msg]
        ;; Collect stdout
        (when-let [out (get msg "out")]
          (swap! output conj out))
        
        ;; Show result when done
        (when (some #{"done"} (get msg "status"))
          (js/console.log "Output:" @output)
          (when-let [val (get msg "value")]
            (js/console.log "Result:" val)))))))

;; Usage
(eval-with-output "(dotimes [i 5] (println i))")
;; Output: ["0\n" "1\n" "2\n" "3\n" "4\n"]
;; Result: nil
```

### Pattern 3: Mixing nREPL and App Messages

```clojure
;; Browser - use both simultaneously

;; 1. Evaluate code
(nrepl-eval! "(def counter (atom 0))"
  (fn [_] (js/console.log "Counter initialized")))

;; 2. Send chat message
(chat-send! "Starting evaluation...")

;; 3. Evaluate and broadcast result
(nrepl-eval! "@counter"
  (fn [msg]
    (when-let [result (get msg "value")]
      ;; Send result to chat
      (chat-send! (str "Counter value: " result)))))

;; 4. Ping server
(ping!)
```

### Pattern 4: Interactive REPL UI

```clojure
;; Complete REPL interface
(defn repl-ui []
  (let [input (r/atom "")
        history (r/atom [])]
    
    (fn []
      [:div.repl
       ;; History
       [:div.history
        (for [[i entry] (map-indexed vector @history)]
          ^{:key i}
          [:div.entry
           [:div.input "> " (:code entry)]
           (when (:output entry)
             [:div.output (:output entry)])
           (when (:result entry)
             [:div.result "=> " (:result entry)])])]
       
       ;; Input
       [:div.input-area
        [:textarea 
         {:value @input
          :on-change #(reset! input (.. % -target -value))
          :on-key-down 
          (fn [e]
            (when (and (= (.-key e) "Enter") (.-ctrlKey e))
              (.preventDefault e)
              (let [code @input]
                ;; Add to history
                (swap! history conj {:code code})
                
                ;; Eval
                (nrepl-eval! code
                  (fn [msg]
                    (let [idx (dec (count @history))]
                      ;; Update history with results
                      (when-let [out (get msg "out")]
                        (swap! history update-in [idx :output] str out))
                      (when-let [val (get msg "value")]
                        (swap! history assoc-in [idx :result] val)))))
                
                ;; Clear input
                (reset! input ""))))}]
        [:div.hint "Ctrl+Enter to evaluate"]]])))
```

---

## Benefits

### ✅ Single WebSocket Connection
- Only one connection to manage
- Lower overhead (~8KB per connection saved)
- No browser connection limit issues
- Single reconnection logic

### ✅ Works with sente-lite Unchanged
- No modifications to sente-lite needed
- Standard event routing works
- All sente-lite features available (reconnection, heartbeat, etc.)

### ✅ Standard nREPL Protocol
- bencode messages are byte-perfect standard nREPL
- Can proxy directly to BB's built-in nREPL server
- All nREPL operations supported (eval, load-file, interrupt, etc.)
- Session management handled by BB

### ✅ Clear Namespace Separation
- `:nrepl/*` events → bencode handling
- `:chat/*`, `:user/*`, etc. → normal Transit
- Easy to understand and maintain

### ✅ Full BB nREPL Features
- No need to reimplement nREPL
- Sessions, namespaces, vars all work
- Interrupt support
- Output streaming (stdout, stderr)

---

## Trade-offs

### ⚠️ Small Overhead (15-20 bytes per nREPL message)

**Pure bencode:**
```
50 bytes: {"op" "eval" "code" "(+ 1 2)" "id" "msg-1"}
```

**Transit envelope:**
```
~65 bytes: [:nrepl/op <50-byte-bencode-array>]
```

**Overhead breakdown:**
- Transit event-id: ~5 bytes (`:nrepl/op`)
- Transit array wrapper: ~3 bytes
- Byte array encoding: ~7 bytes
- **Total: ~15 bytes (~30% overhead)**

**Verdict:** Negligible for typical nREPL messages (<1KB)

### ⚠️ Bencode Library Needed

Browser needs bencode encode/decode:
- **Option 1:** Use npm bencode library (~5KB)
- **Option 2:** Minimal implementation (~200 LOC, shown above)
- **Option 3:** Use existing JS bencode lib

### ⚠️ Convention-Based Routing

- Must use `:nrepl/*` namespace for nREPL events
- Could accidentally use wrong namespace
- **Mitigation:** Helper functions enforce correct usage

---

## Best Practices

### 1. Use Helper Functions

**Don't manually construct events:**
```clojure
;; ❌ Bad - error-prone
((:send! socket) [:nrepl/op (bencode/encode {"op" "eval" ...})])
```

**Use wrapper functions:**
```clojure
;; ✅ Good - safe and clear
(nrepl-eval! "(+ 1 2)" callback)
```

### 2. Handle Streaming Responses

nREPL often sends multiple responses:
```clojure
;; Accumulate all responses for a message
(defn nrepl-eval-complete! [code]
  (let [result (atom {:output [] :value nil})]
    (nrepl-eval! code
      (fn [msg]
        (when-let [out (get msg "out")]
          (swap! result update :output conj out))
        (when-let [val (get msg "value")]
          (swap! result assoc :value val))
        (when (some #{"done"} (get msg "status"))
          (deliver-result! @result))))))
```

### 3. Session Management

Always create session on connect:
```clojure
(add-watch state :ensure-session
  (fn [_ _ old new]
    (when (and (:connected? new)
               (not (:nrepl-session new)))
      (nrepl-create-session! 
        (fn [_] (js/console.log "nREPL ready"))))))
```

### 4. Error Handling

```clojure
(defmethod handle-event :nrepl/op
  [[_ bencode-bytes] {:keys [uid send-fn]}]
  (future
    (try
      (let [msg (bencode/decode bencode-bytes)]
        (doseq [resp (nrepl/message nrepl-conn msg)]
          (send-fn uid [:nrepl/response (bencode/encode resp)])))
      (catch Exception e
        ;; Send error back to client
        (send-fn uid [:nrepl/response 
                      (bencode/encode 
                        {"status" ["error"]
                         "error" (.getMessage e)})])))))
```

### 5. Namespace Conventions

```clojure
;; Use clear namespaces
:nrepl/*     ; nREPL operations (bencode)
:chat/*      ; Chat features (Transit)
:user/*      ; User management (Transit)
:data/*      ; Data queries (Transit)
:admin/*     ; Admin operations (Transit)
```

---

## Testing

### Unit Test: Bencode Round-Trip

```clojure
(deftest bencode-round-trip-test
  (let [msg {"op" "eval"
             "code" "(+ 1 2)"
             "session" "abc-123"
             "id" "msg-456"}
        encoded (bencode/encode msg)
        decoded (bencode/decode encoded)]
    (is (= msg decoded))))
```

### Integration Test: nREPL Proxy

```clojure
(deftest nrepl-proxy-test
  (let [received (atom nil)
        client (make-test-client! 
                 {:on-event (fn [[event-id data]]
                              (when (= event-id :nrepl/response)
                                (reset! received (bencode/decode data))))})]
    
    ;; Send eval request
    ((:send! client) [:nrepl/op (bencode/encode {"op" "eval"
                                                  "code" "(+ 1 2)"
                                                  "id" "test-1"})])
    
    ;; Wait for response
    (Thread/sleep 200)
    
    ;; Verify result
    (is (= "3" (get @received "value")))
    (is (= "test-1" (get @received "id")))))
```

### E2E Test: Mixed Traffic

```clojure
(deftest mixed-traffic-test
  (let [nrepl-responses (atom [])
        chat-messages (atom [])
        
        client (make-test-client!
                 {:on-event (fn [[event-id data]]
                              (case event-id
                                :nrepl/response 
                                (swap! nrepl-responses conj (bencode/decode data))
                                :chat/message
                                (swap! chat-messages conj data)
                                nil))})]
    
    ;; Send both types simultaneously
    ((:send! client) [:nrepl/op (bencode/encode {"op" "eval" "code" "42"})])
    ((:send! client) [:chat/message {:text "Hello"}])
    
    (Thread/sleep 200)
    
    ;; Both should work
    (is (= 1 (count @nrepl-responses)))
    (is (= 1 (count @chat-messages)))))
```

---

## Migration Guide

### From Separate Connections

**Before:**
```clojure
(def nrepl-socket (sente/make-client! "ws://host/nrepl" {:serialization :bencode}))
(def app-socket (sente/make-client! "ws://host/app" {:serialization :transit}))
```

**After:**
```clojure
(def socket (sente/make-client! "ws://host/chsk" {:serialization :transit-json}))

;; Wrap nREPL calls
(defn nrepl-eval! [code callback]
  ((:send! socket) [:nrepl/op (bencode/encode {"op" "eval" "code" code})]))

;; App calls unchanged
(defn chat-send! [text]
  ((:send! socket) [:chat/message {:text text}]))
```

### From Pure Transit

**Before:**
```clojure
((:send! socket) [:nrepl/eval {:code "(+ 1 2)"}])
```

**After:**
```clojure
((:send! socket) [:nrepl/op (bencode/encode {"op" "eval" "code" "(+ 1 2)"})])
```

---

## FAQ

**Q: Why not just use Transit for everything?**  
A: BB's nREPL server expects bencode. You'd have to reimplement all nREPL logic (session management, eval, namespaces, etc.).

**Q: Why not use separate WebSocket connections?**  
A: Single connection is simpler, uses less memory, avoids browser connection limits, and has unified reconnection logic.

**Q: Does this work with standard nREPL clients (CIDER, Calva)?**  
A: No, this is for browser-to-BB communication. Standard editors should connect to BB's TCP nREPL port.

**Q: What about binary WebSocket frames?**  
A: Would be ideal but requires sente-lite changes. Transit envelope is simpler and works now.

**Q: Performance impact?**  
A: ~15 bytes overhead per nREPL message. Negligible for typical workloads.

**Q: Can I use other event namespaces for bencode?**  
A: Yes, but `:nrepl/*` is the convention. Update your routing logic accordingly.

---

## Summary

The **Transit Envelope** pattern provides:

✅ Single WebSocket connection  
✅ Standard nREPL compatibility  
✅ Clean namespace-based routing  
✅ Minimal overhead  
✅ Works with sente-lite unchanged  
✅ Full BB nREPL features  

**Implementation effort:** ~100 LOC (server) + ~150 LOC (client)

**Best for:**
- Browser-based development tools
- In-app REPLs
- Interactive debugging
- Teaching/demo environments

**Not for:**
- Editor integration (use TCP nREPL)
- High-frequency nREPL calls (>100/sec)
- When bencode library size matters

---

**Status:** Production-ready pattern  
**Compatibility:** sente-lite v1.0+, Babashka v1.0+  
**License:** EPL-1.0