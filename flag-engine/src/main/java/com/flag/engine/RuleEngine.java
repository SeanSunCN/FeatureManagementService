package com.flag.engine;

import com.flag.common.dto.EvaluateResponse;
import com.flag.common.dto.EvaluationContext;
import com.flag.common.model.Condition;
import com.flag.common.model.EvaluationRule;
import com.flag.common.model.FlagConfig;

import java.util.List;
import java.util.Map;

/**
 * Pure rule evaluation engine — zero framework dependency, zero I/O.
 *
 * All methods are static pure functions: same input → same output.
 * Can be used by EvalService, Heavy SDK, or any other component
 * without Spring or any container.
 */
public class RuleEngine {

    private RuleEngine() {}

    /**
     * Evaluate a FlagConfig against a runtime context.
     * Pure computation — all input is in the parameters, no external calls.
     *
     * @param config  the full flag configuration (from cache or direct)
     * @param context runtime context (userId, attributes)
     * @return immutable EvaluateResponse
     */
    public static EvaluateResponse evaluate(FlagConfig config, EvaluationContext context) {
        long start = System.nanoTime();

        // 1. Null / global toggle check
        if (config == null) {
            return finish(start, null, false, EvaluateResponse.MatchReason.NOT_FOUND, null);
        }
        if (!config.isGlobalEnabled()) {
            return finish(start, config.getFlagKey(), false, EvaluateResponse.MatchReason.FLAG_DISABLED, null);
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
                            .matchReason(EvaluateResponse.MatchReason.RULE_MATCHED)
                            .matchedRuleName(rule.getRuleName())
                            .evalCostNs(cost)
                            .build();
                }
            }
        }

        // 3. No rule matched → defaultServeValue
        return finish(start, config.getFlagKey(), config.isDefaultServeValue(),
                EvaluateResponse.MatchReason.DEFAULT, null);
    }

    // ========================================================================
    //  Condition matching logic
    // ========================================================================

    /**
     * Check whether ALL conditions match (AND semantics within a rule).
     */
    public static boolean matchesAllConditions(List<Condition> conditions, EvaluationContext context) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }

        Map<String, String> attrs = context.getAttributes();

        for (Condition c : conditions) {
            if (!matchesSingle(c, attrs)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluate a single condition against runtime attributes.
     */
    public static boolean matchesSingle(Condition c, Map<String, String> attrs) {
        String actual = attrs != null ? attrs.get(c.getAttribute()) : null;
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

    // ========================================================================
    //  Version / numeric comparison utilities
    // ========================================================================

    public static int compareVersions(String a, String b) {
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

    public static int tryParseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ========================================================================
    //  Response builder helper
    // ========================================================================

    private static EvaluateResponse finish(long startNanos, String flagKey,
                                            boolean enabled, EvaluateResponse.MatchReason reason,
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
