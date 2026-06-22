package com.flag.eval.rule;

import com.flag.common.model.Condition;
import com.flag.common.model.EvaluationRule;
import com.flag.common.model.FlagConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-level tests for {@link RuleCompiler}.
 *
 * <p>Uses a real temp directory as the CDN root — no mocking of file I/O.
 * The Spring {@code @Value} constructor parameter is satisfied by passing
 * the temp path directly.</p>
 */
class RuleCompilerTest {

    private Path tempCdnRoot;
    private RuleCompiler compiler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws IOException {
        tempCdnRoot = Files.createTempDirectory("flag-cdn-test-");
        mapper = new ObjectMapper();
        compiler = new RuleCompiler(tempCdnRoot.toString(), mapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up temp directory
        try (var walk = Files.walk(tempCdnRoot)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    // ================================================================
    //  filterClientSafeFlags
    // ================================================================

    @Nested
    @DisplayName("filterClientSafeFlags")
    class Filtering {

        @Test
        @DisplayName("keeps flags with well-known client-safe keys")
        void keepsWellKnownKeys() {
            Map<String, FlagConfig> all = Map.of(
                    "new-ui-portal", flagConfig("new-ui-portal", true),
                    "dark-mode-v2", flagConfig("dark-mode-v2", false)
            );

            Map<String, ClientFlagMetadata> result = compiler.filterClientSafeFlags(all);

            assertThat(result).hasSize(2)
                    .containsKey("new-ui-portal")
                    .containsKey("dark-mode-v2");
        }

        @Test
        @DisplayName("keeps flags with public- prefix")
        void keepsPublicPrefix() {
            Map<String, FlagConfig> all = Map.of(
                    "public-search", flagConfig("public-search", true),
                    "public-pricing", flagConfig("public-pricing", false)
            );

            Map<String, ClientFlagMetadata> result = compiler.filterClientSafeFlags(all);

            assertThat(result).hasSize(2)
                    .containsKey("public-search")
                    .containsKey("public-pricing");
        }

        @Test
        @DisplayName("filters out server-only flags")
        void filtersServerOnly() {
            FlagConfig serverOnly = FlagConfig.builder()
                    .flagKey("payment-risk-control")
                    .globalEnabled(true)
                    .defaultServeValue(false)
                    .rules(List.of())
                    .build();

            Map<String, FlagConfig> all = Map.of(
                    "new-ui-portal", flagConfig("new-ui-portal", true),
                    "payment-risk-control", serverOnly
            );

            Map<String, ClientFlagMetadata> result = compiler.filterClientSafeFlags(all);

            assertThat(result).hasSize(1)
                    .containsOnlyKeys("new-ui-portal");
        }

        @Test
        @DisplayName("returns empty map when no flags are client-safe")
        void emptyWhenNoneSafe() {
            Map<String, FlagConfig> all = Map.of(
                    "internal-admin", flagConfig("internal-admin", true),
                    "payment-gateway", flagConfig("payment-gateway", false)
            );

            Map<String, ClientFlagMetadata> result = compiler.filterClientSafeFlags(all);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("returns empty map when input is empty")
        void emptyInput() {
            assertThat(compiler.filterClientSafeFlags(Map.of())).isEmpty();
        }
    }

    // ================================================================
    //  publishSnapshot / onRuleChanged
    // ================================================================

    @Nested
    @DisplayName("publishSnapshot")
    class Publishing {

        @Test
        @DisplayName("publishes rules file and manifest")
        void publishesFiles() {
            Map<String, ClientFlagMetadata> flags = Map.of(
                    "new-ui-portal", new ClientFlagMetadata("new-ui-portal", true, true, List.of())
            );

            compiler.publishSnapshot(flags);

            // Verify rules file exists
            Path rulesFile = tempCdnRoot.resolve("rules.1.json");
            assertThat(rulesFile).exists().isRegularFile();

            // Verify manifest exists
            Path manifestFile = tempCdnRoot.resolve("manifest.json");
            assertThat(manifestFile).exists().isRegularFile();

            // Verify manifest content (Jackson INDENT_OUTPUT uses " : " spacing)
            assertThat(manifestFile).content()
                    .contains("\"latest_file\" : \"rules.1.json\"");
        }

        @Test
        @DisplayName("increments version on each publish")
        void incrementsVersion() {
            compiler.publishSnapshot(Map.of(
                    "flag-a", new ClientFlagMetadata("flag-a", true, true, List.of())
            ));
            compiler.publishSnapshot(Map.of(
                    "flag-b", new ClientFlagMetadata("flag-b", false, false, List.of())
            ));

            assertThat(tempCdnRoot.resolve("rules.1.json")).exists();
            assertThat(tempCdnRoot.resolve("rules.2.json")).exists();
            assertThat(tempCdnRoot.resolve("rules.3.json")).doesNotExist();

            assertThat(compiler.getCurrentVersion()).isEqualTo(2);
        }

        @Test
        @DisplayName("empty flags still publish manifest")
        void emptyFlagsStillPublishes() {
            compiler.publishSnapshot(Map.of());

            assertThat(tempCdnRoot.resolve("rules.1.json")).exists();
            assertThat(tempCdnRoot.resolve("manifest.json")).exists();
        }
    }

    // ================================================================
    //  onRuleChanged (full end-to-end)
    // ================================================================

    @Nested
    @DisplayName("onRuleChanged")
    class EndToEnd {

        @Test
        @DisplayName("filters and publishes in one call")
        void fullPipeline() {
            FlagConfig safeFlag = FlagConfig.builder()
                    .flagKey("new-ui-portal")
                    .globalEnabled(true)
                    .defaultServeValue(true)
                    .rules(List.of())
                    .build();
            FlagConfig unsafeFlag = FlagConfig.builder()
                    .flagKey("payment-risk")
                    .globalEnabled(true)
                    .defaultServeValue(false)
                    .rules(List.of())
                    .build();

            compiler.onRuleChanged(Map.of(
                    "new-ui-portal", safeFlag,
                    "payment-risk", unsafeFlag
            ));

            // Only safe flag in rules file
            Path rulesFile = tempCdnRoot.resolve("rules.1.json");
            assertThat(rulesFile).exists();
            assertThat(rulesFile).content()
                    .contains("new-ui-portal")
                    .doesNotContain("payment-risk");
        }

        @Test
        @DisplayName("empty input produces no error")
        void emptyInput() {
            compiler.onRuleChanged(Map.of());
            // Should not throw — publishes empty snapshot
            assertThat(tempCdnRoot.resolve("rules.1.json")).exists();
        }
    }

    // ================================================================
    //  Helpers
    // ================================================================

    private static FlagConfig flagConfig(String key, boolean enabled) {
        return FlagConfig.builder()
                .flagKey(key)
                .globalEnabled(enabled)
                .defaultServeValue(enabled)
                .rules(List.of())
                .build();
    }
}
