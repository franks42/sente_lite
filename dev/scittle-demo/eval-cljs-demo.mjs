#!/usr/bin/env node
/**
 * Demonstrate evaluating ClojureScript in Scittle from Playwright
 */

import { chromium } from 'playwright';

async function evalClojureScript() {
  console.log('🎭 Launching browser...\n');

  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();

  // Enable console logging
  page.on('console', msg => {
    console.log(`   [Browser]: ${msg.text()}`);
  });

  await page.goto('http://localhost:1341');
  await page.waitForTimeout(2000); // Wait for Scittle to load

  console.log('📊 Evaluating ClojureScript via Scittle...\n');

  // Method 1: Use scittle.core/eval-string
  const result1 = await page.evaluate(() => {
    // Call Scittle's eval-string function
    const evalString = window.scittle.core.eval_string;

    // Evaluate some ClojureScript code
    const result = evalString('(+ 1 2 3)');

    return {
      expression: '(+ 1 2 3)',
      result: result
    };
  });

  console.log(`Expression: ${result1.expression}`);
  console.log(`Result: ${result1.result}\n`);

  // Method 2: More complex example
  const result2 = await page.evaluate(() => {
    const evalString = window.scittle.core.eval_string;

    // Define a function and call it
    evalString('(defn greet [name] (str "Hello, " name "!"))');
    const greeting = evalString('(greet "Playwright")');

    return {
      expression: '(greet "Playwright")',
      result: greeting
    };
  });

  console.log(`Expression: ${result2.expression}`);
  console.log(`Result: ${result2.result}\n`);

  // Method 3: DOM manipulation from ClojureScript
  console.log('🎨 Adding DOM element via ClojureScript...\n');

  await page.evaluate(() => {
    const evalString = window.scittle.core.eval_string;

    evalString(`
      (let [p (.createElement js/document "p")]
        (set! (.-textContent p) "Added via ClojureScript eval!")
        (set! (.-style.color p) "green")
        (set! (.-style.fontSize p) "18px")
        (.appendChild (.-body js/document) p))
    `);
  });

  // Take screenshot
  await page.screenshot({ path: 'dev/scittle-demo/cljs-eval-demo.png' });
  console.log('📸 Screenshot saved to dev/scittle-demo/cljs-eval-demo.png\n');

  await browser.close();
  console.log('✅ Done!\n');
}

evalClojureScript().catch(console.error);
