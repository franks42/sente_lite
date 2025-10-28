# Scittle nREPL Demo - Deployment Protocol

**Version:** 1.0
**Date:** 2025-10-28
**Location:** `/Users/franksiebenlist/Development/sente_lite/dev/scittle-demo/`

---

## 🔴 CRITICAL: Keep This Document Current

**This is a LIVING document that MUST be updated as we learn.**

**UPDATE THIS DOCUMENT whenever you discover:**
- ✅ A better way to perform any step
- ✅ An edge case or failure mode not covered
- ✅ Better verification commands or methods
- ✅ Additional troubleshooting steps that work
- ✅ Any improvement or clarification to the process

**Why this matters:**
- Stale documentation is worse than no documentation
- Future sessions depend on this being accurate
- Incorrect steps waste time and cause frustration
- This protocol is critical for development workflow

**When updating:**
1. Update the relevant section with new information
2. Update the "Version" and "Date" at the top
3. Commit with clear description of what changed
4. Tag if it's a significant protocol improvement

---

## FUNDAMENTAL RULE: NO CHEATING, NO LYING

**IF SOMETHING DOESN'T WORK → STOP AND SAY IT DOESN'T WORK**

- If a port is still in use → SAY IT'S STILL IN USE
- If a process won't die → SAY IT WON'T DIE
- If a connection fails → SAY IT FAILED
- If you see an error → REPORT THE FULL ERROR
- If you're not sure → SAY YOU'RE NOT SURE

**DO NOT:**
- Pretend things work when they don't
- Skip verification steps
- Assume success without checking
- Move forward when something fails
- Use optimistic language without proof

---

## The 5-Step Kill/Restart Sequence

### STEP 1: KILL EVERYTHING

**What to do:**
```bash
pkill -9 bb
pkill -9 node
```

**What this does:**
- Kills ALL Babashka processes (including the 4-service BB server)
- Kills ALL Node.js processes (including Playwright browsers)
- Uses -9 (SIGKILL) for forceful termination

**Verification:**
```bash
ps aux | grep -E "bb|node" | grep -v grep
```

**SUCCESS = Empty output (no bb or node processes)**

**FAILURE = Any line showing bb or node process**
- If you see processes still running → REPORT IT
- If processes won't die → REPORT IT
- DO NOT proceed to Step 2

---

### STEP 2: VERIFY PORTS ARE FREE

**What to do:**
```bash
lsof -i :1338 :1339 :1340 :1341 :1342
```

**What this checks:**
- Port 1338: BB nREPL (direct Clojure eval)
- Port 1339: Browser gateway (ClojureScript eval)
- Port 1340: WebSocket (browser nREPL connection)
- Port 1341: HTTP server (static files)
- Port 1342: Additional WebSocket (sente-lite testing)

**SUCCESS = Empty output (no output at all) OR error message "lsof: no port specification"**

**FAILURE = ANY line showing a process using these ports**

Example of FAILURE:
```
COMMAND   PID  USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
java    12345  user   42u  IPv6 0x1234      0t0  TCP *:1338 (LISTEN)
```

**If ports are in use:**
1. REPORT which ports are still in use
2. REPORT which processes are holding them
3. Try to identify if they're old BB/node processes
4. Try `pkill -9` again
5. DO NOT proceed to Step 3 until ports are free

**Why this matters:**
- Old processes keep ports allocated
- New BB server can't bind to occupied ports
- Silent failures occur when ports are taken
- Browser connects to OLD dead instance

---

### STEP 3: START BB SERVER FRESH

**What to do:**
```bash
cd /Users/franksiebenlist/Development/sente_lite/dev/scittle-demo
bb dev
```

**What this starts:**
The `bb dev` command (defined in `bb.edn`) starts 4 parallel services:

1. **Port 1338: BB nREPL server**
   - Direct nREPL into BB process
   - Eval Clojure code in BB server memory
   - Protocol: nREPL (bencode)

