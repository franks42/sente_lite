// Run: node test/scripts/registry/test_config_discovery_playwright.mjs

import { chromium } from 'playwright';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

async function runTests() {
  console.log('Starting Config Discovery Pattern Tests...\n');

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext();
  const page = await context.newPage();

  page.on('console', msg => {
    console.log('Browser:', msg.text());
  });

  page.on('pageerror', err => {
    console.error('Page error:', err.message);
  });

  const testFile = join(__dirname, 'test_config_discovery.html');
  await page.goto(`file://${testFile}`);

  await page.waitForFunction(() => window.testResults !== undefined, { timeout: 30000 });

  const results = await page.evaluate(() => window.testResults);
  const output = await page.evaluate(() => document.getElementById('output').textContent);

  console.log('\n' + output);

  await browser.close();

  if (results.failed > 0) {
    console.log('\nSome tests failed!');
    process.exit(1);
  } else {
    console.log('\nAll tests passed!');
    process.exit(0);
  }
}

runTests().catch(err => {
  console.error('Test runner error:', err);
  process.exit(1);
});
