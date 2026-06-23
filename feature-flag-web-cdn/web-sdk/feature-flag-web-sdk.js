/**
 * feature-flag-web-sdk.js — Ultra-lightweight client-side Feature Flag SDK
 *
 * Architecture:
 *   Manifest + Cache Busting + Pure In-Memory Evaluation
 *
 * 1. initSdk(cdnUrl)
 *    - Fetches manifest.json with ?t=Date.now() to crush all caches
 *    - Resolves the latest_file URL
 *    - Fetches the versioned rules file (which hits browser Disk Cache)
 *    - Parses rules into an in-memory Map for O(1) lookup
 *
 * 2. isEnabled(flagKey, context)
 *    - Pure local evaluation — 0ms, no network call
 *    - Supports: global toggle, default serve value, rule conditions
 *    - Condition operators: EQUALS, NOT_EQUALS, IN, NOT_IN
 *
 * (c) 2026 — Zero external dependencies, < 4KB gzipped
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
    let baseCdnUrl = '';

    /**
     * Track whether the last rules fetch hit the network or Disk Cache.
     * We inspect performance entries (Resource Timing API) to detect this.
     *
     * @type {{fromCache: boolean, transferSize: number, duration: number}}
     */
    let lastFetchDiagnostics = { fromCache: false, transferSize: -1, duration: -1 };

    // ==================================================================
    //  Initialisation
    // ==================================================================

    /**
     * Initialise the SDK: fetch manifest (always fresh), then fetch rules
     * (likely from Disk Cache).
     *
     * @param {string} cdnUrl  Base URL of the CDN, e.g. "http://localhost:8080"
     * @returns {Promise<void>}
     */
    async function initSdk(cdnUrl) {
        if (!cdnUrl || typeof cdnUrl !== 'string') {
            throw new Error('initSdk: cdnUrl must be a non-empty string');
        }

        // Normalise: strip trailing slash
        baseCdnUrl = cdnUrl.replace(/\/+$/, '');

        // ---- Step 1: Fetch manifest (CACHE BUSTING via ?t=) ----
        const manifestUrl = baseCdnUrl + '/manifest.json?t=' + Date.now();
        console.log('[FF-SDK] Fetching manifest:', manifestUrl);

        let manifest;
        try {
            const resp = await fetch(manifestUrl, { cache: 'no-store' });
            if (!resp.ok) {
                throw new Error('Manifest fetch failed: HTTP ' + resp.status);
            }
            manifest = await resp.json();
        } catch (err) {
            console.error('[FF-SDK] Failed to load manifest:', err.message);
            throw err;
        }

        if (!manifest.latest_file) {
            throw new Error('Manifest missing latest_file field');
        }

        manifestVersion = manifest.version || 0;

        // ---- Step 2: Fetch rules file (STRONG CACHE — likely Disk Cache hit) ----
        // Note: NO cache buster here — the URL is versioned (rules.1002.json)
        // and Nginx returns Cache-Control: public, immutable, max-age=31536000.
        // The browser will serve this from Disk Cache on repeat visits.
        const rulesUrl = baseCdnUrl + '/' + manifest.latest_file;
        console.log('[FF-SDK] Fetching rules:', rulesUrl);

        // Record pre-fetch timestamps for cache diagnostics
        const fetchStart = performance.now();

        let rulesPayload;
        try {
            const resp = await fetch(rulesUrl);
            if (!resp.ok) {
                throw new Error('Rules fetch failed: HTTP ' + resp.status);
            }
            rulesPayload = await resp.json();

            // Capture diagnostics AFTER the response
            lastFetchDiagnostics.duration = performance.now() - fetchStart;

            // Use Resource Timing API to detect cache hit
            const entries = performance.getEntriesByName(rulesUrl);
            if (entries.length > 0) {
                const last = entries[entries.length - 1];
                lastFetchDiagnostics.transferSize = last.transferSize;
                // transferSize === 0 means served from cache (Disk Cache or Memory Cache)
                lastFetchDiagnostics.fromCache = last.transferSize === 0;
            }

        } catch (err) {
            console.error('[FF-SDK] Failed to load rules:', err.message);
            throw err;
        }

        // ---- Step 3: Parse into in-memory map ----
        if (!rulesPayload.flags || typeof rulesPayload.flags !== 'object') {
            throw new Error('Rules payload missing "flags" object');
        }

        flagMap = new Map(Object.entries(rulesPayload.flags));
        console.log('[FF-SDK] Initialised: version=' + manifestVersion +
            ', flags=' + flagMap.size +
            ', cache=' + (lastFetchDiagnostics.fromCache ? 'DISK-CACHE' : 'NETWORK') +
            ', transferSize=' + lastFetchDiagnostics.transferSize + 'B');
    }

    // ==================================================================
    //  Evaluation — pure local, 0ms network cost
    // ==================================================================

    /**
     * Evaluate a feature flag purely in-memory.
     *
     * @param {string} flagKey   The feature flag key
     * @param {Object} [context] Context object with userId, attributes, etc.
     * @returns {boolean}        Whether the flag is enabled for this context
     */
    function isEnabled(flagKey, context) {
        if (!flagMap) {
            console.warn('[FF-SDK] Not initialised — call initSdk() first');
            return false;
        }

        const flag = flagMap.get(flagKey);
        if (!flag) {
            // Unknown flag → disabled by default
            return false;
        }

        // Step 1: If global toggle is OFF, flag is always disabled
        if (!flag.enabled) {
            return false;
        }

        // Step 2: Evaluate rules (first match wins — OR semantics)
        const rules = flag.rules;
        if (rules && Array.isArray(rules) && rules.length > 0) {
            const ctx = context || {};
            for (const rule of rules) {
                if (evaluateRule(rule, ctx)) {
                    return rule.serveValue === true;
                }
            }
        }

        // Step 3: No rule matched → use defaultServeValue
        return flag.defaultServeValue === true;
    }

    // ==================================================================
    //  Rule evaluation helpers
    // ==================================================================

    /**
     * Evaluate a single rule against the given context.
     * A rule matches when ALL its conditions pass (AND logic).
     *
     * @param {Object} rule
     * @param {Object} context
     * @returns {boolean}
     */
    function evaluateRule(rule, context) {
        const conditions = rule.conditions;
        if (!conditions || !Array.isArray(conditions) || conditions.length === 0) {
            // No conditions = always match
            return true;
        }

        for (const cond of conditions) {
            if (!evaluateCondition(cond, context)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluate a single condition.
     *
     * Supported operators:
     *   EQUALS, NOT_EQUALS, IN, NOT_IN, GREATER_THAN, LESS_THAN
     *
     * @param {Object} cond   { attribute, operator, values }
     * @param {Object} ctx    { userId, attributes?: { key: val } }
     * @returns {boolean}
     */
    function evaluateCondition(cond, ctx) {
        // Resolve the actual value from context
        let actualValue;
        if (cond.attribute === 'userId') {
            actualValue = ctx.userId;
        } else if (ctx.attributes && ctx.attributes.hasOwnProperty(cond.attribute)) {
            actualValue = ctx.attributes[cond.attribute];
        } else {
            // Attribute not present in context → condition fails
            return false;
        }

        if (actualValue === undefined || actualValue === null) {
            return false;
        }

        const actualStr = String(actualValue);
        const op = cond.operator;
        const values = cond.values || [];

        switch (op) {
            case 'EQUALS':
                return values.some(v => String(v) === actualStr);

            case 'NOT_EQUALS':
                return !values.some(v => String(v) === actualStr);

            case 'IN':
                return values.some(v => String(v) === actualStr);

            case 'NOT_IN':
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

    /**
     * Return diagnostics from the last rules fetch.
     * Useful for verifying cache-hit behaviour in the demo page.
     *
     * @returns {{ fromCache: boolean, transferSize: number, duration: number, version: number, flagCount: number }}
     */
    function getDiagnostics() {
        return {
            fromCache: lastFetchDiagnostics.fromCache,
            transferSize: lastFetchDiagnostics.transferSize,
            duration: Math.round(lastFetchDiagnostics.duration * 100) / 100,
            version: manifestVersion,
            flagCount: flagMap ? flagMap.size : 0,
        };
    }

    // ==================================================================
    //  Public API
    // ==================================================================

    global.FeatureFlagSDK = {
        initSdk,
        isEnabled,
        getDiagnostics,
    };

})(window);
