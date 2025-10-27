# Connection Timing Investigation - Quick Start

**Status:** 2025-10-26 - Investigation paused, needs fresh perspective

## TL;DR

WebSocket connections sometimes fail initially in tests but succeed via auto-reconnection. Isolated test runs work fine with even 250ms delays. Original test environment shows failures at 2-3 seconds. **Cannot reliably reproduce the failure.**

## What You Need to Know

1. **All tests currently PASS** - Reconnection logic compensates for initial connection failures
2. **All code changes were REVERTED** - Repository is in clean state
3. **Root cause unknown** - Timing behavior differs between isolated and sequential test runs
4. **Full details in:** `doc/connection-timing-investigation.md`

## Quick Test Commands

```bash
# Test that shows failure pattern (passes via reconnection)
bb test/scripts/bb_client_tests/07_reconnection.bb

# Check logs for connection timing
grep -E "reconnect-scheduled|connected|:open" /tmp/07_after_revert.log

# Isolated test that WORKS (for comparison)
bb /tmp/test_2sec_with_telemetry.bb
```

## Key Files

**Investigation docs:**
- `doc/connection-timing-investigation.md` - Full analysis, hypotheses, next steps

**Source files with issues:**
- `src/sente_lite/server.cljc:424-425` - Telemetry handler hangs if `:handler-id` is nil
- `test/scripts/bb_client_tests/09_integration.bb:127-131` - Simplified to hide problem

**Log files (in /tmp/):**
- `/tmp/07_after_revert.log` - Shows connection failure pattern (IMPORTANT)
- `/tmp/timing_test_detailed.log` - Shows isolated tests working

## Three Main Questions

1. **Why do isolated tests work but original tests fail?**
   - Same code, different behavior
   - Cannot reliably reproduce failure

2. **Is telemetry initialization causing delays?**
   - File handler might block
   - Need enable/disable comparison

3. **Should we fix this or document it?**
   - Auto-reconnection handles it gracefully
   - Might not be a real production issue

## Next Steps (See Full Doc for Details)

1. **HIGH PRIORITY:** Reproduce failure reliably
2. **HIGH PRIORITY:** Measure telemetry impact (enable vs disable)
3. Monitor `::server-started` event vs actual readiness
4. Add server health check instead of `Thread/sleep`

## Repository State

```
Branch: main
Status: Clean (no uncommitted code changes)
Tests: All passing
Untracked: doc/connection-timing-investigation.md (this investigation)
```

## What Previous Claude Tried (and Failed)

**Attempted:** Promise-based blocking in `ws_client_managed.clj` to wait for `:on-open`
**Result:** Made it worse - tests hung completely
**Action:** Reverted via `git restore`

The issue is NOT that `:on-open` doesn't fire - it's that the initial connection attempt happens before server is ready.

---

**Read the full investigation doc for complete details, hypotheses, and suggested investigation steps.**
