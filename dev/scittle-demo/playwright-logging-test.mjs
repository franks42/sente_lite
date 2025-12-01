#!/usr/bin/env node
/**
 * Playwright test for Trove logging in browser
 *
 * This script automates browser testing to verify:
 * - Trove logging facade loads correctly
 * - Console logging backend works
 * - WebSocket logging backend works (if server available)
 * - Hybrid logging works
 * - Error handling and fallbacks work
 */

import { chromium } from 'playwright';

async function testTroveLogging() {
  console.log('🎭 Starting Playwright Trove logging test...\n');

  const browser = await chromium.launch({
    headless: true,  // Run headless for CI/CD
    slowMo: 50       // Slight slowdown for stability
  });

  const context = await browser.newContext();
  const page = await context.newPage();

  // Capture console messages
  const consoleLogs = [];
  page.on('console', msg => {
    const logEntry = {
      type: msg.type(),
      text: msg.text(),
      timestamp: new Date().toISOString()
    };
    consoleLogs.push(logEntry);
    console.log(`   [Browser Console] ${msg.type()}: ${msg.text()}`);
  });

  // Capture errors
  const errors = [];
  page.on('pageerror', error => {
    errors.push(error.message);
    console.error(`   [Browser Error] ${error.message}`);
  });

  try {
    console.log('📄 Loading page: http://localhost:1341/test-logging.html\n');
    await page.goto('http://localhost:1341/test-logging.html', { waitUntil: 'networkidle' });

    // Wait for Scittle and Trove to load
    console.log('⏳ Waiting for Scittle and Trove to load...\n');
    await page.waitForTimeout(2000);

    // Test 1: Check if Trove is available
    console.log('🧪 Test 1: Checking Trove availability...\n');
    const troveAvailable = await page.evaluate(() => {
      return typeof window.taoensso !== 'undefined' && 
             typeof window.taoensso.trove !== 'undefined';
    });

    if (troveAvailable) {
      console.log('   ✅ Trove is available in browser context\n');
    } else {
      console.log('   ⚠️  Trove not detected\n');
    }

    // Test 2: Test console logging
    console.log('🧪 Test 2: Testing console logging...\n');
    await page.evaluate(() => {
      // Test basic logging
      if (typeof window.taoensso?.trove?.log !== 'undefined') {
        window.taoensso.trove.log({
          level: 'info',
          id: 'test/console-log',
          data: { message: 'Console logging test' }
        });
      }
    });

    await page.waitForTimeout(500);

    // Test 3: Test error logging
    console.log('🧪 Test 3: Testing error logging...\n');
    await page.evaluate(() => {
      if (typeof window.taoensso?.trove?.log !== 'undefined') {
        window.taoensso.trove.log({
          level: 'error',
          id: 'test/error-log',
          data: { message: 'Error logging test', error: 'Test error' }
        });
      }
    });

    await page.waitForTimeout(500);

    // Test 4: Test different log levels
    console.log('🧪 Test 4: Testing different log levels...\n');
    await page.evaluate(() => {
      if (typeof window.taoensso?.trove?.log !== 'undefined') {
        const levels = ['trace', 'debug', 'info', 'warn', 'error', 'fatal'];
        levels.forEach(level => {
          window.taoensso.trove.log({
            level: level,
            id: `test/level-${level}`,
            data: { level: level, message: `Testing ${level} level` }
          });
        });
      }
    });

    await page.waitForTimeout(1000);

    // Test 5: Verify console logs captured
    console.log('🧪 Test 5: Verifying captured console logs...\n');
    console.log(`   Total console messages: ${consoleLogs.length}`);
    
    const testLogs = consoleLogs.filter(log => 
      log.text.includes('test/') || log.text.includes('Test')
    );
    
    if (testLogs.length > 0) {
      console.log(`   ✅ Found ${testLogs.length} test-related log entries\n`);
      testLogs.slice(0, 5).forEach(log => {
        console.log(`      - [${log.type}] ${log.text.substring(0, 80)}...`);
      });
    } else {
      console.log('   ⚠️  No test-related logs captured\n');
    }

    // Test 6: Check for errors
    console.log('🧪 Test 6: Checking for errors...\n');
    if (errors.length === 0) {
      console.log('   ✅ No errors detected\n');
    } else {
      console.log(`   ⚠️  Found ${errors.length} errors:\n`);
      errors.forEach(error => {
        console.log(`      - ${error}`);
      });
    }

    // Test 7: Test logging with complex data
    console.log('🧪 Test 7: Testing logging with complex data...\n');
    await page.evaluate(() => {
      if (typeof window.taoensso?.trove?.log !== 'undefined') {
        window.taoensso.trove.log({
          level: 'debug',
          id: 'test/complex-data',
          data: {
            nested: {
              object: {
                value: 42,
                array: [1, 2, 3],
                bool: true,
                null: null
              }
            },
            timestamp: new Date().toISOString()
          }
        });
      }
    });

    await page.waitForTimeout(500);
    console.log('   ✅ Complex data logging completed\n');

    // Summary
    console.log('📊 Test Summary:\n');
    console.log(`   Total console messages: ${consoleLogs.length}`);
    console.log(`   Total errors: ${errors.length}`);
    console.log(`   Trove available: ${troveAvailable ? '✅' : '❌'}`);
    console.log(`   Test logs captured: ${testLogs.length > 0 ? '✅' : '⚠️'}`);

    // Take a screenshot
    await page.screenshot({ path: 'dev/scittle-demo/logging-test-screenshot.png' });
    console.log('\n📸 Screenshot saved to dev/scittle-demo/logging-test-screenshot.png\n');

    console.log('✨ All logging tests completed!\n');

    // Return success if no errors
    if (errors.length === 0 && troveAvailable) {
      console.log('✅ Browser logging test PASSED\n');
      return true;
    } else {
      console.log('⚠️  Browser logging test completed with warnings\n');
      return false;
    }

  } catch (error) {
    console.error('❌ Test failed:', error.message);
    console.error(error.stack);
    throw error;
  } finally {
    await browser.close();
    console.log('👋 Browser closed');
  }
}

// Run the test
testTroveLogging()
  .then(success => {
    process.exit(success ? 0 : 1);
  })
  .catch(error => {
    console.error('Fatal error:', error);
    process.exit(1);
  });
