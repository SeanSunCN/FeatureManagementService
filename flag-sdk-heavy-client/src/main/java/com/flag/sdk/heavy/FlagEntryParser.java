package com.flag.sdk.heavy;

import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.model.FlagConfig;
import com.flag.common.util.RuleConfigParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Parses raw server responses (Map or String JSON) into {@link FeatureDataStore.CachedFlag}.
 *
 * Delegates ruleConfig JSON parsing to the shared {@link RuleConfigParser}
 * in flag-common, eliminating code duplication with EvalService's FlagCache.
 */
public class FlagEntryParser {

    private static final Logger log = LoggerFactory.getLogger(FlagEntryParser.class);

    private final ObjectMapper objectMapper;

    public FlagEntryParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse a server response entry into a CachedFlag with pre-parsed FlagConfig.
     */
    @SuppressWarnings("unchecked")
    public FeatureDataStore.CachedFlag parse(String flagKey, Object raw) {
        if (raw == null) return null;

        try {
            if (raw instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) raw;
                String name = (String) map.getOrDefault("name", flagKey);
                boolean enabled = Boolean.TRUE.equals(map.get("enabled"));
                int version = map.getOrDefault("version", 0) instanceof Number
                        ? ((Number) map.get("version")).intValue() : 0;
                FlagConfig flagConfig = RuleConfigParser.parse(flagKey, enabled, map.get("ruleConfig"));
                return new FeatureDataStore.CachedFlag(flagKey, name, flagConfig, version);
            }

            if (raw instanceof String) {
                JsonNode root = objectMapper.readTree((String) raw);
                String name = root.path("name").asText(flagKey);
                boolean enabled = root.path("enabled").asBoolean(false);
                int version = root.path("version").asInt(0);
                FlagConfig flagConfig = RuleConfigParser.parseNode(flagKey, enabled, root.path("ruleConfig"));
                return new FeatureDataStore.CachedFlag(flagKey, name, flagConfig, version);
            }
        } catch (Exception e) {
            log.warn("Failed to parse flag entry for flagKey={}: {}", flagKey, e.getMessage());
        }
        return null;
    }
}
