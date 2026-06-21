package com.flag.worker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Async metrics batch flush service.
 *
 * Architecture mapping: Redis -> MetricsWorker -> batch write -> ClickHouse
 *
 * Workflow:
 * 1. Periodically scan metric counters in Redis (flag:metrics:*)
 * 2. Batch read and aggregate counts
 * 3. Batch write to ClickHouse
 * 4. Clean up flushed Redis keys
 */
@Service
public class MetricsFlushService {

    private static final Logger log = LoggerFactory.getLogger(MetricsFlushService.class);

    private static final String METRICS_KEY_PATTERN = "flag:metrics:*";

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate clickHouseJdbcTemplate;

    @Value("${app.worker.flush-batch-size:500}")
    private int batchSize;

    public MetricsFlushService(StringRedisTemplate stringRedisTemplate,
                               DataSource clickHouseDataSource) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.clickHouseJdbcTemplate = new JdbcTemplate(clickHouseDataSource);
    }

    /**
     * Executes a batch flush every 10 seconds.
     */
    @Scheduled(fixedDelayString = "${app.worker.flush-interval-ms:10000}")
    public void flushMetrics() {
        try {
            Set<String> keys = stringRedisTemplate.keys(METRICS_KEY_PATTERN);
            if (keys == null || keys.isEmpty()) {
                return;
            }

            List<Object[]> batchArgs = new ArrayList<>();
            List<String> keysToDelete = new ArrayList<>();

            for (String key : keys) {
                try {
                    // key format: flag:metrics:{appId}:{flagKey}
                    String[] parts = key.split(":");
                    if (parts.length < 4) continue;

                    String appId = parts[2];
                    String flagKey = parts[3];
                    Object hits = stringRedisTemplate.opsForHash().get(key, "hits");
                    Object count = stringRedisTemplate.opsForHash().get(key, "count");

                    long hitValue = hits != null ? Long.parseLong(hits.toString()) : 0;
                    long countValue = count != null ? Long.parseLong(count.toString()) : 0;

                    if (hitValue > 0) {
                        batchArgs.add(new Object[]{
                                appId, flagKey, hitValue, countValue, System.currentTimeMillis()
                        });
                        keysToDelete.add(key);
                    }

                    // flush early when batch threshold is reached
                    if (batchArgs.size() >= batchSize) {
                        flushBatch(batchArgs, keysToDelete);
                        batchArgs.clear();
                        keysToDelete.clear();
                    }

                } catch (Exception e) {
                    log.warn("Failed to process metrics key={}: {}", key, e.getMessage());
                }
            }

            // flush the remaining batch
            if (!batchArgs.isEmpty()) {
                flushBatch(batchArgs, keysToDelete);
            }

        } catch (Exception e) {
            log.error("Metrics flush cycle failed", e);
        }
    }

    private void flushBatch(List<Object[]> batchArgs, List<String> keysToDelete) {
        try {
            String sql = """
                    INSERT INTO flag_hit_metrics (app_id, flag_key, hits, eval_count, recorded_at)
                    VALUES (?, ?, ?, ?, toDateTime(? / 1000))
                    """;

            clickHouseJdbcTemplate.batchUpdate(sql, batchArgs);

            // clean up Redis keys that have been flushed
            if (!keysToDelete.isEmpty()) {
                stringRedisTemplate.delete(keysToDelete);
            }

            log.info("Flushed {} metric records to ClickHouse", batchArgs.size());

        } catch (Exception e) {
            log.error("Batch flush to ClickHouse failed, will retry {} records", batchArgs.size(), e);
        }
    }
}