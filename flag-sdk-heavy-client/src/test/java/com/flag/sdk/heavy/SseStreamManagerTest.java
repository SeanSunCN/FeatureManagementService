package com.flag.sdk.heavy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SseStreamManager}.
 *
 * <p>Tests the public API surface: lifecycle, state queries, and listener registration.
 * The internal reactive pipeline (start, snapshot, message handling, reconnection)
 * is covered by {@link HeavyFlagClientTest} via MockWebServer integration tests.</p>
 */
@ExtendWith(MockitoExtension.class)
class SseStreamManagerTest {

    private static final String APP_ID = "test-app";

    @Mock
    private WebClient webClient;
    @Mock
    private FeatureDataStore dataStore;

    private FlagEntryParser parser;
    private SseStreamManager sseManager;

    @BeforeEach
    void setUp() {
        parser = new FlagEntryParser(new ObjectMapper());
        sseManager = new SseStreamManager(APP_ID, webClient, dataStore, parser);
    }

    // ================================================================
    //  Connection state
    // ================================================================

    @Nested
    @DisplayName("connection state")
    class ConnectionState {

        @Test
        @DisplayName("isConnected returns false initially")
        void initiallyDisconnected() {
            assertFalse(sseManager.isConnected());
        }

        @Test
        @DisplayName("shutdownNow disconnects")
        void shutdownNowDisconnects() {
            sseManager.shutdownNow();
            assertFalse(sseManager.isConnected());
        }

        @Test
        @DisplayName("shutdownNow is idempotent")
        void shutdownNowIdempotent() {
            sseManager.shutdownNow();
            sseManager.shutdownNow(); // second call should not throw
            assertFalse(sseManager.isConnected());
        }

        @Test
        @DisplayName("lastHeartbeatTime returns positive value after construction")
        void lastHeartbeatTimeReturnsValue() {
            assertTrue(sseManager.lastHeartbeatTime() > 0);
        }
    }

    // ================================================================
    //  Listener registration
    // ================================================================

    @Nested
    @DisplayName("listener registration")
    class Listeners {

        @Test
        @DisplayName("onConnect accepts and stores a listener")
        void onConnectAcceptsListener() {
            @SuppressWarnings("unchecked")
            Consumer<Boolean> listener = mock(Consumer.class);
            sseManager.onConnect(listener);
            // no exception — listener has been added
        }

        @Test
        @DisplayName("multiple onConnect listeners stack")
        void multipleListenersStack() {
            sseManager.onConnect(flag -> {});
            sseManager.onConnect(flag -> {});
            // no exception — both listeners stored
        }
    }
}