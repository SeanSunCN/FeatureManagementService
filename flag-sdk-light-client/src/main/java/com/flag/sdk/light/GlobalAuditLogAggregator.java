package com.flag.sdk.light;

import com.flag.common.dto.AuditLogEntry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Global audit log aggregator — standalone singleton, shared across all LightFlagClient instances.
 *
 * Design (mirrors GlobalMetricsAggregator pattern):
 * - Per-appId queue: ConcurrentHashMap<String, ConcurrentLinkedQueue<AuditLogEntry>>
 * - Daemon thread flushes ALL app queues every 5 seconds
 * - JVM shutdown hook flushes synchronously to guarantee no data loss
 * - Singleton HttpClient reused across all POST calls
 *
 * Thread-safety:
 * - report() uses computeIfAbsent on outer map, offers to per-app queue
 * - flushAll() drains per-app queue via poll() loop — entries not sent remain in queue
 * - shutdown removes appId from flush cycle, then drains remaining entries synchronously
 */
public class GlobalAuditLogAggregator {

    private static final Duration INGEST_TIMEOUT = Duration.ofSeconds(3);
    private static final long FLUSH_INTERVAL_SECONDS = 5;
    private static final String AUDIT_LOG_BATCH_ENDPOINT =
            "http://localhost:8082/api/v1/ingest/audit-log/batch";

    /**
     * Per-appId audit log queues.
     * Key: appId, Value: concurrent queue of AuditLogEntry
     */
    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<AuditLogEntry>> queues
            = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;

    /**
     * Shared singleton HttpClient for all flush operations.
     */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    public GlobalAuditLogAggregator() {
        scheduler = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "flag-global-audit-log-flusher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(
                this::flushAll,
                FLUSH_INTERVAL_SECONDS,
                FLUSH_INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    /**
     * Report an audit log entry. Called from isEnabled() after each evaluation.
     * Entry is queued per appId and will be batch-flushed on the next cycle.
     */
    public void report(String appId, AuditLogEntry entry) {
        if (appId == null || entry == null) return;
        queues.computeIfAbsent(appId, k -> new ConcurrentLinkedQueue<>())
                .offer(entry);
    }

    /**
     * Periodic async flush — drains per-appId queues and POSTs batches.
     */
    public void flushAll() {
        for (Map.Entry<String, ConcurrentLinkedQueue<AuditLogEntry>> appEntry : queues.entrySet()) {
            String appId = appEntry.getKey();
            ConcurrentLinkedQueue<AuditLogEntry> queue = appEntry.getValue();

            List<AuditLogEntry> batch = drainQueue(queue);
            if (batch.isEmpty()) continue;

            postBatch(appId, batch, false);
        }
    }

    /**
     * Synchronous flush across ALL apps (JVM shutdown hook).
     */
    public void flushAllSync() {
        for (Map.Entry<String, ConcurrentLinkedQueue<AuditLogEntry>> appEntry : queues.entrySet()) {
            String appId = appEntry.getKey();
            ConcurrentLinkedQueue<AuditLogEntry> queue = appEntry.getValue();

            List<AuditLogEntry> batch = drainQueue(queue);
            if (batch.isEmpty()) continue;

            postBatch(appId, batch, true);
        }
    }

    /**
     * Graceful shutdown:
     * 1. Stop the scheduler (prevents new flush cycles from starting)
     * 2. Synchronous final flush (guarantees data reaches the wire before JVM exits)
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        flushAllSync();
    }

    // ============================================================
    //  Private helpers
    // ============================================================

    /**
     * Drain all entries from a ConcurrentLinkedQueue into a List.
     * Uses poll() in a loop — entries not polled remain in the queue for next cycle.
     */
    private static List<AuditLogEntry> drainQueue(ConcurrentLinkedQueue<AuditLogEntry> queue) {
        List<AuditLogEntry> batch = new ArrayList<>();
        AuditLogEntry entry;
        while ((entry = queue.poll()) != null) {
            batch.add(entry);
        }
        return batch;
    }

    /**
     * POST a batch of audit log entries to the IngestService audit-log/batch endpoint.
     *
     * @param sync  true = blocking send (shutdown path), false = async send (periodic flush)
     */
    private static void postBatch(String appId, List<AuditLogEntry> batch, boolean sync) {
        try {
            // Serialize batch to JSON array
            StringBuilder body = new StringBuilder(batch.size() * 256);
            body.append('[');
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) body.append(',');
                body.append(batch.get(i).toJson());
            }
            body.append(']');

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(AUDIT_LOG_BATCH_ENDPOINT))
                    .header("Content-Type", "application/json")
                    .timeout(INGEST_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            if (sync) {
                SHARED_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            } else {
                SHARED_HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            }
        } catch (Exception e) {
            // Swallow — best-effort for periodic flush; on shutdown path, log but proceed
        }
    }
}