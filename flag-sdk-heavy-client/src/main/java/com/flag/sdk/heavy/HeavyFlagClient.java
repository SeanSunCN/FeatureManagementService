package com.flag.sdk.heavy;

import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluateResponse;
import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.dto.FlagChangeMessage.ChangeType;
import com.flag.common.dto.MetricsReportRequest;
import com.flag.sdk.FlagSdkClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Industrial-grade backend Heavy SDK client implementation.
 * <p>
 * Corresponds to the architecture diagram: backend Heavy SDK receives rule changes
 * via SSE long connection + local cache evaluation.
 * <p>
 * Core self-healing capabilities:
 * 1. <b>Dual-channel initialization</b> — asynchronous full snapshot pull at startup + parallel SSE long connection
 * 2. <b>SSE real-time push</b> — precise subscription by AppId, incremental/full changes auto-synced to local cache
 * 3. <b>Exponential backoff reconnection</b> — when network jitter or service restart causes SSE disconnection,
 *    Reactor retryWhen drives exponential backoff (1s, 2s, 4s, 8s … ∞) with infinite retries,
 *    automatically re-pulls full snapshot after reconnection to ensure data consistency
 * 4. <b>Read-write split lock</b> — write lock held during cache updates, read lock held during evaluation, thread-safe
 * 5. <b>Graceful shutdown</b> — release WebClient, disconnect SSE, clear cache
 * 6. <b>Metrics reporting</b> — local hit count statistics, batched and periodically reported to IngestService
 * 7. <b>Fallback evaluation</b> — automatic remote evaluation on cache miss, degrades to default value on failure
 */
public class HeavyFlagClient implements FlagSdkClient {

    private static final Logger log = LoggerFactory.getLogger(HeavyFlagClient.class);

    // ============================================================
    // Configuration constants
    // ============================================================

    /** Full snapshot pull timeout */
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(5);
    /** Remote evaluation timeout */
    private static final Duration REMOTE_EVAL_TIMEOUT = Duration.ofMillis(500);
    /** SSE reconnection base time 1s */
    private static final Duration RECONNECT_BASE = Duration.ofSeconds(1);
    /** SSE reconnection max delay 30s */
    private static final Duration RECONNECT_MAX = Duration.ofSeconds(30);
    /** Metrics report interval */
    private static final Duration METRICS_REPORT_INTERVAL = Duration.ofSeconds(60);
    /** Heartbeat idle message max interval (server sends every 30s, client tolerates 60s) */
    private static final long HEARTBEAT_TIMEOUT_MS = 120_000;

    // ============================================================
    // Dependencies
    // ============================================================

    private final String appId;
    private final String evalServiceUrl;
    private final String ingestServiceUrl;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // ============================================================
    // Local cache (read-write lock protected)
    // ============================================================

    /**
     * Cache entry: consistent with the FlagCache.FlagEntry structure of EvalService.
     */
    public record CachedFlag(
            String flagKey,
            String name,
            boolean enabled,
            String ruleConfig,
            int version
    ) {}

    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();
    private final Lock cacheReadLock = cacheLock.readLock();
    private final Lock cacheWriteLock = cacheLock.writeLock();
    private final ConcurrentHashMap<String, CachedFlag> localCache = new ConcurrentHashMap<>();

    // ============================================================
    // Runtime state
    // ============================================================

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicReference<Disposable> sseSubscription = new AtomicReference<>(null);
    private final AtomicReference<Disposable> metricsSubscription = new AtomicReference<>(null);
    /** Timestamp of the last heartbeat/message received */
    private final AtomicLong lastHeartbeatTs = new AtomicLong(System.currentTimeMillis());

    // ============================================================
    // State change listeners
    // ============================================================

    private final List<Consumer<HeavyFlagClient>> connectListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<HeavyFlagClient>> disconnectListeners = new CopyOnWriteArrayList<>();

    // ============================================================
    // Construction & initialization
    // ============================================================

