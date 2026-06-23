#!/usr/bin/env python3
"""
Setup Demo Data — creates demo-app with complex rules.
Run after deploy: python3 deploy/scripts/setup-demo.py
"""

import json
import sys
import time
import urllib.request

ADMIN = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"

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

def upsert_flag(key, flag_data):
    status, _ = api("GET", f"{ADMIN}/api/v1/apps/demo-app/flags/{key}")
    if status == 200:
        # Update: PUT only sends modifiable fields (no appId, flagKey, id)
        update = {
            "flagName": flag_data["flagName"],
            "globalEnabled": flag_data["globalEnabled"],
            "defaultStrategy": flag_data["defaultStrategy"],
            "safeForClient": flag_data.get("safeForClient", True),
            "rules": flag_data.get("rules", []),
        }
        s, _ = api("PUT", f"{ADMIN}/api/v1/apps/demo-app/flags/{key}", update)
    else:
        # Create
        s, _ = api("POST", f"{ADMIN}/api/v1/apps/demo-app/flags", flag_data)
    if s in (200, 400, 409):
        print(f"  {key}: OK")
    else:
        print(f"  {key}: FAILED (HTTP {s})")

print("Setting up demo-app with complex rules...")

# Create app if needed
s, _ = api("POST", f"{ADMIN}/api/v1/apps", {"appId":"demo-app","appName":"Demo App","appType":"BACKEND"})
if s not in (200, 400, 409):
    print(f"FAILED: create demo-app (HTTP {s})")
    sys.exit(1)

flags = {
    "new-ui-portal": {
        "flagKey": "new-ui-portal", "flagName": "New UI Portal",
        "globalEnabled": True, "defaultStrategy": False, "safeForClient": True,
        "description": "Complex rules: region-based content targeting",
        "rules": [
            {"ruleId": "r1", "ruleName": "US Premium", "serveValue": True,
             "conditions": [
                 {"attribute": "country", "operator": "EQUALS", "values": ["US"]},
                 {"attribute": "plan", "operator": "IN", "values": ["pro", "enterprise"]}]},
            {"ruleId": "r2", "ruleName": "EU Localized", "serveValue": True,
             "conditions": [
                 {"attribute": "country", "operator": "IN", "values": ["DE", "FR", "UK"]},
                 {"attribute": "beta_tester", "operator": "EQUALS", "values": ["true"]}]},
        ]},
    "dark-mode-v2": {
        "flagKey": "dark-mode-v2", "flagName": "Dark Mode v2",
        "globalEnabled": True, "defaultStrategy": False, "safeForClient": True,
        "description": "Analytics tier based on usage",
        "rules": [
            {"ruleId": "r1", "ruleName": "Advanced Analytics", "serveValue": True,
             "conditions": [
                 {"attribute": "eval_count", "operator": "GREATER_THAN", "values": ["100"]}]},
            {"ruleId": "r2", "ruleName": "Enterprise Suite", "serveValue": True,
             "conditions": [
                 {"attribute": "plan", "operator": "EQUALS", "values": ["enterprise"]}]},
        ]},
    "new-search-portal": {
        "flagKey": "new-search-portal", "flagName": "New Search Portal",
        "globalEnabled": True, "defaultStrategy": False, "safeForClient": True,
        "description": "Search experience tiers",
        "rules": [
            {"ruleId": "r1", "ruleName": "Enterprise Search", "serveValue": True,
             "conditions": [
                 {"attribute": "country", "operator": "IN", "values": ["US", "DE", "UK"]},
                 {"attribute": "plan", "operator": "IN", "values": ["enterprise"]}]},
            {"ruleId": "r2", "ruleName": "Pro Search Beta", "serveValue": True,
             "conditions": [
                 {"attribute": "beta_tester", "operator": "EQUALS", "values": ["true"]},
                 {"attribute": "eval_count", "operator": "GREATER_THAN", "values": ["50"]}]},
            {"ruleId": "r3", "ruleName": "Basic Search", "serveValue": True,
             "conditions": [
                 {"attribute": "plan", "operator": "IN", "values": ["pro"]}]},
        ]},
    "new-pricing-page": {
        "flagKey": "new-pricing-page", "flagName": "New Pricing Page",
        "globalEnabled": True, "defaultStrategy": False, "safeForClient": True,
        "description": "Pricing page variants",
        "rules": [
            {"ruleId": "r1", "ruleName": "Enterprise Pricing", "serveValue": True,
             "conditions": [
                 {"attribute": "plan", "operator": "EQUALS", "values": ["enterprise"]}]},
            {"ruleId": "r2", "ruleName": "Regional Pricing", "serveValue": True,
             "conditions": [
                 {"attribute": "country", "operator": "IN", "values": ["DE", "FR", "UK"]},
                 {"attribute": "role", "operator": "NOT_IN", "values": ["guest"]}]},
        ]},
    "quick-export": {
        "flagKey": "quick-export", "flagName": "Quick Export",
        "globalEnabled": True, "defaultStrategy": False, "safeForClient": True,
        "description": "Progressive rollout stages",
        "rules": [
            {"ruleId": "r1", "ruleName": "Beta Rollout", "serveValue": True,
             "conditions": [
                 {"attribute": "beta_tester", "operator": "EQUALS", "values": ["true"]}]},
            {"ruleId": "r2", "ruleName": "Gradual Rollout", "serveValue": True,
             "conditions": [
                 {"attribute": "country", "operator": "IN", "values": ["US", "DE", "UK"]},
                 {"attribute": "plan", "operator": "NOT_IN", "values": ["free"]}]},
        ]},
}

for key, data in flags.items():
    upsert_flag(key, data)
    time.sleep(0.2)

print("Done! Open http://localhost:8084/index.html")
