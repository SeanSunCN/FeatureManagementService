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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Metrics batch flush service — atomic RENAME-based snapshot.
 *
 * Architecture: Redis (single Hash {@code flag:metrics}) -> RENAME atomic swap
 *               -> batch write -> ClickHouse
 *
 * Workflow:
 * 1. RENAME "flag:metrics" -> "flag:metrics:flush:{timestamp}" (atomic cut)
 * 2. Read all fields from the backup key (no race with IngestService writes)
 * 3. Parse compound field names into appId / flagKey / counter type
 * 4. Aggregate hits + count per (appId, flagKey), batch write to ClickHouse
 * 5. Delete backup key
 *
 * Thread-safety: multiple Worker Pods may race on RENAME; only one wins,
 * the loser sees the key gone and returns immediately.
 * No distributed lock needed — Redis RENAME is atomic by design.
 */
@Service
public class MetricsFlushService {

    private static final Logger log = LoggerFactory.getLogger(MetricsFlushService.class);

    static final String METRICS_KEY = "flag:metrics";

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
     * Executes an atomic snapshot flush every 10 seconds.
     * Uses RENAME to atomically cut the metrics Hash, eliminating multi-Pod race.
     */
    @Scheduled(cron = "0/10 * * * * ?")
    public void flushMetrics() {
        String backupKey = "flag:metrics:flush:" + System.currentTimeMillis();

        try {
            // 1. Atomic rename: only one Pod wins the race.
            //    After this, the live "flag:metrics" is empty;
            //    IngestService continues writing to it without interruption.
            Boolean renamed = stringRedisTemplate.renameIfAbsent(METRICS_KEY, backupKey);
            if (Boolean.FALSE.equals(renamed)) {
                // No data to flush (or another Pod already took it)
                return;
            }

            // 2. Read all fields from the isolated backup key
            Map<Object, Object> rawData = stringRedisTemplate.opsForHash().entries(backupKey);
            if (rawData == null || rawData.isEmpty()) {
                stringRedisTemplate.delete(backupKey);
                return;
            }

            // 3. Parse compound field names and aggregate
            //    Fields: "{appId}:{flagKey}:hits" / "{appId}:{flagKey}:count"
            //    Inner map: key = "{appId}:{flagKey}", value = [hits, evalCount]
            Map<String, long[]> aggregated = new HashMap<>();

            for (Map.Entry<Object, Object> field : rawData.entrySet()) {
                String compoundKey = field.getKey().toString();
                long value = Long.parseLong(field.getValue().toString());

                int lastColon = compoundKey.lastIndexOf(':');
                if (lastColon < 0) continue;

                String suffix = compoundKey.substring(lastColon + 1);
                String prefix = compoundKey.substring(0, lastColon);

                int firstColon = prefix.indexOf(':');
                if (firstColon < 0) continue;

                long[] counters = aggregated.computeIfAbsent(prefix, k -> new long[2]);
                if ("hits".equals(suffix)) {
                    counters[0] += value;
                } else if ("count".equals(suffix)) {
                    counters[1] += value;
                }
            }

            // 4. Build batch args
            long now = System.currentTimeMillis();
            List<Object[]> batchArgs = new ArrayList<>();

            for (Map.Entry<String, long[]> entry : aggregated.entrySet()) {
                String prefix = entry.getKey();
                long hits = entry.getValue()[0];
                long evalCount = entry.getValue()[1];

                int firstColon = prefix.indexOf(':');
                String appId = prefix.substring(0, firstColon);
                String flagKey = prefix.substring(firstColon + 1);

                batchArgs.add(new Object[]{appId, flagKey, hits, evalCount, now});

                if (batchArgs.size() >= batchSize) {
                    flushBatch(batchArgs);
                    batchArgs.clear();
                }
            }

            // 5. Flush remaining and cleanup
            if (!batchArgs.isEmpty()) {
                flushBatch(batchArgs);
            }

            stringRedisTemplate.delete(backupKey);
            log.info("Metrics flush cycle completed: {} flags, backupKey={}",
                    aggregated.size(), backupKey);

        } catch (Exception e) {
            log.error("Metrics flush cycle failed for backupKey={}", backupKey, e);
        }
    }

    private void flushBatch(List<Object[]> batchArgs) {
        try {
            String sql = """
                    INSERT INTO flag.flag_hit_metrics (app_id, flag_key, hits, eval_count, recorded_at)
                    VALUES (?, ?, ?, ?, toDateTime(? / 1000))
                    """;

            clickHouseJdbcTemplate.batchUpdate(sql, batchArgs);
            log.info("Flushed {} metric records to ClickHouse", batchArgs.size());

        } catch (Exception e) {
            log.error("Batch flush to ClickHouse failed, will retry {} records", batchArgs.size(), e);
        }
    }
}