package com.flag.eval.engine;

import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluationContext;
import com.flag.common.dto.EvaluateResponse;
import com.flag.common.model.FlagConfig;
import com.flag.engine.RuleEngine;
import com.flag.eval.cache.FlagCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Evaluation engine — adapter layer.
 *
 * - Request-based methods bridge from REST API to the pure engine.
 * - Pure computation logic is delegated to {@link RuleEngine}
 *   (zero-framework, shared with Heavy SDK).
 */
@Component
public class EvaluationEngine {

    private static final Logger log = LoggerFactory.getLogger(EvaluationEngine.class);

    private final FlagCache flagCache;

    public EvaluationEngine(FlagCache flagCache) {
        this.flagCache = flagCache;
    }

    /**
     * Evaluate a single flag via the old EvaluateRequest DTO.
     * Looks up FlagConfig from FlagCache, delegates to RuleEngine.
     */
    public EvaluateResponse evaluate(EvaluateRequest request) {
        long start = System.nanoTime();

        if (request.getAppId() == null || request.getFlagKey() == null) {
            return finishFallback(start, null, false, EvaluateResponse.MatchReason.NOT_FOUND);
        }

        FlagConfig config = flagCache.getFlagConfig(request.getAppId(), request.getFlagKey());
        if (config == null) {
            return finishFallback(start, request.getFlagKey(), false, EvaluateResponse.MatchReason.NOT_FOUND);
        }

        EvaluationContext ctx = EvaluationContext.builder()
                .userId(request.getUserId())
                .attributes(request.getAttributes())
                .cachedVersion(request.getCachedVersion() >= 0 ? (long) request.getCachedVersion() : null)
                .build();

        return RuleEngine.evaluate(config, ctx);
    }

    /**
     * Batch evaluate multiple flags.
     * Each EvaluateRequest carries its own appId.
     */
    public List<EvaluateResponse> evaluateBatch(List<EvaluateRequest> requests) {
        return requests.stream()
                .map(this::evaluate)
                .toList();
    }

    private EvaluateResponse finishFallback(long startNanos, String flagKey,
                                             boolean enabled, EvaluateResponse.MatchReason reason) {
        return EvaluateResponse.builder()
                .flagKey(flagKey)
                .enabled(enabled)
                .matchReason(reason)
                .evalCostNs(System.nanoTime() - startNanos)
                .build();
    }
}
