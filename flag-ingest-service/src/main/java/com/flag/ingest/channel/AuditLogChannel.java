package com.flag.ingest.channel;

import com.flag.common.dto.AuditLogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
     * Server-side receive timestamp is injected at the JSON level,
     * NOT in the DTO (which comes from the SDK).
     * The ClickHouse MV parses JSONExtractUInt(message, 'timestamp').
     */
    @Async("auditLogExecutor")
    public CompletableFuture<Boolean> ingest(AuditLogEntry entry) {
        try {
            // Convert DTO to Map, inject timestamp at serialization layer
            Map<String, Object> message = MAPPER.convertValue(entry, MAPPER.getTypeFactory()
                    .constructMapType(HashMap.class, String.class, Object.class));
            message.put("timestamp", System.currentTimeMillis());

            String json = MAPPER.writeValueAsString(message);
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