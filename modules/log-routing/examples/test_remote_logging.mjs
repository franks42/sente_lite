#!/usr/bin/env node
/**
 * Playwright test for Remote Logging Demo
 *
 * Tests:
 * 1. Config discovery from JSON script tag (ephemeral port)
 * 2. Handler switching via registry indirection
 * 3. Log routing to server via sente
 *
 * Usage:
 *   # Start server first:
 *   bb modules/log-routing/examples/remote_logging.bb
 *
 *   # Run test:
 *   node modules/log-routing/examples/test_remote_logging.mjs
 */

import { chromium } from 'playwright';

const HTTP_PORT = 1351;
const TEST_TIMEOUT = 15000;

async function runTests() {
  console.log('\n=== Remote Logging Playwright Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  let passed = 0;
  let failed = 0;

  function test(name, condition) {
    if (condition) {
      console.log(`  ✓ ${name}`);
      passed++;
    } else {
      console.log(`  ✗ ${name}`);
      failed++;
    }
  }

  try {
    // Navigate to demo
    console.log('Loading page...');
    await page.goto(`http://localhost:${HTTP_PORT}/`);

    // Wait for app to be ready
    await page.waitForFunction(() => window.testReady === true, { timeout: TEST_TIMEOUT });
    console.log('App ready.\n');

    // Test 1: Config discovery worked
    console.log('Testing Config Discovery:');
    const configText = await page.locator('#config').textContent();
    test('Config shows ephemeral port', configText.includes('wsPort:') && !configText.includes('9999'));
    test('Config shows host', configText.includes('wsHost: localhost'));

    // Test 2: Handler switching
    console.log('\nTesting Handler Switching:');

    // Switch to sente
    await page.click('button:has-text("Use Sente")');
    await page.waitForTimeout(200);
    let handlerBadge = await page.locator('#current-handler .handler-badge').textContent();
    test('Can switch to sente handler', handlerBadge.includes('sente'));

    // Switch to console
    await page.click('button:has-text("Use Console")');
    await page.waitForTimeout(200);
    handlerBadge = await page.locator('#current-handler .handler-badge').textContent();
    test('Can switch to console handler', handlerBadge.includes('console'));

    // Switch to silent
    await page.click('button:has-text("Use Silent")');
    await page.waitForTimeout(200);
    handlerBadge = await page.locator('#current-handler .handler-badge').textContent();
    test('Can switch to silent handler', handlerBadge.includes('silent'));

    // Test 3: Log routing to server
    console.log('\nTesting Log Routing to Server:');

    // Switch to sente handler
    await page.click('button:has-text("Use Sente")');
    await page.waitForTimeout(200);

    // Send some logs
    await page.click('button:has-text("Log Info")');
    await page.waitForTimeout(300);
    await page.click('button:has-text("Log Warn")');
    await page.waitForTimeout(300);
    await page.click('button:has-text("Log Error")');
    await page.waitForTimeout(500);

    // Check results
    const results = await page.evaluate(() => {
      return {
        sent: window.testResultsSent || 0,
        received: window.testResultsReceived || 0
      };
    });

    test('Logs were sent', results.sent >= 3);
    test('Server acknowledged logs', results.received >= 3);

    // Check activity log shows server acks
    const logContent = await page.locator('#log').textContent();
    test('Activity log shows Server ACK', logContent.includes('Server ACK'));

    // Test 4: Console handler doesn't send to server
    console.log('\nTesting Console Handler (local only):');

    const beforeReceived = results.received;
    await page.click('button:has-text("Use Console")');
    await page.waitForTimeout(200);
    await page.click('button:has-text("Log Info")');
    await page.waitForTimeout(500);

    const afterResults = await page.evaluate(() => {
      return {
        sent: window.testResultsSent || 0,
        received: window.testResultsReceived || 0
      };
    });

    // Received count should NOT increase for console handler
    test('Console handler does not send to server', afterResults.received === beforeReceived);

    console.log(`\n=== Results: ${passed} passed, ${failed} failed ===\n`);

  } catch (error) {
    console.error('\nTest error:', error.message);
    failed++;
  } finally {
    await browser.close();
  }

  process.exit(failed > 0 ? 1 : 0);
}

runTests();
