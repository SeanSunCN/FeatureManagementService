#!/usr/bin/env python3
"""
E2E Playwright test — opens demo page, verifies components respond to flag toggles.

Usage:
  playwright install chromium
  python3 e2e-playwright.py
"""

import json
import os
import subprocess
import sys
import time
from pathlib import Path

# --- Config ---
ADMIN = os.environ.get("ADMIN_API", "http://localhost:8080")
CDN = os.environ.get("CDN_URL", "http://localhost:8084")

PASSED = 0
FAILED = 0

def pass_msg(msg):
    global PASSED; PASSED += 1; print(f"  [PASS] {msg}")

def fail_msg(msg, detail=""):
    global FAILED; FAILED += 1; print(f"  [FAIL] {msg}")
    if detail: print(f"         {detail}")


# ============================================================
#  API helpers
# ============================================================

import urllib.request
def api(method, url, body=None):
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception as e:
        return 0, {}


# ============================================================
#  Playwright helpers
# ============================================================

def ensure_playwright():
    try:
        from playwright.sync_api import sync_playwright
        return sync_playwright
    except ImportError:
        print("[SETUP] Installing Playwright...")
        subprocess.check_call([sys.executable, "-m", "pip", "install", "playwright"])
        subprocess.check_call([sys.executable, "-m", "playwright", "install", "chromium"])
        from playwright.sync_api import sync_playwright
        return sync_playwright


def setup_demo_data():
    """Create test app + 5 demo flags via admin API."""
    print("\n=== Setting up demo data ===")

    # Create app
    status, _ = api("POST", f"{ADMIN}/api/v1/apps", {
        "appId": "e2e-app", "appName": "E2E Test App", "appType": "BACKEND"})
    if status != 200:
        # Already exists, try to clean and recreate
        api("DELETE", f"{ADMIN}/api/v1/apps/e2e-app")
        time.sleep(1)
        status, _ = api("POST", f"{ADMIN}/api/v1/apps", {
            "appId": "e2e-app", "appName": "E2E Test App", "appType": "BACKEND"})
    print(f"  Create app: HTTP {status}")
    if status != 200:
        return False

    # 5 demo flags — all ON initially
    flags_data = [
        ("new-ui-portal", "New UI Portal", True),
        ("dark-mode-v2", "Dark Mode v2", True),
        ("new-search-portal", "New Search Portal", True),
        ("new-pricing-page", "New Pricing Page", True),
        ("quick-export", "Quick Export", True),
    ]
    for key, name, enabled in flags_data:
        status, _ = api("POST", f"{ADMIN}/api/v1/apps/e2e-app/flags", {
            "flagKey": key, "flagName": name,
            "globalEnabled": enabled, "defaultStrategy": True,
            "rules": [], "safeForClient": True})
        if status != 200:
            print(f"  FAIL: Create {key}: HTTP {status}")
            return False
    print(f"  Created {len(flags_data)} demo flags")
    time.sleep(3)
    return True


def verify_cdn_rules(expected_enabled: set, expected_disabled: set):
    """Check CDN rules file contains expected keys and not others."""
    status, manifest = api("GET", f"{CDN}/manifest.json")
    if status != 200 or "latest_file" not in manifest:
        fail_msg("Fetch CDN manifest")
        return

    lf = manifest["latest_file"]
    status, rules = api("GET", f"{CDN}/{lf}")
    if status != 200:
        fail_msg(f"Fetch {lf}", f"HTTP {status}")
        return

    keys_in_rules = set(rules.get("flags", {}).keys())
    for k in expected_enabled:
        if k in keys_in_rules:
            pass_msg(f"CDN contains '{k}' (enabled)")
        else:
            fail_msg(f"CDN missing enabled flag '{k}'")
    for k in expected_disabled:
        if k not in keys_in_rules:
            pass_msg(f"CDN excludes '{k}' (disabled)")
        else:
            fail_msg(f"CDN includes disabled flag '{k}'")


# ============================================================
#  Main test flow
# ============================================================

