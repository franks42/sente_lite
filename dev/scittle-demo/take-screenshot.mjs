#!/usr/bin/env node
/**
 * Take a screenshot of the current page state
 */

import { chromium } from 'playwright';

async function takeScreenshot() {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  await page.goto('http://localhost:1341');
  await page.waitForTimeout(2000); // Wait for Scittle to load

  await page.screenshot({ path: 'dev/scittle-demo/nrepl-success.png' });
  console.log('Screenshot saved to dev/scittle-demo/nrepl-success.png');

  await browser.close();
}

takeScreenshot().catch(console.error);
