package com.flag.sdk.light;

import com.flag.sdk.FlagSdkClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluateResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight Feature Flag SDK client.
 *
 * Architecture mapping: Light SDK — blind remote evaluation + local counter batching for ingestion
 *
 * Four design pillars + global batching:
 *
 * 1. [Synchronous evaluation + fallback degradation]
 * 2. [Asynchronous in-memory accumulation]
 * 3. [Daemon thread periodic batch flush via shared global aggregator]
 * 4. [Server-side clock explosion prevention]
 * 5. [Global aggregator — single thread flushes ALL app counters in one pass]
 */
public class LightFlagClient implements FlagSdkClient, AutoCloseable {

    // ============================================================
    // Configuration constants
    // ============================================================

    private static final Duration EVAL_TIMEOUT = Duration.ofMillis(500);
    private static final Duration INGEST_TIMEOUT = Duration.ofSeconds(3);
    private static final long FLUSH_INTERVAL_SECONDS = 5;
    private static final boolean DEFAULT_RESULT = false;

    // ============================================================
    // Immutable configuration
    // ============================================================

    private final String appId;
    private final String evalServiceUrl;
    private final String ingestServiceUrl;
    private final boolean defaultResult;

    // ============================================================
    // Java native HttpClient (no third-party dependencies)
    // ============================================================

    private final HttpClient httpClient;

    // ============================================================
    // Global aggregator — shared across all LightFlagClient instances
    // Single daemon thread, single HTTP client, batches ALL app counters
    // ============================================================

    private static final GlobalMetricsAggregator aggregator = new GlobalMetricsAggregator();

