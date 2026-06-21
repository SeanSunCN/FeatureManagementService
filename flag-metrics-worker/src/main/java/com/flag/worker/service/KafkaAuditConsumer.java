package com.flag.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Kafka audit log consumer.
 *
 * Architecture diagram: Kafka message queue -> streaming clean & land -> ClickHouse
 *
 * Consumes audit logs from Kafka and writes them to the ClickHouse audit trail table.
 */
@Component
public class KafkaAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditConsumer.class);

    private static final String TOPIC = "flag-audit-log";

    private final JdbcTemplate clickHouseJdbcTemplate;
    private final ObjectMapper objectMapper;

    public KafkaAuditConsumer(DataSource clickHouseDataSource, ObjectMapper objectMapper) {
        this.clickHouseJdbcTemplate = new JdbcTemplate(clickHouseDataSource);
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TOPIC, groupId = "flag-metrics-worker", containerFactory = "kafkaListenerContainerFactory")
    public void consume(String message) {
        try {
            Map<String, Object> entry = objectMapper.readValue(message, new TypeReference<Map<String, Object>>() {});

            String appId = (String) entry.getOrDefault("appId", "");
            String flagKey = (String) entry.getOrDefault("flagKey", "");
            String userId = (String) entry.getOrDefault("userId", "");
            boolean enabled = (boolean) entry.getOrDefault("enabled", false);
            String matchedRule = (String) entry.getOrDefault("matchedRule", "");
            String clientIp = (String) entry.getOrDefault("clientIp", "");
            Object evalCostObj = entry.get("evalCostNs");
            long evalCostNs = evalCostObj != null ? ((Number) evalCostObj).longValue() : 0;
            Object attrsObj = entry.get("attributesSnapshot");
            String attributesJson = attrsObj != null ? objectMapper.writeValueAsString(attrsObj) : "";

            String sql = """
                    INSERT INTO flag_audit_log
                        (app_id, flag_key, user_id, enabled, matched_rule, client_ip, attributes_snapshot, eval_cost_ns, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, toDateTime(? / 1000))
                    """;

            clickHouseJdbcTemplate.update(sql,
                    appId, flagKey, userId, enabled, matchedRule, clientIp,
                    attributesJson, evalCostNs, System.currentTimeMillis());

            log.trace("Audit log consumed: appId={}, flagKey={}, userId={}", appId, flagKey, userId);

        } catch (Exception e) {
            log.warn("Failed to consume audit log message: {}", e.getMessage());
        }
    }
}
