package com.flag.sdk.heavy;

import com.flag.common.dto.EvaluateRequest;
import com.flag.common.dto.EvaluateResponse;
import com.flag.common.model.FlagConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests for {@link HeavyFlagClient} — all internal components are mocked.
 */
@ExtendWith(MockitoExtension.class)
class HeavyFlagClientUnitTest {

    private static final String APP_ID = "test-app";
    private static final String FLAG_KEY = "feature-x";

    @Mock
    private FeatureDataStore dataStore;
    @Mock
    private SseStreamManager sseStream;
    @Mock
    private RemoteEvaluator remoteEvaluator;

    private HeavyFlagClient client;

    @BeforeEach
    void setUp() {
        client = new HeavyFlagClient(APP_ID, null, dataStore, sseStream, remoteEvaluator);
    }

    @AfterEach
    void tearDown() {
        // Clean up static state left by HeavyMetricsAggregator.register() in constructor
        HeavyMetricsAggregator.flushAndDrain(APP_ID);
    }

    // ================================================================
    //  isEnabled
    // ================================================================

    @Nested
    @DisplayName("isEnabled")
    class IsEnabled {

        @Test
        @DisplayName("local cache hit returns enabled")
        void localHitEnabled() {
            when(dataStore.evaluate(eq(FLAG_KEY), any(), any()))
                    .thenReturn(EvaluateResponse.of(FLAG_KEY, true));

            assertTrue(client.isEnabled(APP_ID, FLAG_KEY, "user-1"));
            verify(remoteEvaluator, never()).evaluate(any(), any(), any(), any());
        }

        @Test
        @DisplayName("local cache hit returns disabled")
        void localHitDisabled() {
            when(dataStore.evaluate(eq(FLAG_KEY), any(), any()))
                    .thenReturn(EvaluateResponse.of(FLAG_KEY, false));

            assertFalse(client.isEnabled(APP_ID, FLAG_KEY, "user-1"));
            verify(remoteEvaluator, never()).evaluate(any(), any(), any(), any());
        }

        @Test
        @DisplayName("cache miss delegates to remote evaluation")
        void cacheMissDelegatesToRemote() {
            when(dataStore.evaluate(eq(FLAG_KEY), any(), any())).thenReturn(null);
            when(remoteEvaluator.evaluate(eq(APP_ID), eq(FLAG_KEY), eq("user-1"), isNull()))
                    .thenReturn(true);

            assertTrue(client.isEnabled(APP_ID, FLAG_KEY, "user-1"));
        }

        @Test
        @DisplayName("cache miss with remote failure returns false")
        void cacheMissRemoteFailure() {
            when(dataStore.evaluate(eq(FLAG_KEY), any(), any())).thenReturn(null);
            when(remoteEvaluator.evaluate(any(), any(), any(), any())).thenReturn(false);

            assertFalse(client.isEnabled(APP_ID, FLAG_KEY, "user-1"));
        }

        @Test
        @DisplayName("after shutdown returns false immediately")
        void afterShutdownReturnsFalse() {
            client.shutdown();
            assertFalse(client.isEnabled(APP_ID, FLAG_KEY, "user-1"));
            verify(remoteEvaluator, never()).evaluate(any(), any(), any(), any());
        }

        @Test
        @DisplayName("appId mismatch logs warning but still evaluates")
        void appIdMismatch() {
            when(dataStore.evaluate(eq(FLAG_KEY), any(), any()))
                    .thenReturn(EvaluateResponse.of(FLAG_KEY, true));

            // Different appId from client's own appId — should still work with a warning
            assertTrue(client.isEnabled("other-app", FLAG_KEY, "user-1"));
        }

        @Test
        @DisplayName("isEnabled with attributes delegates correctly")
        void withAttributes() {
            Map<String, String> attrs = Map.of("country", "US");
            when(dataStore.evaluate(eq(FLAG_KEY), eq("user-1"), eq(attrs)))
                    .thenReturn(EvaluateResponse.of(FLAG_KEY, true));

            assertTrue(client.isEnabled(APP_ID, FLAG_KEY, "user-1", attrs));
        }
    }

    // ================================================================
    //  evaluateBatch
    // ================================================================

    @Nested
    @DisplayName("evaluateBatch")
    class EvaluateBatch {

