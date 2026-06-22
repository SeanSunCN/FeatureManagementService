package com.flag.eval.controller;

import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluateResponse;
import com.flag.common.response.UnifiedResponse;
import com.flag.eval.cache.FlagCache;
import com.flag.eval.engine.EvaluationEngine;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Flag evaluation API.
 * <p>
 * Architecture diagram: EvalService only exposes /evaluate, pure in-memory computation / stateless cluster.
 * Receives evaluation requests from SDK and returns flag states.
 */
@RestController
@RequestMapping("/api/v1/eval")
public class EvalController {

    private final EvaluationEngine engine;
    private final FlagCache flagCache;

    public EvalController(EvaluationEngine engine, FlagCache flagCache) {
        this.engine = engine;
        this.flagCache = flagCache;
    }

    /**
     * POST /api/v1/eval/evaluate
     * Evaluate a single flag.
     */
    @PostMapping("/evaluate")
    public Mono<UnifiedResponse<EvaluateResponse>> evaluate(@RequestBody EvaluateRequest request) {
        return Mono.fromCallable(() -> {
            EvaluateResponse result = engine.evaluate(request);
            return UnifiedResponse.success(result);
        });
    }

    /**
     * POST /api/v1/eval/evaluate/batch
     * Batch evaluate multiple flags under the same App.
     */
    @PostMapping("/evaluate/batch")
    public Mono<UnifiedResponse<List<EvaluateResponse>>> evaluateBatch(
            @RequestBody List<EvaluateRequest> requests) {
        return Mono.fromCallable(() -> {
            if (requests == null || requests.isEmpty()) {
                return UnifiedResponse.success(List.of());
            }
            // Each request carries its own appId; engine parses it internally
            List<EvaluateResponse> results = engine.evaluateBatch(requests);
            return UnifiedResponse.success(results);
        });
    }

    /**
     * GET /api/v1/eval/flags?appId=xxx
     * Get the full rule snapshot for a given App (used by Light SDK / CDN origin fetch).
     */
    @GetMapping("/flags")
    public Mono<UnifiedResponse<Map<String, FlagCache.FlagEntry>>> getFlags(
            @RequestParam String appId) {
        return Mono.fromCallable(() -> {
            Map<String, FlagCache.FlagEntry> snapshot = flagCache.getSnapshot(appId);
            return UnifiedResponse.success(snapshot);
        });
    }
}