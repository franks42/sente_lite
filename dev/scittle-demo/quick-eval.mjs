#!/usr/bin/env node
/**
 * Quick test to evaluate JavaScript in the browser
 */

import { chromium } from 'playwright';

async function quickEval() {
  console.log('🔧 Connecting to browser...\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  await page.goto('http://localhost:1341');
  await page.waitForTimeout(2000); // Wait for Scittle to load

  console.log('📊 Evaluating JavaScript in browser...\n');

  // Evaluate various things
  const results = await page.evaluate(() => {
    return {
      title: document.title,
      url: window.location.href,
      bodyContent: document.body.innerText,
      scittleLoaded: typeof window.scittle !== 'undefined',
      domNodes: document.querySelectorAll('*').length,
      // Try evaluating ClojureScript
      canAccessConsole: typeof console !== 'undefined',
      timestamp: new Date().toISOString()
    };
  });

  console.log('Results from browser:');
  console.log('─────────────────────');
  console.log(`Title: ${results.title}`);
  console.log(`URL: ${results.url}`);
  console.log(`Scittle loaded: ${results.scittleLoaded}`);
  console.log(`DOM nodes: ${results.domNodes}`);
  console.log(`Browser timestamp: ${results.timestamp}`);
  console.log(`\nPage content:\n${results.bodyContent}\n`);

  // Now let's manipulate the DOM
  console.log('🎨 Adding content to page via JavaScript...\n');

  await page.evaluate(() => {
    const p = document.createElement('p');
    p.style.color = 'blue';
    p.style.fontSize = '20px';
    p.textContent = '✨ This was added by AI via Playwright!';
    document.body.appendChild(p);
  });

  // Take a screenshot
  await page.screenshot({ path: 'dev/scittle-demo/ai-test.png' });
  console.log('📸 Screenshot saved to dev/scittle-demo/ai-test.png\n');

  await browser.close();
  console.log('✅ Done!\n');
}

quickEval().catch(console.error);