2. **Port 1339: Browser nREPL gateway**
   - Converts bencode ↔ EDN
   - Forwards to/from port 1340 WebSocket
   - Protocol: nREPL (bencode) → EDN → WebSocket

3. **Port 1340: WebSocket server**
   - Accepts browser WebSocket connections
   - Runs Scittle nREPL server in browser
   - Protocol: WebSocket (EDN messages)

4. **Port 1341: HTTP static file server**
   - Serves index.html and playground.cljs
   - Enables browser to load Scittle
   - Protocol: HTTP/1.1

**Expected output:**
```
[1/4] Starting nREPL server on port 1338...
nREPL server started on port 1338 on host localhost - nrepl://localhost:1338
[2/4] Starting browser nREPL gateway on port 1339...
Browser nREPL gateway started on port 1339
[3/4] Starting WebSocket server on port 1340...
WebSocket server started on port 1340
[4/4] Starting HTTP server on port 1341...
HTTP server started on port 1341
All services running. Press Ctrl+C to stop.
```

**Verification:**
```bash
# In a NEW terminal
lsof -i :1338 :1339 :1340 :1341
```

**SUCCESS = 4 lines showing bb process listening on all 4 ports**

Example:
```
COMMAND   PID  USER   FD   TYPE DEVICE SIZE/OFF NODE NAME
bb      12345  user   42u  IPv6 0x1234      0t0  TCP *:1338 (LISTEN)
bb      12345  user   43u  IPv6 0x1235      0t0  TCP *:1339 (LISTEN)
bb      12345  user   44u  IPv6 0x1236      0t0  TCP *:1340 (LISTEN)
bb      12345  user   45u  IPv6 0x1237      0t0  TCP *:1341 (LISTEN)
```

**FAILURE = Any of the following:**
- Fewer than 4 ports listening
- Error messages in BB output
- Process crashes or exits
- Ports show different PID (means old process still running)

**If Step 3 fails:**
1. REPORT the exact error message
2. REPORT which ports did/didn't start
3. GO BACK TO STEP 1 (kill everything again)
4. DO NOT proceed to Step 4

**Test BB nREPL (optional but recommended):**
```bash
bb -Sdeps '{:deps {babashka/nrepl-client {:git/url "https://github.com/babashka/nrepl-client" :git/sha "19fbef2525e47d80b9278c49a545de58f48ee7cf"}}}' -e '
(require (quote [babashka.nrepl-client :as nrepl]))
(nrepl/eval-expr {:port 1338 :expr "(+ 10 20 30)"})'
```

Expected: `{:vals [60]}`

---

### STEP 4: START PLAYWRIGHT BROWSER FRESH

**What to do:**
```bash
# In a NEW terminal (leave BB server running in first terminal)
cd /Users/franksiebenlist/Development/sente_lite/dev/scittle-demo
npm run interactive
```

**What this does:**
- Launches Chromium browser via Playwright
- Opens http://localhost:1341/ (loads index.html)
- Opens Chrome DevTools for inspection
- Browser connects WebSocket to port 1340
- Scittle nREPL server starts IN THE BROWSER

**Expected output:**
```
> scittle-demo@1.0.0 interactive
> node playwright-interactive.mjs

Launching browser with DevTools...
Browser launched. Press Ctrl+C when done.
```

**Browser should show:**
- Page title: "Scittle nREPL Demo"
- Console shows Scittle loading messages
- No red error messages in console

**Verification - Check browser console:**
```
Open DevTools → Console tab
```

Look for:
- ✅ "Scittle loaded"
- ✅ "WebSocket connected to ws://localhost:1340"
- ✅ "nREPL server ready"
- ❌ NO red error messages
- ❌ NO "connection refused"
- ❌ NO "failed to load"

**SUCCESS = Browser opens, DevTools visible, no errors in console, WebSocket connected**

**FAILURE = Any of the following:**
- Browser doesn't open
- Page shows error
- Console shows red errors
- WebSocket fails to connect
- "Connection refused" messages

