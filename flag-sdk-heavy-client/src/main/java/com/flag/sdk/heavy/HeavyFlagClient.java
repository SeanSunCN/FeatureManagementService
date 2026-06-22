package com.flag.sdk.heavy;

import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluateResponse;
import com.flag.common.dto.EvaluationContext;
import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.dto.MetricsReportRequest;
import com.flag.sdk.FlagSdkClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Heavy SDK client facade.
 *
 * Coordinates three internal components:
 * - {@link FeatureDataStore} — local cache and pure evaluation
 * - {@link SseStreamManager} — SSE long connection and real-time sync
 * - {@link HeavyMetricsAggregator} — global metrics reporting
 *
 * The facade handles lifecycle (init, shutdown), connects SSE callbacks
 * to the data store, and provides the {@link FlagSdkClient} interface.
 */
public class HeavyFlagClient implements FlagSdkClient {

    private static final Logger log = LoggerFactory.getLogger(HeavyFlagClient.class);

    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REMOTE_EVAL_TIMEOUT = Duration.ofMillis(500);

    private final String appId;
    private final String ingestServiceUrl;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private final FeatureDataStore dataStore;
    private final SseStreamManager sseStream;
    private final WebClient webClient; // for remote fallback evaluation
    private final List<Consumer<HeavyFlagClient>> connectListeners = new ArrayList<>();
    private final List<Consumer<HeavyFlagClient>> disconnectListeners = new ArrayList<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    public HeavyFlagClient(String appId, String evalServiceUrl, String ingestServiceUrl) {
        this.appId = appId;
        this.ingestServiceUrl = ingestServiceUrl;

        ObjectMapper mapper = new ObjectMapper();
        FlagEntryParser parser = new FlagEntryParser(mapper);
        this.dataStore = new FeatureDataStore(appId, evalServiceUrl, mapper);

        // SSE connection uses the same base URL
        WebClient webClient = WebClient.builder()
                .baseUrl(evalServiceUrl)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.webClient = webClient;

        this.sseStream = new SseStreamManager(appId, webClient, dataStore, parser);
        this.sseStream.onConnect(flag -> {
            connected.set(flag);
            if (flag) notifyConnect();
            else notifyDisconnect();
        });

        HeavyMetricsAggregator.register(appId, ingestServiceUrl);
    }

    public HeavyFlagClient(String appId, String evalServiceUrl) {
        this(appId, evalServiceUrl, null);
    }

    // ========================================================================
    //  Initialization
    // ========================================================================

    /**
     * Start dual-channel initialization: snapshot load + SSE connection.
     */
    public HeavyFlagClient init() {
        if (shutdown.get()) {
            throw new IllegalStateException("HeavyFlagClient has been shut down");
        }
        log.info("Initializing HeavyFlagClient for appId={}", appId);
        sseStream.start();
        log.info("HeavyFlagClient initialized, cacheSize={}", dataStore.size());
        return this;
    }

    // ========================================================================
    //  FlagSdkClient interface
    // ========================================================================

    @Override
    public boolean isEnabled(String appId, String flagKey, String userId) {
        return isEnabled(appId, flagKey, userId, null);
    }

    @Override
    public boolean isEnabled(String appId, String flagKey, String userId,
                             Map<String, String> attributes) {
        if (shutdown.get()) return false;
        if (!this.appId.equals(appId)) {
            log.warn("appId mismatch: client={}, requested={}", this.appId, appId);
        }

        // 1. Local evaluation (lock-free)
        EvaluateResponse local = dataStore.evaluate(flagKey, userId, attributes);
        if (local != null) {
            HeavyMetricsAggregator.increment(appId, flagKey);
            return local.isEnabled();
        }

        // 2. Cache miss → remote evaluation
        return remoteEvaluate(flagKey, userId, attributes);
    }

    @Override
    public List<EvaluateResponse> evaluateBatch(List<EvaluateRequest> requests) {
        if (shutdown.get()) return List.of();
        if (requests == null || requests.isEmpty()) return List.of();

        List<EvaluateResponse> results = new ArrayList<>(requests.size());
        for (EvaluateRequest req : requests) {
            results.add(EvaluateResponse.of(req.getFlagKey(),
                    isEnabled(req.getAppId(), req.getFlagKey(),
                            req.getUserId(), req.getAttributes())));
        }
        return results;
    }

    // ========================================================================
    //  State queries
    // ========================================================================

    public boolean isConnected() { return connected.get(); }
    public boolean isInitialized() { return true; } // init() is blocking
    public boolean isShutdown() { return shutdown.get(); }
    public int cacheSize() { return dataStore.size(); }
    public long lastHeartbeatTime() { return sseStream.lastHeartbeatTime(); }
    public String getAppId() { return appId; }

    // ========================================================================
    //  State listeners
    // ========================================================================

    public HeavyFlagClient onConnect(Consumer<HeavyFlagClient> listener) {
        connectListeners.add(listener);
        return this;
    }

    public HeavyFlagClient onDisconnect(Consumer<HeavyFlagClient> listener) {
        disconnectListeners.add(listener);
        return this;
    }

    private void notifyConnect() {
        for (Consumer<HeavyFlagClient> l : connectListeners) {
            try { l.accept(this); } catch (Exception e) { log.warn("Connect listener error", e); }
        }
    }

    private void notifyDisconnect() {
        for (Consumer<HeavyFlagClient> l : disconnectListeners) {
            try { l.accept(this); } catch (Exception e) { log.warn("Disconnect listener error", e); }
        }
    }

    // ========================================================================
    //  Shutdown
    // ========================================================================

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        log.info("Shutting down HeavyFlagClient for appId={}", appId);

        // 1. Disconnect SSE (stops all incoming updates)
        sseStream.shutdownNow();

        // 2. Flush remaining metrics
        HeavyMetricsAggregator.flushAndDrain(appId);

        // 3. Clear local rules
        dataStore.clear();

        connected.set(false);
        notifyDisconnect();
        log.info("HeavyFlagClient shut down for appId={}", appId);
    }

    // ========================================================================
    //  Remote fallback
    // ========================================================================

    @SuppressWarnings("unchecked")
    private boolean remoteEvaluate(String flagKey, String userId,
                                    Map<String, String> attributes) {
        if (shutdown.get()) return false;

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
                boolean enabled = Boolean.TRUE.equals(data.get("enabled"));
                // ⚠️ Do NOT backfill cache — see design doc
                return enabled;
            }
        } catch (Exception e) {
            log.warn("Remote evaluate failed for appId={}, flagKey={}: {}",
                    appId, flagKey, e.getMessage());
        }
        return false;
    }
}
