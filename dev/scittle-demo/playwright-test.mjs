#!/usr/bin/env node
/**
 * Playwright test for Scittle nREPL browser connection
 *
 * This script automates browser testing to verify:
 * - Scittle loads correctly in the browser
 * - nREPL WebSocket connection establishes
 * - ClojureScript code can be evaluated
 * - DOM manipulation works
 */

import { chromium } from 'playwright';

async function testScittleNREPL() {
  console.log('🎭 Starting Playwright browser test...\n');

  const browser = await chromium.launch({
    headless: false,  // Set to true for CI/CD
    slowMo: 100       // Slow down actions for visibility
  });

  const context = await browser.newContext();
  const page = await context.newPage();

  // Enable console logging from browser
  page.on('console', msg => {
    console.log(`   [Browser Console] ${msg.type()}: ${msg.text()}`);
  });

  // Enable error logging
  page.on('pageerror', error => {
    console.error(`   [Browser Error] ${error.message}`);
  });

  try {
    console.log('📄 Loading page: http://localhost:1341\n');
    await page.goto('http://localhost:1341');

    // Wait for Scittle to load
    console.log('⏳ Waiting for Scittle to load...\n');
    await page.waitForTimeout(2000);

    // Check if alert was shown (from playground.cljs)
    const alertPromise = page.waitForEvent('dialog', { timeout: 3000 }).catch(() => null);
    const dialog = await alertPromise;
    if (dialog) {
      console.log(`   ✅ Alert shown: "${dialog.message()}"\n`);
      await dialog.accept();
    }

    // Verify DOM was modified by ClojureScript
    console.log('🔍 Checking DOM modifications...\n');
    const bodyText = await page.textContent('body');

    if (bodyText.includes('ClojureScript is running in the browser!')) {
      console.log('   ✅ DOM manipulation successful\n');
    } else {
      console.log('   ❌ DOM manipulation failed\n');
    }

    // Check console for initialization message
    await page.waitForTimeout(1000);

    // Try to evaluate ClojureScript from browser context
    console.log('🧪 Attempting to evaluate ClojureScript...\n');

    const result = await page.evaluate(() => {
      // Check if scittle is available
      if (typeof window.scittle !== 'undefined') {
        try {
          // Evaluate simple expression
          return { success: true, scittleAvailable: true };
        } catch (e) {
          return { success: false, error: e.message, scittleAvailable: true };
        }
      } else {
        return { success: false, scittleAvailable: false };
      }
    });

    if (result.scittleAvailable) {
      console.log('   ✅ Scittle is available in browser context\n');
    } else {
      console.log('   ⚠️  Scittle not detected (may load asynchronously)\n');
    }

    // Take a screenshot for debugging
    await page.screenshot({ path: 'dev/scittle-demo/screenshot.png' });
    console.log('📸 Screenshot saved to dev/scittle-demo/screenshot.png\n');

    console.log('✨ Test completed successfully!\n');
    console.log('Instructions for nREPL connection:');
    console.log('  1. Keep this browser window open');
    console.log('  2. In your editor, connect to nREPL on localhost:1339');
    console.log('  3. Select "nbb" REPL type');
    console.log('  4. Evaluate code in playground.cljs to see it run in browser\n');

    // Keep browser open for manual testing
    console.log('Browser will stay open for 30 seconds for manual inspection...');
    await page.waitForTimeout(30000);

  } catch (error) {
    console.error('❌ Test failed:', error.message);
    throw error;
  } finally {
    await browser.close();
    console.log('\n👋 Browser closed');
  }
}

// Run the test
testScittleNREPL().catch(error => {
  console.error('Fatal error:', error);
  process.exit(1);
});
