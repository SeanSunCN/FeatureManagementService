package com.flag.ingest.channel;

import com.flag.common.dto.MetricsReportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Metrics async channel — Pool A: Fire &amp; Forget.
 *
 * Corresponding architecture: MetricsChannel -> HINCRBY memory counter -> Redis
 *
 * Features:
 * - Fully asynchronous, does not block the caller
 * - Uses Redis HINCRBY for high-frequency counters
 * - When the queue is full, the caller's thread executes (CallerRunsPolicy)
 * - Metrics key format: flag:metrics:{appId}:{flagKey}
 */
@Component
public class MetricsChannel {

    private static final Logger log = LoggerFactory.getLogger(MetricsChannel.class);

    private static final String METRICS_KEY_PREFIX = "flag:metrics:";

    private final StringRedisTemplate stringRedisTemplate;

    public MetricsChannel(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Asynchronously receives and writes metrics to Redis counters.
     * Uses @Async("metricsExecutor") to ensure execution in a dedicated thread pool.
     */
    @Async("metricsExecutor")
    public void ingest(MetricsReportRequest request) {
        try {
            String appId = request.getAppId();
            Map<String, Long> hitCounts = request.getFlagHitCounts();

            if (hitCounts == null || hitCounts.isEmpty()) {
                return;
            }

            for (Map.Entry<String, Long> entry : hitCounts.entrySet()) {
                String key = METRICS_KEY_PREFIX + appId + ":" + entry.getKey();
                Long count = entry.getValue();
                if (count != null && count > 0) {
                    stringRedisTemplate.opsForHash().increment(key, "hits", count);
                    stringRedisTemplate.opsForHash().increment(key, "count", 1);
                }
            }

            log.debug("Metrics ingested for appId={}, flags={}", appId, hitCounts.size());

        } catch (Exception e) {
            // Fire & Forget: log the error but do not throw an exception
            log.warn("Metrics ingestion failed (fire & forget): {}", e.getMessage());
        }
    }
}