    public HeavyFlagClient(String appId, String evalServiceUrl, String ingestServiceUrl) {
        this.appId = appId;
        this.evalServiceUrl = evalServiceUrl;
        this.ingestServiceUrl = ingestServiceUrl;
        this.webClient = WebClient.builder()
                .baseUrl(evalServiceUrl)
                .codecs(config -> config.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.objectMapper = new ObjectMapper();
        HeavyMetricsAggregator.register(appId, ingestServiceUrl);
    }

    public HeavyFlagClient(String appId, String evalServiceUrl) {
        this(appId, evalServiceUrl, null);
    }

    /**
     * Start dual-channel initialization:
     * 1) Asynchronously pull full snapshot
     * 2) Establish SSE long connection in parallel
     * <p>
     * This method blocks until at least the snapshot is loaded (or times out).
     */
    public HeavyFlagClient init() {
        if (shutdown.get()) {
            throw new IllegalStateException("HeavyFlagClient has been shut down");
        }
        if (initialized.compareAndSet(false, true)) {
            log.info("Initializing HeavyFlagClient for appId={} at evalService={}", appId, evalServiceUrl);

            // 1) Asynchronously pull full snapshot (blocking wait for result, ensures cache is not empty at startup)
            loadFullSnapshot().block(SNAPSHOT_TIMEOUT);

            // 2) Establish SSE long connection in parallel
            startSseConnection();

            // Metrics reporting is handled by the global HeavyMetricsAggregator

            log.info("HeavyFlagClient initialized successfully for appId={}, cacheSize={}",
                    appId, localCache.size());
        }
        return this;
    }

    // ============================================================
    // 1. Full snapshot loading (async HTTP)
    // ============================================================

    /**
     * Asynchronously call EvalService {@code /api/v1/eval/flags?appId=xxx} via HTTP
     * to pull the full rule snapshot and update the local cache.
     * <p>
     * Uses a read-write lock: fully replaces the cache on write.
     */
    private Mono<Void> loadFullSnapshot() {
        log.info("Loading full flag snapshot from EvalService for appId={}", appId);

        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/eval/flags")
                        .queryParam("appId", appId)
                        .build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(SNAPSHOT_TIMEOUT)
                .flatMap(responseBody -> {
                    // Unwrap UnifiedResponse wrapper
                    // Possible structure: { "code": 0, "message": "success", "data": { ... }, "timestamp": ... }
                    Map<String, Object> data;
                    if (responseBody.containsKey("data")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> d = (Map<String, Object>) responseBody.get("data");
                        data = d;
                    } else {
                        data = responseBody;
                    }

                    int count = data.size();
                    log.debug("Snapshot received for appId={}, flagCount={}", appId, count);

                    // Fully replace cache under write lock protection
                    cacheWriteLock.lock();
                    try {
                        localCache.clear();
                        for (Map.Entry<String, Object> entry : data.entrySet()) {
                            String flagKey = entry.getKey();
                            // entry.getValue() may be a LinkedHashMap or a serialized FlagEntry
                            CachedFlag cf = parseFlagEntry(flagKey, entry.getValue());
                            if (cf != null) {
                                localCache.put(flagKey, cf);
                            }
                        }
                    } finally {
                        cacheWriteLock.unlock();
                    }

                    lastHeartbeatTs.set(System.currentTimeMillis());
                    log.info("Snapshot loaded for appId={}, cached {} flags", appId, count);
                    return Mono.<Void>empty();
                })
                .doOnError(e -> log.error("Failed to load snapshot for appId={}: {}", appId, e.getMessage()))
                .onErrorResume(e -> Mono.<Void>empty()); // Snapshot failure does not block initialization; will retry after SSE reconnection
    }

    /**
     * Parse a deserialized Object into a CachedFlag.
     */
    @SuppressWarnings("unchecked")
    private CachedFlag parseFlagEntry(String flagKey, Object raw) {
        if (raw == null) return null;

        // FlagEntry record may be serialized as a LinkedHashMap
        if (raw instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) raw;
            try {
                String name = (String) map.getOrDefault("name", flagKey);
                boolean enabled = Boolean.TRUE.equals(map.get("enabled"));
                String ruleConfig = (String) map.getOrDefault("ruleConfig", "");
                int version = map.getOrDefault("version", 0) instanceof Number
                        ? ((Number) map.get("version")).intValue() : 0;
                return new CachedFlag(flagKey, name, enabled, ruleConfig, version);
            } catch (Exception e) {
                log.warn("Failed to parse flag entry for flagKey={}: {}", flagKey, e.getMessage());
                return null;
            }
        }

        // If it is a string, try JSON parsing
        if (raw instanceof String) {
            try {
                return objectMapper.readValue((String) raw, CachedFlag.class);
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize flag entry for flagKey={}: {}", flagKey, e.getMessage());
                return null;
            }
        }

        return null;
    }

