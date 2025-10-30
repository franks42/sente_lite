# Context for Next Claude Instance

**Date Created**: 2025-10-29
**Last Updated**: 2025-10-29 (Session 5 - nREPL Gateway BLOCKER RESOLVED ✅)

## 🎉 CURRENT STATUS: nREPL Gateway Working!

**What's working**:
- ✅ Browser connects to gateway via sente-lite WebSocket
- ✅ Browser receives and handles messages (no more "nth not supported" error)
- ✅ Gateway running on ports 1346 (sente WebSocket) and 1347 (nREPL bencode)
- ✅ All code loads successfully into browser

**What's next**:
- Test end-to-end nREPL eval flow (editor → gateway → browser → back)
- Verify message format compatibility between server and client
- Create snapshot when fully working

## ✅ RESOLVED: "nth not supported" Error Fixed!

### THE PROBLEM (Now Fixed)

**Runtime error in browser when sente-lite client calls on-message callback:**
```
nth not supported on this type function(a,b,c,d){this.I=a;this.J=b;this.D=c;this.G=d;this.F=16647951;this.M=401412}
```

**Root Cause**: SCI (Scittle's interpreter) does not support destructuring in function parameters OR in `let` bindings reliably

### THE FIX (Applied Successfully)

**Changed from** (BROKEN - doesn't work in SCI):
```clojure
(defn handle-message
  [[event-type event-data]]  ; ❌ Parameter destructuring fails
  ...)

;; OR even this fails:
(defn handle-message
  [msg]
  (let [[event-type event-data] msg]  ; ❌ Let destructuring also fails
    ...))
```

**Changed to** (WORKS in SCI):
```clojure
(defn handle-message
  [msg]
  (let [event-type (first msg)        ; ✅ Explicit first/second works!
        event-data (second msg)]
    ...))
```

**Why this works**: SCI has limitations with destructuring. Using explicit `first` and `second` calls avoids SCI's destructuring issues entirely.

**Verified working**: Browser successfully receives and handles messages without "nth not supported" error.

### THE FIX OPTIONS (Historical - for reference)

**Option 1**: Change how we call on-message in `client_scittle.cljs:110`:
```clojure
;; Instead of:
(on-message parsed-msg)

;; Try one of:
(apply on-message parsed-msg)  ; Unpacks vector as args
;; OR
(on-message (first parsed-msg) (second parsed-msg))  ; Pass as two args
```

**Option 2**: Change the function signature in `sente-nrepl-client.cljs:42`:
```clojure
;; Instead of:
(defn handle-message [[event-type event-data]])

;; Use:
(defn handle-message [msg]
  (let [[event-type event-data] msg]
    ...))
```

**Option 3**: Investigate if this is a SCI-specific destructuring issue and need workaround

### USER'S REQUEST WHEN STOPPED

User said: **"can you log the values of client-state and on-message to get a better picture of what's happening in that handle-message fn, and run the code again to see?"**

**Issue with logging attempt**:
- Added debug logging to lines 103-108 of client_scittle.cljs
- Browser never showed the debug logs (code caching issue)
- Error still reported old line numbers (103 instead of 110 after adding 7 lines)

Then user said: **"why don't you start from scratch - clean slate"**

Started fresh restart sequence, but context compacting caused thrashing.

## What We're Building: nREPL Gateway

**Purpose**: Proxy nREPL messages between editor and browser Scittle REPL using sente-lite

**Architecture** (per user's explicit guidance):
> "you should be able to send clj-edn as a client thru one of the sente-lite apis, and add context to that message that it will be pickup on the browser side as nrepl request... correct? when the browser repl returns the reply, you should be able to register a handler that pickes up the repl reply and return it thru the gateway to the calling client, correct?"

**Flow**:
1. Editor → bencode nREPL (port 1347)
2. Gateway converts bencode → EDN → sente WebSocket (port 1346)
3. Browser receives EDN: `[:nrepl/eval {:op :eval :code "(+ 1 2 3)" :id "123" :session "abc"}]`
4. Browser evaluates using `window.scittle.core.eval_string(code)`
5. Browser sends EDN response: `[:nrepl/response {:value "6" :id "123" :session "abc"}]`
6. Gateway converts EDN → bencode → Editor

**Key Constraint**: EDN format only on sente channel (not Transit, not bencode)

## Key Files for nREPL Gateway

### 1. `src/sente_lite/client_scittle.cljs`
**Browser WebSocket client** - The BLOCKER is here

**Line 110 issue** (in `handle-message` function):
```clojure
(defn- handle-message [client-state event]
  (let [client-id (:id client-state)
        config (:config client-state)
        raw-data (.-data event)
        parsed-msg (parse-message raw-data)]  ; Returns [:event-type {:data}]

    (swap! clients update-in [client-id :message-count-received] inc)
    (log-client-event! client-id "message-received" {...})

    ;; DEBUG LOGGING ADDED (lines 103-108) - but never appeared
    (js/console.log "DEBUG handle-message:")
    (js/console.log "  client-state:" (pr-str client-state))
    (js/console.log "  config:" (pr-str config))
    (js/console.log "  on-message:" (pr-str (:on-message config)))
    (js/console.log "  on-message type:" (type (:on-message config)))
    (js/console.log "  parsed-msg:" (pr-str parsed-msg))

    (when-let [on-message (:on-message config)]
      (on-message parsed-msg))))  ; <-- LINE 110: ERROR OCCURS HERE
```

### 2. `dev/scittle-demo/examples/sente-nrepl-client.cljs`
**Browser nREPL handler** - Receives messages from gateway, evals code

**Line 42-104** (the function that crashes):
```clojure
(defn handle-message
  "Handle messages from sente-websocket"
  [[event-type event-data]]  ; <-- DOUBLE BRACKET DESTRUCTURING
  (swap! eval-counter inc)
  (js/console.log (str "📨 [" @eval-counter "] nREPL message:")
                  (pr-str event-type))

  (case event-type
    :nrepl/eval
    ;; Evaluate code and send response
    (let [{:keys [op code id session]} event-data]
      (let [result (eval-code code)]
        (when-let [client-id @client-atom]
          (if (:success result)
            (do
              (sente/send! client-id [:nrepl/response {:value (:value result) :id id ...}])
              (sente/send! client-id [:nrepl/response {:status ["done"] :id id ...}]))
            (do
              (sente/send! client-id [:nrepl/response {:ex (:ex result) ...}])
              (sente/send! client-id [:nrepl/response {:status ["eval-error" "done"] ...}]))))))

    :welcome
    ;; Register as nREPL client
    (do
      (js/console.log "✓ Connected to sente-nrepl gateway")
      (when-let [client-id @client-atom]
        (sente/send! client-id [:nrepl/register {}])))

    :ping
    ;; Heartbeat
    (when-let [client-id @client-atom]
      (sente/send! client-id {:type :pong :timestamp (js/Date.now)}))

    ;; Default
    (js/console.log "📨 Other message:" (pr-str event-type) (pr-str event-data))))
```

**Key function** (lines 22-38):
```clojure
(defn eval-code
  "Evaluate ClojureScript code using Scittle's eval_string"
  [code-string]
  (try
    (let [result (.eval_string (.-core js/window.scittle) code-string)
          result-str (pr-str result)]
      (js/console.log "✓ Eval success:" result-str)
      {:success true
       :value result-str
       :ns (str *ns*)})
    (catch js/Error e
      (js/console.error "✗ Eval error:" (.-message e))
      {:success false
       :error (.-message e)
       :ex (str (type e))
       :ns (str *ns*)})))
```

### 3. `dev/scittle-demo/sente-nrepl-gateway.clj`
**Standalone gateway** - Converts between bencode (editor) and EDN (browser)

**Ports**:
- 1346: Sente WebSocket (for browser)
- 1347: nREPL bencode (for editor)

**Status**: ✅ Gateway starts successfully, browser connects, but crashes on message handling

### 4. `/tmp/test-nrepl-1347-fixed.clj`
**Test script** - Sends nREPL eval to port 1347

**Fixed bencode handling**:
```clojure
(let [socket (java.net.Socket. "localhost" 1347)
      in (io/input-stream socket)
      in (java.io.PushbackInputStream. in)  ; Binary streams for bencode
      out (io/output-stream socket)
      out (java.io.BufferedOutputStream. out)]

  (let [request {:op "eval"
                 :code "(+ 1 2 3)"
                 :id "test-1"
                 :session "test-session"}]
    (bencode/write-bencode out request)
    (.flush out))

  (loop [responses []]
    (let [response (bencode/read-bencode in)]
      (if (some #{"done"} (get response "status"))
        (println "\nTest complete!")
        (recur (conj responses response))))))
```

## What Was Working Before Blocker

✅ Gateway starts on ports 1346-1347
✅ Browser connects to gateway via sente-lite WebSocket
✅ Browser receives :welcome message
✅ Browser attempts to call handle-message
❌ **BLOCKER**: "nth not supported" error when calling user's on-message callback

## The Fresh Start Sequence (When You Resume)

**When starting fresh (from DEPLOYMENT-PROTOCOL.md):**

1. **KILL EVERYTHING**: `pkill -9 bb && pkill -9 node`
2. **VERIFY PORTS FREE**: `for port in 1338 1339 1340 1341 1342 1346 1347; do lsof -i tcp:$port; done`
3. **START BB DEV**: `cd /Users/franksiebenlist/Development/sente_lite/dev/scittle-demo && bb dev`
4. **START GATEWAY**: `cd /Users/franksiebenlist/Development/sente_lite && bb -cp src dev/scittle-demo/sente-nrepl-gateway.clj`
5. **START BROWSER**: `cd /Users/franksiebenlist/Development/sente_lite/dev/scittle-demo && npm run interactive`
6. **LOAD CODE IN ORDER**:
   - `bb load-browser ../../src/telemere_lite/scittle.cljs`
   - `bb load-browser ../../src/sente_lite/client_scittle.cljs`
   - `bb load-browser examples/sente-nrepl-client.cljs`

**But BEFORE you load code, FIX THE BLOCKER FIRST!**

## How to Fix the Blocker

**Next Claude instance should**:

1. **Try Option 1** (change how we call on-message):
   - Edit `src/sente_lite/client_scittle.cljs:110`
   - Change from `(on-message parsed-msg)` to `(apply on-message parsed-msg)`
   - This unpacks the vector `[:welcome {...}]` into two arguments

2. **Test the fix**:
   - Remove debug logging (lines 103-108) to clean up
   - Run clj-kondo and cljfmt
   - Do the fresh start sequence
   - Load the fixed code
   - Check browser console for success

3. **If Option 1 doesn't work, try Option 2**:
   - Edit `dev/scittle-demo/examples/sente-nrepl-client.cljs:42`
   - Change from `[[event-type event-data]]` to `[msg]` with `let` destructuring
   - Test again

## Port Architecture

**BB Dev Services** (ports 1338-1341):
- 1338: BB direct nREPL
- 1339: Browser nREPL proxy
- 1340: Browser WebSocket
- 1341: HTTP static server

**Demo Servers** (ports 1343-1345):
- 1343: Echo demo
- 1344: Heartbeat demo
- 1345: Pub/sub demo

**nREPL Gateway** (ports 1346-1347):
- 1346: Sente WebSocket (EDN to browser)
- 1347: nREPL bencode (editor connections)

## Critical Rules (Copy from old context)

**DISPLAY THIS AT START OF EVERY RESPONSE:**
```
I do not cheat or lie and I'm honest about any reporting of progress.
```

### Fundamental Principles

1. **NEVER LIE OR CHEAT**
   - If something doesn't work, SAY IT DOESN'T WORK
   - If tests fail, SAY THEY FAIL
   - If you don't know, SAY YOU DON'T KNOW

2. **"COMPACTING IS LOBOTOMY"**
   - Check recently modified files FIRST to understand actual current work
   - Read CONTEXT.md and CLAUDE.md before making assumptions

3. **ALWAYS START FROM PROJECT ROOT**
   - Use full absolute paths: `/Users/franksiebenlist/Development/sente_lite/...`

4. **VERIFY, DON'T ASSUME**
   - Check actual console output
   - Check actual log files
   - Check actual test results

## Project State Before nREPL Work

**Last Good Tag**: `v0.11.0-browser-reconnect-tested` (2025-10-29)

**What was working**:
- ✅ sente-lite core (BB-to-BB and Browser)
- ✅ Auto-reconnect with exponential backoff (BB and Browser)
- ✅ Pub/sub (all 4 scenarios: BB↔BB, Browser↔Browser, BB↔Browser)
- ✅ All 16 tests passing
- ✅ Zero linting errors

**New work**: nREPL gateway (IN PROGRESS, BLOCKED)

## Documentation References

- **Main instructions**: `CLAUDE.md`
- **Planning**: `doc/plan.md` (single source of truth)
- **Deployment protocol**: `dev/scittle-demo/DEPLOYMENT-PROTOCOL.md`
- **This file**: `CONTEXT.md` (you're reading it)

## For Next Claude Instance

**IMMEDIATE TASK**: Fix the "nth not supported" error

**Approach**:
1. Change `src/sente_lite/client_scittle.cljs:110` to use `apply`
2. Remove debug logging (lines 103-108)
3. Run clj-kondo and cljfmt
4. Test with fresh start sequence

**Success criteria**:
- Browser receives :welcome message without error
- Browser successfully calls handle-message
- Browser registers as nREPL client
- No "nth not supported" error in console

**Then**: Test end-to-end nREPL eval flow with test script

## Common Mistakes to Avoid

1. **Not starting from project root** - Always use full paths
2. **Assuming telemetry is broken** - Check `./sente-lite-server.log`
3. **Not verifying browser is running** - Check browser console output
4. **Using `timeout` on macOS** - It doesn't exist
5. **Bash escaping `!`** - Use double quotes: `"(connect!)"`

## Recovery Checklist

When you start fresh:

- [ ] Read CONTEXT.md (this file)
- [ ] Read CLAUDE.md for general instructions
- [ ] Check git status and recent commits
- [ ] Understand the blocker (nth not supported error)
- [ ] Apply the fix (use `apply` in client_scittle.cljs:110)
- [ ] Test with fresh start sequence
- [ ] Verify end-to-end nREPL flow works
