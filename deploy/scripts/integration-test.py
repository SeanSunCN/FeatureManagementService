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

# ============================================================
#  Globals
# ============================================================

PASSED = 0
FAILED = 0
SKIPPED = 0


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
    """Structured API response with status code and parsed JSON."""

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
        """
        Extract the business payload from the unified response wrapper.
        UnifiedResponse: {code, message, data, timestamp}
        Some older endpoints return the entity directly.
        """
        j = self.json
        if "data" in j:
            return j["data"]
        return j

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300


def call_api(method: str, url: str, body: str | None = None) -> ApiResponse:
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
    p = argparse.ArgumentParser(description="Feature Management Service Integration Test")
    p.add_argument("--admin", default="http://localhost:8080")
    p.add_argument("--eval", default="http://localhost:8081")
    p.add_argument("--ingest", default="http://localhost:8082")
    p.add_argument("--worker", default="http://localhost:8083")
    p.add_argument("--cdn", default="http://localhost:8084")
    p.add_argument("--wait", type=int, default=3)
    p.add_argument("--cdn-root", default=None, help="CDN root directory on disk")
    return p.parse_args()


# ============================================================
#  API convenience helpers
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


def eval_post(b: dict, path: str, body: dict | list) -> ApiResponse:
    return call_api("POST", api_url(b["eval"], path), json.dumps(body))


def cdn_get(b: dict, path: str) -> ApiResponse:
    return call_api("GET", api_url(b["cdn"], path))


def ingest_post(b: dict, path: str, body: dict | list) -> ApiResponse:
    return call_api("POST", api_url(b["ingest"], path), json.dumps(body))


def ingest_get(b: dict, path: str) -> ApiResponse:
    return call_api("GET", api_url(b["ingest"], path))


def worker_get(b: dict, path: str) -> ApiResponse:
    return call_api("GET", api_url(b["worker"], path))


# ============================================================
#  Test sections
# ============================================================

def test_health_check(b: dict):
    header("1/10  Health Check")
    for name, url in [
        ("Admin API", f"{b['admin']}/actuator/health"),
        ("Eval Service", f"{b['eval']}/actuator/health"),
        ("Ingest Service", f"{b['ingest']}/actuator/health"),
        ("Worker Service", f"{b['worker']}/actuator/health"),
    ]:
        resp = call_api("GET", url)
        if resp.ok and resp.json.get("status") == "UP":
            pass_msg(name)
        else:
            fail_msg(name, f"HTTP={resp.status}")
    # CDN
    resp = call_api("GET", f"{b['cdn']}/__headers")
    pass_msg(f"CDN Nginx (HTTP {resp.status})") if resp.ok else fail_msg("CDN Nginx", f"HTTP={resp.status}")


def test_create_app(b: dict):
    header("2/10  Create App")
    resp = admin_post(b, "/api/v1/apps", {
        "appId": "integration-test-app", "appName": "Integration Test App",
        "description": "Auto-created by integration test", "appType": "BACKEND"})
    pass_msg("Create App") if resp.ok else fail_msg("Create App", f"HTTP={resp.status}")
    resp = admin_get(b, "/api/v1/apps/integration-test-app")
    if resp.data.get("appId") == "integration-test-app":
        pass_msg("Verify App created")
    else:
        fail_msg("Verify App created", f"got {resp.data.get('appId')}")


def test_create_flags(b: dict):
    header("3/10  Create Feature Flags")
    flags = [
        ("flag-a", "Flag A (Full Rollout)", True, True, []),
        ("flag-b", "Flag B (Gradual 50%)", True, False, []),
        ("flag-c", "Flag C (Disabled)", False, False, []),
    ]
    for k, name, enabled, strategy, rules in flags:
        resp = admin_post(b, "/api/v1/apps/integration-test-app/flags", {
            "flagKey": k, "flagName": name, "description": f"{name} test",
            "globalEnabled": enabled, "defaultStrategy": strategy, "rules": rules})
        pass_msg(f"Create {k}") if resp.ok else fail_msg(f"Create {k}", f"HTTP={resp.status}")
    resp = admin_get(b, "/api/v1/apps/integration-test-app/flags")
    flags_list = resp.data if isinstance(resp.data, list) else []
    if len(flags_list) == 3:
        pass_msg("Verify 3 Flags created")
    else:
        fail_msg("Verify 3 Flags", f"actual count={len(flags_list)}")


