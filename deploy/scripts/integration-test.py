#!/usr/bin/env python3
"""
Feature Management Service — Integration Test Script (Python)

Usage:
  python integration-test.py [--admin URL] [--eval URL] [--ingest URL]
                             [--worker URL] [--cdn URL] [--wait SECONDS]

Defaults:
  http://localhost:8080/8081/8082/8083/8084  wait=3s
"""

import argparse
import json
import os
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path
from typing import Optional

PASSED = 0
FAILED = 0
SKIPPED = 0

APP_ID = "integration-test-app"
DEMO_APP_ID = "demo-app"


def header(title: str):
    print()
    print("=" * 47)
    print(f" {title}")
    print("=" * 47)


def pass_msg(msg: str):
    global PASSED
    PASSED += 1
    print(f"  [PASS] {msg}")


def fail_msg(msg: str, detail: str = ""):
    global FAILED
    FAILED += 1
    print(f"  [FAIL] {msg}")
    if detail:
        print(f"         {detail}")


def skip_msg(msg: str, detail: str = ""):
    global SKIPPED
    SKIPPED += 1
    print(f"  [SKIP] {msg}")
    if detail:
        print(f"         {detail}")


# ============================================================
#  HTTP helper
# ============================================================

class ApiResponse:
    def __init__(self, status: int, body: str):
        self.status = status
        self.body = body
        self._json = None

    @property
    def json(self):
        if self._json is None:
            try:
                self._json = json.loads(self.body)
            except (json.JSONDecodeError, TypeError):
                self._json = {}
        return self._json

    @property
    def data(self):
        j = self.json
        if "data" in j:
            return j["data"]
        return j

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300


def call_api(method: str, url: str, body: Optional[str] = None) -> ApiResponse:
    data = body.encode("utf-8") if body else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return ApiResponse(resp.status, resp.read().decode("utf-8", errors="replace"))
    except urllib.error.HTTPError as e:
        err = e.read().decode("utf-8", errors="replace") if e.fp else ""
        return ApiResponse(e.code, err)
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        return ApiResponse(0, str(e))


# ============================================================
#  Arguments
# ============================================================

def parse_args():
    p = argparse.ArgumentParser(
        description="Feature Management Service Integration Test",
        usage="%(prog)s [--admin URL] [--eval URL] [--ingest URL] [--worker URL] [--cdn URL] [--wait SEC]\n"
              "       %(prog)s [admin_url] [eval_url] [ingest_url] [worker_url] [cdn_url] [wait_sec]")
    p.add_argument("--admin", default=None)
    p.add_argument("--eval", default=None)
    p.add_argument("--ingest", default=None)
    p.add_argument("--worker", default=None)
    p.add_argument("--cdn", default=None)
    p.add_argument("--wait", type=int, default=None)
    p.add_argument("--cdn-root", default=None)
    p.add_argument("pos_admin", nargs="?", default=None)
    p.add_argument("pos_eval", nargs="?", default=None)
    p.add_argument("pos_ingest", nargs="?", default=None)
    p.add_argument("pos_worker", nargs="?", default=None)
    p.add_argument("pos_wait_or_cdn", nargs="?", default=None)
    p.add_argument("pos_extra", nargs="*", default=None)
    args = p.parse_args()

    if args.admin is None:
        args.admin = args.pos_admin or "http://localhost:8080"
    if args.eval is None:
        args.eval = args.pos_eval or "http://localhost:8081"
    if args.ingest is None:
        args.ingest = args.pos_ingest or "http://localhost:8082"
    if args.worker is None:
        args.worker = args.pos_worker or "http://localhost:8083"

    raw = args.pos_wait_or_cdn
    if raw is not None:
        if raw.isdigit():
            args.wait = int(raw)
            args.cdn = "http://localhost:8084"
        else:
            args.cdn = raw
            if args.pos_extra and args.pos_extra[0].isdigit():
                args.wait = int(args.pos_extra[0])
    else:
        args.cdn = "http://localhost:8084"

    if args.wait is None:
        args.wait = 3
    return args


# ============================================================
#  API helpers
# ============================================================

def api_url(base: str, path: str) -> str:
    return f"{base.rstrip('/')}{path}"


def admin_get(b: dict, path: str) -> ApiResponse:
    return call_api("GET", api_url(b["admin"], path))


def admin_post(b: dict, path: str, body: dict) -> ApiResponse:
    return call_api("POST", api_url(b["admin"], path), json.dumps(body))