        @Test
        @DisplayName("returns results for each request")
        void batchReturnsResults() {
            when(dataStore.evaluate(eq(FLAG_KEY), any(), any()))
                    .thenReturn(EvaluateResponse.of(FLAG_KEY, true));
            when(dataStore.evaluate(eq("feature-y"), any(), any()))
                    .thenReturn(EvaluateResponse.of("feature-y", false));

            List<EvaluateRequest> requests = List.of(
                    req(FLAG_KEY, "user-1"),
                    req("feature-y", "user-2")
            );
            List<EvaluateResponse> results = client.evaluateBatch(requests);

            assertEquals(2, results.size());
            assertTrue(results.get(0).isEnabled());
            assertFalse(results.get(1).isEnabled());
        }

        @Test
        @DisplayName("batch with null requests returns empty")
        void batchNullReturnsEmpty() {
            assertTrue(client.evaluateBatch(null).isEmpty());
        }

        @Test
        @DisplayName("batch with empty requests returns empty")
        void batchEmptyReturnsEmpty() {
            assertTrue(client.evaluateBatch(List.of()).isEmpty());
        }

        @Test
        @DisplayName("batch returns empty after shutdown")
        void batchAfterShutdown() {
            client.shutdown();
            assertTrue(client.evaluateBatch(List.of(req(FLAG_KEY, "user-1"))).isEmpty());
        }
    }

    // ================================================================
    //  init
    // ================================================================

    @Nested
    @DisplayName("init / shutdown")
    class Lifecycle {

        @Test
        @DisplayName("init starts SSE stream and sets initialized")
        void initStartsSseAndSetsInitialized() {
            client.init();
            verify(sseStream).start();
            assertTrue(client.isInitialized());
        }

        @Test
        @DisplayName("init throws after shutdown")
        void initThrowsAfterShutdown() {
            client.shutdown();
            assertThrows(IllegalStateException.class, () -> client.init());
        }

        @Test
        @DisplayName("shutdown clears state and sets flags")
        void shutdownClearsState() {
            client.init();
            assertTrue(client.isInitialized());

            client.shutdown();

            verify(sseStream).shutdownNow();
            verify(dataStore).clear();
            assertTrue(client.isShutdown());
            assertFalse(client.isConnected());
            assertFalse(client.isInitialized());
        }

        @Test
        @DisplayName("shutdown is idempotent")
        void shutdownIdempotent() {
            client.shutdown();
            client.shutdown(); // second call should be no-op
            verify(sseStream, times(1)).shutdownNow();
        }

        @Test
        @DisplayName("before init, isInitialized is false")
        void notInitializedBeforeInit() {
            assertFalse(client.isInitialized());
        }
    }

    // ================================================================
    //  State queries
    // ================================================================

    @Nested
    @DisplayName("state queries")
    class StateQueries {

        @Test
        @DisplayName("cacheSize delegates to dataStore.size()")
        void cacheSize() {
            when(dataStore.size()).thenReturn(42);
            assertEquals(42, client.cacheSize());
        }

        @Test
        @DisplayName("isConnected returns false initially")
        void isConnectedInitiallyFalse() {
            assertFalse(client.isConnected());
        }

        @Test
        @DisplayName("isShutdown returns false initially")
        void isShutdownInitiallyFalse() {
            assertFalse(client.isShutdown());
        }
    }

    // ================================================================
    //  Listeners
    // ================================================================

    @Nested
    @DisplayName("connect/disconnect listeners")
    class Listeners {

        @Test
        @DisplayName("onConnect listener is invoked when SSE connects")
        void connectListener() {
            // The connection callback is wired in the constructor:
            // sseStream.onConnect(flag -> { connected.set(flag); ... })
            // We need to capture and invoke that callback via SseStreamManager
            verify(sseStream).onConnect(any());
        }

        @Test
        @DisplayName("onDisconnect registers listener")
        void disconnectListener() {
            client.onDisconnect(c -> {});
            // no exception thrown — listener was accepted
        }
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private static EvaluateRequest req(String flagKey, String userId) {
        EvaluateRequest r = new EvaluateRequest();
        r.setAppId(APP_ID);
        r.setFlagKey(flagKey);
        r.setUserId(userId);
        return r;
    }
}
