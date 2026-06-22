package com.flag.sdk.heavy;

import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluateResponse;
import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.dto.FlagChangeMessage.ChangeType;
import com.flag.common.model.FlagConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * SSE long-connection manager with exponential backoff reconnection.
 *
 * Responsibilities:
 * - Establish and maintain SSE connection to EvalService
 * - Heartbeat timeout detection (120s)
 * - Exponential backoff reconnection (1s → 30s, infinite, 50% jitter)
 * - Callback to FeatureDataStore on configuration change events
 */
public class SseStreamManager {

    private static final Logger log = LoggerFactory.getLogger(SseStreamManager.class);

    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RECONNECT_BASE = Duration.ofSeconds(1);
    private static final Duration RECONNECT_MAX = Duration.ofSeconds(30);
    private static final long HEARTBEAT_TIMEOUT_MS = 120_000;

    private final String appId;
    private final WebClient webClient;
    private final FeatureDataStore dataStore;
    private final AtomicReference<Disposable> sseSubscription = new AtomicReference<>(null);
    private final AtomicLong lastHeartbeatTs = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final List<Consumer<Boolean>> connectListeners = new CopyOnWriteArrayList<>();

    /** Used internally to parse FlagEntry JSON from the snapshot response. */
    private final FlagEntryParser parser;

    public SseStreamManager(String appId, WebClient webClient, FeatureDataStore dataStore,
                            FlagEntryParser parser) {
        this.appId = appId;
        this.webClient = webClient;
        this.dataStore = dataStore;
        this.parser = parser;
    }

    // ========================================================================
    //  Lifecycle
    // ========================================================================

    /**
     * Initialize dual-channel:
     * 1) Blocking full snapshot pull
     * 2) SSE long connection (non-blocking)
     */
    public void start() {
        loadFullSnapshot().block(SNAPSHOT_TIMEOUT);
        startSseConnection();
    }

    public void shutdownNow() {
        if (!shutdown.compareAndSet(false, true)) return;
        Disposable sub = sseSubscription.getAndSet(null);
        if (sub != null && !sub.isDisposed()) {
            sub.dispose();
        }
        connected.set(false);
    }

    // ========================================================================
    //  State queries
    // ========================================================================

    public boolean isConnected() { return connected.get(); }
    public long lastHeartbeatTime() { return lastHeartbeatTs.get(); }

    // ========================================================================
    //  Listeners
    // ========================================================================

    public void onConnect(Consumer<Boolean> listener) {
        connectListeners.add(listener);
    }

    // ========================================================================
    //  1. Full snapshot loading
    // ========================================================================

