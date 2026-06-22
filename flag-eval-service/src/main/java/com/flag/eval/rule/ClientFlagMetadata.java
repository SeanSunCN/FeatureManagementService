package com.flag.eval.rule;

import com.flag.common.model.FlagConfig;

import java.util.Map;

/**
 * Lightweight DTO representing a flag that is safe to publish to the client-side CDN.
 *
 * <p>Only flags with {@code safe_for_client: true} in their metadata produce
 * a {@link ClientFlagMetadata} instance. All server-only flags are pruned
 * at compile time — they never leave the backend.</p>
 *
 * @param flagKey          unique flag identifier
 * @param enabled          global enabled state
 * @param defaultServeValue fallback value when no rule matches
 * @param rules            list of client-safe rules (serialized as JSON objects)
 */
public record ClientFlagMetadata(
        String flagKey,
        boolean enabled,
        boolean defaultServeValue,
        java.util.List<Map<String, Object>> rules
) {

    /**
     * Convert from a full {@link FlagConfig}, extracting only the fields
     * safe for client exposure.
     */
    public static ClientFlagMetadata from(FlagConfig config) {
        java.util.List<Map<String, Object>> ruleMaps = config.getRules() != null
                ? config.getRules().stream()
                .map(r -> java.util.Map.of(
                        "ruleName", r.getRuleName(),
                        "serveValue", r.isServeValue(),
                        "conditions", r.getConditions().stream()
                                .map(c -> java.util.Map.of(
                                        "attribute", c.getAttribute(),
                                        "operator", c.getOperator().name(),
                                        "values", c.getValues()
                                ))
                                .toList()
                ))
                .toList()
                : java.util.List.of();

        return new ClientFlagMetadata(
                config.getFlagKey(),
                config.isGlobalEnabled(),
                config.isDefaultServeValue(),
                ruleMaps
        );
    }
}