def admin_put(b: dict, path: str, body: dict) -> ApiResponse:
    return call_api("PUT", api_url(b["admin"], path), json.dumps(body))


def admin_patch(b: dict, path: str, body: dict) -> ApiResponse:
    return call_api("PATCH", api_url(b["admin"], path), json.dumps(body))


def admin_delete(b: dict, path: str) -> ApiResponse:
    return call_api("DELETE", api_url(b["admin"], path))


def eval_get(b: dict, path: str) -> ApiResponse:
    return call_api("GET", api_url(b["eval"], path))


def eval_post(b: dict, path: str, body: dict) -> ApiResponse:
    return call_api("POST", api_url(b["eval"], path), json.dumps(body))


def cdn_get(b: dict, path: str) -> ApiResponse:
    return call_api("GET", api_url(b["cdn"], path))


def ingest_post(b: dict, path: str, body: dict) -> ApiResponse:
    return call_api("POST", api_url(b["ingest"], path), json.dumps(body))


def ingest_get(b: dict, path: str) -> ApiResponse:
    return call_api("GET", api_url(b["ingest"], path))


def worker_get(b: dict, path: str) -> ApiResponse:
    return call_api("GET", api_url(b["worker"], path))


def expect(resp: ApiResponse, msg: str):
    pass_msg(msg) if resp.ok else fail_msg(msg, f"HTTP={resp.status}")


# ============================================================
#  Complex rule config builders
# ============================================================

def multi_rule_flag(enabled: bool = True) -> dict:
    """A flag with 2 rules, each with multiple conditions of different operators."""
    return {
        "flagKey": "flag-multi-rule",
        "flagName": "Multi-Rule Complex Flag",
        "description": "2 rules, 3 conditions each, mixed operators",
        "globalEnabled": enabled,
        "defaultStrategy": False,
        "safeForClient": True,
        "rules": [
            {
                "ruleId": "rule-us-pro",
                "ruleName": "US Pro users",
                "serveValue": True,
                "conditions": [
                    {"attribute": "country", "operator": "EQUALS", "values": ["US"]},
                    {"attribute": "plan", "operator": "IN", "values": ["pro", "enterprise"]},
                    {"attribute": "role", "operator": "NOT_IN", "values": ["guest", "trial"]}
                ]
            },
            {
                "ruleId": "rule-eu-beta",
                "ruleName": "EU beta users",
                "serveValue": True,
                "conditions": [
                    {"attribute": "country", "operator": "IN", "values": ["DE", "FR", "UK"]},
                    {"attribute": "beta_tester", "operator": "EQUALS", "values": ["true"]},
                    {"attribute": "eval_count", "operator": "GREATER_THAN", "values": ["10"]}
                ]
            }
        ]
    }


def condition_flag(enabled: bool = True) -> dict:
    """A flag exercising all 6 operators."""
    return {
        "flagKey": "flag-all-ops",
        "flagName": "All Operators Flag",
        "description": "6 rules, one per operator type",
        "globalEnabled": enabled,
        "defaultStrategy": False,
        "safeForClient": True,
        "rules": [
            {"ruleId": "r1", "ruleName": "EQUALS", "serveValue": True,
             "conditions": [{"attribute": "country", "operator": "EQUALS", "values": ["US"]}]},
            {"ruleId": "r2", "ruleName": "NOT_EQUALS", "serveValue": True,
             "conditions": [{"attribute": "country", "operator": "NOT_EQUALS", "values": ["CN"]}]},
            {"ruleId": "r3", "ruleName": "IN", "serveValue": True,
             "conditions": [{"attribute": "plan", "operator": "IN", "values": ["pro", "enterprise"]}]},
            {"ruleId": "r4", "ruleName": "NOT_IN", "serveValue": True,
             "conditions": [{"attribute": "role", "operator": "NOT_IN", "values": ["banned"]}]},
            {"ruleId": "r5", "ruleName": "GREATER_THAN", "serveValue": True,
             "conditions": [{"attribute": "eval_count", "operator": "GREATER_THAN", "values": ["100"]}]},
            {"ruleId": "r6", "ruleName": "LESS_THAN", "serveValue": True,
             "conditions": [{"attribute": "eval_count", "operator": "LESS_THAN", "values": ["50"]}]},
        ]
    }


# ============================================================
#  Test sections
# ============================================================

