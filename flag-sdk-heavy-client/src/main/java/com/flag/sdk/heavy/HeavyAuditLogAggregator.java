package com.flag.sdk.heavy;

import com.flag.common.dto.AuditLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Global audit log aggregator — singleton, shared across all HeavyFlagClient instances.
 *
 * Design (mirrors HeavyMetricsAggregator pattern):
 * - Per-appId queue: ConcurrentHashMap<String, ConcurrentLinkedQueue<AuditLogEntry>>
 * - Daemon thread flushes ALL app queues every 60 seconds
 * - JVM shutdown hook flushes synchronously to guarantee no data loss
 * - Singleton WebClient reused across all POST calls
 *
 * Thread-safety:
 * - report() uses computeIfAbsent on outer map, offers to per-app queue
 * - flushAll() drains per-app queue via poll() loop
 * - flushAndDrain() removes appId FIRST, then drains — zero lost update race
 */
public class HeavyAuditLogAggregator {

    private static final Logger log = LoggerFactory.getLogger(HeavyAuditLogAggregator.class);

    private static final ConcurrentHashMap<String, ConcurrentLinkedQueue<AuditLogEntry>> queues
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
            Thread t = new Thread(r, "flag-heavy-audit-log-flusher");
            t.setDaemon(true);
            return t;
        });
        SCHEDULER.scheduleAtFixedRate(
                HeavyAuditLogAggregator::flushAll,
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
        }, "flag-heavy-audit-log-shutdown-hook"));
    }

    private HeavyAuditLogAggregator() {}

    /**
     * Register an appId for audit log tracking.
     */
    public static void register(String appId, String ingestServiceUrl) {
        queues.computeIfAbsent(appId, k -> new ConcurrentLinkedQueue<>());
        if (ingestServiceUrl != null && !ingestServiceUrl.isBlank()) {
            ingestUrls.put(appId, ingestServiceUrl);
        }
    }

    /**
     * Report an audit log entry for a given appId.
     * If appId has been shut down (queues.get returns null), silently returns.
     * NEVER uses computeIfAbsent on the outer map — prevents re-creation leak.
     */
    public static void report(String appId, AuditLogEntry entry) {
        ConcurrentLinkedQueue<AuditLogEntry> queue = queues.get(appId);
        if (queue == null) return;
        queue.offer(entry);
    }

    // ========================================================================
    //  Private helpers
    // ========================================================================

    private static List<AuditLogEntry> drainQueue(ConcurrentLinkedQueue<AuditLogEntry> queue) {
        List<AuditLogEntry> batch = new ArrayList<>();
        AuditLogEntry entry;
        while ((entry = queue.poll()) != null) {
            batch.add(entry);
        }
        return batch;
    }

    private static void postBatch(String appId, List<AuditLogEntry> batch, boolean sync) {
        String ingestUrl = ingestUrls.getOrDefault(appId, "http://localhost:8082");
        try {
            var spec = SHARED_HTTP_CLIENT.post()
                    .uri(ingestUrl + "/api/v1/ingest/audit-log/batch")
                    .bodyValue(batch)
                    .retrieve()
                    .bodyToMono(Void.class)
                    .timeout(Duration.ofSeconds(3));

            if (sync) {
                spec.block();
            } else {
                spec.doOnError(e ->
                        log.error("Async audit-log batch POST failed for appId={}: {}", appId, e.getMessage()))
                    .subscribe();
            }
        } catch (Exception e) {
            String label = sync ? "Sync" : "Async";
            log.error("{} audit-log batch POST failed for appId={}: {}", label, appId, e.getMessage());
        }
    }

    // ========================================================================
    //  Public flush methods
    // ========================================================================

    /** Periodic async flush — iterates ALL app queues. */
    public static void flushAll() {
        for (Map.Entry<String, ConcurrentLinkedQueue<AuditLogEntry>> app : queues.entrySet()) {
            List<AuditLogEntry> batch = drainQueue(app.getValue());
            if (!batch.isEmpty()) {
                postBatch(app.getKey(), batch, false);
            }
        }
    }

    /** Synchronous flush across ALL apps (JVM shutdown hook). */
    public static void flushAllSync() {
        for (Map.Entry<String, ConcurrentLinkedQueue<AuditLogEntry>> app : queues.entrySet()) {
            List<AuditLogEntry> batch = drainQueue(app.getValue());
            if (!batch.isEmpty()) {
                postBatch(app.getKey(), batch, true);
            }
        }
    }

    /**
     * Synchronous flush + drain for a single appId (client shutdown).
     * Removes the app from queues FIRST to cut off new incoming reports,
     * then drains the remaining entries.
     */
    public static void flushAndDrain(String appId) {
        ConcurrentLinkedQueue<AuditLogEntry> queue = queues.remove(appId);
        if (queue == null) return;

        ingestUrls.remove(appId);

        List<AuditLogEntry> batch = drainQueue(queue);
        if (!batch.isEmpty()) {
            postBatch(appId, batch, true);
        }
    }
}