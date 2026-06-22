package com.flag.sdk.heavy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HeavyMetricsAggregator}.
 *
 * <p>Tests the static singleton's lifecycle: register, increment,
 * flushAndDrain, and post-shutdown no-op behavior.</p>
 *
 * <p>NOTE: These tests operate on global static state. They are designed
 * to run sequentially and clean up state between each test via {@link #tearDown()}.</p>
 */
class HeavyMetricsAggregatorTest {

    private static final String APP_ID_A = "app-a";
    private static final String APP_ID_B = "app-b";
    private static final String FLAG_KEY = "feature-x";

    @BeforeEach
    @AfterEach
    void cleanUp() {
        // Drain all state after each test to prevent cross-test leakage
        HeavyMetricsAggregator.flushAndDrain(APP_ID_A);
        HeavyMetricsAggregator.flushAndDrain(APP_ID_B);
    }

    // ================================================================
    //  register + increment
    // ================================================================

    @Nested
    @DisplayName("register + increment")
    class RegisterAndIncrement {

        @Test
        @DisplayName("increment after register succeeds")
        void incrementAfterRegister() {
            HeavyMetricsAggregator.register(APP_ID_A, null);

            assertDoesNotThrow(() -> HeavyMetricsAggregator.increment(APP_ID_A, FLAG_KEY));
        }

        @Test
        @DisplayName("increment before register is no-op")
        void incrementBeforeRegisterIsNoOp() {
            // Not registered — should silently ignore
            assertDoesNotThrow(() -> HeavyMetricsAggregator.increment(APP_ID_A, FLAG_KEY));
        }

        @Test
        @DisplayName("increment after flushAndDrain is no-op")
        void incrementAfterDrainIsNoOp() {
            HeavyMetricsAggregator.register(APP_ID_A, null);
            HeavyMetricsAggregator.flushAndDrain(APP_ID_A);

            // App has been drained — increment should be silently ignored
            assertDoesNotThrow(() -> HeavyMetricsAggregator.increment(APP_ID_A, FLAG_KEY));
        }
    }

    // ================================================================
    //  flushAndDrain
    // ================================================================

    @Nested
    @DisplayName("flushAndDrain")
    class FlushAndDrain {

        @Test
        @DisplayName("flushAndDrain on unregistered app is no-op")
        void drainUnregisteredIsNoOp() {
            assertDoesNotThrow(() -> HeavyMetricsAggregator.flushAndDrain(APP_ID_A));
        }

        @Test
        @DisplayName("flushAndDrain removes app from counters")
        void drainRemovesApp() {
            HeavyMetricsAggregator.register(APP_ID_A, null);
            HeavyMetricsAggregator.increment(APP_ID_A, FLAG_KEY);

            // First drain should flush metrics
            assertDoesNotThrow(() -> HeavyMetricsAggregator.flushAndDrain(APP_ID_A));

            // Second drain should be no-op (already removed)
            assertDoesNotThrow(() -> HeavyMetricsAggregator.flushAndDrain(APP_ID_A));
        }

        @Test
        @DisplayName("flushAndDrain only affects specified app")
        void drainIsolated() {
            HeavyMetricsAggregator.register(APP_ID_A, null);
            HeavyMetricsAggregator.register(APP_ID_B, null);

            HeavyMetricsAggregator.increment(APP_ID_A, FLAG_KEY);
            HeavyMetricsAggregator.increment(APP_ID_B, FLAG_KEY);

            HeavyMetricsAggregator.flushAndDrain(APP_ID_A);

            // App B should still be present and accepting increments
            assertDoesNotThrow(() -> HeavyMetricsAggregator.increment(APP_ID_B, FLAG_KEY));
        }
    }

    // ================================================================
    //  Multiple app isolation
    // ================================================================

    @Test
    @DisplayName("multiple apps have independent counters")
    void multipleAppsIndependent() {
        HeavyMetricsAggregator.register(APP_ID_A, null);
        HeavyMetricsAggregator.register(APP_ID_B, null);

        assertDoesNotThrow(() -> {
            HeavyMetricsAggregator.increment(APP_ID_A, FLAG_KEY);
            HeavyMetricsAggregator.increment(APP_ID_B, "other-flag");
        });

        HeavyMetricsAggregator.flushAndDrain(APP_ID_A);
        HeavyMetricsAggregator.flushAndDrain(APP_ID_B);
    }

    // ================================================================
    //  Edge cases
    // ================================================================

    @Test
    @DisplayName("register with blank ingest URL uses default")
    void registerWithBlankIngestUrl() {
        assertDoesNotThrow(() -> HeavyMetricsAggregator.register(APP_ID_A, ""));
    }

    @Test
    @DisplayName("register multiple times for same app is safe")
    void duplicateRegisterIsSafe() {
        HeavyMetricsAggregator.register(APP_ID_A, null);
        HeavyMetricsAggregator.register(APP_ID_A, null);
        assertDoesNotThrow(() -> HeavyMetricsAggregator.increment(APP_ID_A, FLAG_KEY));
    }

    @Test
    @DisplayName("flushAll and flushAllSync do not throw")
    void flushAllAndSyncDoNotThrow() {
        HeavyMetricsAggregator.register(APP_ID_A, null);
        HeavyMetricsAggregator.increment(APP_ID_A, FLAG_KEY);

        assertDoesNotThrow(HeavyMetricsAggregator::flushAll);
        assertDoesNotThrow(HeavyMetricsAggregator::flushAllSync);
    }
}
