package com.flag.sdk.heavy;

import com.flag.common.dto.AuditLogEntry;
import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluateResponse;
import com.flag.sdk.FlagSdkClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    private final FeatureDataStore dataStore;
    private final SseStreamManager sseStream;
    private final RemoteEvaluator remoteEvaluator; // for remote fallback evaluation
    private final List<Consumer<HeavyFlagClient>> connectListeners = new ArrayList<>();
    private final List<Consumer<HeavyFlagClient>> disconnectListeners = new ArrayList<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /**
     * Public constructor — creates all internal dependencies automatically (production use).
     */
    public HeavyFlagClient(String appId, String evalServiceUrl, String ingestServiceUrl) {
        this(appId, evalServiceUrl, ingestServiceUrl,
                createDefaultDataStore(appId, evalServiceUrl));
    }

    public HeavyFlagClient(String appId, String evalServiceUrl) {
        this(appId, evalServiceUrl, null);
    }

    /**
     * Internal constructor — creates all dependencies from a shared dataStore.
     */
    private HeavyFlagClient(String appId, String evalServiceUrl, String ingestServiceUrl,
                    FeatureDataStore dataStore) {
        this(appId, ingestServiceUrl, dataStore,
                createDefaultSseStream(appId, evalServiceUrl, dataStore),
                createDefaultRemoteEvaluator(appId, evalServiceUrl));
    }

    /**
     * Package-private constructor — all dependencies injected by caller (used in tests).
     */
    HeavyFlagClient(String appId, String ingestServiceUrl,
                    FeatureDataStore dataStore,
                    SseStreamManager sseStream,
                    RemoteEvaluator remoteEvaluator) {
        this.appId = appId;
        this.ingestServiceUrl = ingestServiceUrl;
        this.dataStore = dataStore;
        this.sseStream = sseStream;
        this.remoteEvaluator = remoteEvaluator;

        this.sseStream.onConnect(flag -> {
            connected.set(flag);
            if (flag) notifyConnect();
            else notifyDisconnect();
        });

        HeavyMetricsAggregator.register(appId, ingestServiceUrl);
        HeavyAuditLogAggregator.register(appId, ingestServiceUrl);
    }

    // ========================================================================
    //  Default factory methods (production defaults)
    // ========================================================================

    private static FeatureDataStore createDefaultDataStore(String appId, String evalServiceUrl) {
        ObjectMapper mapper = new ObjectMapper();
        return new FeatureDataStore(appId, evalServiceUrl, mapper);
    }

    private static SseStreamManager createDefaultSseStream(String appId, String evalServiceUrl,
                                                           FeatureDataStore sharedDataStore) {
        ObjectMapper mapper = new ObjectMapper();
        FlagEntryParser parser = new FlagEntryParser(mapper);
        WebClient webClient = WebClient.builder()
                .baseUrl(evalServiceUrl)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        return new SseStreamManager(appId, webClient, sharedDataStore, parser);
    }

    private static RemoteEvaluator createDefaultRemoteEvaluator(String appId, String evalServiceUrl) {
        WebClient webClient = WebClient.builder()
                .baseUrl(evalServiceUrl)
                .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        return new WebClientRemoteEvaluator(appId, webClient);
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
        initialized.set(true);
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

        boolean enabled;
        String matchedRule;
        long evalCostNs;

        // 1. Local evaluation (lock-free)
        EvaluateResponse local = dataStore.evaluate(flagKey, userId, attributes);
        if (local != null) {
            enabled = local.isEnabled();
            matchedRule = local.getMatchedRuleName() != null ? local.getMatchedRuleName() : "local-eval";
            evalCostNs = local.getEvalCostNs();
            HeavyMetricsAggregator.increment(appId, flagKey);
        } else {
            // 2. Cache miss → remote evaluation
            enabled = remoteEvaluate(flagKey, userId, attributes);
            matchedRule = "remote-eval";
            evalCostNs = 0L;
        }

        // Report audit log
        AuditLogEntry entry = new AuditLogEntry();
        entry.setAppId(appId);
        entry.setFlagKey(flagKey);
        entry.setUserId(userId != null ? userId : "");
        entry.setEnabled(enabled);
        entry.setMatchedRule(matchedRule);
        entry.setEvalCostNs(evalCostNs);
        entry.setAttributesSnapshot(attributes != null ? new HashMap<>(attributes) : new HashMap<>());
        HeavyAuditLogAggregator.report(appId, entry);

        return enabled;
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
    public boolean isInitialized() { return initialized.get(); }
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

        // 2. Flush remaining metrics and audit logs
        HeavyMetricsAggregator.flushAndDrain(appId);
        HeavyAuditLogAggregator.flushAndDrain(appId);

        // 3. Clear local rules
        dataStore.clear();

        connected.set(false);
        initialized.set(false);
        notifyDisconnect();
        log.info("HeavyFlagClient shut down for appId={}", appId);
    }

    // ========================================================================
    //  Remote fallback
    // ========================================================================

    private boolean remoteEvaluate(String flagKey, String userId,
                                    Map<String, String> attributes) {
        if (shutdown.get()) return false;
        // ⚠️ Do NOT backfill cache — see design doc
        return remoteEvaluator.evaluate(appId, flagKey, userId, attributes);
    }
}
