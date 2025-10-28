#!/usr/bin/env node
/**
 * Quick test to evaluate JavaScript in the running browser
 */

import { chromium } from 'playwright';

async function testEval() {
  const browser = await chromium.connectOverCDP('http://localhost:9222');
  const contexts = browser.contexts();

  if (contexts.length === 0) {
    console.log('No browser contexts found. Is the browser running?');
    await browser.close();
    return;
  }

  const context = contexts[0];
  const pages = context.pages();

  if (pages.length === 0) {
    console.log('No pages found.');
    await browser.close();
    return;
  }

  const page = pages[0];

  // Evaluate some JavaScript
  const result = await page.evaluate(() => {
    return {
      title: document.title,
      url: window.location.href,
      bodyText: document.body.innerText.substring(0, 100),
      scittleAvailable: typeof window.scittle !== 'undefined'
    };
  });

  console.log('Browser state:');
  console.log(JSON.stringify(result, null, 2));

  await browser.close();
}

testEval().catch(console.error);