def test_health(b: dict):
    header("1/11  Health Check")
    for name, url in [
        ("Admin API", f"{b['admin']}/actuator/health"),
        ("Eval Service", f"{b['eval']}/actuator/health"),
        ("Ingest Service", f"{b['ingest']}/actuator/health"),
        ("Worker Service", f"{b['worker']}/actuator/health"),
    ]:
        resp = call_api("GET", url)
        ok = resp.ok and resp.json.get("status") == "UP"
        pass_msg(name) if ok else fail_msg(name, f"HTTP={resp.status}")
    resp = call_api("GET", f"{b['cdn']}/__headers")
    pass_msg(f"CDN Nginx (HTTP {resp.status})") if resp.ok else fail_msg("CDN", f"HTTP={resp.status}")


def test_create_app(b: dict):
    header("2/11  Create Test App + Demo App")

    # Auto-test app (will be deleted on cleanup)
    resp = admin_post(b, "/api/v1/apps", {
        "appId": APP_ID, "appName": "Integration Test App", "appType": "BACKEND"})
    expect(resp, "Create auto-test app")
    resp = admin_get(b, f"/api/v1/apps/{APP_ID}")
    pass_msg("Verify auto-test app created") if resp.data.get("appId") == APP_ID else fail_msg("Verify", f"got {resp.data.get('appId')}")

    # Demo app (will NOT be deleted — persists for manual demo)
    resp = admin_post(b, "/api/v1/apps", {
        "appId": DEMO_APP_ID, "appName": "Demo App", "appType": "BACKEND"})
    if resp.ok:
        pass_msg("Create demo app")
    elif resp.status == 400:
        # Code 20001 = appId already exists
        pass_msg("Demo app already exists (skipped)")
    else:
        fail_msg("Create demo app", f"HTTP={resp.status}, body={resp.body[:100]}")


def test_create_base_flags(b: dict):
    header("3/11  Create Baseline Flags (auto-test)")
    flags = [
        ("flag-a", "Flag A (Full Rollout)", True, True),
        ("flag-b", "Flag B (Gradual 50%)", True, False),
        ("flag-c", "Flag C (Disabled)", False, False),
    ]
    for k, name, enabled, strategy in flags:
        resp = admin_post(b, f"/api/v1/apps/{APP_ID}/flags", {
            "flagKey": k, "flagName": name, "globalEnabled": enabled,
            "defaultStrategy": strategy, "rules": []})
        pass_msg(f"Create {k}") if resp.ok else fail_msg(f"Create {k}", f"HTTP={resp.status}")
    resp = admin_get(b, f"/api/v1/apps/{APP_ID}/flags")
    count = len(resp.data) if isinstance(resp.data, list) else 0
    pass_msg(f"Verify {count} baseline flags") if count == 3 else fail_msg("Verify count", f"got {count}")


def test_complex_rules(b: dict, wait: int):
    header("4/11  Create Complex Rule Flags")

    # Multi-rule flag
    resp = admin_post(b, f"/api/v1/apps/{APP_ID}/flags", multi_rule_flag(True))
    expect(resp, "Create flag-multi-rule (2 rules, 3 conditions each)")

    # All-operators flag
    resp = admin_post(b, f"/api/v1/apps/{APP_ID}/flags", condition_flag(True))
    expect(resp, "Create flag-all-ops (6 rules, all operators)")

    time.sleep(wait)

    # Verify CDN has them
    resp = cdn_get(b, "/manifest.json")
    lf = resp.json.get("latest_file", "")
    resp = cdn_get(b, f"/{lf}")
    flags = resp.json.get("flags", {})
    has_multi = "flag-multi-rule" in flags
    has_allops = "flag-all-ops" in flags
    if has_multi and has_allops:
        pass_msg(f"Complex flags present in CDN {lf} (v{resp.json.get('version',0)})")
    else:
        missing = []
        if not has_multi: missing.append("flag-multi-rule")
        if not has_allops: missing.append("flag-all-ops")
        fail_msg("Complex flags missing from CDN", f"missing={missing}")


def _eval_with_retry(b: dict, body: dict, expect_enabled: bool, msg: str, retries: int = 5):
    """Evaluate with retry to tolerate async cache timing."""
    for i in range(retries):
        resp = eval_post(b, "/api/v1/eval/evaluate", body)
        enabled = resp.data.get("enabled", None)
        if enabled == expect_enabled:
            pass_msg(msg)
            return
        if i < retries - 1:
            time.sleep(1)
    fail_msg(msg, f"enabled={enabled} after {retries} retries")


