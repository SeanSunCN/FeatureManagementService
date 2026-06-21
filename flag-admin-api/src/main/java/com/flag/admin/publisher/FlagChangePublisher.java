package com.flag.admin.publisher;

import com.flag.common.constant.RedisChannels;
import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.dto.FlagChangeMessage.ChangeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Rule change notification publisher.
 *
 * Architecture diagram: AdminAPI -> Redis Pub/Sub change notification bus
 */
@Component
public class FlagChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(FlagChangePublisher.class);

    private final StringRedisTemplate stringRedisTemplate;

    public FlagChangePublisher(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * Publish a feature flag change notification with version info.
     * Uses strongly-typed ChangeType — no raw strings.
     */
    public void publishFlagChange(String appId, ChangeType changeType, String flagKey, Long version) {
        FlagChangeMessage message = FlagChangeMessage.of(appId, changeType, flagKey, version);
        stringRedisTemplate.convertAndSend(RedisChannels.FLAG_CHANGE, toJson(message));
        log.info("Published flag change: type={}, appId={}, flagKey={}", changeType, appId, flagKey);
    }

    public void publishAppChange(String appId, ChangeType changeType) {
        FlagChangeMessage message = FlagChangeMessage.of(appId, changeType, null, 0L);
        stringRedisTemplate.convertAndSend(RedisChannels.APP_CHANGE, toJson(message));
        log.info("Published app change: type={}, appId={}", changeType, appId);
    }

    public void publishReload(String appId) {
        FlagChangeMessage message = FlagChangeMessage.of(appId, ChangeType.RELOAD, null, 0L);
        stringRedisTemplate.convertAndSend(RedisChannels.FLAG_RELOAD, toJson(message));
        log.info("Published reload signal for appId={}", appId);
    }

    private String toJson(Object obj) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize message", e);
        }
    }
}
