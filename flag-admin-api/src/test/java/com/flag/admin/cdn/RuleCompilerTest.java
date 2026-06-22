package com.flag.admin.cdn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 * The {@code @Value} constructor parameter is satisfied by passing
 * the temp path directly.</p>
 */
class RuleCompilerTest {

    private Path tempCdnRoot;
    private RuleCompiler compiler;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws IOException {
        tempCdnRoot = Files.createTempDirectory("flag-cdn-test-");
        mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        compiler = new RuleCompiler(tempCdnRoot.toString(), mapper);
    }

    @AfterEach
    void tearDown() throws IOException {
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
    //  publishSnapshot
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

            Path rulesFile = tempCdnRoot.resolve("rules.1.json");
            assertThat(rulesFile).exists().isRegularFile();

            Path manifestFile = tempCdnRoot.resolve("manifest.json");
            assertThat(manifestFile).exists().isRegularFile();

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
    //  getCdnRoot / getCurrentVersion
    // ================================================================

    @Test
    @DisplayName("getCdnRoot returns the configured root path")
    void cdnRoot() {
        assertThat(compiler.getCdnRoot()).isEqualTo(tempCdnRoot);
    }

    @Test
    @DisplayName("getCurrentVersion returns 0 before any publish")
    void initialVersion() {
        assertThat(compiler.getCurrentVersion()).isZero();
    }
}