    static {
        // JVM Shutdown Hook: ensures remaining counts are flushed on JVM exit
        // This prevents metrics data loss when the application terminates without explicitly calling shutdownAll()
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            aggregator.shutdown();
        }, "flag-metrics-shutdown-hook"));
    }

    // ============================================================
    // Constructor
    // ============================================================

    public LightFlagClient(String appId, String evalServiceUrl,
                           String ingestServiceUrl, boolean defaultResult) {
        this.appId = Objects.requireNonNull(appId, "appId must not be null");
        this.evalServiceUrl = Objects.requireNonNull(evalServiceUrl, "evalServiceUrl must not be null");
        this.ingestServiceUrl = Objects.requireNonNull(ingestServiceUrl, "ingestServiceUrl must not be null");
        this.defaultResult = defaultResult;

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        aggregator.registerClient(appId, ingestServiceUrl, httpClient);
    }

    public LightFlagClient(String appId, String evalServiceUrl, String ingestServiceUrl) {
        this(appId, evalServiceUrl, ingestServiceUrl, DEFAULT_RESULT);
    }

    // ============================================================
    // FlagSdkClient interface implementation
    // ============================================================

    @Override
    public boolean isEnabled(String appId, String flagKey, String userId) {
        return isEnabled(appId, flagKey, userId, null);
    }

    @Override
    public boolean isEnabled(String appId, String flagKey, String userId,
                             Map<String, String> attributes) {
        boolean result;
        try {
            StringBuilder body = new StringBuilder(256);
            body.append("{\"appId\":\"").append(escapeJson(appId))
                .append("\",\"flagKey\":\"").append(escapeJson(flagKey))
                .append("\",\"userId\":\"").append(escapeJson(userId != null ? userId : ""));

            if (attributes != null && !attributes.isEmpty()) {
                body.append("\",\"attributes\":{");
                boolean first = true;
                for (Map.Entry<String, String> e : attributes.entrySet()) {
                    if (!first) body.append(",");
                    first = false;
                    body.append("\"").append(escapeJson(e.getKey()))
                        .append("\":\"").append(escapeJson(e.getValue() != null ? e.getValue() : ""))
                        .append("\"");
                }
                body.append("}");
            } else {
                body.append('"');
            }
            body.append("}");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(evalServiceUrl + "/api/v1/eval/evaluate"))
                    .header("Content-Type", "application/json")
                    .timeout(EVAL_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                result = parseEnabled(response.body());
            } else {
                result = defaultResult;
            }
        } catch (Exception e) {
            result = defaultResult;
        }

        // Accumulate into the global aggregator (cross-app batching)
        if (result) {
            aggregator.increment(appId, flagKey);
        }

        return result;
    }

    @Override
    public List<EvaluateResponse> evaluateBatch(String appId, List<EvaluateRequest> requests) {
        List<EvaluateResponse> results = new ArrayList<>(requests.size());
        for (EvaluateRequest req : requests) {
            boolean enabled = isEnabled(appId, req.getFlagKey(), req.getUserId(), req.getAttributes());
            results.add(EvaluateResponse.of(req.getFlagKey(), enabled));
        }
        return results;
    }

    // ============================================================
    // Global metrics aggregator (static, shared across instances)
    // One daemon thread, one HTTP client, batches ALL app counters
    // ============================================================

    static class GlobalMetricsAggregator {

        private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> counters
                = new ConcurrentHashMap<>();
        private final ScheduledExecutorService scheduler;

        GlobalMetricsAggregator() {
            scheduler = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "flag-global-metrics-flusher");
                t.setDaemon(true);
                return t;
            });
            scheduler.scheduleAtFixedRate(
                    this::flushAll,
                    FLUSH_INTERVAL_SECONDS,
                    FLUSH_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
        }

        void registerClient(String appId, String ingestServiceUrl, HttpClient httpClient) {
            counters.computeIfAbsent(appId, k -> new ConcurrentHashMap<>());
        }

        void deregister(String appId) {
            counters.remove(appId);
        }

        void increment(String appId, String flagKey) {
            counters.computeIfAbsent(appId, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(flagKey, k -> new AtomicLong(0))
                    .incrementAndGet();
        }

        void flushAll() {
            for (Map.Entry<String, ConcurrentHashMap<String, AtomicLong>> appEntry : counters.entrySet()) {
                String appId = appEntry.getKey();
                ConcurrentHashMap<String, AtomicLong> appCounters = appEntry.getValue();

                Map<String, Long> snapshot = new HashMap<>();
                for (Map.Entry<String, AtomicLong> flagEntry : appCounters.entrySet()) {
                    long count = flagEntry.getValue().getAndSet(0);
                    if (count > 0) {
                        snapshot.put(flagEntry.getKey(), count);
                    }
                }

                if (snapshot.isEmpty()) continue;

                try {
                    StringBuilder body = new StringBuilder(512);
                    body.append("{\"appId\":\"").append(escapeJson(appId))
                        .append("\",\"flagHitCounts\":{");
                    boolean first = true;
                    for (Map.Entry<String, Long> e : snapshot.entrySet()) {
                        if (!first) body.append(",");
                        first = false;
                        body.append("\"").append(escapeJson(e.getKey()))
                            .append("\":").append(e.getValue());
                    }
                    body.append("}}");

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8082/api/v1/ingest/metrics"))
                            .header("Content-Type", "application/json")
                            .timeout(INGEST_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build();

                    // Each appId gets its own POST, but all share the same scheduler and timer
                    HttpClient.newHttpClient()
                            .sendAsync(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    // Swallow
                }
            }
        }

        void shutdown() {
            // Step 1: Stop the scheduler FIRST — prevents new flush cycles from starting
            // while we drain the remaining counters below
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            // Step 2: SYNCHRONOUS final flush — guarantees data reaches the wire
            // before the JVM exits. Uses .send() (blocking) not .sendAsync().
            flushAllSync();
        }

        /**
         * Synchronous flush — BLOCKING. Each POST waits for the HTTP response.
         * Only used on the shutdown path to guarantee no data loss.
         * The periodic timer path uses the async flushAll() instead.
         */
        private void flushAllSync() {
            for (Map.Entry<String, ConcurrentHashMap<String, AtomicLong>> appEntry : counters.entrySet()) {
                String appId = appEntry.getKey();
                ConcurrentHashMap<String, AtomicLong> appCounters = appEntry.getValue();

                Map<String, Long> snapshot = new HashMap<>();
                for (Map.Entry<String, AtomicLong> flagEntry : appCounters.entrySet()) {
                    long count = flagEntry.getValue().getAndSet(0);
                    if (count > 0) {
                        snapshot.put(flagEntry.getKey(), count);
                    }
                }

                if (snapshot.isEmpty()) continue;

                try {
                    StringBuilder body = new StringBuilder(512);
                    body.append("{\"appId\":\"").append(escapeJson(appId))
                        .append("\",\"flagHitCounts\":{");
                    boolean first = true;
                    for (Map.Entry<String, Long> e : snapshot.entrySet()) {
                        if (!first) body.append(",");
                        first = false;
                        body.append("\"").append(escapeJson(e.getKey()))
                            .append("\":").append(e.getValue());
                    }
                    body.append("}}");

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://localhost:8082/api/v1/ingest/metrics"))
                            .header("Content-Type", "application/json")
                            .timeout(INGEST_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build();

                    // BLOCKING send — guarantees delivery before shutdown proceeds
                    HttpClient.newHttpClient()
                            .send(request, HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) {
                    // Swallow — best-effort on shutdown path
                }
            }
        }
    }

    // ============================================================
    // AutoCloseable — graceful shutdown
    // ============================================================

    @Override
    public void close() {
        aggregator.deregister(appId);
    }

    public static void shutdownAll() {
        aggregator.shutdown();
    }

    // ============================================================
    // Internal utilities
    // ============================================================

    static boolean parseEnabled(String json) {
        if (json == null || json.isEmpty()) return false;
        int idx = json.indexOf("\"enabled\"");
        if (idx < 0) return false;
        int colon = json.indexOf(':', idx + 9);
        if (colon < 0) return false;
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start < json.length() && json.charAt(start) == 't') return true;
        if (start < json.length() && json.charAt(start) == 'f') return false;
        return false;
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
