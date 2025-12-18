#!/usr/bin/env node
/**
 * Playwright automated test for sente-lite client_scittle.cljs
 * 
 * This script:
 * 1. Starts the BB v2 test server
 * 2. Starts a static file server for HTML
 * 3. Launches Playwright browser
 * 4. Runs the client tests
 * 5. Reports pass/fail and exits
 * 
 * Usage: node playwright-client-test.mjs
 */

import { chromium } from 'playwright';
import { spawn } from 'child_process';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

// Configuration
const BB_SERVER_PORT = 1345;
const STATIC_SERVER_PORT = 8765;
const TEST_TIMEOUT_MS = 30000;

// MIME types for static server
const MIME_TYPES = {
  '.html': 'text/html',
  '.js': 'application/javascript',
  '.cljs': 'text/plain',
  '.cljc': 'text/plain',
  '.clj': 'text/plain',
  '.css': 'text/css',
  '.json': 'application/json',
};

let bbServer = null;
let staticServer = null;

function log(msg) {
  console.log(`[test] ${msg}`);
}

function logError(msg) {
  console.error(`[ERROR] ${msg}`);
}

// Start BB WebSocket server
function startBBServer() {
  return new Promise((resolve, reject) => {
    log('Starting BB v2 test server...');
    
    bbServer = spawn('bb', ['v2-test-server.bb'], {
      cwd: __dirname,
      stdio: ['ignore', 'pipe', 'pipe']
    });

    let started = false;

    bbServer.stdout.on('data', (data) => {
      const output = data.toString();
      if (output.includes('Server started')) {
        started = true;
        log(`BB server started on port ${BB_SERVER_PORT}`);
        resolve();
      }
    });

    bbServer.stderr.on('data', (data) => {
      // BB outputs some info to stderr
      const output = data.toString().trim();
      if (output && !output.includes('WARNING')) {
        console.log(`[bb] ${output}`);
      }
    });

    bbServer.on('error', (err) => {
      reject(new Error(`Failed to start BB server: ${err.message}`));
    });

    bbServer.on('exit', (code) => {
      if (!started) {
        reject(new Error(`BB server exited with code ${code} before starting`));
      }
    });

    // Timeout for server start
    setTimeout(() => {
      if (!started) {
        reject(new Error('BB server startup timeout'));
      }
    }, 10000);
  });
}

// Simple static file server
function startStaticServer() {
  return new Promise((resolve) => {
    log('Starting static file server...');
    
    staticServer = http.createServer((req, res) => {
      let filePath = req.url === '/' ? '/test-client-scittle-v2.html' : req.url;
      
      // Handle paths relative to project root
      let fullPath;
      if (filePath.startsWith('/src/')) {
        fullPath = path.join(__dirname, '../..', filePath);
      } else {
        fullPath = path.join(__dirname, filePath);
      }

      // Security: prevent directory traversal beyond project
      const projectRoot = path.join(__dirname, '../..');
      if (!fullPath.startsWith(projectRoot)) {
        res.writeHead(403);
        res.end('Forbidden');
        return;
      }

      fs.readFile(fullPath, (err, data) => {
        if (err) {
          res.writeHead(404);
          res.end(`Not found: ${filePath}`);
          return;
        }

        const ext = path.extname(fullPath);
        const contentType = MIME_TYPES[ext] || 'application/octet-stream';
        
        res.writeHead(200, {
          'Content-Type': contentType,
          'Access-Control-Allow-Origin': '*'
        });
        res.end(data);
      });
    });

    staticServer.listen(STATIC_SERVER_PORT, () => {
      log(`Static server started on port ${STATIC_SERVER_PORT}`);
      resolve();
    });
  });
}

// Run Playwright tests
async function runTests() {
  log('Launching Playwright browser...');
  
  const browser = await chromium.launch({
    headless: true
  });

  const context = await browser.newContext();
  const page = await context.newPage();

  // Collect console messages
  const consoleLogs = [];
  page.on('console', msg => {
    const text = msg.text();
    consoleLogs.push(`[${msg.type()}] ${text}`);
    // Show important messages
    if (text.includes('TESTS_COMPLETE') || text.includes('Error') || msg.type() === 'error') {
      console.log(`   [browser] ${text}`);
    }
  });

  // Collect page errors
  page.on('pageerror', error => {
    logError(`Page error: ${error.message}`);
  });

  try {
    log(`Loading test page: http://localhost:${STATIC_SERVER_PORT}/test-client-scittle-v2.html`);
    await page.goto(`http://localhost:${STATIC_SERVER_PORT}/test-client-scittle-v2.html`);

    // Wait for tests to complete
    log('Waiting for tests to complete...');
    
    await page.waitForFunction(
      () => window.testsComplete === true,
      { timeout: TEST_TIMEOUT_MS }
    );

    // Get test results
    const passed = await page.evaluate(() => window.testsPassed);
    
    // Get summary from page
    const summary = await page.evaluate(() => {
      const summaryEl = document.querySelector('.summary');
      return summaryEl ? summaryEl.innerText : 'No summary found';
    });

    log('');
    log('='.repeat(50));
    log('TEST RESULTS');
    log('='.repeat(50));
    console.log(summary);
    log('='.repeat(50));
    
    await browser.close();
    
    return passed;

  } catch (error) {
    logError(`Test error: ${error.message}`);
    
    // Dump console logs for debugging
    log('Console logs:');
    consoleLogs.forEach(l => console.log(`   ${l}`));
    
    await browser.close();
    return false;
  }
}

// Cleanup
function cleanup() {
  log('Cleaning up...');
  
  if (bbServer) {
    bbServer.kill('SIGTERM');
    bbServer = null;
  }
  
  if (staticServer) {
    staticServer.close();
    staticServer = null;
  }
}

// Main
async function main() {
  console.log('');
  console.log('='.repeat(50));
  console.log('  sente-lite Scittle Client Test');
  console.log('='.repeat(50));
  console.log('');

  try {
    // Start servers
    await startBBServer();
    await startStaticServer();
    
    // Give servers a moment to stabilize
    await new Promise(r => setTimeout(r, 1000));
    
    // Run tests
    const passed = await runTests();
    
    // Cleanup
    cleanup();
    
    // Exit with appropriate code
    if (passed) {
      log('✓ All tests passed!');
      process.exit(0);
    } else {
      logError('✗ Some tests failed!');
      process.exit(1);
    }

  } catch (error) {
    logError(`Fatal error: ${error.message}`);
    cleanup();
    process.exit(1);
  }
}

// Handle interrupts
process.on('SIGINT', () => {
  log('Interrupted');
  cleanup();
  process.exit(130);
});

process.on('SIGTERM', () => {
  log('Terminated');
  cleanup();
  process.exit(143);
});

main();
