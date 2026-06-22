package com.flag.sdk.heavy;

import com.flag.common.dto.EvaluateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * Default {@link RemoteEvaluator} implementation backed by WebClient to EvalService.
 *
 * <p>Extracted from the original inline logic in {@link HeavyFlagClient#remoteEvaluate}
 * to enable mock injection in tests.</p>
 */
public class WebClientRemoteEvaluator implements RemoteEvaluator {

    private static final Logger log = LoggerFactory.getLogger(WebClientRemoteEvaluator.class);
    private static final Duration REMOTE_EVAL_TIMEOUT = Duration.ofMillis(500);

    private final String appId;
    private final WebClient webClient;

    public WebClientRemoteEvaluator(String appId, WebClient webClient) {
        this.appId = appId;
        this.webClient = webClient;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean evaluate(String appId, String flagKey, String userId,
                           Map<String, String> attributes) {
        try {
            EvaluateRequest req = new EvaluateRequest();
            req.setAppId(appId);
            req.setFlagKey(flagKey);
            req.setUserId(userId);
            req.setAttributes(attributes);

            Map<String, Object> response = webClient.post()
                    .uri("/api/v1/eval/evaluate")
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(REMOTE_EVAL_TIMEOUT)
                    .block();

            if (response != null && response.get("data") instanceof Map) {
                Map<String, Object> data = (Map<String, Object>) response.get("data");
                return Boolean.TRUE.equals(data.get("enabled"));
            }
        } catch (Exception e) {
            log.warn("Remote evaluate failed for appId={}, flagKey={}: {}",
                    appId, flagKey, e.getMessage());
        }
        return false;
    }
}
