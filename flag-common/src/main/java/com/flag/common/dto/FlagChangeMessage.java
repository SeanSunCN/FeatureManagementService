package com.flag.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Redis Pub/Sub flag change notification message.
 * Introduces a strongly-typed version number to eliminate stale-data overwrites
 * caused by out-of-order message delivery across multiple instances.
 *
 * Data-plane consumers MUST check:
 *   if (message.getVersion() > localCache.getVersion()) { refreshLocalCache(); }
 * This guarantees that even if messages arrive out of order,
 * a lower-version message can never overwrite newer data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FlagChangeMessage {

    /** Affected AppId */
    private String appId;

    /** Change type — strongly-typed enum prevents string-typo cache misses */
    private ChangeType changeType;

    /** Changed flag key (null for full reload) */
    private String flagKey;

    /**
     * Strictly-increasing version number for this flag or App config.
     * Derived from FeatureFlagEntity.@Version (JPA optimistic lock).
     * Consumers MUST compare: incoming > current → refresh; otherwise → discard.
     */
    private Long version;

    /** Absolute timestamp when the message was sent (observability / latency tracing only) */
    private long timestamp;

    public static FlagChangeMessage of(String appId, ChangeType changeType, String flagKey, Long version) {
        return new FlagChangeMessage(appId, changeType, flagKey, version, System.currentTimeMillis());
    }

    /**
     * Strongly-typed change type — never use raw strings.
     */
    public enum ChangeType {
        CREATE,
        UPDATE,
        DELETE,
        RELOAD,
        ENABLE,
        DISABLE,
        HEARTBEAT
    }
}
