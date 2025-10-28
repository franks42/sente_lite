#!/usr/bin/env node
/**
 * Interactive Playwright session for Scittle nREPL
 *
 * Launches browser and keeps it open indefinitely for manual testing.
 * Use Ctrl+C to close.
 */

import { chromium } from 'playwright';

async function interactiveSession() {
  console.log('🎭 Starting interactive Playwright session...\n');

  const browser = await chromium.launch({
    headless: false,
    slowMo: 100,
    devtools: true  // Open DevTools automatically
  });

  const context = await browser.newContext();
  const page = await context.newPage();

  // Enable console logging from browser
  page.on('console', msg => {
    const type = msg.type();
    const text = msg.text();
    console.log(`   [Browser ${type}]: ${text}`);
  });

  // Enable error logging
  page.on('pageerror', error => {
    console.error(`   [Browser Error]: ${error.message}`);
  });

  // Enable WebSocket logging
  page.on('websocket', ws => {
    console.log(`   [WebSocket] ${ws.url()}`);
    ws.on('framereceived', frame => {
      console.log(`   [WS ←]: ${frame.payload}`);
    });
    ws.on('framesent', frame => {
      console.log(`   [WS →]: ${frame.payload}`);
    });
  });

  try {
    console.log('📄 Loading page: http://localhost:1341\n');
    await page.goto('http://localhost:1341');

    // Wait for Scittle to load
    console.log('⏳ Waiting for Scittle to load...\n');
    await page.waitForTimeout(2000);

    // Handle alert
    page.on('dialog', async dialog => {
      console.log(`   [Browser Alert]: "${dialog.message()}"`);
      await dialog.accept();
    });

    console.log('✅ Browser ready!\n');
    console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n');
    console.log('📝 Instructions:');
    console.log('   1. Browser is now open with DevTools');
    console.log('   2. Connect nREPL client:');
    console.log('      clojure -Sdeps \'{:deps {nrepl/nrepl {:mvn/version "1.0.0"}}}\' \\');
    console.log('              -m nrepl.cmdline --connect --host localhost --port 1339');
    console.log('   3. Or use rebel:');
    console.log('      clojure -Sdeps \'{:deps {com.bhauman/rebel-readline {:mvn/version "0.1.4"} nrepl/nrepl {:mvn/version "1.0.0"}}}\' \\');
    console.log('              -m nrepl.cmdline --connect --host localhost --port 1339 --interactive');
    console.log('   4. Try evaluating: (+ 1 2 3)');
    console.log('   5. Check browser console for execution');
    console.log('   6. Press Ctrl+C here to close browser\n');
    console.log('━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n');

    // Keep alive indefinitely
    console.log('🔄 Browser will stay open until you press Ctrl+C...\n');

    // Wait forever (until process is killed)
    await new Promise(() => {});

  } catch (error) {
    if (error.message.includes('Target page')) {
      console.log('\n👋 Browser closed by user');
    } else {
      console.error('❌ Error:', error.message);
      throw error;
    }
  } finally {
    await browser.close();
    console.log('\n🛑 Session ended');
  }
}

// Handle Ctrl+C gracefully
process.on('SIGINT', () => {
  console.log('\n\n👋 Received Ctrl+C, closing browser...');
  process.exit(0);
});

// Run the session
interactiveSession().catch(error => {
  console.error('Fatal error:', error);
  process.exit(1);
});