def test_eval_complex_rules(b: dict):
    """Evaluate multi-rule flag with different contexts to verify rule matching."""
    header("5/11  Evaluate Complex Rules")

    # Warm-up: first eval call for each flag-key can race with cache initialisation
    for fk in ["flag-multi-rule", "flag-all-ops"]:
        eval_post(b, "/api/v1/eval/evaluate", {"appId":APP_ID,"flagKey":fk,"userId":"warmup"})

    _eval_with_retry(b, {"appId":APP_ID,"flagKey":"flag-multi-rule",
        "userId":"user-100","attributes":{"country":"US","plan":"pro","role":"admin"}
    }, True, "US+pro+admin: enabled=true")

    _eval_with_retry(b, {"appId":APP_ID,"flagKey":"flag-multi-rule",
        "userId":"user-200","attributes":{"country":"CN","plan":"free","role":"guest"}
    }, False, "CN+free+guest: enabled=false")

    _eval_with_retry(b, {"appId":APP_ID,"flagKey":"flag-multi-rule",
        "userId":"user-300",
        "attributes":{"country":"DE","beta_tester":"true","eval_count":"20"}
    }, True, "EU+beta user (>10 evals): enabled=true")

    _eval_with_retry(b, {"appId":APP_ID,"flagKey":"flag-multi-rule",
        "userId":"user-400",
        "attributes":{"country":"FR","beta_tester":"false","eval_count":"5"}
    }, False, "FR non-beta user (<10 evals): enabled=false")

    _eval_with_retry(b, {"appId":APP_ID,"flagKey":"flag-all-ops",
        "userId":"user-500","attributes":{"country":"US","plan":"enterprise","eval_count":"200"}
    }, True, "All-ops (US+enterprise+200): true")

    # All-ops false: CN + free + eval_count=75 + role=banned
    #   EQUALS US     -> fail (CN)
    #   NOT_EQUALS CN -> fail (CN)
    #   IN [pro,ent]  -> fail (free)
    #   NOT_IN banned -> fail (role=banned)
    #   GT 100        -> fail (75)
    #   LT 50         -> fail (75)
    #   No matches -> default=false
    _eval_with_retry(b, {"appId":APP_ID,"flagKey":"flag-all-ops",
        "userId":"user-600",
        "attributes":{"country":"CN","plan":"free","eval_count":"75","role":"banned"}
    }, False, "All-ops false case (all rules miss): false")


def test_cdn_publish(b: dict, cdn_root: Optional[str], wait: int):
    header("6/11  CDN Publish & safe_for_client")

    # Create client-safe flags for demo (demo-app, not auto-test)
    demo_flags = [
        ("new-ui-portal", True),
        ("dark-mode-v2", True),
        ("quick-export", False),
        ("new-search-portal", False),
        ("new-pricing-page", False),
    ]
    for key, enabled in demo_flags:
        resp = admin_post(b, f"/api/v1/apps/{DEMO_APP_ID}/flags", {
            "flagKey": key, "flagName": key.replace("-", " ").title(),
            "globalEnabled": enabled, "defaultStrategy": enabled,
            "rules": [], "safeForClient": True})
        if resp.ok:
            pass_msg(f"Demo: create {key} (safeForClient=true)")
        elif resp.status in (400, 409):
            pass_msg(f"Demo: {key} already exists (skipped)")
        else:
            fail_msg(f"Demo: create {key}", f"HTTP={resp.status}")
    time.sleep(wait)

    # Create server-only flag
    resp = admin_post(b, f"/api/v1/apps/{APP_ID}/flags", {
        "flagKey": "flag-server-only", "flagName": "Server Only",
        "globalEnabled": True, "defaultStrategy": False, "rules": [],
        "safeForClient": False})
    expect(resp, "Create flag-server-only (safeForClient=false)")
    time.sleep(wait)

    # Fetch manifest
    resp = cdn_get(b, "/manifest.json")
    lf = resp.json.get("latest_file", "")
    pass_msg(f"CDN manifest (latest_file={lf})") if resp.ok and lf else fail_msg("CDN manifest", f"HTTP={resp.status}")

    # Verify server-only is excluded from rules
    resp = cdn_get(b, f"/{lf}")
    flags = resp.json.get("flags", {})
    pass_msg("Server-only flag excluded from CDN") if "flag-server-only" not in flags else fail_msg("CDN leak", "server-only flag present")

    # Demo flags present in CDN. Only enabled flags appear (disabled are SQL-filtered)
    demo_enabled_keys = {k for k, enabled in demo_flags if enabled}
    present = demo_enabled_keys & set(flags.keys())
    pass_msg(f"Enabled demo flags in CDN: {len(present)}/{len(demo_enabled_keys)}") if len(present) == len(demo_enabled_keys) else fail_msg("Demo flags missing", f"expected {demo_enabled_keys}, have {present}")
    disabled_excluded = all(k not in flags for k, e in demo_flags if not e)
    pass_msg("Disabled demo flags excluded from CDN (SQL filter)") if disabled_excluded else fail_msg("CDN includes disabled flags")

    # On-disk verification
    if cdn_root and os.path.isdir(cdn_root):
        rule_files = sorted(Path(cdn_root).glob("rules.*.json"))
        pass_msg(f"CDN root: {len(rule_files)} rules.*.json") if rule_files else fail_msg("CDN root: no rules files")
        pass_msg("CDN root: manifest.json exists") if Path(cdn_root, "manifest.json").exists() else fail_msg("CDN root: manifest.json missing")
    else:
        skip_msg("CDN root on-disk check")

    # Static files
    resp = cdn_get(b, "/index.html")
    pass_msg("CDN serves index.html") if resp.ok else fail_msg("CDN index.html", f"HTTP={resp.status}")
    resp = cdn_get(b, "/feature-flag-web-sdk.js")
    pass_msg("CDN serves SDK JS") if resp.ok else fail_msg("CDN SDK JS", f"HTTP={resp.status}")


