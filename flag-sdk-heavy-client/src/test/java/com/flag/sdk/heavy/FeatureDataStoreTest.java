package com.flag.sdk.heavy;

import com.flag.common.dto.EvaluateResponse;
import com.flag.common.model.FlagConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FeatureDataStore}.
 *
 * <p>Pure local cache tests — no network dependencies.
 * Covers evaluate/updateAll/updateSingle/mergeSingle/remove/clear/size.</p>
 */
class FeatureDataStoreTest {

    private static final String APP_ID = "test-app";
    private static final String FLAG_KEY_A = "flag-a";

    private FeatureDataStore store;

    @BeforeEach
    void setUp() {
        store = new FeatureDataStore(APP_ID, "http://localhost:9999", new ObjectMapper());
    }

    // ================================================================
    //  Cache size / queries
    // ================================================================

    @Test
    @DisplayName("new store should be empty")
    void emptyOnCreation() {
        assertEquals(0, store.size());
        assertNull(store.get(FLAG_KEY_A));
    }

    // ================================================================
    //  updateAll
    // ================================================================

    @Test
    @DisplayName("updateAll replaces entire cache")
    void updateAllReplacesCache() {
        store.updateAll(Map.of(
                "flag-a", cachedFlag("flag-a", true, 1),
                "flag-b", cachedFlag("flag-b", false, 1)
        ));

        assertEquals(2, store.size());
        assertNotNull(store.get("flag-a"));
        assertNotNull(store.get("flag-b"));
    }

    @Test
    @DisplayName("updateAll with empty map clears cache")
    void updateAllEmpty() {
        store.updateAll(Map.of("flag-a", cachedFlag("flag-a", true, 1)));
        assertEquals(1, store.size());

        store.updateAll(Map.of());
        assertEquals(0, store.size());
    }

    // ================================================================
    //  updateSingle
    // ================================================================

    @Test
    @DisplayName("updateSingle adds new entry")
    void updateSingleAddsNew() {
        store.updateAll(Map.of("flag-a", cachedFlag("flag-a", true, 1)));
        store.updateSingle("flag-b", cachedFlag("flag-b", false, 1));

        assertEquals(2, store.size());
        assertEquals("flag-b", store.get("flag-b").flagKey());
    }

    @Test
    @DisplayName("updateSingle overwrites existing entry")
    void updateSingleOverwrites() {
        store.updateAll(Map.of("flag-a", cachedFlag("flag-a", true, 1)));
        store.updateSingle("flag-a", cachedFlag("flag-a", false, 2));

        assertEquals(1, store.size());
        assertFalse(store.get("flag-a").flagConfig().isGlobalEnabled());
        assertEquals(2, store.get("flag-a").version());
    }

    // ================================================================
    //  mergeSingle
    // ================================================================

    @Test
    @DisplayName("mergeSingle creates new entry when absent")
    void mergeSingleCreatesNew() {
        store.mergeSingle("flag-a", existing ->
                new FeatureDataStore.CachedFlag("flag-a", "Flag A",
                        FlagConfig.builder().flagKey("flag-a").globalEnabled(true).build(), 1));

        assertEquals(1, store.size());
        assertTrue(store.get("flag-a").flagConfig().isGlobalEnabled());
    }

    @Test
    @DisplayName("mergeSingle updates existing entry via merger")
    void mergeSingleUpdatesExisting() {
        store.updateAll(Map.of("flag-a", cachedFlag("flag-a", true, 1)));

        store.mergeSingle("flag-a", existing ->
                new FeatureDataStore.CachedFlag(
                        existing.flagKey(), existing.name(), existing.flagConfig(),
                        existing.version() + 1));

        assertEquals(2, store.get("flag-a").version());
    }

