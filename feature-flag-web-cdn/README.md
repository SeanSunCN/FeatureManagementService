# Feature Flag Web CDN — Static Rule Distribution Acceleration Layer

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                      Admin / Eval Service                        │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  RuleCompiler (onRuleChanged trigger)                    │   │
│  │  1. DB persist (pseudo)                                  │   │
│  │  2. Filter: safe_for_client == true  (DATA PRUNING)      │   │
│  │  3. Atomic write: rules.<ver>.json + manifest.json       │   │
│  └──────────────────┬───────────────────────────────────────┘   │
└─────────────────────┼───────────────────────────────────────────┘
                      │ writes to
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│  CDN Root (/cdn_root)                                          │
│  ┌──────────────┐  ┌──────────────────────┐                    │
│  │ manifest.json│  │ rules.1002.json      │  ← versioned      │
│  │ (NO CACHE)   │  │ (CACHE 1 YEAR)       │                    │
│  └──────┬───────┘  └──────────┬───────────┘                    │
└─────────┼─────────────────────┼────────────────────────────────┘
          │                     │ served by Nginx
          ▼                     ▼
┌─────────────────────────────────────────────────────────────────┐
│  Nginx Reverse Proxy / Pseudo-CDN  (port 8080)                 │
│  - /manifest.json   → Cache-Control: no-store                  │
│  - /rules.*.json    → Cache-Control: public, immutable, 1 year │
│  - /index.html      → Demo page                                │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│  Browser (Web SDK)                                              │
│  1. GET /manifest.json?t=12345   (bypasses ALL caches)         │
│  2. Parse latest_file → "rules.1002.json"                      │
│  3. GET /rules.1002.json          (HITS DISK CACHE on 2nd load)│
│  4. isEnabled(flagKey, ctx)       (0ms, pure in-memory)        │
└─────────────────────────────────────────────────────────────────┘
```

## Key Design Decisions

### 1. Data Pruning (Security)
Only flags explicitly marked as `safe_for_client: true` survive compilation.
Server-only flags (risk control, payment decisions) are **physically excluded**
from the client JSON — they never leave the backend.

### 2. Cache Busting Strategy
- **Manifest**: always fetched with `?t=Date.now()` + `Cache-Control: no-store`
- **Rules payload**: versioned URL (`rules.1002.json`) + 1-year immutable caching
- Result: second page load hits **Disk Cache** for the large rules file (0 network bytes)

### 3. Atomic Publishing
RuleCompiler uses OS-level file locking + atomic rename to prevent partial writes
when concurrent admin actions occur.

### 4. BFF Hybrid Evaluation
- Heavy SDK (server-side) evaluates ALL flags including `server_only` via SSE
- Frontend handles only `safe_for_client` flags locally (0ms)
- Sensitive decisions (payment, risk) go through BFF API

## Quick Start

### Prerequisites
- Docker + Docker Compose
- Java 21 (for AdminRuleService, optional for demo)

### Step 1: Start pseudo-CDN
```bash
cd feature-flag-web-cdn
docker compose up -d
```

Nginx is now serving on http://localhost:8080

### Step 2: Open the demo page
Open http://localhost:8080/index.html in your browser.

### Step 3: Verify cache behaviour
1. **First load**: Diagnostics show "NETWORK FETCH" for rules
2. **Refresh** (F5): Diagnostics show "DISK CACHE" — rules file served from browser cache
3. **Check DevTools** → Network tab: `rules.1.json` should show "(disk cache)"

### Step 4: Publish new rules (simulate admin action)
```bash
# Generate a new rules version with an updated flag
python -c "
import json, time

# Read current manifest
with open('cdn_root/manifest.json') as f:
    manifest = json.load(f)

new_ver = manifest['version'] + 1
new_file = f'cdn_root/rules.{new_ver}.json'

# Build new rules with new-ui-portal = false
rules = {
  'version': new_ver,
  'flags': {
    'new-ui-portal': {
      'flagKey': 'new-ui-portal',
      'enabled': False,
      'defaultServeValue': False,
      'rules': []
    },
    'dark-mode-v2': {
      'flagKey': 'dark-mode-v2',
      'enabled': True,
      'defaultServeValue': True,
      'rules': []
    }
  }
}

with open(new_file, 'w') as f:
    json.dump(rules, f, indent=2)

# Update manifest
manifest['version'] = new_ver
manifest['latest_file'] = f'rules.{new_ver}.json'
manifest['generated_at'] = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())

with open('cdn_root/manifest.json', 'w') as f:
    json.dump(manifest, f, indent=2)

print(f'Published rules.{new_ver}.json — manifest updated')
"
```

Refresh the demo page → SDK fetches new manifest (cache busted), then loads new rules.

## Project Structure

```
feature-flag-web-cdn/
├── docker-compose.yml        # Nginx + optional admin container
├── nginx.conf                # CDN caching rules
├── README.md                 # This file
├── cdn_root/
│   ├── index.html            # Demo page (Module 4)
│   ├── manifest.json         # Dynamic manifest (Module 2 output)
│   ├── rules.1.json          # Versioned rules payload (Module 2 output)
│   └── feature-flag-web-sdk.js  # Web SDK (copied from web-sdk/)
├── web-sdk/
│   ├── feature-flag-web-sdk.js  # Web SDK source (Module 3)
│   └── index.html            # Demo page source
```

## Java Module: RuleCompiler

Located in `flag-eval-service/src/main/java/com/flag/eval/rule/`:

| Class | Role |
|-------|------|
| `RuleCompiler` | Component with `onRuleChanged()` trigger, filtering, atomic publish |
| `ClientFlagMetadata` | DTO for client-safe flags (safe_for_client filter output) |

Configuration:
```yaml
# application.yml
cdn:
  root: /cdn_root   # default; override via CDN_ROOT env var
```

## Verification Checklist

- [ ] `docker compose up` starts Nginx on port 8080
- [ ] First load: manifest fetched with `?t=` cache buster
- [ ] First load: rules fetched from network
- [ ] Second load: rules served from Disk Cache (transferSize === 0)
- [ ] Click "Check new-ui-portal" toggles theme between old/new UI
- [ ] `new-ui-portal=false` after publishing rules.2.json → old UI shown
- [ ] Admin publishes new version → manifest.json updated atomically
