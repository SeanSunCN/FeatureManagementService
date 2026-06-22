package com.flag.sdk.heavy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HeavyFlagClientTest {

    private MockWebServer mockServer;
    private HeavyFlagClient client;
    private ObjectMapper mapper;

    private static final String APP_ID = "test-app";
    private static final String FLAG_KEY_1 = "feature-x";

    @BeforeEach
    void setUp() {
        mockServer = new MockWebServer();
        mapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }
        mockServer.shutdown();
        HeavyMetricsAggregator.flushAndDrain(APP_ID);
    }

    @Test
    @DisplayName("init: snapshot loads flags into cache")
    void testInitSuccess() throws Exception {
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        enqueueSnapshot(Map.of(
                FLAG_KEY_1, Map.of("flagKey", FLAG_KEY_1, "name", "Feature X",
                        "enabled", true, "ruleConfig", "", "version", 1)
        ));
        enqueueEmptySse();

        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        assertEquals(1, client.cacheSize());

        RecordedRequest snapshotReq = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(snapshotReq);
        assertTrue(snapshotReq.getPath().contains("/api/v1/eval/flags"));
    }

    @Test
    @DisplayName("init: snapshot 500 degrades gracefully")
    void testInitFailureDegradation() throws Exception {
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"internal error\"}"));
        enqueueEmptySse();

        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        assertEquals(0, client.cacheSize());
        assertFalse(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"));
    }

    @Test
    @DisplayName("cache miss: remote evaluate returns enabled")
    void testCacheMissRemoteEvaluate() throws Exception {
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        enqueueSnapshot(Map.of());
        enqueueEmptySse();

        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of(
                        "code", 0, "message", "success",
                        "data", Map.of("enabled", true)
                ))));

        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        assertEquals(0, client.cacheSize());
        assertTrue(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"));
    }

    @Test
    @DisplayName("cache miss: remote 500 degrades to false")
    void testRemoteEvaluateFailureDegradation() throws Exception {
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        enqueueSnapshot(Map.of());
        enqueueEmptySse();

        mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"server error\"}"));

        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        assertFalse(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"));
    }

    @Test
    @DisplayName("shutdown: all evaluations return false")
    void testGracefulShutdown() throws Exception {
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        enqueueSnapshot(Map.of(
                FLAG_KEY_1, Map.of("flagKey", FLAG_KEY_1, "name", "Feature X",
                        "enabled", true, "ruleConfig", "", "version", 1)
        ));
        enqueueEmptySse();

        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        assertTrue(client.isInitialized());
        assertTrue(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"));

        client.shutdown();

        assertTrue(client.isShutdown());
        assertFalse(client.isInitialized());
        assertFalse(client.isConnected());
        assertEquals(0, client.cacheSize());
        assertFalse(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"));
    }

    private void enqueueSnapshot(Map<String, Object> data) throws Exception {
        Map<String, Object> response = Map.of("code", 0, "message", "success", "data", data);
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(response)));
    }

    private void enqueueEmptySse() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"changeType\":\"HEARTBEAT\",\"appId\":\"" + APP_ID + "\",\"timestamp\":0}\n\n"));
    }
}
