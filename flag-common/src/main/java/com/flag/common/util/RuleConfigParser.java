package com.flag.common.util;

import com.flag.common.model.Condition;
import com.flag.common.model.EvaluationRule;
import com.flag.common.model.FlagConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared utility for parsing ruleConfig JSON into {@link FlagConfig} domain objects.
 *
 * Used by:
 * - EvalService's {@code FlagCache} (pre-parses JsonNode at cache-write time)
 * - Heavy SDK's {@code FlagEntryParser} (pre-parses FlagConfig at cache-write time)
 *
 * JSON tree parsing happens ONCE at cache-write time, NOT on the evaluation hot path.
 */
public class RuleConfigParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleConfigParser() {}

    /**
     * Parse a ruleConfig JSON string (or raw Object) into a complete FlagConfig.
     */
    public static FlagConfig parse(String flagKey, boolean enabled, Object rcRaw) {
        if (rcRaw == null) {
            return FlagConfig.builder()
                    .flagKey(flagKey).globalEnabled(enabled).defaultServeValue(enabled)
                    .build();
        }
        try {
            JsonNode node = rcRaw instanceof String
                    ? MAPPER.readTree((String) rcRaw)
                    : MAPPER.valueToTree(rcRaw);
            return parseNode(flagKey, enabled, node);
        } catch (Exception e) {
            return FlagConfig.builder()
                    .flagKey(flagKey).globalEnabled(enabled).defaultServeValue(enabled)
                    .build();
        }
    }

    /**
     * Parse a JsonNode (from ruleConfig field) into a FlagConfig.
     */
    public static FlagConfig parseNode(String flagKey, boolean enabled, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return FlagConfig.builder()
                    .flagKey(flagKey).globalEnabled(enabled).defaultServeValue(enabled)
                    .build();
        }

        JsonNode rulesNode = node.path("rules");
        List<EvaluationRule> rules = new ArrayList<>();

        if (rulesNode.isArray()) {
            for (JsonNode r : rulesNode) {
                rules.add(parseRuleNode(r));
            }
        }

        return FlagConfig.builder()
                .flagKey(flagKey)
                .globalEnabled(enabled)
                .rules(rules)
                .defaultServeValue(node.path("defaultStrategy").asBoolean(false))
                .build();
    }

    private static EvaluationRule parseRuleNode(JsonNode r) {
        JsonNode condsNode = r.path("conditions");
        List<Condition> conditions = new ArrayList<>();

        if (condsNode.isArray()) {
            for (JsonNode c : condsNode) {
                conditions.add(parseConditionNode(c));
            }
        }

        return EvaluationRule.builder()
                .ruleId(r.path("ruleId").asText(null))
                .ruleName(r.path("ruleName").asText(null))
                .serveValue(r.path("serveValue").asBoolean(false))
                .conditions(conditions)
                .build();
    }

    private static Condition parseConditionNode(JsonNode c) {
        String opStr = c.path("operator").asText("EQUALS");
        Condition.Operator op;
        try {
            op = Condition.Operator.valueOf(opStr);
        } catch (IllegalArgumentException e) {
            op = Condition.Operator.EQUALS;
        }

        List<String> values = new ArrayList<>();
        c.path("values").forEach(v -> values.add(v.asText()));

        return Condition.builder()
                .attribute(c.path("attribute").asText(null))
                .operator(op)
                .values(values)
                .build();
    }

    /**
     * Parse ruleConfig string into a raw JsonNode (used by EvalService's FlagEntry).
     * Returns MissingNode for null/blank/malformed input so callers never deal with null.
     */
    public static JsonNode parseToNode(String ruleConfig) {
        if (ruleConfig == null || ruleConfig.isBlank()) {
            return MAPPER.getNodeFactory().missingNode();
        }
        try {
            return MAPPER.readTree(ruleConfig);
        } catch (Exception e) {
            return MAPPER.getNodeFactory().missingNode();
        }
    }

    /**
     * Pre-parse the user_ids whitelist from a ruleConfig JsonNode.
     * Returns empty set (never null).
     */
    public static java.util.Set<String> parseWhitelist(JsonNode root) {
        if (root == null || root.isMissingNode()) return java.util.Collections.emptySet();
        JsonNode userIds = root.path("user_ids");
        if (userIds.isArray()) {
            java.util.Set<String> result = new java.util.HashSet<>();
            userIds.forEach(v -> {
                String s = v.asText();
                if (!s.isBlank()) result.add(s);
            });
            return java.util.Collections.unmodifiableSet(result);
        }
        return java.util.Collections.emptySet();
    }
}
