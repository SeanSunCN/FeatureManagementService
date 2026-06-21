package com.flag.eval.engine;

import com.flag.eval.cache.FlagCache;
import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluateResponse;
import com.flag.common.enums.TargetMatchType;
import com.flag.common.exception.BusinessException;
import com.flag.common.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Pure in-memory evaluation engine.
 * <p>
 * Architecture diagram: EvalService only exposes /evaluate, all in-memory computation.
 * Does not depend on any external services; evaluation is completed entirely in the local cache.
 */
@Component
public class EvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(EvaluationEngine.class);

    private final FlagCache flagCache;
    private final ObjectMapper objectMapper;
    private final boolean defaultResult;

    public EvaluationEngine(FlagCache flagCache,
                            ObjectMapper objectMapper,
                            org.springframework.core.env.Environment env) {
        this.flagCache = flagCache;
        this.objectMapper = objectMapper;
        this.defaultResult = env.getProperty("app.eval.default-result", boolean.class, false);
    }

    /**
     * Evaluate a single flag.
     */
    public EvaluateResponse evaluate(EvaluateRequest request) {
        long start = System.nanoTime();

        if (request.getAppId() == null || request.getFlagKey() == null) {
            throw new BusinessException(ErrorCode.EVAL_PARAM_INVALID, "appId and flagKey are required");
        }

        FlagCache.FlagEntry entry = flagCache.get(request.getAppId(), request.getFlagKey());
        if (entry == null) {
            log.debug("Flag not found in cache: appId={}, flagKey={}, returning default={}",
                    request.getAppId(), request.getFlagKey(), defaultResult);
            return buildResponse(request.getFlagKey(), defaultResult, "default-not-found", start);
        }

        // Version short-circuit: If client's cached version >= server version, skip rule evaluation
        if (request.getCachedVersion() >= entry.version()) {
            return buildResponse(request.getFlagKey(), entry.enabled(), "cache-hit", start);
        }

        if (!entry.enabled()) {
            return buildResponse(request.getFlagKey(), false, "global-disabled", start);
        }

        // Parse rule config and execute matching
        boolean matched = evaluateRule(entry, request);
        return buildResponse(request.getFlagKey(), matched,
                matched ? "rule-matched" : "rule-not-matched", start);
    }

    /**
     * Batch evaluation.
     */
    public List<EvaluateResponse> evaluateBatch(String appId, List<EvaluateRequest> requests) {
        return requests.stream()
                .map(r -> {
                    r.setAppId(appId);
                    return evaluate(r);
                })
                .toList();
    }

    /**
     * Execute matching logic based on rule configuration.
     */
    private boolean evaluateRule(FlagCache.FlagEntry entry, EvaluateRequest request) {
        String ruleConfig = entry.ruleConfig();
        if (ruleConfig == null || ruleConfig.isBlank()) {
            // When no rule config is present, return directly based on the enabled flag
            return entry.enabled();
        }

        try {
            JsonNode root = objectMapper.readTree(ruleConfig);

            // Map JSON strategy string to strongly-typed enum — no raw string comparisons
            String strategyStr = root.path("strategy").asText("boolean");
            TargetMatchType strategy;
            try {
                strategy = TargetMatchType.valueOf(strategyStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Unknown strategy: {}, falling back to enabled flag", strategyStr);
                return entry.enabled();
            }

            return switch (strategy) {
                case BOOLEAN ->
                    root.path("enabled").asBoolean(entry.enabled());
                case GRADUAL_ROLLOUT ->
                    evaluateGradualRollout(root, request);
                case TARGETING ->
                    evaluateTargeting(root, entry, request);
            };

        } catch (JsonProcessingException e) {
            log.error("Failed to parse ruleConfig for flagKey={}: {}", entry.flagKey(), e.getMessage());
            return defaultResult;
        }
    }

    /**
     * Gradual rollout evaluation.
     * Determines whether the user falls within the rollout range based on userId hash modulo.
     */
    private boolean evaluateGradualRollout(JsonNode root, EvaluateRequest request) {
        int percentage = root.path("percentage").asInt(0);
        if (percentage <= 0) return false;
        if (percentage >= 100) return true;

        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            return false;
        }

        // Consistent hash: same userId always lands in the same rollout bucket
        int hash = Math.abs(userId.hashCode()) % 100;
        return hash < percentage;
    }

    /**
     * Targeted condition evaluation:
     * 1. Check userId whitelist first
     * 2. Then check attribute matching rules
     * 3. Return default value if nothing matches
     */
    private boolean evaluateTargeting(JsonNode root, FlagCache.FlagEntry entry, EvaluateRequest request) {
        // O(1) whitelist lookup via pre-parsed Set — no JSON iteration at runtime
        if (request.getUserId() != null && entry.whitelist().contains(request.getUserId())) {
            return true;
        }

        // Check attribute conditions
        JsonNode conditions = root.path("conditions");
        if (conditions.isArray() && request.getAttributes() != null) {
            Map<String, String> attrs = request.getAttributes();
            for (JsonNode condition : conditions) {
                String type = condition.path("type").asText();
                if ("attribute".equals(type)) {
                    String key = condition.path("key").asText();
                    JsonNode values = condition.path("values");
                    String actualValue = attrs.get(key);
                    if (actualValue != null && values.isArray()) {
                        for (JsonNode v : values) {
                            if (v.asText().equals(actualValue)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }

        return root.path("default").asBoolean(defaultResult);
    }

    private EvaluateResponse buildResponse(String flagKey, boolean enabled,
                                           String matchedRule, long start) {
        EvaluateResponse resp = EvaluateResponse.of(flagKey, enabled);
        resp.setMatchedRule(matchedRule);
        resp.setEvalCostNs(System.nanoTime() - start);
        return resp;
    }
}