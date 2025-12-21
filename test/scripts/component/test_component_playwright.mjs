#!/usr/bin/env node
/**
 * Test component system on Scittle via Playwright
 * Run: node test/scripts/component/test_component_playwright.mjs
 */

import { chromium } from 'playwright';
import { fileURLToPath } from 'url';
import path from 'path';
import fs from 'fs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

async function runTests() {
  const htmlPath = path.join(__dirname, 'test_component_scittle.html');

  if (!fs.existsSync(htmlPath)) {
    console.error('ERROR: HTML file not found:', htmlPath);
    process.exit(1);
  }

  console.log('Launching browser...');
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  const logs = [];
  let testsPassed = 0;
  let testsFailed = 0;

  // Capture console logs
  page.on('console', msg => {
    const text = msg.text();
    logs.push(text);

    // Extract test counts
    if (text.startsWith('TESTS_PASSED=')) {
      testsPassed = parseInt(text.split('=')[1], 10);
    }
    if (text.startsWith('TESTS_FAILED=')) {
      testsFailed = parseInt(text.split('=')[1], 10);
    }
  });

  // Capture errors
  page.on('pageerror', err => {
    console.error('PAGE ERROR:', err.message);
    logs.push(`ERROR: ${err.message}`);
  });

  try {
    console.log('Loading test page...');
    await page.goto(`file://${htmlPath}`, { waitUntil: 'networkidle' });

    // Wait for tests to complete (look for results in console)
    await page.waitForFunction(() => {
      const results = document.getElementById('results');
      return results && results.textContent.includes('=== Results ===');
    }, { timeout: 30000 });

    // Get page content for display
    const results = await page.textContent('#results');
    console.log('\n' + results);

    console.log('\n=== Playwright Test Summary ===');
    console.log(`Tests passed: ${testsPassed}`);
    console.log(`Tests failed: ${testsFailed}`);

    await browser.close();

    if (testsFailed > 0) {
      console.log('\nSome tests FAILED!');
      process.exit(1);
    } else {
      console.log('\nAll tests PASSED!');
      process.exit(0);
    }
  } catch (error) {
    console.error('\nTest execution error:', error.message);
    console.log('\nCaptured logs:');
    logs.forEach(log => console.log('  ', log));
    await browser.close();
    process.exit(1);
  }
}

runTests();
