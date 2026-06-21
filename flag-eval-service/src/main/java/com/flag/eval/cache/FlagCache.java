package com.flag.eval.cache;

import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.model.Condition;
import com.flag.common.model.EvaluationRule;
import com.flag.common.model.FlagConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Pure in-memory rule cache.
 * <p>
 * Architecture diagram: EvalService holds rules entirely in memory.
 * - Full load from DB on startup
 * - Incremental updates via Redis Pub/Sub at runtime
 * - Supports targeted push by AppId
 * <p>
 * Thread-safe: all operations backed by ConcurrentHashMap; snapshots use immutable copies.
 */
@Component
public class FlagCache {

    private static final Logger log = LoggerFactory.getLogger(FlagCache.class);

    /**
     * In-memory cache structure: appId -> (flagKey -> FlagEntry)
     */
    private final ConcurrentHashMap<String, Map<String, FlagEntry>> cache = new ConcurrentHashMap<>();

    /**
     * Initialize or replace all rule cache for a given App.
     */
    public void putAll(String appId, Map<String, FlagEntry> flags) {
        cache.put(appId, Collections.unmodifiableMap(new HashMap<>(flags)));
        log.info("Cache updated for appId={}, flagCount={}", appId, flags.size());
    }

    /**
     * Add or update a single flag.
     */
    public void put(String appId, String flagKey, FlagEntry entry) {
        cache.compute(appId, (key, existing) -> {
            Map<String, FlagEntry> map = existing != null
                    ? new HashMap<>(existing)
                    : new HashMap<>();
            map.put(flagKey, entry);
            return Collections.unmodifiableMap(map);
        });
    }

    /**
     * Remove a single flag.
     */
    public void remove(String appId, String flagKey) {
        cache.computeIfPresent(appId, (key, existing) -> {
            Map<String, FlagEntry> map = new HashMap<>(existing);
            map.remove(flagKey);
            return map.isEmpty() ? null : Collections.unmodifiableMap(map);
        });
    }

    /**
     * Remove the entire cache for an App.
     */
    public void removeAll(String appId) {
        cache.remove(appId);
        log.info("Cache cleared for appId={}", appId);
    }

    /**
     * Get a snapshot of all flags for a given App (used for full push).
     */
    public Map<String, FlagEntry> getSnapshot(String appId) {
        Map<String, FlagEntry> map = cache.get(appId);
        return map != null ? Collections.unmodifiableMap(map) : Collections.emptyMap();
    }

    /**
     * Get a single flag.
     */
    public FlagEntry get(String appId, String flagKey) {
        Map<String, FlagEntry> map = cache.get(appId);
        return map != null ? map.get(flagKey) : null;
    }

    /**
     * Get a single flag as a FlagConfig (new model).
     * Bridges from legacy FlagEntry storage to the new domain model.
     */
    public FlagConfig getFlagConfig(String appId, String flagKey) {
        FlagEntry entry = get(appId, flagKey);
        if (entry == null) return null;

        JsonNode node = entry.ruleConfigNode();
        FlagConfig.FlagConfigBuilder builder = FlagConfig.builder()
                .flagKey(entry.flagKey())
                .globalEnabled(entry.enabled());

        if (node == null || node.isMissingNode()) {
            return builder.defaultServeValue(false).build();
        }

        // Try new multi-rule format
        JsonNode rulesNode = node.path("rules");
        if (rulesNode.isArray() && rulesNode.size() > 0) {
            List<EvaluationRule> rules = new ArrayList<>();
            for (JsonNode r : rulesNode) {
                List<Condition> conditions = new ArrayList<>();
                JsonNode condsNode = r.path("conditions");
                if (condsNode.isArray()) {
                    for (JsonNode c : condsNode) {
                        String opStr = c.path("operator").asText("EQUALS");
                        Condition.Operator op;
                        try { op = Condition.Operator.valueOf(opStr); }
                        catch (IllegalArgumentException e) { op = Condition.Operator.EQUALS; }

                        List<String> values = new ArrayList<>();
                        JsonNode valsNode = c.path("values");
                        if (valsNode.isArray()) {
                            valsNode.forEach(v -> values.add(v.asText()));
                        }

                        conditions.add(Condition.builder()
                                .attribute(c.path("attribute").asText(null))
                                .operator(op)
                                .values(values)
                                .build());
                    }
                }
                rules.add(EvaluationRule.builder()
                        .ruleId(r.path("ruleId").asText(null))
                        .ruleName(r.path("ruleName").asText(null))
                        .serveValue(r.path("serveValue").asBoolean(false))
                        .conditions(conditions)
                        .build());
            }
            builder.rules(rules);
        }

        builder.defaultServeValue(node.path("defaultStrategy").asBoolean(false));
        return builder.build();
    }

    /**
     * Get all AppIds currently in the cache.
     */
    public Set<String> getAppIds() {
        return Collections.unmodifiableSet(cache.keySet());
    }

    // ========================================================================
    //  FlagEntry — the legacy in-memory record
    // ========================================================================

    private static final ObjectMapper WHITELIST_MAPPER = new ObjectMapper();

    /**
     * A single flag entry in the cache.
     * ruleConfigNode is pre-parsed from ruleConfig JSON at construction time,
     * so evaluation has zero parsing overhead.
     * whitelist is also pre-parsed for O(1) lookup and is never null.
     */
    public record FlagEntry(
            String flagKey,
            String name,
            boolean enabled,
            JsonNode ruleConfigNode,
            Set<String> whitelist,
            int version
    ) {

        public static FlagEntry fromEntity(
                String flagKey, String name, boolean enabled,
                String ruleConfig, int version) {
            JsonNode node = parseRuleConfig(ruleConfig);
            Set<String> whitelist = parseWhitelist(node);
            return new FlagEntry(flagKey, name, enabled, node, whitelist, version);
        }

        public static FlagEntry fromChangeMessage(FlagChangeMessage msg, boolean enabled, String ruleConfig) {
            JsonNode node = parseRuleConfig(ruleConfig);
            Set<String> whitelist = parseWhitelist(node);
            return new FlagEntry(msg.getFlagKey(), null, enabled, node, whitelist, 0);
        }

        /**
         * Parse ruleConfig JSON string into a JsonNode at construction time.
         * Returns MissingNode for null/blank/malformed input, so callers
         * never deal with null and never parse at runtime.
         */
        private static JsonNode parseRuleConfig(String ruleConfig) {
            if (ruleConfig == null || ruleConfig.isBlank()) {
                return WHITELIST_MAPPER.getNodeFactory().missingNode();
            }
            try {
                return WHITELIST_MAPPER.readTree(ruleConfig);
            } catch (JsonProcessingException e) {
                return WHITELIST_MAPPER.getNodeFactory().missingNode();
            }
        }

        /**
         * Pre-parse the user_ids whitelist from ruleConfig JSON at construction time.
         * Returns empty set (never null) — evaluation simply does .contains().
         */
        private static Set<String> parseWhitelist(JsonNode root) {
            if (root == null || root.isMissingNode()) return Collections.emptySet();
            JsonNode userIds = root.path("user_ids");
            if (userIds.isArray()) {
                return StreamSupport.stream(userIds.spliterator(), false)
                        .map(JsonNode::asText)
                        .filter(s -> !s.isBlank())
                        .collect(Collectors.toUnmodifiableSet());
            }
            return Collections.emptySet();
        }
    }
}