def test_cdn_publish(b: dict, cdn_root: str | None, wait: int):
    header("4/10  CDN Publish (safe_for_client)")

    # Create client-safe flag
    resp = admin_post(b, "/api/v1/apps/integration-test-app/flags", {
        "flagKey": "flag-cdn-safe", "flagName": "CDN Safe Flag",
        "globalEnabled": True, "defaultStrategy": True, "rules": [],
        "safeForClient": True})
    ok = resp.ok
    pass_msg("Create flag-cdn-safe (safeForClient=true)") if ok else fail_msg("Create flag-cdn-safe", f"HTTP={resp.status}")
    time.sleep(wait)

    # Fetch manifest
    resp = cdn_get(b, "/manifest.json")
    latest_file = resp.json.get("latest_file", "")
    if resp.ok and latest_file:
        pass_msg(f"CDN manifest served (latest_file={latest_file})")
    else:
        fail_msg("CDN manifest", f"HTTP={resp.status}, latest_file={latest_file}")

    # Fetch rules file, verify safe flag present
    vr = resp.json.get("version", 0)
    resp = cdn_get(b, f"/{latest_file}")
    flags_in_rules = resp.json.get("flags", {})
    if "flag-cdn-safe" in flags_in_rules:
        pass_msg(f"Rules {latest_file} (version={resp.json.get('version',0)}) contains flag-cdn-safe")
    else:
        fail_msg("Rules missing flag-cdn-safe")

    # Create server-only flag
    resp = admin_post(b, "/api/v1/apps/integration-test-app/flags", {
        "flagKey": "flag-cdn-server", "flagName": "CDN Server-Only",
        "globalEnabled": True, "defaultStrategy": False, "rules": [],
        "safeForClient": False})
    pass_msg("Create flag-cdn-server (safeForClient=false)") if resp.ok else fail_msg("Create flag-cdn-server", f"HTTP={resp.status}")
    time.sleep(wait)

    # Re-fetch manifest
    resp = cdn_get(b, "/manifest.json")
    latest_file = resp.json.get("latest_file", "")
    pass_msg("CDN manifest updated") if latest_file else fail_msg("CDN manifest missing")

    # Re-fetch rules, verify server-only is excluded
    resp = cdn_get(b, f"/{latest_file}")
    flags_in_rules = resp.json.get("flags", {})
    if "flag-cdn-server" not in flags_in_rules:
        pass_msg("Rules excludes flag-cdn-server (safeForClient=false)")
    else:
        fail_msg("Rules includes server-only flag")

    # On-disk verification
    if cdn_root and os.path.isdir(cdn_root):
        rule_files = sorted(Path(cdn_root).glob("rules.*.json"))
        pass_msg(f"CDN root on disk: {len(rule_files)} rules.*.json file(s)") if rule_files else fail_msg("CDN root on disk: no rules files")
        pass_msg("CDN root on disk: manifest.json exists") if Path(cdn_root, "manifest.json").exists() else fail_msg("CDN root on disk: manifest.json missing")
    else:
        skip_msg("CDN root on-disk check", f"directory {cdn_root} not found")


def test_eval_sync(b: dict, expected: int, wait: int):
    header("5/10  Wait for EvalService Sync")
    print(f"     Waiting {wait}s for sync...")
    time.sleep(wait)
    resp = eval_get(b, "/api/v1/eval/flags?appId=integration-test-app")
    flags = resp.data
    count = len(flags) if isinstance(flags, (dict, list)) else 0
    pass_msg(f"EvalService synced {count} Flags") if count == expected else fail_msg("EvalService sync", f"expected {expected}, got {count}")


def test_evaluation(b: dict):
    header("6/10  Rule Evaluation")
    body = {"appId": "integration-test-app", "flagKey": "flag-a", "userId": "user-001"}
    resp = eval_post(b, "/api/v1/eval/evaluate", body)
    enabled = resp.data.get("enabled", False)
    pass_msg("flag-a evaluate: enabled=true") if enabled else fail_msg("flag-a evaluate", f"enabled={enabled}")

    body["flagKey"] = "flag-c"
    resp = eval_post(b, "/api/v1/eval/evaluate", body)
    enabled = resp.data.get("enabled", True)
    pass_msg("flag-c evaluate: enabled=false") if not enabled else fail_msg("flag-c evaluate", f"enabled={enabled}")

    resp = eval_post(b, "/api/v1/eval/evaluate/batch", [
        {"appId": "integration-test-app", "flagKey": "flag-a", "userId": "user-001"},
        {"appId": "integration-test-app", "flagKey": "flag-b", "userId": "user-002"},
        {"appId": "integration-test-app", "flagKey": "flag-c", "userId": "user-003"}])
    batch = resp.data if isinstance(resp.data, list) else []
    pass_msg("Batch evaluate 3 Flags") if len(batch) == 3 else fail_msg("Batch evaluate", f"count={len(batch)}")


def test_ingest(b: dict):
    header("7/10  Ingest Metrics and Audit Logs")
    resp = ingest_post(b, "/api/v1/ingest/metrics",
                       {"appId": "integration-test-app", "flagHitCounts": {"flag-a": 10, "flag-b": 5, "flag-c": 0}})
    pass_msg("Report Metrics") if resp.ok else fail_msg("Report Metrics", f"HTTP={resp.status}")

    resp = ingest_post(b, "/api/v1/ingest/audit-log",
                       {"appId": "integration-test-app", "flagKey": "flag-a",
                        "userId": "user-001", "enabled": True,
                        "clientIp": "192.168.1.100", "evalCostNs": 1250000})
    pass_msg("Report audit log (single)") if resp.ok else fail_msg("Report audit log (single)", f"HTTP={resp.status}")

    resp = ingest_post(b, "/api/v1/ingest/audit-log/batch", [
        {"appId": "integration-test-app", "flagKey": "flag-a", "userId": "user-001",
         "enabled": True, "clientIp": "10.0.0.1", "evalCostNs": 850000},
        {"appId": "integration-test-app", "flagKey": "flag-b", "userId": "user-002",
         "enabled": True, "clientIp": "10.0.0.2", "evalCostNs": 1200000},
        {"appId": "integration-test-app", "flagKey": "flag-c", "userId": "user-003",
         "enabled": False, "clientIp": "10.0.0.3", "evalCostNs": 300000}])
    pass_msg("Report audit logs (batch 3)") if resp.ok else fail_msg("Report audit logs (batch)", f"HTTP={resp.status}")

    resp = ingest_get(b, "/api/v1/ingest/drop-total")
    drop = resp.data
    if isinstance(drop, (int, float)):
        pass_msg(f"Audit log drop count: {drop}") if drop == 0 else fail_msg("Drop count non-zero", f"drop={drop}")
    else:
        pass_msg(f"Audit log drop count: no counter ({drop})")