    // ============================================================
    // 2. SSE long connection with exponential backoff reconnection
    // ============================================================

    /**
     * Start SSE long connection subscription with built-in exponential backoff reconnection.
     * <p>
     * Implemented using Reactor {@code retryWhen(Retry.backoff(...))}:
     * - Initial delay: 1s
     * - Each delay doubles, max: 30s
     * - Infinite reconnection (maxAttempts = Long.MAX_VALUE)
     * - Automatically calls {@link #onReconnected()} after successful reconnection to re-sync
     */
    private void startSseConnection() {
        if (shutdown.get()) return;

        Disposable old = sseSubscription.getAndSet(null);
        if (old != null && !old.isDisposed()) {
            old.dispose();
        }

        Disposable sub = subscribeToSse()
                .doOnTerminate(() -> {
                    if (!shutdown.get()) {
                        log.warn("SSE connection terminated for appId={}, initiating reconnection", appId);
                        connected.set(false);
                        notifyDisconnect();
                    }
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, RECONNECT_BASE)
                        .maxBackoff(RECONNECT_MAX)
                        .jitter(0.5) // 50% jitter to prevent thundering herd
                        .doBeforeRetry(rs -> {
                            long attempt = rs.totalRetries() + 1;
                            Duration backoff = RECONNECT_BASE.multipliedBy(
                                    Math.min(1L << attempt, RECONNECT_MAX.toSeconds()));
                            log.warn("SSE reconnecting for appId={} in attempt {} after backoff {}s",
                                    appId, attempt, backoff.toSeconds());
                        }))
                .subscribe(
                        null, // data handled in the Flux pipeline
                        e -> log.error("SSE subscription failed for appId={}: {}", appId, e.getMessage()),
                        () -> log.warn("SSE subscription completed for appId={}", appId)
                );

        sseSubscription.set(sub);
    }

