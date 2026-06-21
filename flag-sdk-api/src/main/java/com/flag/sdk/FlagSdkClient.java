package com.flag.sdk;

import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluateResponse;

import java.util.List;
import java.util.Map;

/**
 * Feature Flag SDK client interface.
 * <p>
 * Corresponds to the SDK layer in the architecture diagram, defining the core behaviors shared by server-side SDK and client-side SDK.
 * <p>
 * Implementation approaches:
 * - Heavy SDK (backend microservice): receives real-time push via SSE long connection + local cache
 * - Light SDK (mobile/web): pulls full rule snapshots via CDN + local evaluation
 */
public interface FlagSdkClient {

    /**
     * Evaluate a single feature flag.
     *
     * @param appId     Application identifier
     * @param flagKey   Feature flag key
     * @param userId    Target user (optional, used for canary/whitelist matching)
     * @return Evaluation result
     */
    boolean isEnabled(String appId, String flagKey, String userId);

    /**
     * Evaluate a single feature flag with context.
     *
     * @param appId      Application identifier
     * @param flagKey    Feature flag key
     * @param userId     Target user (optional)
     * @param attributes Custom context attributes (used for attribute rule matching)
     * @return Evaluation result
     */
    boolean isEnabled(String appId, String flagKey, String userId, Map<String, String> attributes);

    /**
     * Batch evaluation.
     *
     * @param appId    Application identifier
     * @param requests List of evaluation requests
     * @return List of evaluation results
     */
    List<EvaluateResponse> evaluateBatch(String appId, List<EvaluateRequest> requests);
}