    @Test
    @DisplayName("mergeSingle with null return keeps cache unchanged")
    void mergeSingleNullKeepsUnchanged() {
        store.updateAll(Map.of("flag-a", cachedFlag("flag-a", true, 1)));
        store.mergeSingle("flag-a", existing -> null);

        assertEquals(1, store.size());
        assertEquals(1, store.get("flag-a").version());
    }

    // ================================================================
    //  remove
    // ================================================================

    @Test
    @DisplayName("remove deletes existing entry")
    void removeExisting() {
        store.updateAll(Map.of(
                "flag-a", cachedFlag("flag-a", true, 1),
                "flag-b", cachedFlag("flag-b", false, 1)
        ));

        store.remove("flag-a");

        assertEquals(1, store.size());
        assertNull(store.get("flag-a"));
        assertNotNull(store.get("flag-b"));
    }

    @Test
    @DisplayName("remove non-existent key is no-op")
    void removeNonExistent() {
        store.updateAll(Map.of("flag-a", cachedFlag("flag-a", true, 1)));
        store.remove("non-existent");

        assertEquals(1, store.size());
    }

    // ================================================================
    //  clear
    // ================================================================

    @Test
    @DisplayName("clear empties all entries")
    void clearEmptiesAll() {
        store.updateAll(Map.of(
                "flag-a", cachedFlag("flag-a", true, 1),
                "flag-b", cachedFlag("flag-b", false, 1)
        ));

        store.clear();
        assertEquals(0, store.size());
    }

    // ================================================================
    //  evaluate
    // ================================================================

    @Test
    @DisplayName("evaluate returns null on cache miss")
    void evaluateCacheMissReturnsNull() {
        assertNull(store.evaluate("unknown-flag", "user-1", null));
    }

    @Test
    @DisplayName("evaluate returns response on cache hit (no rules = defaultServeValue)")
    void evaluateCacheHit() {
        FlagConfig config = FlagConfig.builder()
                .appId(APP_ID)
                .flagKey(FLAG_KEY_A)
                .globalEnabled(true)
                .defaultServeValue(true)
                .rules(List.of())
                .build();
        store.updateAll(Map.of(FLAG_KEY_A,
                new FeatureDataStore.CachedFlag(FLAG_KEY_A, "Flag A", config, 1)));

        EvaluateResponse result = store.evaluate(FLAG_KEY_A, "user-1", null);
        assertNotNull(result);
        assertTrue(result.isEnabled());
    }

    @Test
    @DisplayName("evaluate returns null when CachedFlag has null FlagConfig")
    void evaluateNullConfigReturnsNull() {
        store.updateAll(Map.of(FLAG_KEY_A,
                new FeatureDataStore.CachedFlag(FLAG_KEY_A, "Flag A", null, 1)));

        assertNull(store.evaluate(FLAG_KEY_A, "user-1", Map.of()));
    }

    @Test
    @DisplayName("evaluate passes userId and attributes to RuleEngine")
    void evaluatePassesContext() {
        FlagConfig config = FlagConfig.builder()
                .appId(APP_ID)
                .flagKey(FLAG_KEY_A)
                .globalEnabled(true)
                .defaultServeValue(true)
                .rules(List.of())
                .build();
        store.updateAll(Map.of(FLAG_KEY_A,
                new FeatureDataStore.CachedFlag(FLAG_KEY_A, "Flag A", config, 1)));

        EvaluateResponse result = store.evaluate(FLAG_KEY_A, "user-42", Map.of("country", "US"));
        assertNotNull(result);
    }

    // ================================================================
    //  Helper
    // ================================================================

    private static FeatureDataStore.CachedFlag cachedFlag(String flagKey, boolean enabled, int version) {
        return new FeatureDataStore.CachedFlag(
                flagKey,
                "Flag " + flagKey,
                FlagConfig.builder()
                        .flagKey(flagKey)
                        .globalEnabled(enabled)
                        .defaultServeValue(enabled)
                        .rules(List.of())
                        .build(),
                version
        );
    }
}