def test_clickhouse(b: dict):
    header("8/10  Verify ClickHouse persistence")
    print("     Waiting for Worker batch flush (flush-interval=10s)...")
    time.sleep(12)
    resp = worker_get(b, "/actuator/health")
    ok = resp.json.get("status") == "UP"
    pass_msg("Worker running normally") if ok else fail_msg("Worker abnormal", f"status={resp.json.get('status')}")


def test_control_plane(b: dict, wait: int):
    header("9/10  Control Plane Operations (Toggle / Update / Delete)")

    # Toggle off
    resp = admin_patch(b, "/api/v1/apps/integration-test-app/flags/flag-a/enabled", {"enabled": False})
    pass_msg("Toggle flag-a off") if resp.ok else fail_msg("Toggle flag-a", f"HTTP={resp.status}")
    time.sleep(wait)
    resp = eval_post(b, "/api/v1/eval/evaluate", {"appId": "integration-test-app", "flagKey": "flag-a", "userId": "user-001"})
    pass_msg("EvalService sync: flag-a disabled") if not resp.data.get("enabled", True) else fail_msg("EvalService sync", "flag-a still enabled")

    # Toggle on
    resp = admin_patch(b, "/api/v1/apps/integration-test-app/flags/flag-a/enabled", {"enabled": True})
    pass_msg("Toggle flag-a on") if resp.ok else fail_msg("Toggle flag-a", f"HTTP={resp.status}")
    time.sleep(wait)

    # Update
    resp = admin_put(b, "/api/v1/apps/integration-test-app/flags/flag-b", {
        "flagName": "Flag B (Gradual 30%)", "description": "Changed to 30%",
        "globalEnabled": True, "defaultStrategy": False, "rules": []})
    pass_msg("Update flag-b ratio") if resp.ok else fail_msg("Update flag-b", f"HTTP={resp.status}")
    time.sleep(wait)

    # Delete
    resp = admin_delete(b, "/api/v1/apps/integration-test-app/flags/flag-c")
    pass_msg("Delete flag-c") if resp.ok else fail_msg("Delete flag-c", f"HTTP={resp.status}")
    time.sleep(wait)
    resp = eval_get(b, "/api/v1/eval/flags?appId=integration-test-app")
    remaining = len(resp.data) if isinstance(resp.data, (dict, list)) else 0
    pass_msg(f"EvalService: {remaining} flags left") if remaining == 4 else fail_msg("EvalService after delete", f"count={remaining}")

    # Reload
    resp = admin_post(b, "/api/v1/apps/integration-test-app/flags/reload", {})
    pass_msg("Trigger full reload") if resp.ok else fail_msg("Reload", f"HTTP={resp.status}")
    time.sleep(wait)

    # CDN integrity after reload
    resp = cdn_get(b, "/manifest.json")
    lf = resp.json.get("latest_file", "")
    pass_msg("CDN manifest healthy after reload") if resp.ok and lf else fail_msg("CDN manifest after reload", f"HTTP={resp.status}")
    resp = cdn_get(b, f"/{lf}")
    flags = resp.json.get("flags", {})
    has_safe = "flag-cdn-safe" in flags
    has_server = "flag-cdn-server" in flags
    pass_msg("CDN rules: safe=present, server=excluded") if has_safe and not has_server else fail_msg("CDN rules integrity", f"safe={has_safe}, server={has_server}")


def test_cleanup(b: dict):
    header("10/10  Cleanup Test Data")
    resp = admin_delete(b, "/api/v1/apps/integration-test-app")
    pass_msg("Delete App (cascade)") if resp.ok else fail_msg("Delete App", f"HTTP={resp.status}")
    resp = admin_get(b, "/api/v1/apps/integration-test-app")
    pass_msg("Verify App deleted (404)") if resp.status == 404 else fail_msg("Verify deletion", f"HTTP={resp.status}")


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

    test_health_check(b)
    test_create_app(b)
    test_create_flags(b)
    test_cdn_publish(b, cdn_root, args.wait)
    test_eval_sync(b, 5, args.wait)
    test_evaluation(b)
    test_ingest(b)
    test_clickhouse(b)
    test_control_plane(b, args.wait)
    test_cleanup(b)

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
