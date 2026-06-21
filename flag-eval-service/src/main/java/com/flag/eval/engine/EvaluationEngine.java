package com.flag.eval.engine;

import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluationContext;
import com.flag.common.dto.EvaluateResponse;
import com.flag.common.dto.EvaluateResponse.MatchReason;
import com.flag.common.model.Condition;
import com.flag.common.model.EvaluationRule;
import com.flag.common.model.FlagConfig;
import com.flag.eval.cache.FlagCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Evaluation engine with two entry points:
 * <ol>
 *   <li><b>Pure model evaluation</b> — {@link #evaluate(FlagConfig, EvaluationContext)}
 *       takes a pre-loaded FlagConfig and returns an immutable EvaluateResponse.
 *       Zero external dependencies; pure in-memory computation.</li>
 *   <li><b>Request-based adapter</b> — {@link #evaluate(EvaluateRequest)} and
 *       {@link #evaluateBatch(String, List)} bridge from the existing REST API
 *       to the new model by looking up the FlagConfig via FlagCache.</li>
 * </ol>
 * <p>
 * Evaluation flow (pure method):
 * <ol>
 *   <li>Check globalEnabled — OFF means immediate fallback</li>
 *   <li>Iterate rules top-down (OR / if-elseif chain)</li>
 *   <li>Within each rule, evaluate all conditions (AND)</li>
 *   <li>First fully-matched rule short-circuits → return its serveValue</li>
 *   <li>No rule matched → return defaultServeValue</li>
 * </ol>
 */
@Component
public class EvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(EvaluationEngine.class);

    private final FlagCache flagCache;

    public EvaluationEngine(FlagCache flagCache) {
        this.flagCache = flagCache;
    }

    // ========================================================================
    //  Pure model evaluation  (no external dependencies)
    // ========================================================================

    /**
     * Evaluate a FlagConfig against a runtime context.
     * Pure computation — all input is in the parameters, no external calls.
     *
     * @param config  the full flag configuration (from cache or direct)
     * @param context runtime context (userId, attributes)
     * @return immutable EvaluateResponse
     */
    public EvaluateResponse evaluate(FlagConfig config, EvaluationContext context) {
        long start = System.nanoTime();

        // 1. Null / global toggle check
        if (config == null) {
            return finish(start, null, false, MatchReason.NOT_FOUND, null);
        }
        if (!config.isGlobalEnabled()) {
            return finish(start, config.getFlagKey(), false, MatchReason.FLAG_DISABLED, null);
        }

        // 2. Iterate rules in order — first match wins (OR semantics)
        List<EvaluationRule> rules = config.getRules();
        if (rules != null && !rules.isEmpty()) {
            for (EvaluationRule rule : rules) {
                if (matchesAllConditions(rule.getConditions(), context)) {
                    long cost = System.nanoTime() - start;
                    return EvaluateResponse.builder()
                            .flagKey(config.getFlagKey())
                            .enabled(rule.isServeValue())
                            .matchReason(MatchReason.RULE_MATCHED)
                            .matchedRuleName(rule.getRuleName())
                            .evalCostNs(cost)
                            .build();
                }
            }
        }

        // 3. No rule matched → defaultServeValue
        return finish(start, config.getFlagKey(), config.isDefaultServeValue(),
                MatchReason.DEFAULT, null);
    }

    // ========================================================================
    //  Request-based adapter  (bridges old REST API to new model)
    // ========================================================================

    /**
     * Evaluate a single flag via the old EvaluateRequest DTO.
     * Looks up FlagConfig from FlagCache, bridges to the pure method.
     */
    public EvaluateResponse evaluate(EvaluateRequest request) {
        long start = System.nanoTime();

        if (request.getAppId() == null || request.getFlagKey() == null) {
            return finish(start, null, false, MatchReason.NOT_FOUND, null);
        }

        FlagConfig config = flagCache.getFlagConfig(request.getAppId(), request.getFlagKey());
        if (config == null) {
            return finish(start, request.getFlagKey(), false, MatchReason.NOT_FOUND, null);
        }

        EvaluationContext ctx = EvaluationContext.builder()
                .userId(request.getUserId())
                .attributes(request.getAttributes())
                .cachedVersion(request.getCachedVersion() >= 0 ? (long) request.getCachedVersion() : null)
                .build();

        return evaluate(config, ctx);
    }

    /**
     * Batch evaluate multiple flags under the same App.
     */
    public List<EvaluateResponse> evaluateBatch(String appId, List<EvaluateRequest> requests) {
        return requests.stream()
                .map(r -> {
                    r.setAppId(appId);
                    return evaluate(r);
                })
                .toList();
    }

    // ========================================================================
    //  Condition matching logic
    // ========================================================================

    private boolean matchesAllConditions(List<Condition> conditions, EvaluationContext context) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        Map<String, String> attrs = context.getAttributes();
        if (attrs == null) {
            // No attributes provided — conditions that check attributes will fail
            // If all conditions require attributes, this returns false
        }

        for (Condition c : conditions) {
            if (!matchesSingle(c, attrs)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesSingle(Condition c, Map<String, String> attrs) {
        String actual = attrs != null ? attrs.get(c.getAttribute()) : null;
        if (actual == null && c.getAttribute() != null && c.getAttribute().equals("userId")) {
            // Special case: the rule checks "userId" but it's not in attributes map
            // — fall through to return false
        }
        if (actual == null) {
            return false;
        }

        List<String> values = c.getValues();
        if (values == null || values.isEmpty()) {
            return false;
        }

        return switch (c.getOperator()) {
            case EQUALS -> values.stream().anyMatch(v -> v.equals(actual));
            case NOT_EQUALS -> values.stream().noneMatch(v -> v.equals(actual));
            case IN -> values.contains(actual);
            case NOT_IN -> !values.contains(actual);
            case GREATER_THAN -> values.stream().anyMatch(v -> compareVersions(actual, v) > 0);
            case GREATER_THAN_OR_EQUAL -> values.stream().anyMatch(v -> compareVersions(actual, v) >= 0);
            case LESS_THAN -> values.stream().anyMatch(v -> compareVersions(actual, v) < 0);
            case LESS_THAN_OR_EQUAL -> values.stream().anyMatch(v -> compareVersions(actual, v) <= 0);
        };
    }

    private int compareVersions(String a, String b) {
        try {
            long na = Long.parseLong(a.trim());
            long nb = Long.parseLong(b.trim());
            return Long.compare(na, nb);
        } catch (NumberFormatException ignored) {}

        String[] partsA = a.trim().split("\\.");
        String[] partsB = b.trim().split("\\.");
        int len = Math.max(partsA.length, partsB.length);
        for (int i = 0; i < len; i++) {
            int segA = i < partsA.length ? tryParseInt(partsA[i], 0) : 0;
            int segB = i < partsB.length ? tryParseInt(partsB[i], 0) : 0;
            if (segA != segB) {
                return Integer.compare(segA, segB);
            }
        }
        return 0;
    }

    private int tryParseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private EvaluateResponse finish(long startNanos, String flagKey,
                                    boolean enabled, MatchReason reason,
                                    String matchedRuleName) {
        return EvaluateResponse.builder()
                .flagKey(flagKey)
                .enabled(enabled)
                .matchReason(reason)
                .matchedRuleName(matchedRuleName)
                .evalCostNs(System.nanoTime() - startNanos)
                .build();
    }
}