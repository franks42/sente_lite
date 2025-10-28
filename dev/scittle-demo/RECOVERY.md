# Scittle nREPL Demo - Recovery Context

**Last Updated**: 2025-10-27
**Location**: `/Users/franksiebenlist/Development/sente_lite/dev/scittle-demo/`

## What We Built

A live REPL development environment for testing sente-lite in a real browser:

### Architecture
```
BB Server (single process, 4 services):
├─ Port 1338: Direct BB nREPL → eval Clojure in BB server
├─ Port 1339: Browser nREPL gateway → eval ClojureScript in browser
├─ Port 1340: WebSocket for browser nREPL connection
└─ Port 1341: HTTP static file server

Playwright Browser:
└─ Connects to Port 1340 → Runs Scittle nREPL server in browser

Additional (uploaded via nREPL):
└─ Port 1342: http-kit WebSocket server (for sente-lite testing)
```

## MANDATORY: Start From Scratch Sequence

**See CLAUDE.md for full details**

```bash
# 1. KILL EVERYTHING
pkill -9 bb && pkill -9 node

# 2. VERIFY PORTS FREE
lsof -i :1338 :1339 :1340 :1341 :1342
# Should show NOTHING

# 3. START BB SERVER
cd /Users/franksiebenlist/Development/sente_lite/dev/scittle-demo
bb dev

# 4. START PLAYWRIGHT BROWSER
npm run interactive

# 5. RE-UPLOAD ALL CODE (everything is lost!)
# - Upload server code via port 1338
# - Upload client code via port 1339
# - Start port 1342 WebSocket
```

## Files Created

### Core Files
- `bb.edn` - BB configuration with 4 parallel tasks
- `index.html` - Scittle page with nREPL WebSocket
- `playground.cljs` - ClojureScript code loaded on page load
- `package.json` - Playwright dependencies

### Test Scripts
- `playwright-test.mjs` - Automated 30-second test
- `playwright-interactive.mjs` - Interactive session with DevTools
- `eval-cljs-demo.mjs` - ClojureScript eval demo
- `quick-eval.mjs` - Quick JavaScript eval test
- `take-screenshot.mjs` - Screenshot utility

### Server Loading Scripts
- `load-sente-server.bb` - Loads code into BB via port 1338 nREPL

### Documentation
- `README.md` - Full usage documentation
- `RECOVERY.md` - This file

## What Works (Verified)

✅ **BB nREPL (port 1338)**: Eval Clojure in BB server
```bash
bb -Sdeps '{:deps {babashka/nrepl-client {:git/url "https://github.com/babashka/nrepl-client" :git/sha "19fbef2525e47d80b9278c49a545de58f48ee7cf"}}}' -e '
(require (quote [babashka.nrepl-client :as nrepl]))
(nrepl/eval-expr {:port 1338 :expr "(+ 10 20 30)"})'
# => {:vals [60]}
```

✅ **Browser nREPL (port 1339)**: Eval ClojureScript in browser
```bash
bb -Sdeps '{:deps {babashka/nrepl-client {:git/url "https://github.com/babashka/nrepl-client" :git/sha "19fbef2525e47d80b9278c49a545de58f48ee7cf"}}}' -e '
(require (quote [babashka.nrepl-client :as nrepl]))
(nrepl/eval-expr {:port 1339 :expr "(+ 1 2 3)"})'
# => {:vals ["6"]}
```

✅ **DOM Manipulation**: Add elements via nREPL
```clojure
(nrepl/eval-expr {:port 1339 :expr "
(let [p (.createElement js/document \"p\")]
  (set! (.-textContent p) \"Added via nREPL!\")
  (set! (.-style.color p) \"purple\")
  (.appendChild (.-body js/document) p))"})
```

✅ **WebSocket Server on Port 1342**: http-kit server started via nREPL
```clojure
(nrepl/eval-expr {:port 1338 :expr "
(do
  (require (quote [org.httpkit.server :as http]))
  (def test-server (http/run-server
    (fn [req] {:status 200 :body \"OK\"})
    {:port 1342})))"})
```

## What Gets Lost on Restart

⚠️ **EVERYTHING uploaded via nREPL is lost:**
- All code loaded into BB server memory
- All code loaded into browser memory
- All WebSocket servers started via nREPL
- All connections

**The base 4 services (1338, 1339, 1340, 1341) restart automatically.**

## Common Mistakes

1. ❌ Not killing old processes → multiple BB instances fighting for ports
2. ❌ Browser connected to OLD dead BB instance → nREPL hangs
3. ❌ Forgetting ports are allocated by old processes → new BB can't start
4. ❌ Not re-uploading code after restart → trying to use undefined vars
5. ❌ Pretending things work when they don't → NEVER DO THIS

## Next Steps (When Resumed)

1. Start from scratch (5-step sequence above)
2. Load sente-lite server code into BB via port 1338
3. Load sente-lite client code into browser via port 1339
4. Establish bidirectional sente-lite connection
5. Test live REPL development: modify and reload code without restarts

## Key Insight

This enables **TRUE live REPL-driven development** for distributed systems:
- Modify server code → eval in BB → no restart
- Modify client code → eval in browser → no page refresh
- Both sides connected via WebSocket
- Playwright observes and automates everything

## Rule

**If something doesn't work → START FROM SCRATCH. DO NOT CHEAT. DO NOT LIE.**
