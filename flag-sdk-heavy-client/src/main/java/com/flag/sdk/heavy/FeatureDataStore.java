package com.flag.sdk.heavy;

import com.flag.common.dto.EvaluateResponse;
import com.flag.common.dto.EvaluationContext;
import com.flag.common.dto.MetricsReportRequest;
import com.flag.common.model.FlagConfig;
import com.flag.engine.RuleEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure in-memory feature flag cache and evaluation engine.
 *
 * Responsibilities:
 * - Maintain an {@link AtomicReference} map of pre-parsed {@link CachedFlag} entries
 * - Provide lock-free {@link #evaluate(String, String, Map)} for the hot path
 * - Accept cache updates via {@link #updateAll(Map)}}, {@link #updateSingle(String, CachedFlag)}, {@link #remove(String)}
 *
 * Thread-safety: Copy-On-Write + CAS (updateAndGet) — zero lock contention.
 * No network dependencies — pure CPU computation, unit-testable.
 */
public class FeatureDataStore {

    /** Cache entry with pre-parsed FlagConfig. */
    public record CachedFlag(
            String flagKey,
            String name,
            FlagConfig flagConfig,
            int version
    ) {}

    private static final Duration REMOTE_EVAL_TIMEOUT = Duration.ofMillis(500);

    private final AtomicReference<Map<String, CachedFlag>> localCache =
            new AtomicReference<>(Collections.emptyMap());

    private final String appId;
    private final String evalServiceUrl;
    private final ObjectMapper objectMapper;

    public FeatureDataStore(String appId, String evalServiceUrl, ObjectMapper objectMapper) {
        this.appId = appId;
        this.evalServiceUrl = evalServiceUrl;
        this.objectMapper = objectMapper;
    }

    // ========================================================================
    //  Cache queries
    // ========================================================================

    public CachedFlag get(String flagKey) {
        return localCache.get().get(flagKey);
    }

    public int size() {
        return localCache.get().size();
    }

    // ========================================================================
    //  Evaluation — lock-free hot path
    // ========================================================================

    /**
     * Evaluate a flag from the local cache.
     *
     * @return EvaluateResponse if cache hit, null if cache miss
     */
    public EvaluateResponse evaluate(String flagKey, String userId,
                                      Map<String, String> attributes) {
        CachedFlag cached = localCache.get().get(flagKey);
        if (cached == null) return null;

        FlagConfig config = cached.flagConfig();
        if (config == null) return null;

        EvaluationContext ctx = EvaluationContext.builder()
                .userId(userId)
                .attributes(attributes)
                .build();

        return RuleEngine.evaluate(config, ctx);
    }

    // ========================================================================
    //  Cache mutations (Copy-On-Write + CAS)
    // ========================================================================

    /** Atomically replace the entire cache (full snapshot load). */
    public void updateAll(Map<String, CachedFlag> newCache) {
        localCache.set(Collections.unmodifiableMap(newCache));
    }

    /** Atomically add or update a single flag (SSE CREATE/UPDATE). */
    public void updateSingle(String flagKey, CachedFlag entry) {
        localCache.updateAndGet(current -> {
            Map<String, CachedFlag> updated = new HashMap<>(current);
            updated.put(flagKey, entry);
            return Collections.unmodifiableMap(updated);
        });
    }

    /** Atomically add or update a single flag via CAS merge (SSE CREATE/UPDATE with partial update). */
    public void mergeSingle(String flagKey, java.util.function.Function<CachedFlag, CachedFlag> merger) {
        localCache.updateAndGet(current -> {
            CachedFlag existing = current.get(flagKey);
            CachedFlag updated = merger.apply(existing);
            if (updated == null) return current;
            Map<String, CachedFlag> newMap = new HashMap<>(current);
            newMap.put(flagKey, updated);
            return Collections.unmodifiableMap(newMap);
        });
    }

    /** Atomically remove a single flag (SSE DELETE). */
    public void remove(String flagKey) {
        localCache.updateAndGet(current -> {
            if (!current.containsKey(flagKey)) return current;
            Map<String, CachedFlag> updated = new HashMap<>(current);
            updated.remove(flagKey);
            return Collections.unmodifiableMap(updated);
        });
    }

    /** Clear all cache (shutdown). */
    public void clear() {
        localCache.set(Collections.emptyMap());
    }
}
