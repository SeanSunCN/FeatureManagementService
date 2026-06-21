package com.flag.admin.service;

import com.flag.admin.entity.FlagOutboxEntity;
import com.flag.admin.repository.FlagOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * Transactional outbox poller with SKIP LOCKED for multi-instance safety.
 *
 * Uses PostgreSQL FOR UPDATE SKIP LOCKED to ensure each poll cycle grabs
 * an exclusive batch of rows. Other instances automatically skip already-locked
 * rows, preventing duplicate delivery (thundering herd).
 *
 * Entries that fail MAX_RETRIES times are moved to dead-letter state.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);
    private static final int BATCH_SIZE = 100;

    private final FlagOutboxRepository outboxRepository;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    public OutboxPoller(FlagOutboxRepository outboxRepository,
                         DataSource dataSource,
                         StringRedisTemplate stringRedisTemplate) {
        this.outboxRepository = outboxRepository;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Poll every 1 second. Uses SELECT ... FOR UPDATE SKIP LOCKED
     * to safely acquire an exclusive batch — multi-instance safe.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<FlagOutboxEntry> pending = lockNextBatch();
        if (pending.isEmpty()) return;

        log.debug("Outbox poller locked {} pending events", pending.size());

        for (FlagOutboxEntry entry : pending) {
            try {
                stringRedisTemplate.convertAndSend(entry.channel, entry.payload);
                outboxRepository.markSent(entry.id);
                log.trace("Outbox event delivered: channel={}, id={}", entry.channel, entry.id);
            } catch (Exception e) {
                int attempt = entry.retryCount + 1;
                log.warn("Outbox delivery failed ({}/{}): channel={}, id={}, error={}",
                        attempt, FlagOutboxEntity.MAX_RETRIES,
                        entry.channel, entry.id, e.getMessage());

                if (attempt >= FlagOutboxEntity.MAX_RETRIES) {
                    outboxRepository.markDeadLetter(entry.id);
                    log.error("Outbox entry moved to DEAD LETTER: id={}, channel={}, payload={}",
                            entry.id, entry.channel, entry.payload);
                } else {
                    outboxRepository.incrementRetryCount(entry.id);
                }
            }
        }
    }

    /**
     * Atomically lock the next batch of pending outbox rows using
     * PostgreSQL FOR UPDATE SKIP LOCKED.
     *
     * SKIP LOCKED: rows already locked by another transaction/instance
     * are silently skipped — no blocking, no duplicate grabs.
     */
    private List<FlagOutboxEntry> lockNextBatch() {
        String sql = """
                SELECT id, channel, payload, retry_count
                FROM flag_outbox
                WHERE sent = FALSE
                  AND dead_letter = FALSE
                  AND retry_count < ?
                ORDER BY created_at ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;

        return jdbcTemplate.query(sql,
                new Object[]{FlagOutboxEntity.MAX_RETRIES, BATCH_SIZE},
                (ResultSet rs, int rowNum) -> new FlagOutboxEntry(
                        rs.getLong("id"),
                        rs.getString("channel"),
                        rs.getString("payload"),
                        rs.getInt("retry_count")
                ));
    }

    /**
     * Lightweight projection — no entity overhead for the locked query.
     */
    private record FlagOutboxEntry(long id, String channel, String payload, int retryCount) {}
}
