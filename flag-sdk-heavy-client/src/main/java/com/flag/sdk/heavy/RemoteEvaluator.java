package com.flag.sdk.heavy;

import java.util.Map;

/**
 * Abstraction over remote EvalService HTTP calls — enables mock injection in unit tests.
 *
 * <p>Default implementation: {@link WebClientRemoteEvaluator}.
 * Tests can substitute with Mockito mock.</p>
 */
public interface RemoteEvaluator {

    /**
     * Evaluate a flag remotely via the EvalService.
     *
     * @param appId     application identifier
     * @param flagKey   feature flag key
     * @param userId    end-user identifier
     * @param attributes optional user attributes
     * @return {@code true} if the flag is enabled; {@code false} on remote failure
     */
    boolean evaluate(String appId, String flagKey, String userId, Map<String, String> attributes);
}
