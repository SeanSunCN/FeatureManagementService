package com.flag.sdk.heavy;

import com.flag.common.dto.MetricsReportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Global metrics aggregator — singleton, shared across all HeavyFlagClient instances.
 *
 * Design:
 * - Counter map: ConcurrentHashMap<appId, ConcurrentHashMap<flagKey, AtomicLong>>
 * - Daemon thread flushes ALL app counters every 60 seconds
 * - JVM shutdown hook flushes synchronously to guarantee no data loss
 * - Singleton WebClient reused — never calls WebClient.create() more than once
 *
 * Thread-safety:
 * - increment() uses get() + computeIfAbsent only on inner map;
 *   if appId is absent (shut down), returns immediately — no re-creation leak
 * - flushAndDrain() removes appId FIRST, then drains — zero lost update race
 */
public class HeavyMetricsAggregator {

    private static final Logger log = LoggerFactory.getLogger(HeavyMetricsAggregator.class);

    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> counters
            = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> ingestUrls
            = new ConcurrentHashMap<>();

    /** Singleton WebClient — never call WebClient.create() more than once. */
    private static final WebClient SHARED_HTTP_CLIENT = WebClient.builder()
            .codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(64 * 1024))
            .build();

    private static final ScheduledExecutorService SCHEDULER;

    static {
        SCHEDULER = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "flag-heavy-metrics-flusher");
            t.setDaemon(true);
            return t;
        });
        SCHEDULER.scheduleAtFixedRate(
                HeavyMetricsAggregator::flushAll,
                60, 60, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            SCHEDULER.shutdown();
            try {
                if (!SCHEDULER.awaitTermination(2, TimeUnit.SECONDS))
                    SCHEDULER.shutdownNow();
            } catch (InterruptedException e) {
                SCHEDULER.shutdownNow();
                Thread.currentThread().interrupt();
            }
            flushAllSync();
        }, "flag-heavy-metrics-shutdown-hook"));
    }

    private HeavyMetricsAggregator() {}

    /** Register an appId for metrics tracking. */
    public static void register(String appId, String ingestServiceUrl) {
        counters.computeIfAbsent(appId, k -> new ConcurrentHashMap<>());
        if (ingestServiceUrl != null && !ingestServiceUrl.isBlank()) {
            ingestUrls.put(appId, ingestServiceUrl);
        }
    }

    /**
     * Increment hit count for a flag.
     * If appId has been shut down (counters.get returns null), silently returns.
     * NEVER uses computeIfAbsent on the outer map — prevents re-creation leak.
     */
    public static void increment(String appId, String flagKey) {
        ConcurrentHashMap<String, AtomicLong> appCounters = counters.get(appId);
        if (appCounters == null) return;
        appCounters.computeIfAbsent(flagKey, k -> new AtomicLong(0))
                .incrementAndGet();
    }

    // ========================================================================
    //  Private helpers
    // ========================================================================

    private static Map<String, Long> takeSnapshot(
            ConcurrentHashMap<String, AtomicLong> appCounters) {
        Map<String, Long> snapshot = new HashMap<>();
        for (Map.Entry<String, AtomicLong> e : appCounters.entrySet()) {
            long count = e.getValue().getAndSet(0);
            if (count > 0) snapshot.put(e.getKey(), count);
        }
        return snapshot;
    }

    private static void postMetrics(String appId, Map<String, Long> snapshot, boolean sync) {
        String ingestUrl = ingestUrls.getOrDefault(appId, "http://localhost:8082");
        try {
            MetricsReportRequest report = new MetricsReportRequest();
            report.setAppId(appId);
            report.setFlagHitCounts(snapshot);

            var spec = SHARED_HTTP_CLIENT.post()
                    .uri(ingestUrl + "/api/v1/ingest/metrics")
                    .bodyValue(report)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(Duration.ofSeconds(3));

            if (sync) {
                spec.block();
            } else {
                spec.doOnError(e ->
                        log.error("Async metrics POST failed for appId={}: {}", appId, e.getMessage()))
                    .subscribe();
            }
        } catch (Exception e) {
            String label = sync ? "Sync" : "Async";
            log.error("{} metrics POST failed for appId={}: {}", label, appId, e.getMessage());
        }
    }

    // ========================================================================
    //  Public flush methods
    // ========================================================================

    /** Periodic async flush — iterates ALL app counters. */
    public static void flushAll() {
        for (Map.Entry<String, ConcurrentHashMap<String, AtomicLong>> app : counters.entrySet()) {
            Map<String, Long> snapshot = takeSnapshot(app.getValue());
            if (!snapshot.isEmpty()) {
                postMetrics(app.getKey(), snapshot, false);
            }
        }
    }

    /** Synchronous flush across ALL apps (JVM shutdown hook). */
    public static void flushAllSync() {
        for (Map.Entry<String, ConcurrentHashMap<String, AtomicLong>> app : counters.entrySet()) {
            Map<String, Long> snapshot = takeSnapshot(app.getValue());
            if (!snapshot.isEmpty()) {
                postMetrics(app.getKey(), snapshot, true);
            }
        }
    }

    /**
     * Synchronous flush + drain for a single appId (client shutdown).
     * Removes the app from counters FIRST to cut off new incoming increments,
     * then drains the snapshot.
     */
    public static void flushAndDrain(String appId) {
        // Remove FIRST to prevent new increments from leaking into static map
        ConcurrentHashMap<String, AtomicLong> appCounters = counters.remove(appId);
        if (appCounters == null) return;

        ingestUrls.remove(appId);

        Map<String, Long> snapshot = takeSnapshot(appCounters);
        if (!snapshot.isEmpty()) {
            postMetrics(appId, snapshot, true);
        }
    }
}