def main():
    pw = ensure_playwright()

    # Setup
    if not setup_demo_data():
        print("[SKIP] Demo data setup failed — aborting")
        sys.exit(1)

    print("\n=== 1/5  Initial page load — all flags ON ===")
    verify_cdn_rules(
        {"new-ui-portal", "dark-mode-v2", "new-search-portal",
         "new-pricing-page", "quick-export"},
        set()
    )

    print("\n=== 2/5  Toggle quick-export OFF → verify CDN removal ===")
    status, _ = api("PATCH", f"{ADMIN}/api/v1/apps/e2e-app/flags/quick-export/enabled",
                    {"enabled": False})
    pass_msg(f"Toggle quick-export off (HTTP {status})") if status == 200 else fail_msg("Toggle quick-export", f"HTTP {status}")
    time.sleep(3)
    verify_cdn_rules(
        {"new-ui-portal", "dark-mode-v2", "new-search-portal", "new-pricing-page"},
        {"quick-export"}
    )

    print("\n=== 3/5  Toggle new-search-portal OFF → verify removal ===")
    status, _ = api("PATCH", f"{ADMIN}/api/v1/apps/e2e-app/flags/new-search-portal/enabled",
                    {"enabled": False})
    pass_msg(f"Toggle new-search-portal off (HTTP {status})") if status == 200 else fail_msg("Toggle new-search-portal", f"HTTP {status}")
    time.sleep(3)
    verify_cdn_rules(
        {"new-ui-portal", "dark-mode-v2", "new-pricing-page"},
        {"quick-export", "new-search-portal"}
    )

    print("\n=== 4/5  Toggle both back ON → verify reappearance ===")
    for key in ["quick-export", "new-search-portal"]:
        status, _ = api("PATCH", f"{ADMIN}/api/v1/apps/e2e-app/flags/{key}/enabled",
                        {"enabled": True})
        pass_msg(f"Toggle {key} on (HTTP {status})") if status == 200 else fail_msg(f"Toggle {key}", f"HTTP {status}")
    time.sleep(3)
    verify_cdn_rules(
        {"new-ui-portal", "dark-mode-v2", "new-search-portal",
         "new-pricing-page", "quick-export"},
        set()
    )

    print("\n=== 5/5  Browser visual test (Playwright) ===")
    demo_page_url = f"{CDN}/index.html"

    try:
        with pw() as p:
            browser = p.chromium.launch(headless=True)
            page = browser.new_page(viewport={"width": 1280, "height": 800})

            # Load demo page
            page.goto(demo_page_url, wait_until="networkidle")
            time.sleep(3)  # Wait for SDK init

            # Check initial state — all ON
            print("  Page loaded, checking SDK state...")
            sdk_state = page.text_content("#ddState") or ""
            flag_count = page.text_content("#ddFlagCount") or ""
            cache_hit = page.text_content("#ddCache") or ""
            print(f"    SDK State: {sdk_state}")
            print(f"    Flag Count: {flag_count}")
            print(f"    Cache: {cache_hit}")

            if "READY" in sdk_state and "5" in flag_count:
                pass_msg("SDK loaded with 5 flags")
            else:
                fail_msg("SDK state", f"state={sdk_state}, count={flag_count}")

            # Check search is enabled (initial: ON)
            search_btn = page.text_content("#searchBtn") or ""
            search_disabled = page.get_attribute("#searchBtn", "disabled")
            print(f"    Search button: '{search_btn}' disabled={search_disabled}")
            if "Smart Search" in search_btn:
                pass_msg("Search shows 'Smart Search' (enhanced UI)")
            else:
                fail_msg("Search button text", f"got '{search_btn}'")

            # Check export is Quick (initial: ON)
            export_btn = page.text_content("#exportBtn") or ""
            print(f"    Export button: '{export_btn}'")
            if "Quick Export" in export_btn:
                pass_msg("Export shows 'Quick Export' (enhanced)")
            else:
                fail_msg("Export button text", f"got '{export_btn}'")

            # --- Toggle quick-export OFF and re-init ---
            print("\n  Toggling quick-export OFF via API...")
            api("PATCH", f"{ADMIN}/api/v1/apps/e2e-app/flags/quick-export/enabled",
                {"enabled": False})
            time.sleep(4)  # Wait for CDN update

            # Re-init SDK on the page
            page.click("#refreshBtn")
            time.sleep(3)

            # Verify export button changed
            export_btn = page.text_content("#exportBtn") or ""
            print(f"    After toggle — Export button: '{export_btn}'")
            if "Export CSV" in export_btn and "Quick" not in export_btn:
                pass_msg("Export changed to 'Export CSV' (standard) after toggle")
            else:
                fail_msg("Export after toggle", f"got '{export_btn}'")

            # --- Toggle quick-export back ON and re-init ---
            print("\n  Toggling quick-export back ON...")
            api("PATCH", f"{ADMIN}/api/v1/apps/e2e-app/flags/quick-export/enabled",
                {"enabled": True})
            time.sleep(4)

            page.click("#refreshBtn")
            time.sleep(3)

            export_btn = page.text_content("#exportBtn") or ""
            print(f"    After re-enable — Export button: '{export_btn}'")
            if "Quick Export" in export_btn:
                pass_msg("Export returned to 'Quick Export' after re-enable")
            else:
                fail_msg("Export after re-enable", f"got '{export_btn}'")

            browser.close()

    except Exception as e:
        fail_msg("Playwright browser test", str(e))

    # Cleanup
    print("\n=== Cleanup ===")
    api("DELETE", f"{ADMIN}/api/v1/apps/e2e-app")
    print("  Deleted e2e-app")

    # Summary
    total = PASSED + FAILED
    print(f"\n{'=' * 47}")
    print(f" E2E Complete: {PASSED} PASSED / {FAILED} FAILED / {total} TOTAL")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
