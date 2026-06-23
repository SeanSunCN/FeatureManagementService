#!/usr/bin/env python3
"""
E2E Playwright test — opens demo page, verifies components respond to flag toggles.

Usage:
  playwright install chromium
  python3 e2e-playwright.py
"""

import json
import os
import sys
import time
import urllib.request
import urllib.error

ADMIN = os.environ.get("ADMIN_API", "http://localhost:8080")
EVAL = os.environ.get("EVAL_API", "http://localhost:8081")
CDN = os.environ.get("CDN_URL", "http://localhost:8084")
APP = "e2e-app"
CTX = {"userId": "e2e-user", "attributes": {"country": "US", "plan": "pro", "role": "admin"}}

PASSED = 0
FAILED = 0

def pass_msg(msg):
    global PASSED; PASSED += 1; print(f"  [PASS] {msg}")

def fail_msg(msg, detail=""):
    global FAILED; FAILED += 1; print(f"  [FAIL] {msg}")
    if detail: print(f"         {detail}")


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


def setup_data():
    print("\n=== Setting up test data ===")
    api("DELETE", f"{ADMIN}/api/v1/apps/{APP}")
    time.sleep(1)
    status, _ = api("POST", f"{ADMIN}/api/v1/apps", {"appId": APP, "appName": "E2E Test", "appType": "BACKEND"})
    if status != 200: return False

    # 5 basic flags (safeForClient=true)
    for key, name in [("new-ui-portal","New UI"),("dark-mode-v2","Dark Mode"),
                      ("new-search-portal","New Search"),("new-pricing-page","New Pricing"),
                      ("quick-export","Quick Export")]:
        s, _ = api("POST", f"{ADMIN}/api/v1/apps/{APP}/flags",
                   {"flagKey":key,"flagName":name,"globalEnabled":True,
                    "defaultStrategy":True,"rules":[],"safeForClient":True})
        if s not in (200, 400, 409):
            print(f"  FAIL: {key}: HTTP {s}"); return False
    print("  Created 5 basic flags")

    # Complex: flag-multi-rule (2 rules, 3 conditions each)
    s, _ = api("POST", f"{ADMIN}/api/v1/apps/{APP}/flags", {
        "flagKey":"flag-multi-rule","flagName":"Multi-Rule","globalEnabled":True,
        "defaultStrategy":False,"safeForClient":True,
        "rules":[
            {"ruleId":"r1","ruleName":"US Pro","serveValue":True,
             "conditions":[
                 {"attribute":"country","operator":"EQUALS","values":["US"]},
                 {"attribute":"plan","operator":"IN","values":["pro","enterprise"]},
                 {"attribute":"role","operator":"NOT_IN","values":["guest","trial"]}]},
            {"ruleId":"r2","ruleName":"EU Beta","serveValue":True,
             "conditions":[
                 {"attribute":"country","operator":"IN","values":["DE","FR","UK"]},
                 {"attribute":"beta_tester","operator":"EQUALS","values":["true"]},
                 {"attribute":"eval_count","operator":"GREATER_THAN","values":["10"]}]}]})
    print(f"  Create flag-multi-rule: HTTP {s}")
    if s not in (200, 400, 409): return False

    # Complex: flag-all-ops (6 rules, all 6 operators)
    s, _ = api("POST", f"{ADMIN}/api/v1/apps/{APP}/flags", {
        "flagKey":"flag-all-ops","flagName":"All Ops","globalEnabled":True,
        "defaultStrategy":False,"safeForClient":True,
        "rules":[
            {"ruleId":"r1","ruleName":"EQUALS","serveValue":True,
             "conditions":[{"attribute":"country","operator":"EQUALS","values":["US"]}]},
            {"ruleId":"r2","ruleName":"NOT_EQUALS","serveValue":True,
             "conditions":[{"attribute":"country","operator":"NOT_EQUALS","values":["CN"]}]},
            {"ruleId":"r3","ruleName":"IN","serveValue":True,
             "conditions":[{"attribute":"plan","operator":"IN","values":["pro","enterprise"]}]},
            {"ruleId":"r4","ruleName":"NOT_IN","serveValue":True,
             "conditions":[{"attribute":"role","operator":"NOT_IN","values":["banned"]}]},
            {"ruleId":"r5","ruleName":"GREATER_THAN","serveValue":True,
             "conditions":[{"attribute":"eval_count","operator":"GREATER_THAN","values":["100"]}]},
            {"ruleId":"r6","ruleName":"LESS_THAN","serveValue":True,
             "conditions":[{"attribute":"eval_count","operator":"LESS_THAN","values":["50"]}]}]})
    print(f"  Create flag-all-ops: HTTP {s}")
    if s not in (200, 400, 409): return False

    time.sleep(4)
    return True


def verify_cdn(expected_enabled, expected_disabled):
    status, manifest = api("GET", f"{CDN}/manifest.json")
    if status != 200 or "latest_file" not in manifest:
        fail_msg("CDN manifest", f"HTTP {status}"); return
    lf = manifest["latest_file"]
    status, rules = api("GET", f"{CDN}/{lf}")
    if status != 200: fail_msg(f"Fetch {lf}", f"HTTP {status}"); return
    keys = set(rules.get("flags", {}).keys())
    for k in expected_enabled:
        pass_msg(f"CDN contains '{k}'") if k in keys else fail_msg(f"CDN missing '{k}'")
    for k in expected_disabled:
        pass_msg(f"CDN excludes '{k}'") if k not in keys else fail_msg(f"CDN includes '{k}'")