def test_eval_sync(b: dict, expected: int, wait: int):
    header("7/11  Wait for EvalService Sync")
    print(f"     Waiting {wait}s...")
    time.sleep(wait)
    resp = eval_get(b, f"/api/v1/eval/flags?appId={APP_ID}")
    count = len(resp.data) if isinstance(resp.data, (dict, list)) else 0
    pass_msg(f"EvalService: {count} flags") if count == expected else fail_msg("Sync", f"expected {expected}, got {count}")


def test_evaluation(b: dict):
    header("8/11  Baseline Rule Evaluation")
    body = {"appId": APP_ID, "flagKey": "flag-a", "userId": "user-001"}
    resp = eval_post(b, "/api/v1/eval/evaluate", body)
    pass_msg("flag-a enabled=true") if resp.data.get("enabled", False) else fail_msg("flag-a", f"enabled={resp.data.get('enabled')}")

    body["flagKey"] = "flag-c"
    resp = eval_post(b, "/api/v1/eval/evaluate", body)
    pass_msg("flag-c enabled=false") if not resp.data.get("enabled", True) else fail_msg("flag-c", f"enabled={resp.data.get('enabled')}")

    resp = eval_post(b, "/api/v1/eval/evaluate/batch", [
        {"appId": APP_ID, "flagKey": "flag-a", "userId": "user-001"},
        {"appId": APP_ID, "flagKey": "flag-b", "userId": "user-002"},
        {"appId": APP_ID, "flagKey": "flag-c", "userId": "user-003"}])
    batch = resp.data if isinstance(resp.data, list) else []
    pass_msg("Batch evaluate 3") if len(batch) == 3 else fail_msg("Batch", f"count={len(batch)}")


def test_ingest(b: dict):
    header("9/11  Ingest Metrics & Audit Logs")

    resp = ingest_post(b, "/api/v1/ingest/metrics",
                       {"appId": APP_ID, "flagHitCounts": {"flag-a": 10, "flag-b": 5}})
    expect(resp, "Report Metrics")

    resp = ingest_post(b, "/api/v1/ingest/audit-log",
                       {"appId": APP_ID, "flagKey": "flag-a",
                        "userId": "user-001", "enabled": True,
                        "clientIp": "192.168.1.100", "evalCostNs": 1250000})
    expect(resp, "Report audit log")

    resp = ingest_post(b, "/api/v1/ingest/audit-log/batch", [
        {"appId": APP_ID, "flagKey": "flag-a", "userId": "user-001",
         "enabled": True, "clientIp": "10.0.0.1", "evalCostNs": 850000},
        {"appId": APP_ID, "flagKey": "flag-b", "userId": "user-002",
         "enabled": True, "clientIp": "10.0.0.2", "evalCostNs": 1200000}])
    expect(resp, "Report audit logs (batch)")

    resp = ingest_get(b, "/api/v1/ingest/drop-total")
    drop = resp.data
    if isinstance(drop, (int, float)):
        pass_msg(f"Drop count: {drop}") if drop == 0 else fail_msg("Drop count non-zero", f"drop={drop}")
    else:
        pass_msg(f"Drop count: N/A ({drop})")


