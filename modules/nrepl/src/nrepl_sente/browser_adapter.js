/**
 * sente-lite nREPL Browser Adapter - FakeWebSocket (JavaScript version)
 *
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║  ⚠️  DEPRECATED: Use the pure CLJS inline approach instead!               ║
 * ║                                                                           ║
 * ║  See: test/test_browser_adapter.html for the recommended approach         ║
 * ║  using inline CLJS + eval_script_tags() - no JavaScript needed!           ║
 * ║                                                                           ║
 * ║  This JS file is kept for reference/compatibility but the CLJS solution   ║
 * ║  is preferred for 100% Clojure codebase.                                  ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 *
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║  If you still want to use this JS version:                                ║
 * ║                                                                           ║
 * ║  This file MUST be loaded:                                                ║
 * ║    - AFTER  scittle.js                                                    ║
 * ║    - BEFORE scittle.nrepl.js                                              ║
 * ║                                                                           ║
 * ║  If scittle.nrepl.js loads first, it creates its own WebSocket and       ║
 * ║  the hijack fails silently!                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 *
 * This file creates a FakeWebSocket at window.ws_nrepl that intercepts nREPL
 * messages and routes them through sente-lite instead of a direct WebSocket.
 *
 * How it works:
 * 1. We create window.ws_nrepl with readyState=1 (OPEN) BEFORE scittle.nrepl.js loads
 * 2. scittle.nrepl.js sees the existing socket and attaches its onmessage handler
 * 3. We intercept send() to route responses through sente-lite
 * 4. We provide injectMessage() to feed requests from sente-lite to sci.nrepl
 *
 * Correct HTML order:
 *   <script src="scittle.js"></script>
 *   <script src="browser_adapter.js"></script>           <!-- ⚠️ THIS FILE FIRST -->
 *   <script src="scittle.nrepl.js"></script>             <!-- THEN nrepl.js -->
 *   <script src="client_scittle.cljs" type="application/x-scittle"></script>
 *   <script src="browser_adapter.cljs" type="application/x-scittle"></script>
 */

(function() {
  'use strict';

  console.log('[nrepl-adapter] Initializing FakeWebSocket for sente-lite integration');

  /**
   * FakeWebSocket - mimics WebSocket API but routes through sente-lite
   */
  class SenteLiteNreplSocket {
    constructor() {
      this.readyState = 1; // WebSocket.OPEN
      this.onmessage = null;
      this.onerror = null;
      this.onclose = null;
      this.onopen = null;

      // Queue for messages received before sente-lite is connected
      this._pendingInbound = [];

      // Send function - will be set by Scittle adapter code
      this._sendFn = null;

      console.log('[nrepl-adapter] SenteLiteNreplSocket created');
    }

    /**
     * Called by sci.nrepl.server to send responses
     * Routes to sente-lite via _sendFn
     */
    send(data) {
      if (this._sendFn) {
        this._sendFn(data);
      } else {
        console.warn('[nrepl-adapter] send() called but _sendFn not set yet:', data);
      }
    }

    /**
     * Inject a message as if it came from the WebSocket
     * Called by sente-lite when an nREPL request arrives
     */
    injectMessage(data) {
      if (this.onmessage) {
        this.onmessage({ data: data });
      } else {
        // Queue if handler not attached yet
        this._pendingInbound.push(data);
      }
    }

    /**
     * Process any queued messages (call after scittle.nrepl.js loads)
     */
    flushPending() {
      while (this._pendingInbound.length > 0) {
        const data = this._pendingInbound.shift();
        if (this.onmessage) {
          this.onmessage({ data: data });
        }
      }
    }

    /**
     * Set the send function (called from Scittle adapter)
     */
    setSendFn(fn) {
      this._sendFn = fn;
      console.log('[nrepl-adapter] Send function registered');
    }

    close() {
      console.log('[nrepl-adapter] close() called');
      this.readyState = 3; // WebSocket.CLOSED
      if (this.onclose) {
        this.onclose({ code: 1000, reason: 'Normal closure' });
      }
    }
  }

  // Create and expose the socket BEFORE scittle.nrepl.js loads
  window.ws_nrepl = new SenteLiteNreplSocket();

  // Also expose the class for testing
  window.SenteLiteNreplSocket = SenteLiteNreplSocket;

  console.log('[nrepl-adapter] window.ws_nrepl ready for scittle.nrepl.js');
})();