    private Mono<Void> loadFullSnapshot() {
        log.info("Loading full flag snapshot from EvalService for appId={}", appId);

        return webClient.get()
                .uri(ub -> ub.path("/api/v1/eval/flags").queryParam("appId", appId).build())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .timeout(SNAPSHOT_TIMEOUT)
                .flatMap(responseBody -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = responseBody.containsKey("data")
                            ? (Map<String, Object>) responseBody.get("data")
                            : responseBody;

                    Map<String, FeatureDataStore.CachedFlag> newCache = new HashMap<>();
                    for (Map.Entry<String, Object> entry : data.entrySet()) {
                        FeatureDataStore.CachedFlag cf = parser.parse(entry.getKey(), entry.getValue());
                        if (cf != null) {
                            newCache.put(entry.getKey(), cf);
                        }
                    }
                    dataStore.updateAll(newCache);

                    lastHeartbeatTs.set(System.currentTimeMillis());
                    log.info("Snapshot loaded for appId={}, cached {} flags", appId, newCache.size());
                    return Mono.<Void>empty();
                })
                .doOnError(e -> log.error("Failed to load snapshot for appId={}: {}", appId, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    // ========================================================================
    //  2. SSE connection with exponential backoff
    // ========================================================================

    private void startSseConnection() {
        if (shutdown.get()) return;

        Disposable old = sseSubscription.getAndSet(null);
        if (old != null && !old.isDisposed()) old.dispose();

        Disposable sub = subscribeToSse()
                .doOnTerminate(() -> {
                    if (!shutdown.get()) {
                        log.warn("SSE connection terminated for appId={}, initiating reconnection", appId);
                        connected.set(false);
                        notifyListeners(false);
                    }
                })
                .doAfterTerminate(() -> {
                    if (!shutdown.get()) onReconnected();
                })
                .retryWhen(Retry.backoff(Long.MAX_VALUE, RECONNECT_BASE)
                        .maxBackoff(RECONNECT_MAX)
                        .jitter(0.5)
                        .doBeforeRetry(rs -> {
                            long attempt = rs.totalRetries() + 1;
                            long backoff = Math.min(1L << attempt, RECONNECT_MAX.toSeconds());
                            log.warn("SSE reconnecting for appId={} attempt {} after {}s",
                                    appId, attempt, backoff);
                        }))
                .subscribe(
                        null,
                        e -> log.error("SSE subscription failed for appId={}: {}", appId, e.getMessage())
                );

        sseSubscription.set(sub);
    }

    private Flux<FlagChangeMessage> subscribeToSse() {
        return webClient.get()
                .uri(ub -> ub.path("/api/v1/eval/sse/subscribe")
                        .queryParam("appId", appId).build())
                .retrieve()
                .bodyToFlux(FlagChangeMessage.class)
                .doOnSubscribe(s -> {
                    log.info("SSE connected for appId={}", appId);
                    connected.set(true);
                    lastHeartbeatTs.set(System.currentTimeMillis());
                    notifyListeners(true);
                })
                .doOnNext(this::onMessageReceived)
                .timeout(Duration.ofMillis(HEARTBEAT_TIMEOUT_MS))
                .doOnError(e -> {
                    if (!shutdown.get()) {
                        log.warn("SSE stream error for appId={}: {}", appId, e.getMessage());
                    }
                })
                .concatWith(Mono.error(new SseStreamEndedException(appId)));
    }

    private void onMessageReceived(FlagChangeMessage msg) {
        if (msg == null || msg.getChangeType() == null) return;
        lastHeartbeatTs.set(System.currentTimeMillis());

        switch (msg.getChangeType()) {
            case HEARTBEAT -> log.trace("SSE heartbeat for appId={}", appId);

            case RELOAD -> {
                log.info("SSE RELOAD for appId={}", appId);
                loadFullSnapshot().subscribe(null,
                        e -> log.error("RELOAD snapshot failed: {}", e.getMessage()));
            }

            case CREATE, UPDATE -> {
                log.debug("SSE {} for appId={}, flagKey={}",
                        msg.getChangeType(), appId, msg.getFlagKey());
                fetchSingleFlagAndCache(msg.getFlagKey());
            }

            case DELETE -> {
                log.info("SSE DELETE for appId={}, flagKey={}", appId, msg.getFlagKey());
                dataStore.remove(msg.getFlagKey());
            }

            default -> log.warn("Unknown SSE changeType={} for appId={}",
                    msg.getChangeType(), appId);
        }
    }

    private void onReconnected() {
        log.info("SSE reconnected for appId={}, re-syncing full snapshot", appId);
        connected.set(true);
        notifyListeners(true);
        loadFullSnapshot().subscribe(null,
                e -> log.error("Post-reconnect snapshot failed: {}", e.getMessage()));
    }

    private void fetchSingleFlagAndCache(String flagKey) {
        if (flagKey == null) return;

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
                            @SuppressWarnings("unchecked")
                            Map<String, Object> data = response.containsKey("data")
                                    ? (Map<String, Object>) response.get("data")
                                    : response;
                            boolean enabled = Boolean.TRUE.equals(data.get("enabled"));

                            dataStore.mergeSingle(flagKey, existing -> {
                                String name = existing != null ? existing.name() : flagKey;
                                int version = existing != null ? existing.version() + 1 : 0;
                                FlagConfig existingConfig = existing != null ? existing.flagConfig() : null;
                                FlagConfig updatedConfig;
                                if (existingConfig != null) {
                                    updatedConfig = FlagConfig.builder()
                                            .flagKey(flagKey)
                                            .globalEnabled(enabled)
                                            .rules(existingConfig.getRules())
                                            .defaultServeValue(existingConfig.isDefaultServeValue())
                                            .build();
                                } else {
                                    updatedConfig = FlagConfig.builder()
                                            .flagKey(flagKey)
                                            .globalEnabled(enabled)
                                            .defaultServeValue(enabled)
                                            .build();
                                }
                                return new FeatureDataStore.CachedFlag(
                                        flagKey, name, updatedConfig, version);
                            });
                        },
                        e -> log.warn("Failed to fetch single flag for appId={}, flagKey={}: {}",
                                appId, flagKey, e.getMessage())
                );
    }

    private void notifyListeners(boolean connected) {
        for (Consumer<Boolean> listener : connectListeners) {
            try { listener.accept(connected); }
            catch (Exception e) { log.warn("Connect listener error: {}", e.getMessage()); }
        }
    }

    private static class SseStreamEndedException extends RuntimeException {
        SseStreamEndedException(String appId) {
            super("SSE stream ended for appId=" + appId);
        }
    }
}