**If Step 4 fails:**
1. REPORT the exact error from browser console
2. Check if BB server is still running (Step 3)
3. Check if port 1340 is listening (`lsof -i :1340`)
4. If BB server died → GO BACK TO STEP 1
5. DO NOT proceed to Step 5

**Test browser nREPL (optional but recommended):**
```bash
# In a THIRD terminal
bb -Sdeps '{:deps {babashka/nrepl-client {:git/url "https://github.com/babashka/nrepl-client" :git/sha "19fbef2525e47d80b9278c49a545de58f48ee7cf"}}}' -e '
(require (quote [babashka.nrepl-client :as nrepl]))
(nrepl/eval-expr {:port 1339 :expr "(+ 1 2 3)"})'
```

Expected: `{:vals ["6"]}` (note: string "6", not number)

---

### STEP 5: RE-UPLOAD ALL CODE

**What gets lost on restart:**
- ALL code uploaded to BB server memory (via port 1338)
- ALL code uploaded to browser memory (via port 1339)
- ALL WebSocket servers created via nREPL
- ALL connections and state

**What to upload:**

#### 5A. Upload sente-lite SERVER code to BB (port 1338)

**Method 1: Using load-sente-server.bb script**
```bash
cd /Users/franksiebenlist/Development/sente_lite/dev/scittle-demo
./load-sente-server.bb
```

