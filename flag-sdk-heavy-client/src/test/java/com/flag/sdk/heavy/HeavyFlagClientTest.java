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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HeavyFlagClient 工业级单元测试。
 * <p>
 * 测试策略：
 * - 使用 OkHttp MockWebServer 模拟 EvalService HTTP 端点
 * - 覆盖双通道初始化、SSE 实时推送、指数退避断线重连三大核心能力
 * - 所有测试使用 CountDownLatch 精确控制异步时序
 */
class HeavyFlagClientTest {

    private MockWebServer mockServer;
    private HeavyFlagClient client;
    private ObjectMapper mapper;

    private static final String APP_ID = "test-app";
    private static final String FLAG_KEY_1 = "feature-x";
    private static final String FLAG_KEY_2 = "feature-y";

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
    }

    // ============================================================
    // 场景 1：启动初始化成功
    // ============================================================

    @Test
    @DisplayName("启动初始化成功：Mock 快照返回，验证本地缓存正确加载")
    void testInitSuccess() throws Exception {
        // ---- 安排 (Arrange) ----
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        // 准备快照响应（模拟 EvalService UnifiedResponse 格式）
        Map<String, Object> snapshotData = Map.of(
                FLAG_KEY_1, Map.of(
                        "flagKey", FLAG_KEY_1,
                        "name", "Feature X",
                        "enabled", true,
                        "ruleConfig", "",
                        "version", 1
                ),
                FLAG_KEY_2, Map.of(
                        "flagKey", FLAG_KEY_2,
                        "name", "Feature Y",
                        "enabled", false,
                        "ruleConfig", "",
                        "version", 1
                )
        );
        Map<String, Object> snapshotResponse = Map.of(
                "code", 0,
                "message", "success",
                "data", snapshotData
        );
        String snapshotJson = mapper.writeValueAsString(snapshotResponse);

        // 第一次请求：/api/v1/eval/flags?appId=test-app → 返回快照
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(snapshotJson));

        // SSE 订阅端点：返回空流（测试不关心 SSE）
        enqueueEmptySse();

        // ---- 动作 (Act) ----
        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        // ---- 断言 (Assert) ----
        // 验证本地缓存大小
        assertEquals(2, client.cacheSize(), "Should have 2 flags in cache");

        // 验证缓存内容
        assertTrue(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"),
                "feature-x should be enabled");
        assertFalse(client.isEnabled(APP_ID, FLAG_KEY_2, "user-1"),
                "feature-y should be disabled");

        // 验证发出的 HTTP 请求路径
        RecordedRequest snapshotRequest = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(snapshotRequest);
        assertTrue(snapshotRequest.getPath().contains("/api/v1/eval/flags"));
        assertTrue(snapshotRequest.getPath().contains("appId=" + APP_ID));

        // 验证 SSE 订阅请求
        RecordedRequest sseRequest = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(sseRequest);
        assertTrue(sseRequest.getPath().contains("/api/v1/eval/sse/subscribe"));
    }

    // ============================================================
    // 场景 2：启动初始化失败，优雅降级
    // ============================================================

    @Test
    @DisplayName("启动初始化失败降级：快照返回 500，SDK 仍能初始化且评估降级为 false")
    void testInitFailureDegradation() throws Exception {
        // ---- 安排 ----
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        // 快照端点返回 500 错误
        mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"internal error\"}"));

        // SSE 订阅端点：返回空流
        enqueueEmptySse();

        // ---- 动作 ----
        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        // ---- 断言 ----
        // 快速失败后缓存为空，但 SDK 不应抛异常
        assertEquals(0, client.cacheSize(),
                "Cache should be empty after failed snapshot load");

        // 评估应降级为 false（兜底默认值）
        assertFalse(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"),
                "Should degrade to false when cache is empty");

        // 确认请求发到了 /flags 端点
        RecordedRequest request = mockServer.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request);
        assertTrue(request.getPath().contains("/api/v1/eval/flags"));
    }

    // ============================================================
    // 场景 3：SSE 实时变更推送
    // ============================================================

    @Test
    @DisplayName("SSE 实时变更：收到 INSERT/UPDATE/DELETE 消息后本地缓存被精准刷新")
    void testSseRealTimeUpdates() throws Exception {
        // ---- 安排 ----
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        // 初始快照：包含一个功能开关
        Map<String, Object> snapshotData = Map.of(
                FLAG_KEY_1, Map.of(
                        "flagKey", FLAG_KEY_1,
                        "name", "Feature X",
                        "enabled", true,
                        "ruleConfig", "",
                        "version", 1
                )
        );
        Map<String, Object> snapshotResponse = Map.of(
                "code", 0,
                "message", "success",
                "data", snapshotData
        );
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(snapshotResponse)));

        // SSE 流：先发送 CREATE 事件，然后发送 UPDATE 事件，最后关闭
        String createEvent = sseEvent(Map.of(
                "appId", APP_ID,
                "changeType", "CREATE",
                "flagKey", FLAG_KEY_2,
                "timestamp", System.currentTimeMillis()
        ));
        String updateEvent = sseEvent(Map.of(
                "appId", APP_ID,
                "changeType", "UPDATE",
                "flagKey", FLAG_KEY_1,
                "timestamp", System.currentTimeMillis()
        ));

        mockServer.enqueue(createSseMockResponse(createEvent + updateEvent));

        // 远程评估端点：返回 enabled=true（用于 SSE CREATE/UPDATE 后回填缓存）
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of(
                        "code", 0,
                        "message", "success",
                        "data", Map.of(
                                "flagKey", FLAG_KEY_2,
                                "enabled", true,
                                "matchedRule", "rule-matched",
                                "evalCostMs", 1
                        )
                ))));
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of(
                        "code", 0,
                        "message", "success",
                        "data", Map.of(
                                "flagKey", FLAG_KEY_1,
                                "enabled", false,
                                "matchedRule", "rule-matched",
                                "evalCostMs", 1
                        )
                ))));

        // ---- 动作 ----
        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        // 等待 SSE 消息处理完成
        Thread.sleep(1500);

        // ---- 断言 ----
        // CREATE → feature-y 应出现在缓存中
        assertTrue(client.isConnected(), "Should be connected via SSE");

        // 等待远程评估结果回填缓存
        // 由于 CREATE/UPDATE 触发的远程评估是异步的，需要等待
        waitForCacheSize(client, 2, 3000);

        // UPDATE → feature-x 应被更新为 false
        assertFalse(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"),
                "feature-x should be disabled after UPDATE event");
        assertTrue(client.isEnabled(APP_ID, FLAG_KEY_2, "user-1"),
                "feature-y should be enabled after CREATE event");
    }

    // ============================================================
    // 场景 4：SSE 断线重连（指数退避）
    // ============================================================

    @Test
    @DisplayName("SSE 断线重连：连接断开后自动触发指数退避重连")
    void testSseReconnectionWithExponentialBackoff() throws Exception {
        // ---- 安排 ----
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        // 初始快照
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of(
                        "code", 0, "message", "success",
                        "data", Map.of(FLAG_KEY_1, Map.of(
                                "flagKey", FLAG_KEY_1,
                                "name", "Feature X",
                                "enabled", true,
                                "ruleConfig", "",
                                "version", 1
                        ))
                ))));

        // 第一次 SSE → 立即关闭（模拟断连）
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"changeType\":\"HEARTBEAT\",\"appId\":\"" + APP_ID + "\",\"timestamp\":0}\n\n"));

        // 第二次连接（重连后的新 SSE → 同样立即关闭，后续重连将逐步退避）
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"changeType\":\"HEARTBEAT\",\"appId\":\"" + APP_ID + "\",\"timestamp\":0}\n\n"));

        // 第三次连接（这次保持足够长时间以让重连成功）
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBodyDelay(3, TimeUnit.SECONDS)
                .setBody("data: {\"changeType\":\"HEARTBEAT\",\"appId\":\"" + APP_ID + "\",\"timestamp\":0}\n\n"));

        // 监控重连次数
        AtomicInteger reconnectCount = new AtomicInteger(0);
        CountDownLatch reconnectedLatch = new CountDownLatch(1);

        // ---- 动作 ----
        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.onConnect(c -> {
            int count = reconnectCount.incrementAndGet();
            log("SSE connected (attempt #" + count + ")");
            if (count >= 3) {
                reconnectedLatch.countDown();
            }
        });
        client.init();

        // 等待第三次重连（指数退避：1s + 2s + 0.5 jitter，5s 内应完成）
        boolean reconnected = reconnectedLatch.await(8, TimeUnit.SECONDS);

        // ---- 断言 ----
        assertTrue(reconnected, "Should reconnect after SSE disconnection");
        assertTrue(reconnectCount.get() >= 2,
                "Should have attempted reconnection at least 2 times, got " + reconnectCount.get());
        assertTrue(client.isConnected(), "Client should be connected after reconnection");
        assertEquals(1, client.cacheSize(), "Cache should still have the initial flag");

        // 确认至少收到了 1 个 SSE 订阅请求（不包括快照请求）
        // 过滤掉 /flags 请求，只计数 /sse/subscribe 请求
        int sseRequestCount = 0;
        for (int i = 0; i < 10; i++) {
            RecordedRequest req = mockServer.takeRequest(1, TimeUnit.SECONDS);
            if (req == null) break;
            if (req.getPath() != null && req.getPath().contains("/api/v1/eval/sse/subscribe")) {
                sseRequestCount++;
            }
        }
        assertTrue(sseRequestCount >= 2,
                "Should have at least 2 SSE subscribe requests, got " + sseRequestCount);
    }

    // ============================================================
    // 场景 5：优雅关闭
    // ============================================================

    @Test
    @DisplayName("优雅关闭：shutdown 后所有评估降级，连接断开")
    void testGracefulShutdown() throws Exception {
        // ---- 安排 ----
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of(
                        "code", 0, "message", "success",
                        "data", Map.of(FLAG_KEY_1, Map.of(
                                "flagKey", FLAG_KEY_1,
                                "name", "Feature X",
                                "enabled", true,
                                "ruleConfig", "",
                                "version", 1
                        ))
                ))));
        enqueueEmptySse();

        // ---- 动作 ----
        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        // 确认启动正常
        assertTrue(client.isInitialized());
        assertTrue(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"));

        // 优雅关闭
        client.shutdown();

        // ---- 断言 ----
        assertTrue(client.isShutdown());
        assertFalse(client.isInitialized());
        assertFalse(client.isConnected());
        assertEquals(0, client.cacheSize());
        assertFalse(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"),
                "After shutdown, all flags should be false");
    }

    // ============================================================
    // 场景 6：缓存穿透 → 远程评估降级
    // ============================================================

    @Test
    @DisplayName("缓存穿透：未命中缓存时自动调用远程评估")
    void testCacheMissRemoteEvaluate() throws Exception {
        // ---- 安排 ----
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        // 快照：返回空（不包含任何 flag）
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of(
                        "code", 0, "message", "success",
                        "data", Map.of()
                ))));
        enqueueEmptySse();

        // 远程评估端点：返回 enabled=true
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of(
                        "code", 0, "message", "success",
                        "data", Map.of(
                                "flagKey", FLAG_KEY_1,
                                "enabled", true,
                                "matchedRule", "remote-eval",
                                "evalCostMs", 2
                        )
                ))));

        // ---- 动作 ----
        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        assertEquals(0, client.cacheSize());

        // 调用评估（缓存穿透）
        boolean result = client.isEnabled(APP_ID, FLAG_KEY_1, "user-1");

        // ---- 断言 ----
        assertTrue(result, "Remote evaluate should return true");

        // 远程评估结果应回填缓存
        assertTrue(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"),
                "After remote fill, local cache should return enabled");
    }

    @Test
    @DisplayName("远程评估失败：缓存穿透 + 远程 500，静默降级为 false")
    void testRemoteEvaluateFailureDegradation() throws Exception {
        // ---- 安排 ----
        String serverUrl = mockServer.url("").toString().replaceAll("/$", "");

        // 快照：空缓存
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mapper.writeValueAsString(Map.of(
                        "code", 0, "message", "success",
                        "data", Map.of()
                ))));
        enqueueEmptySse();

        // 远程评估返回 500
        mockServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"server error\"}"));

        // ---- 动作 ----
        client = new HeavyFlagClient(APP_ID, serverUrl, null);
        client.init();

        // 调用评估（缓存穿透 → 远程失败）
        boolean result = client.isEnabled(APP_ID, FLAG_KEY_1, "user-1");

        // ---- 断言 ----
        assertFalse(result, "Should degrade to false on remote error");
        // 缓存不应回填（远程失败）
        assertFalse(client.isEnabled(APP_ID, FLAG_KEY_1, "user-1"),
                "Cache should not be filled after remote failure");
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 入队一个空的 SSE 流（用于不关心 SSE 的场景）。
     */
    private void enqueueEmptySse() {
        mockServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"changeType\":\"HEARTBEAT\",\"appId\":\"" + APP_ID + "\",\"timestamp\":0}\n\n"));
    }

    /**
     * 构建 SSE 事件格式字符串。
     * {@code data: {json}
     *
     * }
     */
    private String sseEvent(Map<String, Object> data) {
        try {
            return "data: " + mapper.writeValueAsString(data) + "\n\n";
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 创建 SSE MockResponse。
     */
    private MockResponse createSseMockResponse(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body);
    }

    /**
     * 轮询等待缓存达到预期大小。
     */
    private void waitForCacheSize(HeavyFlagClient client, int expectedSize, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.cacheSize() >= expectedSize) {
                return;
            }
            Thread.sleep(200);
        }
    }

    /**
     * 简易日志。
     */
    private void log(String msg) {
        System.out.println("[TEST] " + msg);
    }
}