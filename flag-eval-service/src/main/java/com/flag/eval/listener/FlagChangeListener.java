package com.flag.eval.listener;

import com.flag.common.constant.RedisChannels;
import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.dto.FlagChangeMessage.ChangeType;
import com.flag.eval.cache.FlagCache;
import com.flag.eval.sse.SseController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;

/**
 * Redis Pub/Sub change notification listener.
 *
 * Architecture diagram: EvalService listens for Redis change notifications -> refreshes local memory cache -> SSE push
 *
 * Listens on three channels:
 * - flag:change — flag changes (create/delete/update/enable/disable)
 * - app:change — app changes (create/delete)
 * - flag:reload — full reload signal
 */
@Component
public class FlagChangeListener {

    private static final Logger log = LoggerFactory.getLogger(FlagChangeListener.class);

    private final ReactiveRedisConnectionFactory connectionFactory;
    private final FlagCache flagCache;
    private final FlagDbLoader flagDbLoader;
    private final SseController sseController;
    private final ObjectMapper objectMapper;

    private ReactiveRedisMessageListenerContainer container;
    private Disposable subscription;

    public FlagChangeListener(ReactiveRedisConnectionFactory connectionFactory,
                              FlagCache flagCache,
                              FlagDbLoader flagDbLoader,
                              SseController sseController,
                              ObjectMapper objectMapper) {
        this.connectionFactory = connectionFactory;
        this.flagCache = flagCache;
        this.flagDbLoader = flagDbLoader;
        this.sseController = sseController;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        container = new ReactiveRedisMessageListenerContainer(connectionFactory);

        // Subscribe to the merged stream of three channels
        Flux<FlagChangeMessage> messageFlux = container.receive(
                ChannelTopic.of(RedisChannels.FLAG_CHANGE),
                ChannelTopic.of(RedisChannels.APP_CHANGE),
                ChannelTopic.of(RedisChannels.FLAG_RELOAD)
        ).map(msg -> {
            String channel = msg.getChannel();
            String body = msg.getMessage();
            try {
                return objectMapper.readValue(body, FlagChangeMessage.class);
            } catch (IOException e) {
                log.error("Failed to deserialize message from channel={}: {}", channel, body, e);
                return null;
            }
        }).filter(msg -> msg != null);

        subscription = messageFlux.subscribe(this::handleMessage);

        log.info("FlagChangeListener subscribed to Redis Pub/Sub channels");
    }

    @PreDestroy
    public void destroy() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        if (container != null) {
            container.destroy();
        }
    }

    /**
     * Handle a change message.
     */
    private void handleMessage(FlagChangeMessage message) {
        String appId = message.getAppId();
        ChangeType changeType = message.getChangeType();
        String flagKey = message.getFlagKey();

        log.info("Received change: type={}, appId={}, flagKey={}", changeType, appId, flagKey);

        try {
            switch (changeType) {
                case CREATE:
                case UPDATE:
                case ENABLE:
                case DISABLE:
                    // Reload the affected single flag from DB
                    if (flagKey != null) {
                        FlagCache.FlagEntry entry = flagDbLoader.loadSingleFlag(appId, flagKey);
                        if (entry != null) {
                            flagCache.put(appId, flagKey, entry);
                            log.debug("Cache updated for appId={}, flagKey={} after {}",
                                    appId, flagKey, changeType);
                        }
                    } else {
                        reloadApp(appId);
                    }
                    break;

                case DELETE:
                    if (flagKey != null) {
                        flagCache.remove(appId, flagKey);
                        log.debug("Cache removed for appId={}, flagKey={}", appId, flagKey);
                    }
                    break;

                case RELOAD:
                    reloadApp(appId);
                    break;

                default:
                    log.warn("Unknown change type: {}", changeType);
                    return;
            }

            // Push to connected clients of the corresponding App via SSE
            sseController.pushChange(appId, message);

        } catch (Exception e) {
            log.error("Error handling change message: appId={}, type={}", appId, changeType, e);
        }
    }

    private void reloadApp(String appId) {
        flagCache.putAll(appId, flagDbLoader.loadAllFlags(appId));
        log.info("Cache reloaded for appId={}", appId);
    }
}