Expected output: (depends on what's in the script)

**Method 2: Manual upload via nREPL**
```bash
# Load server namespace
bb -Sdeps '{...}' -e '
(require (quote [babashka.nrepl-client :as nrepl]))
(nrepl/eval-expr {:port 1338 :expr "(load-file \"../../src/sente_lite/server.cljc\")"})'
```

**Verification:**
- Check for any error messages
- Eval should return success or namespace loaded

#### 5B. Upload sente-lite CLIENT code to browser (port 1339)

```bash
# Load client namespace in browser
bb -Sdeps '{...}' -e '
(require (quote [babashka.nrepl-client :as nrepl]))
(nrepl/eval-expr {:port 1339 :expr "(load-file \"path-to-client-code\")"})'
```

#### 5C. Start additional services (e.g., WebSocket on port 1342)

```bash
# Start http-kit WebSocket server in BB
bb -Sdeps '{...}' -e '
(require (quote [babashka.nrepl-client :as nrepl]))
(nrepl/eval-expr {:port 1338 :expr "
(do
  (require (quote [org.httpkit.server :as http]))
  (def test-server (http/run-server
    (fn [req] {:status 200 :body \"OK\"})
    {:port 1342})))"})'
```

**Verification:**
```bash
lsof -i :1342
```

Should show bb process listening on port 1342.

**SUCCESS = All code uploaded without errors, all expected services running**

**FAILURE = Any error during upload, missing services**

**If Step 5 fails:**
1. REPORT the exact error message
2. REPORT which upload step failed
3. Check if BB server is still running
4. Check if browser is still connected
5. If infrastructure died → GO BACK TO STEP 1
6. DO NOT claim success

---

## What Gets Lost on Restart

**EVERYTHING loaded via nREPL is GONE:**

1. **BB Server Memory (port 1338):**
   - All loaded namespaces
   - All defined functions
   - All state (atoms, refs)
   - All running servers created via code

2. **Browser Memory (port 1339):**
   - All loaded ClojureScript code
   - All defined functions
   - All browser state
   - All DOM modifications

3. **Connections:**
   - All WebSocket connections
   - All subscriptions
   - All message handlers

**ONLY PERSISTENT:**
- The base 4 services (1338, 1339, 1340, 1341)
- Files on disk
- Git repository

---

## Common Mistakes

### ❌ MISTAKE 1: Not killing old processes
**Problem:** Multiple BB instances fighting for ports
**Symptom:** "Address already in use" errors
**Fix:** GO BACK TO STEP 1, verify with `ps` and `lsof`

### ❌ MISTAKE 2: Browser connected to old BB instance
**Problem:** Browser WebSocket connected to dead server
**Symptom:** nREPL commands hang, no response
**Fix:** GO BACK TO STEP 1, restart browser in Step 4

### ❌ MISTAKE 3: Forgetting to check ports
**Problem:** Old process still has ports allocated
**Symptom:** New BB starts but services fail silently
**Fix:** ALWAYS run Step 2 verification

### ❌ MISTAKE 4: Not re-uploading code
**Problem:** Trying to use functions that don't exist
**Symptom:** "Unable to resolve symbol" errors
**Fix:** Complete ALL of Step 5

### ❌ MISTAKE 5: Pretending things work
**Problem:** Moving forward when something failed
**Symptom:** Cascading failures, wasted time, frustration
**Fix:** STOP AND REPORT THE PROBLEM IMMEDIATELY

---

## Verification Checklist

Before claiming "deployment successful", verify ALL of the following:

- [ ] Step 1: No bb or node processes running (`ps aux | grep`)
- [ ] Step 2: All 5 ports are free (`lsof -i :1338 ...`)
- [ ] Step 3: BB server running with all 4 services (`lsof` shows 4 ports)
- [ ] Step 3: BB nREPL responds to test eval
- [ ] Step 4: Browser opens with no console errors
- [ ] Step 4: WebSocket connected (check browser DevTools)
- [ ] Step 4: Browser nREPL responds to test eval
- [ ] Step 5A: Server code uploaded without errors
- [ ] Step 5B: Client code uploaded without errors
- [ ] Step 5C: Additional services started (if needed)
- [ ] Final: Port 1342 listening (if applicable)

**ONLY AFTER ALL CHECKBOXES = ✅ CAN YOU SAY "DEPLOYMENT SUCCESSFUL"**

---

## When Something Goes Wrong

### Rule: START FROM SCRATCH

If ANY step fails, if ANYTHING doesn't work, if you're UNSURE:

1. **STOP**
2. **REPORT WHAT FAILED** (exact error, which step)
3. **GO BACK TO STEP 1**
4. **RUN THE FULL SEQUENCE AGAIN**

DO NOT:
- Try to "fix" individual services
- Skip steps
- Assume "it's probably fine"
- Continue forward

### Rule: NO CHEATING, NO LYING

- If you didn't verify something → SAY YOU DIDN'T VERIFY IT
- If something looks wrong → SAY IT LOOKS WRONG
- If you're not sure → ASK FOR HELP

---

## Success Criteria

**You can ONLY claim success when:**

1. All 5 steps completed without errors
2. All verifications passed
3. All services responding to test evals
4. No error messages anywhere
5. All checkboxes marked ✅

**Success = Working system that you have PROVEN works through testing**

**NOT success = "It should work", "It's probably fine", "I think it's running"**

---

## Architecture Reference

```
Process: BB Server (PID: 12345)
├─ Thread 1: nREPL server → Port 1338 (BB Clojure eval)
├─ Thread 2: Browser gateway → Port 1339 (bencode ↔ EDN)
├─ Thread 3: WebSocket server → Port 1340 (browser connection)
└─ Thread 4: HTTP server → Port 1341 (static files)

Process: Chromium Browser (PID: 12346)
├─ Tab 1: http://localhost:1341/ (loads Scittle)
│   ├─ Scittle nREPL server (in browser JS runtime)
│   └─ WebSocket client → ws://localhost:1340
└─ DevTools: Console, Network, Sources

Optional (created via nREPL in Step 5):
Process: BB Server (same PID: 12345)
└─ http-kit WebSocket → Port 1342 (sente-lite testing)
```

---

## Remember

**IF IT DOESN'T WORK → SAY IT DOESN'T WORK**

The goal is a WORKING system, not a CLAIMED working system.

Honesty and verification are more valuable than speed.
