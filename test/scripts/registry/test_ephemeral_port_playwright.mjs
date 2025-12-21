// Run: node test/scripts/registry/test_ephemeral_port_playwright.mjs
//
// This test requires the ephemeral server to be running:
//   bb dev/scittle-demo/ephemeral_server.bb
//
// The test verifies:
// 1. Server starts on ephemeral port
// 2. HTML is served with port embedded in JSON
// 3. Client discovers port from JSON config
// 4. Client connects using discovered port
// 5. Connection succeeds with echo

import { chromium } from 'playwright';

async function runTests() {
  console.log('Starting Ephemeral Port Discovery Tests...\n');

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();

  let logs = [];
  page.on('console', msg => {
    const text = msg.text();
    logs.push(text);
    console.log('Browser:', text);
  });

  page.on('pageerror', err => {
    console.error('Page error:', err.message);
  });

  try {
    // Navigate to the ephemeral server
    console.log('Connecting to http://localhost:1350/...\n');
    await page.goto('http://localhost:1350/', { timeout: 10000 });

    // Wait for tests to complete
    await page.waitForFunction(() => window.testsComplete === true, { timeout: 30000 });

    // Get results
    const passed = await page.evaluate(() => window.testsPassed);
    const output = await page.evaluate(() => {
      const results = document.getElementById('results');
      return results ? results.innerText : 'No output';
    });

    // Get the discovered port
    const config = await page.evaluate(() => {
      const el = document.getElementById('sente-config');
      return el ? JSON.parse(el.textContent) : null;
    });

    console.log('\n=== Test Output ===');
    console.log(output);

    console.log('\n=== Config Discovered ===');
    console.log('Port:', config?.wsPort, '(ephemeral - different each run)');

    await browser.close();

    if (passed) {
      console.log('\n✓ All tests passed! Ephemeral port discovery works.');
      process.exit(0);
    } else {
      console.log('\n✗ Some tests failed!');
      process.exit(1);
    }
  } catch (err) {
    console.error('\nTest error:', err.message);
    console.log('\nMake sure the ephemeral server is running:');
    console.log('  bb dev/scittle-demo/ephemeral_server.bb');
    await browser.close();
    process.exit(1);
  }
}

runTests();
