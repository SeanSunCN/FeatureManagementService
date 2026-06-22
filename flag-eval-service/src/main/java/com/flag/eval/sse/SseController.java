package com.flag.eval.sse;

import com.flag.common.dto.FlagChangeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
 * <p>
 * Heartbeat is protocol-level (SSE comment line), NOT a fake business DTO.
 * This prevents protocol pollution: the SDK never receives a "HEARTBEAT" event
 * that looks like a real FlagChangeMessage with null fields.
 */
@RestController
@RequestMapping("/api/v1/eval/sse")
public class SseController {

    private static final Logger log = LoggerFactory.getLogger(SseController.class);

    private final long heartbeatIntervalMs;

    /**
     * Sink map isolated by AppId.
     */
    private final Map<String, Sinks.Many<FlagChangeMessage>> appSinks = new ConcurrentHashMap<>();

    /**
     * A shared, infinite heartbeat Flux (comment-line keepalive).
     * One instance for all subscribers — no per-subscription overhead.
     */
    private final Flux<ServerSentEvent<?>> sharedHeartbeat;

    public SseController(
                         org.springframework.core.env.Environment env) {
        this.heartbeatIntervalMs = env.getProperty("app.sse.heartbeat-interval-ms",
                long.class, 30000L);
        // Standard SSE comment-line heartbeat: ": ping\n\n"
        // Zero protocol pollution — no business DTO leaked into the transport layer.
        this.sharedHeartbeat = Flux.interval(Duration.ofMillis(heartbeatIntervalMs))
                .map(tick -> ServerSentEvent.builder()
                        .comment("ping")
                        .build());
    }

    /**
     * GET /api/v1/eval/sse/subscribe?appId=xxx
     * Backend Heavy SDK subscribes to rule changes via SSE long connection.
     * Returns {@code Flux<ServerSentEvent<?>>} for protocol-level SSE compliance.
     * Business payloads are wrapped in SSE {@code event:flag-change} and {@code data:...};
     * heartbeats are standard {@code :ping} comment lines.
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<?>> subscribe(@RequestParam String appId) {
        log.info("SSE client subscribed for appId={}", appId);

        Sinks.Many<FlagChangeMessage> sink = appSinks.computeIfAbsent(appId,
                k -> Sinks.many().multicast().onBackpressureBuffer(1024, true));

        // Business event stream: wrap FlagChangeMessage into SSE event:flag-change
        Flux<ServerSentEvent<?>> businessEvents = sink.asFlux()
                .map(msg -> ServerSentEvent.builder(msg)
                        .event("flag-change")
                        .build());

        // Merge with the shared heartbeat, then attach lifecycle logging
        return Flux.merge(businessEvents, sharedHeartbeat)
                .doOnSubscribe(s -> log.debug("SSE subscriber connected for appId={}", appId))
                .doOnCancel(() ->
                    log.debug("SSE subscriber disconnected for appId={}", appId))
                .doOnError(e ->
                    log.error("SSE error for appId={}: {}", appId, e.getMessage()));
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
}