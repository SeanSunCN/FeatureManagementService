/**
 * feature-flag-web-sdk.js — Ultra-lightweight client-side Feature Flag SDK
 *
 * Architecture:
 *   Manifest + Cache Busting + Pure In-Memory Evaluation + Periodic Telemetry
 *
 * 1. initSdk(cdnUrl, options)
 *    - Fetches manifest.json with ?t=Date.now() to crush all caches
 *    - Fetches the versioned rules file (which hits browser Disk Cache)
 *    - Parses rules into an in-memory Map for O(1) lookup
 *    - If options.ingestUrl is set, starts periodic metrics flush
 *
 * 2. isEnabled(flagKey, context)
 *    - Pure local evaluation — 0ms, no network call
 *    - context.appId is used for telemetry (SDK is stateless)
 *    - Records a hit counter per (appId, flagKey)
 *
 * 3. getDiagnostics()
 *
 * 4. stopAutoRefresh()
 *
 * Telemetry (aligns with Java LightFlagClient):
 *   - flagHitCounts: { [appId]: { [flagKey]: count } }
 *   - flushed every metricsFlushIntervalMs via POST to ingestUrl
 *   - on page hide/unload: sendBeacon for guaranteed delivery
 *   - all errors silently caught
 *
 * (c) 2026 — Zero external dependencies
 */

