package com.flag.ingest.channel;

import com.flag.common.dto.AuditLogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Audit log channel — Pool B: with timeout and degraded drop.
 *
 * Corresponding architecture diagram: LogChannel -> Kafka message queue
 *
 * Features:
 * - Caller needs to wait for the result (with timeout)
 * - Automatically degrades and drops when timeout or queue is full
 * - Records drop counter ingest_drop_total
 */
@Component
public class AuditLogChannel {

    private static final Logger log = LoggerFactory.getLogger(AuditLogChannel.class);

    private static final String TOPIC = "flag-audit-log";
    private static final long TIMEOUT_MS = 200;

    private final KafkaTemplate<String, String> kafkaTemplate;

    /**
     * Degraded drop counter
     */
    private final AtomicLong dropCounter = new AtomicLong(0);

    public AuditLogChannel(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Asynchronously write audit logs to Kafka.
     * With timeout; after timeout, degraded drop and accumulate the drop count.
     */
    @Async("auditLogExecutor")
    public CompletableFuture<Boolean> ingest(AuditLogEntry entry) {
        try {
            String json = entry.toJson();
            CompletableFuture<Boolean> future = kafkaTemplate.send(TOPIC, entry.getAppId(), json)
                    .thenApply(result -> true)
                    .exceptionally(e -> {
                        log.warn("Kafka send failed for audit log: {}", e.getMessage());
                        return false;
                    });

            // Wait with timeout
            boolean success = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (!success) {
                long dropped = dropCounter.incrementAndGet();
                log.warn("Audit log dropped (kafka failed), total drops: {}", dropped);
            }

            return CompletableFuture.completedFuture(success);

        } catch (Exception e) {
            long dropped = dropCounter.incrementAndGet();
            log.warn("Audit log timeout/dropped (ingest_drop_total={}): {}", dropped, e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

    /**
     * Get the current total drop count.
     * Corresponding architecture diagram: ingest_drop_total metric
     */
    public long getDropTotal() {
        return dropCounter.get();
    }
}