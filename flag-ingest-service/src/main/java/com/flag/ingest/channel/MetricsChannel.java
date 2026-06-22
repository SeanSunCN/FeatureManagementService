package com.flag.ingest.channel;

import com.flag.common.dto.MetricsReportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Metrics async channel — Pool A: Fire & Forget.
 *
 * Corresponding architecture: MetricsChannel -> HINCRBY memory counter -> Redis
 *
 * Features:
 * - Fully asynchronous, does not block the caller
 * - Uses Redis EVAL Lua script for atomic HINCRBY (hits + count in one RTT)
 * - When the queue is full, the caller's thread executes (CallerRunsPolicy)
 * - Metrics key format: flag:metrics:{appId}:{flagKey}
 */
@Component
public class MetricsChannel {

    private static final Logger log = LoggerFactory.getLogger(MetricsChannel.class);

    private static final String METRICS_KEY_PREFIX = "flag:metrics:";

    /**
     * Atomic increment of hits and count in one Redis round-trip.
     * KEYS[1] = flag:metrics:{appId}:{flagKey}
     * ARGV[1] = hits delta
     * ARGV[2] = count delta (always 1)
     */
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>();
    static {
        INCR_SCRIPT.setScriptText(
            "redis.call('HINCRBY', KEYS[1], 'hits', ARGV[1]);" +
            "redis.call('HINCRBY', KEYS[1], 'count', ARGV[2]);" +
            "return 1"
        );
        INCR_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public MetricsChannel(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Asynchronously receives and writes metrics to Redis counters.
     * Uses @Async("metricsExecutor") to ensure execution in a dedicated thread pool.
     * Atomic Lua script writes hits + count in a single Redis call (reduced from 2 RTTs to 1).
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
                    // Single Redis call instead of two HINCRBY round-trips
                    stringRedisTemplate.execute(INCR_SCRIPT, List.of(key), count.toString(), "1");
                }
            }

            log.debug("Metrics ingested for appId={}, flags={}", appId, hitCounts.size());

        } catch (Exception e) {
            // Fire & Forget: log the error but do not throw an exception
            log.warn("Metrics ingestion failed (fire & forget): {}", e.getMessage());
        }
    }
}