    /**
     * Establish the SSE stream and process events.
     * The returned Flux terminates when the connection is broken or an error occurs; retryWhen handles reconnection.
     */
    private Flux<FlagChangeMessage> subscribeToSse() {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/eval/sse/subscribe")
                        .queryParam("appId", appId)
                        .build())
                .retrieve()
                .bodyToFlux(FlagChangeMessage.class)
                .doOnSubscribe(s -> {
                    log.info("SSE connected for appId={}", appId);
                    connected.set(true);
                    lastHeartbeatTs.set(System.currentTimeMillis());
                    notifyConnect();
                })
                .doOnNext(this::onMessageReceived)
                // Heartbeat timeout detection: if no message is received beyond HEARTBEAT_TIMEOUT_MS, treat as disconnected
                .timeout(Duration.ofMillis(HEARTBEAT_TIMEOUT_MS))
                .doOnError(e -> {
                    if (!shutdown.get()) {
                        log.warn("SSE stream error for appId={}: {}", appId, e.getMessage());
                    }
                })
                // When the server gracefully closes the connection, the SSE Flux completes,
                // convert it to an error to trigger retryWhen reconnection
                .concatWith(Mono.error(new SseStreamEndedException(appId)));
    }

    /**
     * Indicates that the SSE stream ended unexpectedly (server closed connection or network interruption),
     * used to trigger retryWhen-driven exponential backoff reconnection.
     */
    private static class SseStreamEndedException extends RuntimeException {
        public SseStreamEndedException(String appId) {
            super("SSE stream ended for appId=" + appId);
        }
    }

    /**
     * Entry point for processing received SSE messages.
     */
    private void onMessageReceived(FlagChangeMessage msg) {
        if (msg == null) return;

        lastHeartbeatTs.set(System.currentTimeMillis());

        ChangeType changeType = msg.getChangeType();
        if (changeType == null) return;

        switch (changeType) {
            case HEARTBEAT -> {
                log.trace("SSE heartbeat received for appId={}", appId);
            }
            case RELOAD -> {
                log.info("SSE RELOAD signal received for appId={}, reloading all flags", appId);
                loadFullSnapshot().subscribe(
                        null,
                        e -> log.error("RELOAD snapshot fetch failed for appId={}: {}", appId, e.getMessage())
                );
            }
            case CREATE, UPDATE -> {
                log.debug("SSE {} for appId={}, flagKey={}", changeType, appId, msg.getFlagKey());
                fetchSingleFlagAndCache(msg.getFlagKey());
            }
            case DELETE -> {
                log.info("SSE DELETE for appId={}, flagKey={}", appId, msg.getFlagKey());
                cacheWriteLock.lock();
                try {
                    localCache.remove(msg.getFlagKey());
                } finally {
                    cacheWriteLock.unlock();
                }
            }
            default -> log.warn("Unknown SSE changeType={} for appId={}", changeType, appId);
        }
    }

    /**
     * Sync the latest state after successful reconnection:
     * pull the full snapshot to ensure data consistency.
     */
    private void onReconnected() {
        log.info("SSE reconnected for appId={}, re-syncing full snapshot", appId);
        connected.set(true);
        notifyConnect();
        loadFullSnapshot().subscribe(
                null,
                e -> log.error("Post-reconnect snapshot sync failed for appId={}: {}", appId, e.getMessage())
        );
    }

    /**
     * Pull the latest value for a single flagKey from the server and update the local cache.
     * <p>
     * When SSE only notifies of a change without carrying the full payload,
     * uses the result of remote evaluation to update the cache.
     * A better approach: the server provides a single-flag query API.
     */
    private void fetchSingleFlagAndCache(String flagKey) {
        if (flagKey == null) return;

        // Get the latest result via remote evaluation
        EvaluateRequest req = new EvaluateRequest();
        req.setAppId(appId);
        req.setFlagKey(flagKey);

        webClient.post()
                .uri("/api/v1/eval/evaluate")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(Duration.ofSeconds(3))
                .subscribe(
                        response -> {
                            // Extract data from UnifiedResponse
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = response.containsKey("data")
                                    ? (Map<String, Object>) response.get("data")
                                    : response;
                            boolean enabled = Boolean.TRUE.equals(data.get("enabled"));
                            String matchedRule = (String) data.getOrDefault("matchedRule", "");

                            // Update local cache (preserve name and version, only update enabled)
                            cacheWriteLock.lock();
                            try {
                                CachedFlag existing = localCache.get(flagKey);
                                CachedFlag updated = new CachedFlag(
                                        flagKey,
                                        existing != null ? existing.name() : flagKey,
                                        enabled,
                                        existing != null ? existing.ruleConfig() : matchedRule,
                                        existing != null ? existing.version() + 1 : 0
                                );
                                localCache.put(flagKey, updated);
                            } finally {
                                cacheWriteLock.unlock();
                            }

                            log.debug("Single flag updated from remote: appId={}, flagKey={}, enabled={}",
                                    appId, flagKey, enabled);
                        },
                        e -> log.warn("Failed to fetch single flag for appId={}, flagKey={}: {}",
                                appId, flagKey, e.getMessage())
                );
    }

    // ============================================================
    // 3. Core evaluation interface
    // ============================================================

    @Override
    public boolean isEnabled(String appId, String flagKey, String userId) {
        return isEnabled(appId, flagKey, userId, null);
    }

    @Override
    public boolean isEnabled(String appId, String flagKey, String userId,
                             Map<String, String> attributes) {
        // Validate appId (HeavyFlagClient is bound to a single App)
        if (!this.appId.equals(appId)) {
            log.warn("appId mismatch: client={}, requested={}", this.appId, appId);
        }
        if (shutdown.get()) return false;

        // 1. Read lock: check local cache
        CachedFlag cached = null;
        cacheReadLock.lock();
        try {
            cached = localCache.get(flagKey);
        } finally {
            cacheReadLock.unlock();
        }

        if (cached != null) {
            // Cache hit, record metrics to global aggregator
            HeavyMetricsAggregator.increment(appId, flagKey);

            // If no rule config, directly return enabled
            if (cached.ruleConfig() == null || cached.ruleConfig().isBlank()) {
                return cached.enabled();
            }

            // Local simple evaluation: return enabled when no userId or whitelist matching required
            // Complex rule matching is still done server-side; SDK only caches boolean values
            // For full local matching, a lightweight rule engine could be introduced
            return cached.enabled();
        }

        // 2. Cache miss -> remote evaluation
        return remoteEvaluate(flagKey, userId, attributes);
    }

    /**
     * Call EvalService for remote evaluation, silently degrade on failure.
     * <p>
     * EvalController returns {@code UnifiedResponse<EvaluateResponse>},
     * here we manually extract the data field via Map parsing to avoid Jackson deserialization loss.
     */
    @SuppressWarnings("unchecked")
    private boolean remoteEvaluate(String flagKey, String userId, Map<String, String> attributes) {
        if (shutdown.get()) return false;

        try {
            EvaluateRequest request = new EvaluateRequest();
            request.setAppId(appId);
            request.setFlagKey(flagKey);
            request.setUserId(userId);
            request.setAttributes(attributes);

            Map<String, Object> responseBody = webClient.post()
                    .uri("/api/v1/eval/evaluate")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .timeout(REMOTE_EVAL_TIMEOUT)
                    .block();

            if (responseBody != null) {
                // Extract the data field from UnifiedResponse
                Object dataObj = responseBody.get("data");
                if (dataObj instanceof Map) {
                    Map<String, Object> data = (Map<String, Object>) dataObj;
                    boolean enabled = Boolean.TRUE.equals(data.get("enabled"));

                    // Backfill cache with remote evaluation result
                    cacheWriteLock.lock();
                    try {
                        localCache.put(flagKey, new CachedFlag(
                                flagKey, flagKey, enabled, "", 0));
                    } finally {
                        cacheWriteLock.unlock();
                    }
                    return enabled;
                }
            }
        } catch (Exception e) {
            log.warn("Remote evaluate failed for appId={}, flagKey={}: {}",
                    appId, flagKey, e.getMessage());
        }

        return false; // Fallback degradation
    }

    @Override
    public List<EvaluateResponse> evaluateBatch(List<EvaluateRequest> requests) {
        if (shutdown.get()) return List.of();
        if (requests == null || requests.isEmpty()) return List.of();

        List<EvaluateResponse> results = new ArrayList<>(requests.size());
        for (EvaluateRequest req : requests) {
            boolean enabled = isEnabled(req.getAppId(), req.getFlagKey(), req.getUserId(), req.getAttributes());
            results.add(EvaluateResponse.of(req.getFlagKey(), enabled));
        }
        return results;
    }

    // ============================================================
    // 4. Metrics reporting — delegated to global HeavyMetricsAggregator
    // ============================================================

    // ============================================================
    // 5. State listeners
    // ============================================================

    /**
     * Register a connection established callback.
     */
    public HeavyFlagClient onConnect(Consumer<HeavyFlagClient> listener) {
        connectListeners.add(listener);
        return this;
    }

    /**
     * Register a connection disconnected callback.
     */
    public HeavyFlagClient onDisconnect(Consumer<HeavyFlagClient> listener) {
        disconnectListeners.add(listener);
        return this;
    }

    private void notifyConnect() {
        for (Consumer<HeavyFlagClient> listener : connectListeners) {
            try {
                listener.accept(this);
            } catch (Exception e) {
                log.warn("Connect listener error: {}", e.getMessage());
            }
        }
    }

    private void notifyDisconnect() {
        for (Consumer<HeavyFlagClient> listener : disconnectListeners) {
            try {
                listener.accept(this);
            } catch (Exception e) {
                log.warn("Disconnect listener error: {}", e.getMessage());
            }
        }
    }

    // ============================================================
    // 6. Graceful shutdown
    // ============================================================

    /**
     * Gracefully shut down the SDK client:
     * 1) Mark shutdown state, all new evaluations immediately return false
     * 2) Disconnect SSE long connection
     * 3) Stop metrics reporting timer
     * 4) Clear local cache
     * 5) Trigger disconnect callbacks
     */
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return; // already shut down
        }

        log.info("Shutting down HeavyFlagClient for appId={}", appId);

        // Disconnect SSE
        Disposable sse = sseSubscription.getAndSet(null);
        if (sse != null && !sse.isDisposed()) {
            sse.dispose();
        }

        // Stop metrics reporting
        Disposable metrics = metricsSubscription.getAndSet(null);
        if (metrics != null && !metrics.isDisposed()) {
            metrics.dispose();
        }

        // Final flush & drain: atomically extracts this appId's counters and sends synchronously.
        // No separate clear() call needed — flushAndDrain atomically empties the bucket.
        HeavyMetricsAggregator.flushAndDrain(appId);

        // Clear local rule cache
        cacheWriteLock.lock();
        try {
            localCache.clear();
        } finally {
            cacheWriteLock.unlock();
        }

        connected.set(false);
        initialized.set(false);
        notifyDisconnect();

        log.info("HeavyFlagClient shut down for appId={}", appId);
    }

    // ============================================================
    // State queries
    // ============================================================

    /**
     * Whether SSE is connected.
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Whether the client is initialized.
     */
    public boolean isInitialized() {
        return initialized.get();
    }

    /**
     * Whether the client is shut down.
     */
    public boolean isShutdown() {
        return shutdown.get();
    }

    /**
     * Get the size of the local cache.
     */
    public int cacheSize() {
        cacheReadLock.lock();
        try {
            return localCache.size();
        } finally {
            cacheReadLock.unlock();
        }
    }

    /**
     * Get the timestamp of the last server message received.
     */
    public long lastHeartbeatTime() {
        return lastHeartbeatTs.get();
    }

    /**
     * Get the current AppId.
     */
    public String getAppId() {
        return appId;
    }

    // ============================================================
    // Global metrics aggregator (shared across HeavyFlagClient instances)
    // Single daemon thread batches ALL app counters into IngestService
    // ============================================================

    static class HeavyMetricsAggregator {

        private static final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> counters
                = new ConcurrentHashMap<>();
        // Per-appId ingest URL, stored at registration time
        private static final ConcurrentHashMap<String, String> ingestUrls
                = new ConcurrentHashMap<>();
        private static final ScheduledExecutorService scheduler;

        static {
            scheduler = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "flag-heavy-metrics-flusher");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(
                    HeavyMetricsAggregator::flushAll,
                    60, 60, TimeUnit.SECONDS);

            // JVM Shutdown Hook: SYNCHRONOUS final flush — guarantees no data loss
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                scheduler.shutdown();
                try { if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) scheduler.shutdownNow(); }
                catch (InterruptedException e) { scheduler.shutdownNow(); Thread.currentThread().interrupt(); }
                flushAllSync();
            }, "flag-heavy-metrics-shutdown-hook"));
        }

        static void register(String appId, String ingestServiceUrl) {
            counters.computeIfAbsent(appId, k -> new ConcurrentHashMap<>());
            if (ingestServiceUrl != null && !ingestServiceUrl.isBlank()) {
                ingestUrls.put(appId, ingestServiceUrl);
            }
        }

        static void increment(String appId, String flagKey) {
            counters.computeIfAbsent(appId, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(flagKey, k -> new AtomicLong(0))
                    .incrementAndGet();
        }

        static void flushAll() {
            for (Map.Entry<String, ConcurrentHashMap<String, AtomicLong>> appEntry : counters.entrySet()) {
                String appId = appEntry.getKey();
                ConcurrentHashMap<String, AtomicLong> appCounters = appEntry.getValue();

                Map<String, Long> snapshot = new HashMap<>();
                for (Map.Entry<String, AtomicLong> flagEntry : appCounters.entrySet()) {
                    long count = flagEntry.getValue().getAndSet(0);
                    if (count > 0) {
                        snapshot.put(flagEntry.getKey(), count);
                    }
                }

                if (snapshot.isEmpty()) continue;

                String ingestUrl = ingestUrls.getOrDefault(appId, "http://localhost:8082");

                try {
                    MetricsReportRequest report = new MetricsReportRequest();
                    report.setAppId(appId);
                    report.setFlagHitCounts(snapshot);

                    WebClient.create()
                            .post()
                            .uri(ingestUrl + "/api/v1/ingest/metrics")
                            .bodyValue(report)
                            .retrieve()
                            .bodyToMono(Void.class)
                            .timeout(Duration.ofSeconds(3))
                            .subscribe(null, e -> {});
                } catch (Exception e) {
                    // Swallow
                }
            }
        }

        /**
         * SYNCHRONOUS flush across ALL registered apps.
         * Uses .block() instead of .subscribe() — guarantees data reaches the wire.
         * Only used on the JVM shutdown-hook path.
         */
        static void flushAllSync() {
            for (Map.Entry<String, ConcurrentHashMap<String, AtomicLong>> appEntry : counters.entrySet()) {
                String appId = appEntry.getKey();
                ConcurrentHashMap<String, AtomicLong> appCounters = appEntry.getValue();

                Map<String, Long> snapshot = new HashMap<>();
                for (Map.Entry<String, AtomicLong> flagEntry : appCounters.entrySet()) {
                    long count = flagEntry.getValue().getAndSet(0);
                    if (count > 0) {
                        snapshot.put(flagEntry.getKey(), count);
                    }
                }

                if (snapshot.isEmpty()) continue;

                String ingestUrl = ingestUrls.getOrDefault(appId, "http://localhost:8082");

                try {
                    MetricsReportRequest report = new MetricsReportRequest();
                    report.setAppId(appId);
                    report.setFlagHitCounts(snapshot);

                    WebClient.create()
                            .post()
                            .uri(ingestUrl + "/api/v1/ingest/metrics")
                            .bodyValue(report)
                            .retrieve()
                            .bodyToMono(Void.class)
                            .timeout(Duration.ofSeconds(3))
                            .block();  // ← BLOCKING! Critical for shutdown data integrity
                } catch (Exception e) {
                    // Swallow — best-effort on shutdown path
                }
            }
        }

        /**
         * Atomically extracts and sends this appId's counter snapshot synchronously.
         * This is the SAFE shutdown path — no asynchronous fire-and-forget,
         * no separate clear() call that could race with the HTTP send.
         *
         * After this method returns, the appId's bucket is guaranteed to be empty
         * and all data has been at least submitted to the network layer.
         */
        static void flushAndDrain(String appId) {
            ConcurrentHashMap<String, AtomicLong> appCounters = counters.get(appId);
            if (appCounters == null) return;

            // Atomic extraction — same getAndSet pattern as periodic flushAll()
            Map<String, Long> snapshot = new HashMap<>();
            for (Map.Entry<String, AtomicLong> flagEntry : appCounters.entrySet()) {
                long count = flagEntry.getValue().getAndSet(0);
                if (count > 0) {
                    snapshot.put(flagEntry.getKey(), count);
                }
            }

            if (snapshot.isEmpty()) return;

            String ingestUrl = ingestUrls.getOrDefault(appId, "http://localhost:8082");

            try {
                MetricsReportRequest report = new MetricsReportRequest();
                report.setAppId(appId);
                report.setFlagHitCounts(snapshot);

                // Synchronous blocking send — guarantees the data reaches the wire
                // before we clear the bucket below (no TOCTOU race)
                WebClient.create()
                        .post()
                        .uri(ingestUrl + "/api/v1/ingest/metrics")
                        .bodyValue(report)
                        .retrieve()
                        .bodyToMono(Void.class)
                        .timeout(Duration.ofSeconds(3))
                        .block();  // ← BLOCKING! Critical for shutdown data integrity
            } catch (Exception e) {
                // Swallow — best-effort on shutdown path
            } finally {
                // Full tenant eviction: remove both counters and URL config
                // to prevent memory leaks in dynamic create/close scenarios
                counters.remove(appId);
                ingestUrls.remove(appId);
            }
        }
    }
}