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
    /** IngestService metrics endpoint — shared by periodic flush and shutdown flush */
    private static final String METRICS_ENDPOINT = "http://localhost:8082/api/v1/ingest/metrics";

    // ============================================================
    // Immutable configuration
    // ============================================================

    private final String evalServiceUrl;
    private final boolean defaultResult;

    // ============================================================
    // Java native HttpClient (no third-party dependencies)
    // ============================================================

    private final HttpClient httpClient;

    // ============================================================
    // Global aggregator — shared across all LightFlagClient instances
    // Single daemon thread, single HTTP client, batches ALL app counters
    // Stateless: appId is passed at method call time, not bound to instance.
    // ============================================================

    private static final GlobalMetricsAggregator aggregator = new GlobalMetricsAggregator();

    static {
        // JVM Shutdown Hook: ensures remaining counts are flushed on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(aggregator::shutdown, "flag-metrics-shutdown-hook"));
    }

    // ============================================================
    // Constructor — stateless, no appId binding
    // ============================================================

    public LightFlagClient(String evalServiceUrl, boolean defaultResult) {
        this.evalServiceUrl = Objects.requireNonNull(evalServiceUrl, "evalServiceUrl must not be null");
        this.defaultResult = defaultResult;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    public LightFlagClient(String evalServiceUrl) {
        this(evalServiceUrl, DEFAULT_RESULT);
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
    public List<EvaluateResponse> evaluateBatch(List<EvaluateRequest> requests) {
        if (requests == null || requests.isEmpty()) return List.of();

        try {
            // Batch POST: one HTTP call for N flags
            // appId is carried inside each EvaluateRequest body, not in URL
            // EvalService: POST /api/v1/eval/evaluate/batch
            StringBuilder body = new StringBuilder(requests.size() * 128);
            body.append('[');
            for (int i = 0; i < requests.size(); i++) {
                EvaluateRequest req = requests.get(i);
                if (i > 0) body.append(',');
                String appId = req.getAppId();
                body.append("{\"appId\":\"").append(escapeJson(appId))
                    .append("\",\"flagKey\":\"").append(escapeJson(req.getFlagKey()))
                    .append("\",\"userId\":\"").append(escapeJson(req.getUserId() != null ? req.getUserId() : ""))
                    .append("\"");
                if (req.getAttributes() != null && !req.getAttributes().isEmpty()) {
                    body.append(",\"attributes\":{");
                    boolean first = true;
                    for (Map.Entry<String, String> e : req.getAttributes().entrySet()) {
                        if (!first) body.append(',');
                        first = false;
                        body.append("\"").append(escapeJson(e.getKey()))
                            .append("\":\"").append(escapeJson(e.getValue() != null ? e.getValue() : ""))
                            .append("\"");
                    }
                    body.append('}');
                }
                body.append('}');
            }
            body.append(']');

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(evalServiceUrl + "/api/v1/eval/evaluate/batch"))
                    .header("Content-Type", "application/json")
                    .timeout(EVAL_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 && response.body() != null) {
                // Parse batch response: UnifiedResponse<List<EvaluateResponse>>
                // Extract "data" array from the JSON response
                List<EvaluateResponse> results = parseBatchResult(response.body());
                if (results != null && !results.isEmpty()) {
                    // Accumulate metrics for enabled flags
                    // Use the appId from the first request since all share the same appId
                    String batchAppId = requests.getFirst().getAppId();
                    for (EvaluateResponse r : results) {
                        if (r.isEnabled()) {
                            aggregator.increment(batchAppId, r.getFlagKey());
                        }
                    }
                    return results;
                }
            }
        } catch (Exception e) {
            // Fallback to single evaluations on batch failure
        }

        // Fallback: sequential single evaluations
        List<EvaluateResponse> results = new ArrayList<>(requests.size());
        for (EvaluateRequest req : requests) {
            boolean enabled = isEnabled(req.getAppId(), req.getFlagKey(), req.getUserId(), req.getAttributes());
            results.add(EvaluateResponse.of(req.getFlagKey(), enabled));
        }
        return results;
    }

    /**
     * Parse the batch result JSON from UnifiedResponse wrapper.
     * Returns null on any parse error (triggers single-eval fallback).
     */
    private static List<EvaluateResponse> parseBatchResult(String json) {
        // Find "data":[{...}] array
        int dataStart = json.indexOf("\"data\":");
        if (dataStart < 0) return null;
        dataStart += 7; // skip past "data":
        // Skip whitespace
        while (dataStart < json.length() && json.charAt(dataStart) == ' ') dataStart++;
        if (dataStart >= json.length() || json.charAt(dataStart) != '[') return null;

        // Find the matching closing bracket
        int depth = 0;
        int arrayEnd = -1;
        for (int i = dataStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) { arrayEnd = i + 1; break; }
            }
        }
        if (arrayEnd < 0) return null;

        String arrayJson = json.substring(dataStart, arrayEnd);
        // Simple parser for array of objects with "flagKey" and "enabled"
        List<EvaluateResponse> results = new ArrayList<>();
        int pos = 0;
        while (pos < arrayJson.length()) {
            int objStart = arrayJson.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = arrayJson.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj = arrayJson.substring(objStart, objEnd + 1);

            String flagKey = extractJsonString(obj, "flagKey");
            boolean enabled = extractJsonBool(obj, "enabled");
            if (flagKey != null) {
                results.add(EvaluateResponse.of(flagKey, enabled));
            }

            pos = objEnd + 1;
        }
        return results.isEmpty() ? null : results;
    }

    // ============================================================
    // Global metrics aggregator (static, shared across instances)
    // One daemon thread, one HTTP client, batches ALL app counters
    // ============================================================

    static class GlobalMetricsAggregator {

        private final ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicLong>> counters
                = new ConcurrentHashMap<>();
        private final ScheduledExecutorService scheduler;

        /**
         * Shared singleton HttpClient for all flush operations.
         * Created once, reused for the lifetime of the JVM.
         * Eliminates the overhead of creating a new HttpClient per flush cycle.
         */
        private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();

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
                            .uri(URI.create(METRICS_ENDPOINT))
                            .header("Content-Type", "application/json")
                            .timeout(INGEST_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build();

                    // Each appId gets its own POST, but all share the same scheduler and timer
                    SHARED_HTTP_CLIENT
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
                            .uri(URI.create(METRICS_ENDPOINT))
                            .header("Content-Type", "application/json")
                            .timeout(INGEST_TIMEOUT)
                            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                            .build();

                    // BLOCKING send — guarantees delivery before shutdown proceeds
                    SHARED_HTTP_CLIENT
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
        // No-op: LightFlagClient is stateless, no per-instance state to release.
        // Global aggregator shutdown is handled by JVM shutdown hook.
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

    /**
     * Extract a String value for a key from a JSON object.
     * e.g. extractJsonString("{\"flagKey\":\"flag-a\"}", "flagKey") -> "flag-a"
     */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        // Unescape basic sequences
        String raw = json.substring(start, end);
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> sb.append(c).append(next);
                }
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Extract a boolean value for a key from a JSON object.
     * e.g. extractJsonBool("{\"enabled\":true}", "enabled") -> true
     */
    private static boolean extractJsonBool(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return false;
        idx += search.length();
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        return idx < json.length() && json.charAt(idx) == 't';
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