(function (global) {
    'use strict';

    // ==================================================================
    //  Internal state (not exposed)
    // ==================================================================

    /** @type {Map<string, Object>} */
    let flagMap = null;

    /** @type {number} */
    let manifestVersion = 0;

    /** @type {string} */
    let manifestUpdatedAt = '';

    /** @type {string} */
    let baseCdnUrl = '';

    /** @type {{fromCache: boolean, transferSize: number, duration: number, fetched: boolean}} */
    let lastFetchDiagnostics = { fromCache: false, transferSize: -1, duration: -1, fetched: false };

    // ---- Telemetry state ----

    /**
     * Hit counters keyed by (appId, flagKey):
     *   { [appId]: { [flagKey]: number } }
     */
    let flagHitCounts = {};

    /**
     * Audit log entries keyed by (appId):
     *   { [appId]: Array<{flagKey, userId, enabled, matchedRule, attributesSnapshot}> }
     */
    let flagAuditLogs = {};

    /** @type {?Object} */
    let telemetryOptions = null;

    /** @type {?number} */
    let metricsTimerId = null;

    /** @type {?function} */
    let onVisibilityChange = null;

    /** @type {?function} */
    let onPageHide = null;

    /** @type {?function} */
    let onBeforeUnload = null;

    // ==================================================================
    //  Initialisation
    // ==================================================================

    /**
     * Initialise the SDK: fetch manifest (always fresh), then fetch rules.
     *
     * @param {string} cdnUrl          Base URL of the CDN
     * @param {Object} [options]
     * @param {number} [options.intervalMs=300000]        Rule refresh interval
     * @param {boolean}[options.refreshOnVisibility=true] Auto-refresh on tab visible
     * @param {string} [options.ingestUrl='']             Metrics POST endpoint
     * @param {number} [options.metricsFlushIntervalMs=5000]  Flush period
     * @param {string} [options.appId='default-app']       Fallback appId when context lacks it
     * @returns {Promise<void>}
     */
    async function initSdk(cdnUrl, options) {
        if (!cdnUrl || typeof cdnUrl !== 'string') {
            throw new Error('initSdk: cdnUrl must be a non-empty string');
        }

        baseCdnUrl = cdnUrl.replace(/\/+$/, '');
        lastFetchDiagnostics.fetched = true;

        const opts = options || {};
        telemetryOptions = {
            intervalMs: opts.intervalMs !== undefined ? opts.intervalMs : 300000,
            refreshOnVisibility: opts.refreshOnVisibility !== undefined ? opts.refreshOnVisibility : true,
            ingestUrl: opts.ingestUrl || '',
            metricsFlushIntervalMs: opts.metricsFlushIntervalMs !== undefined ? opts.metricsFlushIntervalMs : 5000,
            appId: opts.appId || 'default-app',
        };

        // ---- Step 1: Fetch manifest (CACHE BUSTING via ?t=) ----
        const manifestUrl = baseCdnUrl + '/manifest.json?t=' + Date.now();
        console.log('[FF-SDK] Fetching manifest:', manifestUrl);

        let manifest;
        try {
            const resp = await fetch(manifestUrl, { cache: 'no-store' });
            if (!resp.ok) throw new Error('Manifest fetch failed: HTTP ' + resp.status);
            manifest = await resp.json();
        } catch (err) {
            console.error('[FF-SDK] Failed to load manifest:', err.message);
            throw err;
        }

        if (!manifest.latest_file) {
            throw new Error('Manifest missing latest_file field');
        }

        manifestVersion = manifest.version || 0;
        manifestUpdatedAt = manifest.updated_at || '';

        // ---- Step 2: Fetch rules file (STRONG CACHE) ----
        const rulesUrl = baseCdnUrl + '/' + manifest.latest_file;
        console.log('[FF-SDK] Fetching rules:', rulesUrl);

        const fetchStart = performance.now();

        let rulesPayload;
        try {
            const resp = await fetch(rulesUrl, { cache: 'force-cache' });
            if (!resp.ok) throw new Error('Rules fetch failed: HTTP ' + resp.status);
            rulesPayload = await resp.json();

            lastFetchDiagnostics.duration = performance.now() - fetchStart;

            const entries = performance.getEntriesByName(rulesUrl);
            if (entries.length > 0) {
                const last = entries[entries.length - 1];
                lastFetchDiagnostics.transferSize = last.transferSize;
                lastFetchDiagnostics.fromCache = last.transferSize === 0;
            }
        } catch (err) {
            console.error('[FF-SDK] Failed to load rules:', err.message);
            throw err;
        }

        if (!rulesPayload.flags || typeof rulesPayload.flags !== 'object') {
            throw new Error('Rules payload missing "flags" object');
        }

        flagMap = new Map(Object.entries(rulesPayload.flags));
        console.log('[FF-SDK] Initialised: version=' + manifestVersion +
            ', flags=' + flagMap.size +
            ', cache=' + (lastFetchDiagnostics.fromCache ? 'DISK-CACHE' : 'NETWORK'));

        // ---- Step 3: Start telemetry ----
        startTelemetry();
    }

    // ==================================================================
    //  Telemetry: Local Counter Batching & Flush
    // ==================================================================

    function startTelemetry() {
        if (!telemetryOptions || !telemetryOptions.ingestUrl) return;

        stopTelemetry();

        metricsTimerId = setInterval(flushMetrics, telemetryOptions.metricsFlushIntervalMs);

        onVisibilityChange = function () {
            if (document.visibilityState === 'hidden') flushMetrics();
        };
        document.addEventListener('visibilitychange', onVisibilityChange);

        onPageHide = function () { flushViaBeacon(); };
        onBeforeUnload = function () { flushViaBeacon(); };
        global.addEventListener('pagehide', onPageHide);
        global.addEventListener('beforeunload', onBeforeUnload);
    }

    function stopTelemetry() {
        if (metricsTimerId !== null) { clearInterval(metricsTimerId); metricsTimerId = null; }
        if (onVisibilityChange) { document.removeEventListener('visibilitychange', onVisibilityChange); onVisibilityChange = null; }
        if (onPageHide) { global.removeEventListener('pagehide', onPageHide); onPageHide = null; }
        if (onBeforeUnload) { global.removeEventListener('beforeunload', onBeforeUnload); onBeforeUnload = null; }
    }

    /**
     * Snapshot and reset all counters and audit logs, POST one request per appId.
     */
    function flushMetrics() {
        const metricsSnapshot = flagHitCounts;
        flagHitCounts = {};

        const auditSnapshot = flagAuditLogs;
        flagAuditLogs = {};

        // Collect all appIds with either metrics or audit logs
        const appIds = new Set();
        Object.keys(metricsSnapshot).forEach(function (k) { appIds.add(k); });
        Object.keys(auditSnapshot).forEach(function (k) { appIds.add(k); });
        if (appIds.size === 0) return;

        var ingestBase = telemetryOptions.ingestUrl;
        // Remove trailing /api/v1/ingest/metrics if present to get base URL
        var metricsSuffix = '/api/v1/ingest/metrics';
        var auditBatchSuffix = '/api/v1/ingest/audit-log/batch';
        if (ingestBase.indexOf('/api/v1/ingest') > 0) {
            // ingestUrl is already the full metrics endpoint path
            var baseUrl = ingestBase.substring(0, ingestBase.indexOf('/api/v1/ingest'));
            var metricsUrl = baseUrl + metricsSuffix;
            var auditBatchUrl = baseUrl + auditBatchSuffix;
        } else {
            var metricsUrl = ingestBase + metricsSuffix;
            var auditBatchUrl = ingestBase + auditBatchSuffix;
        }

        appIds.forEach(function (appId) {
            // Flush metrics
            var counts = metricsSnapshot[appId];
            if (counts) {
                var keys = Object.keys(counts);
                if (keys.length > 0) {
                    var payload = { appId: appId, flagHitCounts: {} };
                    for (var j = 0; j < keys.length; j++) {
                        var k = keys[j];
                        if (counts[k] > 0) payload.flagHitCounts[k] = counts[k];
                    }
                    fetch(metricsUrl, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload),
                    }).catch(function () {});
                }
            }

            // Flush audit logs
            var logs = auditSnapshot[appId];
            if (logs && logs.length > 0) {
                var auditPayload = logs.slice();
                fetch(auditBatchUrl, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(auditPayload),
                }).catch(function () {});
            }
        });
    }

    function flushViaBeacon() {
        const metricsSnapshot = flagHitCounts;
        flagHitCounts = {};

        const auditSnapshot = flagAuditLogs;
        flagAuditLogs = {};

        const appIds = new Set();
        Object.keys(metricsSnapshot).forEach(function (k) { appIds.add(k); });
        Object.keys(auditSnapshot).forEach(function (k) { appIds.add(k); });
        if (appIds.size === 0) return;

        var ingestBase = telemetryOptions.ingestUrl;
        var metricsSuffix = '/api/v1/ingest/metrics';
        var auditBatchSuffix = '/api/v1/ingest/audit-log/batch';
        if (ingestBase.indexOf('/api/v1/ingest') > 0) {
            var baseUrl = ingestBase.substring(0, ingestBase.indexOf('/api/v1/ingest'));
            var metricsUrl = baseUrl + metricsSuffix;
            var auditBatchUrl = baseUrl + auditBatchSuffix;
        } else {
            var metricsUrl = ingestBase + metricsSuffix;
            var auditBatchUrl = ingestBase + auditBatchSuffix;
        }

        appIds.forEach(function (appId) {
            // Flush metrics
            var counts = metricsSnapshot[appId];
            if (counts) {
                var keys = Object.keys(counts);
                if (keys.length > 0) {
                    var payload = { appId: appId, flagHitCounts: {} };
                    for (var j = 0; j < keys.length; j++) {
                        var k = keys[j];
                        if (counts[k] > 0) payload.flagHitCounts[k] = counts[k];
                    }
                    var blob = new Blob([JSON.stringify(payload)], { type: 'application/json' });
                    if (navigator.sendBeacon) {
                        navigator.sendBeacon(metricsUrl, blob);
                    } else {
                        try {
                            fetch(metricsUrl, {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify(payload),
                                keepalive: true,
                            }).catch(function () {});
                        } catch (_) {}
                    }
                }
            }

            // Flush audit logs
            var logs = auditSnapshot[appId];
            if (logs && logs.length > 0) {
                var auditPayload = logs.slice();
                var auditBlob = new Blob([JSON.stringify(auditPayload)], { type: 'application/json' });
                if (navigator.sendBeacon) {
                    navigator.sendBeacon(auditBatchUrl, auditBlob);
                } else {
                    try {
                        fetch(auditBatchUrl, {
                            method: 'POST',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify(auditPayload),
                            keepalive: true,
                        }).catch(function () {});
                    } catch (_) {}
                }
            }
        });
    }

    // ==================================================================
    //  Evaluation — pure local, 0ms network cost
    // ==================================================================

    /**
     * Evaluate a feature flag purely in-memory.
     *
     * @param {string} flagKey
     * @param {Object} [context]  May include appId, userId, attributes
     * @returns {boolean}
     */
    function isEnabled(flagKey, context) {
        if (!flagMap) {
            console.warn('[FF-SDK] Not initialised — call initSdk() first');
            return false;
        }

        const flag = flagMap.get(flagKey);
        if (!flag) return false;

        // Evaluate
        let result = false;
        let matchedRule = 'default';
        if (!flag.enabled) {
            result = false;
            matchedRule = 'global-disabled';
        } else {
            const rules = flag.rules;
            if (rules && Array.isArray(rules) && rules.length > 0) {
                const ctx = context || {};
                for (const rule of rules) {
                    if (evaluateRule(rule, ctx)) {
                        result = rule.serveValue === true;
                        matchedRule = rule.ruleName || rule.ruleId || 'rule-matched';
                        break;
                    }
                }
            }
            if (result === false) {
                result = flag.defaultServeValue === true;
                matchedRule = 'default';
            }
        }

        // ---- Telemetry: count enabled evaluations per (appId, flagKey) ----
        const appId = (context && context.appId) || telemetryOptions.appId;
        if (result && telemetryOptions && telemetryOptions.ingestUrl) {
            if (!flagHitCounts[appId]) flagHitCounts[appId] = {};
            flagHitCounts[appId][flagKey] = (flagHitCounts[appId][flagKey] || 0) + 1;
        }

        // ---- Audit log: record every evaluation (enabled or not) ----
        if (telemetryOptions && telemetryOptions.ingestUrl) {
            if (!flagAuditLogs[appId]) flagAuditLogs[appId] = [];
            flagAuditLogs[appId].push({
                appId: appId,
                flagKey: flagKey,
                userId: (context && context.userId) || '',
                enabled: result,
                matchedRule: matchedRule,
                attributesSnapshot: (context && context.attributes) || {},
                evalCostNs: 0
            });
        }

        return result;
    }

    // ==================================================================
    //  Rule evaluation helpers
    // ==================================================================

    function evaluateRule(rule, context) {
        const conditions = rule.conditions;
        if (!conditions || !Array.isArray(conditions) || conditions.length === 0) return true;
        for (const cond of conditions) {
            if (!evaluateCondition(cond, context)) return false;
        }
        return true;
    }

    function evaluateCondition(cond, ctx) {
        let actualValue;
        if (cond.attribute === 'userId') {
            actualValue = ctx.userId;
        } else if (ctx.attributes && ctx.attributes.hasOwnProperty(cond.attribute)) {
            actualValue = ctx.attributes[cond.attribute];
        } else {
            return false;
        }
        if (actualValue === undefined || actualValue === null) return false;

        const actualStr = String(actualValue);
        const op = cond.operator;
        const values = cond.values || [];

        switch (op) {
            case 'EQUALS': case 'IN':
                return values.some(v => String(v) === actualStr);
            case 'NOT_EQUALS': case 'NOT_IN':
                return !values.some(v => String(v) === actualStr);
            case 'GREATER_THAN':
                return values.some(v => Number(actualValue) > Number(v));
            case 'LESS_THAN':
                return values.some(v => Number(actualValue) < Number(v));
            default:
                console.warn('[FF-SDK] Unknown operator:', op);
                return false;
        }
    }

    // ==================================================================
    //  Diagnostics / utilities
    // ==================================================================

    function getDiagnostics() {
        return {
            fromCache: lastFetchDiagnostics.fromCache,
            transferSize: lastFetchDiagnostics.transferSize,
            duration: Math.round(lastFetchDiagnostics.duration * 100) / 100,
            version: manifestVersion,
            flagCount: flagMap ? flagMap.size : 0,
            updatedAt: manifestUpdatedAt,
            /** true if data in memory was fetched fresh; false if re-evaluating locally */
            fetched: lastFetchDiagnostics.fetched,
        };
    }

    /**
     * Mark that the most recent evaluation was purely local (no fetch).
     * Called by the demo page before Re-evaluate to correct diagnostics display.
     */
    function markLocalEval() {
        lastFetchDiagnostics.fetched = false;
    }

    function stopAutoRefresh() {
        if (telemetryOptions && telemetryOptions.ingestUrl) flushMetrics();
        stopTelemetry();
    }

    // ==================================================================
    //  Public API
    // ==================================================================

    global.FeatureFlagSDK = {
        initSdk,
        isEnabled,
        getDiagnostics,
        stopAutoRefresh,
        markLocalEval,
    };

})(window);