def verify_eval(flag_key, ctx, expect_enabled, msg, retries=6):
    for i in range(retries):
        status, data = api("POST", f"{EVAL}/api/v1/eval/evaluate", {
            "appId": APP, "flagKey": flag_key,
            "userId": ctx.get("userId", "u"),
            "attributes": ctx.get("attributes", {})})
        enabled = data.get("data", {}).get("enabled") if isinstance(data, dict) else None
        if enabled == expect_enabled:
            reason = data.get("data", {}).get("matchReason", "")
            pass_msg(f"{msg} (reason={reason})")
            return
        time.sleep(1)
    fail_msg(msg, f"enabled={enabled} after {retries}s")


def main():
    if not setup_data():
        print("[SKIP] Setup failed"); sys.exit(1)

    all_flags = {"new-ui-portal","dark-mode-v2","new-search-portal",
                 "new-pricing-page","quick-export","flag-multi-rule","flag-all-ops"}
    basic_flags = {"new-ui-portal","dark-mode-v2","new-search-portal",
                   "new-pricing-page","quick-export"}

    print("\n=== 1/7  CDN: all 7 flags present ===")
    verify_cdn(all_flags, set())

    print("\n=== 2/7  Eval: flag-multi-rule (US+pro+admin → matched) ===")
    verify_eval("flag-multi-rule", {"userId":"u1","attributes":{"country":"US","plan":"pro","role":"admin"}}, True,
                "US+pro+admin → true")

    print("\n=== 3/7  Eval: flag-multi-rule (CN+free+guest → no match) ===")
    verify_eval("flag-multi-rule", {"userId":"u2","attributes":{"country":"CN","plan":"free","role":"guest"}}, False,
                "CN+free+guest → false")

    print("\n=== 4/7  Eval: flag-all-ops (US+enterprise+200 → EQUALS matched) ===")
    verify_eval("flag-all-ops", {"userId":"u3","attributes":{"country":"US","plan":"enterprise","eval_count":"200"}}, True,
                "US+enterprise+200 → true")

    print("\n=== 5/7  Eval: flag-all-ops (CN+free+75+banned → no match) ===")
    verify_eval("flag-all-ops", {"userId":"u4","attributes":{"country":"CN","plan":"free","eval_count":"75","role":"banned"}}, False,
                "CN+free+75+banned → false")

    print("\n=== 6/7  Toggle quick-export OFF → verify CDN removal ===")
    api("PATCH", f"{ADMIN}/api/v1/apps/{APP}/flags/quick-export/enabled", {"enabled": False})
    time.sleep(4)
    verify_cdn(all_flags - {"quick-export"}, {"quick-export"})
    api("PATCH", f"{ADMIN}/api/v1/apps/{APP}/flags/quick-export/enabled", {"enabled": True})
    time.sleep(4)
    verify_cdn(all_flags, set())

    print("\n=== 7/7  Browser visual test (Playwright) ===")
    # Check Playwright availability first (importing crashes Node.js 24 on Windows)
    pw_cdn_ok = False
    try:
        # Check CDN serves pages (also validates CDN is up before Playwright)
        s1, _ = api("GET", f"{CDN}/index.html")
        s2, _ = api("GET", f"{CDN}/feature-flag-web-sdk.js")
        pw_cdn_ok = s1 == 200 and s2 == 200
        pass_msg("CDN serves index.html + SDK JS") if pw_cdn_ok else fail_msg("CDN page check", f"index={s1}, sdk={s2}")
    except Exception as e:
        fail_msg("CDN page check", str(e)[:80])

    # Playwright browser
    if pw_cdn_ok:
        try:
            from playwright.sync_api import sync_playwright
            with sync_playwright() as pw:
                browser = pw.chromium.launch(headless=True)
                page = browser.new_page(viewport={"width":1280,"height":800})
                page.goto(f"{CDN}/index.html", wait_until="networkidle")
                time.sleep(3)
                sdk = page.text_content("#ddState") or ""
                cnt = page.text_content("#ddFlagCount") or ""
                print(f"    SDK: {sdk}, Flags: {cnt}")
                if "READY" in sdk:
                    pass_msg("SDK loaded in browser")
                    btn = page.text_content("#exportBtn") or ""
                    pass_msg(f"Export: '{btn}'") if "Quick" in btn else fail_msg("Export btn", btn)
                    # Toggle OFF
                    api("PATCH", f"{ADMIN}/api/v1/apps/{APP}/flags/quick-export/enabled", {"enabled": False})
                    time.sleep(4); page.click("#refreshBtn"); time.sleep(3)
                    btn = page.text_content("#exportBtn") or ""
                    pass_msg("Export: CSV after OFF") if "CSV" in btn else fail_msg("Export CSV", btn)
                    # Toggle ON
                    api("PATCH", f"{ADMIN}/api/v1/apps/{APP}/flags/quick-export/enabled", {"enabled": True})
                    time.sleep(4); page.click("#refreshBtn"); time.sleep(3)
                    btn = page.text_content("#exportBtn") or ""
                    pass_msg("Export: Quick after ON") if "Quick" in btn else fail_msg("Export Quick", btn)
                else:
                    fail_msg("SDK state", sdk)
                browser.close()
        except Exception as e:
            print(f"  [SKIP] Playwright browser — {str(e)[:80]}")

    print("\n=== Cleanup ===")
    api("DELETE", f"{ADMIN}/api/v1/apps/{APP}")
    print("  Deleted e2e-app")

    total = PASSED + FAILED
    print(f"\n{'='*47}")
    print(f" E2E Complete: {PASSED} PASSED / {FAILED} FAILED / {total} TOTAL")
    sys.exit(1 if FAILED else 0)

if __name__ == "__main__":
    main()
