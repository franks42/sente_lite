#!/usr/bin/env node
// Playwright test for sente-lite-nrepl bundle
// Usage: node test-bundle.mjs

import { chromium } from 'playwright';

const PORT = 8765;
const URL = `http://localhost:${PORT}/test-bundle.html`;

async function runTests() {
  console.log('=== sente-lite-nrepl Bundle Test ===\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  // Collect console output
  const logs = [];
  const errors = [];

  page.on('console', msg => {
    const text = msg.text();
    logs.push(text);
    if (msg.type() === 'log') {
      console.log(`[browser] ${text}`);
    }
  });

  page.on('pageerror', err => {
    errors.push(err.message);
    console.error(`[ERROR] ${err.message}`);
  });

  try {
    console.log(`Loading ${URL}...\n`);
    await page.goto(URL, { timeout: 30000 });

    // Wait for tests to complete
    await page.waitForFunction(() => window.BUNDLE_TEST_PASSED !== undefined, { timeout: 15000 });

    // Check result
    const passed = await page.evaluate(() => window.BUNDLE_TEST_PASSED);
    const status = await page.textContent('#status');

    console.log('\n--- Results ---');
    console.log(`Status: ${status}`);
    console.log(`BUNDLE_TEST_PASSED: ${passed}`);

    if (errors.length > 0) {
      console.log(`\nErrors encountered: ${errors.length}`);
      errors.forEach(e => console.log(`  - ${e}`));
    }

    // Verify specific namespaces loaded by checking if we can eval code that uses them
    const namespaces = await page.evaluate(() => {
      const results = {};
      const evalCode = (code) => {
        try {
          scittle.core.eval_string(code);
          return true;
        } catch(e) {
          console.log('Eval failed:', code, e.message);
          return false;
        }
      };
      results['sente-lite.client-scittle'] = evalCode('(require \'[sente-lite.client-scittle :as c]) (fn? c/make-client!)');
      results['sente-lite.registry'] = evalCode('(require \'[sente-lite.registry :as r]) (fn? r/register!)');
      results['nrepl-sente.protocol'] = evalCode('(require \'[nrepl-sente.protocol :as p]) (keyword? p/request-event-id)');
      results['nrepl-sente.browser-adapter'] = evalCode('(require \'[nrepl-sente.browser-adapter :as a]) (fn? a/status)');
      return results;
    });

    console.log('\nNamespace verification:');
    let allNsLoaded = true;
    for (const [ns, loaded] of Object.entries(namespaces)) {
      const icon = loaded ? '✅' : '❌';
      console.log(`  ${icon} ${ns}`);
      if (!loaded) allNsLoaded = false;
    }

    await browser.close();

    const success = passed && errors.length === 0 && allNsLoaded;
    console.log(`\n${success ? '✅ ALL TESTS PASSED' : '❌ TESTS FAILED'}`);
    process.exit(success ? 0 : 1);

  } catch (err) {
    console.error(`\n❌ Test failed: ${err.message}`);
    await browser.close();
    process.exit(1);
  }
}

runTests();
