package com.flag.eval.sse;

import com.flag.common.dto.FlagChangeMessage;
import com.flag.common.dto.FlagChangeMessage.ChangeType;
import com.flag.eval.cache.FlagCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE long-connection push endpoint.
 * <p>
 * Architecture diagram:
 * EvalService --SSE--> HeavySDK_A (pushes only App A changes)
 * EvalService --SSE--> HeavySDK_B (pushes only App B changes)
 * <p>
 * Uses WebFlux Sinks.Many for multicast, with an independent channel per App.
 */
@RestController
@RequestMapping("/api/v1/eval/sse")
public class SseController {

    private static final Logger log = LoggerFactory.getLogger(SseController.class);

    private final FlagCache flagCache;
    private final long heartbeatIntervalMs;

    /**
     * Sink map isolated by AppId.
     * Each App has its own Sinks.Many, enabling EvalService to push precisely by AppId.
     */
    private final Map<String, Sinks.Many<FlagChangeMessage>> appSinks = new ConcurrentHashMap<>();

    public SseController(FlagCache flagCache,
                         org.springframework.core.env.Environment env) {
        this.flagCache = flagCache;
        this.heartbeatIntervalMs = env.getProperty("app.sse.heartbeat-interval-ms",
                long.class, 30000L);
    }

    /**
     * GET /api/v1/eval/sse/subscribe?appId=xxx
     * Backend Heavy SDK subscribes to rule changes via SSE long connection.
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<FlagChangeMessage> subscribe(@RequestParam String appId) {
        log.info("SSE client subscribed for appId={}", appId);

        Sinks.Many<FlagChangeMessage> sink = appSinks.computeIfAbsent(appId,
                k -> Sinks.many().multicast().onBackpressureBuffer(1024, false));

        return sink.asFlux()
                .doOnSubscribe(s -> log.debug("SSE subscriber connected for appId={}", appId))
                .doOnCancel(() -> log.debug("SSE subscriber disconnected for appId={}", appId))
                .doOnError(e -> log.error("SSE error for appId={}: {}", appId, e.getMessage()))
                // Heartbeat keep-alive
                .mergeWith(heartbeat(appId));
    }

    /**
     * Push a change event to the specified App (called by the Redis listener).
     */
    public void pushChange(String appId, FlagChangeMessage message) {
        Sinks.Many<FlagChangeMessage> sink = appSinks.get(appId);
        if (sink != null) {
            var result = sink.tryEmitNext(message);
            if (result.isFailure()) {
                log.warn("Failed to push SSE message for appId={}, result={}", appId, result);
            }
        }
    }

    /**
     * SSE heartbeat Flux, sends empty events at the configured interval to keep the connection alive.
     */
    private Flux<FlagChangeMessage> heartbeat(String appId) {
        return Flux.interval(Duration.ofMillis(heartbeatIntervalMs))
                .map(tick -> {
                    // Send an empty message as heartbeat
                    return new FlagChangeMessage(appId, ChangeType.HEARTBEAT, null, 0L,
                            System.currentTimeMillis());
                })
                .doOnEach(signal -> {
                    if (signal.isOnNext()) {
                        log.trace("Heartbeat sent for appId={}", appId);
                    }
                });
    }
}