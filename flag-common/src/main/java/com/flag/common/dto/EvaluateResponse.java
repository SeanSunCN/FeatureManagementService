package com.flag.common.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Immutable evaluation result.
 * <p>
 * Produced by {@code EvaluationEngine.evaluate()}.
 * Once created, fields never change — thread-safe by design.
 *
 * @see com.flag.common.model.FlagConfig
 * @see com.flag.eval.engine.EvaluationEngine
 */
@Value
@Builder(toBuilder = true)
public class EvaluateResponse {

    /** All possible match outcomes */
    public enum MatchReason {
        /** Client-side cache is current — evaluation skipped */
        CACHE_HIT,
        /** Global flag switch is OFF */
        FLAG_DISABLED,
        /** A rule's conditions matched and its serveValue is returned */
        RULE_MATCHED,
        /** No flag config found for the given key */
        NOT_FOUND,
        /** Fallback: no rule matched, defaultServeValue returned */
        DEFAULT
    }

    String flagKey;
    boolean enabled;
    MatchReason matchReason;
    String matchedRuleName;
    long evalCostNs;

    /**
     * Quick fallback — flag not found or globally disabled.
     * Cost is set to 0 since no evaluation ran.
     */
    public static EvaluateResponse fallback(String flagKey, boolean defaultEnabled, MatchReason reason) {
        return EvaluateResponse.builder()
                .flagKey(flagKey)
                .enabled(defaultEnabled)
                .matchReason(reason)
                .evalCostNs(0L)
                .build();
    }

    /**
     * Quick builder for SDK local evaluations (Light SDK).
     * Sets matchReason to RULE_MATCHED and cost to 0.
     */
    public static EvaluateResponse of(String flagKey, boolean enabled) {
        return EvaluateResponse.builder()
                .flagKey(flagKey)
                .enabled(enabled)
                .matchReason(MatchReason.RULE_MATCHED)
                .matchedRuleName("local-sdk")
                .evalCostNs(0L)
                .build();
    }
}