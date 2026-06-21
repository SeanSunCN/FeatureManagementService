package com.flag.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strategy-specific JSON Schema validator for ruleConfig.
 *
 * Each strategy has its own JSON Schema enforcing required fields,
 * value ranges, and structural correctness at the input boundary.
 * Catches incomplete or malformed configurations before they reach
 * the database or evaluation engine.
 */
public class RuleConfigValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V7);
    private static final Map<String, JsonSchema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    // ──────────────────────────────────────────────
    // JSON Schema definitions per strategy
    // ──────────────────────────────────────────────

    private static final String BOOLEAN_SCHEMA = """
    {
        "type": "object",
        "properties": {
            "strategy": {"type": "string", "enum": ["boolean"]},
            "enabled": {"type": "boolean"}
        }
    }
    """;

    private static final String GRADUAL_ROLLOUT_SCHEMA = """
    {
        "type": "object",
        "properties": {
            "strategy": {"type": "string", "enum": ["gradual_rollout"]},
            "percentage": {"type": "integer", "minimum": 0, "maximum": 100},
            "bucket_key": {"type": "string"}
        },
        "required": ["strategy", "percentage"]
    }
    """;

    private static final String TARGETING_SCHEMA = """
    {
        "type": "object",
        "properties": {
            "strategy": {"type": "string", "enum": ["targeting"]},
            "user_ids": {
                "type": "array",
                "items": {"type": "string"},
                "uniqueItems": true
            },
            "conditions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "type": {"type": "string", "enum": ["attribute"]},
                        "key": {"type": "string"},
                        "values": {
                            "type": "array",
                            "items": {"type": "string"}
                        }
                    },
                    "required": ["type", "key", "values"]
                }
            },
            "default": {"type": "boolean"}
        },
        "required": ["strategy"]
    }
    """;

    private static final Map<String, String> SCHEMAS = Map.of(
            "boolean", BOOLEAN_SCHEMA,
            "gradual_rollout", GRADUAL_ROLLOUT_SCHEMA,
            "targeting", TARGETING_SCHEMA
    );

    /**
     * Validate a ruleConfig JSON string against its declared strategy.
     *
     * @param ruleConfig the raw JSON string from the API request
     * @throws IllegalArgumentException with detailed message on validation failure
     */
    public static void validate(String ruleConfig) {
        if (ruleConfig == null || ruleConfig.isBlank()) return;

        JsonNode root;
        try {
            root = MAPPER.readTree(ruleConfig);
        } catch (Exception e) {
            throw new IllegalArgumentException("ruleConfig is not valid JSON: " + e.getMessage());
        }

        String strategy = root.path("strategy").asText(null);
        if (strategy == null) {
            // Empty/minimal config — default to boolean strategy, no required fields
            return;
        }

        String schemaStr = SCHEMAS.get(strategy);
        if (schemaStr == null) {
            throw new IllegalArgumentException(
                    "Unknown strategy: '" + strategy + "'. " +
                    "Valid values: boolean, gradual_rollout, targeting");
        }

        JsonSchema schema = SCHEMA_CACHE.computeIfAbsent(strategy, k -> {
            try {
                return FACTORY.getSchema(MAPPER.readTree(schemaStr));
            } catch (Exception e) {
                throw new RuntimeException("Failed to load schema for strategy: " + k, e);
            }
        });

        Set<ValidationMessage> errors = schema.validate(root);
        if (!errors.isEmpty()) {
            StringBuilder msg = new StringBuilder("ruleConfig validation failed: ");
            for (ValidationMessage err : errors) {
                msg.append(err.getMessage()).append("; ");
            }
            throw new IllegalArgumentException(msg.toString());
        }
    }
}
