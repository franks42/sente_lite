# Scittle Trove Build Pipeline

A ready-to-use GitHub Actions pipeline for building `scittle.trove.js` that Peter could adopt in the Trove repo.

## Overview

Automatically build and publish a compiled Scittle plugin on each Trove release:

```
Trove release tag → GitHub Action → shadow-cljs build → npm publish → jsdelivr CDN
```

## Files to Create

### 1. `.github/workflows/scittle-release.yml`

```yaml
name: Build Scittle Plugin

on:
  release:
    types: [published]
  workflow_dispatch:
    inputs:
      version:
        description: 'Version to build (e.g., 1.2.0)'
        required: true

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Setup Java
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      
      - name: Setup Node
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          registry-url: 'https://registry.npmjs.org'
      
      - name: Setup Clojure
        uses: DeLaGuardo/setup-clojure@12.5
        with:
          cli: latest
      
      - name: Install dependencies
        run: npm ci
      
      - name: Build scittle.trove.js
        run: npx shadow-cljs release scittle-trove
      
      - name: Update package.json version
        run: |
          VERSION=${{ github.event.release.tag_name || github.event.inputs.version }}
          VERSION=${VERSION#v}  # Remove 'v' prefix if present
          npm version $VERSION --no-git-tag-version
      
      - name: Publish to npm
        run: npm publish --access public
        env:
          NODE_AUTH_TOKEN: ${{ secrets.NPM_TOKEN }}
```

### 2. `shadow-cljs.edn`

```clojure
{:deps true
 :builds
 {:scittle-trove
  {:target :browser
   :output-dir "dist"
   :compiler-options {:optimizations :advanced}
   :modules {:scittle.trove {:init-fn scittle.trove/init!}}}}}
```

### 3. `src/scittle/trove.cljs`

```clojure
(ns scittle.trove
  (:require [scittle.core :as scittle]
            [taoensso.trove :as trove]
            [taoensso.trove.console :as console]))

(def config
  {:namespaces
   {'taoensso.trove
    {'*log-fn*    trove/*log-fn*
     'log!        (with-meta @#'trove/log! {:sci/macro true})
     'set-log-fn! (with-meta @#'trove/set-log-fn! {:sci/macro true})}
    
    'taoensso.trove.console
    {'get-log-fn console/get-log-fn}}})

(defn init! []
  (scittle/register-plugin! :taoensso.trove config))
```

### 4. `package.json`

```json
{
  "name": "@taoensso/scittle-trove",
  "version": "0.0.0",
  "description": "Trove logging plugin for Scittle",
  "main": "dist/scittle.trove.js",
  "files": ["dist"],
  "repository": {
    "type": "git",
    "url": "https://github.com/taoensso/trove"
  },
  "keywords": ["clojure", "clojurescript", "scittle", "logging", "trove"],
  "author": "Peter Taoussanis",
  "license": "EPL-2.0",
  "devDependencies": {
    "shadow-cljs": "^2.28.0"
  }
}
```

### 5. `deps.edn` additions

```clojure
;; Add to existing deps.edn under :aliases
:scittle
{:extra-deps {borkdude/sci {:mvn/version "0.8.43"}
              io.github.babashka/scittle {:git/tag "v0.7.28" :git/sha "..."}}}
```

## Setup Steps for Peter

1. **Create npm account** (if not already)
   - Sign up at npmjs.com
   - Create organization `@taoensso` or use personal scope

2. **Add npm token to GitHub**
   - Generate token: npmjs.com → Access Tokens → Generate New Token
   - Add to repo: Settings → Secrets → Actions → New secret
   - Name: `NPM_TOKEN`, Value: the token

3. **Copy files to repo**
   - `.github/workflows/scittle-release.yml`
   - `shadow-cljs.edn`
   - `src/scittle/trove.cljs`
   - `package.json`
   - Update `deps.edn` with scittle alias

4. **Test locally**
   ```bash
   npm install
   npx shadow-cljs release scittle-trove
   # Check dist/scittle.trove.js exists
   ```

5. **Publish first release**
   - Create a GitHub release
   - Action triggers automatically
   - Check npmjs.com for package

## Usage After Publishing

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.28/dist/scittle.js"></script>
<script src="https://cdn.jsdelivr.net/npm/@taoensso/scittle-trove@1.2.0/dist/scittle.trove.js"></script>

<script type="application/x-scittle">
  (require '[taoensso.trove :refer [log!]])
  (log! {:level :info :id :my/event :msg "Hello!"})
</script>
```

## Prerequisites

Before this works, Trove needs the utils.cljc fix:
- Remove `#?(:clj ...)` from `const?`
- Remove `#?(:clj ...)` from `callsite-coords`

## Alternative: PR to babashka/scittle

Instead of Peter maintaining a separate npm package, Trove could be added to the official Scittle plugins. This would:
- Require a PR to babashka/scittle
- Be maintained by the Scittle team
- Be published under the main `scittle` npm package
- Available at `cdn.jsdelivr.net/npm/scittle@X.X.X/dist/scittle.trove.js`

This is less work for Peter but requires Michiel's approval.

## Maintenance

Once set up, the pipeline is fully automated:
- Peter creates a Trove release
- GitHub Action builds and publishes
- jsdelivr CDN updates automatically
- Users get new version by updating script tag