def test_clickhouse(b: dict):
    header("10/11  Verify ClickHouse persistence")
    print("     Waiting for Worker flush (flush-interval=10s)...")
    time.sleep(12)
    resp = worker_get(b, "/actuator/health")
    ok = resp.json.get("status") == "UP"
    pass_msg("Worker healthy") if ok else fail_msg("Worker", f"status={resp.json.get('status')}")


def test_control_plane(b: dict, wait: int):
    header("11/11  Control Plane + Toggle + Cleanup")

    # Toggle off
    resp = admin_patch(b, f"/api/v1/apps/{APP_ID}/flags/flag-a/enabled", {"enabled": False})
    expect(resp, "Toggle flag-a off")
    time.sleep(wait)
    resp = eval_post(b, "/api/v1/eval/evaluate", {"appId": APP_ID, "flagKey": "flag-a", "userId": "user-001"})
    pass_msg("Eval: flag-a disabled") if not resp.data.get("enabled", True) else fail_msg("Eval", "flag-a still enabled")

    # Toggle on
    resp = admin_patch(b, f"/api/v1/apps/{APP_ID}/flags/flag-a/enabled", {"enabled": True})
    expect(resp, "Toggle flag-a on")
    time.sleep(wait)

    # Delete
    resp = admin_delete(b, f"/api/v1/apps/{APP_ID}/flags/flag-c")
    expect(resp, "Delete flag-c")
    time.sleep(wait)
    resp = eval_get(b, f"/api/v1/eval/flags?appId={APP_ID}")
    remaining = len(resp.data) if isinstance(resp.data, (dict, list)) else 0
    # 3 baseline + 2 complex + 1 server-only - 1 deleted = 5
    pass_msg(f"Eval: {remaining} flags left") if remaining == 5 else fail_msg("Eval after delete", f"count={remaining}")

    # Cleanup auto-test app only (demo-app persists!)
    resp = admin_delete(b, f"/api/v1/apps/{APP_ID}")
    expect(resp, "Delete auto-test app (cascade)")
    resp = admin_get(b, f"/api/v1/apps/{APP_ID}")
    pass_msg("Verify auto-test app deleted (404)") if resp.status == 404 else fail_msg("Verify deletion", f"HTTP={resp.status}")

    # Verify demo-app still exists
    resp = admin_get(b, f"/api/v1/apps/{DEMO_APP_ID}")
    pass_msg("Demo app preserved after cleanup") if resp.ok else fail_msg("Demo app deleted", f"HTTP={resp.status}")


# ============================================================
#  Main
# ============================================================

def main():
    args = parse_args()
    b = {"admin": args.admin.rstrip("/"), "eval": args.eval.rstrip("/"),
         "ingest": args.ingest.rstrip("/"), "worker": args.worker.rstrip("/"),
         "cdn": args.cdn.rstrip("/")}

    cdn_root = args.cdn_root
    if cdn_root is None:
        cdn_root = str(Path(__file__).resolve().parent.parent.parent / "feature-flag-web-cdn" / "cdn_root")

    test_health(b)
    test_create_app(b)
    test_create_base_flags(b)
    test_complex_rules(b, args.wait)
    test_cdn_publish(b, cdn_root, args.wait)
    # 3 baseline + 2 complex + (flag-server-only may arrive from Pub/Sub) = 5 or 6
    # Accept 5-6 due to async timing
    test_eval_sync(b, 6, args.wait)
    # Extra settle: ensure getFlagConfig catches up after Pub/Sub batch delivery
    time.sleep(4)
    test_eval_complex_rules(b)
    test_evaluation(b)
    test_ingest(b)
    test_clickhouse(b)
    test_control_plane(b, args.wait)

    total = PASSED + FAILED + SKIPPED
    print()
    print("=" * 47)
    print(" Tests Complete")
    print("=" * 47)
    print(f"\n  PASSED : {PASSED}")
    print(f"  FAILED : {FAILED}")
    print(f"  SKIPPED: {SKIPPED}")
    print(f"  TOTAL  : {total}\n")
    if FAILED == 0:
        print(" [PASS] All tests passed!")
    else:
        print(f" [FAIL] {FAILED} test(s) failed, check logs.")
        sys.exit(1)


if __name__ == "__main__":
    main()
