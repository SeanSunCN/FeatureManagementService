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
 * Metrics async channel — Pool A: Fire &amp; Forget.
 *
 * All metrics are written into a single Redis Hash key "flag:metrics".
 * Field names use compound keys: {@code {appId}:{flagKey}:hits} and {@code :count}.
 * This enables atomic RENAME-based snapshot at the MetricsWorker side,
 * eliminating multi-Pod data race without distributed locks.
 *
 * Redis key: flag:metrics (single Hash)
 * Fields:    {appId}:{flagKey}:hits  (counter of flag hits)
 *            {appId}:{flagKey}:count (number of evaluations)
 */
@Component
public class MetricsChannel {

    private static final Logger log = LoggerFactory.getLogger(MetricsChannel.class);

    static final String METRICS_KEY = "flag:metrics";

    /**
     * Atomic increment of hits and count in one Redis round-trip.
     * All writes go to the single Hash key {@link #METRICS_KEY}.
     * KEYS[1] = "flag:metrics" (the single Hash)
     * ARGV[1] = hits delta
     * ARGV[2] = count delta (always 1)
     * ARGV[3] = field prefix: "{appId}:{flagKey}"
     */
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>();
    static {
        INCR_SCRIPT.setScriptText(
            "redis.call('HINCRBY', KEYS[1], ARGV[3] .. ':hits',  ARGV[1]);" +
            "redis.call('HINCRBY', KEYS[1], ARGV[3] .. ':count', ARGV[2]);" +
            "return 1"
        );
        INCR_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;

    public MetricsChannel(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Asynchronously receives and writes metrics to the single Redis Hash.
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
                Long count = entry.getValue();
                if (count != null && count > 0) {
                    // Single Redis call, one RTT for both hits + count
                    // KEYS[1] = "flag:metrics", ARGV[3] = "{appId}:{flagKey}"
                    stringRedisTemplate.execute(INCR_SCRIPT,
                            List.of(METRICS_KEY),
                            count.toString(), "1",
                            appId + ":" + entry.getKey());
                }
            }

            log.debug("Metrics ingested for appId={}, flags={}", appId, hitCounts.size());

        } catch (Exception e) {
            // Fire &amp; Forget: log the error but do not throw an exception
            log.warn("Metrics ingestion failed (fire &amp; forget): {}", e.getMessage());
        }
    }
}