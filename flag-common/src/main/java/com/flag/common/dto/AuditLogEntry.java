package com.flag.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Audit log entry.
 *
 * SDK calls /evaluate, then EvalService or the SDK reports the evaluation
 * to IngestService. AuditLogChannel writes to Kafka, eventually landed in ClickHouse.
 *
 * timestamp field is populated by IngestService on arrival (server-side clock),
 * ensuring accuracy regardless of Kafka consumer lag.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntry {
    private String appId;
    private String flagKey;
    private String userId;
    private boolean enabled;
    /** Matched rule label (e.g. "rule-matched", "global-disabled", "cache-hit", "whitelist", "targeting:region") */
    private String matchedRule;
    private String clientIp;
    /** Snapshot of context attributes passed during evaluation */
    private Map<String, String> attributesSnapshot;
    /** Evaluation duration in NANOSECONDS */
    private long evalCostNs;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            return "{}";
        }
    }
}